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
	"text/tabwriter"
	"time"

	"github.com/hddq/bluehop/linux/internal/bluez"
	"github.com/hddq/bluehop/linux/internal/grab"
	"github.com/hddq/bluehop/linux/internal/holder"
	"github.com/hddq/bluehop/linux/internal/mdns"
	"github.com/hddq/bluehop/linux/internal/pairing"
	"github.com/hddq/bluehop/linux/internal/peers"
	"github.com/hddq/bluehop/linux/internal/protocol"
)

// version can be overridden at link time via:
// go build -ldflags "-X main.version=v0.1.0" -o build/bluehop ./cmd/bluehop
var version = "dev"

type cliDeps struct {
	openBluez        func() (bluez.Controller, error)
	startAdvertising func(mdns.AdvertiseConfig) (mdns.Advertiser, error)
	discoverPeers    func(context.Context, mdns.DiscoverOptions) ([]mdns.DiscoveredPeer, error)
	newHolderClient  func() grab.HolderClient
	shutdownCtx      context.Context
}

func defaultDeps() cliDeps {
	return cliDeps{
		openBluez: func() (bluez.Controller, error) {
			return bluez.ConnectSystem()
		},
		startAdvertising: mdns.StartAdvertising,
		discoverPeers:    mdns.Discover,
		newHolderClient: func() grab.HolderClient {
			return grab.NewHTTPClient(grab.HTTPClientConfig{})
		},
	}
}

func main() {
	os.Exit(runWithIO(os.Args[1:], os.Stdin, os.Stdout, os.Stderr))
}

func run(args []string, stdout, stderr io.Writer) int {
	return runWithIO(args, os.Stdin, stdout, stderr)
}

func runWithIO(args []string, stdin io.Reader, stdout, stderr io.Writer) int {
	return runWithDeps(args, stdin, stdout, stderr, defaultDeps())
}

func runWithDeps(args []string, stdin io.Reader, stdout, stderr io.Writer, deps cliDeps) int {
	fs := flag.NewFlagSet("bluehop", flag.ContinueOnError)
	fs.SetOutput(stderr)
	fs.Usage = func() {
		fmt.Fprintln(stderr, "Usage: bluehop <command> [options]")
		fmt.Fprintln(stderr, "")
		fmt.Fprintln(stderr, "Commands:")
		fmt.Fprintln(stderr, "  grab        Release a Bluetooth device from another host and connect here")
		fmt.Fprintln(stderr, "  devices     List paired Bluetooth devices via BlueZ")
		fmt.Fprintln(stderr, "  connect     Connect to a paired Bluetooth device")
		fmt.Fprintln(stderr, "  disconnect  Disconnect a paired Bluetooth device")
		fmt.Fprintln(stderr, "  discover    Discover BlueHop hosts on the local network (mDNS)")
		fmt.Fprintln(stderr, "  serve       Run the holder HTTP server (/status, /release)")
		fmt.Fprintln(stderr, "  pair        Pair with another BlueHop host via ECDH + 6-digit SAS")
		fmt.Fprintln(stderr, "  peers       List or remove paired hosts")
		fmt.Fprintln(stderr, "  version     Print the bluehop CLI version")
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
	case "grab":
		return runGrab(fs.Args()[1:], stdout, stderr, deps)
	case "devices":
		return runDevices(fs.Args()[1:], stdout, stderr, deps)
	case "connect":
		return runBluetoothAction("connect", fs.Args()[1:], stdout, stderr, deps)
	case "disconnect":
		return runBluetoothAction("disconnect", fs.Args()[1:], stdout, stderr, deps)
	case "discover":
		return runDiscover(fs.Args()[1:], stdout, stderr, deps)
	case "serve":
		return runServe(fs.Args()[1:], stdout, stderr, deps)
	case "pair":
		return runPair(fs.Args()[1:], stdin, stdout, stderr, deps)
	case "peers":
		return runPeers(fs.Args()[1:], stdout, stderr)
	default:
		fmt.Fprintf(stderr, "unknown command: %s\n\n", fs.Arg(0))
		fs.Usage()
		return 2
	}
}

