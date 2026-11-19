package grab

import (
	"context"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/hddq/bluehop/linux/internal/bluez"
	"github.com/hddq/bluehop/linux/internal/holder"
	"github.com/hddq/bluehop/linux/internal/mdns"
	"github.com/hddq/bluehop/linux/internal/peers"
	"github.com/hddq/bluehop/linux/internal/protocol"
)

type fakeController struct {
	mu           sync.Mutex
	devices      []bluez.Device
	connectCalls []string
	connectErr   error
}

func (f *fakeController) ListDevices(context.Context) ([]bluez.Device, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	out := make([]bluez.Device, len(f.devices))
	copy(out, f.devices)
	return out, nil
}

func (f *fakeController) FindDevice(_ context.Context, query string) (bluez.Device, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	for _, d := range f.devices {
		if strings.EqualFold(d.Address, query) || strings.EqualFold(d.DisplayName(), query) {
			return d, nil
		}
	}
	return bluez.Device{}, fmt.Errorf("%w: %q", bluez.ErrDeviceNotFound, query)
}

func (f *fakeController) Connect(_ context.Context, query string) (bluez.Device, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.connectCalls = append(f.connectCalls, query)
	if f.connectErr != nil {
		return bluez.Device{}, f.connectErr
	}
	for i, d := range f.devices {
		if strings.EqualFold(d.Address, query) || strings.EqualFold(d.DisplayName(), query) {
			f.devices[i].Connected = true
			return f.devices[i], nil
		}
	}
	return bluez.Device{}, fmt.Errorf("%w: %q", bluez.ErrDeviceNotFound, query)
}

func (f *fakeController) Disconnect(context.Context, string) (bluez.Device, error) {
	return bluez.Device{}, nil
}

func (f *fakeController) Close() error { return nil }

type fakeBackend struct {
	mu      sync.Mutex
	devices []holder.ManagedDevice
}

func (b *fakeBackend) ListManagedDevices(context.Context) ([]holder.ManagedDevice, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	out := make([]holder.ManagedDevice, len(b.devices))
	copy(out, b.devices)
	return out, nil
}

func (b *fakeBackend) Disconnect(_ context.Context, address string) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	for i, d := range b.devices {
		if strings.EqualFold(d.Address, address) {
			b.devices[i].Connected = false
		}
	}
	return nil
}

type staticDiscoverer struct {
	peers []mdns.DiscoveredPeer
	err   error
	calls int
	opts  mdns.DiscoverOptions
}

func (d *staticDiscoverer) Discover(_ context.Context, opts mdns.DiscoverOptions) ([]mdns.DiscoveredPeer, error) {
	d.calls++
	d.opts = opts
	if d.err != nil {
		return nil, d.err
	}
	return d.peers, nil
}

