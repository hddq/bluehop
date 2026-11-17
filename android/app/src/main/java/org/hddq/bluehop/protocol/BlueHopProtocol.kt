package org.hddq.bluehop.protocol

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

object HmacSigner {
    private const val ALGORITHM = "HmacSHA256"
    private const val EXPECTED_SIGNATURE_HEX_LENGTH = 64
    private const val HEX_DIGITS = "0123456789abcdef"

    fun sign(psk: ByteArray, payload: String): String {
        require(psk.isNotEmpty()) { "PSK must not be empty" }
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(psk, ALGORITHM))
        return toHex(mac.doFinal(payload.toByteArray(Charsets.UTF_8)))
    }

    fun verify(psk: ByteArray, payload: String, signatureHex: String): Boolean {
        if (psk.isEmpty() || signatureHex.length != EXPECTED_SIGNATURE_HEX_LENGTH) {
            return false
        }
        val providedBytes = fromHexOrNull(signatureHex) ?: return false
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(psk, ALGORITHM))
        val expectedBytes = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        return MessageDigest.isEqual(expectedBytes, providedBytes)
    }

    fun signRequest(psk: ByteArray, request: GrabRequest): GrabRequest =
        request.copy(signature = sign(psk, request.canonicalPayload()))

    fun signResponse(psk: ByteArray, response: GrabResponse): GrabResponse =
        response.copy(signature = sign(psk, response.canonicalPayload()))

    fun generateNonce(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return toHex(bytes)
    }

    fun toHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX_DIGITS[v ushr 4]
            out[i * 2 + 1] = HEX_DIGITS[v and 0x0F]
        }
        return String(out)
    }

    fun fromHexOrNull(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = hex[i * 2].digitToIntOrNull(16) ?: return null
            val lo = hex[i * 2 + 1].digitToIntOrNull(16) ?: return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}

class NonceReplayGuard(
    private val retentionSeconds: Long = DEFAULT_RETENTION_SECONDS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val seenNonces = LinkedHashMap<String, Long>()

    @Synchronized
    fun checkAndRecord(nonce: String, timestampEpochSec: Long, nowEpochSec: Long): Boolean {
        pruneExpired(nowEpochSec)
        if (seenNonces.containsKey(nonce)) {
            return false
        }
        if (seenNonces.size >= maxEntries) {
            val oldestKey = seenNonces.entries.firstOrNull()?.key
            if (oldestKey != null) {
                seenNonces.remove(oldestKey)
            }
        }
        seenNonces[nonce] = timestampEpochSec
        return true
    }

    @Synchronized
    fun size(): Int = seenNonces.size

    private fun pruneExpired(nowEpochSec: Long) {
        val cutoff = nowEpochSec - retentionSeconds
        val iterator = seenNonces.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value < cutoff) {
                iterator.remove()
            }
        }
    }

    companion object {
        const val DEFAULT_RETENTION_SECONDS = 60L
        const val DEFAULT_MAX_ENTRIES = 256
    }
}

