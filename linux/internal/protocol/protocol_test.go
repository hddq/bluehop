package protocol

import (
	"encoding/hex"
	"encoding/json"
	"math"
	"os"
	"path/filepath"
	"testing"
)

type replayExpectation struct {
	ExpectedValid  bool   `json:"expectedValid"`
	ExpectedReason string `json:"expectedReason"`
}

type testVector struct {
	Name              string             `json:"name"`
	Kind              string             `json:"kind"`
	CanonicalPayload  string             `json:"canonicalPayload"`
	ExpectedNonce     string             `json:"expectedNonce"`
	Payload           json.RawMessage    `json:"payload"`
	ExpectedValid     bool               `json:"expectedValid"`
	ExpectedReason    string             `json:"expectedReason"`
	ReplaySecondCheck *replayExpectation `json:"replaySecondCheck"`
}

type vectorFile struct {
	ProtocolVersion int          `json:"protocolVersion"`
	MaxSkewSeconds  int64        `json:"maxSkewSeconds"`
	PSKHex          string       `json:"pskHex"`
	NowEpochSec     int64        `json:"nowEpochSec"`
	Vectors         []testVector `json:"vectors"`
}

func loadTestVectors(t *testing.T) vectorFile {
	t.Helper()
	path := filepath.Join("..", "..", "..", "protocol", "test-vectors.json")
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read %s: %v", path, err)
	}
	var vf vectorFile
	if err := json.Unmarshal(data, &vf); err != nil {
		t.Fatalf("unmarshal %s: %v", path, err)
	}
	return vf
}

func assertResult(t *testing.T, label string, got VerificationResult, wantValid bool, wantReason string) {
	t.Helper()
	if got.Valid != wantValid || string(got.Reason) != wantReason {
		t.Fatalf("%s: got {Valid:%v Reason:%q}, want {Valid:%v Reason:%q}",
			label, got.Valid, got.Reason, wantValid, wantReason)
	}
}

func TestProtocolVectors(t *testing.T) {
	vf := loadTestVectors(t)
	psk, err := hex.DecodeString(vf.PSKHex)
	if err != nil {
		t.Fatalf("decode pskHex: %v", err)
	}

	guard := NewNonceReplayGuard(vf.MaxSkewSeconds*2, DefaultNonceMaxEntries)
	verifier := NewProtocolVerifier(vf.MaxSkewSeconds, guard, func() int64 { return vf.NowEpochSec })

	for _, vec := range vf.Vectors {
		t.Run(vec.Name, func(t *testing.T) {
			switch vec.Kind {
			case "request":
				var req GrabRequest
				if err := json.Unmarshal(vec.Payload, &req); err != nil {
					t.Fatalf("unmarshal request: %v", err)
				}
				if req.CanonicalPayload() != vec.CanonicalPayload {
					t.Fatalf("canonical payload mismatch:\ngot:  %q\nwant: %q", req.CanonicalPayload(), vec.CanonicalPayload)
				}
				raw, err := json.Marshal(req)
				if err != nil {
					t.Fatalf("marshal request: %v", err)
				}
				var roundTrip GrabRequest
				if err := json.Unmarshal(raw, &roundTrip); err != nil {
					t.Fatalf("round-trip unmarshal request: %v", err)
				}

				res := verifier.VerifyRequest(roundTrip, psk)
				assertResult(t, vec.Name, res, vec.ExpectedValid, vec.ExpectedReason)

				if vec.ReplaySecondCheck != nil {
					replay := verifier.VerifyRequest(roundTrip, psk)
					assertResult(t, vec.Name+" replay", replay, vec.ReplaySecondCheck.ExpectedValid, vec.ReplaySecondCheck.ExpectedReason)
				}

			case "response":
				var resp GrabResponse
				if err := json.Unmarshal(vec.Payload, &resp); err != nil {
					t.Fatalf("unmarshal response: %v", err)
				}
				if resp.CanonicalPayload() != vec.CanonicalPayload {
					t.Fatalf("canonical payload mismatch:\ngot:  %q\nwant: %q", resp.CanonicalPayload(), vec.CanonicalPayload)
				}
				raw, err := json.Marshal(resp)
				if err != nil {
					t.Fatalf("marshal response: %v", err)
				}
				var roundTrip GrabResponse
				if err := json.Unmarshal(raw, &roundTrip); err != nil {
					t.Fatalf("round-trip unmarshal response: %v", err)
				}

				res := verifier.VerifyResponse(roundTrip, psk, vec.ExpectedNonce)
				assertResult(t, vec.Name, res, vec.ExpectedValid, vec.ExpectedReason)

			default:
				t.Fatalf("unknown vector kind %q", vec.Kind)
			}
		})
	}
}

func TestSigningAndReplayPruningAcrossWindow(t *testing.T) {
	psk, err := hex.DecodeString("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff")
	if err != nil {
		t.Fatalf("decode psk: %v", err)
	}

	clock := int64(1700000000)
	guard := NewNonceReplayGuard(60, 2)
	verifier := NewProtocolVerifier(30, guard, func() int64 { return clock })

	req1, err := SignRequest(psk, GrabRequest{
		DeviceAddress:     "AA:BB:CC:DD:EE:FF",
		RequesterID:       "desktop",
		TimestampEpochSec: clock,
		Nonce:             "nonce-1",
	})
	if err != nil {
		t.Fatalf("SignRequest: %v", err)
	}

	assertResult(t, "first request", verifier.VerifyRequest(req1, psk), true, "")
	assertResult(t, "replayed request", verifier.VerifyRequest(req1, psk), false, string(ReasonReplayedNonce))

	clock += 61
	req2, err := SignRequest(psk, GrabRequest{
		DeviceAddress:     "AA:BB:CC:DD:EE:FF",
		RequesterID:       "desktop",
		TimestampEpochSec: clock,
		Nonce:             "nonce-2",
	})
	if err != nil {
		t.Fatalf("SignRequest 2: %v", err)
	}
	assertResult(t, "after expiry", verifier.VerifyRequest(req2, psk), true, "")
	if guard.Size() != 1 {
		t.Fatalf("expected expired nonce to be pruned, guard size = %d", guard.Size())
	}

	resp, err := SignResponse(psk, GrabResponse{
		Status:            StatusError,
		DeviceAddress:     "AA:BB:CC:DD:EE:FF",
		TimestampEpochSec: clock,
		Nonce:             "nonce-2",
		Message:           "timeout",
	})
	if err != nil {
		t.Fatalf("SignResponse: %v", err)
	}
	assertResult(t, "signed response", verifier.VerifyResponse(resp, psk, "nonce-2"), true, "")
}

