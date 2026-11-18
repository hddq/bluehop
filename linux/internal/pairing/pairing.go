package pairing

import (
	"bytes"
	"context"
	"crypto/ecdh"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/hddq/bluehop/linux/internal/peers"
	"github.com/hddq/bluehop/linux/internal/protocol"
)

const (
	defaultMaxBodyBytes   = 64 * 1024
	defaultPollInterval   = 500 * time.Millisecond
	defaultRequestTimeout = 5 * time.Second
)

type PendingSession struct {
	SessionID     string
	RequesterID   string
	RequesterName string
	SAS           string
	ExpiresAt     time.Time
}

type SASChallenge struct {
	SessionID     string
	ResponderID   string
	ResponderName string
	SAS           string
}

type ServerConfig struct {
	Identity         peers.Identity
	OnPeerPaired     func(peers.Peer) error
	OnPendingSession func(PendingSession)
	SessionTTL       time.Duration
	Now              func() time.Time
}

type activeSession struct {
	id            string
	requesterID   string
	requesterName string
	clientPub     string
	serverPub     string
	serverKey     *ecdh.PrivateKey
	keys          protocol.PairingKeys
	status        protocol.PairSessionStatus
	expiresAt     time.Time
	completed     bool
}

type Server struct {
	cfg     ServerConfig
	mu      sync.Mutex
	session *activeSession
}

func NewServer(cfg ServerConfig) *Server {
	if cfg.SessionTTL <= 0 {
		cfg.SessionTTL = protocol.PairingSessionTTLSec * time.Second
	}
	if cfg.Now == nil {
		cfg.Now = time.Now
	}
	return &Server{cfg: cfg}
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/pair/start", s.handleStart)
	mux.HandleFunc("/pair/status", s.handleStatus)
	mux.HandleFunc("/pair/confirm", s.handleConfirm)
	return mux
}

func (s *Server) Approve(sessionID string) error {
	return s.decide(sessionID, protocol.PairStatusApproved)
}

func (s *Server) Reject(sessionID string) error {
	return s.decide(sessionID, protocol.PairStatusRejected)
}

func (s *Server) Reset() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.session = nil
}

func (s *Server) decide(sessionID string, target protocol.PairSessionStatus) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.session == nil || s.session.id != sessionID {
		return errors.New("pairing session not found")
	}
	if s.session.completed {
		return errors.New("pairing session already completed")
	}
	now := s.cfg.Now()
	if now.After(s.session.expiresAt) {
		s.session.status = protocol.PairStatusExpired
		return errors.New("pairing session expired")
	}
	if s.session.status != protocol.PairStatusPending {
		return fmt.Errorf("pairing session is %s", s.session.status)
	}
	s.session.status = target
	return nil
}

func (s *Server) handleStart(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writePairError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	var req protocol.PairStartRequest
	if err := decodeJSONBody(r.Body, &req); err != nil {
		writePairError(w, http.StatusBadRequest, "invalid pairing JSON")
		return
	}
	if req.Version != protocol.PairingVersion {
		writePairError(w, http.StatusBadRequest, fmt.Sprintf("unsupported pairing version %d", req.Version))
		return
	}
	if !protocol.ValidPairingField(req.RequesterID) ||
		!protocol.ValidPairingField(req.RequesterName) ||
		!protocol.ValidPairingField(req.ClientPub) {
		writePairError(w, http.StatusBadRequest, "invalid pairing parameters")
		return
	}
	if req.RequesterID == s.cfg.Identity.DeviceID {
		writePairError(w, http.StatusBadRequest, "cannot pair device with itself")
		return
	}

	clientPubKey, err := protocol.DecodePairingPublicKey(req.ClientPub)
	if err != nil {
		writePairError(w, http.StatusBadRequest, "invalid client public key")
		return
	}

	serverKey, err := protocol.GeneratePairingKey()
	if err != nil {
		writePairError(w, http.StatusInternalServerError, "failed to generate ephemeral key")
		return
	}
	serverPub, err := protocol.EncodePairingPublicKey(serverKey.PublicKey())
	if err != nil {
		writePairError(w, http.StatusInternalServerError, "failed to encode server public key")
		return
	}
	sharedSecret, err := serverKey.ECDH(clientPubKey)
	if err != nil {
		writePairError(w, http.StatusBadRequest, "ECDH key agreement failed")
		return
	}
	sessionID, err := protocol.GenerateNonce()
	if err != nil {
		writePairError(w, http.StatusInternalServerError, "failed to allocate session id")
		return
	}
	keys, err := protocol.DerivePairingKeys(sharedSecret, sessionID, req.ClientPub, serverPub)
	if err != nil {
		writePairError(w, http.StatusInternalServerError, "key derivation failed")
		return
	}

	now := s.cfg.Now()
	expiresAt := now.Add(s.cfg.SessionTTL)
	requesterName := strings.TrimSpace(req.RequesterName)

	s.mu.Lock()
	if s.session != nil {
		if !s.session.completed && now.After(s.session.expiresAt) {
			s.session.status = protocol.PairStatusExpired
		}
		if s.session.completed {
			s.mu.Unlock()
			writePairError(w, http.StatusConflict, "pairing session already completed")
			return
		}
		if s.session.status == protocol.PairStatusPending || s.session.status == protocol.PairStatusApproved {
			s.mu.Unlock()
			writePairError(w, http.StatusConflict, "pairing session already in progress")
			return
		}
	}
	s.session = &activeSession{
		id:            sessionID,
		requesterID:   req.RequesterID,
		requesterName: requesterName,
		clientPub:     req.ClientPub,
		serverPub:     serverPub,
		serverKey:     serverKey,
		keys:          keys,
		status:        protocol.PairStatusPending,
		expiresAt:     expiresAt,
	}
	s.mu.Unlock()

	if s.cfg.OnPendingSession != nil {
		s.cfg.OnPendingSession(PendingSession{
			SessionID:     sessionID,
			RequesterID:   req.RequesterID,
			RequesterName: requesterName,
			SAS:           keys.SAS,
			ExpiresAt:     expiresAt,
		})
	}

	writeJSON(w, http.StatusOK, protocol.PairStartResponse{
		Version:       protocol.PairingVersion,
		SessionID:     sessionID,
		ResponderID:   s.cfg.Identity.DeviceID,
		ResponderName: s.cfg.Identity.DeviceName,
		ServerPub:     serverPub,
	})
}

