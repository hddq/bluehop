package org.hddq.bluehop

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import org.hddq.bluehop.protocol.GrabRequest
import org.hddq.bluehop.protocol.GrabResponse
import org.hddq.bluehop.protocol.GrabResponseStatus
import org.hddq.bluehop.protocol.HmacSigner
import org.hddq.bluehop.protocol.NonceReplayGuard
import org.hddq.bluehop.protocol.ProtocolJson
import org.hddq.bluehop.protocol.ProtocolVerifier
import org.hddq.bluehop.protocol.VerificationFailureReason
import org.hddq.bluehop.protocol.VerificationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

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
    private data class VectorFile(
        val protocolVersion: Int,
        val maxSkewSeconds: Long,
        val pskHex: String,
        val nowEpochSec: Long,
        val vectors: List<TestVector>,
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
