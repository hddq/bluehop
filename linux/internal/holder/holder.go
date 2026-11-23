package holder

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/hddq/bluehop/linux/internal/bluez"
	"github.com/hddq/bluehop/linux/internal/peers"
	"github.com/hddq/bluehop/linux/internal/protocol"
)

const (
	DefaultPort                 = 8765
	DefaultAddr                 = ":8765"
	DefaultDisconnectRetryDelay = 4 * time.Second
	DefaultDisconnectTimeout    = 10 * time.Second
	DefaultPollInterval         = 250 * time.Millisecond

	maxRequestBodyBytes  = 64 * 1024
	unknownDeviceAddress = "00:00:00:00:00:00"
)

type ManagedDevice struct {
	Address   string
	Name      string
	Connected bool
}

type BluetoothBackend interface {
	ListManagedDevices(ctx context.Context) ([]ManagedDevice, error)
	Disconnect(ctx context.Context, address string) error
}

type BluezBackend struct {
	ctrl bluez.Controller
}

func NewBluezBackend(ctrl bluez.Controller) *BluezBackend {
	return &BluezBackend{ctrl: ctrl}
}

func (b *BluezBackend) ListManagedDevices(ctx context.Context) ([]ManagedDevice, error) {
	devices, err := b.ctrl.ListDevices(ctx)
	if err != nil {
		return nil, err
	}
	out := make([]ManagedDevice, 0, len(devices))
	for _, d := range devices {
		out = append(out, ManagedDevice{
			Address:   d.Address,
			Name:      d.DisplayName(),
			Connected: d.Connected,
		})
	}
	return out, nil
}

func (b *BluezBackend) Disconnect(ctx context.Context, address string) error {
	_, err := b.ctrl.Disconnect(ctx, address)
	if errors.Is(err, bluez.ErrNotConnected) {
		return nil
	}
	return err
}

type DeviceStatus struct {
	DeviceAddress  string  `json:"deviceAddress"`
	DeviceName     *string `json:"deviceName"`
	Connected      bool    `json:"connected"`
	BredrConnected bool    `json:"bredrConnected"`
	LeConnected    bool    `json:"leConnected"`
}

type StatusResponse struct {
	Version        int            `json:"version"`
	DeviceAddress  *string        `json:"deviceAddress"`
	DeviceName     *string        `json:"deviceName"`
	Connected      bool           `json:"connected"`
	BredrConnected bool           `json:"bredrConnected"`
	LeConnected    bool           `json:"leConnected"`
	Port           int            `json:"port"`
	HolderID       *string        `json:"holderId"`
	HolderName     *string        `json:"holderName"`
	Devices        []DeviceStatus `json:"devices"`
}

type Config struct {
	Identity             peers.Identity
	Backend              BluetoothBackend
	PeerPSKLookup        func(requesterID string) ([]byte, error)
	Verifier             *protocol.ProtocolVerifier
	Now                  func() time.Time
	DisconnectRetryDelay time.Duration
	DisconnectTimeout    time.Duration
	PollInterval         time.Duration
	OnLog                func(string)
}

type Server struct {
	cfg      Config
	verifier *protocol.ProtocolVerifier

	mu       sync.Mutex
	httpSrv  *http.Server
	listener net.Listener
	port     int

	inFlightCount atomic.Int64
	inFlightWG    sync.WaitGroup
}

func NewServer(cfg Config) *Server {
	if cfg.Now == nil {
		cfg.Now = time.Now
	}
	if cfg.Verifier == nil {
		cfg.Verifier = protocol.NewProtocolVerifier(
			protocol.DefaultMaxSkewSeconds,
			nil,
			func() int64 { return cfg.Now().Unix() },
		)
	}
	if cfg.DisconnectTimeout <= 0 {
		cfg.DisconnectTimeout = DefaultDisconnectTimeout
	}
	if cfg.DisconnectRetryDelay <= 0 {
		cfg.DisconnectRetryDelay = DefaultDisconnectRetryDelay
	}
	if cfg.PollInterval <= 0 {
		cfg.PollInterval = DefaultPollInterval
	}
	if cfg.OnLog == nil {
		cfg.OnLog = func(string) {}
	}
	return &Server{
		cfg:      cfg,
		verifier: cfg.Verifier,
	}
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/status", s.handleStatus)
	mux.HandleFunc("/release", s.handleRelease)
	return mux
}

func (s *Server) Port() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.port
}

func (s *Server) Addr() net.Addr {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.listener == nil {
		return nil
	}
	return s.listener.Addr()
}

