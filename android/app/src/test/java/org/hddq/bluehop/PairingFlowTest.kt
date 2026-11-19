package org.hddq.bluehop

import android.bluetooth.BluetoothStatusCodes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.hddq.bluehop.bluetooth.BluetoothControlResult
import org.hddq.bluehop.bluetooth.BluetoothController
import org.hddq.bluehop.bluetooth.DeviceState
import org.hddq.bluehop.bluetooth.SelectedBluetoothDevice
import org.hddq.bluehop.protocol.LocalDeviceIdentity
import org.hddq.bluehop.protocol.PAIRING_PROTOCOL_VERSION
import org.hddq.bluehop.protocol.PairConfirmRequest
import org.hddq.bluehop.protocol.PairConfirmResponse
import org.hddq.bluehop.protocol.PairSessionStatus
import org.hddq.bluehop.protocol.PairStartRequest
import org.hddq.bluehop.protocol.PairStartResponse
import org.hddq.bluehop.protocol.PairStatusResponse
import org.hddq.bluehop.protocol.PairedPeer
import org.hddq.bluehop.protocol.PairingCrypto
import org.hddq.bluehop.protocol.ProtocolJson
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
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
    fun pairingCryptoDerivesSymmetricKeysSasAndValidatesConfirmations() {
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

        val sessionId = "00112233445566778899aabbccddeeff"
        val keysRequester = PairingCrypto.derivePairingKeys(
            sharedSecret = secretAB,
            sessionId = sessionId,
            clientPub = alicePub,
            serverPub = bobPub,
        )
        val keysResponder = PairingCrypto.derivePairingKeys(
            sharedSecret = secretBA,
            sessionId = sessionId,
            clientPub = alicePub,
            serverPub = bobPub,
        )
        assertEquals(32, keysRequester.psk.size)
        assertEquals(6, keysRequester.sas.length)
        assertEquals(keysRequester, keysResponder)

        val otherSessionKeys = PairingCrypto.derivePairingKeys(
            sharedSecret = secretAB,
            sessionId = "ffeeddccbbaa99887766554433221100",
            clientPub = alicePub,
            serverPub = bobPub,
        )
        assertFalse(keysRequester.psk.contentEquals(otherSessionKeys.psk))
        assertFalse(keysRequester.confirmKey.contentEquals(otherSessionKeys.confirmKey))

        val clientConfirm = PairingCrypto.computeConfirm(
            confirmKey = keysRequester.confirmKey,
            role = PairingCrypto.CONFIRM_ROLE_CLIENT,
            sessionId = sessionId,
            requesterId = requesterIdentity.deviceId,
            responderId = responderIdentity.deviceId,
        )
        assertTrue(
            PairingCrypto.verifyConfirm(
                confirmKey = keysResponder.confirmKey,
                role = PairingCrypto.CONFIRM_ROLE_CLIENT,
                sessionId = sessionId,
                requesterId = requesterIdentity.deviceId,
                responderId = responderIdentity.deviceId,
                macHex = clientConfirm,
            ),
        )
        assertFalse(
            PairingCrypto.verifyConfirm(
                confirmKey = otherSessionKeys.confirmKey,
                role = PairingCrypto.CONFIRM_ROLE_CLIENT,
                sessionId = sessionId,
                requesterId = requesterIdentity.deviceId,
                responderId = responderIdentity.deviceId,
                macHex = clientConfirm,
            ),
        )

        val serverConfirm = PairingCrypto.computeConfirm(
            confirmKey = keysResponder.confirmKey,
            role = PairingCrypto.CONFIRM_ROLE_SERVER,
            sessionId = sessionId,
            requesterId = requesterIdentity.deviceId,
            responderId = responderIdentity.deviceId,
        )
        assertTrue(
            PairingCrypto.verifyConfirm(
                confirmKey = keysRequester.confirmKey,
                role = PairingCrypto.CONFIRM_ROLE_SERVER,
                sessionId = sessionId,
                requesterId = requesterIdentity.deviceId,
                responderId = responderIdentity.deviceId,
                macHex = serverConfirm,
            ),
        )
    }

    @Test
    fun pairingSessionServerAndClientCompleteSasApproveAndMutualConfirm() = runBlocking {
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
            assertTrue(server.state.value is PairingSessionState.WaitingForPeer)

            val client = PairingClient(
                nowEpochSeconds = { 1_760_000_005L },
                pollIntervalMs = 20L,
            )
            val clientSasDeferred = kotlinx.coroutines.CompletableDeferred<Pair<String, String>>()
            val pairDeferred = async {
                client.pairWithHost(
                    host = "127.0.0.1",
                    port = port,
                    localIdentity = requesterIdentity,
                    onSasReady = { sas, name ->
                        clientSasDeferred.complete(sas to name)
                    },
                )
            }

            val pending = withTimeout(5_000L) {
                server.state.filterIsInstance<PairingSessionState.PendingConfirmation>().first()
            }
            val (clientSas, clientResponderName) = withTimeout(5_000L) {
                clientSasDeferred.await()
            }
            assertEquals(pending.sas, clientSas)
            assertEquals(responderIdentity.deviceName, clientResponderName)
            assertEquals(requesterIdentity.deviceName, pending.requesterName)
            assertNull("Peer must not be persisted before approval and confirm", responderPairedPeer)

            assertTrue(server.approveSession(pending.sessionId))
            val result = pairDeferred.await()
            assertTrue("Pairing should succeed: ${result.exceptionOrNull()}", result.isSuccess)
            val requesterPairedPeer = result.getOrThrow()

            val storedOnResponder = responderPairedPeer!!
            assertEquals(requesterIdentity.deviceId, storedOnResponder.peerId)
            assertEquals(requesterIdentity.deviceName, storedOnResponder.peerName)
            assertEquals(responderIdentity.deviceId, requesterPairedPeer.peerId)
            assertEquals(responderIdentity.deviceName, requesterPairedPeer.peerName)
            assertArrayEquals(storedOnResponder.psk, requesterPairedPeer.psk)
            assertTrue(server.state.value is PairingSessionState.Completed)

            // Completed session must reject new pairing attempts until reset
            val secondAttempt = client.pairWithHost(
                host = "127.0.0.1",
                port = port,
                localIdentity = requesterIdentity,
            )
            assertTrue(secondAttempt.isFailure)
        } finally {
            server.stop()
        }
    }

    @Test
    fun pairingSessionRejectDoesNotPersistAndCanReset() = runBlocking {
        var responderPairedPeer: PairedPeer? = null
        val server = PairingSessionServer(
            host = "127.0.0.1",
            preferredPort = 0,
            identityProvider = { responderIdentity },
            onPeerPaired = { peer -> responderPairedPeer = peer },
        )

        val port = server.start()
        try {
            val client = PairingClient(pollIntervalMs = 20L)
            val pairDeferred = async {
                client.pairWithHost(
                    host = "127.0.0.1",
                    port = port,
                    localIdentity = requesterIdentity,
                )
            }

            val pending = withTimeout(5_000L) {
                server.state.filterIsInstance<PairingSessionState.PendingConfirmation>().first()
            }
            assertTrue(server.rejectSession(pending.sessionId))

            val rejectedResult = pairDeferred.await()
            assertTrue(rejectedResult.isFailure)
            assertNull(responderPairedPeer)
            assertTrue(server.state.value is PairingSessionState.Rejected)

            // Resetting lets a fresh pairing session proceed
            server.resetSession()
            val retryDeferred = async {
                client.pairWithHost(
                    host = "127.0.0.1",
                    port = port,
                    localIdentity = requesterIdentity,
                )
            }
            val nextPending = withTimeout(5_000L) {
                server.state.filterIsInstance<PairingSessionState.PendingConfirmation>().first()
            }
            assertTrue(server.approveSession(nextPending.sessionId))
            val success = retryDeferred.await()
            assertTrue(success.isSuccess)
            assertArrayEquals(responderPairedPeer!!.psk, success.getOrThrow().psk)
        } finally {
            server.stop()
        }
    }

    @Test
    fun clientCancelNotifiesServerAndPreventsAccept() = runBlocking {
        var responderPairedPeer: PairedPeer? = null
        val server = PairingSessionServer(
            host = "127.0.0.1",
            preferredPort = 0,
            identityProvider = { responderIdentity },
            onPeerPaired = { peer -> responderPairedPeer = peer },
        )

        val port = server.start()
        try {
            val client = PairingClient(pollIntervalMs = 20L)
            val sasReady = CompletableDeferred<Unit>()
            val pairJob = launch {
                client.pairWithHost(
                    host = "127.0.0.1",
                    port = port,
                    localIdentity = requesterIdentity,
                    onSasReady = { _, _ -> sasReady.complete(Unit) },
                )
            }

            val pending = withTimeout(5_000L) {
                server.state.filterIsInstance<PairingSessionState.PendingConfirmation>().first()
            }
            withTimeout(5_000L) { sasReady.await() }

            pairJob.cancelAndJoin()

            val rejected = withTimeout(5_000L) {
                server.state.filterIsInstance<PairingSessionState.Rejected>().first()
            }
            assertTrue(rejected.message.contains(requesterIdentity.deviceName))
            assertFalse("Server must not accept a session cancelled by client", server.approveSession(pending.sessionId))
            assertNull(responderPairedPeer)

            server.resetSession()
            val retryDeferred = async {
                client.pairWithHost(
                    host = "127.0.0.1",
                    port = port,
                    localIdentity = requesterIdentity,
                )
            }
            val nextPending = withTimeout(5_000L) {
                server.state.filterIsInstance<PairingSessionState.PendingConfirmation>().first()
            }
            assertTrue(server.approveSession(nextPending.sessionId))
            val success = retryDeferred.await()
            assertTrue(success.isSuccess)
            assertArrayEquals(responderPairedPeer!!.psk, success.getOrThrow().psk)
        } finally {
            server.stop()
        }
    }

    @Test
    fun pairingSessionExpiresActivelyWithoutClientPolling(): Unit = runBlocking {
        val server = PairingSessionServer(
            host = "127.0.0.1",
            preferredPort = 0,
            identityProvider = { responderIdentity },
            onPeerPaired = {},
            sessionTtlSeconds = 1L,
        )

        val port = server.start()
        try {
            val clientKeyPair = PairingCrypto.generateEphemeralKeyPair()
            val startConn = (URI.create("http://127.0.0.1:$port/pair/start").toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            startConn.outputStream.use {
                it.write(
                    ProtocolJson.encodePairStartRequest(
                        PairStartRequest(
                            version = PAIRING_PROTOCOL_VERSION,
                            requesterId = requesterIdentity.deviceId,
                            requesterName = requesterIdentity.deviceName,
                            clientPub = PairingCrypto.encodePublicKey(clientKeyPair.public),
                        ),
                    ).toByteArray(Charsets.UTF_8),
                )
            }
            assertEquals(200, startConn.responseCode)
            startConn.disconnect()

            withTimeout(5_000L) {
                server.state.filterIsInstance<PairingSessionState.PendingConfirmation>().first()
                server.state.filterIsInstance<PairingSessionState.Expired>().first()
            }
            assertTrue(server.state.value is PairingSessionState.Expired)
        } finally {
            server.stop()
        }
    }

    @Test
    fun pairingSessionTtlExpiryDoesNotPersist() = runBlocking {
        var clock = 1_760_000_000L
        var responderPairedPeer: PairedPeer? = null
        val server = PairingSessionServer(
            host = "127.0.0.1",
            preferredPort = 0,
            identityProvider = { responderIdentity },
            onPeerPaired = { peer -> responderPairedPeer = peer },
            nowEpochSeconds = { clock },
            sessionTtlSeconds = 60L,
        )

        val port = server.start()
        try {
            val client = PairingClient(
                nowEpochSeconds = { clock },
                pollIntervalMs = 20L,
            )
            val pairDeferred = async {
                client.pairWithHost(
                    host = "127.0.0.1",
                    port = port,
                    localIdentity = requesterIdentity,
                )
            }

            withTimeout(5_000L) {
                server.state.filterIsInstance<PairingSessionState.PendingConfirmation>().first()
            }
            clock += 61L

            val result = pairDeferred.await()
            assertTrue(result.isFailure)
            assertNull(responderPairedPeer)
            assertTrue(server.state.value is PairingSessionState.Expired)
        } finally {
            server.stop()
        }
    }

    @Test
    fun tamperedClientConfirmRejectedWithoutPersisting() = runBlocking {
        var responderPairedPeer: PairedPeer? = null
        val server = PairingSessionServer(
            host = "127.0.0.1",
            preferredPort = 0,
            identityProvider = { responderIdentity },
            onPeerPaired = { peer -> responderPairedPeer = peer },
        )

        val port = server.start()
        try {
            val clientKeyPair = PairingCrypto.generateEphemeralKeyPair()
            val startConn = (URI.create("http://127.0.0.1:$port/pair/start").toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            startConn.outputStream.use {
                it.write(
                    ProtocolJson.encodePairStartRequest(
                        PairStartRequest(
                            version = PAIRING_PROTOCOL_VERSION,
                            requesterId = requesterIdentity.deviceId,
                            requesterName = requesterIdentity.deviceName,
                            clientPub = PairingCrypto.encodePublicKey(clientKeyPair.public),
                        ),
                    ).toByteArray(Charsets.UTF_8),
                )
            }
            assertEquals(200, startConn.responseCode)
            val startResp = ProtocolJson.decodePairStartResponse(startConn.inputStream.bufferedReader().readText())
            startConn.disconnect()

            assertTrue(server.approveSession(startResp.sessionId))

            val confirmConn = (URI.create("http://127.0.0.1:$port/pair/confirm").toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            confirmConn.outputStream.use {
                it.write(
                    ProtocolJson.encodePairConfirmRequest(
                        PairConfirmRequest(
                            version = PAIRING_PROTOCOL_VERSION,
                            sessionId = startResp.sessionId,
                            clientConfirm = "0".repeat(64),
                        ),
                    ).toByteArray(Charsets.UTF_8),
                )
            }
            assertEquals(401, confirmConn.responseCode)
            confirmConn.disconnect()
            assertNull("Tampered clientConfirm must never persist peer", responderPairedPeer)
            assertTrue(
                "Failed clientConfirm must not revoke user approval",
                server.state.value is PairingSessionState.ApprovedWaitingForConfirm,
            )

            // Subsequent legitimate confirmation for the same approved session must still succeed.
            val sharedSecret = PairingCrypto.computeSharedSecret(
                clientKeyPair.private,
                PairingCrypto.decodePublicKey(startResp.serverPub),
            )
            val keys = PairingCrypto.derivePairingKeys(
                sharedSecret = sharedSecret,
                sessionId = startResp.sessionId,
                clientPub = PairingCrypto.encodePublicKey(clientKeyPair.public),
                serverPub = startResp.serverPub,
            )
            val validClientConfirm = PairingCrypto.computeConfirm(
                confirmKey = keys.confirmKey,
                role = PairingCrypto.CONFIRM_ROLE_CLIENT,
                sessionId = startResp.sessionId,
                requesterId = requesterIdentity.deviceId,
                responderId = responderIdentity.deviceId,
            )
            val validConfirmConn = (URI.create("http://127.0.0.1:$port/pair/confirm").toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            validConfirmConn.outputStream.use {
                it.write(
                    ProtocolJson.encodePairConfirmRequest(
                        PairConfirmRequest(
                            version = PAIRING_PROTOCOL_VERSION,
                            sessionId = startResp.sessionId,
                            clientConfirm = validClientConfirm,
                        ),
                    ).toByteArray(Charsets.UTF_8),
                )
            }
            assertEquals(200, validConfirmConn.responseCode)
            validConfirmConn.disconnect()
            assertNotNull(responderPairedPeer)
            assertTrue(server.state.value is PairingSessionState.Completed)
        } finally {
            server.stop()
        }
    }

    @Test
    fun tamperedServerConfirmRejectedByClientWithoutPersisting() = runBlocking {
        val serverKeyPair = PairingCrypto.generateEphemeralKeyPair()
        val serverPubBase64 = PairingCrypto.encodePublicKey(serverKeyPair.public)
        val sessionId = "fake-session-123"

        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { serverSocket ->
            val serverThread = thread(isDaemon = true) {
                runCatching {
                    // 1. /pair/start
                    serverSocket.accept().use { s ->
                        val body = ProtocolJson.encodePairStartResponse(
                            PairStartResponse(
                                version = PAIRING_PROTOCOL_VERSION,
                                sessionId = sessionId,
                                responderId = responderIdentity.deviceId,
                                responderName = responderIdentity.deviceName,
                                serverPub = serverPubBase64,
                            ),
                        )
                        writeHttpJsonResponse(s, body)
                    }
                    // 2. /pair/status
                    serverSocket.accept().use { s ->
                        val body = ProtocolJson.encodePairStatusResponse(
                            PairStatusResponse(
                                version = PAIRING_PROTOCOL_VERSION,
                                sessionId = sessionId,
                                status = PairSessionStatus.APPROVED,
                            ),
                        )
                        writeHttpJsonResponse(s, body)
                    }
                    // 3. /pair/confirm with bogus serverConfirm
                    serverSocket.accept().use { s ->
                        val body = ProtocolJson.encodePairConfirmResponse(
                            PairConfirmResponse(
                                version = PAIRING_PROTOCOL_VERSION,
                                sessionId = sessionId,
                                serverConfirm = "f".repeat(64),
                            ),
                        )
                        writeHttpJsonResponse(s, body)
                    }
                }
            }

            val result = PairingClient(pollIntervalMs = 10L).pairWithHost(
                host = "127.0.0.1",
                port = serverSocket.localPort,
                localIdentity = requesterIdentity,
            )
            serverThread.join(2_000)
            assertTrue("Expected tampered serverConfirm to fail", result.isFailure)
        }
    }

    private fun writeHttpJsonResponse(socket: java.net.Socket, jsonBody: String) {
        socket.soTimeout = 2_000
        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
        var contentLength = 0
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        if (contentLength > 0) {
            val buf = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = reader.read(buf, read, contentLength - read)
                if (n == -1) break
                read += n
            }
        }
        val bodyBytes = jsonBody.toByteArray(Charsets.UTF_8)
        val header = (
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${bodyBytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            ).toByteArray(Charsets.US_ASCII)
        socket.getOutputStream().apply {
            write(header)
            write(bodyBytes)
            flush()
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
            val target = parseManualPairingAddress("127.0.0.1:$port").getOrThrow()
            val client = PairingClient(pollIntervalMs = 20L)
            val pairDeferred = async {
                client.pairWithHost(
                    host = target.host,
                    port = target.port,
                    localIdentity = requesterIdentity,
                )
            }
            val pending = withTimeout(5_000L) {
                server.state.filterIsInstance<PairingSessionState.PendingConfirmation>().first()
            }
            assertTrue(server.approveSession(pending.sessionId))
            val result = pairDeferred.await()
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
            val manualTarget = parseManualPairingAddress("[::1]:$pairingPort").getOrThrow()
            val pairingClient = PairingClient(
                nowEpochSeconds = { 1_760_000_005L },
                pollIntervalMs = 20L,
            )
            val pairDeferred = async {
                pairingClient.pairWithHost(
                    host = manualTarget.host,
                    port = manualTarget.port,
                    localIdentity = requesterIdentity,
                )
            }
            val pendingIpv6 = withTimeout(5_000L) {
                pairingServer.state.filterIsInstance<PairingSessionState.PendingConfirmation>().first()
            }
            assertTrue(pairingServer.approveSession(pendingIpv6.sessionId))
            val pairResult = pairDeferred.await()
            assertTrue("Pairing over ::1 should succeed: ${pairResult.exceptionOrNull()}", pairResult.isSuccess)
            val requesterPeer = pairResult.getOrThrow()
            val responderPeer = pairedOverIpv6!!
            assertArrayEquals(responderPeer.psk, requesterPeer.psk)

            // Also verify dual-stack PairingSessionServer accepts IPv4 127.0.0.1 after resetting session
            pairingServer.resetSession()
            val ipv4PairDeferred = async {
                pairingClient.pairWithHost(
                    host = "127.0.0.1",
                    port = pairingPort,
                    localIdentity = requesterIdentity,
                )
            }
            val pendingIpv4 = withTimeout(5_000L) {
                pairingServer.state.filterIsInstance<PairingSessionState.PendingConfirmation>().first()
            }
            assertTrue(pairingServer.approveSession(pendingIpv4.sessionId))
            val ipv4PairResult = ipv4PairDeferred.await()
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
}
