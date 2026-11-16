package org.hddq.bluehop.bluetooth

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class AudioProfileConfirmation(
    context: Context,
    private val timeoutMs: Long = 10_000L,
) : ProfileConfirmation {
    private val appContext = context.applicationContext

    override suspend fun awaitConfirmation(
        address: String,
        targetConnected: Boolean,
    ): Boolean {
        if (appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        val expectedState = if (targetConnected) {
            BluetoothProfile.STATE_CONNECTED
        } else {
            BluetoothProfile.STATE_DISCONNECTED
        }

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val unregistered = AtomicBoolean(false)
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(receiverContext: Context?, intent: Intent?) {
                        val device = intent?.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE,
                            BluetoothDevice::class.java,
                        ) ?: return
                        if (!device.address.equals(address, ignoreCase = true)) return

                        val state = intent.getIntExtra(
                            BluetoothProfile.EXTRA_STATE,
                            BluetoothProfile.STATE_DISCONNECTED,
                        )
                        if (state == expectedState && continuation.isActive) {
                            if (unregistered.compareAndSet(false, true)) {
                                runCatching { appContext.unregisterReceiver(this) }
                            }
                            continuation.resume(true)
                        }
                    }
                }

                val filter = IntentFilter().apply {
                    addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                    addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                }
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)

                continuation.invokeOnCancellation {
                    if (unregistered.compareAndSet(false, true)) {
                        runCatching { appContext.unregisterReceiver(receiver) }
                    }
                }
            }
        } ?: false
    }
}
