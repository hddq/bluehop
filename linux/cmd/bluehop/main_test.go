package main

import (
	"bytes"
	"context"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"path/filepath"
	"reflect"
	"regexp"
	"runtime/debug"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/hddq/bluehop/linux/internal/bluez"
	"github.com/hddq/bluehop/linux/internal/holder"
	"github.com/hddq/bluehop/linux/internal/peers"
	"github.com/hddq/bluehop/linux/internal/protocol"
)

func TestRunHelpAndVersion(t *testing.T) {
	var stdout, stderr bytes.Buffer
	if code := run([]string{"--help"}, &stdout, &stderr); code != 0 {
		t.Fatalf("expected exit 0 for --help, got %d (stderr=%q)", code, stderr.String())
	}
	if !strings.Contains(stderr.String(), "Usage: bluehop") {
		t.Fatalf("expected usage in stderr, got %q", stderr.String())
	}

	stdout.Reset()
	stderr.Reset()
	if code := run([]string{"version"}, &stdout, &stderr); code != 0 {
		t.Fatalf("expected exit 0 for version, got %d (stderr=%q)", code, stderr.String())
	}
	if !strings.HasPrefix(stdout.String(), "bluehop ") {
		t.Fatalf("expected version output, got %q", stdout.String())
	}

	stdout.Reset()
	stderr.Reset()
	if code := run([]string{"version", "extra"}, &stdout, &stderr); code != 2 {
		t.Fatalf("expected exit 2 for extra args on version, got %d", code)
	}
}

func TestFormatBuildVersion(t *testing.T) {
	tests := []struct {
		name string
		info *debug.BuildInfo
		want string
	}{
		{
			name: "tagged module build",
			info: &debug.BuildInfo{Main: debug.Module{Version: "v0.1.0"}},
			want: "v0.1.0",
		},
		{
			name: "clean dev vcs build",
			info: &debug.BuildInfo{
				Main: debug.Module{Version: "(devel)"},
				Settings: []debug.BuildSetting{
					{Key: "vcs.revision", Value: "47e84f71234567890"},
					{Key: "vcs.modified", Value: "false"},
				},
			},
			want: "dev+47e84f7",
		},
		{
			name: "dirty dev vcs build",
			info: &debug.BuildInfo{
				Main: debug.Module{Version: "(devel)"},
				Settings: []debug.BuildSetting{
					{Key: "vcs.revision", Value: "47e84f71234567890"},
					{Key: "vcs.modified", Value: "true"},
				},
			},
			want: "dev+47e84f7-dirty",
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			if got := formatBuildVersion(tc.info); got != tc.want {
				t.Fatalf("formatBuildVersion() = %q, want %q", got, tc.want)
			}
		})
	}
}