func runPair(args []string, stdin io.Reader, stdout, stderr io.Writer, deps cliDeps) int {
	fs := flag.NewFlagSet("pair", flag.ContinueOnError)
	fs.SetOutput(stderr)
	listenMode := fs.Bool("listen", false, "Wait for an incoming pairing request and display the 6-digit SAS")
	listenAddr := fs.String("addr", ":0", "Listen address when --listen is set (default :0)")
	nameFlag := fs.String("name", "", "Local device name advertised during pairing")
	storeFlag := fs.String("store", "", "Path to peers.json (default XDG config)")
	timeoutFlag := fs.Duration("timeout", (protocol.PairingSessionTTLSec+15)*time.Second, "Pairing timeout")
	fs.Usage = func() {
		fmt.Fprintln(stderr, "Usage:")
		fmt.Fprintln(stderr, "  bluehop pair [options] [<host:port>]  Discover or connect to a waiting host")
		fmt.Fprintln(stderr, "  bluehop pair --listen [options]       Wait for a peer, verify SAS, and Accept/Reject")
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
		return runPairServer(ctx, *listenAddr, identity, store, stdin, stdout, stderr, deps)
	}

	if fs.NArg() > 1 {
		fs.Usage()
		return 2
	}
	target := ""
	if fs.NArg() == 1 {
		target = fs.Arg(0)
	} else {
		var ok bool
		target, ok = discoverPairingTarget(ctx, identity, stdin, stdout, stderr, deps)
		if !ok {
			return 1
		}
	}
	return runPairClient(ctx, target, identity, store, stdout, stderr)
}

func discoverPairingTarget(
	ctx context.Context,
	identity peers.Identity,
	stdin io.Reader,
	stdout, stderr io.Writer,
	deps cliDeps,
) (string, bool) {
	if deps.discoverPeers == nil {
		fmt.Fprintln(stderr, "mDNS discovery unavailable; pass <host:port> explicitly")
		return "", false
	}
	fmt.Fprintln(stdout, "Searching for waiting BlueHop hosts via mDNS...")
	discCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()

	found, err := deps.discoverPeers(discCtx, mdns.DiscoverOptions{
		ExcludeDeviceID: identity.DeviceID,
		PairingOnly:     true,
	})
	if err != nil {
		fmt.Fprintf(stderr, "mDNS discovery failed: %v (pass <host:port> explicitly)\n", err)
		return "", false
	}
	if len(found) == 0 {
		fmt.Fprintln(stderr, "No BlueHop hosts advertising pairing on the LAN. Start pairing on the peer or pass <host:port>.")
		return "", false
	}
	if len(found) == 1 {
		fmt.Fprintf(stdout, "Discovered %s at %s\n", found[0].DisplayName, found[0].Endpoint())
		return found[0].Endpoint(), true
	}

	fmt.Fprintln(stdout, "Discovered multiple pairing hosts:")
	for i, p := range found {
		fmt.Fprintf(stdout, "  [%d] %s (%s)\n", i+1, p.DisplayName, p.Endpoint())
	}
	fmt.Fprintf(stdout, "Select host [1-%d]: ", len(found))

	line, _ := bufio.NewReader(stdin).ReadString('\n')
	idx, err := strconv.Atoi(strings.TrimSpace(line))
	if err != nil || idx < 1 || idx > len(found) {
		fmt.Fprintln(stderr, "invalid host selection")
		return "", false
	}
	return found[idx-1].Endpoint(), true
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
	deps cliDeps,
) int {
	ln, err := net.Listen("tcp", listenAddr)
	if err != nil {
		fmt.Fprintf(stderr, "listen %s: %v\n", listenAddr, err)
		return 1
	}
	defer ln.Close()

	pendingCh := make(chan pairing.PendingSession, 1)
	rejectedObservedCh := make(chan string, 1)

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
	var adv mdns.Advertiser
	if deps.startAdvertising != nil && !isLoopbackAddr(ln.Addr()) {
		if port, convErr := strconv.Atoi(portStr); convErr == nil && port > 0 {
			var advErr error
			adv, advErr = deps.startAdvertising(mdns.AdvertiseConfig{
				DeviceID:   identity.DeviceID,
				DeviceName: identity.DeviceName,
				Port:       port,
				Pairing:    true,
			})
			if advErr != nil {
				fmt.Fprintf(stderr, "warning: mDNS pairing advertisement failed: %v\n", advErr)
			} else {
				defer adv.Shutdown()
			}
		}
	}

	fmt.Fprintf(stdout, "Waiting for pairing request as %s on port %s\n", identity.DeviceName, portStr)
	if adv != nil {
		fmt.Fprintf(stdout, "Advertising pairing via mDNS as %q (%s)\n", mdns.BuildServiceName(identity.DeviceName, true), mdns.ServiceType)
	}
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

func runDevices(args []string, stdout, stderr io.Writer, deps cliDeps) int {
	fs := flag.NewFlagSet("devices", flag.ContinueOnError)
	fs.SetOutput(stderr)
	timeoutFlag := fs.Duration("timeout", 5*time.Second, "BlueZ D-Bus timeout")
	fs.Usage = func() {
		fmt.Fprintln(stderr, "Usage:")
		fmt.Fprintln(stderr, "  bluehop devices [options]")
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
	if fs.NArg() > 0 {
		fs.Usage()
		return 2
	}

	ctrl, err := deps.openBluez()
	if err != nil {
		fmt.Fprintf(stderr, "open BlueZ controller: %v\n", err)
		return 1
	}
	defer ctrl.Close()

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if *timeoutFlag > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, *timeoutFlag)
		defer cancel()
	}

	devices, err := ctrl.ListDevices(ctx)
	if err != nil {
		fmt.Fprintf(stderr, "list Bluetooth devices: %v\n", err)
		return 1
	}
	if len(devices) == 0 {
		fmt.Fprintln(stdout, "No paired Bluetooth devices.")
		return 0
	}
	for _, d := range devices {
		status := "disconnected"
		if d.Connected {
			status = "connected"
		}
		fmt.Fprintf(stdout, "%s\t%s\t%s\n", d.Address, status, d.DisplayName())
	}
	return 0
}

