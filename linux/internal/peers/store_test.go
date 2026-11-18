package peers

import (
	"bytes"
	"encoding/hex"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestStorePreservesExistingPeersAndPersistsPSKHex(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "peers.json")
	store := NewStore(path)

	id1, err := store.GetOrCreateIdentity("workstation")
	if err != nil {
		t.Fatalf("GetOrCreateIdentity: %v", err)
	}
	if id1.DeviceID == "" || id1.DeviceName != "workstation" {
		t.Fatalf("unexpected identity: %+v", id1)
	}
	id2, err := store.GetOrCreateIdentity("")
	if err != nil {
		t.Fatalf("GetOrCreateIdentity reload: %v", err)
	}
	if id2 != id1 {
		t.Fatalf("identity changed across reload: got %+v, want %+v", id2, id1)
	}

	existingPSK := bytes.Repeat([]byte{0x11}, 32)
	existingHex := hex.EncodeToString(existingPSK)
	if err := store.UpsertPeer(Peer{
		PeerID:           "existing-peer",
		PeerName:         "Existing Phone",
		PSKHex:           existingHex,
		PairedAtEpochSec: 1700000000,
	}); err != nil {
		t.Fatalf("UpsertPeer existing: %v", err)
	}

	newPSK := bytes.Repeat([]byte{0x22}, 32)
	newHex := hex.EncodeToString(newPSK)
	if err := store.UpsertPeer(Peer{
		PeerID:           "new-peer",
		PeerName:         "Alpha Tablet",
		PSKHex:           strings.ToUpper(newHex),
		PairedAtEpochSec: 1700000100,
	}); err != nil {
		t.Fatalf("UpsertPeer new: %v", err)
	}

	list, err := store.ListPeers()
	if err != nil {
		t.Fatalf("ListPeers: %v", err)
	}
	if len(list) != 2 {
		t.Fatalf("expected 2 peers, got %d", len(list))
	}

	gotExisting, err := store.GetPeerPSK("existing-peer")
	if err != nil || !bytes.Equal(gotExisting, existingPSK) {
		t.Fatalf("existing-peer PSK mismatch: %x, err=%v", gotExisting, err)
	}
	gotNew, err := store.GetPeerPSK("new-peer")
	if err != nil || !bytes.Equal(gotNew, newPSK) {
		t.Fatalf("new-peer PSK mismatch: %x, err=%v", gotNew, err)
	}

	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read raw file: %v", err)
	}
	if !strings.Contains(string(raw), `"pskHex": "`+newHex+`"`) {
		t.Fatalf("expected lowercase pskHex in JSON, got:\n%s", string(raw))
	}

	info, err := os.Stat(path)
	if err != nil {
		t.Fatalf("stat %s: %v", path, err)
	}
	if perm := info.Mode().Perm(); perm != 0o600 {
		t.Fatalf("expected 0600 permissions on %s, got %o", path, perm)
	}

	removed, err := store.RemovePeer("existing-peer")
	if err != nil || !removed {
		t.Fatalf("RemovePeer: removed=%v err=%v", removed, err)
	}
	list, err = store.ListPeers()
	if err != nil {
		t.Fatalf("ListPeers after remove: %v", err)
	}
	if len(list) != 1 || list[0].PeerID != "new-peer" {
		t.Fatalf("unexpected peers after remove: %+v", list)
	}
}
