package org.hddq.bluehop.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val PROTOCOL_VERSION = 1

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

object ProtocolJson {
    val codec = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encodeRequest(request: GrabRequest): String = codec.encodeToString(request)

    fun decodeRequest(json: String): GrabRequest = codec.decodeFromString(json)

    fun encodeResponse(response: GrabResponse): String = codec.encodeToString(response)

    fun decodeResponse(json: String): GrabResponse = codec.decodeFromString(json)
}