func runBluetoothAction(action string, args []string, stdout, stderr io.Writer, deps cliDeps) int {
	defaultTimeout := 30 * time.Second
	if action == "disconnect" {
		defaultTimeout = 15 * time.Second
	}

	fs := flag.NewFlagSet(action, flag.ContinueOnError)
	fs.SetOutput(stderr)
	timeoutFlag := fs.Duration("timeout", defaultTimeout, "BlueZ D-Bus operation timeout")
	fs.Usage = func() {
		fmt.Fprintln(stderr, "Usage:")
		fmt.Fprintf(stderr, "  bluehop %s [options] <mac-or-name>\n", action)
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
	if fs.NArg() != 1 {
		fs.Usage()
		return 2
	}

	ctrl, err := deps.openBluez()
	if err != nil {
		fmt.Fprintf(stderr, "open BlueZ controller: %v\n", err)
		return 1
	}
	defer ctrl.Close()

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if *timeoutFlag > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, *timeoutFlag)
		defer cancel()
	}

	target := fs.Arg(0)
	if action == "connect" {
		dev, err := ctrl.Connect(ctx, target)
		if errors.Is(err, bluez.ErrAlreadyConnected) {
			fmt.Fprintf(stdout, "%s (%s) is already connected\n", dev.DisplayName(), dev.Address)
			return 0
		}
		if err != nil {
			fmt.Fprintf(stderr, "%v\n", err)
			return 1
		}
		fmt.Fprintf(stdout, "Connected to %s (%s)\n", dev.DisplayName(), dev.Address)
		return 0
	}

	dev, err := ctrl.Disconnect(ctx, target)
	if errors.Is(err, bluez.ErrNotConnected) {
		fmt.Fprintf(stdout, "%s (%s) is already disconnected\n", dev.DisplayName(), dev.Address)
		return 0
	}
	if err != nil {
		fmt.Fprintf(stderr, "%v\n", err)
		return 1
	}
	fmt.Fprintf(stdout, "Disconnected from %s (%s)\n", dev.DisplayName(), dev.Address)
	return 0
}

type discovererFunc func(context.Context, mdns.DiscoverOptions) ([]mdns.DiscoveredPeer, error)

func (f discovererFunc) Discover(ctx context.Context, opts mdns.DiscoverOptions) ([]mdns.DiscoveredPeer, error) {
	return f(ctx, opts)
}

