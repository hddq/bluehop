package main

import (
	"bufio"
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/signal"
	"runtime/debug"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/hddq/bluehop/linux/internal/pairing"
	"github.com/hddq/bluehop/linux/internal/peers"
	"github.com/hddq/bluehop/linux/internal/protocol"
)

// version can be overridden at link time via:
// go build -ldflags "-X main.version=v0.1.0" -o build/bluehop ./cmd/bluehop
var version = "dev"

func main() {
	os.Exit(runWithIO(os.Args[1:], os.Stdin, os.Stdout, os.Stderr))
}

func run(args []string, stdout, stderr io.Writer) int {
	return runWithIO(args, os.Stdin, stdout, stderr)
}

func runWithIO(args []string, stdin io.Reader, stdout, stderr io.Writer) int {
	fs := flag.NewFlagSet("bluehop", flag.ContinueOnError)
	fs.SetOutput(stderr)
	fs.Usage = func() {
		fmt.Fprintln(stderr, "Usage: bluehop <command> [options]")
		fmt.Fprintln(stderr, "")
		fmt.Fprintln(stderr, "Commands:")
		fmt.Fprintln(stderr, "  pair      Pair with another BlueHop host via ECDH + 6-digit SAS")
		fmt.Fprintln(stderr, "  peers     List or remove paired hosts")
		fmt.Fprintln(stderr, "  version   Print the bluehop CLI version")
		fmt.Fprintln(stderr, "")
		fmt.Fprintln(stderr, "Options:")
		fs.PrintDefaults()
	}

	if err := fs.Parse(args); err != nil {
		if err == flag.ErrHelp {
			return 0
		}
		return 2
	}

	if fs.NArg() == 0 {
		fs.Usage()
		return 0
	}

	switch fs.Arg(0) {
	case "version":
		if fs.NArg() > 1 {
			fmt.Fprintf(stderr, "version command does not accept arguments: %v\n\n", fs.Args()[1:])
			fs.Usage()
			return 2
		}
		if _, err := fmt.Fprintf(stdout, "bluehop %s\n", resolveVersion()); err != nil {
			fmt.Fprintf(stderr, "failed to write version output: %v\n", err)
			return 1
		}
		return 0
	case "pair":
		return runPair(fs.Args()[1:], stdin, stdout, stderr)
	case "peers":
		return runPeers(fs.Args()[1:], stdout, stderr)
	default:
		fmt.Fprintf(stderr, "unknown command: %s\n\n", fs.Arg(0))
		fs.Usage()
		return 2
	}
}

func runPair(args []string, stdin io.Reader, stdout, stderr io.Writer) int {
	fs := flag.NewFlagSet("pair", flag.ContinueOnError)
	fs.SetOutput(stderr)
	listenMode := fs.Bool("listen", false, "Wait for an incoming pairing request and display the 6-digit SAS")
	listenAddr := fs.String("addr", ":0", "Listen address when --listen is set (default :0)")
	nameFlag := fs.String("name", "", "Local device name advertised during pairing")
	storeFlag := fs.String("store", "", "Path to peers.json (default XDG config)")
	timeoutFlag := fs.Duration("timeout", (protocol.PairingSessionTTLSec+15)*time.Second, "Pairing timeout")
	fs.Usage = func() {
		fmt.Fprintln(stderr, "Usage:")
		fmt.Fprintln(stderr, "  bluehop pair [options] <host:port>   Connect to a waiting host and verify SAS")
		fmt.Fprintln(stderr, "  bluehop pair --listen [options]      Wait for a peer, verify SAS, and Accept/Reject")
		fmt.Fprintln(stderr, "")
		fmt.Fprintln(stderr, "Options:")
		fs.PrintDefaults()
	}
	if err := fs.Parse(args); err != nil {
		if err == flag.ErrHelp {
			return 0
		}
		return 2
	}

	storePath, err := resolveStorePath(*storeFlag)
	if err != nil {
		fmt.Fprintf(stderr, "resolve peer store path: %v\n", err)
		return 1
	}
	store := peers.NewStore(storePath)
	identity, err := store.GetOrCreateIdentity(*nameFlag)
	if err != nil {
		fmt.Fprintf(stderr, "load local identity: %v\n", err)
		return 1
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if *timeoutFlag > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, *timeoutFlag)
		defer cancel()
	}

	if *listenMode {
		if fs.NArg() > 0 {
			fmt.Fprintf(stderr, "unexpected arguments with --listen: %v\n", fs.Args())
			return 2
		}
		return runPairServer(ctx, *listenAddr, identity, store, stdin, stdout, stderr)
	}

	if fs.NArg() != 1 {
		fs.Usage()
		return 2
	}
	return runPairClient(ctx, fs.Arg(0), identity, store, stdout, stderr)
}

