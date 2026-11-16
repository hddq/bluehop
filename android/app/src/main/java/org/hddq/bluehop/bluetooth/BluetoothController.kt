package org.hddq.bluehop.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.content.Context

interface BluetoothController {
    fun connect(address: String): BluetoothControlResult
    fun disconnect(address: String): BluetoothControlResult
}

data class BluetoothControlResult(
    val statusCode: Int,
    val message: String,
) {
    val isSuccess: Boolean
        get() = statusCode == BluetoothStatusCodes.SUCCESS
}

class Api37BluetoothController(
    private val context: Context,
) : BluetoothController {
    override fun connect(address: String): BluetoothControlResult =
        execute(address) { device ->
            @SuppressLint("MissingPermission")
            device.connect()
        }

    override fun disconnect(address: String): BluetoothControlResult =
        execute(address) { device ->
            @SuppressLint("MissingPermission")
            device.disconnect()
        }

    private inline fun execute(
        address: String,
        action: (BluetoothDevice) -> Int,
    ): BluetoothControlResult {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return BluetoothControlResult(
                statusCode = BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED,
                message = bluetoothStatusMessage(BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED),
            )
        val statusCode = try {
            val device = adapter.getRemoteDevice(address)
            action(device)
        } catch (_: SecurityException) {
            BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION
        } catch (_: IllegalArgumentException) {
            BluetoothStatusCodes.ERROR_UNKNOWN
        }
        return BluetoothControlResult(
            statusCode = statusCode,
            message = bluetoothStatusMessage(statusCode),
        )
    }
}

fun bluetoothStatusMessage(statusCode: Int): String = when (statusCode) {
    BluetoothStatusCodes.SUCCESS -> "Request accepted (SUCCESS)"
    BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED ->
        "Bluetooth is turned off (ERROR_BLUETOOTH_NOT_ENABLED)"
    BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED ->
        "Not allowed — associate this device first (ERROR_BLUETOOTH_NOT_ALLOWED)"
    BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION ->
        "Missing Nearby devices permission (ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION)"
    BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED ->
        "Device is not paired (ERROR_DEVICE_NOT_BONDED)"
    else -> "Bluetooth error ($statusCode)"
}