func (s *Server) handleStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writePairError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	sessionID := strings.TrimSpace(r.URL.Query().Get("sessionId"))
	if sessionID == "" {
		writePairError(w, http.StatusBadRequest, "missing sessionId")
		return
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	if s.session == nil || s.session.id != sessionID {
		writePairError(w, http.StatusNotFound, "unknown pairing session")
		return
	}
	if !s.session.completed && s.cfg.Now().After(s.session.expiresAt) &&
		(s.session.status == protocol.PairStatusPending || s.session.status == protocol.PairStatusApproved) {
		s.session.status = protocol.PairStatusExpired
	}
	writeJSON(w, http.StatusOK, protocol.PairStatusResponse{
		Version:   protocol.PairingVersion,
		SessionID: s.session.id,
		Status:    s.session.status,
	})
}

func (s *Server) handleConfirm(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writePairError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	var req protocol.PairConfirmRequest
	if err := decodeJSONBody(r.Body, &req); err != nil {
		writePairError(w, http.StatusBadRequest, "invalid confirmation JSON")
		return
	}
	if req.Version != protocol.PairingVersion ||
		!protocol.ValidPairingField(req.SessionID) ||
		!protocol.ValidPairingField(req.ClientConfirm) {
		writePairError(w, http.StatusBadRequest, "invalid confirmation parameters")
		return
	}

	s.mu.Lock()
	sess := s.session
	if sess == nil || sess.id != req.SessionID {
		s.mu.Unlock()
		writePairError(w, http.StatusNotFound, "unknown pairing session")
		return
	}
	if sess.completed {
		s.mu.Unlock()
		writePairError(w, http.StatusConflict, "pairing session already completed")
		return
	}
	now := s.cfg.Now()
	if now.After(sess.expiresAt) {
		sess.status = protocol.PairStatusExpired
		s.mu.Unlock()
		writePairError(w, http.StatusGone, "pairing session expired")
		return
	}
	if sess.status == protocol.PairStatusRejected {
		s.mu.Unlock()
		writePairError(w, http.StatusForbidden, "pairing request rejected")
		return
	}
	if sess.status != protocol.PairStatusApproved {
		s.mu.Unlock()
		writePairError(w, http.StatusConflict, "pairing session is not approved yet")
		return
	}

	if !protocol.VerifyConfirm(
		sess.keys.ConfirmKey,
		protocol.ConfirmRoleClient,
		sess.id,
		sess.requesterID,
		s.cfg.Identity.DeviceID,
		req.ClientConfirm,
	) {
		sess.status = protocol.PairStatusRejected
		s.mu.Unlock()
		writePairError(w, http.StatusUnauthorized, "client key confirmation failed")
		return
	}

	serverConfirm, err := protocol.ComputeConfirm(
		sess.keys.ConfirmKey,
		protocol.ConfirmRoleServer,
		sess.id,
		sess.requesterID,
		s.cfg.Identity.DeviceID,
	)
	if err != nil {
		s.mu.Unlock()
		writePairError(w, http.StatusInternalServerError, "failed to compute confirmation MAC")
		return
	}

	peer := peers.Peer{
		PeerID:           sess.requesterID,
		PeerName:         sess.requesterName,
		PSKHex:           sess.keys.PSKHex(),
		PairedAtEpochSec: now.Unix(),
	}
	sess.completed = true
	s.mu.Unlock()

	if s.cfg.OnPeerPaired != nil {
		if err := s.cfg.OnPeerPaired(peer); err != nil {
			s.mu.Lock()
			if s.session == sess {
				sess.completed = false
			}
			s.mu.Unlock()
			writePairError(w, http.StatusInternalServerError, "failed to persist paired peer")
			return
		}
	}

	writeJSON(w, http.StatusOK, protocol.PairConfirmResponse{
		Version:       protocol.PairingVersion,
		SessionID:     sess.id,
		ServerConfirm: serverConfirm,
	})
}

