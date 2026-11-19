package grab

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/hddq/bluehop/linux/internal/bluez"
	"github.com/hddq/bluehop/linux/internal/holder"
	"github.com/hddq/bluehop/linux/internal/mdns"
	"github.com/hddq/bluehop/linux/internal/peers"
	"github.com/hddq/bluehop/linux/internal/protocol"
)

const (
	DefaultDiscoveryTimeout = 3 * time.Second
	DefaultStatusTimeout    = 3 * time.Second
	DefaultReleaseTimeout   = 15 * time.Second

	maxResponseBodyBytes = 256 * 1024
)

type Stage string

const (
	StageSearching  Stage = "searching"
	StageReleasing  Stage = "releasing"
	StageConnecting Stage = "connecting"
)

type Progress struct {
	Stage      Stage
	Device     bluez.Device
	HolderName string
}

type Result struct {
	Device           bluez.Device
	AlreadyConnected bool
	ReleasedFrom     string
}

type ReleaseStatus string

const (
	ReleaseOK                  ReleaseStatus = "released"
	ReleaseAlreadyDisconnected ReleaseStatus = "already_disconnected"
	ReleaseRejected            ReleaseStatus = "rejected"
	ReleaseFailed              ReleaseStatus = "failed"
)

type ReleaseOutcome struct {
	Status  ReleaseStatus
	Message string
}

type HolderClient interface {
	FetchStatus(ctx context.Context, host string, port int) (holder.StatusResponse, error)
	RequestRelease(ctx context.Context, host string, port int, deviceAddress, requesterID string, psk []byte) ReleaseOutcome
}

type HTTPClient struct {
	httpClient     *http.Client
	verifier       *protocol.ProtocolVerifier
	now            func() time.Time
	statusTimeout  time.Duration
	releaseTimeout time.Duration
}

type HTTPClientConfig struct {
	HTTPClient     *http.Client
	Verifier       *protocol.ProtocolVerifier
	Now            func() time.Time
	StatusTimeout  time.Duration
	ReleaseTimeout time.Duration
}

func NewHTTPClient(cfg HTTPClientConfig) *HTTPClient {
	hc := cfg.HTTPClient
	if hc == nil {
		hc = &http.Client{}
	}
	nowFn := cfg.Now
	if nowFn == nil {
		nowFn = time.Now
	}
	verifier := cfg.Verifier
	if verifier == nil {
		verifier = protocol.NewProtocolVerifier(
			protocol.DefaultMaxSkewSeconds,
			nil,
			func() int64 { return nowFn().Unix() },
		)
	}
	statusTimeout := cfg.StatusTimeout
	if statusTimeout <= 0 {
		statusTimeout = DefaultStatusTimeout
	}
	releaseTimeout := cfg.ReleaseTimeout
	if releaseTimeout <= 0 {
		releaseTimeout = DefaultReleaseTimeout
	}
	return &HTTPClient{
		httpClient:     hc,
		verifier:       verifier,
		now:            nowFn,
		statusTimeout:  statusTimeout,
		releaseTimeout: releaseTimeout,
	}
}

func (c *HTTPClient) FetchStatus(ctx context.Context, host string, port int) (holder.StatusResponse, error) {
	reqCtx, cancel := context.WithTimeout(ctx, c.statusTimeout)
	defer cancel()

	req, err := http.NewRequestWithContext(reqCtx, http.MethodGet, formatBaseURL(host, port)+"/status", nil)
	if err != nil {
		return holder.StatusResponse{}, err
	}
	req.Header.Set("Accept", "application/json")

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return holder.StatusResponse{}, err
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(io.LimitReader(resp.Body, maxResponseBodyBytes+1))
	if err != nil {
		return holder.StatusResponse{}, err
	}
	if len(body) > maxResponseBodyBytes {
		return holder.StatusResponse{}, errors.New("status response body too large")
	}
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return holder.StatusResponse{}, fmt.Errorf("status check failed (%d)", resp.StatusCode)
	}

	var status holder.StatusResponse
	if err := json.Unmarshal(body, &status); err != nil {
		return holder.StatusResponse{}, fmt.Errorf("decode status response: %w", err)
	}
	return status, nil
}

