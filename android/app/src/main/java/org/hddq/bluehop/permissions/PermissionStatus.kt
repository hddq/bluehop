package org.hddq.bluehop.permissions

import androidx.annotation.DrawableRes

enum class PermissionGrantStatus {
    GRANTED,
    NOT_GRANTED,
    PERMANENTLY_DENIED,
}

data class ManagedPermission(
    val id: String,
    val permission: String,
    val title: String,
    val description: String,
    @param:DrawableRes val iconRes: Int,
    val status: PermissionGrantStatus,
)

fun resolvePermissionStatus(
    isGranted: Boolean,
    shouldShowRationale: Boolean,
    hasRequestedOnce: Boolean,
): PermissionGrantStatus = when {
    isGranted -> PermissionGrantStatus.GRANTED
    hasRequestedOnce && !shouldShowRationale -> PermissionGrantStatus.PERMANENTLY_DENIED
    else -> PermissionGrantStatus.NOT_GRANTED
}
