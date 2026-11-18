package org.hddq.bluehop

import android.bluetooth.BluetoothStatusCodes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.hddq.bluehop.bluetooth.BluetoothControlResult
import org.hddq.bluehop.bluetooth.BluetoothController
import org.hddq.bluehop.bluetooth.DeviceState
import org.hddq.bluehop.bluetooth.SelectedBluetoothDevice
import org.hddq.bluehop.protocol.LocalDeviceIdentity
import org.hddq.bluehop.protocol.PairedPeer
import org.hddq.bluehop.protocol.PairingCrypto
import org.hddq.bluehop.protocol.ProtocolVerifier
import org.hddq.bluehop.protocol.PskStore
import org.hddq.bluehop.server.GrabClient
import org.hddq.bluehop.server.HolderHttpServer
import org.hddq.bluehop.server.ManualPairingTarget
import org.hddq.bluehop.server.NsdPeerDiscovery
import org.hddq.bluehop.server.PairingClient
import org.hddq.bluehop.server.PairingSessionServer
import org.hddq.bluehop.server.PairingSessionState
import org.hddq.bluehop.server.ReleaseOutcome
import org.hddq.bluehop.server.filterAndSortLocalIpAddresses
import org.hddq.bluehop.server.parseManualPairingAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.channels.ServerSocketChannel
import kotlin.concurrent.thread

class PairingFlowTest {

    private val responderIdentity = LocalDeviceIdentity(
        deviceId = "responder-uuid-001",
        deviceName = "Pixel 9",
    )
    private val requesterIdentity = LocalDeviceIdentity(
        deviceId = "requester-uuid-002",
        deviceName = "Pixel Tablet",
    )

    @Test
    fun pairingCryptoDerivesSymmetricPskAndValidatesProofs() {
        val alice = PairingCrypto.generateEphemeralKeyPair()
        val bob = PairingCrypto.generateEphemeralKeyPair()

        val alicePub = PairingCrypto.encodePublicKey(alice.public)
        val bobPub = PairingCrypto.encodePublicKey(bob.public)

        val secretAB = PairingCrypto.computeSharedSecret(
            alice.private,
            PairingCrypto.decodePublicKey(bobPub),
        )
        val secretBA = PairingCrypto.computeSharedSecret(
            bob.private,
            PairingCrypto.decodePublicKey(alicePub),
        )
        assertArrayEquals(secretAB, secretBA)

        val sessionNonce = "00112233445566778899aabbccddeeff"
        val pskRequester = PairingCrypto.derivePairingPsk(
            sharedSecret = secretAB,
            pin = "042-719",
            sessionNonce = sessionNonce,
            firstId = requesterIdentity.deviceId,
            secondId = responderIdentity.deviceId,
        )
        val pskResponder = PairingCrypto.derivePairingPsk(
            sharedSecret = secretBA,
            pin = "042719",
            sessionNonce = sessionNonce,
            firstId = responderIdentity.deviceId,
            secondId = requesterIdentity.deviceId,
        )
        assertEquals(32, pskRequester.size)
        assertArrayEquals(pskRequester, pskResponder)

        val wrongPinPsk = PairingCrypto.derivePairingPsk(
            sharedSecret = secretAB,
            pin = "042718",
            sessionNonce = sessionNonce,
            firstId = requesterIdentity.deviceId,
            secondId = responderIdentity.deviceId,
        )
        assertFalse(pskRequester.contentEquals(wrongPinPsk))

        val clientProof = PairingCrypto.computeClientProof(
            psk = pskRequester,
            sessionNonce = sessionNonce,
            requesterId = requesterIdentity.deviceId,
            responderId = responderIdentity.deviceId,
            requesterPublicKeyBase64 = alicePub,
            responderPublicKeyBase64 = bobPub,
        )
        assertTrue(
            PairingCrypto.verifyClientProof(
                psk = pskResponder,
                sessionNonce = sessionNonce,
                requesterId = requesterIdentity.deviceId,
                responderId = responderIdentity.deviceId,
                requesterPublicKeyBase64 = alicePub,
                responderPublicKeyBase64 = bobPub,
                proofHex = clientProof,
            ),
        )
        assertFalse(
            PairingCrypto.verifyClientProof(
                psk = wrongPinPsk,
                sessionNonce = sessionNonce,
                requesterId = requesterIdentity.deviceId,
                responderId = responderIdentity.deviceId,
                requesterPublicKeyBase64 = alicePub,
                responderPublicKeyBase64 = bobPub,
                proofHex = clientProof,
            ),
        )

        val serverProof = PairingCrypto.computeServerProof(
            psk = pskResponder,
            sessionNonce = sessionNonce,
            requesterId = requesterIdentity.deviceId,
            responderId = responderIdentity.deviceId,
            requesterPublicKeyBase64 = alicePub,
            responderPublicKeyBase64 = bobPub,
        )
        assertTrue(
            PairingCrypto.verifyServerProof(
                psk = pskRequester,
                sessionNonce = sessionNonce,
                requesterId = requesterIdentity.deviceId,
                responderId = responderIdentity.deviceId,
                requesterPublicKeyBase64 = alicePub,
                responderPublicKeyBase64 = bobPub,
                proofHex = serverProof,
            ),
        )
    }

