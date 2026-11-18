package org.hddq.bluehop.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.hddq.bluehop.BuildConfig
import org.hddq.bluehop.data.SelectedDeviceRepository
import org.hddq.bluehop.protocol.KeystorePskStore
import org.hddq.bluehop.protocol.PairedPeersRepository
import org.hddq.bluehop.resolveLocalIpAddresses
import org.hddq.bluehop.server.HolderHttpServer
import org.hddq.bluehop.server.NsdAdvertiser
import org.hddq.bluehop.server.PairingClient
import java.util.Locale

private const val TAG = "BlueHopPresence"

class BlueHopCompanionService : CompanionDeviceService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()

    private lateinit var stateObserver: BluetoothStateObserver
    private lateinit var bluetoothController: Api37BluetoothController
    private lateinit var selectedDeviceRepository: SelectedDeviceRepository
    private lateinit var pskStore: KeystorePskStore
    private lateinit var pairedPeersRepository: PairedPeersRepository
    private lateinit var holderServer: HolderHttpServer
    private lateinit var nsdAdvertiser: NsdAdvertiser

    private var holderActive = false
    private var advertisedAddresses: List<String> = emptyList()
    private var stopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val appContext = applicationContext
        stateObserver = BluetoothStateObserver(
            context = appContext,
            profileConfirmation = AudioProfileConfirmation(appContext),
        )
        bluetoothController = Api37BluetoothController(appContext)
        selectedDeviceRepository = SelectedDeviceRepository.from(appContext)
        pskStore = KeystorePskStore.from(appContext)
        pairedPeersRepository = PairedPeersRepository.from(appContext)
        nsdAdvertiser = NsdAdvertiser(appContext)
        holderServer = HolderHttpServer(
            deviceStates = stateObserver.deviceStates,
            selectedDeviceProvider = { selectedDeviceRepository.selectedDevice.first() },
            managedDevicesProvider = { selectedDeviceRepository.managedDevices.first() },
            isDeviceConnected = { address -> isDeviceCurrentlyConnected(address) },
            bluetoothController = bluetoothController,
            pskStore = pskStore,
            peerPskResolver = { requesterId ->
                pairedPeersRepository.getPeerPsk(requesterId)
                    ?: if (BuildConfig.DEBUG) pskStore.getOrCreatePsk() else null
            },
            holderIdProvider = { pairedPeersRepository.getOrCreateIdentity().deviceId },
            holderNameProvider = { pairedPeersRepository.getOrCreateIdentity().deviceName },
            onLog = { message -> Log.i(TAG, message) },
        )

        stateObserver.start()
        Log.i(TAG, "CompanionDeviceService created (bound)")

        serviceScope.launch {
            selectedDeviceRepository.managedDevices.collect {
                syncHolderState()
            }
        }
        serviceScope.launch {
            stateObserver.deviceStates.collect {
                syncHolderState()
            }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "CompanionDeviceService unbound")
        return super.onUnbind(intent)
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        Log.i(TAG, "CompanionDeviceService rebound")
    }

    override fun onDestroy() {
        Log.i(TAG, "CompanionDeviceService destroyed")
        stopJob?.cancel()
        runBlocking {
            withTimeoutOrNull(2_000L) {
                holderServer.awaitIdle(1_500L)
            }
            stopHolder()
        }
        nsdAdvertiser.shutdown()
        stateObserver.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        Log.i(TAG, formatPresenceEvent(event.event, event.associationId))
        when (event.event) {
            DevicePresenceEvent.EVENT_BT_CONNECTED -> {
                stopJob?.cancel()
                stopJob = null
                serviceScope.launch {
                    stateObserver.refresh()
                    val address = resolveDeviceAddress(event.associationId)
                    syncHolderState(forceIncludeAddress = address)
                }
            }

            DevicePresenceEvent.EVENT_BT_DISCONNECTED,
            DevicePresenceEvent.EVENT_ASSOCIATION_REMOVED,
            -> {
                stateObserver.refresh()
                stopJob?.cancel()
                stopJob = serviceScope.launch {
                    holderServer.awaitIdle(4_000L)
                    stateObserver.refresh()
                    syncHolderState()
                }
            }
        }
    }

    private suspend fun syncHolderState(forceIncludeAddress: String? = null) {
        if (holderActive && resolveConnectedManagedAddresses(forceIncludeAddress).isEmpty()) {
            holderServer.awaitIdle(4_000L)
        }
        lifecycleMutex.withLock {
            val connected = resolveConnectedManagedAddresses(forceIncludeAddress)
            if (connected.isEmpty()) {
                if (holderActive) {
                    holderActive = false
                    advertisedAddresses = emptyList()
                    nsdAdvertiser.stop()
                    holderServer.stop()
                    Log.i(TAG, "Holder stopped")
                }
                return
            }

            stopJob?.cancel()
            stopJob = null
            if (!holderActive || advertisedAddresses != connected) {
                val identity = pairedPeersRepository.getOrCreateIdentity()
                val port = holderServer.start()
                nsdAdvertiser.start(
                    port = port,
                    deviceAddresses = connected,
                    deviceId = identity.deviceId,
                    deviceName = identity.deviceName,
                    pairing = false,
                )
                holderActive = true
                advertisedAddresses = connected
                val ips = localIpAddresses(port).joinToString().ifEmpty { "none" }
                Log.i(
                    TAG,
                    "Holder active on port=$port ips=[$ips] id=${identity.deviceId} devices=${connected.joinToString()}",
                )
            }
        }
    }

    private suspend fun stopHolder() {
        lifecycleMutex.withLock {
            if (!holderActive) return
            holderActive = false
            advertisedAddresses = emptyList()
            nsdAdvertiser.stop()
            holderServer.stop()
            Log.i(TAG, "Holder stopped")
        }
    }

    private suspend fun resolveConnectedManagedAddresses(
        forceIncludeAddress: String? = null,
    ): List<String> = selectedDeviceRepository.managedDevices.first()
        .map { it.address.uppercase(Locale.ROOT) }
        .distinct()
        .filter { address ->
            (forceIncludeAddress != null && address.equals(forceIncludeAddress, ignoreCase = true)) ||
                isDeviceCurrentlyConnected(address)
        }

    private fun resolveDeviceAddress(associationId: Int): String? {
        val companionDeviceManager = getSystemService(CompanionDeviceManager::class.java)
        return companionDeviceManager?.myAssociations
            ?.firstOrNull { it.id == associationId }
            ?.deviceMacAddress
            ?.toString()
    }

    @SuppressLint("MissingPermission")
    private fun isDeviceCurrentlyConnected(address: String): Boolean {
        val stateMatch = stateObserver.deviceStates.value.entries
            .firstOrNull { (key, _) -> key.equals(address, ignoreCase = true) }
            ?.value
        if (stateMatch != null) {
            return stateMatch.isConnected
        }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        return try {
            val device = adapter.getRemoteDevice(address.uppercase(Locale.ROOT))
            device.isConnected(BluetoothDevice.TRANSPORT_BREDR) ||
                device.isConnected(BluetoothDevice.TRANSPORT_LE)
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun localIpAddresses(port: Int): List<String> =
        resolveLocalIpAddresses(this).map { host ->
            PairingClient.formatHostPort(host, port)
        }
}

internal data class AssociationEntry(
    val id: Int,
    val address: String?,
)

internal data class AssociationSyncPlan(
    val keepAssociationIds: List<Int>,
    val stopObservingIds: List<Int>,
    val disassociateIds: List<Int>,
)

internal fun buildAssociationSyncPlan(
    associations: List<AssociationEntry>,
    targetAddresses: Set<String>,
): AssociationSyncPlan {
    val normalizedTargets = targetAddresses
        .map { it.trim().uppercase(Locale.ROOT) }
        .filter { it.isNotEmpty() }
        .toSet()

    val keepIds = mutableListOf<Int>()
    val stopIds = mutableListOf<Int>()
    val duplicateIds = mutableListOf<Int>()
    val keptAddresses = mutableSetOf<String>()

    for (entry in associations) {
        val normalizedAddress = entry.address?.trim()?.uppercase(Locale.ROOT)
        if (normalizedAddress != null && normalizedAddress in normalizedTargets) {
            if (keptAddresses.add(normalizedAddress)) {
                keepIds += entry.id
            } else {
                stopIds += entry.id
                duplicateIds += entry.id
            }
        } else {
            stopIds += entry.id
        }
    }

    return AssociationSyncPlan(
        keepAssociationIds = keepIds,
        stopObservingIds = stopIds,
        disassociateIds = duplicateIds,
    )
}

internal fun formatPresenceEvent(eventType: Int, associationId: Int): String {
    val label = when (eventType) {
        DevicePresenceEvent.EVENT_BT_CONNECTED -> "BT connected"
        DevicePresenceEvent.EVENT_BT_DISCONNECTED -> "BT disconnected"
        DevicePresenceEvent.EVENT_BLE_APPEARED -> "BLE appeared"
        DevicePresenceEvent.EVENT_BLE_DISAPPEARED -> "BLE disappeared"
        DevicePresenceEvent.EVENT_ASSOCIATION_REMOVED -> "Association removed"
        DevicePresenceEvent.EVENT_SELF_MANAGED_APPEARED -> "Self-managed appeared"
        DevicePresenceEvent.EVENT_SELF_MANAGED_DISAPPEARED -> "Self-managed disappeared"
        DevicePresenceEvent.EVENT_SELF_MANAGED_NEARBY -> "Self-managed nearby"
        DevicePresenceEvent.EVENT_SELF_MANAGED_NOT_NEARBY -> "Self-managed not nearby"
        else -> "Unknown event ($eventType)"
    }
    return "Presence event: $label (associationId=$associationId)"
}

internal fun findAssociationForAddress(
    associations: List<AssociationInfo>,
    address: String,
): AssociationInfo? = associations.firstOrNull { association ->
    association.deviceMacAddress?.toString()?.equals(address, ignoreCase = true) == true
}

fun syncObservedCompanionDevices(
    context: Context,
    targetAddresses: Set<String>,
) {
    if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
        return
    }
    val companionDeviceManager = context.getSystemService(CompanionDeviceManager::class.java)
        ?: return

    val associations = companionDeviceManager.myAssociations
    val plan = buildAssociationSyncPlan(
        associations = associations.map { AssociationEntry(it.id, it.deviceMacAddress?.toString()) },
        targetAddresses = targetAddresses,
    )

    for (associationId in plan.stopObservingIds) {
        runCatching {
            val stopRequest = ObservingDevicePresenceRequest.Builder()
                .setAssociationId(associationId)
                .build()
            companionDeviceManager.stopObservingDevicePresence(stopRequest)
        }.onFailure { error ->
            Log.w(TAG, "Failed to stop observing associationId=$associationId", error)
        }
    }

    for (duplicateId in plan.disassociateIds) {
        runCatching {
            companionDeviceManager.disassociate(duplicateId)
            Log.i(TAG, "Removed duplicate associationId=$duplicateId")
        }.onFailure { error ->
            Log.w(TAG, "Failed to disassociate duplicate associationId=$duplicateId", error)
        }
    }

    val keepIdSet = plan.keepAssociationIds.toSet()
    for (association in associations) {
        if (association.id in keepIdSet) {
            startObservingAssociation(companionDeviceManager, association)
        }
    }
}

fun startObservingAssociation(
    context: Context,
    association: AssociationInfo,
) {
    if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
        return
    }
    val companionDeviceManager = context.getSystemService(CompanionDeviceManager::class.java)
        ?: return
    startObservingAssociation(companionDeviceManager, association)
}

private fun startObservingAssociation(
    companionDeviceManager: CompanionDeviceManager,
    association: AssociationInfo,
) {
    val request = ObservingDevicePresenceRequest.Builder()
        .setAssociationId(association.id)
        .build()
    runCatching {
        companionDeviceManager.startObservingDevicePresence(request)
        Log.i(
            TAG,
            "Observing device presence for associationId=${association.id} (${association.deviceMacAddress})",
        )
    }.onFailure { error ->
        Log.w(TAG, "Failed to start observing associationId=${association.id}", error)
    }
}
