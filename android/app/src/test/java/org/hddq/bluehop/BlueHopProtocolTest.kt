package org.hddq.bluehop

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import org.hddq.bluehop.protocol.GrabRequest
import org.hddq.bluehop.protocol.GrabResponse
import org.hddq.bluehop.protocol.GrabResponseStatus
import org.hddq.bluehop.protocol.HmacSigner
import org.hddq.bluehop.protocol.NonceReplayGuard
import org.hddq.bluehop.protocol.PAIRING_PROTOCOL_VERSION
import org.hddq.bluehop.protocol.PairSessionStatus
import org.hddq.bluehop.protocol.PairingCrypto
import org.hddq.bluehop.protocol.ProtocolJson
import org.hddq.bluehop.protocol.ProtocolVerifier
import org.hddq.bluehop.protocol.VerificationFailureReason
import org.hddq.bluehop.protocol.VerificationResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPrivateKeySpec

class BlueHopProtocolTest {

    @Serializable
    private data class ReplayExpectation(
        val expectedValid: Boolean,
        val expectedReason: String? = null,
    )

    @Serializable
    private data class TestVector(
        val name: String,
        val kind: String,
        val canonicalPayload: String,
        val expectedNonce: String? = null,
        val payload: JsonObject,
        val expectedValid: Boolean,
        val expectedReason: String? = null,
        val replaySecondCheck: ReplayExpectation? = null,
    )

    @Serializable
    private data class SasCaseVector(
        val bytesHex: String,
        val sas: String,
    )

    @Serializable
    private data class PairingVector(
        val pairingVersion: Int,
        val sessionId: String,
        val requesterId: String,
        val responderId: String,
        val clientPrivateKeyHex: String,
        val serverPrivateKeyHex: String,
        val clientPub: String,
        val serverPub: String,
        val sharedSecretHex: String,
        val pskHex: String,
        val confirmKeyHex: String,
        val sas: String,
        val clientConfirm: String,
        val serverConfirm: String,
        val sasCases: List<SasCaseVector>,
    )

