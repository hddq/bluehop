package org.hddq.bluehop.protocol

import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val PROTOCOL_VERSION = 1
const val PAIRING_PROTOCOL_VERSION = 2

@Serializable
data class GrabRequest(
    val version: Int = PROTOCOL_VERSION,
    val deviceAddress: String,
    val requesterId: String,
    val timestampEpochSec: Long,
    val nonce: String,
    val signature: String = "",
) {
    fun canonicalPayload(): String =
        "v$PROTOCOL_VERSION\nrequest\n$version\n$deviceAddress\n$requesterId\n$timestampEpochSec\n$nonce"
}

@Serializable
enum class GrabResponseStatus(val wireValue: String) {
    @SerialName("ok")
    OK("ok"),

    @SerialName("rejected")
    REJECTED("rejected"),

    @SerialName("error")
    ERROR("error"),
}

@Serializable
data class GrabResponse(
    val version: Int = PROTOCOL_VERSION,
    val status: GrabResponseStatus,
    val deviceAddress: String,
    val timestampEpochSec: Long,
    val nonce: String,
    val message: String = "",
    val signature: String = "",
) {
    fun canonicalPayload(): String =
        "v$PROTOCOL_VERSION\nresponse\n$version\n${status.wireValue}\n$deviceAddress\n$timestampEpochSec\n$nonce\n$message"
}

enum class VerificationFailureReason {
    UNSUPPORTED_VERSION,
    INVALID_FORMAT,
    INVALID_SIGNATURE,
    TIMESTAMP_OUT_OF_WINDOW,
    REPLAYED_NONCE,
    NONCE_MISMATCH,
}

sealed interface VerificationResult {
    data object Valid : VerificationResult

    data class Invalid(val reason: VerificationFailureReason) : VerificationResult
}

@Serializable
enum class PairSessionStatus(val wireValue: String) {
    @SerialName("pending")
    PENDING("pending"),

    @SerialName("approved")
    APPROVED("approved"),

    @SerialName("rejected")
    REJECTED("rejected"),

    @SerialName("expired")
    EXPIRED("expired"),
}

@Serializable
data class PairStartRequest(
    @Required val version: Int = PAIRING_PROTOCOL_VERSION,
    val requesterId: String,
    val requesterName: String,
    val clientPub: String,
)

@Serializable
data class PairStartResponse(
    @Required val version: Int = PAIRING_PROTOCOL_VERSION,
    val sessionId: String,
    val responderId: String,
    val responderName: String,
    val serverPub: String,
)

@Serializable
data class PairStatusResponse(
    @Required val version: Int = PAIRING_PROTOCOL_VERSION,
    val sessionId: String,
    val status: PairSessionStatus,
)

@Serializable
data class PairConfirmRequest(
    @Required val version: Int = PAIRING_PROTOCOL_VERSION,
    val sessionId: String,
    val clientConfirm: String,
)

@Serializable
data class PairConfirmResponse(
    @Required val version: Int = PAIRING_PROTOCOL_VERSION,
    val sessionId: String,
    val serverConfirm: String,
)

@Serializable
data class PairErrorResponse(
    val version: Int = PAIRING_PROTOCOL_VERSION,
    val message: String,
)

object ProtocolJson {
    val codec = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encodeRequest(request: GrabRequest): String = codec.encodeToString(request)

    fun decodeRequest(json: String): GrabRequest = codec.decodeFromString(json)

    fun encodeResponse(response: GrabResponse): String = codec.encodeToString(response)

    fun decodeResponse(json: String): GrabResponse = codec.decodeFromString(json)

    fun encodePairStartRequest(request: PairStartRequest): String = codec.encodeToString(request)

    fun decodePairStartRequest(json: String): PairStartRequest = codec.decodeFromString(json)

    fun encodePairStartResponse(response: PairStartResponse): String = codec.encodeToString(response)

    fun decodePairStartResponse(json: String): PairStartResponse = codec.decodeFromString(json)

    fun encodePairStatusResponse(response: PairStatusResponse): String = codec.encodeToString(response)

    fun decodePairStatusResponse(json: String): PairStatusResponse = codec.decodeFromString(json)

    fun encodePairConfirmRequest(request: PairConfirmRequest): String = codec.encodeToString(request)

    fun decodePairConfirmRequest(json: String): PairConfirmRequest = codec.decodeFromString(json)

    fun encodePairConfirmResponse(response: PairConfirmResponse): String = codec.encodeToString(response)

    fun decodePairConfirmResponse(json: String): PairConfirmResponse = codec.decodeFromString(json)

    fun encodePairErrorResponse(response: PairErrorResponse): String = codec.encodeToString(response)

    fun decodePairErrorResponse(json: String): PairErrorResponse = codec.decodeFromString(json)
}
