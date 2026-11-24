# BlueHop Wire Protocol

BlueHop uses two versioned HTTP/JSON protocols over local Wi-Fi (advertised via mDNS `_bluehop._tcp.` on UDP port `5353`):

- **Pairing Protocol v2 (`PAIRING_PROTOCOL_VERSION = 2`)**: ephemeral ECDH (P-256) + 6-digit Short Authentication String (SAS) + mutual HMAC key confirmation. Listens on TCP port **`8766`** by default (falling back to an ephemeral port if unavailable; the bound port is advertised via mDNS).
- **Release Protocol v1 (`PROTOCOL_VERSION = 1`)**: authenticated `/status` and `/release` requests signed with the paired 32-byte per-peer PSK (`pskHex`). Listens on TCP port **`8765`** by default (falling back to an ephemeral port if unavailable; the bound port is advertised via mDNS).

Deterministic cross-platform test vectors for both protocols live in [`test-vectors.json`](test-vectors.json).

---

## Pairing Protocol (v2)

Pairing establishes a 32-byte per-peer pre-shared key (`psk`) without sending any PIN or secret-derived proof before out-of-band human verification.

### Sequence Diagram

```mermaid
sequenceDiagram
    participant C as Client (Initiator)
    participant S as Server (Responder, 60s TTL)
    participant U as User (Both Screens)

    C->>C: Generate ephemeral P-256 keypair (cPriv, clientPub)
    C->>S: POST /pair/start {version: 2, requesterId, requesterName, clientPub}
    S->>S: Validate P-256 SPKI, generate (sPriv, serverPub) & sessionId
    S->>S: Derive shared, psk, confirmKey, 6-digit sas
    S-->>C: 200 OK {version: 2, sessionId, responderId, responderName, serverPub}
    C->>C: Derive shared, psk, confirmKey, 6-digit sas
    Note over C,S: Both displays show the 6-digit SAS
    U->>S: Compare SAS & click Accept (or Reject)
    loop Poll every ~500ms
        C->>S: GET /pair/status?sessionId=...
        S-->>C: 200 OK {version: 2, sessionId, status: pending|approved|rejected|expired}
    end
    Note over C,S: Only after status == "approved"
    C->>C: clientConfirm = HMAC-SHA256(confirmKey, "bluehop-confirm-v2\nclient\n...")
    C->>S: POST /pair/confirm {version: 2, sessionId, clientConfirm}
    S->>S: Constant-time verify clientConfirm, persist peer + pskHex
    S->>S: serverConfirm = HMAC-SHA256(confirmKey, "bluehop-confirm-v2\nserver\n...")
    S-->>C: 200 OK {version: 2, sessionId, serverConfirm}
    C->>C: Constant-time verify serverConfirm, persist peer + pskHex
```

### Public Key Encoding

Ephemeral P-256 (`secp256r1` / `prime256v1`) public keys are encoded as **standard padded base64** of X.509 `SubjectPublicKeyInfo` DER:

- Go: `x509.MarshalPKIXPublicKey(pub)` / `x509.ParsePKIXPublicKey(der)`
- Kotlin / JCA: `publicKey.encoded` / `KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))`

Both sides must reject non-P-256 curves.

### Key & SAS Derivation

1. Compute the 32-byte ECDH shared secret (`ECDH_P256(priv, peerPub)`, X coordinate).
2. Build the transcript string:
   ```text
   bluehop-pair-v2\n<sessionId>\n<clientPub>\n<serverPub>
   ```
3. Compute `salt = SHA-256(transcript_utf8)`.
4. Derive keys via HKDF-SHA256 (`ikm = sharedSecret`, `salt = salt`):
   - `psk` (32 bytes, info `"bluehop-pair-v2 psk"`) -> stored as lowercase hex `pskHex`
   - `confirmKey` (32 bytes, info `"bluehop-pair-v2 confirm"`)
   - `sasBytes` (4 bytes, info `"bluehop-pair-v2 sas"`)
5. Compute the 6-digit SAS:
   ```text
   sas = zeroPad6(uint32_be(sasBytes) % 1_000_000)
   ```

### Mutual Key Confirmation

After `GET /pair/status?sessionId=...` returns `"approved"`, both sides compute and verify lowercase hex `HMAC-SHA256` tags using `confirmKey` over:

```text
bluehop-confirm-v2\n<role>\n<sessionId>\n<requesterId>\n<responderId>
```

where `<role>` is `"client"` for `clientConfirm` and `"server"` for `serverConfirm`. Both verifications use constant-time equality (`hmac.Equal` in Go, `MessageDigest.isEqual` in Java/Kotlin). Neither device persists the new peer or `pskHex` until key confirmation succeeds.

### Endpoints & Lifecycle

- `POST /pair/start` -> `200 OK` (`PairStartResponse`), `400` on invalid input/key, `409` if a session is already active or completed. Pending sessions expire after **60 seconds**.
- `GET /pair/status?sessionId=...` -> `200 OK` (`PairStatusResponse` with `status` in `pending`, `approved`, `rejected`, `expired`), `404` if unknown.
- `POST /pair/confirm` -> `200 OK` (`PairConfirmResponse`), `401` on invalid `clientConfirm`, `403` if rejected, `409` if not yet approved or already completed, `410` if expired.

---

## Release Protocol (v1)

Post-pairing endpoints remain unchanged at `PROTOCOL_VERSION = 1`:

- `POST /status` and `POST /release` accept `GrabRequest` signed with lowercase hex `HMAC-SHA256(psk, canonicalPayload)` and return `GrabResponse` signed with the same `psk`.
- Canonical request payload:
  ```text
  v1\nrequest\n<version>\n<deviceAddress>\n<requesterId>\n<timestampEpochSec>\n<nonce>
  ```
- Canonical response payload:
  ```text
  v1\nresponse\n<version>\n<status>\n<deviceAddress>\n<timestampEpochSec>\n<nonce>\n<message>
  ```
- Receivers enforce a `±30s` timestamp skew window and a 60s nonce replay guard.
