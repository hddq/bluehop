package protocol

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

const (
	ProtocolVersion          = 1
	DefaultMaxSkewSeconds    = int64(30)
	DefaultNonceRetentionSec = int64(60)
	DefaultNonceMaxEntries   = 256
	expectedSignatureHexLen  = 64
	nonceByteLen             = 16
)

type GrabResponseStatus string

const (
	StatusOK       GrabResponseStatus = "ok"
	StatusRejected GrabResponseStatus = "rejected"
	StatusError    GrabResponseStatus = "error"
)

func (s GrabResponseStatus) IsValid() bool {
	switch s {
	case StatusOK, StatusRejected, StatusError:
		return true
	default:
		return false
	}
}

func (s *GrabResponseStatus) UnmarshalJSON(data []byte) error {
	var raw string
	if err := json.Unmarshal(data, &raw); err != nil {
		return err
	}
	status := GrabResponseStatus(raw)
	if !status.IsValid() {
		return fmt.Errorf("invalid grab response status: %q", raw)
	}
	*s = status
	return nil
}

type GrabRequest struct {
	Version           int    `json:"version"`
	DeviceAddress     string `json:"deviceAddress"`
	RequesterID       string `json:"requesterId"`
	TimestampEpochSec int64  `json:"timestampEpochSec"`
	Nonce             string `json:"nonce"`
	Signature         string `json:"signature"`
}

func (r GrabRequest) CanonicalPayload() string {
	return fmt.Sprintf(
		"v%d\nrequest\n%d\n%s\n%s\n%d\n%s",
		ProtocolVersion,
		r.Version,
		r.DeviceAddress,
		r.RequesterID,
		r.TimestampEpochSec,
		r.Nonce,
	)
}

type GrabResponse struct {
	Version           int                `json:"version"`
	Status            GrabResponseStatus `json:"status"`
	DeviceAddress     string             `json:"deviceAddress"`
	TimestampEpochSec int64              `json:"timestampEpochSec"`
	Nonce             string             `json:"nonce"`
	Message           string             `json:"message"`
	Signature         string             `json:"signature"`
}

func (r GrabResponse) CanonicalPayload() string {
	return fmt.Sprintf(
		"v%d\nresponse\n%d\n%s\n%s\n%d\n%s\n%s",
		ProtocolVersion,
		r.Version,
		r.Status,
		r.DeviceAddress,
		r.TimestampEpochSec,
		r.Nonce,
		r.Message,
	)
}

type VerificationFailureReason string

const (
	ReasonUnsupportedVersion   VerificationFailureReason = "UNSUPPORTED_VERSION"
	ReasonInvalidFormat        VerificationFailureReason = "INVALID_FORMAT"
	ReasonInvalidSignature     VerificationFailureReason = "INVALID_SIGNATURE"
	ReasonTimestampOutOfWindow VerificationFailureReason = "TIMESTAMP_OUT_OF_WINDOW"
	ReasonReplayedNonce        VerificationFailureReason = "REPLAYED_NONCE"
	ReasonNonceMismatch        VerificationFailureReason = "NONCE_MISMATCH"
)

type VerificationResult struct {
	Valid  bool
	Reason VerificationFailureReason
}

func Sign(psk []byte, payload string) (string, error) {
	if len(psk) == 0 {
		return "", errors.New("PSK must not be empty")
	}
	mac := hmac.New(sha256.New, psk)
	mac.Write([]byte(payload))
	return hex.EncodeToString(mac.Sum(nil)), nil
}

func VerifySignature(psk []byte, payload, signatureHex string) bool {
	if len(psk) == 0 || len(signatureHex) != expectedSignatureHexLen {
		return false
	}
	provided, err := hex.DecodeString(signatureHex)
	if err != nil {
		return false
	}
	mac := hmac.New(sha256.New, psk)
	mac.Write([]byte(payload))
	return hmac.Equal(mac.Sum(nil), provided)
}

func SignRequest(psk []byte, req GrabRequest) (GrabRequest, error) {
	if req.Version == 0 {
		req.Version = ProtocolVersion
	}
	if !isValidSingleLineField(req.DeviceAddress) ||
		!isValidSingleLineField(req.RequesterID) ||
		!isValidSingleLineField(req.Nonce) {
		return GrabRequest{}, errors.New("invalid grab request fields")
	}
	sig, err := Sign(psk, req.CanonicalPayload())
	if err != nil {
		return GrabRequest{}, err
	}
	req.Signature = sig
	return req, nil
}

func SignResponse(psk []byte, resp GrabResponse) (GrabResponse, error) {
	if resp.Version == 0 {
		resp.Version = ProtocolVersion
	}
	if !resp.Status.IsValid() {
		return GrabResponse{}, fmt.Errorf("invalid grab response status: %q", resp.Status)
	}
	if !isValidSingleLineField(resp.DeviceAddress) ||
		!isValidSingleLineField(resp.Nonce) ||
		!utf8.ValidString(resp.Message) {
		return GrabResponse{}, errors.New("invalid grab response fields")
	}
	sig, err := Sign(psk, resp.CanonicalPayload())
	if err != nil {
		return GrabResponse{}, err
	}
	resp.Signature = sig
	return resp, nil
}

func GenerateNonce() (string, error) {
	buf := make([]byte, nonceByteLen)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	return hex.EncodeToString(buf), nil
}

type nonceEntry struct {
	nonce     string
	timestamp int64
}

