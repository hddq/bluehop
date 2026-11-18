package protocol

import (
	"bytes"
	"crypto/ecdh"
	"crypto/elliptic"
	"crypto/ecdsa"
	"crypto/rand"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type sasCaseVector struct {
	BytesHex string `json:"bytesHex"`
	SAS      string `json:"sas"`
}

type pairingVector struct {
	PairingVersion      int             `json:"pairingVersion"`
	SessionID           string          `json:"sessionId"`
	RequesterID         string          `json:"requesterId"`
	ResponderID         string          `json:"responderId"`
	ClientPrivateKeyHex string          `json:"clientPrivateKeyHex"`
	ServerPrivateKeyHex string          `json:"serverPrivateKeyHex"`
	ClientPub           string          `json:"clientPub"`
	ServerPub           string          `json:"serverPub"`
	SharedSecretHex     string          `json:"sharedSecretHex"`
	PSKHex              string          `json:"pskHex"`
	ConfirmKeyHex       string          `json:"confirmKeyHex"`
	SAS                 string          `json:"sas"`
	ClientConfirm       string          `json:"clientConfirm"`
	ServerConfirm       string          `json:"serverConfirm"`
	SASCases            []sasCaseVector `json:"sasCases"`
}

type fullVectorFile struct {
	Pairing pairingVector `json:"pairing"`
}

func loadPairingVectors(t *testing.T) pairingVector {
	t.Helper()
	path := filepath.Join("..", "..", "..", "protocol", "test-vectors.json")
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read %s: %v", path, err)
	}
	var vf fullVectorFile
	if err := json.Unmarshal(data, &vf); err != nil {
		t.Fatalf("unmarshal %s: %v", path, err)
	}
	return vf.Pairing
}