class ProtocolVerifier(
    private val maxSkewSeconds: Long = DEFAULT_MAX_SKEW_SECONDS,
    private val replayGuard: NonceReplayGuard = NonceReplayGuard(
        retentionSeconds = maxSkewSeconds * 2,
    ),
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
) {
    fun verifyRequest(request: GrabRequest, psk: ByteArray): VerificationResult {
        if (request.version != PROTOCOL_VERSION) {
            return VerificationResult.Invalid(VerificationFailureReason.UNSUPPORTED_VERSION)
        }
        if (request.deviceAddress.isBlank() ||
            request.requesterId.isBlank() ||
            request.nonce.isBlank()
        ) {
            return VerificationResult.Invalid(VerificationFailureReason.INVALID_FORMAT)
        }
        if (!HmacSigner.verify(psk, request.canonicalPayload(), request.signature)) {
            return VerificationResult.Invalid(VerificationFailureReason.INVALID_SIGNATURE)
        }
        val now = nowEpochSeconds()
        if (abs(now - request.timestampEpochSec) > maxSkewSeconds) {
            return VerificationResult.Invalid(VerificationFailureReason.TIMESTAMP_OUT_OF_WINDOW)
        }
        if (!replayGuard.checkAndRecord(request.nonce, request.timestampEpochSec, now)) {
            return VerificationResult.Invalid(VerificationFailureReason.REPLAYED_NONCE)
        }
        return VerificationResult.Valid
    }

    fun verifyResponse(
        response: GrabResponse,
        psk: ByteArray,
        expectedNonce: String? = null,
    ): VerificationResult {
        if (response.version != PROTOCOL_VERSION) {
            return VerificationResult.Invalid(VerificationFailureReason.UNSUPPORTED_VERSION)
        }
        if (response.deviceAddress.isBlank() || response.nonce.isBlank()) {
            return VerificationResult.Invalid(VerificationFailureReason.INVALID_FORMAT)
        }
        if (!HmacSigner.verify(psk, response.canonicalPayload(), response.signature)) {
            return VerificationResult.Invalid(VerificationFailureReason.INVALID_SIGNATURE)
        }
        val now = nowEpochSeconds()
        if (abs(now - response.timestampEpochSec) > maxSkewSeconds) {
            return VerificationResult.Invalid(VerificationFailureReason.TIMESTAMP_OUT_OF_WINDOW)
        }
        if (expectedNonce != null && response.nonce != expectedNonce) {
            return VerificationResult.Invalid(VerificationFailureReason.NONCE_MISMATCH)
        }
        return VerificationResult.Valid
    }

    companion object {
        const val DEFAULT_MAX_SKEW_SECONDS = 30L
    }
}

object PairingCrypto {
    private const val EC_ALGORITHM = "EC"
    private const val EC_CURVE = "secp256r1"
    private const val ECDH_ALGORITHM = "ECDH"
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val PIN_DIGITS = 6
    private const val PIN_BOUND = 1_000_000
    private const val PSK_LENGTH = 32

    fun generatePin(random: SecureRandom = SecureRandom()): String =
        random.nextInt(PIN_BOUND).toString().padStart(PIN_DIGITS, '0')

    fun normalizePin(pin: String): String =
        pin.filter { it.isDigit() }

    fun generateEphemeralKeyPair(random: SecureRandom = SecureRandom()): KeyPair {
        val generator = KeyPairGenerator.getInstance(EC_ALGORITHM)
        generator.initialize(ECGenParameterSpec(EC_CURVE), random)
        return generator.generateKeyPair()
    }

    fun encodePublicKey(publicKey: PublicKey): String =
        Base64.getEncoder().encodeToString(publicKey.encoded)

    fun decodePublicKey(base64: String): PublicKey {
        val bytes = Base64.getDecoder().decode(base64)
        return KeyFactory.getInstance(EC_ALGORITHM).generatePublic(X509EncodedKeySpec(bytes))
    }

    fun computeSharedSecret(privateKey: PrivateKey, peerPublicKey: PublicKey): ByteArray {
        val agreement = KeyAgreement.getInstance(ECDH_ALGORITHM)
        agreement.init(privateKey)
        agreement.doPhase(peerPublicKey, true)
        return agreement.generateSecret()
    }

    fun derivePairingPsk(
        sharedSecret: ByteArray,
        pin: String,
        sessionNonce: String,
        firstId: String,
        secondId: String,
    ): ByteArray {
        val normalizedPin = normalizePin(pin)
        require(normalizedPin.length == PIN_DIGITS) { "PIN must be $PIN_DIGITS digits" }
        val (idLow, idHigh) = if (firstId <= secondId) firstId to secondId else secondId to firstId
        val salt = MessageDigest.getInstance("SHA-256").digest(
            "bluehop-pin-v1\n$normalizedPin\n$sessionNonce".toByteArray(Charsets.UTF_8),
        )
        val info = "bluehop-pair-v1\n$idLow\n$idHigh\n$sessionNonce".toByteArray(Charsets.UTF_8)
        return hkdfSha256(ikm = sharedSecret, salt = salt, info = info, length = PSK_LENGTH)
    }