func runGrab(args []string, stdout, stderr io.Writer, deps cliDeps) int {
	fs := flag.NewFlagSet("grab", flag.ContinueOnError)
	fs.SetOutput(stderr)
	discoverTimeout := fs.Duration("discover-timeout", grab.DefaultDiscoveryTimeout, "mDNS discovery timeout")
	timeoutFlag := fs.Duration("timeout", 45*time.Second, "Overall grab operation timeout")
	storeFlag := fs.String("store", "", "Path to peers.json (default XDG config)")
	nameFlag := fs.String("name", "", "Local device name override")
	fs.Usage = func() {
		fmt.Fprintln(stderr, "Usage:")
		fmt.Fprintln(stderr, "  bluehop grab [options] <mac-or-name>")
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
	if fs.NArg() != 1 {
		fs.Usage()
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
	pairedPeers, err := store.ListPeers()
	if err != nil {
		fmt.Fprintf(stderr, "load paired peers: %v\n", err)
		return 1
	}

	ctrl, err := deps.openBluez()
	if err != nil {
		fmt.Fprintf(stderr, "open BlueZ controller: %v\n", err)
		return 1
	}
	defer ctrl.Close()

	ctx := deps.shutdownCtx
	if ctx == nil {
		var stop context.CancelFunc
		ctx, stop = signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
		defer stop()
	}
	if *timeoutFlag > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, *timeoutFlag)
		defer cancel()
	}

	var disc mdns.Discoverer
	if deps.discoverPeers != nil {
		disc = discovererFunc(deps.discoverPeers)
	}
	var holderClient grab.HolderClient
	if deps.newHolderClient != nil {
		holderClient = deps.newHolderClient()
	}

	res, err := grab.Grab(ctx, grab.Options{
		Target:           fs.Arg(0),
		Identity:         identity,
		Peers:            pairedPeers,
		Controller:       ctrl,
		Discoverer:       disc,
		HolderClient:     holderClient,
		DiscoveryTimeout: *discoverTimeout,
		OnProgress: func(p grab.Progress) {
			switch p.Stage {
			case grab.StageSearching:
				fmt.Fprintln(stdout, "Searching for BlueHop peers on LAN...")
			case grab.StageReleasing:
				fmt.Fprintf(stdout, "Releasing %s (%s) from %s...\n", p.Device.DisplayName(), p.Device.Address, p.HolderName)
			case grab.StageConnecting:
				fmt.Fprintf(stdout, "Connecting to %s (%s)...\n", p.Device.DisplayName(), p.Device.Address)
			}
		},
	})
	if err != nil {
		fmt.Fprintf(stderr, "%v\n", err)
		return 1
	}

	if res.AlreadyConnected {
		fmt.Fprintf(stdout, "%s (%s) is already connected\n", res.Device.DisplayName(), res.Device.Address)
		return 0
	}
	if res.ReleasedFrom != "" {
		fmt.Fprintf(stdout, "Connected to %s (%s) (released from %s)\n", res.Device.DisplayName(), res.Device.Address, res.ReleasedFrom)
		return 0
	}
	fmt.Fprintf(stdout, "Connected to %s (%s)\n", res.Device.DisplayName(), res.Device.Address)
	return 0
}