type NonceReplayGuard struct {
	mu               sync.Mutex
	retentionSeconds int64
	maxEntries       int
	entries          []nonceEntry
	seen             map[string]struct{}
}

func NewNonceReplayGuard(retentionSeconds int64, maxEntries int) *NonceReplayGuard {
	if retentionSeconds <= 0 {
		retentionSeconds = DefaultNonceRetentionSec
	}
	if maxEntries <= 0 {
		maxEntries = DefaultNonceMaxEntries
	}
	return &NonceReplayGuard{
		retentionSeconds: retentionSeconds,
		maxEntries:       maxEntries,
		seen:             make(map[string]struct{}),
	}
}

func (g *NonceReplayGuard) CheckAndRecord(nonce string, timestampEpochSec, nowEpochSec int64) bool {
	g.mu.Lock()
	defer g.mu.Unlock()

	g.pruneExpiredLocked(nowEpochSec)
	if _, exists := g.seen[nonce]; exists {
		return false
	}
	if len(g.entries) >= g.maxEntries {
		oldest := g.entries[0]
		g.entries = g.entries[1:]
		delete(g.seen, oldest.nonce)
	}
	g.entries = append(g.entries, nonceEntry{nonce: nonce, timestamp: timestampEpochSec})
	g.seen[nonce] = struct{}{}
	return true
}

func (g *NonceReplayGuard) Size() int {
	g.mu.Lock()
	defer g.mu.Unlock()
	return len(g.entries)
}

func (g *NonceReplayGuard) pruneExpiredLocked(nowEpochSec int64) {
	cutoff := nowEpochSec - g.retentionSeconds
	kept := g.entries[:0]
	for _, entry := range g.entries {
		if entry.timestamp < cutoff {
			delete(g.seen, entry.nonce)
			continue
		}
		kept = append(kept, entry)
	}
	g.entries = kept
}

type ProtocolVerifier struct {
	maxSkewSeconds  int64
	replayGuard     *NonceReplayGuard
	nowEpochSeconds func() int64
}

func NewProtocolVerifier(maxSkewSeconds int64, replayGuard *NonceReplayGuard, nowFn func() int64) *ProtocolVerifier {
	if maxSkewSeconds <= 0 {
		maxSkewSeconds = DefaultMaxSkewSeconds
	}
	if replayGuard == nil {
		replayGuard = NewNonceReplayGuard(maxSkewSeconds*2, DefaultNonceMaxEntries)
	}
	if nowFn == nil {
		nowFn = func() int64 { return time.Now().Unix() }
	}
	return &ProtocolVerifier{
		maxSkewSeconds:  maxSkewSeconds,
		replayGuard:     replayGuard,
		nowEpochSeconds: nowFn,
	}
}

func (v *ProtocolVerifier) VerifyRequest(req GrabRequest, psk []byte) VerificationResult {
	if req.Version != ProtocolVersion {
		return VerificationResult{Reason: ReasonUnsupportedVersion}
	}
	if !isValidSingleLineField(req.DeviceAddress) ||
		!isValidSingleLineField(req.RequesterID) ||
		!isValidSingleLineField(req.Nonce) {
		return VerificationResult{Reason: ReasonInvalidFormat}
	}
	if !VerifySignature(psk, req.CanonicalPayload(), req.Signature) {
		return VerificationResult{Reason: ReasonInvalidSignature}
	}
	now := v.nowEpochSeconds()
	if !withinSkewWindow(req.TimestampEpochSec, now, v.maxSkewSeconds) {
		return VerificationResult{Reason: ReasonTimestampOutOfWindow}
	}
	if !v.replayGuard.CheckAndRecord(req.Nonce, req.TimestampEpochSec, now) {
		return VerificationResult{Reason: ReasonReplayedNonce}
	}
	return VerificationResult{Valid: true}
}

func (v *ProtocolVerifier) VerifyResponse(resp GrabResponse, psk []byte, expectedNonce string) VerificationResult {
	if resp.Version != ProtocolVersion {
		return VerificationResult{Reason: ReasonUnsupportedVersion}
	}
	if !resp.Status.IsValid() ||
		!isValidSingleLineField(resp.DeviceAddress) ||
		!isValidSingleLineField(resp.Nonce) ||
		!utf8.ValidString(resp.Message) {
		return VerificationResult{Reason: ReasonInvalidFormat}
	}
	if !VerifySignature(psk, resp.CanonicalPayload(), resp.Signature) {
		return VerificationResult{Reason: ReasonInvalidSignature}
	}
	now := v.nowEpochSeconds()
	if !withinSkewWindow(resp.TimestampEpochSec, now, v.maxSkewSeconds) {
		return VerificationResult{Reason: ReasonTimestampOutOfWindow}
	}
	if expectedNonce != "" && resp.Nonce != expectedNonce {
		return VerificationResult{Reason: ReasonNonceMismatch}
	}
	return VerificationResult{Valid: true}
}

func isValidSingleLineField(s string) bool {
	return strings.TrimSpace(s) != "" &&
		utf8.ValidString(s) &&
		!strings.ContainsAny(s, "\r\n")
}

func withinSkewWindow(ts, now, maxSkew int64) bool {
	if maxSkew < 0 {
		return false
	}
	if ts >= now {
		return uint64(ts)-uint64(now) <= uint64(maxSkew)
	}
	return uint64(now)-uint64(ts) <= uint64(maxSkew)
}