    fun clientProofPayload(
        sessionNonce: String,
        requesterId: String,
        responderId: String,
        requesterPublicKeyBase64: String,
        responderPublicKeyBase64: String,
    ): String =
        "pair-client-v1\n$sessionNonce\n$requesterId\n$responderId\n$requesterPublicKeyBase64\n$responderPublicKeyBase64"

    fun serverProofPayload(
        sessionNonce: String,
        requesterId: String,
        responderId: String,
        requesterPublicKeyBase64: String,
        responderPublicKeyBase64: String,
    ): String =
        "pair-server-v1\n$sessionNonce\n$requesterId\n$responderId\n$requesterPublicKeyBase64\n$responderPublicKeyBase64"

    fun computeClientProof(
        psk: ByteArray,
        sessionNonce: String,
        requesterId: String,
        responderId: String,
        requesterPublicKeyBase64: String,
        responderPublicKeyBase64: String,
    ): String = HmacSigner.sign(
        psk = psk,
        payload = clientProofPayload(
            sessionNonce = sessionNonce,
            requesterId = requesterId,
            responderId = responderId,
            requesterPublicKeyBase64 = requesterPublicKeyBase64,
            responderPublicKeyBase64 = responderPublicKeyBase64,
        ),
    )

    fun verifyClientProof(
        psk: ByteArray,
        sessionNonce: String,
        requesterId: String,
        responderId: String,
        requesterPublicKeyBase64: String,
        responderPublicKeyBase64: String,
        proofHex: String,
    ): Boolean = HmacSigner.verify(
        psk = psk,
        payload = clientProofPayload(
            sessionNonce = sessionNonce,
            requesterId = requesterId,
            responderId = responderId,
            requesterPublicKeyBase64 = requesterPublicKeyBase64,
            responderPublicKeyBase64 = responderPublicKeyBase64,
        ),
        signatureHex = proofHex,
    )

    fun computeServerProof(
        psk: ByteArray,
        sessionNonce: String,
        requesterId: String,
        responderId: String,
        requesterPublicKeyBase64: String,
        responderPublicKeyBase64: String,
    ): String = HmacSigner.sign(
        psk = psk,
        payload = serverProofPayload(
            sessionNonce = sessionNonce,
            requesterId = requesterId,
            responderId = responderId,
            requesterPublicKeyBase64 = requesterPublicKeyBase64,
            responderPublicKeyBase64 = responderPublicKeyBase64,
        ),
    )

    fun verifyServerProof(
        psk: ByteArray,
        sessionNonce: String,
        requesterId: String,
        responderId: String,
        requesterPublicKeyBase64: String,
        responderPublicKeyBase64: String,
        proofHex: String,
    ): Boolean = HmacSigner.verify(
        psk = psk,
        payload = serverProofPayload(
            sessionNonce = sessionNonce,
            requesterId = requesterId,
            responderId = responderId,
            requesterPublicKeyBase64 = requesterPublicKeyBase64,
            responderPublicKeyBase64 = responderPublicKeyBase64,
        ),
        signatureHex = proofHex,
    )

    internal fun hkdfSha256(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        val prkMac = Mac.getInstance(HMAC_ALGORITHM)
        prkMac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, HMAC_ALGORITHM))
        val prk = prkMac.doFinal(ikm)

        val expandMac = Mac.getInstance(HMAC_ALGORITHM)
        expandMac.init(SecretKeySpec(prk, HMAC_ALGORITHM))
        val out = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            expandMac.reset()
            expandMac.update(t)
            expandMac.update(info)
            expandMac.update(counter.toByte())
            t = expandMac.doFinal()
            val toCopy = minOf(t.size, length - offset)
            System.arraycopy(t, 0, out, offset, toCopy)
            offset += toCopy
            counter++
        }
        return out
    }
}
