package protocol

// Pairing (protocol v2): ephemeral ECDH P-256 + 6-digit SAS + mutual HMAC key confirmation.
//
//  1. Client -> POST /pair/start   {version, requesterId, requesterName, clientPub}
//     Server <- {version, sessionId, responderId, responderName, serverPub}
//     Public keys are base64 (standard, padded) X.509 SubjectPublicKeyInfo DER.
//  2. Both sides compute shared = ECDH(priv, peerPub) (32-byte X coordinate) and
//     transcript = "bluehop-pair-v2\n" + sessionId + "\n" + clientPub + "\n" + serverPub
//     salt       = SHA-256(transcript)
//     psk        = HKDF-SHA256(shared, salt, "bluehop-pair-v2 psk", 32)
//     confirmKey = HKDF-SHA256(shared, salt, "bluehop-pair-v2 confirm", 32)
//     sas        = uint32be(HKDF-SHA256(shared, salt, "bluehop-pair-v2 sas", 4)) mod 1e6, zero-padded to 6 digits
//  3. Both devices display the SAS. The server user accepts or rejects while the
//     client polls GET /pair/status?sessionId=... (pending|approved|rejected|expired).
//  4. After approval: client -> POST /pair/confirm {version, sessionId, clientConfirm};
//     server verifies it (constant time), persists the peer, and replies {version, sessionId, serverConfirm};
//     the client verifies serverConfirm (constant time) before persisting.
//     confirm = hex(HMAC-SHA256(confirmKey, "bluehop-confirm-v2\n" + role + "\n" + sessionId + "\n" + requesterId + "\n" + responderId))
//     with role "client" or "server".
//
// No PIN or secret-derived value other than the confirmation MACs (sent only after
// the user compared the SAS) ever crosses the network.

import (
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/hkdf"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
)

const (
	PairingVersion         = 2
	PairingSessionTTLSec   = 60
	ConfirmRoleClient      = "client"
	ConfirmRoleServer      = "server"
	pairingKeyLen          = 32
	sasModulus             = 1_000_000
	pairingTranscriptLabel = "bluehop-pair-v2"
	confirmLabel           = "bluehop-confirm-v2"
)

type PairSessionStatus string

const (
	PairStatusPending  PairSessionStatus = "pending"
	PairStatusApproved PairSessionStatus = "approved"
	PairStatusRejected PairSessionStatus = "rejected"
	PairStatusExpired  PairSessionStatus = "expired"
)

func (s PairSessionStatus) IsValid() bool {
	switch s {
	case PairStatusPending, PairStatusApproved, PairStatusRejected, PairStatusExpired:
		return true
	default:
		return false
	}
}

func (s *PairSessionStatus) UnmarshalJSON(data []byte) error {
	var raw string
	if err := json.Unmarshal(data, &raw); err != nil {
		return err
	}
	status := PairSessionStatus(raw)
	if !status.IsValid() {
		return fmt.Errorf("invalid pair session status: %q", raw)
	}
	*s = status
	return nil
}

type PairStartRequest struct {
	Version       int    `json:"version"`
	RequesterID   string `json:"requesterId"`
	RequesterName string `json:"requesterName"`
	ClientPub     string `json:"clientPub"`
}

type PairStartResponse struct {
	Version       int    `json:"version"`
	SessionID     string `json:"sessionId"`
	ResponderID   string `json:"responderId"`
	ResponderName string `json:"responderName"`
	ServerPub     string `json:"serverPub"`
}

type PairStatusResponse struct {
	Version   int               `json:"version"`
	SessionID string            `json:"sessionId"`
	Status    PairSessionStatus `json:"status"`
}

type PairConfirmRequest struct {
	Version       int    `json:"version"`
	SessionID     string `json:"sessionId"`
	ClientConfirm string `json:"clientConfirm"`
}

type PairConfirmResponse struct {
	Version       int    `json:"version"`
	SessionID     string `json:"sessionId"`
	ServerConfirm string `json:"serverConfirm"`
}