func runServe(args []string, stdout, stderr io.Writer, deps cliDeps) int {
	fs := flag.NewFlagSet("serve", flag.ContinueOnError)
	fs.SetOutput(stderr)
	addrFlag := fs.String("addr", holder.DefaultAddr, "Listen address")
	nameFlag := fs.String("name", "", "Local device name reported in /status")
	storeFlag := fs.String("store", "", "Path to peers.json (default XDG config)")
	fs.Usage = func() {
		fmt.Fprintln(stderr, "Usage:")
		fmt.Fprintln(stderr, "  bluehop serve [options]")
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
	if fs.NArg() > 0 {
		fs.Usage()
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

	ctrl, err := deps.openBluez()
	if err != nil {
		fmt.Fprintf(stderr, "open BlueZ controller: %v\n", err)
		return 1
	}
	defer ctrl.Close()

	srv := holder.NewServer(holder.Config{
		Identity:      identity,
		Backend:       holder.NewBluezBackend(ctrl),
		PeerPSKLookup: store.GetPeerPSK,
		OnLog: func(msg string) {
			fmt.Fprintln(stderr, msg)
		},
	})

	port, err := srv.Start(*addrFlag)
	if err != nil {
		fmt.Fprintf(stderr, "start holder server on %s: %v\n", *addrFlag, err)
		return 1
	}

	var adv mdns.Advertiser
	if deps.startAdvertising != nil && !isLoopbackAddr(srv.Addr()) {
		connectedAddrs := connectedBluetoothAddresses(ctrl)
		var advErr error
		adv, advErr = deps.startAdvertising(mdns.AdvertiseConfig{
			DeviceID:           identity.DeviceID,
			DeviceName:         identity.DeviceName,
			Port:               port,
			ConnectedAddresses: connectedAddrs,
			Pairing:            false,
		})
		if advErr != nil {
			fmt.Fprintf(stderr, "warning: mDNS advertisement failed: %v\n", advErr)
		} else {
			defer adv.Shutdown()
		}
	}

	fmt.Fprintf(stdout, "Serving BlueHop holder endpoints as %s (%s) on port %d\n", identity.DeviceName, identity.DeviceID, port)
	if adv != nil {
		fmt.Fprintf(stdout, "Advertising mDNS service %q (%s)\n", mdns.BuildServiceName(identity.DeviceName, false), mdns.ServiceType)
	}
	if addr := srv.Addr(); addr != nil {
		for _, ep := range listeningEndpoints(*addrFlag, addr) {
			fmt.Fprintf(stdout, "  http://%s\n", ep)
		}
	}

	ctx := deps.shutdownCtx
	if ctx == nil {
		var stop context.CancelFunc
		ctx, stop = signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
		defer stop()
	}
	<-ctx.Done()

	if adv != nil {
		adv.Shutdown()
	}

	stopCtx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if err := srv.Stop(stopCtx); err != nil {
		fmt.Fprintf(stderr, "stop holder server: %v\n", err)
		return 1
	}
	return 0
}

func runDiscover(args []string, stdout, stderr io.Writer, deps cliDeps) int {
	fs := flag.NewFlagSet("discover", flag.ContinueOnError)
	fs.SetOutput(stderr)
	timeoutFlag := fs.Duration("timeout", 3*time.Second, "mDNS discovery duration")
	pairingFlag := fs.Bool("pairing", false, "Only show hosts advertising pairing (pair=1)")
	storeFlag := fs.String("store", "", "Path to peers.json (default XDG config)")
	fs.Usage = func() {
		fmt.Fprintln(stderr, "Usage:")
		fmt.Fprintln(stderr, "  bluehop discover [options]")
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
	if fs.NArg() > 0 {
		fs.Usage()
		return 2
	}
	if deps.discoverPeers == nil {
		fmt.Fprintln(stderr, "mDNS discovery unavailable")
		return 1
	}

	var excludeID string
	pairedIDs := make(map[string]bool)
	storePath, storeErr := resolveStorePath(*storeFlag)
	if storeErr != nil {
		if strings.TrimSpace(*storeFlag) != "" {
			fmt.Fprintf(stderr, "resolve peer store path: %v\n", storeErr)
			return 1
		}
	} else {
		store := peers.NewStore(storePath)
		if id, err := store.GetOrCreateIdentity(""); err == nil {
			excludeID = id.DeviceID
		}
		if list, err := store.ListPeers(); err == nil {
			for _, p := range list {
				pairedIDs[p.PeerID] = true
			}
		}
	}

	ctx := deps.shutdownCtx
	if ctx == nil {
		var stop context.CancelFunc
		ctx, stop = signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
		defer stop()
	}
	if *timeoutFlag > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, *timeoutFlag)
		defer cancel()
	}

	found, err := deps.discoverPeers(ctx, mdns.DiscoverOptions{
		ExcludeDeviceID: excludeID,
		PairingOnly:     *pairingFlag,
	})
	if err != nil {
		fmt.Fprintf(stderr, "discover BlueHop hosts: %v\n", err)
		return 1
	}
	if len(found) == 0 {
		if *pairingFlag {
			fmt.Fprintln(stdout, "No BlueHop hosts advertising pairing found.")
		} else {
			fmt.Fprintln(stdout, "No BlueHop hosts found.")
		}
		return 0
	}

	tw := tabwriter.NewWriter(stdout, 0, 4, 2, ' ', 0)
	fmt.Fprintln(tw, "NAME\tENDPOINT\tMODE\tTRUST\tDEVICES\tID")
	for _, p := range found {
		mode := "holder"
		if p.PairingAvailable {
			mode = "pairing"
		}
		trust := "unpaired"
		if p.DeviceID != "" && pairedIDs[p.DeviceID] {
			trust = "paired"
		}
		devs := "-"
		if len(p.ConnectedAddresses) > 0 {
			devs = strings.Join(p.ConnectedAddresses, ",")
		}
		id := p.DeviceID
		if id == "" {
			id = "-"
		}
		fmt.Fprintf(tw, "%s\t%s\t%s\t%s\t%s\t%s\n", p.DisplayName, p.Endpoint(), mode, trust, devs, id)
	}
	_ = tw.Flush()
	return 0
}

func connectedBluetoothAddresses(ctrl bluez.Controller) []string {
	if ctrl == nil {
		return nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	devices, err := ctrl.ListDevices(ctx)
	if err != nil {
		return nil
	}
	var addrs []string
	for _, d := range devices {
		if d.Connected && strings.TrimSpace(d.Address) != "" {
			addrs = append(addrs, d.Address)
		}
	}
	return addrs
}

func isLoopbackAddr(addr net.Addr) bool {
	if addr == nil {
		return false
	}
	tcpAddr, ok := addr.(*net.TCPAddr)
	if !ok || tcpAddr.IP == nil {
		return false
	}
	return tcpAddr.IP.IsLoopback()
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
