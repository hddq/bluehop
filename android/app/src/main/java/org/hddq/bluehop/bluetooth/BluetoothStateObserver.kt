package org.hddq.bluehop.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DeviceState(
    val bredrConnected: Boolean = false,
    val leConnected: Boolean = false,
) {
    val isConnected: Boolean
        get() = bredrConnected || leConnected
}

fun interface ProfileConfirmation {
    suspend fun awaitConfirmation(address: String, targetConnected: Boolean): Boolean
}

class BluetoothStateObserver(
    context: Context,
    val profileConfirmation: ProfileConfirmation? = null,
) {
    private val appContext = context.applicationContext
    private val _deviceStates = MutableStateFlow<Map<String, DeviceState>>(emptyMap())
    val deviceStates: StateFlow<Map<String, DeviceState>> = _deviceStates.asStateFlow()

    private var isRegistered = false

    private val aclReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val connected = when (action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> true
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> false
                else -> return
            }
            val device = intent.getParcelableExtra(
                BluetoothDevice.EXTRA_DEVICE,
                BluetoothDevice::class.java,
            ) ?: return
            if (!hasConnectPermission()) return
            if (device.address !in _deviceStates.value && !isDeviceBonded(device)) return

            val transport = intent.getIntExtra(
                BluetoothDevice.EXTRA_TRANSPORT,
                BluetoothDevice.TRANSPORT_AUTO,
            )

            _deviceStates.update { current ->
                val nextState = when (transport) {
                    BluetoothDevice.TRANSPORT_BREDR,
                    BluetoothDevice.TRANSPORT_LE,
                    -> reduceDeviceState(
                        current = current[device.address],
                        transport = transport,
                        connected = connected,
                    )

                    else -> readDeviceState(device)
                }
                current + (device.address to nextState)
            }
        }
    }

    fun start() {
        if (!isRegistered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
            appContext.registerReceiver(aclReceiver, filter, Context.RECEIVER_EXPORTED)
            isRegistered = true
        }
        refresh()
    }

    fun stop() {
        if (isRegistered) {
            runCatching { appContext.unregisterReceiver(aclReceiver) }
            isRegistered = false
        }
    }

    fun refresh() {
        if (!hasConnectPermission()) {
            _deviceStates.value = emptyMap()
            return
        }
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            _deviceStates.value = emptyMap()
            return
        }

        _deviceStates.value = try {
            @SuppressLint("MissingPermission")
            val bonded = adapter.bondedDevices.orEmpty()
            buildMap(bonded.size) {
                for (device in bonded) {
                    put(device.address, readDeviceState(device))
                }
            }
        } catch (_: SecurityException) {
            emptyMap()
        }
    }

    private fun hasConnectPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun isDeviceBonded(device: BluetoothDevice): Boolean =
        try {
            device.bondState == BluetoothDevice.BOND_BONDED
        } catch (_: SecurityException) {
            false
        }

    @SuppressLint("MissingPermission")
    private fun readDeviceState(device: BluetoothDevice): DeviceState =
        try {
            DeviceState(
                bredrConnected = device.isConnected(BluetoothDevice.TRANSPORT_BREDR),
                leConnected = device.isConnected(BluetoothDevice.TRANSPORT_LE),
            )
        } catch (_: SecurityException) {
            DeviceState()
        }
}

internal fun reduceDeviceState(
    current: DeviceState?,
    transport: Int,
    connected: Boolean,
): DeviceState {
    val base = current ?: DeviceState()
    return when (transport) {
        BluetoothDevice.TRANSPORT_BREDR -> base.copy(bredrConnected = connected)
        BluetoothDevice.TRANSPORT_LE -> base.copy(leConnected = connected)
        else -> base
    }
}
