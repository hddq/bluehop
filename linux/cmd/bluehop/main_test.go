package main

import (
	"bytes"
	"context"
	"io"
	"net"
	"path/filepath"
	"reflect"
	"regexp"
	"runtime/debug"
	"strings"
	"testing"
	"time"

	"github.com/hddq/bluehop/linux/internal/pairing"
	"github.com/hddq/bluehop/linux/internal/peers"
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

func TestCLIPairListenExitsWhenClientCancels(t *testing.T) {
	dir := t.TempDir()
	srvStorePath := filepath.Join(dir, "srv-peers.json")

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
	outCh := make(chan string, 1)
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
				outCh <- acc
				return
			}
		}
	}()

	port := <-portCh
	client := pairing.NewClient(pairing.ClientConfig{PollInterval: 10 * time.Millisecond})
	clientCtx, cancelClient := context.WithCancel(context.Background())
	defer cancelClient()
	clientSASCh := make(chan pairing.SASChallenge, 1)
	clientErrCh := make(chan error, 1)
	go func() {
		_, err := client.PairWithHost(
			clientCtx,
			"127.0.0.1:"+port,
			peers.Identity{DeviceID: "cli-client", DeviceName: "Linux Client"},
			func(ch pairing.SASChallenge) { clientSASCh <- ch },
		)
		clientErrCh <- err
	}()

	<-sasCh
	<-clientSASCh
	cancelClient()

	select {
	case code := <-srvCodeCh:
		if code == 0 {
			t.Fatalf("expected non-zero exit when client cancels pairing")
		}
	case <-time.After(3 * time.Second):
		t.Fatalf("timed out waiting for server to exit on client cancel; stderr=%q", srvStderr.String())
	}
	if err := <-clientErrCh; err == nil {
		t.Fatal("expected cancelled client to fail")
	}
	out := <-outCh
	if !strings.Contains(out, "Pairing cancelled by peer.") {
		t.Fatalf("expected cancellation message in stdout, got %q", out)
	}
}
