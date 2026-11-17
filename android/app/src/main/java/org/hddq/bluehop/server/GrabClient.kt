package org.hddq.bluehop.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import org.hddq.bluehop.protocol.GrabRequest
import org.hddq.bluehop.protocol.GrabResponse
import org.hddq.bluehop.protocol.GrabResponseStatus
import org.hddq.bluehop.protocol.HmacSigner
import org.hddq.bluehop.protocol.ProtocolJson
import org.hddq.bluehop.protocol.ProtocolVerifier
import org.hddq.bluehop.protocol.VerificationResult
import java.net.HttpURLConnection
import java.net.URI

sealed interface ReleaseOutcome {
    data class Released(val response: GrabResponse) : ReleaseOutcome

    data class AlreadyDisconnected(val message: String) : ReleaseOutcome

    data class Rejected(val message: String) : ReleaseOutcome

    data class Failed(val message: String) : ReleaseOutcome
}

interface HolderClient {
    suspend fun fetchStatus(host: String, port: Int): Result<HolderStatusResponse>

    suspend fun requestRelease(
        host: String,
        port: Int,
        deviceAddress: String,
        requesterId: String,
        psk: ByteArray,
    ): ReleaseOutcome
}

class GrabClient(
    private val verifier: ProtocolVerifier = ProtocolVerifier(),
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val statusReadTimeoutMs: Int = DEFAULT_STATUS_READ_TIMEOUT_MS,
    private val releaseReadTimeoutMs: Int = DEFAULT_RELEASE_READ_TIMEOUT_MS,
) : HolderClient {

    override suspend fun fetchStatus(
        host: String,
        port: Int,
    ): Result<HolderStatusResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = formatBaseUrl(host, port)
            val response = execute(
                url = "$baseUrl/status",
                method = "GET",
                jsonBody = null,
                readTimeoutMs = statusReadTimeoutMs,
            )
            if (response.statusCode !in 200..299) {
                error("Status check failed (${response.statusCode})")
            }
            ProtocolJson.codec.decodeFromString<HolderStatusResponse>(response.body)
        }
    }

    override suspend fun requestRelease(
        host: String,
        port: Int,
        deviceAddress: String,
        requesterId: String,
        psk: ByteArray,
    ): ReleaseOutcome = withContext(Dispatchers.IO) {
        val baseUrl = formatBaseUrl(host, port)
        val signedRequest = HmacSigner.signRequest(
            psk = psk,
            request = GrabRequest(
                deviceAddress = deviceAddress,
                requesterId = requesterId,
                timestampEpochSec = nowEpochSeconds(),
                nonce = HmacSigner.generateNonce(),
            ),
        )

        val httpResponse = try {
            execute(
                url = "$baseUrl/release",
                method = "POST",
                jsonBody = ProtocolJson.encodeRequest(signedRequest),
                readTimeoutMs = releaseReadTimeoutMs,
            )
        } catch (error: Exception) {
            return@withContext ReleaseOutcome.Failed(
                error.message?.takeIf { it.isNotBlank() } ?: "Could not reach holder",
            )
        }

        val grabResponse = try {
            ProtocolJson.decodeResponse(httpResponse.body)
        } catch (_: SerializationException) {
            return@withContext ReleaseOutcome.Failed(
                "Malformed holder response (${httpResponse.statusCode})",
            )
        } catch (_: IllegalArgumentException) {
            return@withContext ReleaseOutcome.Failed(
                "Invalid holder response (${httpResponse.statusCode})",
            )
        }

        if (httpResponse.statusCode == HttpURLConnection.HTTP_CONFLICT &&
            grabResponse.status == GrabResponseStatus.REJECTED
        ) {
            return@withContext ReleaseOutcome.AlreadyDisconnected(
                grabResponse.message.ifBlank { "Device is not connected on holder" },
            )
        }

        if (httpResponse.statusCode !in 200..299) {
            val msg = grabResponse.message.ifBlank {
                "Release rejected (${httpResponse.statusCode})"
            }
            return@withContext if (grabResponse.status == GrabResponseStatus.REJECTED) {
                ReleaseOutcome.Rejected(msg)
            } else {
                ReleaseOutcome.Failed(msg)
            }
        }

        when (val verification = verifier.verifyResponse(grabResponse, psk, signedRequest.nonce)) {
            VerificationResult.Valid -> Unit
            is VerificationResult.Invalid -> {
                return@withContext ReleaseOutcome.Failed(
                    "Unverified holder response (${verification.reason.name})",
                )
            }
        }

        if (!grabResponse.deviceAddress.equals(deviceAddress, ignoreCase = true)) {
            return@withContext ReleaseOutcome.Failed(
                "Holder released unexpected device (${grabResponse.deviceAddress})",
            )
        }

        if (grabResponse.status != GrabResponseStatus.OK) {
            val msg = grabResponse.message.ifBlank { "Release failed" }
            return@withContext if (grabResponse.status == GrabResponseStatus.REJECTED) {
                ReleaseOutcome.Rejected(msg)
            } else {
                ReleaseOutcome.Failed(msg)
            }
        }

        ReleaseOutcome.Released(grabResponse)
    }

    private fun execute(
        url: String,
        method: String,
        jsonBody: String?,
        readTimeoutMs: Int,
    ): SimpleHttpResponse {
        val connection = (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            this.readTimeout = readTimeoutMs
            useCaches = false
            setRequestProperty("Accept", "application/json")
            if (jsonBody != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        return try {
            if (jsonBody != null) {
                connection.outputStream.use { stream ->
                    stream.write(jsonBody.toByteArray(Charsets.UTF_8))
                }
            }
            val code = connection.responseCode
            val stream = if (code in 200..399) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            SimpleHttpResponse(statusCode = code, body = body)
        } finally {
            connection.disconnect()
        }
    }

    internal fun formatBaseUrl(host: String, port: Int): String {
        val bracketedHost = if (':' in host && !host.startsWith("[")) "[$host]" else host
        return "http://$bracketedHost:$port"
    }

    private data class SimpleHttpResponse(
        val statusCode: Int,
        val body: String,
    )

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 4_000
        const val DEFAULT_STATUS_READ_TIMEOUT_MS = 3_000
        const val DEFAULT_RELEASE_READ_TIMEOUT_MS = 15_000
    }
}
