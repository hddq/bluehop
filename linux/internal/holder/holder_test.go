package holder

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/hddq/bluehop/linux/internal/bluez"
	"github.com/hddq/bluehop/linux/internal/peers"
	"github.com/hddq/bluehop/linux/internal/protocol"
)

type fakeBluetoothBackend struct {
	mu              sync.Mutex
	devices         []ManagedDevice
	listErr         error
	disconnectErr   error
	disconnectCalls []string
	onDisconnect    func(addr string)
}

func (f *fakeBluetoothBackend) ListManagedDevices(context.Context) ([]ManagedDevice, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.listErr != nil {
		return nil, f.listErr
	}
	out := make([]ManagedDevice, len(f.devices))
	copy(out, f.devices)
	return out, nil
}

func (f *fakeBluetoothBackend) Disconnect(_ context.Context, address string) error {
	f.mu.Lock()
	f.disconnectCalls = append(f.disconnectCalls, address)
	err := f.disconnectErr
	cb := f.onDisconnect
	f.mu.Unlock()

	if cb != nil {
		cb(address)
	}
	return err
}

func (f *fakeBluetoothBackend) setConnected(address string, connected bool) {
	f.mu.Lock()
	defer f.mu.Unlock()
	for i := range f.devices {
		if strings.EqualFold(f.devices[i].Address, address) {
			f.devices[i].Connected = connected
		}
	}
}

func (f *fakeBluetoothBackend) calls() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	out := make([]string, len(f.disconnectCalls))
	copy(out, f.disconnectCalls)
	return out
}

func makeTestPSK(seed byte) []byte {
	b := make([]byte, 32)
	for i := range b {
		b[i] = seed + byte(i)
	}
	return b
}

func postJSON(t *testing.T, url string, body any) *http.Response {
	t.Helper()
	var payload []byte
	switch v := body.(type) {
	case string:
		payload = []byte(v)
	default:
		var err error
		payload, err = json.Marshal(v)
		if err != nil {
			t.Fatalf("marshal request: %v", err)
		}
	}
	resp, err := http.Post(url, "application/json; charset=utf-8", bytes.NewReader(payload))
	if err != nil {
		t.Fatalf("POST %s: %v", url, err)
	}
	return resp
}

func decodeGrabResponse(t *testing.T, resp *http.Response) protocol.GrabResponse {
	t.Helper()
	defer resp.Body.Close()
	var out protocol.GrabResponse
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		t.Fatalf("decode GrabResponse: %v", err)
	}
	return out
}

func TestStatusEndpoint(t *testing.T) {
	const testAddr = "AC:80:0A:CD:82:A1"
	const secondAddr = "11:22:33:44:55:66"
	fixedNow := time.Unix(1_760_000_000, 0)

	t.Run("lists multiple devices and prefers connected primary", func(t *testing.T) {
		backend := &fakeBluetoothBackend{
			devices: []ManagedDevice{
				{Address: testAddr, Name: "WH-1000XM4", Connected: false},
				{Address: secondAddr, Name: "Keychron K2", Connected: true},
			},
		}
		srv := NewServer(Config{
			Identity: peers.Identity{DeviceID: "linux-1", DeviceName: "nix-client"},
			Backend:  backend,
			Now:      func() time.Time { return fixedNow },
		})

		port, err := srv.Start("127.0.0.1:0")
		if err != nil {
			t.Fatalf("Start() error = %v", err)
		}
		defer func() { _ = srv.Stop(context.Background()) }()

		resp, err := http.Get(fmt.Sprintf("http://127.0.0.1:%d/status", port))
		if err != nil {
			t.Fatalf("GET /status: %v", err)
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("status = %d, want 200", resp.StatusCode)
		}

		var status StatusResponse
		if err := json.NewDecoder(resp.Body).Decode(&status); err != nil {
			t.Fatalf("decode StatusResponse: %v", err)
		}
		if status.Version != protocol.ProtocolVersion || status.Port != port {
			t.Fatalf("unexpected version/port: %+v", status)
		}
		if status.DeviceAddress == nil || *status.DeviceAddress != secondAddr || !status.Connected {
			t.Fatalf("expected connected primary %s, got %+v", secondAddr, status)
		}
		if status.HolderID == nil || *status.HolderID != "linux-1" {
			t.Fatalf("unexpected holderId: %+v", status.HolderID)
		}
		if status.HolderName == nil || *status.HolderName != "nix-client" {
			t.Fatalf("unexpected holderName: %+v", status.HolderName)
		}
		if len(status.Devices) != 2 || status.Devices[0].Connected || !status.Devices[1].Connected {
			t.Fatalf("unexpected devices slice: %+v", status.Devices)
		}
	})

	t.Run("empty devices encodes non-null JSON array", func(t *testing.T) {
		srv := NewServer(Config{
			Identity: peers.Identity{DeviceID: "linux-1", DeviceName: "nix-client"},
			Backend:  &fakeBluetoothBackend{},
		})
		ts := httptest.NewServer(srv.Handler())
		defer ts.Close()

		resp, err := http.Get(ts.URL + "/status")
		if err != nil {
			t.Fatalf("GET /status: %v", err)
		}
		defer resp.Body.Close()

		var raw map[string]any
		if err := json.NewDecoder(resp.Body).Decode(&raw); err != nil {
			t.Fatalf("decode raw JSON: %v", err)
		}
		devices, ok := raw["devices"].([]any)
		if !ok || len(devices) != 0 {
			t.Fatalf("expected empty JSON array for devices, got %#v", raw["devices"])
		}
		if raw["deviceAddress"] != nil || raw["connected"] != false {
			t.Fatalf("expected null deviceAddress and connected=false, got %#v", raw)
		}
	})
}

