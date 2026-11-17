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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import org.hddq.bluehop.protocol.HmacSigner
import org.hddq.bluehop.protocol.LocalDeviceIdentity
import org.hddq.bluehop.protocol.PROTOCOL_VERSION
import org.hddq.bluehop.protocol.PairInfoResponse
import org.hddq.bluehop.protocol.PairRequest
import org.hddq.bluehop.protocol.PairResponse
import org.hddq.bluehop.protocol.PairResponseStatus
import org.hddq.bluehop.protocol.PairedPeer
import org.hddq.bluehop.protocol.PairingCrypto
import org.hddq.bluehop.protocol.ProtocolJson
import java.security.KeyPair

sealed interface PairingSessionState {
    data object Idle : PairingSessionState

    data class WaitingForPeer(
        val pin: String,
        val port: Int,
        val attemptsRemaining: Int,
    ) : PairingSessionState

    data class Completed(
        val peer: PairedPeer,
    ) : PairingSessionState

    data class LockedOut(
        val port: Int,
    ) : PairingSessionState
}

private data class ActiveSession(
    val pin: String,
    val sessionNonce: String,
    val keyPair: KeyPair,
    val publicKeyBase64: String,
    var attemptsRemaining: Int,
    var completed: Boolean = false,
)

class PairingSessionServer(
    private val host: String = HolderHttpServer.DEFAULT_HOST,
    private val preferredPort: Int = 0,
    private val identityProvider: suspend () -> LocalDeviceIdentity,
    private val onPeerPaired: suspend (PairedPeer) -> Unit,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val onLog: (String) -> Unit = {},
) {
    private val sessionMutex = Mutex()
    private val lifecycleMutex = Mutex()
    private val _state = MutableStateFlow<PairingSessionState>(PairingSessionState.Idle)
    val state: StateFlow<PairingSessionState> = _state.asStateFlow()

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var activeSession: ActiveSession? = null

    @Volatile
    var boundPort: Int = 0
        private set

    suspend fun start(): Int = lifecycleMutex.withLock {
        server?.let { return boundPort }
        sessionMutex.withLock {
            activeSession = createSession()
        }

        val started = embeddedServer(
            factory = CIO,
            port = preferredPort,
            host = host,
        ) {
            routing {
                get("/pair/info") {
                    handlePairInfo(call)
                }
                post("/pair") {
                    handlePair(call)
                }
            }
        }.start(wait = false)

        server = started
        boundPort = started.engine.resolvedConnectors().first().port
        val session = activeSession
        if (session != null) {
            _state.value = PairingSessionState.WaitingForPeer(
                pin = session.pin,
                port = boundPort,
                attemptsRemaining = session.attemptsRemaining,
            )
        }
        onLog("Pairing server listening on $host:$boundPort")
        return boundPort
    }

    suspend fun regenerateSession() {
        sessionMutex.withLock {
            val next = createSession()
            activeSession = next
            if (server != null) {
                _state.value = PairingSessionState.WaitingForPeer(
                    pin = next.pin,
                    port = boundPort,
                    attemptsRemaining = next.attemptsRemaining,
                )
            }
        }
    }

    suspend fun stop() = lifecycleMutex.withLock {
        val current = server ?: return@withLock
        server = null
        boundPort = 0
        sessionMutex.withLock {
            activeSession = null
        }
        _state.value = PairingSessionState.Idle
        current.stop(gracePeriodMillis = 200L, timeoutMillis = 1_000L)
        onLog("Pairing server stopped")
    }

    private fun createSession(): ActiveSession {
        val keyPair = PairingCrypto.generateEphemeralKeyPair()
        return ActiveSession(
            pin = PairingCrypto.generatePin(),
            sessionNonce = HmacSigner.generateNonce(),
            keyPair = keyPair,
            publicKeyBase64 = PairingCrypto.encodePublicKey(keyPair.public),
            attemptsRemaining = maxAttempts,
        )
    }

    private suspend fun handlePairInfo(call: io.ktor.server.routing.RoutingCall) {
        val identity = identityProvider()
        val session = sessionMutex.withLock { activeSession }
        if (session == null || session.completed || session.attemptsRemaining <= 0) {
            call.respondText(
                text = ProtocolJson.encodePairResponse(
                    PairResponse(
                        status = PairResponseStatus.REJECTED,
                        responderId = identity.deviceId,
                        responderName = identity.deviceName,
                        message = "Pairing session unavailable",
                    ),
                ),
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.Conflict,
            )
            return
        }

        val info = PairInfoResponse(
            version = PROTOCOL_VERSION,
            responderId = identity.deviceId,
            responderName = identity.deviceName,
            responderPublicKeyBase64 = session.publicKeyBase64,
            sessionNonce = session.sessionNonce,
        )
        call.respondText(
            text = ProtocolJson.encodePairInfo(info),
            contentType = ContentType.Application.Json,
            status = HttpStatusCode.OK,
        )
    }

    private suspend fun handlePair(call: io.ktor.server.routing.RoutingCall) {
        val identity = identityProvider()
        val body = call.receiveText()
        val request = try {
            ProtocolJson.decodePairRequest(body)
        } catch (_: SerializationException) {
            respondPair(
                call = call,
                httpStatus = HttpStatusCode.BadRequest,
                response = PairResponse(
                    status = PairResponseStatus.ERROR,
                    responderId = identity.deviceId,
                    responderName = identity.deviceName,
                    message = "Malformed pairing JSON",
                ),
            )
            return
        } catch (_: IllegalArgumentException) {
            respondPair(
                call = call,
                httpStatus = HttpStatusCode.BadRequest,
                response = PairResponse(
                    status = PairResponseStatus.ERROR,
                    responderId = identity.deviceId,
                    responderName = identity.deviceName,
                    message = "Invalid pairing payload",
                ),
            )
            return
        }

        if (request.version != PROTOCOL_VERSION ||
            request.requesterId.isBlank() ||
            request.requesterName.isBlank() ||
            request.requesterPublicKeyBase64.isBlank()
        ) {
            respondPair(
                call = call,
                httpStatus = HttpStatusCode.BadRequest,
                response = PairResponse(
                    status = PairResponseStatus.REJECTED,
                    responderId = identity.deviceId,
                    responderName = identity.deviceName,
                    message = "Invalid pairing parameters",
                ),
            )
            return
        }

        val outcome = sessionMutex.withLock {
            val session = activeSession
            if (session == null || session.completed) {
                return@withLock PairAttemptOutcome.Reject(
                    httpStatus = HttpStatusCode.Conflict,
                    message = "No active pairing session",
                )
            }
            if (session.attemptsRemaining <= 0) {
                return@withLock PairAttemptOutcome.Reject(
                    httpStatus = HttpStatusCode.TooManyRequests,
                    message = "Too many invalid PIN attempts",
                )
            }
            if (request.sessionNonce != session.sessionNonce) {
                return@withLock PairAttemptOutcome.Reject(
                    httpStatus = HttpStatusCode.Conflict,
                    message = "Stale pairing session",
                )
            }
            if (request.requesterId == identity.deviceId) {
                return@withLock PairAttemptOutcome.Reject(
                    httpStatus = HttpStatusCode.BadRequest,
                    message = "Cannot pair device with itself",
                )
            }

            val peerPub = runCatching {
                PairingCrypto.decodePublicKey(request.requesterPublicKeyBase64)
            }.getOrNull() ?: return@withLock PairAttemptOutcome.Reject(
                httpStatus = HttpStatusCode.BadRequest,
                message = "Invalid requester public key",
            )

            val sharedSecret = runCatching {
                PairingCrypto.computeSharedSecret(session.keyPair.private, peerPub)
            }.getOrNull() ?: return@withLock PairAttemptOutcome.Reject(
                httpStatus = HttpStatusCode.BadRequest,
                message = "ECDH key agreement failed",
            )

            val derivedPsk = PairingCrypto.derivePairingPsk(
                sharedSecret = sharedSecret,
                pin = session.pin,
                sessionNonce = session.sessionNonce,
                firstId = request.requesterId,
                secondId = identity.deviceId,
            )

            val validProof = PairingCrypto.verifyClientProof(
                psk = derivedPsk,
                sessionNonce = session.sessionNonce,
                requesterId = request.requesterId,
                responderId = identity.deviceId,
                requesterPublicKeyBase64 = request.requesterPublicKeyBase64,
                responderPublicKeyBase64 = session.publicKeyBase64,
                proofHex = request.proof,
            )

            if (!validProof) {
                session.attemptsRemaining -= 1
                if (session.attemptsRemaining <= 0) {
                    _state.value = PairingSessionState.LockedOut(port = boundPort)
                    return@withLock PairAttemptOutcome.Reject(
                        httpStatus = HttpStatusCode.Unauthorized,
                        message = "Invalid PIN — generate a new code to retry",
                    )
                }
                _state.value = PairingSessionState.WaitingForPeer(
                    pin = session.pin,
                    port = boundPort,
                    attemptsRemaining = session.attemptsRemaining,
                )
                return@withLock PairAttemptOutcome.Reject(
                    httpStatus = HttpStatusCode.Unauthorized,
                    message = "Invalid pairing PIN (${session.attemptsRemaining} attempts left)",
                )
            }

            session.completed = true
            val serverProof = PairingCrypto.computeServerProof(
                psk = derivedPsk,
                sessionNonce = session.sessionNonce,
                requesterId = request.requesterId,
                responderId = identity.deviceId,
                requesterPublicKeyBase64 = request.requesterPublicKeyBase64,
                responderPublicKeyBase64 = session.publicKeyBase64,
            )
            val peer = PairedPeer(
                peerId = request.requesterId,
                peerName = request.requesterName.trim(),
                psk = derivedPsk,
                pairedAtEpochSec = nowEpochSeconds(),
            )
            PairAttemptOutcome.Success(peer = peer, serverProof = serverProof)
        }

        when (outcome) {
            is PairAttemptOutcome.Reject -> {
                onLog("Rejected /pair from ${request.requesterId}: ${outcome.message}")
                respondPair(
                    call = call,
                    httpStatus = outcome.httpStatus,
                    response = PairResponse(
                        status = PairResponseStatus.REJECTED,
                        responderId = identity.deviceId,
                        responderName = identity.deviceName,
                        message = outcome.message,
                    ),
                )
            }

            is PairAttemptOutcome.Success -> {
                onPeerPaired(outcome.peer)
                _state.value = PairingSessionState.Completed(outcome.peer)
                onLog("Paired with ${outcome.peer.peerName} (${outcome.peer.peerId})")
                respondPair(
                    call = call,
                    httpStatus = HttpStatusCode.OK,
                    response = PairResponse(
                        status = PairResponseStatus.OK,
                        responderId = identity.deviceId,
                        responderName = identity.deviceName,
                        proof = outcome.serverProof,
                        message = "Paired",
                    ),
                )
            }
        }
    }

    private suspend fun respondPair(
        call: io.ktor.server.routing.RoutingCall,
        httpStatus: HttpStatusCode,
        response: PairResponse,
    ) {
        call.respondText(
            text = ProtocolJson.encodePairResponse(response),
            contentType = ContentType.Application.Json,
            status = httpStatus,
        )
    }

    private sealed interface PairAttemptOutcome {
        data class Reject(val httpStatus: HttpStatusCode, val message: String) : PairAttemptOutcome
        data class Success(val peer: PairedPeer, val serverProof: String) : PairAttemptOutcome
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 5
    }
}
