package org.hddq.bluehop

import android.bluetooth.BluetoothDevice
import org.hddq.bluehop.bluetooth.DeviceState
import org.hddq.bluehop.bluetooth.reduceDeviceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothStateObserverTest {

    @Test
    fun deviceStateIsConnectedWhenEitherTransportIsConnected() {
        assertFalse(DeviceState().isConnected)
        assertTrue(DeviceState(bredrConnected = true, leConnected = false).isConnected)
        assertTrue(DeviceState(bredrConnected = false, leConnected = true).isConnected)
        assertTrue(DeviceState(bredrConnected = true, leConnected = true).isConnected)
    }

    @Test
    fun reduceDeviceStateTracksBredrAndLeIndependently() {
        val initial = DeviceState()

        val afterBredrConnect = reduceDeviceState(
            current = initial,
            transport = BluetoothDevice.TRANSPORT_BREDR,
            connected = true,
        )
        assertEquals(DeviceState(bredrConnected = true, leConnected = false), afterBredrConnect)
        assertTrue(afterBredrConnect.isConnected)

        val afterLeConnect = reduceDeviceState(
            current = afterBredrConnect,
            transport = BluetoothDevice.TRANSPORT_LE,
            connected = true,
        )
        assertEquals(DeviceState(bredrConnected = true, leConnected = true), afterLeConnect)
        assertTrue(afterLeConnect.isConnected)

        val afterLeDisconnect = reduceDeviceState(
            current = afterLeConnect,
            transport = BluetoothDevice.TRANSPORT_LE,
            connected = false,
        )
        assertEquals(DeviceState(bredrConnected = true, leConnected = false), afterLeDisconnect)
        assertTrue(afterLeDisconnect.isConnected)

        val afterBredrDisconnect = reduceDeviceState(
            current = afterLeDisconnect,
            transport = BluetoothDevice.TRANSPORT_BREDR,
            connected = false,
        )
        assertEquals(DeviceState(bredrConnected = false, leConnected = false), afterBredrDisconnect)
        assertFalse(afterBredrDisconnect.isConnected)
    }

    @Test
    fun reduceDeviceStateCreatesStateWhenNullAndIgnoresUnknownTransport() {
        val connected = reduceDeviceState(
            current = null,
            transport = BluetoothDevice.TRANSPORT_LE,
            connected = true,
        )
        assertEquals(DeviceState(bredrConnected = false, leConnected = true), connected)

        val unchanged = reduceDeviceState(
            current = connected,
            transport = BluetoothDevice.TRANSPORT_AUTO,
            connected = false,
        )
        assertEquals(connected, unchanged)
    }
}