func runPairClient(
	ctx context.Context,
	target string,
	identity peers.Identity,
	store *peers.Store,
	stdout, stderr io.Writer,
) int {
	client := pairing.NewClient(pairing.ClientConfig{
		OnPeerPaired: store.UpsertPeer,
	})
	peer, err := client.PairWithHost(ctx, target, identity, func(ch pairing.SASChallenge) {
		fmt.Fprintf(stdout, "Pairing with %s (%s)\n", ch.ResponderName, ch.ResponderID)
		fmt.Fprintf(stdout, "Verification code (SAS): %s\n", formatSAS(ch.SAS))
		fmt.Fprintln(stdout, "Confirm that this 6-digit code matches on the other device and accept it there...")
	})
	if err != nil {
		fmt.Fprintf(stderr, "pairing failed: %v\n", err)
		return 1
	}
	fmt.Fprintf(stdout, "Paired with %s (%s)\n", peer.PeerName, peer.PeerID)
	return 0
}

func runPairServer(
	ctx context.Context,
	listenAddr string,
	identity peers.Identity,
	store *peers.Store,
	stdin io.Reader,
	stdout, stderr io.Writer,
) int {
	ln, err := net.Listen("tcp", listenAddr)
	if err != nil {
		fmt.Fprintf(stderr, "listen %s: %v\n", listenAddr, err)
		return 1
	}
	defer ln.Close()

	pendingCh := make(chan pairing.PendingSession, 1)
	rejectedObservedCh := make(chan string, 1)
	cancelledCh := make(chan string, 1)

	type pairResult struct {
		peer peers.Peer
		err  error
	}
	pairDoneCh := make(chan pairResult, 1)
	var pairMu sync.Mutex
	var confirmStarted bool
	var aborted bool

	srv := pairing.NewServer(pairing.ServerConfig{
		Identity:      identity,
		SingleSession: true,
		OnPeerPaired: func(p peers.Peer) error {
			pairMu.Lock()
			if aborted {
				pairMu.Unlock()
				return errors.New("pairing listener timed out")
			}
			confirmStarted = true
			pairMu.Unlock()

			err := store.UpsertPeer(p)
			select {
			case pairDoneCh <- pairResult{peer: p, err: err}:
			default:
			}
			return err
		},
		OnPendingSession: func(ps pairing.PendingSession) {
			select {
			case pendingCh <- ps:
			default:
			}
		},
		OnStatusObserved: func(sessionID string, status protocol.PairSessionStatus) {
			if status == protocol.PairStatusRejected {
				select {
				case rejectedObservedCh <- sessionID:
				default:
				}
			}
		},
		OnCancelled: func(sessionID string) {
			select {
			case cancelledCh <- sessionID:
			default:
			}
		},
	})

	httpSrv := &http.Server{
		Handler:           srv.Handler(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      10 * time.Second,
		IdleTimeout:       30 * time.Second,
	}
	serveErrCh := make(chan error, 1)
	go func() {
		err := httpSrv.Serve(ln)
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			serveErrCh <- err
		}
		close(serveErrCh)
	}()
	defer func() {
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		_ = httpSrv.Shutdown(shutdownCtx)
	}()

	_, portStr, _ := net.SplitHostPort(ln.Addr().String())
	fmt.Fprintf(stdout, "Waiting for pairing request as %s on port %s\n", identity.DeviceName, portStr)
	for _, ep := range listeningEndpoints(listenAddr, ln.Addr()) {
		fmt.Fprintf(stdout, "  %s\n", ep)
	}

	var pending pairing.PendingSession
	select {
	case pending = <-pendingCh:
	case err := <-serveErrCh:
		if err != nil {
			fmt.Fprintf(stderr, "pairing server error: %v\n", err)
			return 1
		}
		return 1
	case <-ctx.Done():
		fmt.Fprintln(stderr, "pairing timed out waiting for peer")
		return 1
	}

	fmt.Fprintf(stdout, "\nIncoming pairing request from %s (%s)\n", pending.RequesterName, pending.RequesterID)
	fmt.Fprintf(stdout, "Verification code (SAS): %s\n", formatSAS(pending.SAS))
	fmt.Fprint(stdout, "Accept pairing? [y/N]: ")

	answerCh := make(chan string, 1)
	go func() {
		reader := bufio.NewReader(stdin)
		line, _ := reader.ReadString('\n')
		answerCh <- strings.TrimSpace(strings.ToLower(line))
	}()

	var accepted bool
	select {
	case ans := <-answerCh:
		accepted = ans == "y" || ans == "yes"
	case <-cancelledCh:
		fmt.Fprintln(stdout, "\nPairing cancelled by peer.")
		return 1
	case <-ctx.Done():
		_ = srv.Reject(pending.SessionID)
		fmt.Fprintln(stderr, "\npairing timed out")
		return 1
	}

	if !accepted {
		_ = srv.Reject(pending.SessionID)
		fmt.Fprintln(stdout, "Pairing rejected.")
		// Keep serving rejected status until client observes it or a >500ms poll grace window elapses.
		timer := time.NewTimer(1500 * time.Millisecond)
		defer timer.Stop()
	waitReject:
		for {
			select {
			case observed := <-rejectedObservedCh:
				if observed == pending.SessionID {
					break waitReject
				}
			case <-timer.C:
				break waitReject
			case <-ctx.Done():
				break waitReject
			}
		}
		return 1
	}

	if err := srv.Approve(pending.SessionID); err != nil {
		fmt.Fprintf(stderr, "failed to approve pairing: %v\n", err)
		return 1
	}

	fmt.Fprintln(stdout, "Approved. Waiting for peer key confirmation...")
	select {
	case res := <-pairDoneCh:
		if res.err != nil {
			fmt.Fprintf(stderr, "failed to save paired peer: %v\n", res.err)
			return 1
		}
		fmt.Fprintf(stdout, "Paired with %s (%s)\n", res.peer.PeerName, res.peer.PeerID)
		return 0
	case <-cancelledCh:
		fmt.Fprintln(stdout, "Pairing cancelled by peer.")
		return 1
	case <-ctx.Done():
		pairMu.Lock()
		started := confirmStarted
		if !started {
			aborted = true
		}
		pairMu.Unlock()
		if started {
			res := <-pairDoneCh
			if res.err != nil {
				fmt.Fprintf(stderr, "failed to save paired peer: %v\n", res.err)
				return 1
			}
			fmt.Fprintf(stdout, "Paired with %s (%s)\n", res.peer.PeerName, res.peer.PeerID)
			return 0
		}
		fmt.Fprintln(stderr, "timed out waiting for peer key confirmation")
		return 1
	}
}

