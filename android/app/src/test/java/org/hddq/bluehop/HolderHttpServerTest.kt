package org.hddq.bluehop

import android.bluetooth.BluetoothStatusCodes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.hddq.bluehop.bluetooth.BluetoothControlResult
import org.hddq.bluehop.bluetooth.BluetoothController
import org.hddq.bluehop.bluetooth.DeviceState
import org.hddq.bluehop.bluetooth.SelectedBluetoothDevice
import org.hddq.bluehop.protocol.GrabRequest
import org.hddq.bluehop.protocol.GrabResponseStatus
import org.hddq.bluehop.protocol.HmacSigner
import org.hddq.bluehop.protocol.ProtocolJson
import org.hddq.bluehop.protocol.ProtocolVerifier
import org.hddq.bluehop.protocol.PskStore
import org.hddq.bluehop.protocol.VerificationFailureReason
import org.hddq.bluehop.protocol.VerificationResult
import org.hddq.bluehop.server.GrabClient
import org.hddq.bluehop.server.HolderHttpServer
import org.hddq.bluehop.server.HolderStatusResponse
import org.hddq.bluehop.server.ReleaseOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.ServerSocketChannel
import java.time.Duration

class HolderHttpServerTest {

    private class InMemoryPskStore(
        private var psk: ByteArray,
    ) : PskStore {
        override suspend fun getOrCreatePsk(): ByteArray = psk.copyOf()

        override suspend fun setPsk(psk: ByteArray) {
            this.psk = psk.copyOf()
        }

        override suspend fun clearPsk() {
            this.psk = ByteArray(32)
        }
    }

    private class FakeBluetoothController(
        private val onDisconnect: (String) -> Unit = {},
    ) : BluetoothController {
        val disconnectCalls = mutableListOf<String>()

        override fun connect(address: String): BluetoothControlResult =
            BluetoothControlResult(BluetoothStatusCodes.SUCCESS, "OK")

        override fun disconnect(address: String): BluetoothControlResult {
            disconnectCalls += address
            onDisconnect(address)
            return BluetoothControlResult(BluetoothStatusCodes.SUCCESS, "Request accepted (SUCCESS)")
        }
    }

    private val testAddress = "AC:80:0A:CD:82:A1"
    private val testPsk = ByteArray(32) { (it + 1).toByte() }
    private val fixedNow = 1_760_000_000L

