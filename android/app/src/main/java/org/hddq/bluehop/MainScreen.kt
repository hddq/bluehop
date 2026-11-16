package org.hddq.bluehop

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.hddq.bluehop.permissions.ManagedPermission
import org.hddq.bluehop.permissions.PermissionGrantStatus
import org.hddq.bluehop.permissions.resolvePermissionStatus
import org.hddq.bluehop.ui.theme.BlueHopTheme

@Composable
fun MainRoute(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current

    var hasRequestedNearbyDevices by rememberSaveable { mutableStateOf(false) }
    var isNearbyDevicesGranted by rememberSaveable {
        mutableStateOf(context.hasPermission(Manifest.permission.BLUETOOTH_CONNECT))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasRequestedNearbyDevices = true
        isNearbyDevicesGranted = granted
    }

    LifecycleResumeEffect(context) {
        val granted = context.hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        isNearbyDevicesGranted = granted
        if (granted) {
            hasRequestedNearbyDevices = false
        }
        onPauseOrDispose {}
    }

    val shouldShowRationale = activity?.shouldShowRequestPermissionRationale(
        Manifest.permission.BLUETOOTH_CONNECT,
    ) ?: false

    val nearbyDevicesStatus = resolvePermissionStatus(
        isGranted = isNearbyDevicesGranted,
        shouldShowRationale = shouldShowRationale,
        hasRequestedOnce = hasRequestedNearbyDevices,
    )

    val permissions = listOf(
        ManagedPermission(
            id = "nearby_devices",
            permission = Manifest.permission.BLUETOOTH_CONNECT,
            title = stringResource(R.string.permission_nearby_devices_title),
            description = stringResource(R.string.permission_nearby_devices_description),
            iconRes = R.drawable.ic_bluetooth,
            status = nearbyDevicesStatus,
        ),
    )

    MainScreen(
        permissions = permissions,
        onPermissionActionClick = { permission ->
            when (permission.status) {
                PermissionGrantStatus.GRANTED -> Unit
                PermissionGrantStatus.NOT_GRANTED -> {
                    permissionLauncher.launch(permission.permission)
                }
                PermissionGrantStatus.PERMANENTLY_DENIED -> {
                    context.openAppSettings()
                }
            }
        },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MainScreen(
    permissions: List<ManagedPermission>,
    onPermissionActionClick: (ManagedPermission) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            MediumFlexibleTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                subtitle = {
                    Text(
                        text = stringResource(R.string.main_subtitle),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.permissions_section_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                ) {
                    permissions.forEachIndexed { index, item ->
                        key(item.id) {
                            PermissionListItem(
                                item = item,
                                index = index,
                                count = permissions.size,
                                onActionClick = { onPermissionActionClick(item) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PermissionListItem(
    item: ManagedPermission,
    index: Int,
    count: Int,
    onActionClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isGranted = item.status == PermissionGrantStatus.GRANTED
    val iconContainerColor by animateColorAsState(
        targetValue = if (isGranted) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        label = "permissionIconContainer",
    )
    val iconContentColor by animateColorAsState(
        targetValue = if (isGranted) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
        label = "permissionIconContent",
    )

    val supportingText = when (item.status) {
        PermissionGrantStatus.GRANTED -> stringResource(R.string.permission_status_allowed)
        PermissionGrantStatus.NOT_GRANTED -> item.description
        PermissionGrantStatus.PERMANENTLY_DENIED -> stringResource(R.string.permission_status_denied_permanently)
    }

    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceBright,
        ),
        modifier = modifier,
        leadingContent = {
            Surface(
                shape = CircleShape,
                color = iconContainerColor,
                contentColor = iconContentColor,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(item.iconRes),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        },
        supportingContent = {
            Text(text = supportingText)
        },
        trailingContent = {
            AnimatedContent(
                targetState = item.status,
                label = "permissionAction",
            ) { status ->
                when (status) {
                    PermissionGrantStatus.GRANTED -> {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.permission_status_allowed),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    }

                    PermissionGrantStatus.NOT_GRANTED -> {
                        FilledTonalButton(
                            onClick = onActionClick,
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Text(text = stringResource(R.string.permission_action_allow))
                        }
                    }

                    PermissionGrantStatus.PERMANENTLY_DENIED -> {
                        FilledTonalButton(
                            onClick = onActionClick,
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Text(text = stringResource(R.string.permission_action_open_settings))
                        }
                    }
                }
            }
        },
        content = {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
            )
        },
    )
}

private fun Context.hasPermission(permission: String): Boolean =
    checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

private fun Context.openAppSettings() {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MainScreenPendingPreview() {
    BlueHopTheme {
        MainScreen(
            permissions = listOf(
                ManagedPermission(
                    id = "nearby_devices",
                    permission = Manifest.permission.BLUETOOTH_CONNECT,
                    title = "Nearby devices",
                    description = "Find and connect to nearby Bluetooth devices",
                    iconRes = R.drawable.ic_bluetooth,
                    status = PermissionGrantStatus.NOT_GRANTED,
                ),
            ),
            onPermissionActionClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun MainScreenGrantedPreview() {
    BlueHopTheme {
        MainScreen(
            permissions = listOf(
                ManagedPermission(
                    id = "nearby_devices",
                    permission = Manifest.permission.BLUETOOTH_CONNECT,
                    title = "Nearby devices",
                    description = "Find and connect to nearby Bluetooth devices",
                    iconRes = R.drawable.ic_bluetooth,
                    status = PermissionGrantStatus.GRANTED,
                ),
            ),
            onPermissionActionClick = {},
        )
    }
}