func TestCLIPairListenAndConnectAndPeers(t *testing.T) {
	dir := t.TempDir()
	srvStorePath := filepath.Join(dir, "srv-peers.json")
	cliStorePath := filepath.Join(dir, "cli-peers.json")

	pr, pw := io.Pipe()
	srvInR, srvInW := io.Pipe()
	var srvStderr bytes.Buffer
	srvCodeCh := make(chan int, 1)

	go func() {
		mw := io.MultiWriter(pw)
		code := runWithIO(
			[]string{"pair", "--listen", "--addr", "127.0.0.1:0", "--name", "Linux Server", "--store", srvStorePath, "--timeout", "10s"},
			srvInR,
			mw,
			&srvStderr,
		)
		_ = pw.Close()
		srvCodeCh <- code
	}()

	// Wait until port and SAS are printed by server, then keep draining pr until EOF.
	portCh := make(chan string, 1)
	sasServerCh := make(chan string, 1)
	go func() {
		buf := make([]byte, 4096)
		var acc string
		portRe := regexp.MustCompile(`on port (\d+)`)
		sasRe := regexp.MustCompile(`Verification code \(SAS\): (\d{3} \d{3})`)
		portSent := false
		sasSent := false
		for {
			n, err := pr.Read(buf)
			if n > 0 {
				acc += string(buf[:n])
				if !portSent {
					if m := portRe.FindStringSubmatch(acc); len(m) == 2 {
						portSent = true
						portCh <- m[1]
					}
				}
				if !sasSent {
					if m := sasRe.FindStringSubmatch(acc); len(m) == 2 {
						sasSent = true
						sasServerCh <- m[1]
					}
				}
			}
			if err != nil {
				return
			}
		}
	}()

	var port string
	select {
	case port = <-portCh:
	case <-time.After(3 * time.Second):
		t.Fatalf("timed out waiting for server port; stderr=%q", srvStderr.String())
	}

	var cliStdout, cliStderr bytes.Buffer
	cliCodeCh := make(chan int, 1)
	go func() {
		cliCodeCh <- runWithIO(
			[]string{"pair", "--name", "Linux Client", "--store", cliStorePath, "--timeout", "10s", "127.0.0.1:" + port},
			strings.NewReader(""),
			&cliStdout,
			&cliStderr,
		)
	}()

	var serverSAS string
	select {
	case serverSAS = <-sasServerCh:
	case <-time.After(3 * time.Second):
		t.Fatalf("timed out waiting for server SAS; stderr=%q", srvStderr.String())
	}

	// Accept pairing on server stdin.
	_, _ = srvInW.Write([]byte("y\n"))
	_ = srvInW.Close()

	if code := <-srvCodeCh; code != 0 {
		t.Fatalf("server exited %d; stderr=%q", code, srvStderr.String())
	}
	if code := <-cliCodeCh; code != 0 {
		t.Fatalf("client exited %d; stderr=%q", code, cliStderr.String())
	}
	if !strings.Contains(cliStdout.String(), "Verification code (SAS): "+serverSAS) {
		t.Fatalf("client SAS mismatch or missing; stdout=%q serverSAS=%q", cliStdout.String(), serverSAS)
	}

	// Verify peers command lists and removes the paired peer.
	var listOut, listErr bytes.Buffer
	if code := runWithIO([]string{"peers", "--store", cliStorePath, "list"}, strings.NewReader(""), &listOut, &listErr); code != 0 {
		t.Fatalf("peers list failed: %d %s", code, listErr.String())
	}
	if !strings.Contains(listOut.String(), "Linux Server") {
		t.Fatalf("expected Linux Server in peers list, got %q", listOut.String())
	}

	srvPeers, err := peers.NewStore(srvStorePath).ListPeers()
	if err != nil || len(srvPeers) != 1 {
		t.Fatalf("unexpected server peers: %+v err=%v", srvPeers, err)
	}
	cliPeers, err := peers.NewStore(cliStorePath).ListPeers()
	if err != nil || len(cliPeers) != 1 {
		t.Fatalf("unexpected client peers: %+v err=%v", cliPeers, err)
	}
	if srvPeers[0].PSKHex != cliPeers[0].PSKHex {
		t.Fatalf("PSKHex mismatch between server and client")
	}

	var rmOut, rmErr bytes.Buffer
	if code := runWithIO([]string{"peers", "--store", cliStorePath, "remove", cliPeers[0].PeerID}, strings.NewReader(""), &rmOut, &rmErr); code != 0 {
		t.Fatalf("peers remove failed: %d %s", code, rmErr.String())
	}
}

func TestListeningEndpointsExplicitBind(t *testing.T) {
	gotV4 := listeningEndpoints("127.0.0.1:0", &net.TCPAddr{IP: net.ParseIP("127.0.0.1"), Port: 43215})
	wantV4 := []string{"127.0.0.1:43215"}
	if !reflect.DeepEqual(gotV4, wantV4) {
		t.Fatalf("listeningEndpoints(v4) = %v, want %v", gotV4, wantV4)
	}

	gotV6 := listeningEndpoints("[::1]:0", &net.TCPAddr{IP: net.ParseIP("::1"), Port: 43215})
	wantV6 := []string{"[::1]:43215"}
	if !reflect.DeepEqual(gotV6, wantV6) {
		t.Fatalf("listeningEndpoints(v6) = %v, want %v", gotV6, wantV6)
	}
}

