package org.hddq.bluehop.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import org.hddq.bluehop.protocol.HmacSigner
import org.hddq.bluehop.protocol.LocalDeviceIdentity
import org.hddq.bluehop.protocol.PAIRING_PROTOCOL_VERSION
import org.hddq.bluehop.protocol.PairConfirmRequest
import org.hddq.bluehop.protocol.PairConfirmResponse
import org.hddq.bluehop.protocol.PairErrorResponse
import org.hddq.bluehop.protocol.PairSessionStatus
import org.hddq.bluehop.protocol.PairStartRequest
import org.hddq.bluehop.protocol.PairStartResponse
import org.hddq.bluehop.protocol.PairStatusResponse
import org.hddq.bluehop.protocol.PairedPeer
import org.hddq.bluehop.protocol.PairingCrypto
import org.hddq.bluehop.protocol.PairingKeys
import org.hddq.bluehop.protocol.ProtocolJson

sealed interface PairingSessionState {
    data object Idle : PairingSessionState

    data class WaitingForPeer(
        val port: Int,
    ) : PairingSessionState

    data class PendingConfirmation(
        val sessionId: String,
        val requesterId: String,
        val requesterName: String,
        val sas: String,
        val port: Int,
        val expiresAtEpochSec: Long,
    ) : PairingSessionState

    data class ApprovedWaitingForConfirm(
        val sessionId: String,
        val requesterName: String,
        val sas: String,
        val port: Int,
    ) : PairingSessionState

    data class Rejected(
        val port: Int,
        val message: String,
    ) : PairingSessionState

    data class Expired(
        val port: Int,
    ) : PairingSessionState

    data class Completed(
        val peer: PairedPeer,
    ) : PairingSessionState
}

private data class ActivePairingSession(
    val sessionId: String,
    val requesterId: String,
    val requesterName: String,
    val keys: PairingKeys,
    val expiresAtEpochSec: Long,
    var status: PairSessionStatus = PairSessionStatus.PENDING,
    var confirming: Boolean = false,
    var completed: Boolean = false,
)

