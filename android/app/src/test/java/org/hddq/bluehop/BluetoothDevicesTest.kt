package org.hddq.bluehop

import org.hddq.bluehop.bluetooth.BondedBluetoothDevice
import org.hddq.bluehop.bluetooth.sortBondedDevices
import org.junit.Assert.assertEquals
import org.junit.Test

class BluetoothDevicesTest {

    @Test
    fun sortBondedDevicesPlacesAudioDevicesFirstThenAlphabetical() {
        val keyboard = BondedBluetoothDevice("Keychron K3", "00:00:00:00:00:03", isAudioDevice = false)
        val buds = BondedBluetoothDevice("Pixel Buds", "00:00:00:00:00:02", isAudioDevice = true)
        val airpods = BondedBluetoothDevice("airpods max", "00:00:00:00:00:01", isAudioDevice = true)
        val watch = BondedBluetoothDevice("Amazfit Watch", "00:00:00:00:00:04", isAudioDevice = false)

        val sorted = sortBondedDevices(listOf(keyboard, buds, airpods, watch))

        assertEquals(listOf(airpods, buds, watch, keyboard), sorted)
    }
}
