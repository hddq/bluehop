package org.hddq.bluehop.bluetooth

import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

private const val TAG = "BlueHopPresence"

class BlueHopCompanionService : CompanionDeviceService() {

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "CompanionDeviceService created (bound)")
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
        super.onDestroy()
    }

    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        Log.i(TAG, formatPresenceEvent(event.event, event.associationId))
    }
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
    val targetAssociation = findAssociationForAddress(associations, targetAddress)

    for (association in associations) {
        if (targetAssociation == null || association.id != targetAssociation.id) {
            runCatching {
                val stopRequest = ObservingDevicePresenceRequest.Builder()
                    .setAssociationId(association.id)
                    .build()
                companionDeviceManager.stopObservingDevicePresence(stopRequest)
            }.onFailure { error ->
                Log.w(TAG, "Failed to stop observing associationId=${association.id}", error)
            }
        }
    }

    if (targetAssociation != null) {
        startObservingAssociation(companionDeviceManager, targetAssociation)
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