func runPeers(args []string, stdout, stderr io.Writer) int {
	fs := flag.NewFlagSet("peers", flag.ContinueOnError)
	fs.SetOutput(stderr)
	storeFlag := fs.String("store", "", "Path to peers.json (default XDG config)")
	fs.Usage = func() {
		fmt.Fprintln(stderr, "Usage:")
		fmt.Fprintln(stderr, "  bluehop peers [options] [list]")
		fmt.Fprintln(stderr, "  bluehop peers [options] remove <peerId>")
		fmt.Fprintln(stderr, "")
		fmt.Fprintln(stderr, "Options:")
		fs.PrintDefaults()
	}
	if err := fs.Parse(args); err != nil {
		if err == flag.ErrHelp {
			return 0
		}
		return 2
	}

	storePath, err := resolveStorePath(*storeFlag)
	if err != nil {
		fmt.Fprintf(stderr, "resolve peer store path: %v\n", err)
		return 1
	}
	store := peers.NewStore(storePath)

	sub := "list"
	if fs.NArg() > 0 {
		sub = fs.Arg(0)
	}
	switch sub {
	case "list":
		if fs.NArg() > 1 {
			fs.Usage()
			return 2
		}
		list, err := store.ListPeers()
		if err != nil {
			fmt.Fprintf(stderr, "list peers: %v\n", err)
			return 1
		}
		if len(list) == 0 {
			fmt.Fprintln(stdout, "No paired peers.")
			return 0
		}
		for _, p := range list {
			fmt.Fprintf(stdout, "%s\t%s\n", p.PeerID, p.PeerName)
		}
		return 0
	case "remove":
		if fs.NArg() != 2 {
			fs.Usage()
			return 2
		}
		removed, err := store.RemovePeer(fs.Arg(1))
		if err != nil {
			fmt.Fprintf(stderr, "remove peer: %v\n", err)
			return 1
		}
		if !removed {
			fmt.Fprintf(stderr, "peer not found: %s\n", fs.Arg(1))
			return 1
		}
		fmt.Fprintf(stdout, "Removed peer %s\n", fs.Arg(1))
		return 0
	default:
		fs.Usage()
		return 2
	}
}