    @Test
    fun `status endpoint returns selected device and connection state`() = runBlocking {
        val deviceStates = MutableStateFlow(
            mapOf(testAddress to DeviceState(bredrConnected = true, leConnected = false)),
        )
        val server = HolderHttpServer(
            host = "127.0.0.1",
            preferredPort = 0,
            deviceStates = deviceStates,
            selectedDeviceProvider = { SelectedBluetoothDevice("WH-1000XM4", testAddress) },
            isDeviceConnected = { addr -> deviceStates.value[addr]?.isConnected == true },
            bluetoothController = FakeBluetoothController(),
            pskStore = InMemoryPskStore(testPsk),
            verifier = ProtocolVerifier(nowEpochSeconds = { fixedNow }),
            nowEpochSeconds = { fixedNow },
        )

        val port = server.start()
        try {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build()
            val response = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/status"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, response.statusCode())
            val status = ProtocolJson.codec.decodeFromString<HolderStatusResponse>(response.body())
            assertEquals(testAddress, status.deviceAddress)
            assertEquals("WH-1000XM4", status.deviceName)
            assertTrue(status.connected)
            assertTrue(status.bredrConnected)
            assertFalse(status.leConnected)
            assertEquals(port, status.port)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `release rejects invalid signature without calling disconnect`() = runBlocking {
        val deviceStates = MutableStateFlow(
            mapOf(testAddress to DeviceState(bredrConnected = true)),
        )
        val controller = FakeBluetoothController()
        val verifier = ProtocolVerifier(nowEpochSeconds = { fixedNow })
        val server = HolderHttpServer(
            host = "127.0.0.1",
            preferredPort = 0,
            deviceStates = deviceStates,
            selectedDeviceProvider = { SelectedBluetoothDevice("WH-1000XM4", testAddress) },
            isDeviceConnected = { addr -> deviceStates.value[addr]?.isConnected == true },
            bluetoothController = controller,
            pskStore = InMemoryPskStore(testPsk),
            verifier = verifier,
            nowEpochSeconds = { fixedNow },
        )

        val port = server.start()
        try {
            val badRequest = GrabRequest(
                deviceAddress = testAddress,
                requesterId = "pc-linux",
                timestampEpochSec = fixedNow,
                nonce = "00112233445566778899aabbccddeeff",
                signature = "0".repeat(64),
            )
            val response = postRelease(port, ProtocolJson.encodeRequest(badRequest))
            assertEquals(401, response.statusCode())
            assertTrue(controller.disconnectCalls.isEmpty())

            val grabResponse = ProtocolJson.decodeResponse(response.body())
            assertEquals(GrabResponseStatus.REJECTED, grabResponse.status)
            assertEquals(VerificationFailureReason.INVALID_SIGNATURE.name, grabResponse.message)
            assertEquals(VerificationResult.Valid, verifier.verifyResponse(grabResponse, testPsk, badRequest.nonce))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `release waits for disconnect confirmation before responding and blocks replay`() = runBlocking {
        val deviceStates = MutableStateFlow(
            mapOf(testAddress to DeviceState(bredrConnected = true)),
        )
        val disconnectCalled = CompletableDeferred<Unit>()
        val controller = FakeBluetoothController(
            onDisconnect = { disconnectCalled.complete(Unit) },
        )
        val verifier = ProtocolVerifier(nowEpochSeconds = { fixedNow })
        val server = HolderHttpServer(
            host = "127.0.0.1",
            preferredPort = 0,
            deviceStates = deviceStates,
            selectedDeviceProvider = { SelectedBluetoothDevice("WH-1000XM4", testAddress) },
            isDeviceConnected = { addr -> deviceStates.value[addr]?.isConnected == true },
            bluetoothController = controller,
            pskStore = InMemoryPskStore(testPsk),
            verifier = verifier,
            nowEpochSeconds = { fixedNow },
            disconnectRetryDelayMs = 2_000L,
            disconnectTimeoutMs = 5_000L,
            pollIntervalMs = 50L,
        )

        val port = server.start()
        try {
            val signedRequest = HmacSigner.signRequest(
                psk = testPsk,
                request = GrabRequest(
                    deviceAddress = testAddress.lowercase(),
                    requesterId = "pc-linux",
                    timestampEpochSec = fixedNow,
                    nonce = "abcdef0123456789abcdef0123456789",
                ),
            )
            val bodyJson = ProtocolJson.encodeRequest(signedRequest)

            val responseDeferred = async(Dispatchers.IO) {
                postRelease(port, bodyJson)
            }

            disconnectCalled.await()
            delay(150L)
            assertFalse("Server must wait for disconnect confirmation before answering", responseDeferred.isCompleted)

            deviceStates.value = mapOf(testAddress to DeviceState(bredrConnected = false, leConnected = false))

            val response = responseDeferred.await()
            assertEquals(200, response.statusCode())
            assertEquals(listOf(testAddress), controller.disconnectCalls)

            val grabResponse = ProtocolJson.decodeResponse(response.body())
            assertEquals(GrabResponseStatus.OK, grabResponse.status)
            assertEquals(
                VerificationResult.Valid,
                verifier.verifyResponse(grabResponse, testPsk, signedRequest.nonce),
            )

            // Replaying the same signed request must be rejected with 401 REPLAYED_NONCE
            val replayResponse = postRelease(port, bodyJson)
            assertEquals(401, replayResponse.statusCode())
            val replayGrabResponse = ProtocolJson.decodeResponse(replayResponse.body())
            assertEquals(VerificationFailureReason.REPLAYED_NONCE.name, replayGrabResponse.message)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `release returns conflict when device is already disconnected`() = runBlocking {
        val deviceStates = MutableStateFlow(
            mapOf(testAddress to DeviceState(bredrConnected = false, leConnected = false)),
        )
        val controller = FakeBluetoothController()
        val server = HolderHttpServer(
            host = "127.0.0.1",
            preferredPort = 0,
            deviceStates = deviceStates,
            selectedDeviceProvider = { SelectedBluetoothDevice("WH-1000XM4", testAddress) },
            isDeviceConnected = { addr -> deviceStates.value[addr]?.isConnected == true },
            bluetoothController = controller,
            pskStore = InMemoryPskStore(testPsk),
            verifier = ProtocolVerifier(nowEpochSeconds = { fixedNow }),
            nowEpochSeconds = { fixedNow },
        )

        val port = server.start()
        try {
            val signedRequest = HmacSigner.signRequest(
                psk = testPsk,
                request = GrabRequest(
                    deviceAddress = testAddress,
                    requesterId = "pc-linux",
                    timestampEpochSec = fixedNow,
                    nonce = "11223344556677889900aabbccddeeff",
                ),
            )
            val response = postRelease(port, ProtocolJson.encodeRequest(signedRequest))
            assertEquals(409, response.statusCode())
            assertTrue(controller.disconnectCalls.isEmpty())
            val grabResponse = ProtocolJson.decodeResponse(response.body())
            assertEquals(GrabResponseStatus.REJECTED, grabResponse.status)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `release rejects unknown requester and authenticates using per-peer PSK`() = runBlocking {
        val peerAPsk = ByteArray(32) { (it + 11).toByte() }
        val peerBPsk = ByteArray(32) { (it + 77).toByte() }
        val peerPsks = mapOf(
            "peer-a" to peerAPsk,
            "peer-b" to peerBPsk,
        )
        val deviceStates = MutableStateFlow(
            mapOf(testAddress to DeviceState(bredrConnected = true)),
        )
        val controller = FakeBluetoothController(
            onDisconnect = {
                deviceStates.value = mapOf(testAddress to DeviceState(bredrConnected = false))
            },
        )
        val verifier = ProtocolVerifier(nowEpochSeconds = { fixedNow })
        val server = HolderHttpServer(
            host = "127.0.0.1",
            preferredPort = 0,
            deviceStates = deviceStates,
            selectedDeviceProvider = { SelectedBluetoothDevice("WH-1000XM4", testAddress) },
            isDeviceConnected = { addr -> deviceStates.value[addr]?.isConnected == true },
            bluetoothController = controller,
            pskStore = InMemoryPskStore(testPsk),
            peerPskResolver = { requesterId -> peerPsks[requesterId] },
            holderIdProvider = { "holder-uuid-1" },
            holderNameProvider = { "Pixel 9" },
            verifier = verifier,
            nowEpochSeconds = { fixedNow },
        )

        val port = server.start()
        try {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build()
            val statusHttp = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/status"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            val status = ProtocolJson.codec.decodeFromString<HolderStatusResponse>(statusHttp.body())
            assertEquals("holder-uuid-1", status.holderId)
            assertEquals("Pixel 9", status.holderName)

            val unknownReq = HmacSigner.signRequest(
                psk = testPsk,
                request = GrabRequest(
                    deviceAddress = testAddress,
                    requesterId = "unpaired-device",
                    timestampEpochSec = fixedNow,
                    nonce = "aaaa1111bbbb2222cccc3333dddd4444",
                ),
            )
            val unknownResp = postRelease(port, ProtocolJson.encodeRequest(unknownReq))
            assertEquals(401, unknownResp.statusCode())
            val unknownBody = ProtocolJson.decodeResponse(unknownResp.body())
            assertEquals(GrabResponseStatus.REJECTED, unknownBody.status)
            assertEquals("UNKNOWN_REQUESTER", unknownBody.message)
            assertEquals("", unknownBody.signature)
            assertTrue(controller.disconnectCalls.isEmpty())

            // Signed with peer-b's PSK while claiming requesterId = peer-a must fail
            val wrongKeyReq = HmacSigner.signRequest(
                psk = peerBPsk,
                request = GrabRequest(
                    deviceAddress = testAddress,
                    requesterId = "peer-a",
                    timestampEpochSec = fixedNow,
                    nonce = "eeee1111ffff2222aaaa3333bbbb4444",
                ),
            )
            val wrongKeyResp = postRelease(port, ProtocolJson.encodeRequest(wrongKeyReq))
            assertEquals(401, wrongKeyResp.statusCode())
            assertTrue(controller.disconnectCalls.isEmpty())

            // Signed with peer-a's own PSK succeeds and signs response with peer-a's PSK
            val validReq = HmacSigner.signRequest(
                psk = peerAPsk,
                request = GrabRequest(
                    deviceAddress = testAddress,
                    requesterId = "peer-a",
                    timestampEpochSec = fixedNow,
                    nonce = "12341234123412341234123412341234",
                ),
            )
            val validResp = postRelease(port, ProtocolJson.encodeRequest(validReq))
            assertEquals(200, validResp.statusCode())
            val validBody = ProtocolJson.decodeResponse(validResp.body())
            assertEquals(GrabResponseStatus.OK, validBody.status)
            assertEquals(
                VerificationResult.Valid,
                verifier.verifyResponse(validBody, peerAPsk, validReq.nonce),
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun `status lists multiple managed devices and release targets matched managed device`() = runBlocking {
        val secondAddress = "11:22:33:44:55:66"
        val unmanagedAddress = "99:88:77:66:55:44"
        val deviceStates = MutableStateFlow(
            mapOf(
                testAddress to DeviceState(bredrConnected = false, leConnected = false),
                secondAddress to DeviceState(bredrConnected = true, leConnected = false),
                unmanagedAddress to DeviceState(bredrConnected = true, leConnected = false),
            ),
        )
        val controller = FakeBluetoothController(
            onDisconnect = { addr ->
                deviceStates.value = deviceStates.value + (addr to DeviceState())
            },
        )
        val verifier = ProtocolVerifier(nowEpochSeconds = { fixedNow })
        val server = HolderHttpServer(
            host = "127.0.0.1",
            preferredPort = 0,
            deviceStates = deviceStates,
            managedDevicesProvider = {
                listOf(
                    SelectedBluetoothDevice("WH-1000XM4", testAddress),
                    SelectedBluetoothDevice("Pixel Buds Pro 2", secondAddress),
                )
            },
            isDeviceConnected = { addr -> deviceStates.value[addr]?.isConnected == true },
            bluetoothController = controller,
            pskStore = InMemoryPskStore(testPsk),
            verifier = verifier,
            nowEpochSeconds = { fixedNow },
        )

        val port = server.start()
        try {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build()
            val statusHttp = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/status"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, statusHttp.statusCode())
            val status = ProtocolJson.codec.decodeFromString<HolderStatusResponse>(statusHttp.body())
            assertEquals(2, status.devices.size)
            assertEquals(secondAddress, status.deviceAddress)
            assertTrue(status.connected)
            assertFalse(status.devices[0].connected)
            assertTrue(status.devices[1].connected)

            // Releasing an unmanaged device must be rejected with 409 Conflict
            val unmanagedReq = HmacSigner.signRequest(
                psk = testPsk,
                request = GrabRequest(
                    deviceAddress = unmanagedAddress,
                    requesterId = "pc-linux",
                    timestampEpochSec = fixedNow,
                    nonce = "00001111222233334444555566667777",
                ),
            )
            val unmanagedResp = postRelease(port, ProtocolJson.encodeRequest(unmanagedReq))
            assertEquals(409, unmanagedResp.statusCode())
            assertTrue(controller.disconnectCalls.isEmpty())

            // Releasing the second connected managed device disconnects only that address
            val releaseSecondReq = HmacSigner.signRequest(
                psk = testPsk,
                request = GrabRequest(
                    deviceAddress = secondAddress.lowercase(),
                    requesterId = "pc-linux",
                    timestampEpochSec = fixedNow,
                    nonce = "88889999aaaabbbbccccddddeeeeffff",
                ),
            )
            val releaseSecondResp = postRelease(port, ProtocolJson.encodeRequest(releaseSecondReq))
            assertEquals(200, releaseSecondResp.statusCode())
            assertEquals(listOf(secondAddress), controller.disconnectCalls)
        } finally {
            server.stop()
        }
    }

    private fun postRelease(port: Int, jsonBody: String): HttpResponse<String> {
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build()
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/release"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `default dual-stack server accepts status and release over ipv4 and ipv6`() = runBlocking {
        assertEquals("::", HolderHttpServer.DEFAULT_HOST)
        Assume.assumeTrue("IPv6 is disabled in this test environment", isIpv6DualStackSupported())

        val deviceStates = MutableStateFlow(
            mapOf(testAddress to DeviceState(bredrConnected = true)),
        )
        val controller = FakeBluetoothController(
            onDisconnect = { addr ->
                deviceStates.value = mapOf(addr to DeviceState(bredrConnected = false))
            },
        )
        val verifier = ProtocolVerifier(nowEpochSeconds = { fixedNow })
        val server = HolderHttpServer(
            preferredPort = 0,
            deviceStates = deviceStates,
            selectedDeviceProvider = { SelectedBluetoothDevice("WH-1000XM4", testAddress) },
            isDeviceConnected = { addr -> deviceStates.value[addr]?.isConnected == true },
            bluetoothController = controller,
            pskStore = InMemoryPskStore(testPsk),
            verifier = verifier,
            nowEpochSeconds = { fixedNow },
            pollIntervalMs = 20L,
        )

        val port = server.start()
        try {
            val grabClient = GrabClient(
                verifier = verifier,
                nowEpochSeconds = { fixedNow },
            )
            val ipv4Status = grabClient.fetchStatus("127.0.0.1", port).getOrThrow()
            assertEquals(port, ipv4Status.port)
            assertTrue(ipv4Status.connected)

            val ipv6Status = grabClient.fetchStatus("::1", port).getOrThrow()
            assertEquals(port, ipv6Status.port)
            assertTrue(ipv6Status.connected)

            val bracketedStatus = grabClient.fetchStatus("[::1]", port).getOrThrow()
            assertEquals(port, bracketedStatus.port)

            val releaseOutcome = grabClient.requestRelease(
                host = "::1",
                port = port,
                deviceAddress = testAddress,
                requesterId = "pc-linux",
                psk = testPsk,
            )
            assertTrue("Expected release over ::1 to succeed, got $releaseOutcome", releaseOutcome is ReleaseOutcome.Released)
            assertEquals(listOf(testAddress), controller.disconnectCalls)
        } finally {
            server.stop()
        }
    }

    private fun isIpv6DualStackSupported(): Boolean {
        if (System.getProperty("java.net.preferIPv4Stack")?.toBoolean() == true) {
            return false
        }
        return runCatching {
            ServerSocketChannel.open().use { it.bind(InetSocketAddress("::", 0)) }
            ServerSocketChannel.open().use { it.bind(InetSocketAddress("::1", 0)) }
            true
        }.getOrDefault(false)
    }
}
