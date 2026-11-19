package peers

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
)

const PSKHexLen = 64

type Identity struct {
	DeviceID   string `json:"deviceId"`
	DeviceName string `json:"deviceName"`
}

type Peer struct {
	PeerID           string `json:"peerId"`
	PeerName         string `json:"peerName"`
	PSKHex           string `json:"pskHex"`
	PairedAtEpochSec int64  `json:"pairedAtEpochSec"`
}

func (p Peer) PSKBytes() ([]byte, error) {
	if len(p.PSKHex) != PSKHexLen {
		return nil, fmt.Errorf("invalid pskHex length: got %d, want %d", len(p.PSKHex), PSKHexLen)
	}
	b, err := hex.DecodeString(p.PSKHex)
	if err != nil {
		return nil, fmt.Errorf("decode pskHex: %w", err)
	}
	return b, nil
}

type storeFile struct {
	DeviceID   string `json:"deviceId"`
	DeviceName string `json:"deviceName"`
	Peers      []Peer `json:"peers"`
}

type Store struct {
	mu   sync.Mutex
	path string
}

func DefaultPath() (string, error) {
	cfgDir, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(cfgDir, "bluehop", "peers.json"), nil
}

func NewStore(path string) *Store {
	return &Store{path: path}
}

func (s *Store) Path() string {
	return s.path
}

func (s *Store) GetOrCreateIdentity(overrideName string) (Identity, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	data, err := s.readLocked()
	if err != nil {
		return Identity{}, err
	}
	changed := false
	if strings.TrimSpace(data.DeviceID) == "" {
		id, err := newUUIDv4()
		if err != nil {
			return Identity{}, err
		}
		data.DeviceID = id
		changed = true
	}
	if name := strings.TrimSpace(overrideName); name != "" && name != data.DeviceName {
		data.DeviceName = name
		changed = true
	} else if strings.TrimSpace(data.DeviceName) == "" {
		host, err := os.Hostname()
		if err != nil || strings.TrimSpace(host) == "" {
			host = "Linux"
		}
		data.DeviceName = strings.TrimSpace(host)
		changed = true
	}
	if changed {
		if err := s.writeLocked(data); err != nil {
			return Identity{}, err
		}
	}
	return Identity{DeviceID: data.DeviceID, DeviceName: data.DeviceName}, nil
}

func (s *Store) ListPeers() ([]Peer, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	data, err := s.readLocked()
	if err != nil {
		return nil, err
	}
	out := make([]Peer, len(data.Peers))
	copy(out, data.Peers)
	return out, nil
}

func (s *Store) GetPeerPSK(peerID string) ([]byte, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	data, err := s.readLocked()
	if err != nil {
		return nil, err
	}
	for _, p := range data.Peers {
		if p.PeerID == peerID {
			return p.PSKBytes()
		}
	}
	return nil, os.ErrNotExist
}

func (s *Store) UpsertPeer(peer Peer) error {
	peer.PeerID = strings.TrimSpace(peer.PeerID)
	peer.PeerName = strings.TrimSpace(peer.PeerName)
	peer.PSKHex = strings.ToLower(strings.TrimSpace(peer.PSKHex))
	if peer.PeerID == "" || peer.PeerName == "" {
		return errors.New("peerId and peerName must not be empty")
	}
	if _, err := peer.PSKBytes(); err != nil {
		return err
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	data, err := s.readLocked()
	if err != nil {
		return err
	}
	kept := make([]Peer, 0, len(data.Peers)+1)
	for _, existing := range data.Peers {
		if existing.PeerID != peer.PeerID {
			kept = append(kept, existing)
		}
	}
	kept = append(kept, peer)
	sort.Slice(kept, func(i, j int) bool {
		return strings.ToLower(kept[i].PeerName) < strings.ToLower(kept[j].PeerName)
	})
	data.Peers = kept
	return s.writeLocked(data)
}

func (s *Store) RemovePeer(peerID string) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	data, err := s.readLocked()
	if err != nil {
		return false, err
	}
	kept := make([]Peer, 0, len(data.Peers))
	removed := false
	for _, existing := range data.Peers {
		if existing.PeerID == peerID {
			removed = true
			continue
		}
		kept = append(kept, existing)
	}
	if !removed {
		return false, nil
	}
	data.Peers = kept
	return true, s.writeLocked(data)
}

func (s *Store) readLocked() (storeFile, error) {
	raw, err := os.ReadFile(s.path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return storeFile{}, nil
		}
		return storeFile{}, err
	}
	if len(strings.TrimSpace(string(raw))) == 0 {
		return storeFile{}, nil
	}
	var sf storeFile
	if err := json.Unmarshal(raw, &sf); err != nil {
		return storeFile{}, fmt.Errorf("parse %s: %w", s.path, err)
	}
	return sf, nil
}

func (s *Store) writeLocked(sf storeFile) error {
	dir := filepath.Dir(s.path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	raw, err := json.MarshalIndent(sf, "", "  ")
	if err != nil {
		return err
	}
	raw = append(raw, '\n')
	tmp, err := os.CreateTemp(dir, ".peers-*.tmp")
	if err != nil {
		return err
	}
	tmpPath := tmp.Name()
	cleanup := true
	defer func() {
		if cleanup {
			_ = os.Remove(tmpPath)
		}
	}()
	if err := tmp.Chmod(0o600); err != nil {
		_ = tmp.Close()
		return err
	}
	if _, err := tmp.Write(raw); err != nil {
		_ = tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	if err := os.Rename(tmpPath, s.path); err != nil {
		return err
	}
	cleanup = false
	return nil
}

func newUUIDv4() (string, error) {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%08x-%04x-%04x-%04x-%012x",
		b[0:4], b[4:6], b[6:8], b[8:10], b[10:16]), nil
}
