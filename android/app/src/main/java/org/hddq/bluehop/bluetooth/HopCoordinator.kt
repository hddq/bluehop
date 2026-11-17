package org.hddq.bluehop.bluetooth

import android.bluetooth.BluetoothStatusCodes
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.hddq.bluehop.protocol.LocalDeviceIdentity
import org.hddq.bluehop.server.DiscoveredBlueHopPeer
import org.hddq.bluehop.server.HolderClient
import org.hddq.bluehop.server.ReleaseOutcome

sealed interface HopProgress {
    data object Searching : HopProgress

    data class Releasing(val holderName: String) : HopProgress

    data class Connecting(val deviceName: String) : HopProgress

    data class ConfirmingAudio(val deviceName: String) : HopProgress
}

sealed interface HopOutcome {
    data object AlreadyConnected : HopOutcome

    data class Connected(val releasedFromHolderName: String?) : HopOutcome

    data class ConnectedUnconfirmed(val releasedFromHolderName: String?) : HopOutcome

    data object NeedsAssociation : HopOutcome

    data class Failed(val message: String) : HopOutcome
}

class HopCoordinator(
    private val bluetoothController: BluetoothController,
    private val holderClient: HolderClient,
    private val profileConfirmation: ProfileConfirmation? = null,
    private val discoveryWaitTimeoutMs: Long = DEFAULT_DISCOVERY_WAIT_MS,
) {
    suspend fun hopToDevice(
        targetAddress: String,
        targetName: String,
        isAudioDevice: Boolean,
        isLocallyConnected: Boolean,
        localIdentity: LocalDeviceIdentity,
        peersFlow: StateFlow<List<DiscoveredBlueHopPeer>>,
        hasPairedPeers: Boolean,
        peerPskProvider: suspend (peerId: String) -> ByteArray?,
        onProgress: (HopProgress) -> Unit = {},
    ): HopOutcome {
        if (isLocallyConnected) {
            return HopOutcome.AlreadyConnected
        }

        val candidates = if (hasPairedPeers) {
            onProgress(HopProgress.Searching)
            val discoveredPeers = resolveAvailablePeers(
                peersFlow = peersFlow,
                localDeviceId = localIdentity.deviceId,
            )
            resolvePairedCandidates(
                peers = discoveredPeers,
                localDeviceId = localIdentity.deviceId,
                peerPskProvider = peerPskProvider,
            )
        } else {
            emptyList()
        }

        val releaseOutcome = releaseFromActiveHolder(
            candidates = candidates,
            targetAddress = targetAddress,
            localDeviceId = localIdentity.deviceId,
            onProgress = onProgress,
        )

        val releasedFromHolderName = when (releaseOutcome) {
            is HolderReleaseAttempt.Released -> releaseOutcome.holderName
            HolderReleaseAttempt.NoActiveHolder -> null
            is HolderReleaseAttempt.Failed -> return HopOutcome.Failed(releaseOutcome.message)
        }

        return connectLocally(
            targetAddress = targetAddress,
            targetName = targetName,
            isAudioDevice = isAudioDevice,
            releasedFromHolderName = releasedFromHolderName,
            onProgress = onProgress,
        )
    }

    private suspend fun resolveAvailablePeers(
        peersFlow: StateFlow<List<DiscoveredBlueHopPeer>>,
        localDeviceId: String,
    ): List<DiscoveredBlueHopPeer> {
        val current = peersFlow.value
        val hasCandidate = current.any { !it.pairingAvailable && it.deviceId != null && it.deviceId != localDeviceId }
        if (hasCandidate || discoveryWaitTimeoutMs <= 0L) {
            return current
        }
        return withTimeoutOrNull(discoveryWaitTimeoutMs) {
            peersFlow.first { list ->
                list.any { !it.pairingAvailable && it.deviceId != null && it.deviceId != localDeviceId }
            }
        } ?: peersFlow.value
    }

    private suspend fun resolvePairedCandidates(
        peers: List<DiscoveredBlueHopPeer>,
        localDeviceId: String,
        peerPskProvider: suspend (String) -> ByteArray?,
    ): List<PairedCandidate> = peers.mapNotNull { peer ->
        if (peer.pairingAvailable) return@mapNotNull null
        val peerId = peer.deviceId?.takeIf { it.isNotBlank() && it != localDeviceId }
            ?: return@mapNotNull null
        val psk = peerPskProvider(peerId) ?: return@mapNotNull null
        PairedCandidate(peer = peer, psk = psk)
    }

    private suspend fun releaseFromActiveHolder(
        candidates: List<PairedCandidate>,
        targetAddress: String,
        localDeviceId: String,
        onProgress: (HopProgress) -> Unit,
    ): HolderReleaseAttempt {
        if (candidates.isEmpty()) return HolderReleaseAttempt.NoActiveHolder

        val directMatches = candidates.filter { candidate ->
            candidate.peer.connectedBluetoothAddress?.equals(targetAddress, ignoreCase = true) == true
        }
        for (candidate in directMatches) {
            when (val result = tryRelease(candidate, targetAddress, localDeviceId, onProgress)) {
                is HolderReleaseAttempt.Released -> return result
                is HolderReleaseAttempt.Failed -> return result
                HolderReleaseAttempt.NoActiveHolder -> Unit
            }
        }

        val fallbackCandidates = candidates.filter { candidate ->
            candidate.peer.connectedBluetoothAddress == null
        }
        for (candidate in fallbackCandidates) {
            val status = holderClient.fetchStatus(candidate.peer.host, candidate.peer.port).getOrNull()
                ?: continue
            val holdsTarget = status.connected &&
                status.deviceAddress?.equals(targetAddress, ignoreCase = true) == true
            if (!holdsTarget) continue

            val displayCandidate = candidate.copy(
                peer = candidate.peer.copy(
                    displayName = status.holderName?.takeIf { it.isNotBlank() }
                        ?: candidate.peer.displayName,
                ),
            )
            when (val result = tryRelease(displayCandidate, targetAddress, localDeviceId, onProgress)) {
                is HolderReleaseAttempt.Released -> return result
                is HolderReleaseAttempt.Failed -> return result
                HolderReleaseAttempt.NoActiveHolder -> Unit
            }
        }

        return HolderReleaseAttempt.NoActiveHolder
    }

    private suspend fun tryRelease(
        candidate: PairedCandidate,
        targetAddress: String,
        localDeviceId: String,
        onProgress: (HopProgress) -> Unit,
    ): HolderReleaseAttempt {
        val holderName = candidate.peer.displayName
        onProgress(HopProgress.Releasing(holderName))
        return when (
            val outcome = holderClient.requestRelease(
                host = candidate.peer.host,
                port = candidate.peer.port,
                deviceAddress = targetAddress,
                requesterId = localDeviceId,
                psk = candidate.psk,
            )
        ) {
            is ReleaseOutcome.Released -> HolderReleaseAttempt.Released(holderName)
            is ReleaseOutcome.AlreadyDisconnected -> HolderReleaseAttempt.NoActiveHolder
            is ReleaseOutcome.Rejected -> {
                val message = if (outcome.message == "UNKNOWN_REQUESTER") {
                    "Host $holderName no longer recognizes this device. Remove and re-pair it."
                } else {
                    "Release rejected by $holderName: ${outcome.message}"
                }
                HolderReleaseAttempt.Failed(message)
            }
            is ReleaseOutcome.Failed -> {
                HolderReleaseAttempt.Failed("Failed to release from $holderName: ${outcome.message}")
            }
        }
    }

    private suspend fun connectLocally(
        targetAddress: String,
        targetName: String,
        isAudioDevice: Boolean,
        releasedFromHolderName: String?,
        onProgress: (HopProgress) -> Unit,
    ): HopOutcome = coroutineScope {
        onProgress(HopProgress.Connecting(targetName))
        val confirmationDeferred = if (isAudioDevice && profileConfirmation != null) {
            async(start = CoroutineStart.UNDISPATCHED) {
                profileConfirmation.awaitConfirmation(
                    address = targetAddress,
                    targetConnected = true,
                )
            }
        } else {
            null
        }

        val result = bluetoothController.connect(targetAddress)
        if (result.statusCode == BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED) {
            confirmationDeferred?.cancel()
            return@coroutineScope HopOutcome.NeedsAssociation
        }
        if (!result.isSuccess) {
            confirmationDeferred?.cancel()
            return@coroutineScope HopOutcome.Failed(result.message)
        }

        if (confirmationDeferred != null) {
            onProgress(HopProgress.ConfirmingAudio(targetName))
            val confirmed = confirmationDeferred.await()
            if (!confirmed) {
                return@coroutineScope HopOutcome.ConnectedUnconfirmed(releasedFromHolderName)
            }
        }
        HopOutcome.Connected(releasedFromHolderName)
    }

    private data class PairedCandidate(
        val peer: DiscoveredBlueHopPeer,
        val psk: ByteArray,
    )

    private sealed interface HolderReleaseAttempt {
        data class Released(val holderName: String) : HolderReleaseAttempt

        data object NoActiveHolder : HolderReleaseAttempt

        data class Failed(val message: String) : HolderReleaseAttempt
    }

    companion object {
        const val DEFAULT_DISCOVERY_WAIT_MS = 800L
    }
}
