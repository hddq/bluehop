package pairing

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/hddq/bluehop/linux/internal/peers"
	"github.com/hddq/bluehop/linux/internal/protocol"
)

func TestEndToEndPairingApproveAndPersist(t *testing.T) {
	dir := t.TempDir()
	serverStore := peers.NewStore(filepath.Join(dir, "server.json"))
	clientStore := peers.NewStore(filepath.Join(dir, "client.json"))

	// Seed an existing peer on both stores to ensure upgrade/pairing preserves prior pairings.
	seed := peers.Peer{
		PeerID:           "legacy-peer",
		PeerName:         "Legacy Host",
		PSKHex:           strings.Repeat("ab", 32),
		PairedAtEpochSec: 1700000000,
	}
	if err := serverStore.UpsertPeer(seed); err != nil {
		t.Fatalf("seed server store: %v", err)
	}
	if err := clientStore.UpsertPeer(seed); err != nil {
		t.Fatalf("seed client store: %v", err)
	}

	serverID, err := serverStore.GetOrCreateIdentity("Pixel 9")
	if err != nil {
		t.Fatalf("server identity: %v", err)
	}
	clientID, err := clientStore.GetOrCreateIdentity("Linux Laptop")
	if err != nil {
		t.Fatalf("client identity: %v", err)
	}

	pendingCh := make(chan PendingSession, 1)
	srv := NewServer(ServerConfig{
		Identity:     serverID,
		OnPeerPaired: serverStore.UpsertPeer,
		OnPendingSession: func(p PendingSession) {
			pendingCh <- p
		},
		SessionTTL: 60 * time.Second,
	})
	httpSrv := httptest.NewServer(srv.Handler())
	defer httpSrv.Close()

	client := NewClient(ClientConfig{
		PollInterval: 10 * time.Millisecond,
		OnPeerPaired: clientStore.UpsertPeer,
	})

	clientSASCh := make(chan SASChallenge, 1)
	type pairOutcome struct {
		peer peers.Peer
		err  error
	}
	doneCh := make(chan pairOutcome, 1)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	go func() {
		peer, err := client.PairWithHost(ctx, httpSrv.URL, clientID, func(ch SASChallenge) {
			clientSASCh <- ch
		})
		doneCh <- pairOutcome{peer: peer, err: err}
	}()

	serverPending := <-pendingCh
	clientSAS := <-clientSASCh
	if serverPending.SAS != clientSAS.SAS || len(serverPending.SAS) != 6 {
		t.Fatalf("SAS mismatch: server=%q client=%q", serverPending.SAS, clientSAS.SAS)
	}

	// Neither side may persist the peer before user approval and mutual confirmation.
	if list, _ := serverStore.ListPeers(); len(list) != 1 {
		t.Fatalf("server persisted peer before approval: %+v", list)
	}
	if list, _ := clientStore.ListPeers(); len(list) != 1 {
		t.Fatalf("client persisted peer before approval: %+v", list)
	}

	if err := srv.Approve(serverPending.SessionID); err != nil {
		t.Fatalf("Approve: %v", err)
	}

	res := <-doneCh
	if res.err != nil {
		t.Fatalf("PairWithHost failed: %v", res.err)
	}

	serverPSK, err := serverStore.GetPeerPSK(clientID.DeviceID)
	if err != nil {
		t.Fatalf("server missing paired peer: %v", err)
	}
	clientPSK, err := clientStore.GetPeerPSK(serverID.DeviceID)
	if err != nil {
		t.Fatalf("client missing paired peer: %v", err)
	}
	if !bytes.Equal(serverPSK, clientPSK) {
		t.Fatalf("derived PSKs do not match")
	}

	// Seeded legacy peer must remain intact on both sides.
	if _, err := serverStore.GetPeerPSK(seed.PeerID); err != nil {
		t.Fatalf("legacy peer missing on server: %v", err)
	}
	if _, err := clientStore.GetPeerPSK(seed.PeerID); err != nil {
		t.Fatalf("legacy peer missing on client: %v", err)
	}
}

func TestPairingRejectDoesNotPersist(t *testing.T) {
	dir := t.TempDir()
	serverStore := peers.NewStore(filepath.Join(dir, "server.json"))
	clientStore := peers.NewStore(filepath.Join(dir, "client.json"))

	pendingCh := make(chan PendingSession, 1)
	srv := NewServer(ServerConfig{
		Identity:     peers.Identity{DeviceID: "server-1", DeviceName: "Server"},
		OnPeerPaired: serverStore.UpsertPeer,
		OnPendingSession: func(p PendingSession) {
			pendingCh <- p
		},
	})
	httpSrv := httptest.NewServer(srv.Handler())
	defer httpSrv.Close()

	client := NewClient(ClientConfig{
		PollInterval: 10 * time.Millisecond,
		OnPeerPaired: clientStore.UpsertPeer,
	})

	errCh := make(chan error, 1)
	go func() {
		_, err := client.PairWithHost(
			context.Background(),
			httpSrv.URL,
			peers.Identity{DeviceID: "client-1", DeviceName: "Client"},
			nil,
		)
		errCh <- err
	}()

	pending := <-pendingCh
	if err := srv.Reject(pending.SessionID); err != nil {
		t.Fatalf("Reject: %v", err)
	}

	err := <-errCh
	if err == nil || !strings.Contains(err.Error(), "rejected") {
		t.Fatalf("expected rejection error, got %v", err)
	}
	if list, _ := serverStore.ListPeers(); len(list) != 0 {
		t.Fatalf("server persisted rejected peer")
	}
	if list, _ := clientStore.ListPeers(); len(list) != 0 {
		t.Fatalf("client persisted rejected peer")
	}
}