    @Test
    fun pairingSessionServerAndClientCompleteMutualKeyExchange() = runBlocking {
        var responderPairedPeer: PairedPeer? = null
        val server = PairingSessionServer(
            host = "127.0.0.1",
            preferredPort = 0,
            identityProvider = { responderIdentity },
            onPeerPaired = { peer -> responderPairedPeer = peer },
            nowEpochSeconds = { 1_760_000_000L },
        )

        val port = server.start()
        try {
            val waiting = server.state.value as PairingSessionState.WaitingForPeer
            assertEquals(PairingSessionServer.DEFAULT_MAX_ATTEMPTS, waiting.attemptsRemaining)

            val client = PairingClient(nowEpochSeconds = { 1_760_000_005L })
            val result = client.pairWithHost(
                host = "127.0.0.1",
                port = port,
                pin = waiting.pin,
                localIdentity = requesterIdentity,
            )
            assertTrue("Pairing should succeed: ${result.exceptionOrNull()}", result.isSuccess)
            val requesterPairedPeer = result.getOrThrow()

            val storedOnResponder = responderPairedPeer!!
            assertEquals(requesterIdentity.deviceId, storedOnResponder.peerId)
            assertEquals(requesterIdentity.deviceName, storedOnResponder.peerName)
            assertEquals(responderIdentity.deviceId, requesterPairedPeer.peerId)
            assertEquals(responderIdentity.deviceName, requesterPairedPeer.peerName)
            assertArrayEquals(storedOnResponder.psk, requesterPairedPeer.psk)
            assertTrue(server.state.value is PairingSessionState.Completed)

            // Session must be single-use after completion
            val secondAttempt = client.pairWithHost(
                host = "127.0.0.1",
                port = port,
                pin = waiting.pin,
                localIdentity = requesterIdentity,
            )
            assertTrue(secondAttempt.isFailure)
        } finally {
            server.stop()
        }
    }

