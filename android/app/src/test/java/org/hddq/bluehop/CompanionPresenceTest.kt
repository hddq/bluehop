package org.hddq.bluehop

import android.companion.DevicePresenceEvent
import org.hddq.bluehop.bluetooth.formatPresenceEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionPresenceTest {

    @Test
    fun formatPresenceEvent_mapsConnectAndDisconnectEvents() {
        assertEquals(
            "Presence event: BT connected (associationId=7)",
            formatPresenceEvent(DevicePresenceEvent.EVENT_BT_CONNECTED, 7),
        )
        assertEquals(
            "Presence event: BT disconnected (associationId=7)",
            formatPresenceEvent(DevicePresenceEvent.EVENT_BT_DISCONNECTED, 7),
        )
        assertEquals(
            "Presence event: BLE appeared (associationId=3)",
            formatPresenceEvent(DevicePresenceEvent.EVENT_BLE_APPEARED, 3),
        )
        assertEquals(
            "Presence event: BLE disappeared (associationId=3)",
            formatPresenceEvent(DevicePresenceEvent.EVENT_BLE_DISAPPEARED, 3),
        )
        assertEquals(
            "Presence event: Association removed (associationId=9)",
            formatPresenceEvent(DevicePresenceEvent.EVENT_ASSOCIATION_REMOVED, 9),
        )
        assertEquals(
            "Presence event: Unknown event (99) (associationId=1)",
            formatPresenceEvent(99, 1),
        )
    }
}