func TestPairingTTLExpiryDoesNotPersist(t *testing.T) {
	var nowUnix atomic.Int64
	nowUnix.Store(1700000000)

	dir := t.TempDir()
	serverStore := peers.NewStore(filepath.Join(dir, "server.json"))
	clientStore := peers.NewStore(filepath.Join(dir, "client.json"))

	pendingCh := make(chan PendingSession, 1)
	srv := NewServer(ServerConfig{
		Identity:     peers.Identity{DeviceID: "server-1", DeviceName: "Server"},
		OnPeerPaired: serverStore.UpsertPeer,
		OnPendingSession: func(p PendingSession) {
			pendingCh <- p
		},
		SessionTTL: 60 * time.Second,
		Now:        func() time.Time { return time.Unix(nowUnix.Load(), 0) },
	})
	httpSrv := httptest.NewServer(srv.Handler())
	defer httpSrv.Close()

	client := NewClient(ClientConfig{
		PollInterval: 10 * time.Millisecond,
		OnPeerPaired: clientStore.UpsertPeer,
	})

	errCh := make(chan error, 1)
	go func() {
		_, err := client.PairWithHost(
			context.Background(),
			httpSrv.URL,
			peers.Identity{DeviceID: "client-1", DeviceName: "Client"},
			nil,
		)
		errCh <- err
	}()

	pending := <-pendingCh
	nowUnix.Add(61)
	if err := srv.Approve(pending.SessionID); err == nil {
		t.Fatal("expected Approve after TTL expiry to fail")
	}

	err := <-errCh
	if err == nil || !strings.Contains(err.Error(), "expired") {
		t.Fatalf("expected expired error, got %v", err)
	}
	if list, _ := serverStore.ListPeers(); len(list) != 0 {
		t.Fatalf("server persisted expired peer")
	}
	if list, _ := clientStore.ListPeers(); len(list) != 0 {
		t.Fatalf("client persisted expired peer")
	}
}

func TestTamperedConfirmationRejectedWithoutPersisting(t *testing.T) {
	dir := t.TempDir()
	serverStore := peers.NewStore(filepath.Join(dir, "server.json"))

	pendingCh := make(chan PendingSession, 1)
	srv := NewServer(ServerConfig{
		Identity:     peers.Identity{DeviceID: "server-1", DeviceName: "Server"},
		OnPeerPaired: serverStore.UpsertPeer,
		OnPendingSession: func(p PendingSession) {
			pendingCh <- p
		},
	})
	httpSrv := httptest.NewServer(srv.Handler())
	defer httpSrv.Close()

	key, _ := protocol.GeneratePairingKey()
	pub, _ := protocol.EncodePairingPublicKey(key.PublicKey())

	startBody, _ := json.Marshal(protocol.PairStartRequest{
		Version:       protocol.PairingVersion,
		RequesterID:   "client-1",
		RequesterName: "Client",
		ClientPub:     pub,
	})
	resp, err := http.Post(httpSrv.URL+"/pair/start", "application/json", bytes.NewReader(startBody))
	if err != nil {
		t.Fatalf("POST /pair/start: %v", err)
	}
	resp.Body.Close()

	pending := <-pendingCh
	if err := srv.Approve(pending.SessionID); err != nil {
		t.Fatalf("Approve: %v", err)
	}

	badConfirmBody, _ := json.Marshal(protocol.PairConfirmRequest{
		Version:       protocol.PairingVersion,
		SessionID:     pending.SessionID,
		ClientConfirm: strings.Repeat("0", 64),
	})
	confirmResp, err := http.Post(httpSrv.URL+"/pair/confirm", "application/json", bytes.NewReader(badConfirmBody))
	if err != nil {
		t.Fatalf("POST /pair/confirm: %v", err)
	}
	confirmResp.Body.Close()
	if confirmResp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("expected 401 on bad clientConfirm, got %d", confirmResp.StatusCode)
	}
	if list, _ := serverStore.ListPeers(); len(list) != 0 {
		t.Fatalf("server persisted peer after failed clientConfirm")
	}
}

func TestTamperedServerConfirmRejectedByClientWithoutPersisting(t *testing.T) {
	dir := t.TempDir()
	clientStore := peers.NewStore(filepath.Join(dir, "client.json"))

	fakeServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/pair/start":
			k, _ := protocol.GeneratePairingKey()
			sp, _ := protocol.EncodePairingPublicKey(k.PublicKey())
			writeJSON(w, http.StatusOK, protocol.PairStartResponse{
				Version:       protocol.PairingVersion,
				SessionID:     "sess-1",
				ResponderID:   "server-1",
				ResponderName: "Server",
				ServerPub:     sp,
			})
		case "/pair/status":
			writeJSON(w, http.StatusOK, protocol.PairStatusResponse{
				Version:   protocol.PairingVersion,
				SessionID: "sess-1",
				Status:    protocol.PairStatusApproved,
			})
		case "/pair/confirm":
			writeJSON(w, http.StatusOK, protocol.PairConfirmResponse{
				Version:       protocol.PairingVersion,
				SessionID:     "sess-1",
				ServerConfirm: strings.Repeat("f", 64),
			})
		}
	}))
	defer fakeServer.Close()

	client := NewClient(ClientConfig{
		PollInterval: 5 * time.Millisecond,
		OnPeerPaired: clientStore.UpsertPeer,
	})
	_, err := client.PairWithHost(
		context.Background(),
		fakeServer.URL,
		peers.Identity{DeviceID: "client-1", DeviceName: "Client"},
		nil,
	)
	if err == nil || !strings.Contains(err.Error(), "server key confirmation failed") {
		t.Fatalf("expected server confirmation error, got %v", err)
	}
	if list, _ := clientStore.ListPeers(); len(list) != 0 {
		t.Fatalf("client persisted peer after failed serverConfirm")
	}
}
