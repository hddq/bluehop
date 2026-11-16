package org.hddq.bluehop

import android.companion.DevicePresenceEvent
import org.hddq.bluehop.bluetooth.AssociationEntry
import org.hddq.bluehop.bluetooth.buildAssociationSyncPlan
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

    @Test
    fun buildAssociationSyncPlan_keepsOneAndDisassociatesDuplicates() {
        val plan = buildAssociationSyncPlan(
            associations = listOf(
                AssociationEntry(id = 1, address = "ac:80:0a:cd:82:a1"),
                AssociationEntry(id = 2, address = "11:22:33:44:55:66"),
                AssociationEntry(id = 4, address = "AC:80:0A:CD:82:A1"),
            ),
            targetAddress = "AC:80:0A:CD:82:A1",
        )

        assertEquals(1, plan.keepAssociationId)
        assertEquals(listOf(2, 4), plan.stopObservingIds)
        assertEquals(listOf(4), plan.disassociateIds)
    }
}