func TestReleaseAuthenticationAndConflicts(t *testing.T) {
	const testAddr = "AC:80:0A:CD:82:A1"
	const secondAddr = "11:22:33:44:55:66"
	fixedNow := time.Unix(1_760_000_000, 0)
	peerAPSK := makeTestPSK(11)
	peerBPSK := makeTestPSK(77)

	backend := &fakeBluetoothBackend{
		devices: []ManagedDevice{
			{Address: testAddr, Name: "WH-1000XM4", Connected: true},
			{Address: secondAddr, Name: "Keychron K2", Connected: false},
		},
	}
	verifier := protocol.NewProtocolVerifier(protocol.DefaultMaxSkewSeconds, nil, func() int64 { return fixedNow.Unix() })
	srv := NewServer(Config{
		Identity: peers.Identity{DeviceID: "linux-1", DeviceName: "nix-client"},
		Backend:  backend,
		PeerPSKLookup: func(id string) ([]byte, error) {
			switch id {
			case "peer-a":
				return peerAPSK, nil
			case "peer-b":
				return peerBPSK, nil
			default:
				return nil, os.ErrNotExist
			}
		},
		Verifier: verifier,
		Now:      func() time.Time { return fixedNow },
	})
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	t.Run("malformed json returns 400", func(t *testing.T) {
		resp := postJSON(t, ts.URL+"/release", "{not-json")
		if resp.StatusCode != http.StatusBadRequest {
			t.Fatalf("status = %d, want 400", resp.StatusCode)
		}
		body := decodeGrabResponse(t, resp)
		if body.Status != protocol.StatusError {
			t.Fatalf("body status = %s, want error", body.Status)
		}
	})

	t.Run("unknown requester returns 401 unsigned UNKNOWN_REQUESTER", func(t *testing.T) {
		req, _ := protocol.SignRequest(peerAPSK, protocol.GrabRequest{
			DeviceAddress:     testAddr,
			RequesterID:       "stranger",
			TimestampEpochSec: fixedNow.Unix(),
			Nonce:             "aaaa1111bbbb2222cccc3333dddd4444",
		})
		resp := postJSON(t, ts.URL+"/release", req)
		if resp.StatusCode != http.StatusUnauthorized {
			t.Fatalf("status = %d, want 401", resp.StatusCode)
		}
		body := decodeGrabResponse(t, resp)
		if body.Status != protocol.StatusRejected || body.Message != "UNKNOWN_REQUESTER" || body.Signature != "" {
			t.Fatalf("unexpected body: %+v", body)
		}
	})

	t.Run("wrong psk returns signed 401 INVALID_SIGNATURE", func(t *testing.T) {
		req, _ := protocol.SignRequest(peerBPSK, protocol.GrabRequest{
			DeviceAddress:     testAddr,
			RequesterID:       "peer-a",
			TimestampEpochSec: fixedNow.Unix(),
			Nonce:             "bbbb1111cccc2222dddd3333eeee4444",
		})
		resp := postJSON(t, ts.URL+"/release", req)
		if resp.StatusCode != http.StatusUnauthorized {
			t.Fatalf("status = %d, want 401", resp.StatusCode)
		}
		body := decodeGrabResponse(t, resp)
		if body.Message != string(protocol.ReasonInvalidSignature) {
			t.Fatalf("message = %q, want INVALID_SIGNATURE", body.Message)
		}
		if res := verifier.VerifyResponse(body, peerAPSK, req.Nonce); !res.Valid {
			t.Fatalf("expected error response signed with peer-a PSK, got %+v", res)
		}
	})

	t.Run("unmanaged device returns 409", func(t *testing.T) {
		req, _ := protocol.SignRequest(peerAPSK, protocol.GrabRequest{
			DeviceAddress:     "99:88:77:66:55:44",
			RequesterID:       "peer-a",
			TimestampEpochSec: fixedNow.Unix(),
			Nonce:             "cccc1111dddd2222eeee3333ffff4444",
		})
		resp := postJSON(t, ts.URL+"/release", req)
		if resp.StatusCode != http.StatusConflict {
			t.Fatalf("status = %d, want 409", resp.StatusCode)
		}
		body := decodeGrabResponse(t, resp)
		if body.Status != protocol.StatusRejected {
			t.Fatalf("unexpected status: %+v", body)
		}
	})

	t.Run("already disconnected device returns 409", func(t *testing.T) {
		req, _ := protocol.SignRequest(peerAPSK, protocol.GrabRequest{
			DeviceAddress:     secondAddr,
			RequesterID:       "peer-a",
			TimestampEpochSec: fixedNow.Unix(),
			Nonce:             "dddd1111eeee2222ffff333300004444",
		})
		resp := postJSON(t, ts.URL+"/release", req)
		if resp.StatusCode != http.StatusConflict {
			t.Fatalf("status = %d, want 409", resp.StatusCode)
		}
		_ = resp.Body.Close()
	})

	if calls := backend.calls(); len(calls) != 0 {
		t.Fatalf("expected zero disconnect calls on rejected requests, got %v", calls)
	}
}