func TestReplayGuardEvictsOldestAtCapacity(t *testing.T) {
	guard := NewNonceReplayGuard(60, 2)
	if !guard.CheckAndRecord("n1", 100, 100) {
		t.Fatal("expected n1 to be accepted")
	}
	if !guard.CheckAndRecord("n2", 100, 100) {
		t.Fatal("expected n2 to be accepted")
	}
	if !guard.CheckAndRecord("n3", 100, 100) {
		t.Fatal("expected n3 to evict oldest and be accepted")
	}
	if guard.Size() != 2 {
		t.Fatalf("expected size 2, got %d", guard.Size())
	}
	if guard.CheckAndRecord("n2", 100, 100) {
		t.Fatal("expected n2 to still be rejected as replay")
	}
}

func TestTimestampSkewNoOverflow(t *testing.T) {
	psk := []byte("0123456789abcdef0123456789abcdef")
	clock := int64(1700000000)
	verifier := NewProtocolVerifier(30, nil, func() int64 { return clock })

	// Previously absInt64(clock - extremeTS) could overflow to math.MinInt64 (< 0 <= 30).
	extremeTS := clock + math.MinInt64
	req, err := SignRequest(psk, GrabRequest{
		DeviceAddress:     "AA:BB:CC:DD:EE:FF",
		RequesterID:       "desktop",
		TimestampEpochSec: extremeTS,
		Nonce:             "nonce-overflow",
	})
	if err != nil {
		t.Fatalf("SignRequest: %v", err)
	}
	assertResult(t, "overflow request", verifier.VerifyRequest(req, psk), false, string(ReasonTimestampOutOfWindow))

	resp, err := SignResponse(psk, GrabResponse{
		Status:            StatusOK,
		DeviceAddress:     "AA:BB:CC:DD:EE:FF",
		TimestampEpochSec: extremeTS,
		Nonce:             "nonce-overflow",
	})
	if err != nil {
		t.Fatalf("SignResponse: %v", err)
	}
	assertResult(t, "overflow response", verifier.VerifyResponse(resp, psk, "nonce-overflow"), false, string(ReasonTimestampOutOfWindow))
}

func TestValidationRejectsMissingStatusNewlinesAndInvalidUTF8(t *testing.T) {
	psk := []byte("0123456789abcdef0123456789abcdef")
	clock := int64(1700000000)
	verifier := NewProtocolVerifier(30, nil, func() int64 { return clock })

	if _, err := SignResponse(psk, GrabResponse{
		DeviceAddress:     "AA:BB:CC:DD:EE:FF",
		TimestampEpochSec: clock,
		Nonce:             "nonce-1",
	}); err == nil {
		t.Fatal("expected SignResponse to reject missing status")
	}

	var omitted GrabResponse
	if err := json.Unmarshal([]byte(`{"version":1,"deviceAddress":"AA:BB:CC:DD:EE:FF","timestampEpochSec":1700000000,"nonce":"nonce-1","signature":"`+
		func() string {
			sig, _ := Sign(psk, GrabResponse{Version: 1, DeviceAddress: "AA:BB:CC:DD:EE:FF", TimestampEpochSec: clock, Nonce: "nonce-1"}.CanonicalPayload())
			return sig
		}()+`"}`), &omitted); err != nil {
		t.Fatalf("unmarshal omitted status: %v", err)
	}
	assertResult(t, "omitted status", verifier.VerifyResponse(omitted, psk, "nonce-1"), false, string(ReasonInvalidFormat))

	if _, err := SignRequest(psk, GrabRequest{
		DeviceAddress:     "AA:BB\nCC",
		RequesterID:       "desktop",
		TimestampEpochSec: clock,
		Nonce:             "nonce-nl",
	}); err == nil {
		t.Fatal("expected SignRequest to reject newline in DeviceAddress")
	}

	if _, err := SignRequest(psk, GrabRequest{
		DeviceAddress:     "AA:BB:CC:DD:EE:FF",
		RequesterID:       "bad\xffutf8",
		TimestampEpochSec: clock,
		Nonce:             "nonce-utf8",
	}); err == nil {
		t.Fatal("expected SignRequest to reject invalid UTF-8")
	}

	// Newlines remain allowed in the final Message field.
	resp, err := SignResponse(psk, GrabResponse{
		Status:            StatusError,
		DeviceAddress:     "AA:BB:CC:DD:EE:FF",
		TimestampEpochSec: clock,
		Nonce:             "nonce-msg-nl",
		Message:           "line1\nline2",
	})
	if err != nil {
		t.Fatalf("expected newline in Message to succeed: %v", err)
	}
	assertResult(t, "multiline message", verifier.VerifyResponse(resp, psk, "nonce-msg-nl"), true, "")
}
