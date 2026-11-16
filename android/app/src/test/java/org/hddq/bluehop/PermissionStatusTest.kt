package org.hddq.bluehop

import org.hddq.bluehop.permissions.PermissionGrantStatus
import org.hddq.bluehop.permissions.resolvePermissionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionStatusTest {

    @Test
    fun grantedPermissionAlwaysResolvesToGranted() {
        assertEquals(
            PermissionGrantStatus.GRANTED,
            resolvePermissionStatus(
                isGranted = true,
                shouldShowRationale = false,
                hasRequestedOnce = false,
            ),
        )
        assertEquals(
            PermissionGrantStatus.GRANTED,
            resolvePermissionStatus(
                isGranted = true,
                shouldShowRationale = false,
                hasRequestedOnce = true,
            ),
        )
    }

    @Test
    fun unrequestedDeniedPermissionResolvesToNotGranted() {
        assertEquals(
            PermissionGrantStatus.NOT_GRANTED,
            resolvePermissionStatus(
                isGranted = false,
                shouldShowRationale = false,
                hasRequestedOnce = false,
            ),
        )
    }

    @Test
    fun deniedOnceWithRationaleResolvesToNotGranted() {
        assertEquals(
            PermissionGrantStatus.NOT_GRANTED,
            resolvePermissionStatus(
                isGranted = false,
                shouldShowRationale = true,
                hasRequestedOnce = true,
            ),
        )
    }

    @Test
    fun deniedWithoutRationaleAfterRequestResolvesToPermanentlyDenied() {
        assertEquals(
            PermissionGrantStatus.PERMANENTLY_DENIED,
            resolvePermissionStatus(
                isGranted = false,
                shouldShowRationale = false,
                hasRequestedOnce = true,
            ),
        )
    }
}