func TestHTTPClientWithHolderServer(t *testing.T) {
	psk := make([]byte, 32)
	for i := range psk {
		psk[i] = byte(i + 10)
	}

	backend := &fakeBackend{
		devices: []holder.ManagedDevice{
			{Address: "AC:80:0A:CD:82:A1", Name: "WH-1000XM4", Connected: true},
		},
	}
	holderSrv := holder.NewServer(holder.Config{
		Identity: peers.Identity{DeviceID: "phone-1", DeviceName: "motorola edge"},
		Backend:  backend,
		PeerPSKLookup: func(requesterID string) ([]byte, error) {
			if requesterID == "linux-1" {
				return psk, nil
			}
			return nil, errors.New("not found")
		},
		PollInterval: 10 * time.Millisecond,
	})
	port, err := holderSrv.Start("127.0.0.1:0")
	if err != nil {
		t.Fatalf("holderSrv.Start: %v", err)
	}
	defer func() {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		_ = holderSrv.Stop(ctx)
	}()

	client := NewHTTPClient(HTTPClientConfig{})
	ctx := context.Background()

	status, err := client.FetchStatus(ctx, "127.0.0.1", port)
	if err != nil {
		t.Fatalf("FetchStatus: %v", err)
	}
	if !status.Connected || status.DeviceAddress == nil || *status.DeviceAddress != "AC:80:0A:CD:82:A1" {
		t.Fatalf("unexpected status: %+v", status)
	}

	unknownOutcome := client.RequestRelease(ctx, "127.0.0.1", port, "AC:80:0A:CD:82:A1", "stranger", psk)
	if unknownOutcome.Status != ReleaseRejected || unknownOutcome.Message != "UNKNOWN_REQUESTER" {
		t.Fatalf("expected ReleaseRejected/UNKNOWN_REQUESTER, got %+v", unknownOutcome)
	}

	okOutcome := client.RequestRelease(ctx, "127.0.0.1", port, "AC:80:0A:CD:82:A1", "linux-1", psk)
	if okOutcome.Status != ReleaseOK {
		t.Fatalf("expected ReleaseOK, got %+v", okOutcome)
	}

	// Second release finds it disconnected -> 409 AlreadyDisconnected.
	alreadyOutcome := client.RequestRelease(ctx, "127.0.0.1", port, "AC:80:0A:CD:82:A1", "linux-1", psk)
	if alreadyOutcome.Status != ReleaseAlreadyDisconnected {
		t.Fatalf("expected ReleaseAlreadyDisconnected, got %+v", alreadyOutcome)
	}
}

func TestHTTPClientRejectsTamperedResponseSignature(t *testing.T) {
	psk := bytesRepeat(0x42, 32)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var req protocol.GrabRequest
		_ = json.NewDecoder(r.Body).Decode(&req)
		resp, _ := protocol.SignResponse(psk, protocol.GrabResponse{
			Version:           protocol.ProtocolVersion,
			Status:            protocol.StatusOK,
			DeviceAddress:     req.DeviceAddress,
			TimestampEpochSec: time.Now().Unix(),
			Nonce:             req.Nonce,
			Message:           "Disconnected",
		})
		resp.Signature = strings.Repeat("0", 64)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(resp)
	}))
	defer srv.Close()

	host, portStr, _ := net.SplitHostPort(strings.TrimPrefix(srv.URL, "http://"))
	port, _ := strconv.Atoi(portStr)

	client := NewHTTPClient(HTTPClientConfig{})
	outcome := client.RequestRelease(context.Background(), host, port, "AC:80:0A:CD:82:A1", "linux-1", psk)
	if outcome.Status != ReleaseFailed || !strings.Contains(outcome.Message, "INVALID_SIGNATURE") {
		t.Fatalf("expected INVALID_SIGNATURE failure, got %+v", outcome)
	}
}