func (s *Server) Start(addr string) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.httpSrv != nil {
		return s.port, nil
	}

	ln, err := ListenWithFallback(addr, s.cfg.OnLog)
	if err != nil {
		return 0, err
	}

	tcpAddr, _ := ln.Addr().(*net.TCPAddr)
	port := 0
	if tcpAddr != nil {
		port = tcpAddr.Port
	}

	srv := &http.Server{
		Handler:           s.Handler(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      20 * time.Second,
		IdleTimeout:       30 * time.Second,
	}

	s.listener = ln
	s.httpSrv = srv
	s.port = port

	go func() {
		if err := srv.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) {
			s.cfg.OnLog(fmt.Sprintf("holder HTTP server error: %v", err))
		}
	}()

	s.cfg.OnLog(fmt.Sprintf("Holder HTTP server listening on %s", ln.Addr().String()))
	return port, nil
}

func (s *Server) Stop(ctx context.Context) error {
	s.mu.Lock()
	srv := s.httpSrv
	s.httpSrv = nil
	s.listener = nil
	s.port = 0
	s.mu.Unlock()

	if srv == nil {
		return nil
	}

	s.awaitInFlight(ctx)
	err := srv.Shutdown(ctx)
	s.cfg.OnLog("Holder HTTP server stopped")
	return err
}

func (s *Server) awaitInFlight(ctx context.Context) {
	if s.inFlightCount.Load() == 0 {
		return
	}
	done := make(chan struct{})
	go func() {
		s.inFlightWG.Wait()
		close(done)
	}()
	select {
	case <-done:
	case <-ctx.Done():
	}
}

func ListenWithFallback(addr string, onLog func(string)) (net.Listener, error) {
	if strings.TrimSpace(addr) == "" {
		addr = DefaultAddr
	}
	ln, err := listenWithIPv4Fallback(addr, onLog)
	if err == nil {
		return ln, nil
	}
	host, port, splitErr := net.SplitHostPort(addr)
	if splitErr != nil || port == "0" || !errors.Is(err, syscall.EADDRINUSE) {
		return nil, err
	}
	if onLog != nil {
		onLog(fmt.Sprintf("Port %s unavailable, falling back to ephemeral port", port))
	}
	return listenWithIPv4Fallback(net.JoinHostPort(host, "0"), onLog)
}

func listenWithIPv4Fallback(addr string, onLog func(string)) (net.Listener, error) {
	ln, err := net.Listen("tcp", addr)
	if err == nil {
		return ln, nil
	}
	host, port, splitErr := net.SplitHostPort(addr)
	if splitErr == nil && (host == "" || host == "::") && isAddressFamilyUnsupported(err) {
		if onLog != nil {
			onLog(fmt.Sprintf("Dual-stack IPv6 unavailable on %s, falling back to 0.0.0.0:%s", addr, port))
		}
		return net.Listen("tcp4", net.JoinHostPort("0.0.0.0", port))
	}
	return nil, err
}

func isAddressFamilyUnsupported(err error) bool {
	return errors.Is(err, syscall.EAFNOSUPPORT) ||
		errors.Is(err, syscall.EPROTONOSUPPORT) ||
		errors.Is(err, syscall.EADDRNOTAVAIL)
}

func (s *Server) handleStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var managed []ManagedDevice
	if s.cfg.Backend != nil {
		list, err := s.cfg.Backend.ListManagedDevices(r.Context())
		if err != nil {
			writeJSON(w, http.StatusBadGateway, map[string]string{
				"error": fmt.Sprintf("failed to query Bluetooth devices: %v", err),
			})
			return
		}
		managed = list
	}

	statuses := make([]DeviceStatus, 0, len(managed))
	for _, dev := range managed {
		namePtr := stringPtrOrNil(dev.Name)
		statuses = append(statuses, DeviceStatus{
			DeviceAddress:  dev.Address,
			DeviceName:     namePtr,
			Connected:      dev.Connected,
			BredrConnected: false,
			LeConnected:    false,
		})
	}

	var primary *DeviceStatus
	for i := range statuses {
		if statuses[i].Connected {
			primary = &statuses[i]
			break
		}
	}
	if primary == nil && len(statuses) > 0 {
		primary = &statuses[0]
	}

	resp := StatusResponse{
		Version:  protocol.ProtocolVersion,
		Port:     s.Port(),
		HolderID: stringPtrOrNil(s.cfg.Identity.DeviceID),
		HolderName: stringPtrOrNil(
			s.cfg.Identity.DeviceName,
		),
		Devices: statuses,
	}
	if primary != nil {
		addrCopy := primary.DeviceAddress
		resp.DeviceAddress = &addrCopy
		resp.DeviceName = primary.DeviceName
		resp.Connected = primary.Connected
		resp.BredrConnected = primary.BredrConnected
		resp.LeConnected = primary.LeConnected
	}

	writeJSON(w, http.StatusOK, resp)
}