func (c *HTTPClient) RequestRelease(
	ctx context.Context,
	host string,
	port int,
	deviceAddress string,
	requesterID string,
	psk []byte,
) ReleaseOutcome {
	nonce, err := protocol.GenerateNonce()
	if err != nil {
		return ReleaseOutcome{Status: ReleaseFailed, Message: fmt.Sprintf("generate nonce: %v", err)}
	}
	signedReq, err := protocol.SignRequest(psk, protocol.GrabRequest{
		Version:           protocol.ProtocolVersion,
		DeviceAddress:     deviceAddress,
		RequesterID:       requesterID,
		TimestampEpochSec: c.now().Unix(),
		Nonce:             nonce,
	})
	if err != nil {
		return ReleaseOutcome{Status: ReleaseFailed, Message: fmt.Sprintf("sign release request: %v", err)}
	}
	payload, err := json.Marshal(signedReq)
	if err != nil {
		return ReleaseOutcome{Status: ReleaseFailed, Message: fmt.Sprintf("encode release request: %v", err)}
	}

	reqCtx, cancel := context.WithTimeout(ctx, c.releaseTimeout)
	defer cancel()

	req, err := http.NewRequestWithContext(
		reqCtx,
		http.MethodPost,
		formatBaseURL(host, port)+"/release",
		bytes.NewReader(payload),
	)
	if err != nil {
		return ReleaseOutcome{Status: ReleaseFailed, Message: err.Error()}
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("Content-Type", "application/json; charset=utf-8")

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return ReleaseOutcome{Status: ReleaseFailed, Message: fmt.Sprintf("could not reach holder: %v", err)}
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(io.LimitReader(resp.Body, maxResponseBodyBytes+1))
	if err != nil {
		return ReleaseOutcome{Status: ReleaseFailed, Message: fmt.Sprintf("read holder response: %v", err)}
	}
	if len(body) > maxResponseBodyBytes {
		return ReleaseOutcome{Status: ReleaseFailed, Message: "holder response body too large"}
	}

	var grabResp protocol.GrabResponse
	if err := json.Unmarshal(body, &grabResp); err != nil {
		return ReleaseOutcome{
			Status:  ReleaseFailed,
			Message: fmt.Sprintf("malformed holder response (%d)", resp.StatusCode),
		}
	}

	if resp.StatusCode == http.StatusConflict && grabResp.Status == protocol.StatusRejected {
		msg := strings.TrimSpace(grabResp.Message)
		if msg == "" {
			msg = "Device is not connected on holder"
		}
		return ReleaseOutcome{Status: ReleaseAlreadyDisconnected, Message: msg}
	}

	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		msg := strings.TrimSpace(grabResp.Message)
		if msg == "" {
			msg = fmt.Sprintf("release rejected (%d)", resp.StatusCode)
		}
		if grabResp.Status == protocol.StatusRejected {
			return ReleaseOutcome{Status: ReleaseRejected, Message: msg}
		}
		return ReleaseOutcome{Status: ReleaseFailed, Message: msg}
	}

	if res := c.verifier.VerifyResponse(grabResp, psk, signedReq.Nonce); !res.Valid {
		return ReleaseOutcome{
			Status:  ReleaseFailed,
			Message: fmt.Sprintf("unverified holder response (%s)", res.Reason),
		}
	}
	if !strings.EqualFold(grabResp.DeviceAddress, deviceAddress) {
		return ReleaseOutcome{
			Status:  ReleaseFailed,
			Message: fmt.Sprintf("holder released unexpected device (%s)", grabResp.DeviceAddress),
		}
	}
	if grabResp.Status != protocol.StatusOK {
		msg := strings.TrimSpace(grabResp.Message)
		if msg == "" {
			msg = "release failed"
		}
		if grabResp.Status == protocol.StatusRejected {
			return ReleaseOutcome{Status: ReleaseRejected, Message: msg}
		}
		return ReleaseOutcome{Status: ReleaseFailed, Message: msg}
	}

	return ReleaseOutcome{Status: ReleaseOK, Message: grabResp.Message}
}

type Options struct {
	Target           string
	Identity         peers.Identity
	Peers            []peers.Peer
	Controller       bluez.Controller
	Discoverer       mdns.Discoverer
	HolderClient     HolderClient
	DiscoveryTimeout time.Duration
	OnProgress       func(Progress)
}

func Grab(ctx context.Context, opts Options) (Result, error) {
	if opts.Controller == nil {
		return Result{}, errors.New("BlueZ controller is required")
	}
	if opts.HolderClient == nil {
		opts.HolderClient = NewHTTPClient(HTTPClientConfig{})
	}
	if opts.DiscoveryTimeout <= 0 {
		opts.DiscoveryTimeout = DefaultDiscoveryTimeout
	}
	report := opts.OnProgress
	if report == nil {
		report = func(Progress) {}
	}

	dev, err := opts.Controller.FindDevice(ctx, opts.Target)
	if err != nil {
		return Result{}, err
	}
	if dev.Connected {
		return Result{Device: dev, AlreadyConnected: true}, nil
	}

	pskByID := make(map[string][]byte, len(opts.Peers))
	for _, p := range opts.Peers {
		psk, err := p.PSKBytes()
		if err != nil {
			continue
		}
		pskByID[p.PeerID] = psk
	}

	var releasedFrom string
	if len(pskByID) > 0 && opts.Discoverer != nil {
		report(Progress{Stage: StageSearching, Device: dev})
		discCtx, cancel := context.WithTimeout(ctx, opts.DiscoveryTimeout)
		discovered, discErr := opts.Discoverer.Discover(discCtx, mdns.DiscoverOptions{
			ExcludeDeviceID: opts.Identity.DeviceID,
			PairingOnly:     false,
		})
		cancel()
		if discErr != nil {
			if ctx.Err() != nil {
				return Result{}, ctx.Err()
			}
			// Proceed to local connect even if mDNS discovery had a transient error.
			discovered = nil
		}

		candidates := pairedCandidates(discovered, opts.Identity.DeviceID, pskByID)
		releasedFrom, err = releaseFromActiveHolder(ctx, opts.HolderClient, candidates, dev, opts.Identity.DeviceID, report)
		if err != nil {
			return Result{}, err
		}
	}

	report(Progress{Stage: StageConnecting, Device: dev, HolderName: releasedFrom})
	connectedDev, err := opts.Controller.Connect(ctx, dev.Address)
	if errors.Is(err, bluez.ErrAlreadyConnected) {
		connectedDev.Connected = true
		return Result{
			Device:           connectedDev,
			AlreadyConnected: true,
			ReleasedFrom:     releasedFrom,
		}, nil
	}
	if err != nil {
		return Result{}, err
	}
	return Result{
		Device:       connectedDev,
		ReleasedFrom: releasedFrom,
	}, nil
}