    @Test
    fun pairingSessionServerLocksOutAfterFiveWrongPinsAndRegeneratesSession() = runBlocking {
        var responderPairedPeer: PairedPeer? = null
        val server = PairingSessionServer(
            host = "127.0.0.1",
            preferredPort = 0,
            identityProvider = { responderIdentity },
            onPeerPaired = { peer -> responderPairedPeer = peer },
        )

        val port = server.start()
        try {
            val initial = server.state.value as PairingSessionState.WaitingForPeer
            val wrongPin = if (initial.pin == "000000") "999999" else "000000"
            val client = PairingClient()

            for (attempt in 1 until PairingSessionServer.DEFAULT_MAX_ATTEMPTS) {
                val fail = client.pairWithHost(
                    host = "127.0.0.1",
                    port = port,
                    pin = wrongPin,
                    localIdentity = requesterIdentity,
                )
                assertTrue(fail.isFailure)
                val state = server.state.value as PairingSessionState.WaitingForPeer
                assertEquals(
                    PairingSessionServer.DEFAULT_MAX_ATTEMPTS - attempt,
                    state.attemptsRemaining,
                )
            }

            // 5th wrong PIN locks out the session
            val fifthFail = client.pairWithHost(
                host = "127.0.0.1",
                port = port,
                pin = wrongPin,
                localIdentity = requesterIdentity,
            )
            assertTrue(fifthFail.isFailure)
            assertTrue(server.state.value is PairingSessionState.LockedOut)

            // Even the old correct PIN must now be refused
            val afterLockout = client.pairWithHost(
                host = "127.0.0.1",
                port = port,
                pin = initial.pin,
                localIdentity = requesterIdentity,
            )
            assertTrue(afterLockout.isFailure)

            // Regenerating the session yields a fresh PIN that succeeds
            server.regenerateSession()
            val refreshed = server.state.value as PairingSessionState.WaitingForPeer
            val success = client.pairWithHost(
                host = "127.0.0.1",
                port = port,
                pin = refreshed.pin,
                localIdentity = requesterIdentity,
            )
            assertTrue(success.isSuccess)
            assertArrayEquals(responderPairedPeer!!.psk, success.getOrThrow().psk)
        } finally {
            server.stop()
        }
    }

    @Test
    fun parseManualPairingAddressParsesValidHostsAndPorts() {
        assertEquals(
            ManualPairingTarget("192.168.1.42", 43215),
            parseManualPairingAddress("192.168.1.42:43215").getOrThrow(),
        )
        assertEquals(
            ManualPairingTarget("192.168.1.42", 43215),
            parseManualPairingAddress(" 192.168.1.42:43215 ").getOrThrow(),
        )
        assertEquals(
            ManualPairingTarget("laptop.local", 9000),
            parseManualPairingAddress("http://laptop.local:9000/").getOrThrow(),
        )
        assertEquals(
            ManualPairingTarget("::1", 8765),
            parseManualPairingAddress("[::1]:8765").getOrThrow(),
        )
        assertEquals(
            ManualPairingTarget("2001:db8::1", 43215),
            parseManualPairingAddress("[2001:db8::1]:43215").getOrThrow(),
        )
    }

    @Test
    fun parseManualPairingAddressRejectsInvalidInputs() {
        val invalidInputs = listOf(
            "",
            "   ",
            "192.168.1.42",
            "http://laptop.local/",
            "[2001:db8::1]",
            "2001:db8::1",
            "https://192.168.1.42:8765",
            "192.168.1.42:",
            ":8765",
            "192.168.1.42:0",
            "192.168.1.42:65536",
            "192.168.1.42:abc",
            "192.168.1.42/pair",
            "192.168.1.42?a=b",
            "user@192.168.1.42:8765",
            "192.168.1.42 8765",
            "[::1",
            "[::1]extra",
        )
        for (input in invalidInputs) {
            assertTrue("Expected '$input' to fail", parseManualPairingAddress(input).isFailure)
        }
    }

    @Test
    fun pairingWithManuallyEnteredAddressSucceeds() = runBlocking {
        var responderPairedPeer: PairedPeer? = null
        val server = PairingSessionServer(
            host = "127.0.0.1",
            preferredPort = 0,
            identityProvider = { responderIdentity },
            onPeerPaired = { peer -> responderPairedPeer = peer },
        )

        val port = server.start()
        try {
            val waiting = server.state.value as PairingSessionState.WaitingForPeer
            val target = parseManualPairingAddress("127.0.0.1:$port").getOrThrow()
            val client = PairingClient()
            val result = client.pairWithHost(
                host = target.host,
                port = target.port,
                pin = waiting.pin,
                localIdentity = requesterIdentity,
            )
            assertTrue("Pairing should succeed: ${result.exceptionOrNull()}", result.isSuccess)
            assertArrayEquals(responderPairedPeer!!.psk, result.getOrThrow().psk)
        } finally {
            server.stop()
        }
    }