type ClientConfig struct {
	HTTPClient   *http.Client
	PollInterval time.Duration
	Now          func() time.Time
	OnPeerPaired func(peers.Peer) error
}

type Client struct {
	httpClient   *http.Client
	pollInterval time.Duration
	now          func() time.Time
	onPeerPaired func(peers.Peer) error
}

func NewClient(cfg ClientConfig) *Client {
	hc := cfg.HTTPClient
	if hc == nil {
		hc = &http.Client{Timeout: defaultRequestTimeout}
	}
	poll := cfg.PollInterval
	if poll <= 0 {
		poll = defaultPollInterval
	}
	nowFn := cfg.Now
	if nowFn == nil {
		nowFn = time.Now
	}
	return &Client{
		httpClient:   hc,
		pollInterval: poll,
		now:          nowFn,
		onPeerPaired: cfg.OnPeerPaired,
	}
}

func (c *Client) PairWithHost(
	ctx context.Context,
	targetAddr string,
	identity peers.Identity,
	onSAS func(SASChallenge),
) (peers.Peer, error) {
	baseURL, err := NormalizeBaseURL(targetAddr)
	if err != nil {
		return peers.Peer{}, err
	}

	clientKey, err := protocol.GeneratePairingKey()
	if err != nil {
		return peers.Peer{}, err
	}
	clientPub, err := protocol.EncodePairingPublicKey(clientKey.PublicKey())
	if err != nil {
		return peers.Peer{}, err
	}

	var startResp protocol.PairStartResponse
	if err := c.postJSON(ctx, baseURL+"/pair/start", protocol.PairStartRequest{
		Version:       protocol.PairingVersion,
		RequesterID:   identity.DeviceID,
		RequesterName: identity.DeviceName,
		ClientPub:     clientPub,
	}, &startResp); err != nil {
		return peers.Peer{}, err
	}

	if startResp.Version != protocol.PairingVersion {
		return peers.Peer{}, fmt.Errorf("unsupported pairing version %d", startResp.Version)
	}
	if !protocol.ValidPairingField(startResp.SessionID) ||
		!protocol.ValidPairingField(startResp.ResponderID) ||
		!protocol.ValidPairingField(startResp.ResponderName) ||
		!protocol.ValidPairingField(startResp.ServerPub) {
		return peers.Peer{}, errors.New("incomplete pairing start response")
	}
	if startResp.ResponderID == identity.DeviceID {
		return peers.Peer{}, errors.New("cannot pair device with itself")
	}

	serverPubKey, err := protocol.DecodePairingPublicKey(startResp.ServerPub)
	if err != nil {
		return peers.Peer{}, fmt.Errorf("invalid server public key: %w", err)
	}
	sharedSecret, err := clientKey.ECDH(serverPubKey)
	if err != nil {
		return peers.Peer{}, fmt.Errorf("ECDH key agreement failed: %w", err)
	}
	keys, err := protocol.DerivePairingKeys(sharedSecret, startResp.SessionID, clientPub, startResp.ServerPub)
	if err != nil {
		return peers.Peer{}, err
	}

	if onSAS != nil {
		onSAS(SASChallenge{
			SessionID:     startResp.SessionID,
			ResponderID:   startResp.ResponderID,
			ResponderName: startResp.ResponderName,
			SAS:           keys.SAS,
		})
	}

	statusURL := baseURL + "/pair/status?sessionId=" + url.QueryEscape(startResp.SessionID)
	for {
		var statusResp protocol.PairStatusResponse
		if err := c.getJSON(ctx, statusURL, &statusResp); err != nil {
			return peers.Peer{}, err
		}
		if statusResp.Version != protocol.PairingVersion || statusResp.SessionID != startResp.SessionID {
			return peers.Peer{}, errors.New("mismatched pairing status response")
		}
		switch statusResp.Status {
		case protocol.PairStatusApproved:
			goto confirmed
		case protocol.PairStatusRejected:
			return peers.Peer{}, errors.New("pairing rejected by host")
		case protocol.PairStatusExpired:
			return peers.Peer{}, errors.New("pairing session expired")
		case protocol.PairStatusPending:
			timer := time.NewTimer(c.pollInterval)
			select {
			case <-ctx.Done():
				timer.Stop()
				return peers.Peer{}, ctx.Err()
			case <-timer.C:
			}
		default:
			return peers.Peer{}, fmt.Errorf("unexpected pairing status %q", statusResp.Status)
		}
	}

confirmed:
	clientConfirm, err := protocol.ComputeConfirm(
		keys.ConfirmKey,
		protocol.ConfirmRoleClient,
		startResp.SessionID,
		identity.DeviceID,
		startResp.ResponderID,
	)
	if err != nil {
		return peers.Peer{}, err
	}

	var confirmResp protocol.PairConfirmResponse
	if err := c.postJSON(ctx, baseURL+"/pair/confirm", protocol.PairConfirmRequest{
		Version:       protocol.PairingVersion,
		SessionID:     startResp.SessionID,
		ClientConfirm: clientConfirm,
	}, &confirmResp); err != nil {
		return peers.Peer{}, err
	}

	if confirmResp.Version != protocol.PairingVersion || confirmResp.SessionID != startResp.SessionID {
		return peers.Peer{}, errors.New("mismatched pairing confirmation response")
	}
	if !protocol.VerifyConfirm(
		keys.ConfirmKey,
		protocol.ConfirmRoleServer,
		startResp.SessionID,
		identity.DeviceID,
		startResp.ResponderID,
		confirmResp.ServerConfirm,
	) {
		return peers.Peer{}, errors.New("server key confirmation failed")
	}

	peer := peers.Peer{
		PeerID:           startResp.ResponderID,
		PeerName:         strings.TrimSpace(startResp.ResponderName),
		PSKHex:           keys.PSKHex(),
		PairedAtEpochSec: c.now().Unix(),
	}
	if c.onPeerPaired != nil {
		if err := c.onPeerPaired(peer); err != nil {
			return peers.Peer{}, err
		}
	}
	return peer, nil
}

