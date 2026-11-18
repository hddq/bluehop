package org.hddq.bluehop.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.hddq.bluehop.bluetooth.BluetoothController
import org.hddq.bluehop.bluetooth.DeviceState
import org.hddq.bluehop.bluetooth.SelectedBluetoothDevice
import org.hddq.bluehop.protocol.GrabRequest
import org.hddq.bluehop.protocol.GrabResponse
import org.hddq.bluehop.protocol.GrabResponseStatus
import org.hddq.bluehop.protocol.HmacSigner
import org.hddq.bluehop.protocol.PROTOCOL_VERSION
import org.hddq.bluehop.protocol.ProtocolJson
import org.hddq.bluehop.protocol.ProtocolVerifier
import org.hddq.bluehop.protocol.PskStore
import org.hddq.bluehop.protocol.VerificationResult
import java.net.BindException
import java.net.SocketException
import java.nio.channels.UnsupportedAddressTypeException
import java.util.Locale

@Serializable
data class HolderDeviceStatus(
    val deviceAddress: String,
    val deviceName: String?,
    val connected: Boolean,
    val bredrConnected: Boolean,
    val leConnected: Boolean,
)

@Serializable
data class HolderStatusResponse(
    val version: Int = PROTOCOL_VERSION,
    val deviceAddress: String?,
    val deviceName: String?,
    val connected: Boolean,
    val bredrConnected: Boolean,
    val leConnected: Boolean,
    val port: Int,
    val holderId: String? = null,
    val holderName: String? = null,
    val devices: List<HolderDeviceStatus> = emptyList(),
)

