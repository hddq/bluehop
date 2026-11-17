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
import org.hddq.bluehop.server.HolderHttpServer
import org.hddq.bluehop.server.NsdAdvertiser
import java.net.Inet4Address
import java.net.NetworkInterface
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
            val selected = selectedDeviceRepository.selectedDevice.first()
            if (selected != null && isDeviceCurrentlyConnected(selected.address)) {
                ensureHolderActive(selected.address)
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
                    ensureHolderActive(address)
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
                    val selected = selectedDeviceRepository.selectedDevice.first()
                    if (selected == null || !isDeviceCurrentlyConnected(selected.address)) {
                        stopHolder()
                    }
                }
            }
        }
    }

    private suspend fun ensureHolderActive(deviceAddress: String?) {
        lifecycleMutex.withLock {
            if (holderActive) return
            val identity = pairedPeersRepository.getOrCreateIdentity()
            val port = holderServer.start()
            nsdAdvertiser.start(
                port = port,
                deviceAddress = deviceAddress,
                deviceId = identity.deviceId,
                deviceName = identity.deviceName,
                pairing = false,
            )
            holderActive = true
            val ips = localIpv4Addresses().joinToString().ifEmpty { "none" }
            Log.i(
                TAG,
                "Holder active on port=$port ips=[$ips] id=${identity.deviceId} device=${deviceAddress ?: "any"}",
            )
        }
    }

    private suspend fun stopHolder() {
        lifecycleMutex.withLock {
            if (!holderActive) return
            holderActive = false
            nsdAdvertiser.stop()
            holderServer.stop()
            Log.i(TAG, "Holder stopped")
        }
    }

    private suspend fun resolveDeviceAddress(associationId: Int): String? {
        val companionDeviceManager = getSystemService(CompanionDeviceManager::class.java)
        val fromAssociation = companionDeviceManager?.myAssociations
            ?.firstOrNull { it.id == associationId }
            ?.deviceMacAddress
            ?.toString()
        return fromAssociation ?: selectedDeviceRepository.selectedDevice.first()?.address
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

    private fun localIpv4Addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()
            ?.toList()
            .orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())
}

internal data class AssociationEntry(
    val id: Int,
    val address: String?,
)

internal data class AssociationSyncPlan(
    val keepAssociationId: Int?,
    val stopObservingIds: List<Int>,
    val disassociateIds: List<Int>,
)

internal fun buildAssociationSyncPlan(
    associations: List<AssociationEntry>,
    targetAddress: String,
): AssociationSyncPlan {
    val matching = associations.filter { entry ->
        entry.address?.equals(targetAddress, ignoreCase = true) == true
    }
    val keep = matching.firstOrNull()
    val stopIds = associations
        .filter { it.id != keep?.id }
        .map { it.id }
    val duplicateIds = matching
        .drop(1)
        .map { it.id }
    return AssociationSyncPlan(
        keepAssociationId = keep?.id,
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

fun syncObservedCompanionDevice(
    context: Context,
    targetAddress: String,
) {
    if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
        return
    }
    val companionDeviceManager = context.getSystemService(CompanionDeviceManager::class.java)
        ?: return

    val associations = companionDeviceManager.myAssociations
    val plan = buildAssociationSyncPlan(
        associations = associations.map { AssociationEntry(it.id, it.deviceMacAddress?.toString()) },
        targetAddress = targetAddress,
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
            Log.i(TAG, "Removed duplicate associationId=$duplicateId for $targetAddress")
        }.onFailure { error ->
            Log.w(TAG, "Failed to disassociate duplicate associationId=$duplicateId", error)
        }
    }

    val keepAssociation = associations.firstOrNull { it.id == plan.keepAssociationId }
    if (keepAssociation != null) {
        startObservingAssociation(companionDeviceManager, keepAssociation)
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
