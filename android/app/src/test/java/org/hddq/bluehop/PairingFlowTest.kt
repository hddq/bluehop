package org.hddq.bluehop

import kotlinx.coroutines.runBlocking
import org.hddq.bluehop.protocol.LocalDeviceIdentity
import org.hddq.bluehop.protocol.PairedPeer
import org.hddq.bluehop.protocol.PairingCrypto
import org.hddq.bluehop.server.ManualPairingTarget
import org.hddq.bluehop.server.PairingClient
import org.hddq.bluehop.server.PairingSessionServer
import org.hddq.bluehop.server.PairingSessionState
import org.hddq.bluehop.server.parseManualPairingAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
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
    fun formatHostPortFormatsIpv4HostnameAndIpv6() {
        assertEquals("192.168.1.42:43215", PairingClient.formatHostPort("192.168.1.42", 43215))
        assertEquals("laptop.local:8765", PairingClient.formatHostPort("laptop.local", 8765))
        assertEquals("[::1]:8765", PairingClient.formatHostPort("::1", 8765))
        assertEquals("[2001:db8::1]:43215", PairingClient.formatHostPort("[2001:db8::1]", 43215))
    }
}