type PairErrorResponse struct {
	Version int    `json:"version"`
	Message string `json:"message"`
}

// PairingKeys holds the values derived from the ECDH shared secret.
type PairingKeys struct {
	PSK        []byte
	SAS        string
	ConfirmKey []byte
}

func (k PairingKeys) PSKHex() string { return hex.EncodeToString(k.PSK) }

func GeneratePairingKey() (*ecdh.PrivateKey, error) {
	return ecdh.P256().GenerateKey(rand.Reader)
}

// EncodePairingPublicKey returns base64(X.509 SPKI DER), matching Android's PublicKey.encoded.
func EncodePairingPublicKey(pub *ecdh.PublicKey) (string, error) {
	der, err := x509.MarshalPKIXPublicKey(pub)
	if err != nil {
		return "", err
	}
	return base64.StdEncoding.EncodeToString(der), nil
}

func DecodePairingPublicKey(b64 string) (*ecdh.PublicKey, error) {
	der, err := base64.StdEncoding.DecodeString(b64)
	if err != nil {
		return nil, fmt.Errorf("decode public key: %w", err)
	}
	parsed, err := x509.ParsePKIXPublicKey(der)
	if err != nil {
		return nil, fmt.Errorf("parse public key: %w", err)
	}
	ecdsaPub, ok := parsed.(*ecdsa.PublicKey)
	if !ok {
		return nil, errors.New("public key is not an EC key")
	}
	pub, err := ecdsaPub.ECDH()
	if err != nil {
		return nil, err
	}
	if pub.Curve() != ecdh.P256() {
		return nil, errors.New("public key is not on P-256")
	}
	return pub, nil
}

func PairingTranscript(sessionID, clientPub, serverPub string) string {
	return pairingTranscriptLabel + "\n" + sessionID + "\n" + clientPub + "\n" + serverPub
}

func DerivePairingKeys(sharedSecret []byte, sessionID, clientPub, serverPub string) (PairingKeys, error) {
	if len(sharedSecret) == 0 {
		return PairingKeys{}, errors.New("shared secret must not be empty")
	}
	salt := sha256.Sum256([]byte(PairingTranscript(sessionID, clientPub, serverPub)))
	psk, err := hkdf.Key(sha256.New, sharedSecret, salt[:], pairingTranscriptLabel+" psk", pairingKeyLen)
	if err != nil {
		return PairingKeys{}, err
	}
	confirmKey, err := hkdf.Key(sha256.New, sharedSecret, salt[:], pairingTranscriptLabel+" confirm", pairingKeyLen)
	if err != nil {
		return PairingKeys{}, err
	}
	sasBytes, err := hkdf.Key(sha256.New, sharedSecret, salt[:], pairingTranscriptLabel+" sas", 4)
	if err != nil {
		return PairingKeys{}, err
	}
	return PairingKeys{PSK: psk, SAS: SASFromBytes(sasBytes), ConfirmKey: confirmKey}, nil
}

// SASFromBytes maps 4 bytes (big-endian uint32) to a zero-padded 6-digit code.
func SASFromBytes(b []byte) string {
	return fmt.Sprintf("%06d", binary.BigEndian.Uint32(b)%sasModulus)
}

func ConfirmPayload(role, sessionID, requesterID, responderID string) string {
	return confirmLabel + "\n" + role + "\n" + sessionID + "\n" + requesterID + "\n" + responderID
}

func ComputeConfirm(confirmKey []byte, role, sessionID, requesterID, responderID string) (string, error) {
	return Sign(confirmKey, ConfirmPayload(role, sessionID, requesterID, responderID))
}

// VerifyConfirm checks a peer's confirmation MAC in constant time.
func VerifyConfirm(confirmKey []byte, role, sessionID, requesterID, responderID, macHex string) bool {
	return VerifySignature(confirmKey, ConfirmPayload(role, sessionID, requesterID, responderID), macHex)
}

// ValidPairingField reports whether s is usable as a single-line pairing field.
func ValidPairingField(s string) bool { return isValidSingleLineField(s) }