class HolderHttpServer(
    private val host: String = DEFAULT_HOST,
    private val preferredPort: Int = DEFAULT_PORT,
    private val deviceStates: StateFlow<Map<String, DeviceState>>,
    private val selectedDeviceProvider: suspend () -> SelectedBluetoothDevice? = { null },
    private val managedDevicesProvider: suspend () -> List<SelectedBluetoothDevice> = {
        listOfNotNull(selectedDeviceProvider())
    },
    private val isDeviceConnected: (String) -> Boolean,
    private val bluetoothController: BluetoothController,
    private val pskStore: PskStore,
    private val peerPskResolver: suspend (requesterId: String) -> ByteArray? = {
        pskStore.getOrCreatePsk()
    },
    private val holderIdProvider: suspend () -> String? = { null },
    private val holderNameProvider: suspend () -> String? = { null },
    private val verifier: ProtocolVerifier = ProtocolVerifier(),
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
    private val disconnectRetryDelayMs: Long = DEFAULT_DISCONNECT_RETRY_DELAY_MS,
    private val disconnectTimeoutMs: Long = DEFAULT_DISCONNECT_TIMEOUT_MS,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val onLog: (String) -> Unit = {},
) {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val _inFlightReleases = MutableStateFlow(0)
    val inFlightReleases: StateFlow<Int> = _inFlightReleases.asStateFlow()

    @Volatile
    var boundPort: Int = 0
        private set

    suspend fun start(): Int {
        server?.let { return boundPort }

        val (started, boundHost) = startWithFallback()
        server = started
        boundPort = started.engine.resolvedConnectors().first().port
        onLog("Holder HTTP server listening on ${PairingClient.formatHostPort(boundHost, boundPort)}")
        return boundPort
    }

    private fun startWithFallback(): Pair<EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>, String> =
        try {
            startEngineWithPortFallback(host) to host
        } catch (error: Throwable) {
            if (host == DEFAULT_HOST && isIpv6UnavailableError(error)) {
                onLog("Dual-stack IPv6 unavailable on $host, falling back to $IPV4_FALLBACK_HOST")
                startEngineWithPortFallback(IPV4_FALLBACK_HOST) to IPV4_FALLBACK_HOST
            } else {
                throw error
            }
        }

    private fun startEngineWithPortFallback(
        bindHost: String,
    ): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> =
        try {
            startEngine(bindHost, preferredPort)
        } catch (error: BindException) {
            if (preferredPort == 0) throw error
            onLog("Port $preferredPort unavailable, falling back to ephemeral port")
            startEngine(bindHost, 0)
        }

    suspend fun awaitIdle(timeoutMs: Long = 3_000L) {
        withTimeoutOrNull(timeoutMs) {
            inFlightReleases.first { it == 0 }
        }
    }

    fun stop() {
        val current = server ?: return
        server = null
        boundPort = 0
        current.stop(gracePeriodMillis = 1_000L, timeoutMillis = 3_000L)
        onLog("Holder HTTP server stopped")
    }

    private fun startEngine(
        bindHost: String,
        port: Int,
    ): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> =
        embeddedServer(
            factory = CIO,
            port = port,
            host = bindHost,
        ) {
            routing {
                get("/status") {
                    val managed = managedDevicesProvider()
                    val deviceStatuses = managed.map { device ->
                        val state = findDeviceState(deviceStates.value, device.address)
                        val connected = state?.isConnected
                            ?: isDeviceConnected(device.address.uppercase(Locale.ROOT))
                        HolderDeviceStatus(
                            deviceAddress = device.address,
                            deviceName = device.name,
                            connected = connected,
                            bredrConnected = state?.bredrConnected ?: false,
                            leConnected = state?.leConnected ?: false,
                        )
                    }
                    val primary = deviceStatuses.firstOrNull { it.connected }
                        ?: deviceStatuses.firstOrNull()
                    val payload = HolderStatusResponse(
                        deviceAddress = primary?.deviceAddress,
                        deviceName = primary?.deviceName,
                        connected = primary?.connected ?: false,
                        bredrConnected = primary?.bredrConnected ?: false,
                        leConnected = primary?.leConnected ?: false,
                        port = boundPort,
                        holderId = holderIdProvider(),
                        holderName = holderNameProvider(),
                        devices = deviceStatuses,
                    )
                    call.respondText(
                        text = ProtocolJson.codec.encodeToString(payload),
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.OK,
                    )
                }

                post("/release") {
                    _inFlightReleases.update { it + 1 }
                    try {
                        handleRelease(call)
                    } finally {
                        _inFlightReleases.update { (it - 1).coerceAtLeast(0) }
                    }
                }
            }
        }.start(wait = false)

    private suspend fun handleRelease(call: io.ktor.server.routing.RoutingCall) {
        val body = call.receiveText()
        val request = try {
            ProtocolJson.decodeRequest(body)
        } catch (_: SerializationException) {
            respondUnsignedError(
                call = call,
                httpStatus = HttpStatusCode.BadRequest,
                deviceAddress = UNKNOWN_DEVICE_ADDRESS,
                nonce = HmacSigner.generateNonce(),
                message = "Malformed release request JSON",
            )
            return
        } catch (_: IllegalArgumentException) {
            respondUnsignedError(
                call = call,
                httpStatus = HttpStatusCode.BadRequest,
                deviceAddress = UNKNOWN_DEVICE_ADDRESS,
                nonce = HmacSigner.generateNonce(),
                message = "Invalid release request payload",
            )
            return
        }

        val psk = peerPskResolver(request.requesterId)
        if (psk == null) {
            onLog("Rejected /release from unknown requester ${request.requesterId}")
            respondUnsignedError(
                call = call,
                httpStatus = HttpStatusCode.Unauthorized,
                deviceAddress = request.deviceAddress,
                nonce = request.nonce,
                message = "UNKNOWN_REQUESTER",
                status = GrabResponseStatus.REJECTED,
            )
            return
        }

        when (val verification = verifier.verifyRequest(request, psk)) {
            VerificationResult.Valid -> Unit
            is VerificationResult.Invalid -> {
                onLog("Rejected /release from ${request.requesterId}: ${verification.reason}")
                respondSigned(
                    call = call,
                    httpStatus = HttpStatusCode.Unauthorized,
                    psk = psk,
                    status = GrabResponseStatus.REJECTED,
                    deviceAddress = request.deviceAddress,
                    nonce = request.nonce,
                    message = verification.reason.name,
                )
                return
            }
        }

        val managed = managedDevicesProvider()
        val matched = managed.firstOrNull { it.address.equals(request.deviceAddress, ignoreCase = true) }
        if (matched == null) {
            onLog("Rejected /release for ${request.deviceAddress}: not in managed devices (${managed.map { it.address }})")
            respondSigned(
                call = call,
                httpStatus = HttpStatusCode.Conflict,
                psk = psk,
                status = GrabResponseStatus.REJECTED,
                deviceAddress = request.deviceAddress,
                nonce = request.nonce,
                message = "Requested device is not enabled on this host",
            )
            return
        }

        val targetAddress = matched.address.uppercase(Locale.ROOT)
        if (!isCurrentlyConnected(targetAddress)) {
            onLog("Rejected /release for $targetAddress: device is not connected")
            respondSigned(
                call = call,
                httpStatus = HttpStatusCode.Conflict,
                psk = psk,
                status = GrabResponseStatus.REJECTED,
                deviceAddress = request.deviceAddress,
                nonce = request.nonce,
                message = "Device is not connected",
            )
            return
        }

        onLog("Executing disconnect for $targetAddress requested by ${request.requesterId}")
        val disconnectResult = bluetoothController.disconnect(targetAddress)
        if (!disconnectResult.isSuccess) {
            onLog("Disconnect call failed for $targetAddress: ${disconnectResult.message}")
            respondSigned(
                call = call,
                httpStatus = HttpStatusCode.BadGateway,
                psk = psk,
                status = GrabResponseStatus.ERROR,
                deviceAddress = request.deviceAddress,
                nonce = request.nonce,
                message = disconnectResult.message,
            )
            return
        }

        val confirmed = awaitDisconnectConfirmed(targetAddress)
        if (!confirmed) {
            onLog("Timed out waiting for disconnect confirmation for $targetAddress")
            respondSigned(
                call = call,
                httpStatus = HttpStatusCode.GatewayTimeout,
                psk = psk,
                status = GrabResponseStatus.ERROR,
                deviceAddress = request.deviceAddress,
                nonce = request.nonce,
                message = "Timed out waiting for disconnect confirmation",
            )
            return
        }

        onLog("Confirmed disconnect for $targetAddress")
        respondSigned(
            call = call,
            httpStatus = HttpStatusCode.OK,
            psk = psk,
            status = GrabResponseStatus.OK,
            deviceAddress = request.deviceAddress,
            nonce = request.nonce,
            message = "Disconnected",
        )
    }

    private suspend fun respondSigned(
        call: io.ktor.server.routing.RoutingCall,
        httpStatus: HttpStatusCode,
        psk: ByteArray,
        status: GrabResponseStatus,
        deviceAddress: String,
        nonce: String,
        message: String,
    ) {
        val response = HmacSigner.signResponse(
            psk = psk,
            response = GrabResponse(
                status = status,
                deviceAddress = deviceAddress,
                timestampEpochSec = nowEpochSeconds(),
                nonce = nonce,
                message = message,
            ),
        )
        call.respondText(
            text = ProtocolJson.encodeResponse(response),
            contentType = ContentType.Application.Json,
            status = httpStatus,
        )
    }

    private suspend fun respondUnsignedError(
        call: io.ktor.server.routing.RoutingCall,
        httpStatus: HttpStatusCode,
        deviceAddress: String,
        nonce: String,
        message: String,
        status: GrabResponseStatus = GrabResponseStatus.ERROR,
    ) {
        val response = GrabResponse(
            status = status,
            deviceAddress = deviceAddress,
            timestampEpochSec = nowEpochSeconds(),
            nonce = nonce,
            message = message,
        )
        call.respondText(
            text = ProtocolJson.encodeResponse(response),
            contentType = ContentType.Application.Json,
            status = httpStatus,
        )
    }

    private suspend fun awaitDisconnectConfirmed(address: String): Boolean {
        if (!isCurrentlyConnected(address)) return true

        return withTimeoutOrNull(disconnectTimeoutMs) {
            coroutineScope {
                val retryJob = launch {
                    if (disconnectRetryDelayMs in 1 until disconnectTimeoutMs) {
                        delay(disconnectRetryDelayMs)
                        if (isCurrentlyConnected(address)) {
                            onLog("Retrying disconnect for $address")
                            bluetoothController.disconnect(address)
                        }
                    }
                }
                val stateWaitJob = launch {
                    deviceStates.first { states ->
                        val state = findDeviceState(states, address)
                        state != null && !state.isConnected
                    }
                }
                val pollJob = launch {
                    while (isCurrentlyConnected(address)) {
                        delay(pollIntervalMs)
                    }
                }

                select<Unit> {
                    stateWaitJob.onJoin {}
                    pollJob.onJoin {}
                }
                retryJob.cancel()
                stateWaitJob.cancel()
                pollJob.cancel()
                true
            }
        } ?: !isCurrentlyConnected(address)
    }

    private fun isCurrentlyConnected(address: String): Boolean {
        val state = findDeviceState(deviceStates.value, address)
        return state?.isConnected ?: isDeviceConnected(address)
    }

    private fun findDeviceState(
        states: Map<String, DeviceState>,
        address: String,
    ): DeviceState? = states.entries.firstOrNull { (key, _) ->
        key.equals(address, ignoreCase = true)
    }?.value

    companion object {
        const val DEFAULT_HOST = "::"
        const val IPV4_FALLBACK_HOST = "0.0.0.0"
        const val DEFAULT_PORT = 8765
        const val DEFAULT_DISCONNECT_RETRY_DELAY_MS = 4_000L
        const val DEFAULT_DISCONNECT_TIMEOUT_MS = 10_000L
        const val DEFAULT_POLL_INTERVAL_MS = 250L
        private const val UNKNOWN_DEVICE_ADDRESS = "00:00:00:00:00:00"
    }
}

internal fun isIpv6UnavailableError(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.any { cause ->
        cause is UnsupportedAddressTypeException ||
            (cause is SocketException && cause !is BindException) ||
            (cause is BindException &&
                cause.message?.contains("assign requested address", ignoreCase = true) == true)
    }
