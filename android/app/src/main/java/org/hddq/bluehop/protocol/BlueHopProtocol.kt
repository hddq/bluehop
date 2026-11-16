package org.hddq.bluehop.protocol

import java.security.MessageDigest
import java.security.SecureRandom
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
