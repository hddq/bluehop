package org.hddq.bluehop.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.hddq.bluehop.protocol.LocalDeviceIdentity
import org.hddq.bluehop.protocol.PROTOCOL_VERSION
import org.hddq.bluehop.protocol.PairRequest
import org.hddq.bluehop.protocol.PairResponseStatus
import org.hddq.bluehop.protocol.PairedPeer
import org.hddq.bluehop.protocol.PairingCrypto
import org.hddq.bluehop.protocol.ProtocolJson
import java.net.HttpURLConnection
import java.net.URI

class PairingClient(
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) {
    suspend fun pairWithHost(
        host: String,
        port: Int,
        pin: String,
        localIdentity: LocalDeviceIdentity,
    ): Result<PairedPeer> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedPin = PairingCrypto.normalizePin(pin)
            require(normalizedPin.length == 6) { "Enter the 6-digit pairing code" }

            val baseUrl = formatBaseUrl(host, port)
            val infoHttpResponse = get("$baseUrl/pair/info")
            if (infoHttpResponse.statusCode !in 200..299) {
                val errMsg = runCatching {
                    ProtocolJson.decodePairResponse(infoHttpResponse.body).message
                }.getOrNull()
                error(errMsg?.ifBlank { null } ?: "Host rejected pairing info (${infoHttpResponse.statusCode})")
            }

            val info = ProtocolJson.decodePairInfo(infoHttpResponse.body)
            require(info.version == PROTOCOL_VERSION) {
                "Unsupported protocol version ${info.version}"
            }
            require(info.responderId != localIdentity.deviceId) {
                "Cannot pair device with itself"
            }

            val responderPub = PairingCrypto.decodePublicKey(info.responderPublicKeyBase64)
            val myKeyPair = PairingCrypto.generateEphemeralKeyPair()
            val myPubBase64 = PairingCrypto.encodePublicKey(myKeyPair.public)
            val sharedSecret = PairingCrypto.computeSharedSecret(myKeyPair.private, responderPub)
            val derivedPsk = PairingCrypto.derivePairingPsk(
                sharedSecret = sharedSecret,
                pin = normalizedPin,
                sessionNonce = info.sessionNonce,
                firstId = localIdentity.deviceId,
                secondId = info.responderId,
            )

            val clientProof = PairingCrypto.computeClientProof(
                psk = derivedPsk,
                sessionNonce = info.sessionNonce,
                requesterId = localIdentity.deviceId,
                responderId = info.responderId,
                requesterPublicKeyBase64 = myPubBase64,
                responderPublicKeyBase64 = info.responderPublicKeyBase64,
            )

            val pairRequest = PairRequest(
                version = PROTOCOL_VERSION,
                requesterId = localIdentity.deviceId,
                requesterName = localIdentity.deviceName,
                requesterPublicKeyBase64 = myPubBase64,
                sessionNonce = info.sessionNonce,
                proof = clientProof,
            )

            val pairHttpResponse = postJson(
                url = "$baseUrl/pair",
                jsonBody = ProtocolJson.encodePairRequest(pairRequest),
            )

            val pairResponse = ProtocolJson.decodePairResponse(pairHttpResponse.body)
            if (pairHttpResponse.statusCode != 200 || pairResponse.status != PairResponseStatus.OK) {
                error(pairResponse.message.ifBlank { "Pairing rejected (${pairHttpResponse.statusCode})" })
            }

            val serverProofValid = PairingCrypto.verifyServerProof(
                psk = derivedPsk,
                sessionNonce = info.sessionNonce,
                requesterId = localIdentity.deviceId,
                responderId = info.responderId,
                requesterPublicKeyBase64 = myPubBase64,
                responderPublicKeyBase64 = info.responderPublicKeyBase64,
                proofHex = pairResponse.proof,
            )
            check(serverProofValid) { "Host proof verification failed" }

            PairedPeer(
                peerId = info.responderId,
                peerName = pairResponse.responderName.ifBlank { info.responderName },
                psk = derivedPsk,
                pairedAtEpochSec = nowEpochSeconds(),
            )
        }
    }

    private fun get(url: String): SimpleHttpResponse = execute(url = url, method = "GET", jsonBody = null)

    private fun postJson(url: String, jsonBody: String): SimpleHttpResponse =
        execute(url = url, method = "POST", jsonBody = jsonBody)

    private fun execute(
        url: String,
        method: String,
        jsonBody: String?,
    ): SimpleHttpResponse {
        val connection = (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
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

    internal fun formatBaseUrl(host: String, port: Int): String = Companion.formatBaseUrl(host, port)

    private data class SimpleHttpResponse(
        val statusCode: Int,
        val body: String,
    )

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 4_000
        const val DEFAULT_READ_TIMEOUT_MS = 5_000

        internal fun formatHostPort(host: String, port: Int): String {
            val bracketedHost = if (':' in host && !host.startsWith("[")) "[$host]" else host
            return "$bracketedHost:$port"
        }

        internal fun formatBaseUrl(host: String, port: Int): String =
            "http://${formatHostPort(host, port)}"
    }
}