    @Test
    fun pairingClientRejectsOversizedResponseBodies() = runBlocking {
        for (statusLine in listOf("200 OK", "500 Internal Server Error")) {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { serverSocket ->
                val serverThread = thread(isDaemon = true) {
                    runCatching {
                        serverSocket.accept().use { clientSocket ->
                            clientSocket.soTimeout = 2_000
                            val reader = clientSocket.getInputStream().bufferedReader(Charsets.US_ASCII)
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                            }
                            val payloadSize = PairingClient.MAX_RESPONSE_BYTES + 1
                            val header = (
                                "HTTP/1.1 $statusLine\r\n" +
                                    "Content-Type: application/json\r\n" +
                                    "Content-Length: $payloadSize\r\n" +
                                    "Connection: close\r\n\r\n"
                                ).toByteArray(Charsets.US_ASCII)
                            val out = clientSocket.getOutputStream()
                            out.write(header)
                            out.write(ByteArray(payloadSize) { 'a'.code.toByte() })
                            out.flush()
                            clientSocket.shutdownOutput()
                            while (reader.read() != -1) {
                                // Wait for client to close cleanly before closing server socket
                            }
                        }
                    }
                }
                val result = PairingClient().pairWithHost(
                    host = "127.0.0.1",
                    port = serverSocket.localPort,
                    pin = "123456",
                    localIdentity = requesterIdentity,
                )
                serverThread.join(2_000)
                assertTrue("Expected failure for $statusLine", result.isFailure)
                val message = result.exceptionOrNull()?.message.orEmpty()
                assertTrue(
                    "Expected size limit message for $statusLine, got: $message",
                    "${PairingClient.MAX_RESPONSE_BYTES}" in message,
                )
            }
        }
    }

    @Test
    fun pairingClientEnforcesOverallRequestDeadline() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { serverSocket ->
            val serverThread = thread(isDaemon = true) {
                runCatching {
                    serverSocket.accept().use { clientSocket ->
                        clientSocket.soTimeout = 2_000
                        val reader = clientSocket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val out = clientSocket.getOutputStream()
                        out.write(
                            (
                                "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: application/json\r\n" +
                                    "Content-Length: 100\r\n" +
                                    "Connection: close\r\n\r\n"
                                ).toByteArray(Charsets.US_ASCII),
                        )
                        out.flush()
                        repeat(40) {
                            Thread.sleep(50)
                            out.write('{'.code)
                            out.flush()
                        }
                    }
                }
            }
            val startNanos = System.nanoTime()
            val result = PairingClient(
                connectTimeoutMs = 1_000,
                readTimeoutMs = 2_000,
                totalTimeoutMs = 250,
            ).pairWithHost(
                host = "127.0.0.1",
                port = serverSocket.localPort,
                pin = "123456",
                localIdentity = requesterIdentity,
            )
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L
            serverThread.join(2_000)
            assertTrue("Expected timeout failure, got $result", result.isFailure)
            assertTrue(
                "Expected SocketTimeoutException, got ${result.exceptionOrNull()}",
                result.exceptionOrNull() is java.net.SocketTimeoutException,
            )
            assertTrue("Expected deadline to abort quickly, took ${elapsedMs}ms", elapsedMs < 1_500L)
        }
    }

    @Test
    fun formatHostPortFormatsIpv4HostnameAndIpv6() {
        assertEquals("192.168.1.42:43215", PairingClient.formatHostPort("192.168.1.42", 43215))
        assertEquals("laptop.local:8765", PairingClient.formatHostPort("laptop.local", 8765))
        assertEquals("[::1]:8765", PairingClient.formatHostPort("::1", 8765))
        assertEquals("[2001:db8::1]:43215", PairingClient.formatHostPort("[2001:db8::1]", 43215))
        assertEquals(
            "[2001:0db8:85a3:0000:0000:8a2e:0370:7334]:8765",
            PairingClient.formatHostPort("2001:0db8:85a3:0000:0000:8a2e:0370:7334", 8765),
        )
    }

    @Test
    fun formatBaseUrlAndManualAddressSupportIpv4AndFullIpv6() {
        val grabClient = GrabClient()
        val pairingClient = PairingClient()

        assertEquals("http://192.168.1.42:8765", grabClient.formatBaseUrl("192.168.1.42", 8765))
        assertEquals("http://[::1]:8765", grabClient.formatBaseUrl("::1", 8765))
        assertEquals("http://[::1]:8765", grabClient.formatBaseUrl("[::1]", 8765))
        assertEquals(
            "http://[2001:0db8:85a3:0000:0000:8a2e:0370:7334]:8765",
            grabClient.formatBaseUrl("2001:0db8:85a3:0000:0000:8a2e:0370:7334", 8765),
        )
        assertEquals(
            "http://[2001:0db8:85a3:0000:0000:8a2e:0370:7334]:8765",
            pairingClient.formatBaseUrl("[2001:0db8:85a3:0000:0000:8a2e:0370:7334]", 8765),
        )

        assertEquals(
            ManualPairingTarget("2001:0db8:85a3:0000:0000:8a2e:0370:7334", 43215),
            parseManualPairingAddress("[2001:0db8:85a3:0000:0000:8a2e:0370:7334]:43215").getOrThrow(),
        )
        assertEquals(
            ManualPairingTarget("::1", 8765),
            parseManualPairingAddress("http://[::1]:8765/").getOrThrow(),
        )
    }

    @Test
    fun selectPreferredHostAndFilterLocalIpsSupportIpv4AndRoutableIpv6() {
        val ipv4Lan = InetAddress.getByName("192.168.1.25")
        val ipv4LinkLocal = InetAddress.getByName("169.254.10.20")
        val ipv4Loopback = InetAddress.getByName("127.0.0.1")
        val ipv6Loopback = InetAddress.getByName("::1")
        val ipv6LinkLocal = InetAddress.getByName("fe80::1")
        val ipv6SiteLocal = InetAddress.getByName("fec0::1")
        val ipv6Multicast = InetAddress.getByName("ff02::1")
        val ipv6Teredo = InetAddress.getByName("2001:0000::1")
        val ipv6Ula = InetAddress.getByName("fd12:3456:789a::1")
        val ipv6Global = InetAddress.getByName("2001:0db8:85a3:0000:0000:8a2e:0370:7334")
        val ipv6Temp = InetAddress.getByName("2001:0db8:85a3::9999")

        assertEquals(
            ipv4Lan.hostAddress,
            NsdPeerDiscovery.selectPreferredHost(listOf(ipv6Loopback, ipv6Global, ipv4Lan)),
        )
        assertEquals(
            ipv6Ula.hostAddress,
            NsdPeerDiscovery.selectPreferredHost(listOf(ipv4Loopback, ipv4LinkLocal, ipv6LinkLocal, ipv6Global, ipv6Ula)),
        )
        assertEquals(
            ipv6Global.hostAddress,
            NsdPeerDiscovery.selectPreferredHost(listOf(ipv6Loopback, ipv6LinkLocal, ipv6Global)),
        )

        val filtered = filterAndSortLocalIpAddresses(
            addresses = listOf(
                ipv6Global,
                ipv6Temp,
                ipv6Loopback,
                ipv6LinkLocal,
                ipv6SiteLocal,
                ipv6Multicast,
                ipv6Teredo,
                ipv4LinkLocal,
                ipv4Loopback,
                ipv6Ula,
                ipv4Lan,
            ),
            excludedAddresses = setOf(ipv6Temp),
        )
        assertEquals(
            listOf(ipv4Lan.hostAddress, ipv6Ula.hostAddress, ipv6Global.hostAddress),
            filtered,
        )
    }

    @Test
    fun pairingSessionServerDefaultDualStackPairsAndReleasesOverIpv6AndIpv4() = runBlocking {
        Assume.assumeTrue("IPv6 is disabled in this test environment", isIpv6DualStackSupported())

        var pairedOverIpv6: PairedPeer? = null
        val pairingServer = PairingSessionServer(
            preferredPort = 0,
            identityProvider = { responderIdentity },
            onPeerPaired = { peer -> pairedOverIpv6 = peer },
            nowEpochSeconds = { 1_760_000_000L },
        )

        val pairingPort = pairingServer.start()
        try {
            val waiting = pairingServer.state.value as PairingSessionState.WaitingForPeer
            val manualTarget = parseManualPairingAddress("[::1]:$pairingPort").getOrThrow()
            val pairingClient = PairingClient(nowEpochSeconds = { 1_760_000_005L })
            val pairResult = pairingClient.pairWithHost(
                host = manualTarget.host,
                port = manualTarget.port,
                pin = waiting.pin,
                localIdentity = requesterIdentity,
            )
            assertTrue("Pairing over ::1 should succeed: ${pairResult.exceptionOrNull()}", pairResult.isSuccess)
            val requesterPeer = pairResult.getOrThrow()
            val responderPeer = pairedOverIpv6!!
            assertArrayEquals(responderPeer.psk, requesterPeer.psk)

            // Also verify dual-stack PairingSessionServer accepts IPv4 127.0.0.1 after regenerating session
            pairingServer.regenerateSession()
            val refreshed = pairingServer.state.value as PairingSessionState.WaitingForPeer
            val ipv4PairResult = pairingClient.pairWithHost(
                host = "127.0.0.1",
                port = pairingPort,
                pin = refreshed.pin,
                localIdentity = requesterIdentity,
            )
            assertTrue("Pairing over 127.0.0.1 on :: server should succeed", ipv4PairResult.isSuccess)

            // Follow up with an actual HolderHttpServer release request over ::1 using the paired PSK
            val headphoneMac = "AC:80:0A:CD:82:A1"
            val deviceStates = MutableStateFlow(
                mapOf(headphoneMac to DeviceState(bredrConnected = true)),
            )
            val disconnectCalls = mutableListOf<String>()
            val holderServer = HolderHttpServer(
                preferredPort = 0,
                deviceStates = deviceStates,
                selectedDeviceProvider = { SelectedBluetoothDevice("WH-1000XM4", headphoneMac) },
                isDeviceConnected = { addr -> deviceStates.value[addr]?.isConnected == true },
                bluetoothController = object : BluetoothController {
                    override fun connect(address: String) =
                        BluetoothControlResult(BluetoothStatusCodes.SUCCESS, "OK")

                    override fun disconnect(address: String): BluetoothControlResult {
                        disconnectCalls += address
                        deviceStates.value = mapOf(address to DeviceState(bredrConnected = false))
                        return BluetoothControlResult(BluetoothStatusCodes.SUCCESS, "OK")
                    }
                },
                pskStore = object : PskStore {
                    override suspend fun getOrCreatePsk(): ByteArray = ByteArray(32)
                    override suspend fun setPsk(psk: ByteArray) = Unit
                    override suspend fun clearPsk() = Unit
                },
                peerPskResolver = { requesterId ->
                    if (requesterId == requesterIdentity.deviceId) responderPeer.psk else null
                },
                verifier = ProtocolVerifier(nowEpochSeconds = { 1_760_000_010L }),
                nowEpochSeconds = { 1_760_000_010L },
                pollIntervalMs = 20L,
            )
            val holderPort = holderServer.start()
            try {
                val grabClient = GrabClient(
                    verifier = ProtocolVerifier(nowEpochSeconds = { 1_760_000_010L }),
                    nowEpochSeconds = { 1_760_000_010L },
                )
                val releaseOutcome = grabClient.requestRelease(
                    host = "::1",
                    port = holderPort,
                    deviceAddress = headphoneMac,
                    requesterId = requesterIdentity.deviceId,
                    psk = requesterPeer.psk,
                )
                assertTrue("Expected ReleaseOutcome.Released over ::1, got $releaseOutcome", releaseOutcome is ReleaseOutcome.Released)
                assertEquals(listOf(headphoneMac), disconnectCalls)
            } finally {
                holderServer.stop()
            }
        } finally {
            pairingServer.stop()
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
