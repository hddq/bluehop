package org.hddq.bluehop

import kotlinx.coroutines.runBlocking
import org.hddq.bluehop.protocol.LocalDeviceIdentity
import org.hddq.bluehop.protocol.PairedPeer
import org.hddq.bluehop.protocol.PairingCrypto
import org.hddq.bluehop.server.PairingClient
import org.hddq.bluehop.server.PairingSessionServer
import org.hddq.bluehop.server.PairingSessionState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