func (s *Server) handleRelease(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	s.inFlightWG.Add(1)
	s.inFlightCount.Add(1)
	defer func() {
		s.inFlightCount.Add(-1)
		s.inFlightWG.Done()
	}()

	body, err := io.ReadAll(io.LimitReader(r.Body, maxRequestBodyBytes+1))
	if err != nil {
		s.respondUnsignedError(w, http.StatusBadRequest, protocol.StatusError, unknownDeviceAddress, s.fallbackNonce(), "Failed to read release request")
		return
	}
	if len(body) > maxRequestBodyBytes {
		s.respondUnsignedError(w, http.StatusBadRequest, protocol.StatusError, unknownDeviceAddress, s.fallbackNonce(), "Release request body too large")
		return
	}

	var req protocol.GrabRequest
	if err := json.Unmarshal(body, &req); err != nil {
		s.respondUnsignedError(w, http.StatusBadRequest, protocol.StatusError, unknownDeviceAddress, s.fallbackNonce(), "Malformed release request JSON")
		return
	}

	if s.cfg.PeerPSKLookup == nil {
		s.respondUnsignedError(w, http.StatusUnauthorized, protocol.StatusRejected, safeSingleLine(req.DeviceAddress, unknownDeviceAddress), safeSingleLine(req.Nonce, s.fallbackNonce()), "UNKNOWN_REQUESTER")
		return
	}

	psk, err := s.cfg.PeerPSKLookup(req.RequesterID)
	if err != nil || len(psk) == 0 {
		if err != nil && !errors.Is(err, os.ErrNotExist) {
			s.cfg.OnLog(fmt.Sprintf("Peer PSK lookup error for %q: %v", req.RequesterID, err))
		} else {
			s.cfg.OnLog(fmt.Sprintf("Rejected /release from unknown requester %q", req.RequesterID))
		}
		s.respondUnsignedError(w, http.StatusUnauthorized, protocol.StatusRejected, safeSingleLine(req.DeviceAddress, unknownDeviceAddress), safeSingleLine(req.Nonce, s.fallbackNonce()), "UNKNOWN_REQUESTER")
		return
	}

	if res := s.verifier.VerifyRequest(req, psk); !res.Valid {
		s.cfg.OnLog(fmt.Sprintf("Rejected /release from %s: %s", req.RequesterID, res.Reason))
		s.respondSigned(w, http.StatusUnauthorized, psk, protocol.StatusRejected, req.DeviceAddress, req.Nonce, string(res.Reason))
		return
	}

	if s.cfg.Backend == nil {
		s.respondSigned(w, http.StatusBadGateway, psk, protocol.StatusError, req.DeviceAddress, req.Nonce, "Bluetooth backend unavailable")
		return
	}

	managed, err := s.cfg.Backend.ListManagedDevices(r.Context())
	if err != nil {
		s.cfg.OnLog(fmt.Sprintf("Failed to list Bluetooth devices for /release: %v", err))
		s.respondSigned(w, http.StatusBadGateway, psk, protocol.StatusError, req.DeviceAddress, req.Nonce, err.Error())
		return
	}

	matched, found := findManagedDevice(managed, req.DeviceAddress)
	if !found {
		s.cfg.OnLog(fmt.Sprintf("Rejected /release for %s: not a paired/managed device", req.DeviceAddress))
		s.respondSigned(w, http.StatusConflict, psk, protocol.StatusRejected, req.DeviceAddress, req.Nonce, "Requested device is not enabled on this host")
		return
	}

	if !matched.Connected {
		s.cfg.OnLog(fmt.Sprintf("Rejected /release for %s: device is not connected", matched.Address))
		s.respondSigned(w, http.StatusConflict, psk, protocol.StatusRejected, req.DeviceAddress, req.Nonce, "Device is not connected")
		return
	}

	s.cfg.OnLog(fmt.Sprintf("Executing disconnect for %s requested by %s", matched.Address, req.RequesterID))
	if err := s.cfg.Backend.Disconnect(r.Context(), matched.Address); err != nil {
		s.cfg.OnLog(fmt.Sprintf("Disconnect failed for %s: %v", matched.Address, err))
		s.respondSigned(w, http.StatusBadGateway, psk, protocol.StatusError, req.DeviceAddress, req.Nonce, err.Error())
		return
	}

	confirmed, err := s.awaitDisconnectConfirmed(r.Context(), matched.Address)
	if err != nil {
		s.cfg.OnLog(fmt.Sprintf("Error confirming disconnect for %s: %v", matched.Address, err))
		s.respondSigned(w, http.StatusBadGateway, psk, protocol.StatusError, req.DeviceAddress, req.Nonce, err.Error())
		return
	}
	if !confirmed {
		s.cfg.OnLog(fmt.Sprintf("Timed out waiting for disconnect confirmation for %s", matched.Address))
		s.respondSigned(w, http.StatusGatewayTimeout, psk, protocol.StatusError, req.DeviceAddress, req.Nonce, "Timed out waiting for disconnect confirmation")
		return
	}

	s.cfg.OnLog(fmt.Sprintf("Confirmed disconnect for %s", matched.Address))
	s.respondSigned(w, http.StatusOK, psk, protocol.StatusOK, req.DeviceAddress, req.Nonce, "Disconnected")
}