func (c *Client) getJSON(ctx context.Context, rawURL string, out any) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return err
	}
	req.Header.Set("Accept", "application/json")
	return c.doJSON(req, out)
}

func (c *Client) postJSON(ctx context.Context, rawURL string, body any, out any) error {
	payload, err := json.Marshal(body)
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, rawURL, bytes.NewReader(payload))
	if err != nil {
		return err
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("Content-Type", "application/json; charset=utf-8")
	return c.doJSON(req, out)
}

func (c *Client) doJSON(req *http.Request, out any) error {
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	raw, err := io.ReadAll(io.LimitReader(resp.Body, defaultMaxBodyBytes+1))
	if err != nil {
		return err
	}
	if len(raw) > defaultMaxBodyBytes {
		return fmt.Errorf("pairing response exceeded %d bytes", defaultMaxBodyBytes)
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		var errResp protocol.PairErrorResponse
		if json.Unmarshal(raw, &errResp) == nil && strings.TrimSpace(errResp.Message) != "" {
			return fmt.Errorf("%s (HTTP %d)", errResp.Message, resp.StatusCode)
		}
		return fmt.Errorf("pairing request failed (HTTP %d)", resp.StatusCode)
	}
	if err := json.Unmarshal(raw, out); err != nil {
		return fmt.Errorf("decode pairing response: %w", err)
	}
	return nil
}

// NormalizeBaseURL turns "host:port", "[ipv6]:port", or "http://host:port/" into "http://host:port".
func NormalizeBaseURL(input string) (string, error) {
	trimmed := strings.TrimSpace(input)
	if trimmed == "" {
		return "", errors.New("host address is required (e.g. 192.168.1.10:43215)")
	}
	if !strings.Contains(trimmed, "://") {
		trimmed = "http://" + trimmed
	}
	u, err := url.Parse(trimmed)
	if err != nil {
		return "", fmt.Errorf("invalid host address: %w", err)
	}
	if u.Scheme != "http" {
		return "", errors.New("only http:// or host:port addresses are supported")
	}
	if u.Host == "" || u.Port() == "" || u.Hostname() == "" {
		return "", errors.New("address must include host and port")
	}
	if (u.Path != "" && u.Path != "/") || u.RawQuery != "" || u.Fragment != "" || u.User != nil {
		return "", errors.New("enter only host:port")
	}
	return "http://" + u.Host, nil
}

func decodeJSONBody(body io.Reader, dst any) error {
	raw, err := io.ReadAll(io.LimitReader(body, defaultMaxBodyBytes+1))
	if err != nil {
		return err
	}
	if len(raw) > defaultMaxBodyBytes {
		return errors.New("request body too large")
	}
	return json.Unmarshal(raw, dst)
}

func writePairError(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, protocol.PairErrorResponse{
		Version: protocol.PairingVersion,
		Message: msg,
	})
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
