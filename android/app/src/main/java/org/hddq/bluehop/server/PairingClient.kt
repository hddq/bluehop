package org.hddq.bluehop.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.hddq.bluehop.protocol.LocalDeviceIdentity
import org.hddq.bluehop.protocol.PROTOCOL_VERSION
import org.hddq.bluehop.protocol.PairRequest
import org.hddq.bluehop.protocol.PairResponseStatus
import org.hddq.bluehop.protocol.PairedPeer
import org.hddq.bluehop.protocol.PairingCrypto
import org.hddq.bluehop.protocol.ProtocolJson
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.TimeUnit

class PairingClient(
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val totalTimeoutMs: Int = DEFAULT_TOTAL_TIMEOUT_MS,
) {
    suspend fun pairWithHost(
        host: String,
        port: Int,
        pin: String,
        localIdentity: LocalDeviceIdentity,
    ): Result<PairedPeer> = withContext(Dispatchers.IO) {
        try {
            require(totalTimeoutMs > 0) { "Total timeout must be positive" }
            val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(totalTimeoutMs.toLong())

            val normalizedPin = PairingCrypto.normalizePin(pin)
            require(normalizedPin.length == 6) { "Enter the 6-digit pairing code" }

            val baseUrl = formatBaseUrl(host, port)
            val infoHttpResponse = get("$baseUrl/pair/info", deadlineNanos)
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
                deadlineNanos = deadlineNanos,
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

            Result.success(
                PairedPeer(
                    peerId = info.responderId,
                    peerName = pairResponse.responderName.ifBlank { info.responderName },
                    psk = derivedPsk,
                    pairedAtEpochSec = nowEpochSeconds(),
                ),
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private suspend fun get(url: String, deadlineNanos: Long): SimpleHttpResponse =
        execute(url = url, method = "GET", jsonBody = null, deadlineNanos = deadlineNanos)

    private suspend fun postJson(url: String, jsonBody: String, deadlineNanos: Long): SimpleHttpResponse =
        execute(url = url, method = "POST", jsonBody = jsonBody, deadlineNanos = deadlineNanos)

    private suspend fun execute(
        url: String,
        method: String,
        jsonBody: String?,
        deadlineNanos: Long,
    ): SimpleHttpResponse = coroutineScope {
        val initialRemainingMs = remainingMillis(deadlineNanos)
        val connection = (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = minOf(connectTimeoutMs, initialRemainingMs)
            readTimeout = minOf(readTimeoutMs, initialRemainingMs)
            useCaches = false
            setRequestProperty("Accept", "application/json")
            if (jsonBody != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        val cancelHandle = coroutineContext.job.invokeOnCompletion { cause ->
            if (cause != null) {
                runCatching { connection.disconnect() }
            }
        }
        val watchdog = launch {
            delay(initialRemainingMs.toLong())
            runCatching { connection.disconnect() }
        }
        return@coroutineScope try {
            if (jsonBody != null) {
                connection.outputStream.use { stream ->
                    stream.write(jsonBody.toByteArray(Charsets.UTF_8))
                }
            }
            val code = connection.responseCode
            val stream = if (code in 200..399) connection.inputStream else connection.errorStream
            val body = stream?.use { readBoundedUtf8(it, deadlineNanos = deadlineNanos) }.orEmpty()
            SimpleHttpResponse(statusCode = code, body = body)
        } catch (error: IOException) {
            if (System.nanoTime() >= deadlineNanos) {
                throw SocketTimeoutException("Pairing request timed out").apply { initCause(error) }
            }
            throw error
        } finally {
            watchdog.cancel()
            cancelHandle.dispose()
            runCatching { connection.disconnect() }
        }
    }

    private fun remainingMillis(deadlineNanos: Long): Int {
        val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
        if (remainingMs <= 0L) {
            throw SocketTimeoutException("Pairing request timed out")
        }
        return remainingMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun readBoundedUtf8(
        stream: InputStream,
        deadlineNanos: Long,
        maxBytes: Int = MAX_RESPONSE_BYTES,
    ): String {
        val buffer = ByteArray(4096)
        val output = ByteArrayOutputStream()
        while (true) {
            if (System.nanoTime() >= deadlineNanos) {
                throw SocketTimeoutException("Pairing request timed out")
            }
            val read = stream.read(buffer)
            if (read == -1) break
            if (System.nanoTime() >= deadlineNanos) {
                throw SocketTimeoutException("Pairing request timed out")
            }
            if (output.size() + read > maxBytes) {
                error("Pairing response exceeded $maxBytes bytes")
            }
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8)
    }

    internal fun formatBaseUrl(host: String, port: Int): String = Companion.formatBaseUrl(host, port)

    private data class SimpleHttpResponse(
        val statusCode: Int,
        val body: String,
    )

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 4_000
        const val DEFAULT_READ_TIMEOUT_MS = 5_000
        const val DEFAULT_TOTAL_TIMEOUT_MS = 15_000
        const val MAX_RESPONSE_BYTES = 64 * 1024

        internal fun formatHostPort(host: String, port: Int): String {
            val trimmed = host.trim()
            val bracketedHost = if (':' in trimmed && !trimmed.startsWith("[")) "[$trimmed]" else trimmed
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
): Result<ManualPairingTarget> = runCatching {
    val trimmed = input.trim()
    require(trimmed.isNotEmpty()) { "Enter host:port (for example 192.168.1.10:43215)" }

    val withoutScheme = when {
        trimmed.startsWith("http://", ignoreCase = true) -> trimmed.substring(7)
        "://" in trimmed -> error("Only http:// or host:port addresses are supported")
        else -> trimmed
    }.trimEnd('/')

    require(withoutScheme.isNotEmpty()) { "Enter host:port (for example 192.168.1.10:43215)" }
    require(withoutScheme.none { it.isWhitespace() }) { "Host address must not contain spaces" }
    require('/' !in withoutScheme && '?' !in withoutScheme && '#' !in withoutScheme && '@' !in withoutScheme) {
        "Enter only host:port"
    }

    val (rawHost, port) = if (withoutScheme.startsWith("[")) {
        val closeBracket = withoutScheme.indexOf(']')
        require(closeBracket > 1) { "Invalid IPv6 address" }
        val hostPart = withoutScheme.substring(1, closeBracket)
        val remainder = withoutScheme.substring(closeBracket + 1)
        require(remainder.startsWith(":")) {
            "Include :port (for example [::1]:43215)"
        }
        hostPart to parseManualPairingPort(remainder.substring(1))
    } else {
        require('[' !in withoutScheme && ']' !in withoutScheme) { "Invalid brackets in host address" }
        val colonCount = withoutScheme.count { it == ':' }
        when (colonCount) {
            0 -> error("Include :port (for example 192.168.1.10:43215)")
            1 -> {
                val colonIndex = withoutScheme.indexOf(':')
                val hostPart = withoutScheme.substring(0, colonIndex)
                val portPart = withoutScheme.substring(colonIndex + 1)
                require(hostPart.isNotEmpty()) { "Enter a host before :port" }
                hostPart to parseManualPairingPort(portPart)
            }
            else -> error("Use [host]:port for IPv6 addresses")
        }
    }

    val host = rawHost.trim()
    require(host.isNotEmpty()) { "Enter host:port (for example 192.168.1.10:43215)" }
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

internal fun isUniqueLocalIpv6(address: Inet6Address): Boolean {
    val bytes = address.address
    return bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC
}

internal fun isGlobalUnicastIpv6(address: Inet6Address): Boolean {
    val bytes = address.address
    if (bytes.size != 16) return false
    if ((bytes[0].toInt() and 0xE0) != 0x20) return false
    val isTeredo = bytes[0].toInt() == 0x20 &&
        bytes[1].toInt() == 0x01 &&
        bytes[2].toInt() == 0x00 &&
        bytes[3].toInt() == 0x00
    return !isTeredo
}

internal fun isUsableLocalIpAddress(address: InetAddress): Boolean {
    if (address.isLoopbackAddress ||
        address.isAnyLocalAddress ||
        address.isLinkLocalAddress ||
        address.isMulticastAddress
    ) {
        return false
    }
    return when (address) {
        is Inet4Address -> true
        is Inet6Address -> {
            if (address.isSiteLocalAddress || address.scopeId != 0 || address.scopedInterface != null) {
                return false
            }
            val host = address.hostAddress ?: return false
            if ('%' in host) return false
            isUniqueLocalIpv6(address) || isGlobalUnicastIpv6(address)
        }
        else -> false
    }
}

internal fun filterAndSortLocalIpAddresses(
    addresses: Iterable<InetAddress>,
    excludedAddresses: Set<InetAddress> = emptySet(),
): List<String> = addresses
    .filter { it !in excludedAddresses && isUsableLocalIpAddress(it) }
    .sortedBy { address ->
        when {
            address is Inet4Address -> 0
            address is Inet6Address && isUniqueLocalIpv6(address) -> 1
            else -> 2
        }
    }
    .mapNotNull { it.hostAddress }
    .distinct()