    @Serializable
    private data class VectorFile(
        val protocolVersion: Int,
        val maxSkewSeconds: Long,
        val pskHex: String,
        val nowEpochSec: Long,
        val vectors: List<TestVector>,
        val pairing: PairingVector,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadVectors(): VectorFile {
        val candidates = listOf(
            File("protocol/test-vectors.json"),
            File("../protocol/test-vectors.json"),
            File("../../protocol/test-vectors.json"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("Could not locate protocol/test-vectors.json from ${File(".").absolutePath}")
        return json.decodeFromString(file.readText())
    }

    private fun expectedResult(valid: Boolean, reason: String?): VerificationResult =
        if (valid) {
            VerificationResult.Valid
        } else {
            VerificationResult.Invalid(
                VerificationFailureReason.valueOf(requireNotNull(reason)),
            )
        }

    @Test
    fun protocolVectorsMatchKotlinImplementation() {
        val vectorFile = loadVectors()
        val psk = HmacSigner.fromHexOrNull(vectorFile.pskHex)
        assertNotNull("PSK hex must be valid", psk)
        requireNotNull(psk)

        val verifier = ProtocolVerifier(
            maxSkewSeconds = vectorFile.maxSkewSeconds,
            replayGuard = NonceReplayGuard(retentionSeconds = vectorFile.maxSkewSeconds * 2),
            nowEpochSeconds = { vectorFile.nowEpochSec },
        )

        for (vector in vectorFile.vectors) {
            when (vector.kind) {
                "request" -> {
                    val request = json.decodeFromJsonElement<GrabRequest>(vector.payload)
                    assertEquals(vector.name, vector.canonicalPayload, request.canonicalPayload())

                    val decoded = ProtocolJson.decodeRequest(ProtocolJson.encodeRequest(request))
                    val result = verifier.verifyRequest(decoded, psk)
                    assertEquals(
                        vector.name,
                        expectedResult(vector.expectedValid, vector.expectedReason),
                        result,
                    )

                    vector.replaySecondCheck?.let { replay ->
                        val replayResult = verifier.verifyRequest(decoded, psk)
                        assertEquals(
                            "${vector.name} replay",
                            expectedResult(replay.expectedValid, replay.expectedReason),
                            replayResult,
                        )
                    }
                }

                "response" -> {
                    val response = json.decodeFromJsonElement<GrabResponse>(vector.payload)
                    assertEquals(vector.name, vector.canonicalPayload, response.canonicalPayload())

                    val decoded = ProtocolJson.decodeResponse(ProtocolJson.encodeResponse(response))
                    val result = verifier.verifyResponse(
                        response = decoded,
                        psk = psk,
                        expectedNonce = vector.expectedNonce,
                    )
                    assertEquals(
                        vector.name,
                        expectedResult(vector.expectedValid, vector.expectedReason),
                        result,
                    )
                }

                else -> error("Unknown vector kind: ${vector.kind}")
            }
        }
    }

    @Test
    fun pairingVectorsMatchKotlinImplementation() {
        val pv = loadVectors().pairing
        assertEquals(PAIRING_PROTOCOL_VERSION, pv.pairingVersion)

        val decodedClientPub = PairingCrypto.decodePublicKey(pv.clientPub) as ECPublicKey
        val decodedServerPub = PairingCrypto.decodePublicKey(pv.serverPub) as ECPublicKey
        assertEquals(pv.clientPub, PairingCrypto.encodePublicKey(decodedClientPub))
        assertEquals(pv.serverPub, PairingCrypto.encodePublicKey(decodedServerPub))

        val keyFactory = KeyFactory.getInstance("EC")
        val clientPriv = keyFactory.generatePrivate(
            ECPrivateKeySpec(BigInteger(1, HmacSigner.fromHexOrNull(pv.clientPrivateKeyHex)!!), decodedClientPub.params),
        )
        val serverPriv = keyFactory.generatePrivate(
            ECPrivateKeySpec(BigInteger(1, HmacSigner.fromHexOrNull(pv.serverPrivateKeyHex)!!), decodedServerPub.params),
        )

        val clientShared = PairingCrypto.computeSharedSecret(clientPriv, decodedServerPub)
        val serverShared = PairingCrypto.computeSharedSecret(serverPriv, decodedClientPub)
        assertArrayEquals(clientShared, serverShared)
        assertEquals(pv.sharedSecretHex, HmacSigner.toHex(clientShared))

        val keys = PairingCrypto.derivePairingKeys(
            sharedSecret = clientShared,
            sessionId = pv.sessionId,
            clientPub = pv.clientPub,
            serverPub = pv.serverPub,
        )
        assertEquals(pv.pskHex, keys.pskHex)
        assertEquals(pv.confirmKeyHex, HmacSigner.toHex(keys.confirmKey))
        assertEquals(pv.sas, keys.sas)

        for (sc in pv.sasCases) {
            val raw = HmacSigner.fromHexOrNull(sc.bytesHex)!!
            assertEquals("SAS for ${sc.bytesHex}", sc.sas, PairingCrypto.sasFromBytes(raw))
        }

        val clientConfirm = PairingCrypto.computeConfirm(
            confirmKey = keys.confirmKey,
            role = PairingCrypto.CONFIRM_ROLE_CLIENT,
            sessionId = pv.sessionId,
            requesterId = pv.requesterId,
            responderId = pv.responderId,
        )
        assertEquals(pv.clientConfirm, clientConfirm)

        val serverConfirm = PairingCrypto.computeConfirm(
            confirmKey = keys.confirmKey,
            role = PairingCrypto.CONFIRM_ROLE_SERVER,
            sessionId = pv.sessionId,
            requesterId = pv.requesterId,
            responderId = pv.responderId,
        )
        assertEquals(pv.serverConfirm, serverConfirm)

        assertTrue(
            PairingCrypto.verifyConfirm(
                confirmKey = keys.confirmKey,
                role = PairingCrypto.CONFIRM_ROLE_CLIENT,
                sessionId = pv.sessionId,
                requesterId = pv.requesterId,
                responderId = pv.responderId,
                macHex = pv.clientConfirm,
            ),
        )
        assertTrue(
            PairingCrypto.verifyConfirm(
                confirmKey = keys.confirmKey,
                role = PairingCrypto.CONFIRM_ROLE_SERVER,
                sessionId = pv.sessionId,
                requesterId = pv.requesterId,
                responderId = pv.responderId,
                macHex = pv.serverConfirm,
            ),
        )
        assertFalse(
            PairingCrypto.verifyConfirm(
                confirmKey = keys.confirmKey,
                role = PairingCrypto.CONFIRM_ROLE_CLIENT,
                sessionId = pv.sessionId,
                requesterId = pv.requesterId,
                responderId = pv.responderId,
                macHex = pv.serverConfirm,
            ),
        )
        assertFalse(
            PairingCrypto.verifyConfirm(
                confirmKey = keys.confirmKey,
                role = PairingCrypto.CONFIRM_ROLE_CLIENT,
                sessionId = "other-session",
                requesterId = pv.requesterId,
                responderId = pv.responderId,
                macHex = pv.clientConfirm,
            ),
        )
        assertFalse(
            PairingCrypto.verifyConfirm(
                confirmKey = keys.confirmKey,
                role = PairingCrypto.CONFIRM_ROLE_CLIENT,
                sessionId = pv.sessionId,
                requesterId = pv.responderId,
                responderId = pv.requesterId,
                macHex = pv.clientConfirm,
            ),
        )
    }

    @Test
    fun decodePublicKeyRejectsInvalidAndNonP256Keys() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingCrypto.decodePublicKey("not-valid-base64!!!")
        }
        assertThrows(Exception::class.java) {
            PairingCrypto.decodePublicKey(
                java.util.Base64.getEncoder().encodeToString("bad-der".toByteArray()),
            )
        }
        val p384Gen = KeyPairGenerator.getInstance("EC")
        p384Gen.initialize(ECGenParameterSpec("secp384r1"))
        val p384PubBase64 = PairingCrypto.encodePublicKey(p384Gen.generateKeyPair().public)
        assertThrows(IllegalArgumentException::class.java) {
            PairingCrypto.decodePublicKey(p384PubBase64)
        }
    }

    @Test
    fun pairStatusJsonRejectsUnknownStatus() {
        val valid = ProtocolJson.decodePairStatusResponse("""{"version":2,"sessionId":"s1","status":"approved"}""")
        assertEquals(PairSessionStatus.APPROVED, valid.status)

        assertThrows(SerializationException::class.java) {
            ProtocolJson.decodePairStatusResponse("""{"version":2,"sessionId":"s1","status":"bogus"}""")
        }
    }

    @Test
    fun signingAndReplayPruningWorkAcrossWindow() {
        val psk = HmacSigner.fromHexOrNull(
            "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff",
        )!!
        var clock = 1_700_000_000L
        val guard = NonceReplayGuard(retentionSeconds = 60L)
        val verifier = ProtocolVerifier(
            maxSkewSeconds = 30L,
            replayGuard = guard,
            nowEpochSeconds = { clock },
        )

        val request = HmacSigner.signRequest(
            psk = psk,
            request = GrabRequest(
                deviceAddress = "AA:BB:CC:DD:EE:FF",
                requesterId = "desktop",
                timestampEpochSec = clock,
                nonce = "nonce-1",
            ),
        )
        assertEquals(VerificationResult.Valid, verifier.verifyRequest(request, psk))
        assertEquals(
            VerificationResult.Invalid(VerificationFailureReason.REPLAYED_NONCE),
            verifier.verifyRequest(request, psk),
        )

        clock += 61L
        val newer = HmacSigner.signRequest(
            psk = psk,
            request = GrabRequest(
                deviceAddress = "AA:BB:CC:DD:EE:FF",
                requesterId = "desktop",
                timestampEpochSec = clock,
                nonce = "nonce-2",
            ),
        )
        assertEquals(VerificationResult.Valid, verifier.verifyRequest(newer, psk))
        assertEquals(1, guard.size())

        val response = HmacSigner.signResponse(
            psk = psk,
            response = GrabResponse(
                status = GrabResponseStatus.ERROR,
                deviceAddress = "AA:BB:CC:DD:EE:FF",
                timestampEpochSec = clock,
                nonce = "nonce-2",
                message = "timeout",
            ),
        )
        assertEquals(
            VerificationResult.Valid,
            verifier.verifyResponse(response, psk, expectedNonce = "nonce-2"),
        )
    }
}