func TestPairingVectors(t *testing.T) {
	pv := loadPairingVectors(t)
	if pv.PairingVersion != PairingVersion {
		t.Fatalf("pairingVersion = %d, want %d", pv.PairingVersion, PairingVersion)
	}

	clientPrivBytes, err := hex.DecodeString(pv.ClientPrivateKeyHex)
	if err != nil {
		t.Fatalf("decode clientPrivateKeyHex: %v", err)
	}
	serverPrivBytes, err := hex.DecodeString(pv.ServerPrivateKeyHex)
	if err != nil {
		t.Fatalf("decode serverPrivateKeyHex: %v", err)
	}

	clientKey, err := ecdh.P256().NewPrivateKey(clientPrivBytes)
	if err != nil {
		t.Fatalf("client NewPrivateKey: %v", err)
	}
	serverKey, err := ecdh.P256().NewPrivateKey(serverPrivBytes)
	if err != nil {
		t.Fatalf("server NewPrivateKey: %v", err)
	}

	gotClientPub, err := EncodePairingPublicKey(clientKey.PublicKey())
	if err != nil {
		t.Fatalf("EncodePairingPublicKey(client): %v", err)
	}
	if gotClientPub != pv.ClientPub {
		t.Fatalf("clientPub = %q, want %q", gotClientPub, pv.ClientPub)
	}

	gotServerPub, err := EncodePairingPublicKey(serverKey.PublicKey())
	if err != nil {
		t.Fatalf("EncodePairingPublicKey(server): %v", err)
	}
	if gotServerPub != pv.ServerPub {
		t.Fatalf("serverPub = %q, want %q", gotServerPub, pv.ServerPub)
	}

	decodedServerPub, err := DecodePairingPublicKey(pv.ServerPub)
	if err != nil {
		t.Fatalf("DecodePairingPublicKey(server): %v", err)
	}
	decodedClientPub, err := DecodePairingPublicKey(pv.ClientPub)
	if err != nil {
		t.Fatalf("DecodePairingPublicKey(client): %v", err)
	}

	clientShared, err := clientKey.ECDH(decodedServerPub)
	if err != nil {
		t.Fatalf("client ECDH: %v", err)
	}
	serverShared, err := serverKey.ECDH(decodedClientPub)
	if err != nil {
		t.Fatalf("server ECDH: %v", err)
	}
	if !bytes.Equal(clientShared, serverShared) {
		t.Fatalf("shared secret mismatch between client and server")
	}
	if gotHex := hex.EncodeToString(clientShared); gotHex != pv.SharedSecretHex {
		t.Fatalf("sharedSecretHex = %q, want %q", gotHex, pv.SharedSecretHex)
	}

	keys, err := DerivePairingKeys(clientShared, pv.SessionID, pv.ClientPub, pv.ServerPub)
	if err != nil {
		t.Fatalf("DerivePairingKeys: %v", err)
	}
	if keys.PSKHex() != pv.PSKHex {
		t.Fatalf("pskHex = %q, want %q", keys.PSKHex(), pv.PSKHex)
	}
	if gotConfirm := hex.EncodeToString(keys.ConfirmKey); gotConfirm != pv.ConfirmKeyHex {
		t.Fatalf("confirmKeyHex = %q, want %q", gotConfirm, pv.ConfirmKeyHex)
	}
	if keys.SAS != pv.SAS {
		t.Fatalf("sas = %q, want %q", keys.SAS, pv.SAS)
	}

	for _, sc := range pv.SASCases {
		raw, err := hex.DecodeString(sc.BytesHex)
		if err != nil {
			t.Fatalf("decode sas case %q: %v", sc.BytesHex, err)
		}
		if got := SASFromBytes(raw); got != sc.SAS {
			t.Fatalf("SASFromBytes(%s) = %q, want %q", sc.BytesHex, got, sc.SAS)
		}
	}

	clientConfirm, err := ComputeConfirm(keys.ConfirmKey, ConfirmRoleClient, pv.SessionID, pv.RequesterID, pv.ResponderID)
	if err != nil {
		t.Fatalf("ComputeConfirm(client): %v", err)
	}
	if clientConfirm != pv.ClientConfirm {
		t.Fatalf("clientConfirm = %q, want %q", clientConfirm, pv.ClientConfirm)
	}
	serverConfirm, err := ComputeConfirm(keys.ConfirmKey, ConfirmRoleServer, pv.SessionID, pv.RequesterID, pv.ResponderID)
	if err != nil {
		t.Fatalf("ComputeConfirm(server): %v", err)
	}
	if serverConfirm != pv.ServerConfirm {
		t.Fatalf("serverConfirm = %q, want %q", serverConfirm, pv.ServerConfirm)
	}

	if !VerifyConfirm(keys.ConfirmKey, ConfirmRoleClient, pv.SessionID, pv.RequesterID, pv.ResponderID, pv.ClientConfirm) {
		t.Fatal("expected VerifyConfirm(client) to pass")
	}
	if !VerifyConfirm(keys.ConfirmKey, ConfirmRoleServer, pv.SessionID, pv.RequesterID, pv.ResponderID, pv.ServerConfirm) {
		t.Fatal("expected VerifyConfirm(server) to pass")
	}
	// Cross-role and tampered MACs must fail.
	if VerifyConfirm(keys.ConfirmKey, ConfirmRoleClient, pv.SessionID, pv.RequesterID, pv.ResponderID, pv.ServerConfirm) {
		t.Fatal("expected server confirm MAC to fail client verification")
	}
	if VerifyConfirm(keys.ConfirmKey, ConfirmRoleClient, "other-session", pv.RequesterID, pv.ResponderID, pv.ClientConfirm) {
		t.Fatal("expected mismatched sessionId to fail verification")
	}
	if VerifyConfirm(keys.ConfirmKey, ConfirmRoleClient, pv.SessionID, pv.ResponderID, pv.RequesterID, pv.ClientConfirm) {
		t.Fatal("expected swapped peer IDs to fail verification")
	}
}

func TestDecodePairingPublicKeyRejectsInvalidKeys(t *testing.T) {
	if _, err := DecodePairingPublicKey("not-valid-base64!!!"); err == nil {
		t.Fatal("expected invalid base64 to fail")
	}
	if _, err := DecodePairingPublicKey(base64.StdEncoding.EncodeToString([]byte("bad-der"))); err == nil {
		t.Fatal("expected invalid DER to fail")
	}

	// Non-P-256 curve (P-384) must be rejected.
	p384Key, err := ecdsa.GenerateKey(elliptic.P384(), rand.Reader)
	if err != nil {
		t.Fatalf("generate P-384 key: %v", err)
	}
	p384Der, err := x509.MarshalPKIXPublicKey(&p384Key.PublicKey)
	if err != nil {
		t.Fatalf("marshal P-384 key: %v", err)
	}
	if _, err := DecodePairingPublicKey(base64.StdEncoding.EncodeToString(p384Der)); err == nil {
		t.Fatal("expected P-384 public key to be rejected")
	}
}

func TestPairSessionStatusJSONValidation(t *testing.T) {
	var ok PairStatusResponse
	if err := json.Unmarshal([]byte(`{"version":2,"sessionId":"s1","status":"approved"}`), &ok); err != nil {
		t.Fatalf("unmarshal valid status: %v", err)
	}
	if ok.Status != PairStatusApproved {
		t.Fatalf("status = %q, want approved", ok.Status)
	}

	var bad PairStatusResponse
	if err := json.Unmarshal([]byte(`{"version":2,"sessionId":"s1","status":"bogus"}`), &bad); err == nil {
		t.Fatal("expected invalid status to fail unmarshal")
	}
}