func TestCLIPairRejectObservedWithinDefaultPoll(t *testing.T) {
	dir := t.TempDir()
	srvStorePath := filepath.Join(dir, "srv-peers.json")
	cliStorePath := filepath.Join(dir, "cli-peers.json")

	pr, pw := io.Pipe()
	srvInR, srvInW := io.Pipe()
	t.Cleanup(func() {
		_ = pr.Close()
		_ = pw.Close()
		_ = srvInR.Close()
		_ = srvInW.Close()
	})

	var srvStderr bytes.Buffer
	srvCodeCh := make(chan int, 1)
	go func() {
		code := runWithIO(
			[]string{"pair", "--listen", "--addr", "127.0.0.1:0", "--name", "Linux Server", "--store", srvStorePath, "--timeout", "10s"},
			srvInR,
			pw,
			&srvStderr,
		)
		_ = pw.Close()
		srvCodeCh <- code
	}()

	portCh := make(chan string, 1)
	sasCh := make(chan string, 1)
	go func() {
		buf := make([]byte, 4096)
		var acc string
		portRe := regexp.MustCompile(`on port (\d+)`)
		sasRe := regexp.MustCompile(`Verification code \(SAS\): (\d{3} \d{3})`)
		portSent, sasSent := false, false
		for {
			n, err := pr.Read(buf)
			if n > 0 {
				acc += string(buf[:n])
				if !portSent {
					if m := portRe.FindStringSubmatch(acc); len(m) == 2 {
						portSent = true
						portCh <- m[1]
					}
				}
				if !sasSent {
					if m := sasRe.FindStringSubmatch(acc); len(m) == 2 {
						sasSent = true
						sasCh <- m[1]
					}
				}
			}
			if err != nil {
				return
			}
		}
	}()

	port := <-portCh
	var cliStdout, cliStderr bytes.Buffer
	cliCodeCh := make(chan int, 1)
	go func() {
		cliCodeCh <- runWithIO(
			[]string{"pair", "--name", "Linux Client", "--store", cliStorePath, "--timeout", "10s", "127.0.0.1:" + port},
			strings.NewReader(""),
			&cliStdout,
			&cliStderr,
		)
	}()

	<-sasCh
	_, _ = srvInW.Write([]byte("n\n"))
	_ = srvInW.Close()

	if code := <-srvCodeCh; code == 0 {
		t.Fatalf("expected non-zero exit when server rejects pairing")
	}
	if code := <-cliCodeCh; code == 0 {
		t.Fatalf("expected non-zero exit on rejected client")
	}
	if !strings.Contains(cliStderr.String(), "rejected") {
		t.Fatalf("expected client to observe rejection reason rather than connection failure, got stderr=%q", cliStderr.String())
	}
}

type mockBluezController struct {
	mu            sync.Mutex
	devices       []bluez.Device
	listErr       error
	connectErr    error
	disconnectErr error
	lastQuery     string
	closed        bool
}

func (m *mockBluezController) ListDevices(context.Context) ([]bluez.Device, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.listErr != nil {
		return nil, m.listErr
	}
	out := make([]bluez.Device, len(m.devices))
	copy(out, m.devices)
	return out, nil
}

func (m *mockBluezController) FindDevice(_ context.Context, query string) (bluez.Device, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, d := range m.devices {
		if strings.EqualFold(d.Address, query) || strings.EqualFold(d.DisplayName(), query) {
			return d, nil
		}
	}
	return bluez.Device{}, fmt.Errorf("%w: %q", bluez.ErrDeviceNotFound, query)
}

func (m *mockBluezController) Connect(_ context.Context, query string) (bluez.Device, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.lastQuery = query
	for i, d := range m.devices {
		if strings.EqualFold(d.Address, query) || strings.EqualFold(d.DisplayName(), query) {
			if m.connectErr != nil {
				return d, m.connectErr
			}
			m.devices[i].Connected = true
			return m.devices[i], nil
		}
	}
	return bluez.Device{}, fmt.Errorf("%w: %q", bluez.ErrDeviceNotFound, query)
}

func (m *mockBluezController) Disconnect(_ context.Context, query string) (bluez.Device, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.lastQuery = query
	for i, d := range m.devices {
		if strings.EqualFold(d.Address, query) || strings.EqualFold(d.DisplayName(), query) {
			if m.disconnectErr != nil {
				return d, m.disconnectErr
			}
			m.devices[i].Connected = false
			return m.devices[i], nil
		}
	}
	return bluez.Device{}, fmt.Errorf("%w: %q", bluez.ErrDeviceNotFound, query)
}

func (m *mockBluezController) Close() error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.closed = true
	return nil
}

