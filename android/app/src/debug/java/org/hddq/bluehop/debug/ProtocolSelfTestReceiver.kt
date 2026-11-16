package org.hddq.bluehop.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.hddq.bluehop.data.SelectedDeviceRepository
import org.hddq.bluehop.protocol.GrabRequest
import org.hddq.bluehop.protocol.GrabResponse
import org.hddq.bluehop.protocol.GrabResponseStatus
import org.hddq.bluehop.protocol.HmacSigner
import org.hddq.bluehop.protocol.KeystorePskStore
import org.hddq.bluehop.protocol.ProtocolJson
import org.hddq.bluehop.protocol.ProtocolVerifier
import org.hddq.bluehop.protocol.VerificationFailureReason
import org.hddq.bluehop.protocol.VerificationResult

private const val TAG = "BlueHopProtocol"
private const val ACTION_DUMP_PSK = "org.hddq.bluehop.DEBUG_DUMP_PSK"

class ProtocolSelfTestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val action = intent.action
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val pskStore = KeystorePskStore.from(appContext)
                if (action == ACTION_DUMP_PSK) {
                    val psk = pskStore.getOrCreatePsk()
                    val selected = SelectedDeviceRepository.from(appContext).selectedDevice.first()
                    Log.i(
                        TAG,
                        "DEBUG_PSK hex=${HmacSigner.toHex(psk)} device=${selected?.address ?: "none"}",
                    )
                    return@launch
                }

                val psk1 = pskStore.getOrCreatePsk()
                val psk2 = pskStore.getOrCreatePsk()
                check(psk1.size == KeystorePskStore.PSK_BYTE_LENGTH) { "Unexpected PSK length" }
                check(psk1.contentEquals(psk2)) { "Keystore PSK round-trip mismatch" }
                Log.i(TAG, "Keystore PSK round-trip OK (${psk1.size} bytes)")

                val now = System.currentTimeMillis() / 1_000L
                val nonce = HmacSigner.generateNonce()
                val signedRequest = HmacSigner.signRequest(
                    psk = psk1,
                    request = GrabRequest(
                        deviceAddress = "AC:80:0A:CD:82:A1",
                        requesterId = "adb-self-test",
                        timestampEpochSec = now,
                        nonce = nonce,
                    ),
                )
                val decodedRequest = ProtocolJson.decodeRequest(ProtocolJson.encodeRequest(signedRequest))

                val verifier = ProtocolVerifier()
                val firstCheck = verifier.verifyRequest(decodedRequest, psk2)
                check(firstCheck == VerificationResult.Valid) { "Expected Valid request, got $firstCheck" }
                val replayCheck = verifier.verifyRequest(decodedRequest, psk2)
                check(
                    replayCheck == VerificationResult.Invalid(VerificationFailureReason.REPLAYED_NONCE),
                ) { "Expected REPLAYED_NONCE, got $replayCheck" }

                val tampered = decodedRequest.copy(requesterId = "tampered")
                val tamperedCheck = verifier.verifyRequest(tampered, psk2)
                check(
                    tamperedCheck == VerificationResult.Invalid(VerificationFailureReason.INVALID_SIGNATURE),
                ) { "Expected INVALID_SIGNATURE, got $tamperedCheck" }

                val signedResponse = HmacSigner.signResponse(
                    psk = psk1,
                    response = GrabResponse(
                        status = GrabResponseStatus.OK,
                        deviceAddress = "AC:80:0A:CD:82:A1",
                        timestampEpochSec = now,
                        nonce = nonce,
                        message = "disconnected",
                    ),
                )
                val decodedResponse = ProtocolJson.decodeResponse(ProtocolJson.encodeResponse(signedResponse))
                val responseCheck = verifier.verifyResponse(decodedResponse, psk2, expectedNonce = nonce)
                check(responseCheck == VerificationResult.Valid) {
                    "Expected Valid response, got $responseCheck"
                }

                Log.i(TAG, "Protocol self-test PASSED (request, replay, tamper, response)")
            } catch (t: Throwable) {
                Log.e(TAG, "Protocol self-test FAILED", t)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