class PairingSessionServer(
    private val host: String = HolderHttpServer.DEFAULT_HOST,
    private val preferredPort: Int = 0,
    private val identityProvider: suspend () -> LocalDeviceIdentity,
    private val onPeerPaired: suspend (PairedPeer) -> Unit,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
    private val sessionTtlSeconds: Long = DEFAULT_SESSION_TTL_SECONDS,
    private val onLog: (String) -> Unit = {},
) {
    private val sessionMutex = Mutex()
    private val lifecycleMutex = Mutex()
    private val _state = MutableStateFlow<PairingSessionState>(PairingSessionState.Idle)
    val state: StateFlow<PairingSessionState> = _state.asStateFlow()

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var activeSession: ActivePairingSession? = null

    @Volatile
    var boundPort: Int = 0
        private set

    suspend fun start(): Int = lifecycleMutex.withLock {
        server?.let { return boundPort }
        sessionMutex.withLock {
            activeSession = null
        }

        val (started, boundHost) = try {
            startEngine(host) to host
        } catch (error: Throwable) {
            if (host == HolderHttpServer.DEFAULT_HOST && isIpv6UnavailableError(error)) {
                onLog(
                    "Dual-stack IPv6 unavailable on $host, falling back to ${HolderHttpServer.IPV4_FALLBACK_HOST}",
                )
                startEngine(HolderHttpServer.IPV4_FALLBACK_HOST) to HolderHttpServer.IPV4_FALLBACK_HOST
            } else {
                throw error
            }
        }

        server = started
        boundPort = started.engine.resolvedConnectors().first().port
        _state.value = PairingSessionState.WaitingForPeer(port = boundPort)
        onLog("Pairing server listening on ${PairingClient.formatHostPort(boundHost, boundPort)}")
        return boundPort
    }

    private fun startEngine(
        bindHost: String,
    ): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> {
        val candidate = embeddedServer(
            factory = CIO,
            port = preferredPort,
            host = bindHost,
        ) {
            routing {
                post("/pair/start") {
                    handlePairStart(call)
                }
                get("/pair/status") {
                    handlePairStatus(call)
                }
                post("/pair/confirm") {
                    handlePairConfirm(call)
                }
            }
        }
        return try {
            candidate.start(wait = false)
        } catch (error: Throwable) {
            runCatching { candidate.stop(gracePeriodMillis = 0L, timeoutMillis = 0L) }
            throw error
        }
    }

    suspend fun approveSession(sessionId: String): Boolean = sessionMutex.withLock {
        val session = activeSession ?: return@withLock false
        if (session.sessionId != sessionId || session.completed) return@withLock false
        if (nowEpochSeconds() > session.expiresAtEpochSec) {
            session.status = PairSessionStatus.EXPIRED
            _state.value = PairingSessionState.Expired(port = boundPort)
            return@withLock false
        }
        if (session.status != PairSessionStatus.PENDING) return@withLock false
        session.status = PairSessionStatus.APPROVED
        _state.value = PairingSessionState.ApprovedWaitingForConfirm(
            sessionId = session.sessionId,
            requesterName = session.requesterName,
            sas = session.keys.sas,
            port = boundPort,
        )
        onLog("Approved pairing session ${session.sessionId} for ${session.requesterName}")
        true
    }

    suspend fun rejectSession(sessionId: String): Boolean = sessionMutex.withLock {
        val session = activeSession ?: return@withLock false
        if (session.sessionId != sessionId || session.completed) return@withLock false
        if (nowEpochSeconds() > session.expiresAtEpochSec) {
            session.status = PairSessionStatus.EXPIRED
            _state.value = PairingSessionState.Expired(port = boundPort)
            return@withLock false
        }
        if (session.status != PairSessionStatus.PENDING) return@withLock false
        session.status = PairSessionStatus.REJECTED
        _state.value = PairingSessionState.Rejected(
            port = boundPort,
            message = "Pairing request rejected",
        )
        onLog("Rejected pairing session ${session.sessionId} for ${session.requesterName}")
        true
    }

    suspend fun resetSession() {
        sessionMutex.withLock {
            activeSession = null
            if (server != null) {
                _state.value = PairingSessionState.WaitingForPeer(port = boundPort)
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

    private suspend fun handlePairStart(call: RoutingCall) {
        val identity = identityProvider()
        val body = call.receiveText()
        val request = try {
            ProtocolJson.decodePairStartRequest(body)
        } catch (_: SerializationException) {
            respondError(call, HttpStatusCode.BadRequest, "Malformed pairing start JSON")
            return
        } catch (_: IllegalArgumentException) {
            respondError(call, HttpStatusCode.BadRequest, "Invalid pairing start payload")
            return
        }

        if (request.version != PAIRING_PROTOCOL_VERSION) {
            respondError(call, HttpStatusCode.BadRequest, "Unsupported pairing version ${request.version}")
            return
        }
        if (!PairingCrypto.isValidSingleLineField(request.requesterId) ||
            request.requesterId != request.requesterId.trim() ||
            !PairingCrypto.isValidSingleLineField(request.requesterName) ||
            !PairingCrypto.isValidSingleLineField(request.clientPub)
        ) {
            respondError(call, HttpStatusCode.BadRequest, "Invalid pairing parameters")
            return
        }
        if (request.requesterId == identity.deviceId) {
            respondError(call, HttpStatusCode.BadRequest, "Cannot pair device with itself")
            return
        }

        val clientPubKey = runCatching {
            PairingCrypto.decodePublicKey(request.clientPub)
        }.getOrNull() ?: run {
            respondError(call, HttpStatusCode.BadRequest, "Invalid client public key")
            return
        }

        val serverKeyPair = PairingCrypto.generateEphemeralKeyPair()
        val serverPubBase64 = PairingCrypto.encodePublicKey(serverKeyPair.public)
        val sharedSecret = runCatching {
            PairingCrypto.computeSharedSecret(serverKeyPair.private, clientPubKey)
        }.getOrNull() ?: run {
            respondError(call, HttpStatusCode.BadRequest, "ECDH key agreement failed")
            return
        }

        val sessionId = HmacSigner.generateNonce()
        val keys = PairingCrypto.derivePairingKeys(
            sharedSecret = sharedSecret,
            sessionId = sessionId,
            clientPub = request.clientPub,
            serverPub = serverPubBase64,
        )

        val now = nowEpochSeconds()
        val expiresAt = now + sessionTtlSeconds
        val requesterName = request.requesterName.trim()

        val conflictMessage = sessionMutex.withLock {
            val current = activeSession
            if (current != null) {
                if (!current.completed && now > current.expiresAtEpochSec &&
                    (current.status == PairSessionStatus.PENDING || current.status == PairSessionStatus.APPROVED)
                ) {
                    current.status = PairSessionStatus.EXPIRED
                }
                if (current.completed) {
                    return@withLock "Pairing session already completed"
                }
                if (current.status == PairSessionStatus.PENDING || current.status == PairSessionStatus.APPROVED) {
                    return@withLock "Pairing session already in progress"
                }
            }
            activeSession = ActivePairingSession(
                sessionId = sessionId,
                requesterId = request.requesterId,
                requesterName = requesterName,
                keys = keys,
                expiresAtEpochSec = expiresAt,
            )
            _state.value = PairingSessionState.PendingConfirmation(
                sessionId = sessionId,
                requesterId = request.requesterId,
                requesterName = requesterName,
                sas = keys.sas,
                port = boundPort,
                expiresAtEpochSec = expiresAt,
            )
            null
        }

        if (conflictMessage != null) {
            onLog("Rejected /pair/start from ${request.requesterId}: $conflictMessage")
            respondError(call, HttpStatusCode.Conflict, conflictMessage)
            return
        }

        onLog("Started pairing session $sessionId for $requesterName (${request.requesterId})")
        val response = PairStartResponse(
            version = PAIRING_PROTOCOL_VERSION,
            sessionId = sessionId,
            responderId = identity.deviceId,
            responderName = identity.deviceName,
            serverPub = serverPubBase64,
        )
        call.respondText(
            text = ProtocolJson.encodePairStartResponse(response),
            contentType = ContentType.Application.Json,
            status = HttpStatusCode.OK,
        )
    }

    private suspend fun handlePairStatus(call: RoutingCall) {
        val sessionId = call.request.queryParameters["sessionId"]?.trim().orEmpty()
        if (sessionId.isEmpty()) {
            respondError(call, HttpStatusCode.BadRequest, "Missing sessionId")
            return
        }

        val status = sessionMutex.withLock {
            val current = activeSession
            if (current == null || current.sessionId != sessionId) {
                return@withLock null
            }
            if (!current.completed && nowEpochSeconds() > current.expiresAtEpochSec &&
                (current.status == PairSessionStatus.PENDING || current.status == PairSessionStatus.APPROVED)
            ) {
                current.status = PairSessionStatus.EXPIRED
                _state.value = PairingSessionState.Expired(port = boundPort)
            }
            current.status
        }

        if (status == null) {
            respondError(call, HttpStatusCode.NotFound, "Unknown pairing session")
            return
        }

        call.respondText(
            text = ProtocolJson.encodePairStatusResponse(
                PairStatusResponse(
                    version = PAIRING_PROTOCOL_VERSION,
                    sessionId = sessionId,
                    status = status,
                ),
            ),
            contentType = ContentType.Application.Json,
            status = HttpStatusCode.OK,
        )
    }

    private suspend fun handlePairConfirm(call: RoutingCall) {
        val identity = identityProvider()
        val body = call.receiveText()
        val request = try {
            ProtocolJson.decodePairConfirmRequest(body)
        } catch (_: SerializationException) {
            respondError(call, HttpStatusCode.BadRequest, "Malformed confirmation JSON")
            return
        } catch (_: IllegalArgumentException) {
            respondError(call, HttpStatusCode.BadRequest, "Invalid confirmation payload")
            return
        }

        if (request.version != PAIRING_PROTOCOL_VERSION ||
            !PairingCrypto.isValidSingleLineField(request.sessionId) ||
            !PairingCrypto.isValidSingleLineField(request.clientConfirm)
        ) {
            respondError(call, HttpStatusCode.BadRequest, "Invalid confirmation parameters")
            return
        }

        val checkOutcome = sessionMutex.withLock {
            val session = activeSession
            if (session == null || session.sessionId != request.sessionId) {
                return@withLock ConfirmCheckOutcome.Error(HttpStatusCode.NotFound, "Unknown pairing session")
            }
            if (session.completed || session.confirming) {
                return@withLock ConfirmCheckOutcome.Error(HttpStatusCode.Conflict, "Pairing session already completed")
            }
            val now = nowEpochSeconds()
            if (now > session.expiresAtEpochSec) {
                session.status = PairSessionStatus.EXPIRED
                _state.value = PairingSessionState.Expired(port = boundPort)
                return@withLock ConfirmCheckOutcome.Error(HttpStatusCode.Gone, "Pairing session expired")
            }
            if (session.status == PairSessionStatus.REJECTED) {
                return@withLock ConfirmCheckOutcome.Error(HttpStatusCode.Forbidden, "Pairing request rejected")
            }
            if (session.status != PairSessionStatus.APPROVED) {
                return@withLock ConfirmCheckOutcome.Error(HttpStatusCode.Conflict, "Pairing session is not approved yet")
            }

            val clientValid = PairingCrypto.verifyConfirm(
                confirmKey = session.keys.confirmKey,
                role = PairingCrypto.CONFIRM_ROLE_CLIENT,
                sessionId = session.sessionId,
                requesterId = session.requesterId,
                responderId = identity.deviceId,
                macHex = request.clientConfirm,
            )
            if (!clientValid) {
                return@withLock ConfirmCheckOutcome.Error(
                    HttpStatusCode.Unauthorized,
                    "Client key confirmation failed",
                )
            }

            session.confirming = true
            val serverConfirm = PairingCrypto.computeConfirm(
                confirmKey = session.keys.confirmKey,
                role = PairingCrypto.CONFIRM_ROLE_SERVER,
                sessionId = session.sessionId,
                requesterId = session.requesterId,
                responderId = identity.deviceId,
            )
            val peer = PairedPeer(
                peerId = session.requesterId,
                peerName = session.requesterName,
                psk = session.keys.psk,
                pairedAtEpochSec = now,
            )
            ConfirmCheckOutcome.Ready(session = session, peer = peer, serverConfirm = serverConfirm)
        }

        when (checkOutcome) {
            is ConfirmCheckOutcome.Error -> {
                onLog("Rejected /pair/confirm: ${checkOutcome.message}")
                respondError(call, checkOutcome.httpStatus, checkOutcome.message)
            }

            is ConfirmCheckOutcome.Ready -> {
                try {
                    onPeerPaired(checkOutcome.peer)
                } catch (error: Throwable) {
                    withContext(NonCancellable) {
                        sessionMutex.withLock {
                            checkOutcome.session.confirming = false
                        }
                    }
                    if (error is CancellationException) throw error
                    onLog("Failed to persist paired peer ${checkOutcome.peer.peerId}: ${error.message}")
                    respondError(call, HttpStatusCode.InternalServerError, "Failed to save paired peer")
                    return
                }

                sessionMutex.withLock {
                    checkOutcome.session.confirming = false
                    checkOutcome.session.completed = true
                    if (activeSession === checkOutcome.session) {
                        _state.value = PairingSessionState.Completed(checkOutcome.peer)
                    }
                }
                onLog("Paired with ${checkOutcome.peer.peerName} (${checkOutcome.peer.peerId})")

                call.respondText(
                    text = ProtocolJson.encodePairConfirmResponse(
                        PairConfirmResponse(
                            version = PAIRING_PROTOCOL_VERSION,
                            sessionId = checkOutcome.session.sessionId,
                            serverConfirm = checkOutcome.serverConfirm,
                        ),
                    ),
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.OK,
                )
            }
        }
    }

    private suspend fun respondError(
        call: RoutingCall,
        httpStatus: HttpStatusCode,
        message: String,
    ) {
        call.respondText(
            text = ProtocolJson.encodePairErrorResponse(
                PairErrorResponse(
                    version = PAIRING_PROTOCOL_VERSION,
                    message = message,
                ),
            ),
            contentType = ContentType.Application.Json,
            status = httpStatus,
        )
    }

    private sealed interface ConfirmCheckOutcome {
        data class Error(val httpStatus: HttpStatusCode, val message: String) : ConfirmCheckOutcome
        data class Ready(
            val session: ActivePairingSession,
            val peer: PairedPeer,
            val serverConfirm: String,
        ) : ConfirmCheckOutcome
    }

    companion object {
        const val DEFAULT_SESSION_TTL_SECONDS = 60L
    }
}