func TestCLIDevicesConnectDisconnect(t *testing.T) {
	sample := []bluez.Device{
		{Address: "AA:BB:CC:DD:EE:01", Alias: "Keychron K2", Connected: false},
		{Address: "AA:BB:CC:DD:EE:02", Alias: "WH-1000XM5", Connected: true},
	}

	t.Run("devices lists paired devices", func(t *testing.T) {
		ctrl := &mockBluezController{devices: sample}
		deps := cliDeps{openBluez: func() (bluez.Controller, error) { return ctrl, nil }}

		var stdout, stderr bytes.Buffer
		code := runWithDeps([]string{"devices"}, strings.NewReader(""), &stdout, &stderr, deps)
		if code != 0 {
			t.Fatalf("devices exit=%d stderr=%q", code, stderr.String())
		}
		if !ctrl.closed {
			t.Fatalf("expected controller to be closed")
		}
		want := "AA:BB:CC:DD:EE:01\tdisconnected\tKeychron K2\nAA:BB:CC:DD:EE:02\tconnected\tWH-1000XM5\n"
		if stdout.String() != want {
			t.Fatalf("stdout = %q, want %q", stdout.String(), want)
		}
	})

	t.Run("devices empty", func(t *testing.T) {
		ctrl := &mockBluezController{}
		deps := cliDeps{openBluez: func() (bluez.Controller, error) { return ctrl, nil }}

		var stdout, stderr bytes.Buffer
		code := runWithDeps([]string{"devices"}, strings.NewReader(""), &stdout, &stderr, deps)
		if code != 0 {
			t.Fatalf("devices exit=%d stderr=%q", code, stderr.String())
		}
		if !strings.Contains(stdout.String(), "No paired Bluetooth devices.") {
			t.Fatalf("unexpected stdout=%q", stdout.String())
		}
	})

	t.Run("connect success and already connected", func(t *testing.T) {
		ctrl := &mockBluezController{devices: sample}
		deps := cliDeps{openBluez: func() (bluez.Controller, error) { return ctrl, nil }}

		var stdout, stderr bytes.Buffer
		code := runWithDeps([]string{"connect", "Keychron K2"}, strings.NewReader(""), &stdout, &stderr, deps)
		if code != 0 {
			t.Fatalf("connect exit=%d stderr=%q", code, stderr.String())
		}
		if !strings.Contains(stdout.String(), "Connected to Keychron K2 (AA:BB:CC:DD:EE:01)") {
			t.Fatalf("unexpected stdout=%q", stdout.String())
		}

		stdout.Reset()
		ctrl.connectErr = fmt.Errorf("%w: WH-1000XM5", bluez.ErrAlreadyConnected)
		code = runWithDeps([]string{"connect", "AA:BB:CC:DD:EE:02"}, strings.NewReader(""), &stdout, &stderr, deps)
		if code != 0 {
			t.Fatalf("already connected exit=%d stderr=%q", code, stderr.String())
		}
		if !strings.Contains(stdout.String(), "WH-1000XM5 (AA:BB:CC:DD:EE:02) is already connected") {
			t.Fatalf("unexpected stdout=%q", stdout.String())
		}
	})

	t.Run("disconnect success and already disconnected", func(t *testing.T) {
		ctrl := &mockBluezController{devices: sample}
		deps := cliDeps{openBluez: func() (bluez.Controller, error) { return ctrl, nil }}

		var stdout, stderr bytes.Buffer
		code := runWithDeps([]string{"disconnect", "WH-1000XM5"}, strings.NewReader(""), &stdout, &stderr, deps)
		if code != 0 {
			t.Fatalf("disconnect exit=%d stderr=%q", code, stderr.String())
		}
		if !strings.Contains(stdout.String(), "Disconnected from WH-1000XM5 (AA:BB:CC:DD:EE:02)") {
			t.Fatalf("unexpected stdout=%q", stdout.String())
		}

		stdout.Reset()
		ctrl.disconnectErr = fmt.Errorf("%w: Keychron K2", bluez.ErrNotConnected)
		code = runWithDeps([]string{"disconnect", "Keychron K2"}, strings.NewReader(""), &stdout, &stderr, deps)
		if code != 0 {
			t.Fatalf("already disconnected exit=%d stderr=%q", code, stderr.String())
		}
		if !strings.Contains(stdout.String(), "Keychron K2 (AA:BB:CC:DD:EE:01) is already disconnected") {
			t.Fatalf("unexpected stdout=%q", stdout.String())
		}
	})

	t.Run("error cases return non-zero", func(t *testing.T) {
		ctrl := &mockBluezController{devices: sample, connectErr: errors.New("page timeout")}
		deps := cliDeps{openBluez: func() (bluez.Controller, error) { return ctrl, nil }}

		var stdout, stderr bytes.Buffer
		if code := runWithDeps([]string{"connect"}, strings.NewReader(""), &stdout, &stderr, deps); code != 2 {
			t.Fatalf("missing arg exit=%d, want 2", code)
		}
		stderr.Reset()
		if code := runWithDeps([]string{"connect", "Keychron K2"}, strings.NewReader(""), &stdout, &stderr, deps); code != 1 {
			t.Fatalf("connect error exit=%d, want 1", code)
		}
	})
}