func resolveStorePath(override string) (string, error) {
	if trimmed := strings.TrimSpace(override); trimmed != "" {
		return trimmed, nil
	}
	return peers.DefaultPath()
}

func formatSAS(sas string) string {
	if len(sas) == 6 {
		return sas[:3] + " " + sas[3:]
	}
	return sas
}

func listeningEndpoints(listenAddr string, boundAddr net.Addr) []string {
	tcpAddr, ok := boundAddr.(*net.TCPAddr)
	if !ok {
		return []string{boundAddr.String()}
	}
	portStr := strconv.Itoa(tcpAddr.Port)
	if tcpAddr.IP != nil && !tcpAddr.IP.IsUnspecified() {
		return []string{net.JoinHostPort(tcpAddr.IP.String(), portStr)}
	}

	reqHost, _, _ := net.SplitHostPort(listenAddr)
	wantV4, wantV6 := true, true
	switch reqHost {
	case "0.0.0.0":
		wantV6 = false
	case "::":
		wantV4 = false
	}
	return localListeningEndpoints(portStr, wantV4, wantV6)
}

func localListeningEndpoints(port string, wantV4, wantV6 bool) []string {
	ifaces, err := net.Interfaces()
	if err != nil {
		return nil
	}
	var out []string
	for _, iface := range ifaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, a := range addrs {
			ipNet, ok := a.(*net.IPNet)
			if !ok || ipNet.IP == nil || ipNet.IP.IsLoopback() || ipNet.IP.IsLinkLocalUnicast() {
				continue
			}
			isV4 := ipNet.IP.To4() != nil
			if isV4 && !wantV4 {
				continue
			}
			if !isV4 && !wantV6 {
				continue
			}
			out = append(out, net.JoinHostPort(ipNet.IP.String(), port))
		}
	}
	return out
}

func resolveVersion() string {
	if version != "" && version != "dev" {
		return version
	}

	info, ok := debug.ReadBuildInfo()
	if !ok {
		return "dev"
	}
	return formatBuildVersion(info)
}

func formatBuildVersion(info *debug.BuildInfo) string {
	if info == nil {
		return "dev"
	}
	if info.Main.Version != "" && info.Main.Version != "(devel)" {
		return info.Main.Version
	}

	var revision string
	var dirty bool
	for _, setting := range info.Settings {
		switch setting.Key {
		case "vcs.revision":
			revision = setting.Value
		case "vcs.modified":
			dirty = setting.Value == "true"
		}
	}
	if revision == "" {
		return "dev"
	}
	if len(revision) > 7 {
		revision = revision[:7]
	}
	if dirty {
		return "dev+" + revision + "-dirty"
	}
	return "dev+" + revision
}