func TestReleaseDisconnectWaitRetryTimeoutAndReplay(t *testing.T) {
	const testAddr = "AC:80:0A:CD:82:A1"
	fixedNow := time.Unix(1_760_000_000, 0)
	psk := makeTestPSK(5)

	t.Run("waits for disconnect confirmation and blocks replay", func(t *testing.T) {
		disconnectedSignal := make(chan struct{}, 1)
		backend := &fakeBluetoothBackend{
			devices: []ManagedDevice{{Address: testAddr, Name: "WH-1000XM4", Connected: true}},
			onDisconnect: func(string) {
				select {
				case disconnectedSignal <- struct{}{}:
				default:
				}
			},
		}
		verifier := protocol.NewProtocolVerifier(protocol.DefaultMaxSkewSeconds, nil, func() int64 { return fixedNow.Unix() })
		srv := NewServer(Config{
			Identity:             peers.Identity{DeviceID: "linux-1", DeviceName: "nix-client"},
			Backend:              backend,
			PeerPSKLookup:        func(string) ([]byte, error) { return psk, nil },
			Verifier:             verifier,
			Now:                  func() time.Time { return fixedNow },
			DisconnectRetryDelay: 2 * time.Second,
			DisconnectTimeout:    5 * time.Second,
			PollInterval:         20 * time.Millisecond,
		})
		ts := httptest.NewServer(srv.Handler())
		defer ts.Close()

		req, err := protocol.SignRequest(psk, protocol.GrabRequest{
			DeviceAddress:     strings.ToLower(testAddr),
			RequesterID:       "phone-1",
			TimestampEpochSec: fixedNow.Unix(),
			Nonce:             "abcdef0123456789abcdef0123456789",
		})
		if err != nil {
			t.Fatalf("SignRequest: %v", err)
		}

		type result struct {
			status int
			body   protocol.GrabResponse
		}
		resCh := make(chan result, 1)
		go func() {
			resp := postJSON(t, ts.URL+"/release", req)
			resCh <- result{status: resp.StatusCode, body: decodeGrabResponse(t, resp)}
		}()

		<-disconnectedSignal
		select {
		case r := <-resCh:
			t.Fatalf("server responded before disconnect confirmation: %+v", r)
		case <-time.After(80 * time.Millisecond):
		}

		backend.setConnected(testAddr, false)

		res := <-resCh
		if res.status != http.StatusOK || res.body.Status != protocol.StatusOK {
			t.Fatalf("unexpected release result: %+v", res)
		}
		if v := verifier.VerifyResponse(res.body, psk, req.Nonce); !v.Valid {
			t.Fatalf("VerifyResponse failed: %+v", v)
		}

		replayResp := postJSON(t, ts.URL+"/release", req)
		if replayResp.StatusCode != http.StatusUnauthorized {
			t.Fatalf("replay status = %d, want 401", replayResp.StatusCode)
		}
		replayBody := decodeGrabResponse(t, replayResp)
		if replayBody.Message != string(protocol.ReasonReplayedNonce) {
			t.Fatalf("replay message = %q, want REPLAYED_NONCE", replayBody.Message)
		}
	})

	t.Run("retries disconnect and returns 504 on timeout", func(t *testing.T) {
		backend := &fakeBluetoothBackend{
			devices: []ManagedDevice{{Address: testAddr, Name: "WH-1000XM4", Connected: true}},
		}
		srv := NewServer(Config{
			Identity:             peers.Identity{DeviceID: "linux-1", DeviceName: "nix-client"},
			Backend:              backend,
			PeerPSKLookup:        func(string) ([]byte, error) { return psk, nil },
			Now:                  func() time.Time { return fixedNow },
			DisconnectRetryDelay: 30 * time.Millisecond,
			DisconnectTimeout:    90 * time.Millisecond,
			PollInterval:         15 * time.Millisecond,
		})
		ts := httptest.NewServer(srv.Handler())
		defer ts.Close()

		req, _ := protocol.SignRequest(psk, protocol.GrabRequest{
			DeviceAddress:     testAddr,
			RequesterID:       "phone-1",
			TimestampEpochSec: fixedNow.Unix(),
			Nonce:             "11112222333344445555666677778888",
		})
		resp := postJSON(t, ts.URL+"/release", req)
		if resp.StatusCode != http.StatusGatewayTimeout {
			t.Fatalf("status = %d, want 504", resp.StatusCode)
		}
		body := decodeGrabResponse(t, resp)
		if body.Status != protocol.StatusError {
			t.Fatalf("unexpected body: %+v", body)
		}
		if len(backend.calls()) < 2 {
			t.Fatalf("expected initial disconnect + retry, got calls=%v", backend.calls())
		}
	})
}

