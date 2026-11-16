package org.hddq.bluehop

import android.bluetooth.BluetoothStatusCodes
import org.hddq.bluehop.bluetooth.BluetoothControlResult
import org.hddq.bluehop.bluetooth.bluetoothStatusMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothControllerTest {

    @Test
    fun bluetoothStatusMessageMapsAllApi37ConnectionStatusCodes() {
        assertEquals(
            "Request accepted (SUCCESS)",
            bluetoothStatusMessage(BluetoothStatusCodes.SUCCESS),
        )
        assertEquals(
            "Bluetooth is turned off (ERROR_BLUETOOTH_NOT_ENABLED)",
            bluetoothStatusMessage(BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED),
        )
        assertEquals(
            "Not allowed — associate this device first (ERROR_BLUETOOTH_NOT_ALLOWED)",
            bluetoothStatusMessage(BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED),
        )
        assertEquals(
            "Missing Nearby devices permission (ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION)",
            bluetoothStatusMessage(BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION),
        )
        assertEquals(
            "Device is not paired (ERROR_DEVICE_NOT_BONDED)",
            bluetoothStatusMessage(BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED),
        )
        assertEquals(
            "Bluetooth error (999)",
            bluetoothStatusMessage(999),
        )
    }

    @Test
    fun resultIsSuccessOnlyForStatusSuccess() {
        assertTrue(BluetoothControlResult(BluetoothStatusCodes.SUCCESS, "ok").isSuccess)
        assertFalse(BluetoothControlResult(BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED, "err").isSuccess)
    }
}
