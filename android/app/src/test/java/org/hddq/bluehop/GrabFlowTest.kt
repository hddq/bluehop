package org.hddq.bluehop

import android.bluetooth.BluetoothStatusCodes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.hddq.bluehop.bluetooth.BluetoothControlResult
import org.hddq.bluehop.bluetooth.BluetoothController
import org.hddq.bluehop.bluetooth.DeviceState
import org.hddq.bluehop.bluetooth.HopCoordinator
import org.hddq.bluehop.bluetooth.HopOutcome
import org.hddq.bluehop.bluetooth.HopProgress
import org.hddq.bluehop.bluetooth.ProfileConfirmation
import org.hddq.bluehop.bluetooth.SelectedBluetoothDevice
import org.hddq.bluehop.protocol.GrabResponse
import org.hddq.bluehop.protocol.GrabResponseStatus
import org.hddq.bluehop.protocol.HmacSigner
import org.hddq.bluehop.protocol.LocalDeviceIdentity
import org.hddq.bluehop.protocol.ProtocolJson
import org.hddq.bluehop.protocol.ProtocolVerifier
import org.hddq.bluehop.protocol.PskStore
import org.hddq.bluehop.server.DiscoveredBlueHopPeer
import org.hddq.bluehop.server.GrabClient
import org.hddq.bluehop.server.HolderHttpServer
import org.hddq.bluehop.server.ReleaseOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrabFlowTest {

    private class InMemoryPskStore(
        private var psk: ByteArray = ByteArray(32) { 9 },
    ) : PskStore {
        override suspend fun getOrCreatePsk(): ByteArray = psk.copyOf()

        override suspend fun setPsk(psk: ByteArray) {
            this.psk = psk.copyOf()
        }

        override suspend fun clearPsk() {
            this.psk = ByteArray(32)
        }
    }

    private class RecordingBluetoothController(
        private val events: MutableList<String> = mutableListOf(),
        private val connectStatusCode: Int = BluetoothStatusCodes.SUCCESS,
        private val onDisconnect: (String) -> Unit = {},
        private val onConnect: (String) -> Unit = {},
    ) : BluetoothController {
        override fun connect(address: String): BluetoothControlResult {
            events += "connect:$address"
            if (connectStatusCode == BluetoothStatusCodes.SUCCESS) {
                onConnect(address)
            }
            return BluetoothControlResult(
                statusCode = connectStatusCode,
                message = if (connectStatusCode == BluetoothStatusCodes.SUCCESS) "OK" else "Error ($connectStatusCode)",
            )
        }

        override fun disconnect(address: String): BluetoothControlResult {
            events += "disconnect:$address"
            onDisconnect(address)
            return BluetoothControlResult(BluetoothStatusCodes.SUCCESS, "OK")
        }
    }

    private class FakeProfileConfirmation(
        private val result: Boolean = true,
        private val events: MutableList<String>? = null,
    ) : ProfileConfirmation {
        override suspend fun awaitConfirmation(address: String, targetConnected: Boolean): Boolean {
            events?.add("confirm:$address:$targetConnected")
            return result
        }
    }

    private val headphoneMac = "AC:80:0A:CD:82:A1"
    private val holderIdentity = LocalDeviceIdentity("holder-uuid-a", "Pixel 9")
    private val requesterIdentity = LocalDeviceIdentity("requester-uuid-b", "Pixel Tablet")
    private val sharedPsk = ByteArray(32) { (it + 33).toByte() }
    private val fixedNow = 1_760_000_000L

    @Test
    fun `paired holder releases before requester connects and confirms audio`() = runBlocking {
        val events = mutableListOf<String>()
        val holderStates = MutableStateFlow(
            mapOf(headphoneMac to DeviceState(bredrConnected = true, leConnected = false)),
        )
        val holderBt = RecordingBluetoothController(
            events = events,
            onDisconnect = {
                holderStates.value = mapOf(headphoneMac to DeviceState(bredrConnected = false))
            },
        )
        val server = HolderHttpServer(
            host = "127.0.0.1",
            preferredPort = 0,
            deviceStates = holderStates,
            selectedDeviceProvider = { SelectedBluetoothDevice("WH-1000XM4", headphoneMac) },
            isDeviceConnected = { addr -> holderStates.value[addr]?.isConnected == true },
            bluetoothController = holderBt,
            pskStore = InMemoryPskStore(),
            peerPskResolver = { requesterId ->
                if (requesterId == requesterIdentity.deviceId) sharedPsk else null
            },
            holderIdProvider = { holderIdentity.deviceId },
            holderNameProvider = { holderIdentity.deviceName },
            verifier = ProtocolVerifier(nowEpochSeconds = { fixedNow }),
            nowEpochSeconds = { fixedNow },
            pollIntervalMs = 20L,
        )

        val port = server.start()
        try {
            val requesterBt = RecordingBluetoothController(events = events)
            val grabClient = GrabClient(
                verifier = ProtocolVerifier(nowEpochSeconds = { fixedNow }),
                nowEpochSeconds = { fixedNow },
            )
            val coordinator = HopCoordinator(
                bluetoothController = requesterBt,
                holderClient = grabClient,
                profileConfirmation = FakeProfileConfirmation(result = true, events = events),
                discoveryWaitTimeoutMs = 0L,
            )

            val peersFlow = MutableStateFlow(
                listOf(
                    DiscoveredBlueHopPeer(
                        serviceName = "BlueHop - Pixel 9",
                        host = "127.0.0.1",
                        port = port,
                        deviceId = holderIdentity.deviceId,
                        displayName = holderIdentity.deviceName,
                        pairingAvailable = false,
                        connectedBluetoothAddress = headphoneMac,
                    ),
                ),
            )
            val progressSteps = mutableListOf<HopProgress>()

            val outcome = coordinator.hopToDevice(
                targetAddress = headphoneMac,
                targetName = "WH-1000XM4",
                isAudioDevice = true,
                isLocallyConnected = false,
                localIdentity = requesterIdentity,
                peersFlow = peersFlow,
                hasPairedPeers = true,
                peerPskProvider = { peerId -> if (peerId == holderIdentity.deviceId) sharedPsk else null },
                onProgress = { progressSteps += it },
            )

            assertEquals(HopOutcome.Connected(releasedFromHolderName = "Pixel 9"), outcome)
            assertEquals(
                listOf(
                    "disconnect:$headphoneMac",
                    "confirm:$headphoneMac:true",
                    "connect:$headphoneMac",
                ),
                events,
            )
            assertEquals(
                listOf(
                    HopProgress.Searching,
                    HopProgress.Releasing("Pixel 9"),
                    HopProgress.Connecting("WH-1000XM4"),
                    HopProgress.ConfirmingAudio("WH-1000XM4"),
                ),
                progressSteps,
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun `falls back to status check when peer txt omits connected bluetooth address`() = runBlocking {
        val events = mutableListOf<String>()
        val holderStates = MutableStateFlow(
            mapOf(headphoneMac to DeviceState(bredrConnected = true)),
        )
        val server = HolderHttpServer(
            host = "127.0.0.1",
            preferredPort = 0,
            deviceStates = holderStates,
            selectedDeviceProvider = { SelectedBluetoothDevice("WH-1000XM4", headphoneMac) },
            isDeviceConnected = { addr -> holderStates.value[addr]?.isConnected == true },
            bluetoothController = RecordingBluetoothController(
                events = events,
                onDisconnect = {
                    holderStates.value = mapOf(headphoneMac to DeviceState(bredrConnected = false))
                },
            ),
            pskStore = InMemoryPskStore(),
            peerPskResolver = { requesterId ->
                if (requesterId == requesterIdentity.deviceId) sharedPsk else null
            },
            holderIdProvider = { holderIdentity.deviceId },
            holderNameProvider = { "Pixel 9 Pro" },
            verifier = ProtocolVerifier(nowEpochSeconds = { fixedNow }),
            nowEpochSeconds = { fixedNow },
            pollIntervalMs = 20L,
        )

        val port = server.start()
        try {
            val coordinator = HopCoordinator(
                bluetoothController = RecordingBluetoothController(events = events),
                holderClient = GrabClient(
                    verifier = ProtocolVerifier(nowEpochSeconds = { fixedNow }),
                    nowEpochSeconds = { fixedNow },
                ),
                profileConfirmation = FakeProfileConfirmation(result = true),
                discoveryWaitTimeoutMs = 0L,
            )

            val peersFlow = MutableStateFlow(
                listOf(
                    DiscoveredBlueHopPeer(
                        serviceName = "BlueHop - Pixel 9",
                        host = "127.0.0.1",
                        port = port,
                        deviceId = holderIdentity.deviceId,
                        displayName = "Pixel 9",
                        pairingAvailable = false,
                        connectedBluetoothAddress = null,
                    ),
                ),
            )

            val outcome = coordinator.hopToDevice(
                targetAddress = headphoneMac,
                targetName = "WH-1000XM4",
                isAudioDevice = true,
                isLocallyConnected = false,
                localIdentity = requesterIdentity,
                peersFlow = peersFlow,
                hasPairedPeers = true,
                peerPskProvider = { sharedPsk },
            )

            assertEquals(HopOutcome.Connected(releasedFromHolderName = "Pixel 9 Pro"), outcome)
            assertEquals(listOf("disconnect:$headphoneMac", "connect:$headphoneMac"), events)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `rejects hop without connecting locally when holder denies pairing`() = runBlocking {
        val events = mutableListOf<String>()
        val holderStates = MutableStateFlow(
            mapOf(headphoneMac to DeviceState(bredrConnected = true)),
        )
        val server = HolderHttpServer(
            host = "127.0.0.1",
            preferredPort = 0,
            deviceStates = holderStates,
            selectedDeviceProvider = { SelectedBluetoothDevice("WH-1000XM4", headphoneMac) },
            isDeviceConnected = { addr -> holderStates.value[addr]?.isConnected == true },
            bluetoothController = RecordingBluetoothController(events = events),
            pskStore = InMemoryPskStore(),
            peerPskResolver = { null },
            verifier = ProtocolVerifier(nowEpochSeconds = { fixedNow }),
            nowEpochSeconds = { fixedNow },
        )

        val port = server.start()
        try {
            val coordinator = HopCoordinator(
                bluetoothController = RecordingBluetoothController(events = events),
                holderClient = GrabClient(
                    verifier = ProtocolVerifier(nowEpochSeconds = { fixedNow }),
                    nowEpochSeconds = { fixedNow },
                ),
                profileConfirmation = FakeProfileConfirmation(result = true),
                discoveryWaitTimeoutMs = 0L,
            )

            val peersFlow = MutableStateFlow(
                listOf(
                    DiscoveredBlueHopPeer(
                        serviceName = "BlueHop - Pixel 9",
                        host = "127.0.0.1",
                        port = port,
                        deviceId = holderIdentity.deviceId,
                        displayName = holderIdentity.deviceName,
                        pairingAvailable = false,
                        connectedBluetoothAddress = headphoneMac,
                    ),
                ),
            )

            val outcome = coordinator.hopToDevice(
                targetAddress = headphoneMac,
                targetName = "WH-1000XM4",
                isAudioDevice = true,
                isLocallyConnected = false,
                localIdentity = requesterIdentity,
                peersFlow = peersFlow,
                hasPairedPeers = true,
                peerPskProvider = { sharedPsk },
            )

            assertTrue(outcome is HopOutcome.Failed)
            assertTrue((outcome as HopOutcome.Failed).message.contains("no longer recognizes"))
            assertTrue(events.isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `grab client rejects forged holder response signature`() = runBlocking {
        val forgedServer = embeddedServer(
            factory = CIO,
            port = 0,
            host = "127.0.0.1",
        ) {
            routing {
                post("/release") {
                    val body = call.receiveText()
                    val req = ProtocolJson.decodeRequest(body)
                    val forged = HmacSigner.signResponse(
                        psk = ByteArray(32) { 1 },
                        response = GrabResponse(
                            status = GrabResponseStatus.OK,
                            deviceAddress = req.deviceAddress,
                            timestampEpochSec = fixedNow,
                            nonce = req.nonce,
                            message = "Disconnected",
                        ),
                    )
                    call.respondText(
                        text = ProtocolJson.encodeResponse(forged),
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.OK,
                    )
                }
            }
        }.start(wait = false)

        val port = forgedServer.engine.resolvedConnectors().first().port
        try {
            val client = GrabClient(
                verifier = ProtocolVerifier(nowEpochSeconds = { fixedNow }),
                nowEpochSeconds = { fixedNow },
            )
            val outcome = client.requestRelease(
                host = "127.0.0.1",
                port = port,
                deviceAddress = headphoneMac,
                requesterId = requesterIdentity.deviceId,
                psk = sharedPsk,
            )
            assertTrue(outcome is ReleaseOutcome.Failed)
            assertTrue((outcome as ReleaseOutcome.Failed).message.contains("INVALID_SIGNATURE"))
        } finally {
            forgedServer.stop(500L, 1_000L)
        }
    }

    @Test
    fun `connects directly when no paired holder is active or holder reports already disconnected`() = runBlocking {
        val events = mutableListOf<String>()
        val holderStates = MutableStateFlow(
            mapOf(headphoneMac to DeviceState(bredrConnected = false, leConnected = false)),
        )
        val server = HolderHttpServer(
            host = "127.0.0.1",
            preferredPort = 0,
            deviceStates = holderStates,
            selectedDeviceProvider = { SelectedBluetoothDevice("WH-1000XM4", headphoneMac) },
            isDeviceConnected = { false },
            bluetoothController = RecordingBluetoothController(events = events),
            pskStore = InMemoryPskStore(),
            peerPskResolver = { sharedPsk },
            verifier = ProtocolVerifier(nowEpochSeconds = { fixedNow }),
            nowEpochSeconds = { fixedNow },
        )

        val port = server.start()
        try {
            val coordinator = HopCoordinator(
                bluetoothController = RecordingBluetoothController(events = events),
                holderClient = GrabClient(
                    verifier = ProtocolVerifier(nowEpochSeconds = { fixedNow }),
                    nowEpochSeconds = { fixedNow },
                ),
                profileConfirmation = FakeProfileConfirmation(result = true),
                discoveryWaitTimeoutMs = 0L,
            )

            // Even with a stale mDNS record pointing to the holder, 409 already-disconnected falls through cleanly
            val peersFlow = MutableStateFlow(
                listOf(
                    DiscoveredBlueHopPeer(
                        serviceName = "BlueHop - Pixel 9",
                        host = "127.0.0.1",
                        port = port,
                        deviceId = holderIdentity.deviceId,
                        displayName = holderIdentity.deviceName,
                        pairingAvailable = false,
                        connectedBluetoothAddress = headphoneMac,
                    ),
                ),
            )

            val outcome = coordinator.hopToDevice(
                targetAddress = headphoneMac,
                targetName = "WH-1000XM4",
                isAudioDevice = true,
                isLocallyConnected = false,
                localIdentity = requesterIdentity,
                peersFlow = peersFlow,
                hasPairedPeers = true,
                peerPskProvider = { sharedPsk },
            )

            assertEquals(HopOutcome.Connected(releasedFromHolderName = null), outcome)
            assertEquals(listOf("connect:$headphoneMac"), events)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `returns already connected and needs association appropriately`() = runBlocking {
        val events = mutableListOf<String>()
        val alreadyCoordinator = HopCoordinator(
            bluetoothController = RecordingBluetoothController(events = events),
            holderClient = GrabClient(),
            discoveryWaitTimeoutMs = 0L,
        )
        val alreadyOutcome = alreadyCoordinator.hopToDevice(
            targetAddress = headphoneMac,
            targetName = "WH-1000XM4",
            isAudioDevice = true,
            isLocallyConnected = true,
            localIdentity = requesterIdentity,
            peersFlow = MutableStateFlow(emptyList()),
            hasPairedPeers = false,
            peerPskProvider = { null },
        )
        assertEquals(HopOutcome.AlreadyConnected, alreadyOutcome)
        assertTrue(events.isEmpty())

        val assocCoordinator = HopCoordinator(
            bluetoothController = RecordingBluetoothController(
                events = events,
                connectStatusCode = BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED,
            ),
            holderClient = GrabClient(),
            profileConfirmation = FakeProfileConfirmation(result = true),
            discoveryWaitTimeoutMs = 0L,
        )
        val assocOutcome = assocCoordinator.hopToDevice(
            targetAddress = headphoneMac,
            targetName = "WH-1000XM4",
            isAudioDevice = true,
            isLocallyConnected = false,
            localIdentity = requesterIdentity,
            peersFlow = MutableStateFlow(emptyList()),
            hasPairedPeers = false,
            peerPskProvider = { null },
        )
        assertEquals(HopOutcome.NeedsAssociation, assocOutcome)
    }
}
