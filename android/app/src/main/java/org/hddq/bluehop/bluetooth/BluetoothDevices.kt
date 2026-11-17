package org.hddq.bluehop.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.serialization.Serializable

data class BondedBluetoothDevice(
    val name: String,
    val address: String,
    val isAudioDevice: Boolean,
)

@Serializable
data class SelectedBluetoothDevice(
    val name: String,
    val address: String,
)

sealed interface BondedDevicesState {
    data object PermissionRequired : BondedDevicesState
    data object BluetoothUnavailable : BondedDevicesState
    data object BluetoothDisabled : BondedDevicesState
    data class Ready(val devices: List<BondedBluetoothDevice>) : BondedDevicesState
}

fun sortBondedDevices(devices: Collection<BondedBluetoothDevice>): List<BondedBluetoothDevice> =
    devices.sortedWith(
        compareByDescending<BondedBluetoothDevice> { it.isAudioDevice }
            .thenBy { it.name.lowercase() }
            .thenBy { it.address },
    )

fun loadBondedDevices(context: Context): BondedDevicesState {
    if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return BondedDevicesState.PermissionRequired
    }

    val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    val adapter = bluetoothManager?.adapter ?: return BondedDevicesState.BluetoothUnavailable

    if (!adapter.isEnabled) {
        return BondedDevicesState.BluetoothDisabled
    }

    return try {
        @SuppressLint("MissingPermission")
        val bonded = adapter.bondedDevices.orEmpty().map { device ->
            device.toBondedBluetoothDevice()
        }
        BondedDevicesState.Ready(sortBondedDevices(bonded))
    } catch (_: SecurityException) {
        BondedDevicesState.PermissionRequired
    }
}

@SuppressLint("MissingPermission")
private fun BluetoothDevice.toBondedBluetoothDevice(): BondedBluetoothDevice {
    val resolvedName = alias?.takeIf { it.isNotBlank() }
        ?: name?.takeIf { it.isNotBlank() }
        ?: address
    val isAudio = bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO
    return BondedBluetoothDevice(
        name = resolvedName,
        address = address,
        isAudioDevice = isAudio,
    )
}
