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
	"sync/atomic"
	"testing"
	"time"

	"github.com/hddq/bluehop/linux/internal/bluez"
	"github.com/hddq/bluehop/linux/internal/grab"
	"github.com/hddq/bluehop/linux/internal/holder"
	"github.com/hddq/bluehop/linux/internal/mdns"
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

type mockAdvertiser struct {
	shutdownCalls atomic.Int32
}

func (m *mockAdvertiser) Shutdown() {
	m.shutdownCalls.Add(1)
}

func TestCLIDiscover(t *testing.T) {
	dir := t.TempDir()
	storePath := filepath.Join(dir, "peers.json")
	store := peers.NewStore(storePath)
	selfID, err := store.GetOrCreateIdentity("linux-host")
	if err != nil {
		t.Fatalf("GetOrCreateIdentity: %v", err)
	}
	if err := store.UpsertPeer(peers.Peer{
		PeerID:           "phone-1",
		PeerName:         "motorola edge",
		PSKHex:           strings.Repeat("ab", 32),
		PairedAtEpochSec: time.Now().Unix(),
	}); err != nil {
		t.Fatalf("UpsertPeer: %v", err)
	}

	t.Run("lists discovered peers with paired status and devices", func(t *testing.T) {
		var gotOpts mdns.DiscoverOptions
		deps := cliDeps{
			discoverPeers: func(_ context.Context, opts mdns.DiscoverOptions) ([]mdns.DiscoveredPeer, error) {
				gotOpts = opts
				return []mdns.DiscoveredPeer{
					{
						Host:               "192.168.10.15",
						Port:               8765,
						DeviceID:           "phone-1",
						DisplayName:        "motorola edge",
						PairingAvailable:   false,
						ConnectedAddresses: []string{"AC:80:0A:CD:82:A1"},
					},
					{
						Host:             "192.168.10.22",
						Port:             41234,
						DeviceID:         "phone-2",
						DisplayName:      "Pixel 9",
						PairingAvailable: true,
					},
				}, nil
			},
		}

		var stdout, stderr bytes.Buffer
		code := runWithDeps([]string{"discover", "--store", storePath, "--timeout", "1s"}, strings.NewReader(""), &stdout, &stderr, deps)
		if code != 0 {
			t.Fatalf("discover exit=%d stderr=%q", code, stderr.String())
		}
		if gotOpts.ExcludeDeviceID != selfID.DeviceID || gotOpts.PairingOnly {
			t.Fatalf("unexpected discover opts: %+v", gotOpts)
		}
		out := stdout.String()
		for _, want := range []string{
			"motorola edge", "192.168.10.15:8765", "holder", "paired", "AC:80:0A:CD:82:A1", "phone-1",
			"Pixel 9", "192.168.10.22:41234", "pairing", "unpaired", "phone-2",
		} {
			if !strings.Contains(out, want) {
				t.Fatalf("expected %q in discover output:\n%s", want, out)
			}
		}
	})

	t.Run("pairing filter and empty results", func(t *testing.T) {
		var gotOpts mdns.DiscoverOptions
		deps := cliDeps{
			discoverPeers: func(_ context.Context, opts mdns.DiscoverOptions) ([]mdns.DiscoveredPeer, error) {
				gotOpts = opts
				return nil, nil
			},
		}

		var stdout, stderr bytes.Buffer
		code := runWithDeps([]string{"discover", "--pairing", "--store", storePath}, strings.NewReader(""), &stdout, &stderr, deps)
		if code != 0 {
			t.Fatalf("discover --pairing exit=%d stderr=%q", code, stderr.String())
		}
		if !gotOpts.PairingOnly {
			t.Fatalf("expected PairingOnly=true")
		}
		if !strings.Contains(stdout.String(), "No BlueHop hosts advertising pairing found.") {
			t.Fatalf("unexpected stdout=%q", stdout.String())
		}

		stdout.Reset()
		code = runWithDeps([]string{"discover", "--store", storePath}, strings.NewReader(""), &stdout, &stderr, deps)
		if code != 0 {
			t.Fatalf("discover empty exit=%d stderr=%q", code, stderr.String())
		}
		if !strings.Contains(stdout.String(), "No BlueHop hosts found.") {
			t.Fatalf("unexpected stdout=%q", stdout.String())
		}
	})

	t.Run("extra argument returns usage error", func(t *testing.T) {
		var stdout, stderr bytes.Buffer
		if code := runWithDeps([]string{"discover", "unexpected"}, strings.NewReader(""), &stdout, &stderr, cliDeps{}); code != 2 {
			t.Fatalf("expected exit 2, got %d", code)
		}
	})
}