func TestGrabFlows(t *testing.T) {
	psk := bytesRepeat(0x21, 32)
	pskHex := hex.EncodeToString(psk)
	pairedPeers := []peers.Peer{
		{PeerID: "phone-1", PeerName: "motorola edge", PSKHex: pskHex},
	}
	localID := peers.Identity{DeviceID: "linux-1", DeviceName: "nix-client"}

	t.Run("already connected locally skips mDNS and release", func(t *testing.T) {
		ctrl := &fakeController{
			devices: []bluez.Device{
				{Address: "AC:80:0A:CD:82:A1", Alias: "WH-1000XM4", Connected: true},
			},
		}
		disc := &staticDiscoverer{}
		res, err := Grab(context.Background(), Options{
			Target:     "WH-1000XM4",
			Identity:   localID,
			Peers:      pairedPeers,
			Controller: ctrl,
			Discoverer: disc,
		})
		if err != nil {
			t.Fatalf("Grab: %v", err)
		}
		if !res.AlreadyConnected || disc.calls != 0 || len(ctrl.connectCalls) != 0 {
			t.Fatalf("unexpected result/calls: res=%+v discCalls=%d connectCalls=%v", res, disc.calls, ctrl.connectCalls)
		}
	})

	t.Run("releases direct mDNS holder then connects locally", func(t *testing.T) {
		backend := &fakeBackend{
			devices: []holder.ManagedDevice{
				{Address: "AC:80:0A:CD:82:A1", Name: "WH-1000XM4", Connected: true},
			},
		}
		holderSrv := holder.NewServer(holder.Config{
			Identity: peers.Identity{DeviceID: "phone-1", DeviceName: "motorola edge"},
			Backend:  backend,
			PeerPSKLookup: func(id string) ([]byte, error) {
				if id == "linux-1" {
					return psk, nil
				}
				return nil, errors.New("not found")
			},
			PollInterval: 10 * time.Millisecond,
		})
		port, err := holderSrv.Start("127.0.0.1:0")
		if err != nil {
			t.Fatalf("holderSrv.Start: %v", err)
		}
		defer func() {
			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			defer cancel()
			_ = holderSrv.Stop(ctx)
		}()

		ctrl := &fakeController{
			devices: []bluez.Device{
				{Address: "AC:80:0A:CD:82:A1", Alias: "WH-1000XM4", Connected: false},
			},
		}
		disc := &staticDiscoverer{
			peers: []mdns.DiscoveredPeer{
				{Host: "127.0.0.1", Port: port, DeviceID: "phone-1", DisplayName: "motorola edge", PairingAvailable: true},
				{Host: "127.0.0.1", Port: 1, DeviceID: "unpaired-phone", DisplayName: "Stranger", ConnectedAddresses: []string{"AC:80:0A:CD:82:A1"}},
				{Host: "127.0.0.1", Port: port, DeviceID: "phone-1", DisplayName: "motorola edge", ConnectedAddresses: []string{"AC:80:0A:CD:82:A1"}},
			},
		}

		var stages []Stage
		res, err := Grab(context.Background(), Options{
			Target:     "WH-1000XM4",
			Identity:   localID,
			Peers:      pairedPeers,
			Controller: ctrl,
			Discoverer: disc,
			OnProgress: func(p Progress) {
				stages = append(stages, p.Stage)
			},
		})
		if err != nil {
			t.Fatalf("Grab: %v", err)
		}
		if res.ReleasedFrom != "motorola edge" || !res.Device.Connected {
			t.Fatalf("unexpected result: %+v", res)
		}
		if !reflect.DeepEqual(stages, []Stage{StageSearching, StageReleasing, StageConnecting}) {
			t.Fatalf("stages = %v", stages)
		}
		managed, _ := backend.ListManagedDevices(context.Background())
		if managed[0].Connected {
			t.Fatalf("expected holder backend device to be disconnected")
		}
	})

	t.Run("falls back to /status when mDNS connected list is empty", func(t *testing.T) {
		backend := &fakeBackend{
			devices: []holder.ManagedDevice{
				{Address: "AC:80:0A:CD:82:A1", Name: "WH-1000XM4", Connected: true},
			},
		}
		holderSrv := holder.NewServer(holder.Config{
			Identity: peers.Identity{DeviceID: "phone-1", DeviceName: "Moto Edge"},
			Backend:  backend,
			PeerPSKLookup: func(id string) ([]byte, error) {
				return psk, nil
			},
			PollInterval: 10 * time.Millisecond,
		})
		port, err := holderSrv.Start("127.0.0.1:0")
		if err != nil {
			t.Fatalf("holderSrv.Start: %v", err)
		}
		defer func() {
			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			defer cancel()
			_ = holderSrv.Stop(ctx)
		}()

		ctrl := &fakeController{
			devices: []bluez.Device{
				{Address: "AC:80:0A:CD:82:A1", Alias: "WH-1000XM4", Connected: false},
			},
		}
		disc := &staticDiscoverer{
			peers: []mdns.DiscoveredPeer{
				{Host: "127.0.0.1", Port: port, DeviceID: "phone-1", DisplayName: "motorola"},
			},
		}

		res, err := Grab(context.Background(), Options{
			Target:     "AC:80:0A:CD:82:A1",
			Identity:   localID,
			Peers:      pairedPeers,
			Controller: ctrl,
			Discoverer: disc,
		})
		if err != nil {
			t.Fatalf("Grab: %v", err)
		}
		if res.ReleasedFrom != "Moto Edge" || !res.Device.Connected {
			t.Fatalf("unexpected result: %+v", res)
		}
	})

	t.Run("proceeds to connect when holder already disconnected (409)", func(t *testing.T) {
		backend := &fakeBackend{
			devices: []holder.ManagedDevice{
				{Address: "AC:80:0A:CD:82:A1", Name: "WH-1000XM4", Connected: false},
			},
		}
		holderSrv := holder.NewServer(holder.Config{
			Identity:      peers.Identity{DeviceID: "phone-1", DeviceName: "motorola edge"},
			Backend:       backend,
			PeerPSKLookup: func(string) ([]byte, error) { return psk, nil },
		})
		port, err := holderSrv.Start("127.0.0.1:0")
		if err != nil {
			t.Fatalf("holderSrv.Start: %v", err)
		}
		defer func() {
			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			defer cancel()
			_ = holderSrv.Stop(ctx)
		}()

		ctrl := &fakeController{
			devices: []bluez.Device{
				{Address: "AC:80:0A:CD:82:A1", Alias: "WH-1000XM4", Connected: false},
			},
		}
		disc := &staticDiscoverer{
			peers: []mdns.DiscoveredPeer{
				{Host: "127.0.0.1", Port: port, DeviceID: "phone-1", DisplayName: "motorola edge", ConnectedAddresses: []string{"AC:80:0A:CD:82:A1"}},
			},
		}

		res, err := Grab(context.Background(), Options{
			Target:     "WH-1000XM4",
			Identity:   localID,
			Peers:      pairedPeers,
			Controller: ctrl,
			Discoverer: disc,
		})
		if err != nil {
			t.Fatalf("Grab: %v", err)
		}
		if res.ReleasedFrom != "" || !res.Device.Connected {
			t.Fatalf("unexpected result: %+v", res)
		}
	})

	t.Run("aborts before connecting if holder rejects requester", func(t *testing.T) {
		backend := &fakeBackend{
			devices: []holder.ManagedDevice{
				{Address: "AC:80:0A:CD:82:A1", Name: "WH-1000XM4", Connected: true},
			},
		}
		holderSrv := holder.NewServer(holder.Config{
			Identity:      peers.Identity{DeviceID: "phone-1", DeviceName: "motorola edge"},
			Backend:       backend,
			PeerPSKLookup: func(string) ([]byte, error) { return nil, errors.New("unknown") },
		})
		port, err := holderSrv.Start("127.0.0.1:0")
		if err != nil {
			t.Fatalf("holderSrv.Start: %v", err)
		}
		defer func() {
			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			defer cancel()
			_ = holderSrv.Stop(ctx)
		}()

		ctrl := &fakeController{
			devices: []bluez.Device{
				{Address: "AC:80:0A:CD:82:A1", Alias: "WH-1000XM4", Connected: false},
			},
		}
		disc := &staticDiscoverer{
			peers: []mdns.DiscoveredPeer{
				{Host: "127.0.0.1", Port: port, DeviceID: "phone-1", DisplayName: "motorola edge", ConnectedAddresses: []string{"AC:80:0A:CD:82:A1"}},
			},
		}

		_, err = Grab(context.Background(), Options{
			Target:     "WH-1000XM4",
			Identity:   localID,
			Peers:      pairedPeers,
			Controller: ctrl,
			Discoverer: disc,
		})
		if err == nil || !strings.Contains(err.Error(), "no longer recognizes") {
			t.Fatalf("expected re-pair error, got %v", err)
		}
		if len(ctrl.connectCalls) != 0 {
			t.Fatalf("expected no local connect call on rejection, got %v", ctrl.connectCalls)
		}
	})
}

func bytesRepeat(b byte, n int) []byte {
	out := make([]byte, n)
	for i := range out {
		out[i] = b
	}
	return out
}
