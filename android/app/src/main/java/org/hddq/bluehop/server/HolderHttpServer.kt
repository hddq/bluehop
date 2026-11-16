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
import java.util.Locale

@Serializable
data class HolderStatusResponse(
    val version: Int = PROTOCOL_VERSION,
    val deviceAddress: String?,
    val deviceName: String?,
    val connected: Boolean,
    val bredrConnected: Boolean,
    val leConnected: Boolean,
    val port: Int,
)

class HolderHttpServer(
    private val host: String = DEFAULT_HOST,
    private val preferredPort: Int = DEFAULT_PORT,
    private val deviceStates: StateFlow<Map<String, DeviceState>>,
    private val selectedDeviceProvider: suspend () -> SelectedBluetoothDevice?,
    private val isDeviceConnected: (String) -> Boolean,
    private val bluetoothController: BluetoothController,
    private val pskStore: PskStore,
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

        val started = try {
            startEngine(preferredPort)
        } catch (error: BindException) {
            if (preferredPort == 0) throw error
            onLog("Port $preferredPort unavailable, falling back to ephemeral port")
            startEngine(0)
        }
        server = started
        boundPort = started.engine.resolvedConnectors().first().port
        onLog("Holder HTTP server listening on $host:$boundPort")
        return boundPort
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
        port: Int,
    ): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> =
        embeddedServer(
            factory = CIO,
            port = port,
            host = host,
        ) {
            routing {
                get("/status") {
                    val selected = selectedDeviceProvider()
                    val state = selected?.address?.let { findDeviceState(deviceStates.value, it) }
                    val connected = state?.isConnected
                        ?: selected?.address?.let { isDeviceConnected(it.uppercase(Locale.ROOT)) }
                        ?: false
                    val payload = HolderStatusResponse(
                        deviceAddress = selected?.address,
                        deviceName = selected?.name,
                        connected = connected,
                        bredrConnected = state?.bredrConnected ?: false,
                        leConnected = state?.leConnected ?: false,
                        port = boundPort,
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
        val psk = pskStore.getOrCreatePsk()
        val body = call.receiveText()
        val request = try {
            ProtocolJson.decodeRequest(body)
        } catch (_: SerializationException) {
            respondSigned(
                call = call,
                httpStatus = HttpStatusCode.BadRequest,
                psk = psk,
                status = GrabResponseStatus.ERROR,
                deviceAddress = UNKNOWN_DEVICE_ADDRESS,
                nonce = HmacSigner.generateNonce(),
                message = "Malformed release request JSON",
            )
            return
        } catch (_: IllegalArgumentException) {
            respondSigned(
                call = call,
                httpStatus = HttpStatusCode.BadRequest,
                psk = psk,
                status = GrabResponseStatus.ERROR,
                deviceAddress = UNKNOWN_DEVICE_ADDRESS,
                nonce = HmacSigner.generateNonce(),
                message = "Invalid release request payload",
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

        val selected = selectedDeviceProvider()
        if (selected != null && !selected.address.equals(request.deviceAddress, ignoreCase = true)) {
            onLog("Rejected /release for ${request.deviceAddress}: selected device is ${selected.address}")
            respondSigned(
                call = call,
                httpStatus = HttpStatusCode.Conflict,
                psk = psk,
                status = GrabResponseStatus.REJECTED,
                deviceAddress = request.deviceAddress,
                nonce = request.nonce,
                message = "Requested device is not the selected device",
            )
            return
        }

        val targetAddress = (selected?.address ?: request.deviceAddress).uppercase(Locale.ROOT)
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
        const val DEFAULT_HOST = "0.0.0.0"
        const val DEFAULT_PORT = 8765
        const val DEFAULT_DISCONNECT_RETRY_DELAY_MS = 4_000L
        const val DEFAULT_DISCONNECT_TIMEOUT_MS = 10_000L
        const val DEFAULT_POLL_INTERVAL_MS = 250L
        private const val UNKNOWN_DEVICE_ADDRESS = "00:00:00:00:00:00"
    }
}