type pairedCandidate struct {
	peer mdns.DiscoveredPeer
	psk  []byte
}

func pairedCandidates(
	discovered []mdns.DiscoveredPeer,
	localDeviceID string,
	pskByID map[string][]byte,
) []pairedCandidate {
	out := make([]pairedCandidate, 0, len(discovered))
	for _, peer := range discovered {
		if peer.PairingAvailable {
			continue
		}
		id := strings.TrimSpace(peer.DeviceID)
		if id == "" || id == localDeviceID {
			continue
		}
		psk, ok := pskByID[id]
		if !ok || len(psk) == 0 {
			continue
		}
		out = append(out, pairedCandidate{peer: peer, psk: psk})
	}
	return out
}

func releaseFromActiveHolder(
	ctx context.Context,
	client HolderClient,
	candidates []pairedCandidate,
	dev bluez.Device,
	localDeviceID string,
	report func(Progress),
) (string, error) {
	if len(candidates) == 0 {
		return "", nil
	}

	// 1. Direct matches from mDNS TXT connected device list.
	for _, cand := range candidates {
		if !containsAddress(cand.peer.ConnectedAddresses, dev.Address) {
			continue
		}
		releasedName, released, err := tryRelease(ctx, client, cand, dev, localDeviceID, report)
		if err != nil {
			return "", err
		}
		if released {
			return releasedName, nil
		}
	}

	// 2. Fallback to GET /status for paired peers whose mDNS TXT had no connected addresses.
	for _, cand := range candidates {
		if len(cand.peer.ConnectedAddresses) > 0 {
			continue
		}
		status, err := client.FetchStatus(ctx, cand.peer.Host, cand.peer.Port)
		if err != nil {
			continue
		}
		if !statusHoldsDevice(status, dev.Address) {
			continue
		}
		if status.HolderName != nil && strings.TrimSpace(*status.HolderName) != "" {
			cand.peer.DisplayName = strings.TrimSpace(*status.HolderName)
		}
		releasedName, released, err := tryRelease(ctx, client, cand, dev, localDeviceID, report)
		if err != nil {
			return "", err
		}
		if released {
			return releasedName, nil
		}
	}

	return "", nil
}

func tryRelease(
	ctx context.Context,
	client HolderClient,
	cand pairedCandidate,
	dev bluez.Device,
	localDeviceID string,
	report func(Progress),
) (string, bool, error) {
	holderName := cand.peer.DisplayName
	if strings.TrimSpace(holderName) == "" {
		holderName = cand.peer.Endpoint()
	}
	report(Progress{
		Stage:      StageReleasing,
		Device:     dev,
		HolderName: holderName,
	})

	outcome := client.RequestRelease(
		ctx,
		cand.peer.Host,
		cand.peer.Port,
		dev.Address,
		localDeviceID,
		cand.psk,
	)
	switch outcome.Status {
	case ReleaseOK:
		return holderName, true, nil
	case ReleaseAlreadyDisconnected:
		return "", false, nil
	case ReleaseRejected:
		if outcome.Message == "UNKNOWN_REQUESTER" {
			return "", false, fmt.Errorf("host %s no longer recognizes this device; remove and re-pair it", holderName)
		}
		return "", false, fmt.Errorf("release rejected by %s: %s", holderName, outcome.Message)
	default:
		return "", false, fmt.Errorf("failed to release from %s: %s", holderName, outcome.Message)
	}
}

func containsAddress(addrs []string, target string) bool {
	for _, a := range addrs {
		if strings.EqualFold(a, target) {
			return true
		}
	}
	return false
}

func statusHoldsDevice(status holder.StatusResponse, target string) bool {
	for _, d := range status.Devices {
		if d.Connected && strings.EqualFold(d.DeviceAddress, target) {
			return true
		}
	}
	return status.Connected && status.DeviceAddress != nil && strings.EqualFold(*status.DeviceAddress, target)
}

func formatBaseURL(host string, port int) string {
	trimmed := strings.TrimSpace(host)
	if strings.Contains(trimmed, ":") && strings.Contains(trimmed, "%") {
		trimmed = strings.ReplaceAll(trimmed, "%", "%25")
	}
	return "http://" + net.JoinHostPort(trimmed, strconv.Itoa(port))
}