func (s *Server) awaitDisconnectConfirmed(ctx context.Context, address string) (bool, error) {
	connected, err := s.isDeviceConnected(ctx, address)
	if err != nil {
		return false, err
	}
	if !connected {
		return true, nil
	}

	deadlineCtx, cancel := context.WithTimeout(ctx, s.cfg.DisconnectTimeout)
	defer cancel()

	ticker := time.NewTicker(s.cfg.PollInterval)
	defer ticker.Stop()

	var retryCh <-chan time.Time
	var retryTimer *time.Timer
	if s.cfg.DisconnectRetryDelay > 0 && s.cfg.DisconnectRetryDelay < s.cfg.DisconnectTimeout {
		retryTimer = time.NewTimer(s.cfg.DisconnectRetryDelay)
		defer retryTimer.Stop()
		retryCh = retryTimer.C
	}

	for {
		select {
		case <-deadlineCtx.Done():
			connected, err := s.isDeviceConnected(ctx, address)
			if err != nil {
				if ctx.Err() != nil {
					return false, ctx.Err()
				}
				return false, nil
			}
			return !connected, nil

		case <-retryCh:
			retryCh = nil
			connected, err := s.isDeviceConnected(deadlineCtx, address)
			if err == nil && !connected {
				return true, nil
			}
			s.cfg.OnLog(fmt.Sprintf("Retrying disconnect for %s", address))
			_ = s.cfg.Backend.Disconnect(deadlineCtx, address)

		case <-ticker.C:
			connected, err := s.isDeviceConnected(deadlineCtx, address)
			if err != nil {
				if deadlineCtx.Err() != nil {
					continue
				}
				return false, err
			}
			if !connected {
				return true, nil
			}
		}
	}
}

func (s *Server) isDeviceConnected(ctx context.Context, address string) (bool, error) {
	managed, err := s.cfg.Backend.ListManagedDevices(ctx)
	if err != nil {
		return false, err
	}
	dev, ok := findManagedDevice(managed, address)
	if !ok {
		return false, nil
	}
	return dev.Connected, nil
}

func findManagedDevice(devices []ManagedDevice, queryAddress string) (ManagedDevice, bool) {
	normalizedQuery, ok := bluez.NormalizeMAC(queryAddress)
	for _, dev := range devices {
		if ok && strings.EqualFold(dev.Address, normalizedQuery) {
			return dev, true
		}
		if strings.EqualFold(dev.Address, strings.TrimSpace(queryAddress)) {
			return dev, true
		}
	}
	return ManagedDevice{}, false
}

func (s *Server) respondSigned(
	w http.ResponseWriter,
	httpStatus int,
	psk []byte,
	status protocol.GrabResponseStatus,
	deviceAddress string,
	nonce string,
	message string,
) {
	safeAddr := safeSingleLine(deviceAddress, unknownDeviceAddress)
	safeNonce := safeSingleLine(nonce, s.fallbackNonce())
	resp, err := protocol.SignResponse(psk, protocol.GrabResponse{
		Version:           protocol.ProtocolVersion,
		Status:            status,
		DeviceAddress:     safeAddr,
		TimestampEpochSec: s.cfg.Now().Unix(),
		Nonce:             safeNonce,
		Message:           message,
	})
	if err != nil {
		s.respondUnsignedError(w, httpStatus, status, safeAddr, safeNonce, message)
		return
	}
	writeJSON(w, httpStatus, resp)
}

func (s *Server) respondUnsignedError(
	w http.ResponseWriter,
	httpStatus int,
	status protocol.GrabResponseStatus,
	deviceAddress string,
	nonce string,
	message string,
) {
	writeJSON(w, httpStatus, protocol.GrabResponse{
		Version:           protocol.ProtocolVersion,
		Status:            status,
		DeviceAddress:     deviceAddress,
		TimestampEpochSec: s.cfg.Now().Unix(),
		Nonce:             nonce,
		Message:           message,
	})
}

func (s *Server) fallbackNonce() string {
	n, err := protocol.GenerateNonce()
	if err != nil {
		return "00000000000000000000000000000000"
	}
	return n
}

func safeSingleLine(s, fallback string) string {
	trimmed := strings.TrimSpace(s)
	if trimmed == "" || strings.ContainsAny(trimmed, "\r\n") {
		return fallback
	}
	return s
}

func stringPtrOrNil(s string) *string {
	trimmed := strings.TrimSpace(s)
	if trimmed == "" {
		return nil
	}
	return &trimmed
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