func TestStartPortFallbackAndBluezBackend(t *testing.T) {
	occupied, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("Listen: %v", err)
	}
	defer occupied.Close()

	srv := NewServer(Config{
		Identity: peers.Identity{DeviceID: "linux-1", DeviceName: "nix-client"},
		Backend:  &fakeBluetoothBackend{},
	})
	port, err := srv.Start(occupied.Addr().String())
	if err != nil {
		t.Fatalf("Start() with occupied port error = %v", err)
	}
	defer func() { _ = srv.Stop(context.Background()) }()

	if port == 0 || port == occupied.Addr().(*net.TCPAddr).Port {
		t.Fatalf("expected fallback ephemeral port, got %d", port)
	}

	ctrl := &stubBluezCtrl{
		devices: []bluez.Device{
			{Address: "AA:BB:CC:DD:EE:01", Alias: "Keyboard", Connected: true},
		},
		disconnectErr: fmt.Errorf("%w: Keyboard", bluez.ErrNotConnected),
	}
	bb := NewBluezBackend(ctrl)
	list, err := bb.ListManagedDevices(context.Background())
	if err != nil || len(list) != 1 || list[0].Name != "Keyboard" || !list[0].Connected {
		t.Fatalf("unexpected ListManagedDevices: (%+v, %v)", list, err)
	}
	if err := bb.Disconnect(context.Background(), "AA:BB:CC:DD:EE:01"); err != nil {
		t.Fatalf("expected ErrNotConnected to be treated as nil, got %v", err)
	}
	ctrl.disconnectErr = errors.New("dbus boom")
	if err := bb.Disconnect(context.Background(), "AA:BB:CC:DD:EE:01"); err == nil {
		t.Fatalf("expected non-ErrNotConnected error to propagate")
	}
}

type stubBluezCtrl struct {
	devices       []bluez.Device
	disconnectErr error
}

func (s *stubBluezCtrl) ListDevices(context.Context) ([]bluez.Device, error) {
	return s.devices, nil
}

func (s *stubBluezCtrl) FindDevice(context.Context, string) (bluez.Device, error) {
	return s.devices[0], nil
}

func (s *stubBluezCtrl) Connect(context.Context, string) (bluez.Device, error) {
	return s.devices[0], nil
}

func (s *stubBluezCtrl) Disconnect(context.Context, string) (bluez.Device, error) {
	return s.devices[0], s.disconnectErr
}

func (s *stubBluezCtrl) Close() error { return nil }
