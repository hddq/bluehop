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
    fun buildAssociationSyncPlan_keepsOnePerTargetAndDisassociatesDuplicates() {
        val plan = buildAssociationSyncPlan(
            associations = listOf(
                AssociationEntry(id = 1, address = "ac:80:0a:cd:82:a1"),
                AssociationEntry(id = 2, address = "11:22:33:44:55:66"),
                AssociationEntry(id = 3, address = "aa:bb:cc:dd:ee:02"),
                AssociationEntry(id = 4, address = "AC:80:0A:CD:82:A1"),
            ),
            targetAddresses = setOf("AC:80:0A:CD:82:A1", "AA:BB:CC:DD:EE:02"),
        )

        assertEquals(listOf(1, 3), plan.keepAssociationIds)
        assertEquals(listOf(2, 4), plan.stopObservingIds)
        assertEquals(listOf(4), plan.disassociateIds)
    }

    @Test
    fun packDevicesAttribute_truncatesAtMaxBytesWithoutSplittingMac() {
        val packed = org.hddq.bluehop.server.NsdAdvertiser.packDevicesAttribute(
            addresses = listOf(
                "AA:BB:CC:DD:EE:01",
                "AA:BB:CC:DD:EE:02",
                "AA:BB:CC:DD:EE:03",
            ),
            maxBytes = 35,
        )
        assertEquals("AA:BB:CC:DD:EE:01,AA:BB:CC:DD:EE:02", packed)
    }
}