data class ManualPairingTarget(
    val host: String,
    val port: Int,
)

internal fun parseManualPairingAddress(
    input: String,
    defaultPort: Int = HolderHttpServer.DEFAULT_PORT,
): Result<ManualPairingTarget> = runCatching {
    val trimmed = input.trim()
    require(trimmed.isNotEmpty()) { "Enter a host address" }

    val withoutScheme = when {
        trimmed.startsWith("http://", ignoreCase = true) -> trimmed.substring(7)
        "://" in trimmed -> error("Only http:// or host:port addresses are supported")
        else -> trimmed
    }.trimEnd('/')

    require(withoutScheme.isNotEmpty()) { "Enter a host address" }
    require(withoutScheme.none { it.isWhitespace() }) { "Host address must not contain spaces" }
    require('/' !in withoutScheme && '?' !in withoutScheme && '#' !in withoutScheme && '@' !in withoutScheme) {
        "Enter only host or host:port"
    }

    val (rawHost, port) = if (withoutScheme.startsWith("[")) {
        val closeBracket = withoutScheme.indexOf(']')
        require(closeBracket > 1) { "Invalid IPv6 address" }
        val hostPart = withoutScheme.substring(1, closeBracket)
        val remainder = withoutScheme.substring(closeBracket + 1)
        val parsedPort = when {
            remainder.isEmpty() -> defaultPort
            remainder.startsWith(":") -> parseManualPairingPort(remainder.substring(1))
            else -> error("Invalid host address")
        }
        hostPart to parsedPort
    } else {
        require('[' !in withoutScheme && ']' !in withoutScheme) { "Invalid brackets in host address" }
        val colonCount = withoutScheme.count { it == ':' }
        when (colonCount) {
            0 -> withoutScheme to defaultPort
            1 -> {
                val colonIndex = withoutScheme.indexOf(':')
                val hostPart = withoutScheme.substring(0, colonIndex)
                val portPart = withoutScheme.substring(colonIndex + 1)
                require(hostPart.isNotEmpty()) { "Enter a host before :port" }
                hostPart to parseManualPairingPort(portPart)
            }
            else -> withoutScheme to defaultPort
        }
    }

    val host = rawHost.trim()
    require(host.isNotEmpty()) { "Enter a host address" }
    val url = URI.create(PairingClient.formatBaseUrl(host, port)).toURL()
    require(url.host.isNotBlank()) { "Invalid host address" }

    ManualPairingTarget(host = host, port = port)
}

private fun parseManualPairingPort(rawPort: String): Int {
    require(rawPort.isNotEmpty() && rawPort.all { it.isDigit() }) {
        "Port must be a number between 1 and 65535"
    }
    val port = rawPort.toIntOrNull()
        ?: error("Port must be a number between 1 and 65535")
    require(port in 1..65535) { "Port must be between 1 and 65535" }
    return port
}