func TestCLIPairAutoDiscovery(t *testing.T) {
	t.Run("auto-selects single discovered pairing peer", func(t *testing.T) {
		dir := t.TempDir()
		srvStore := filepath.Join(dir, "srv-peers.json")
		cliStore := filepath.Join(dir, "cli-peers.json")

		pr, pw := io.Pipe()
		srvInR, srvInW := io.Pipe()
		var srvStderr bytes.Buffer
		srvCodeCh := make(chan int, 1)
		go func() {
			code := runWithDeps(
				[]string{"pair", "--listen", "--addr", "127.0.0.1:0", "--name", "Phone Listener", "--store", srvStore, "--timeout", "10s"},
				srvInR,
				pw,
				&srvStderr,
				cliDeps{},
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

		portStr := <-portCh
		var port int
		_, _ = fmt.Sscanf(portStr, "%d", &port)

		var cliStdout, cliStderr bytes.Buffer
		cliCodeCh := make(chan int, 1)
		go func() {
			cliCodeCh <- runWithDeps(
				[]string{"pair", "--name", "Linux Client", "--store", cliStore, "--timeout", "10s"},
				strings.NewReader(""),
				&cliStdout,
				&cliStderr,
				cliDeps{
					discoverPeers: func(_ context.Context, opts mdns.DiscoverOptions) ([]mdns.DiscoveredPeer, error) {
						if !opts.PairingOnly {
							return nil, errors.New("expected PairingOnly=true")
						}
						return []mdns.DiscoveredPeer{
							{Host: "127.0.0.1", Port: port, DeviceID: "srv-1", DisplayName: "Phone Listener", PairingAvailable: true},
						}, nil
					},
				},
			)
		}()

		<-sasCh
		_, _ = srvInW.Write([]byte("y\n"))
		_ = srvInW.Close()

		if code := <-srvCodeCh; code != 0 {
			t.Fatalf("server exit=%d stderr=%q", code, srvStderr.String())
		}
		if code := <-cliCodeCh; code != 0 {
			t.Fatalf("client exit=%d stderr=%q stdout=%q", code, cliStderr.String(), cliStdout.String())
		}
	})

	t.Run("prompts when multiple pairing peers discovered", func(t *testing.T) {
		dir := t.TempDir()
		srvStore := filepath.Join(dir, "srv-peers.json")
		cliStore := filepath.Join(dir, "cli-peers.json")

		pr, pw := io.Pipe()
		srvInR, srvInW := io.Pipe()
		var srvStderr bytes.Buffer
		srvCodeCh := make(chan int, 1)
		go func() {
			code := runWithDeps(
				[]string{"pair", "--listen", "--addr", "127.0.0.1:0", "--name", "Phone Two", "--store", srvStore, "--timeout", "10s"},
				srvInR,
				pw,
				&srvStderr,
				cliDeps{},
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

		portStr := <-portCh
		var port int
		_, _ = fmt.Sscanf(portStr, "%d", &port)

		var cliStdout, cliStderr bytes.Buffer
		cliCodeCh := make(chan int, 1)
		go func() {
			cliCodeCh <- runWithDeps(
				[]string{"pair", "--name", "Linux Client", "--store", cliStore, "--timeout", "10s"},
				strings.NewReader("2\n"),
				&cliStdout,
				&cliStderr,
				cliDeps{
					discoverPeers: func(_ context.Context, _ mdns.DiscoverOptions) ([]mdns.DiscoveredPeer, error) {
						return []mdns.DiscoveredPeer{
							{Host: "127.0.0.1", Port: 1, DeviceID: "srv-unused", DisplayName: "Phone One", PairingAvailable: true},
							{Host: "127.0.0.1", Port: port, DeviceID: "srv-2", DisplayName: "Phone Two", PairingAvailable: true},
						}, nil
					},
				},
			)
		}()

		<-sasCh
		_, _ = srvInW.Write([]byte("y\n"))
		_ = srvInW.Close()

		if code := <-srvCodeCh; code != 0 {
			t.Fatalf("server exit=%d stderr=%q", code, srvStderr.String())
		}
		if code := <-cliCodeCh; code != 0 {
			t.Fatalf("client exit=%d stderr=%q stdout=%q", code, cliStderr.String(), cliStdout.String())
		}
	})

	t.Run("returns error when no pairing hosts found", func(t *testing.T) {
		dir := t.TempDir()
		var stdout, stderr bytes.Buffer
		code := runWithDeps(
			[]string{"pair", "--store", filepath.Join(dir, "peers.json")},
			strings.NewReader(""),
			&stdout,
			&stderr,
			cliDeps{
				discoverPeers: func(_ context.Context, _ mdns.DiscoverOptions) ([]mdns.DiscoveredPeer, error) {
					return nil, nil
				},
			},
		)
		if code != 1 {
			t.Fatalf("expected exit 1, got %d", code)
		}
		if !strings.Contains(stderr.String(), "No BlueHop hosts advertising pairing") {
			t.Fatalf("unexpected stderr=%q", stderr.String())
		}
	})
}

func TestCLIServeAndPairListenAdvertiseMDNS(t *testing.T) {
	t.Run("serve advertises connected bluetooth addresses and shuts down", func(t *testing.T) {
		dir := t.TempDir()
		storePath := filepath.Join(dir, "peers.json")
		ctrl := &mockBluezController{
			devices: []bluez.Device{
				{Address: "AC:80:0A:CD:82:A1", Alias: "WH-1000XM4", Connected: true},
				{Address: "11:22:33:44:55:66", Alias: "Keychron K2", Connected: false},
			},
		}

		ctx, cancel := context.WithCancel(context.Background())
		defer cancel()

		var gotCfg mdns.AdvertiseConfig
		adv := &mockAdvertiser{}
		advStarted := make(chan struct{})

		var stdout, stderr bytes.Buffer
		codeCh := make(chan int, 1)
		go func() {
			codeCh <- runWithDeps(
				[]string{"serve", "--addr", "0.0.0.0:0", "--name", "Linux Box", "--store", storePath},
				strings.NewReader(""),
				&stdout,
				&stderr,
				cliDeps{
					openBluez:   func() (bluez.Controller, error) { return ctrl, nil },
					shutdownCtx: ctx,
					startAdvertising: func(cfg mdns.AdvertiseConfig) (mdns.Advertiser, error) {
						gotCfg = cfg
						close(advStarted)
						return adv, nil
					},
				},
			)
		}()

		select {
		case <-advStarted:
		case <-time.After(3 * time.Second):
			t.Fatalf("timed out waiting for mDNS advertisement; stderr=%q", stderr.String())
		}
		cancel()

		if code := <-codeCh; code != 0 {
			t.Fatalf("serve exit=%d stderr=%q", code, stderr.String())
		}
		if gotCfg.Pairing {
			t.Fatalf("expected Pairing=false for serve")
		}
		if gotCfg.DeviceName != "Linux Box" || gotCfg.Port <= 0 {
			t.Fatalf("unexpected advertise config: %+v", gotCfg)
		}
		if !reflect.DeepEqual(gotCfg.ConnectedAddresses, []string{"AC:80:0A:CD:82:A1"}) {
			t.Fatalf("ConnectedAddresses = %v, want [AC:80:0A:CD:82:A1]", gotCfg.ConnectedAddresses)
		}
		if adv.shutdownCalls.Load() == 0 {
			t.Fatalf("expected advertiser Shutdown() to be called")
		}
	})

	t.Run("pair --listen advertises pairing=true and shuts down", func(t *testing.T) {
		dir := t.TempDir()
		storePath := filepath.Join(dir, "peers.json")

		var gotCfg mdns.AdvertiseConfig
		adv := &mockAdvertiser{}
		advStarted := make(chan struct{})

		var stdout, stderr bytes.Buffer
		codeCh := make(chan int, 1)
		go func() {
			codeCh <- runWithDeps(
				[]string{"pair", "--listen", "--addr", "0.0.0.0:0", "--name", "Linux Pairer", "--store", storePath, "--timeout", "200ms"},
				strings.NewReader(""),
				&stdout,
				&stderr,
				cliDeps{
					startAdvertising: func(cfg mdns.AdvertiseConfig) (mdns.Advertiser, error) {
						gotCfg = cfg
						close(advStarted)
						return adv, nil
					},
				},
			)
		}()

		select {
		case <-advStarted:
		case <-time.After(3 * time.Second):
			t.Fatalf("timed out waiting for pairing mDNS advertisement; stderr=%q", stderr.String())
		}

		_ = <-codeCh // exits with timeout (1) because no peer connected
		if !gotCfg.Pairing {
			t.Fatalf("expected Pairing=true for pair --listen")
		}
		if gotCfg.DeviceName != "Linux Pairer" || gotCfg.Port <= 0 {
			t.Fatalf("unexpected advertise config: %+v", gotCfg)
		}
		if adv.shutdownCalls.Load() == 0 {
			t.Fatalf("expected advertiser Shutdown() to be called")
		}
	})
}

func TestCLIGrab(t *testing.T) {
	t.Run("releases from holder server and connects locally", func(t *testing.T) {
		dir := t.TempDir()
		storePath := filepath.Join(dir, "peers.json")
		store := peers.NewStore(storePath)
		selfID, err := store.GetOrCreateIdentity("nix-client")
		if err != nil {
			t.Fatalf("GetOrCreateIdentity: %v", err)
		}

		psk := make([]byte, 32)
		for i := range psk {
			psk[i] = byte(i + 3)
		}
		if err := store.UpsertPeer(peers.Peer{
			PeerID:           "phone-1",
			PeerName:         "motorola edge",
			PSKHex:           hex.EncodeToString(psk),
			PairedAtEpochSec: time.Now().Unix(),
		}); err != nil {
			t.Fatalf("UpsertPeer: %v", err)
		}

		holderCtrl := &mockBluezController{
			devices: []bluez.Device{
				{Address: "AC:80:0A:CD:82:A1", Alias: "WH-1000XM4", Connected: true},
			},
		}
		holderSrv := holder.NewServer(holder.Config{
			Identity: peers.Identity{DeviceID: "phone-1", DeviceName: "motorola edge"},
			Backend:  holder.NewBluezBackend(holderCtrl),
			PeerPSKLookup: func(id string) ([]byte, error) {
				if id == selfID.DeviceID {
					return psk, nil
				}
				return nil, errors.New("unknown")
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

		localCtrl := &mockBluezController{
			devices: []bluez.Device{
				{Address: "AC:80:0A:CD:82:A1", Alias: "WH-1000XM4", Connected: false},
			},
		}

		var stdout, stderr bytes.Buffer
		code := runWithDeps(
			[]string{"grab", "--store", storePath, "WH-1000XM4"},
			strings.NewReader(""),
			&stdout,
			&stderr,
			cliDeps{
				openBluez: func() (bluez.Controller, error) { return localCtrl, nil },
				discoverPeers: func(context.Context, mdns.DiscoverOptions) ([]mdns.DiscoveredPeer, error) {
					return []mdns.DiscoveredPeer{
						{
							Host:               "127.0.0.1",
							Port:               port,
							DeviceID:           "phone-1",
							DisplayName:        "motorola edge",
							ConnectedAddresses: []string{"AC:80:0A:CD:82:A1"},
						},
					}, nil
				},
				newHolderClient: func() grab.HolderClient {
					return grab.NewHTTPClient(grab.HTTPClientConfig{})
				},
			},
		)
		if code != 0 {
			t.Fatalf("grab exit=%d stderr=%q stdout=%q", code, stderr.String(), stdout.String())
		}
		out := stdout.String()
		for _, want := range []string{
			"Searching for BlueHop peers on LAN...",
			"Releasing WH-1000XM4 (AC:80:0A:CD:82:A1) from motorola edge...",
			"Connecting to WH-1000XM4 (AC:80:0A:CD:82:A1)...",
			"Connected to WH-1000XM4 (AC:80:0A:CD:82:A1) (released from motorola edge)",
		} {
			if !strings.Contains(out, want) {
				t.Fatalf("expected %q in grab stdout:\n%s", want, out)
			}
		}
		if holderCtrl.devices[0].Connected {
			t.Fatalf("expected holder controller device to be disconnected")
		}
		if !localCtrl.devices[0].Connected {
			t.Fatalf("expected local controller device to be connected")
		}
	})

	t.Run("already connected locally and missing arg", func(t *testing.T) {
		dir := t.TempDir()
		storePath := filepath.Join(dir, "peers.json")
		ctrl := &mockBluezController{
			devices: []bluez.Device{
				{Address: "AC:80:0A:CD:82:A1", Alias: "WH-1000XM4", Connected: true},
			},
		}

		var stdout, stderr bytes.Buffer
		code := runWithDeps(
			[]string{"grab", "--store", storePath, "AC:80:0A:CD:82:A1"},
			strings.NewReader(""),
			&stdout,
			&stderr,
			cliDeps{openBluez: func() (bluez.Controller, error) { return ctrl, nil }},
		)
		if code != 0 {
			t.Fatalf("grab exit=%d stderr=%q", code, stderr.String())
		}
		if !strings.Contains(stdout.String(), "WH-1000XM4 (AC:80:0A:CD:82:A1) is already connected") {
			t.Fatalf("unexpected stdout=%q", stdout.String())
		}

		stdout.Reset()
		stderr.Reset()
		if code := runWithDeps([]string{"grab"}, strings.NewReader(""), &stdout, &stderr, cliDeps{}); code != 2 {
			t.Fatalf("expected exit 2 for missing arg, got %d", code)
		}
	})
}

type mockWatchingBluezController struct {
	*mockBluezController
	watchCh  <-chan struct{}
	watchErr error
}

func (m *mockWatchingBluezController) WatchChanges(context.Context) (<-chan struct{}, error) {
	if m.watchErr != nil {
		return nil, m.watchErr
	}
	return m.watchCh, nil
}

func (m *mockWatchingBluezController) setConnected(addr string, connected bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	for i := range m.devices {
		if strings.EqualFold(m.devices[i].Address, addr) {
			m.devices[i].Connected = connected
			return
		}
	}
}

func TestCLIServeLiveMDNSRefresh(t *testing.T) {
	type advEvent struct {
		cfg mdns.AdvertiseConfig
		adv *mockAdvertiser
	}

	t.Run("refreshes mDNS on BlueZ connect and disconnect signals and skips unchanged state", func(t *testing.T) {
		dir := t.TempDir()
		storePath := filepath.Join(dir, "peers.json")
		changesCh := make(chan struct{}, 4)
		ctrl := &mockWatchingBluezController{
			mockBluezController: &mockBluezController{
				devices: []bluez.Device{
					{Address: "AC:80:0A:CD:82:A1", Alias: "WH-1000XM4", Connected: false},
				},
			},
			watchCh: changesCh,
		}

		ctx, cancel := context.WithCancel(context.Background())
		defer cancel()

		events := make(chan advEvent, 8)
		var stdout, stderr bytes.Buffer
		codeCh := make(chan int, 1)
		go func() {
			codeCh <- runWithDeps(
				[]string{"serve", "--addr", "0.0.0.0:0", "--name", "Linux Box", "--store", storePath},
				strings.NewReader(""),
				&stdout,
				&stderr,
				cliDeps{
					openBluez:     func() (bluez.Controller, error) { return ctrl, nil },
					shutdownCtx:   ctx,
					watchDebounce: 15 * time.Millisecond,
					startAdvertising: func(cfg mdns.AdvertiseConfig) (mdns.Advertiser, error) {
						adv := &mockAdvertiser{}
						events <- advEvent{cfg: cfg, adv: adv}
						return adv, nil
					},
				},
			)
		}()

		waitEvent := func(desc string) advEvent {
			t.Helper()
			select {
			case ev := <-events:
				return ev
			case <-time.After(3 * time.Second):
				t.Fatalf("timed out waiting for %s", desc)
				return advEvent{}
			}
		}

		ev0 := waitEvent("initial advertisement")
		if len(ev0.cfg.ConnectedAddresses) != 0 {
			t.Fatalf("initial ConnectedAddresses = %v, want empty", ev0.cfg.ConnectedAddresses)
		}

		// Connect device and burst two signals to verify debounce coalescing.
		ctrl.setConnected("AC:80:0A:CD:82:A1", true)
		changesCh <- struct{}{}
		changesCh <- struct{}{}

		ev1 := waitEvent("connect refresh advertisement")
		if !reflect.DeepEqual(ev1.cfg.ConnectedAddresses, []string{"AC:80:0A:CD:82:A1"}) {
			t.Fatalf("connect ConnectedAddresses = %v, want [AC:80:0A:CD:82:A1]", ev1.cfg.ConnectedAddresses)
		}
		if ev0.adv.shutdownCalls.Load() != 1 {
			t.Fatalf("expected initial advertiser to be shut down once, got %d", ev0.adv.shutdownCalls.Load())
		}

		// Send a spurious signal with no state change: should NOT re-advertise.
		changesCh <- struct{}{}
		select {
		case unexpected := <-events:
			t.Fatalf("unexpected mDNS refresh when connected addresses did not change: %+v", unexpected.cfg)
		case <-time.After(60 * time.Millisecond):
		}

		// Disconnect device and trigger signal.
		ctrl.setConnected("AC:80:0A:CD:82:A1", false)
		changesCh <- struct{}{}

		ev2 := waitEvent("disconnect refresh advertisement")
		if len(ev2.cfg.ConnectedAddresses) != 0 {
			t.Fatalf("disconnect ConnectedAddresses = %v, want empty", ev2.cfg.ConnectedAddresses)
		}
		if ev1.adv.shutdownCalls.Load() != 1 {
			t.Fatalf("expected second advertiser to be shut down once, got %d", ev1.adv.shutdownCalls.Load())
		}

		cancel()
		if code := <-codeCh; code != 0 {
			t.Fatalf("serve exit=%d stderr=%q", code, stderr.String())
		}
		if ev2.adv.shutdownCalls.Load() != 1 {
			t.Fatalf("expected final advertiser to be shut down once, got %d", ev2.adv.shutdownCalls.Load())
		}
		errLog := stderr.String()
		if !strings.Contains(errLog, "Updated mDNS advertisement (connected devices: AC:80:0A:CD:82:A1)") {
			t.Fatalf("expected connect update log in stderr, got %q", errLog)
		}
		if !strings.Contains(errLog, "Updated mDNS advertisement (no connected Bluetooth devices)") {
			t.Fatalf("expected disconnect update log in stderr, got %q", errLog)
		}
	})

	t.Run("falls back to polling when WatchChanges fails", func(t *testing.T) {
		dir := t.TempDir()
		storePath := filepath.Join(dir, "peers.json")
		ctrl := &mockWatchingBluezController{
			mockBluezController: &mockBluezController{
				devices: []bluez.Device{
					{Address: "AC:80:0A:CD:82:A1", Alias: "WH-1000XM4", Connected: false},
				},
			},
			watchErr: errors.New("dbus watch denied"),
		}

		ctx, cancel := context.WithCancel(context.Background())
		defer cancel()

		events := make(chan advEvent, 8)
		var stdout, stderr bytes.Buffer
		codeCh := make(chan int, 1)
		go func() {
			codeCh <- runWithDeps(
				[]string{"serve", "--addr", "0.0.0.0:0", "--name", "Linux Box", "--store", storePath},
				strings.NewReader(""),
				&stdout,
				&stderr,
				cliDeps{
					openBluez:         func() (bluez.Controller, error) { return ctrl, nil },
					shutdownCtx:       ctx,
					watchPollInterval: 20 * time.Millisecond,
					startAdvertising: func(cfg mdns.AdvertiseConfig) (mdns.Advertiser, error) {
						adv := &mockAdvertiser{}
						events <- advEvent{cfg: cfg, adv: adv}
						return adv, nil
					},
				},
			)
		}()

		var ev0 advEvent
		select {
		case ev0 = <-events:
		case <-time.After(3 * time.Second):
			t.Fatalf("timed out waiting for initial advertisement")
		}
		if len(ev0.cfg.ConnectedAddresses) != 0 {
			t.Fatalf("initial ConnectedAddresses = %v, want empty", ev0.cfg.ConnectedAddresses)
		}

		ctrl.setConnected("AC:80:0A:CD:82:A1", true)

		var ev1 advEvent
		select {
		case ev1 = <-events:
		case <-time.After(3 * time.Second):
			t.Fatalf("timed out waiting for poll-driven refresh")
		}
		if !reflect.DeepEqual(ev1.cfg.ConnectedAddresses, []string{"AC:80:0A:CD:82:A1"}) {
			t.Fatalf("poll ConnectedAddresses = %v, want [AC:80:0A:CD:82:A1]", ev1.cfg.ConnectedAddresses)
		}
		if ev0.adv.shutdownCalls.Load() != 1 {
			t.Fatalf("expected initial advertiser shutdown, got %d", ev0.adv.shutdownCalls.Load())
		}

		cancel()
		if code := <-codeCh; code != 0 {
			t.Fatalf("serve exit=%d stderr=%q", code, stderr.String())
		}
		if ev1.adv.shutdownCalls.Load() != 1 {
			t.Fatalf("expected final advertiser shutdown, got %d", ev1.adv.shutdownCalls.Load())
		}
		if !strings.Contains(stderr.String(), "falling back to") {
			t.Fatalf("expected polling fallback warning in stderr, got %q", stderr.String())
		}
	})

	t.Run("falls back to polling when signal stream closes unexpectedly", func(t *testing.T) {
		dir := t.TempDir()
		storePath := filepath.Join(dir, "peers.json")
		changesCh := make(chan struct{}, 1)
		ctrl := &mockWatchingBluezController{
			mockBluezController: &mockBluezController{
				devices: []bluez.Device{
					{Address: "AC:80:0A:CD:82:A1", Alias: "WH-1000XM4", Connected: false},
				},
			},
			watchCh: changesCh,
		}

		ctx, cancel := context.WithCancel(context.Background())
		defer cancel()

		events := make(chan advEvent, 8)
		var stdout, stderr bytes.Buffer
		codeCh := make(chan int, 1)
		go func() {
			codeCh <- runWithDeps(
				[]string{"serve", "--addr", "0.0.0.0:0", "--name", "Linux Box", "--store", storePath},
				strings.NewReader(""),
				&stdout,
				&stderr,
				cliDeps{
					openBluez:         func() (bluez.Controller, error) { return ctrl, nil },
					shutdownCtx:       ctx,
					watchPollInterval: 20 * time.Millisecond,
					startAdvertising: func(cfg mdns.AdvertiseConfig) (mdns.Advertiser, error) {
						adv := &mockAdvertiser{}
						events <- advEvent{cfg: cfg, adv: adv}
						return adv, nil
					},
				},
			)
		}()

		select {
		case <-events:
		case <-time.After(3 * time.Second):
			t.Fatalf("timed out waiting for initial advertisement")
		}

		close(changesCh)
		ctrl.setConnected("AC:80:0A:CD:82:A1", true)

		var ev1 advEvent
		select {
		case ev1 = <-events:
		case <-time.After(3 * time.Second):
			t.Fatalf("timed out waiting for poll refresh after stream close")
		}
		if !reflect.DeepEqual(ev1.cfg.ConnectedAddresses, []string{"AC:80:0A:CD:82:A1"}) {
			t.Fatalf("ConnectedAddresses = %v, want [AC:80:0A:CD:82:A1]", ev1.cfg.ConnectedAddresses)
		}

		cancel()
		if code := <-codeCh; code != 0 {
			t.Fatalf("serve exit=%d stderr=%q", code, stderr.String())
		}
		if !strings.Contains(stderr.String(), "BlueZ signal stream closed") {
			t.Fatalf("expected stream closed warning in stderr, got %q", stderr.String())
		}
	})
}