func TestCLIServe(t *testing.T) {
	dir := t.TempDir()
	storePath := filepath.Join(dir, "peers.json")
	store := peers.NewStore(storePath)

	psk := make([]byte, 32)
	for i := range psk {
		psk[i] = byte(i + 1)
	}
	if err := store.UpsertPeer(peers.Peer{
		PeerID:           "phone-1",
		PeerName:         "Pixel",
		PSKHex:           hex.EncodeToString(psk),
		PairedAtEpochSec: time.Now().Unix(),
	}); err != nil {
		t.Fatalf("UpsertPeer: %v", err)
	}

	ctrl := &mockBluezController{
		devices: []bluez.Device{
			{Address: "AC:80:0A:CD:82:A1", Alias: "WH-1000XM4", Connected: true},
		},
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	pr, pw := io.Pipe()
	var stderr bytes.Buffer
	codeCh := make(chan int, 1)
	go func() {
		code := runWithDeps(
			[]string{"serve", "--addr", "127.0.0.1:0", "--name", "Linux Box", "--store", storePath},
			strings.NewReader(""),
			pw,
			&stderr,
			cliDeps{
				openBluez:   func() (bluez.Controller, error) { return ctrl, nil },
				shutdownCtx: ctx,
			},
		)
		_ = pw.Close()
		codeCh <- code
	}()

	portCh := make(chan string, 1)
	go func() {
		buf := make([]byte, 4096)
		var acc string
		re := regexp.MustCompile(`on port (\d+)`)
		for {
			n, err := pr.Read(buf)
			if n > 0 {
				acc += string(buf[:n])
				if m := re.FindStringSubmatch(acc); len(m) == 2 {
					select {
					case portCh <- m[1]:
					default:
					}
				}
			}
			if err != nil {
				return
			}
		}
	}()

	var port string
	select {
	case port = <-portCh:
	case <-time.After(3 * time.Second):
		t.Fatalf("timed out waiting for serve port; stderr=%q", stderr.String())
	}

	statusResp, err := http.Get("http://127.0.0.1:" + port + "/status")
	if err != nil {
		t.Fatalf("GET /status: %v", err)
	}
	var status holder.StatusResponse
	if err := json.NewDecoder(statusResp.Body).Decode(&status); err != nil {
		t.Fatalf("decode status: %v", err)
	}
	_ = statusResp.Body.Close()
	if !status.Connected || status.DeviceAddress == nil || *status.DeviceAddress != "AC:80:0A:CD:82:A1" {
		t.Fatalf("unexpected /status payload: %+v", status)
	}

	signedReq, err := protocol.SignRequest(psk, protocol.GrabRequest{
		DeviceAddress:     "AC:80:0A:CD:82:A1",
		RequesterID:       "phone-1",
		TimestampEpochSec: time.Now().Unix(),
		Nonce:             "0123456789abcdef0123456789abcdef",
	})
	if err != nil {
		t.Fatalf("SignRequest: %v", err)
	}
	reqBytes, _ := json.Marshal(signedReq)
	relResp, err := http.Post("http://127.0.0.1:"+port+"/release", "application/json", bytes.NewReader(reqBytes))
	if err != nil {
		t.Fatalf("POST /release: %v", err)
	}
	var grabResp protocol.GrabResponse
	if err := json.NewDecoder(relResp.Body).Decode(&grabResp); err != nil {
		t.Fatalf("decode GrabResponse: %v", err)
	}
	_ = relResp.Body.Close()
	if relResp.StatusCode != http.StatusOK || grabResp.Status != protocol.StatusOK {
		t.Fatalf("unexpected /release response: http=%d body=%+v", relResp.StatusCode, grabResp)
	}

	cancel()
	if code := <-codeCh; code != 0 {
		t.Fatalf("serve exit=%d, want 0; stderr=%q", code, stderr.String())
	}
	if !ctrl.closed {
		t.Fatalf("expected controller to close on serve exit")
	}
}
