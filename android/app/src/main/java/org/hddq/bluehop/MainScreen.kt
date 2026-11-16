package org.hddq.bluehop

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.hddq.bluehop.bluetooth.Api37BluetoothController
import org.hddq.bluehop.bluetooth.AudioProfileConfirmation
import org.hddq.bluehop.bluetooth.BluetoothController
import org.hddq.bluehop.bluetooth.BluetoothStateObserver
import org.hddq.bluehop.bluetooth.BondedBluetoothDevice
import org.hddq.bluehop.bluetooth.BondedDevicesState
import org.hddq.bluehop.bluetooth.DeviceState
import org.hddq.bluehop.bluetooth.SelectedBluetoothDevice
import org.hddq.bluehop.bluetooth.loadBondedDevices
import org.hddq.bluehop.data.SelectedDeviceRepository
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
    val coroutineScope = rememberCoroutineScope()
    val repository = remember(context) { SelectedDeviceRepository.from(context) }
    val bluetoothController: BluetoothController = remember(context) {
        Api37BluetoothController(context.applicationContext)
    }
    val stateObserver = remember(context) {
        BluetoothStateObserver(
            context = context.applicationContext,
            profileConfirmation = AudioProfileConfirmation(context.applicationContext),
        )
    }
    val debugConfirmationHolder = remember {
        object {
            var job: Job? = null
        }
    }

    val selectedDevice by repository.selectedDevice.collectAsStateWithLifecycle(initialValue = null)
    val deviceStates by stateObserver.deviceStates.collectAsStateWithLifecycle()
    var debugControlStatus by remember { mutableStateOf<String?>(null) }

    var hasRequestedNearbyDevices by rememberSaveable { mutableStateOf(false) }
    var isNearbyDevicesGranted by rememberSaveable {
        mutableStateOf(context.hasPermission(Manifest.permission.BLUETOOTH_CONNECT))
    }
    var bondedDevicesState by remember(context) {
        mutableStateOf(loadBondedDevices(context))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasRequestedNearbyDevices = true
        isNearbyDevicesGranted = granted
        bondedDevicesState = loadBondedDevices(context)
        stateObserver.refresh()
    }

    val associationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { _ -> }

    LifecycleResumeEffect(context) {
        val granted = context.hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        isNearbyDevicesGranted = granted
        if (granted) {
            hasRequestedNearbyDevices = false
        }
        bondedDevicesState = loadBondedDevices(context)
        stateObserver.refresh()
        onPauseOrDispose {}
    }

    DisposableEffect(stateObserver) {
        stateObserver.start()
        onDispose {
            debugConfirmationHolder.job?.cancel()
            stateObserver.stop()
        }
    }

    DisposableEffect(context) {
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                bondedDevicesState = loadBondedDevices(context)
                stateObserver.refresh()
            }
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        onDispose {
            context.unregisterReceiver(receiver)
        }
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

    val waitingAudioProfileText = stringResource(R.string.debug_waiting_audio_profile)
    val audioProfileConnectedText = stringResource(R.string.debug_audio_profile_connected)
    val audioProfileDisconnectedText = stringResource(R.string.debug_audio_profile_disconnected)
    val audioProfileTimeoutText = stringResource(R.string.debug_audio_profile_timeout)

    MainScreen(
        permissions = permissions,
        selectedDevice = selectedDevice,
        bondedDevicesState = bondedDevicesState,
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
        onDeviceClick = { device ->
            coroutineScope.launch {
                repository.selectDevice(
                    SelectedBluetoothDevice(
                        name = device.name,
                        address = device.address,
                    ),
                )
            }
            context.associateDevice(
                address = device.address,
                onAssociationPending = { intentSender ->
                    associationLauncher.launch(
                        IntentSenderRequest.Builder(intentSender).build(),
                    )
                },
            )
        },
        onRefreshDevicesClick = {
            bondedDevicesState = loadBondedDevices(context)
            stateObserver.refresh()
        },
        onOpenBluetoothSettingsClick = {
            context.openBluetoothSettings()
        },
        modifier = modifier,
        deviceStates = deviceStates,
        debugControlStatus = debugControlStatus,
        onConnectClick = {
            selectedDevice?.let { device ->
                debugConfirmationHolder.job?.cancel()
                val result = bluetoothController.connect(device.address)
                val confirmation = stateObserver.profileConfirmation
                val isAudioDevice = (bondedDevicesState as? BondedDevicesState.Ready)
                    ?.devices
                    ?.firstOrNull { it.address == device.address }
                    ?.isAudioDevice == true
                if (result.isSuccess && isAudioDevice && confirmation != null) {
                    debugControlStatus = "${result.message} • $waitingAudioProfileText"
                    debugConfirmationHolder.job = coroutineScope.launch {
                        val confirmed = confirmation.awaitConfirmation(
                            address = device.address,
                            targetConnected = true,
                        )
                        debugControlStatus = if (confirmed) {
                            "${result.message} • $audioProfileConnectedText"
                        } else {
                            "${result.message} • $audioProfileTimeoutText"
                        }
                    }
                } else {
                    debugControlStatus = result.message
                }
            }
        },
        onDisconnectClick = {
            selectedDevice?.let { device ->
                debugConfirmationHolder.job?.cancel()
                val result = bluetoothController.disconnect(device.address)
                val confirmation = stateObserver.profileConfirmation
                val isAudioDevice = (bondedDevicesState as? BondedDevicesState.Ready)
                    ?.devices
                    ?.firstOrNull { it.address == device.address }
                    ?.isAudioDevice == true
                if (result.isSuccess && isAudioDevice && confirmation != null) {
                    debugControlStatus = "${result.message} • $waitingAudioProfileText"
                    debugConfirmationHolder.job = coroutineScope.launch {
                        val confirmed = confirmation.awaitConfirmation(
                            address = device.address,
                            targetConnected = false,
                        )
                        debugControlStatus = if (confirmed) {
                            "${result.message} • $audioProfileDisconnectedText"
                        } else {
                            "${result.message} • $audioProfileTimeoutText"
                        }
                    }
                } else {
                    debugControlStatus = result.message
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MainScreen(
    permissions: List<ManagedPermission>,
    selectedDevice: SelectedBluetoothDevice?,
    bondedDevicesState: BondedDevicesState,
    onPermissionActionClick: (ManagedPermission) -> Unit,
    onDeviceClick: (BondedBluetoothDevice) -> Unit,
    onRefreshDevicesClick: () -> Unit,
    onOpenBluetoothSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    deviceStates: Map<String, DeviceState> = emptyMap(),
    debugControlStatus: String? = null,
    onConnectClick: () -> Unit = {},
    onDisconnectClick: () -> Unit = {},
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
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                item(key = "permissions_header") {
                    SectionHeader(
                        title = stringResource(R.string.permissions_section_title),
                    )
                }

                itemsIndexed(
                    items = permissions,
                    key = { _, item -> "permission_${item.id}" },
                ) { index, item ->
                    PermissionListItem(
                        item = item,
                        index = index,
                        count = permissions.size,
                        onActionClick = { onPermissionActionClick(item) },
                    )
                }

                item(key = "paired_devices_spacer") {
                    Spacer(modifier = Modifier.height(14.dp))
                }

                item(key = "paired_devices_header") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SectionHeader(
                            title = stringResource(R.string.bluetooth_devices_section_title),
                            modifier = Modifier.weight(1f),
                        )
                        if (bondedDevicesState !is BondedDevicesState.PermissionRequired) {
                            IconButton(
                                onClick = onRefreshDevicesClick,
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_refresh),
                                    contentDescription = stringResource(R.string.bluetooth_action_refresh),
                                )
                            }
                        }
                    }
                }

                when (bondedDevicesState) {
                    BondedDevicesState.PermissionRequired -> {
                        item(key = "devices_permission_required") {
                            DeviceStateListItem(
                                iconRes = R.drawable.ic_bluetooth,
                                title = stringResource(R.string.bluetooth_devices_permission_required_title),
                                description = stringResource(R.string.bluetooth_devices_permission_required_description),
                                actionLabel = permissions.firstOrNull()?.let { permission ->
                                    when (permission.status) {
                                        PermissionGrantStatus.GRANTED -> null
                                        PermissionGrantStatus.NOT_GRANTED -> stringResource(R.string.permission_action_allow)
                                        PermissionGrantStatus.PERMANENTLY_DENIED -> stringResource(R.string.permission_action_open_settings)
                                    }
                                },
                                onActionClick = {
                                    permissions.firstOrNull()?.let(onPermissionActionClick)
                                },
                            )
                        }
                    }

                    BondedDevicesState.BluetoothUnavailable -> {
                        item(key = "devices_unavailable") {
                            DeviceStateListItem(
                                iconRes = R.drawable.ic_bluetooth,
                                title = stringResource(R.string.bluetooth_devices_unavailable_title),
                                description = stringResource(R.string.bluetooth_devices_unavailable_description),
                            )
                        }
                    }

                    BondedDevicesState.BluetoothDisabled -> {
                        item(key = "devices_disabled") {
                            DeviceStateListItem(
                                iconRes = R.drawable.ic_bluetooth,
                                title = stringResource(R.string.bluetooth_devices_disabled_title),
                                description = stringResource(R.string.bluetooth_devices_disabled_description),
                                actionLabel = stringResource(R.string.bluetooth_action_open_settings),
                                onActionClick = onOpenBluetoothSettingsClick,
                            )
                        }
                    }

                    is BondedDevicesState.Ready -> {
                        if (bondedDevicesState.devices.isEmpty()) {
                            item(key = "devices_empty") {
                                DeviceStateListItem(
                                    iconRes = R.drawable.ic_bluetooth,
                                    title = stringResource(R.string.bluetooth_devices_empty_title),
                                    description = stringResource(R.string.bluetooth_devices_empty_description),
                                    actionLabel = stringResource(R.string.bluetooth_action_open_settings),
                                    onActionClick = onOpenBluetoothSettingsClick,
                                )
                            }
                        } else {
                            itemsIndexed(
                                items = bondedDevicesState.devices,
                                key = { _, device -> "device_${device.address}" },
                            ) { index, device ->
                                val isSelected = selectedDevice?.address == device.address
                                BondedDeviceListItem(
                                    device = device,
                                    deviceState = deviceStates[device.address],
                                    selected = isSelected,
                                    index = index,
                                    count = bondedDevicesState.devices.size,
                                    onClick = { onDeviceClick(device) },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }
                }

                if (BuildConfig.DEBUG) {
                    item(key = "debug_controls_spacer") {
                        Spacer(modifier = Modifier.height(14.dp))
                    }

                    item(key = "debug_controls") {
                        DebugBluetoothControls(
                            enabled = selectedDevice != null,
                            statusMessage = debugControlStatus,
                            onConnectClick = onConnectClick,
                            onDisconnectClick = onDisconnectClick,
                        )
                    }
                }

                item(key = "bottom_spacer") {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BondedDeviceListItem(
    device: BondedBluetoothDevice,
    deviceState: DeviceState?,
    selected: Boolean,
    index: Int,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isConnected = deviceState?.isConnected == true
    val statusLabel = when {
        deviceState?.bredrConnected == true && deviceState.leConnected ->
            stringResource(R.string.bluetooth_state_connected_both)
        deviceState?.bredrConnected == true ->
            stringResource(R.string.bluetooth_state_connected_bredr)
        deviceState?.leConnected == true ->
            stringResource(R.string.bluetooth_state_connected_le)
        else ->
            stringResource(R.string.bluetooth_state_disconnected)
    }

    val iconContainerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        label = "deviceIconContainer",
    )
    val iconContentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
        label = "deviceIconContent",
    )

    SegmentedListItem(
        selected = selected,
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.segmentedColors(
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
                        painter = painterResource(
                            if (device.isAudioDevice) R.drawable.ic_headphones else R.drawable.ic_bluetooth,
                        ),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        },
        supportingContent = {
            Column {
                Text(
                    text = statusLabel,
                    color = if (isConnected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = device.address,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        trailingContent = {
            RadioButton(
                selected = selected,
                onClick = null,
            )
        },
        content = {
            Text(
                text = device.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DeviceStateListItem(
    iconRes: Int,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceBright,
        ),
        modifier = modifier,
        leadingContent = {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        },
        supportingContent = {
            Text(text = description)
        },
        trailingContent = if (actionLabel != null && onActionClick != null) {
            {
                FilledTonalButton(
                    onClick = onActionClick,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(text = actionLabel)
                }
            }
        } else {
            null
        },
        content = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DebugBluetoothControls(
    enabled: Boolean,
    statusMessage: String?,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onConnectClick,
                enabled = enabled,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.weight(1f),
            ) {
                Text(text = stringResource(R.string.debug_action_connect))
            }
            FilledTonalButton(
                onClick = onDisconnectClick,
                enabled = enabled,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.weight(1f),
            ) {
                Text(text = stringResource(R.string.debug_action_disconnect))
            }
        }
        if (statusMessage != null) {
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
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

private fun Context.openBluetoothSettings() {
    val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
}

private fun Context.associateDevice(
    address: String,
    onAssociationPending: (IntentSender) -> Unit,
) {
    val companionDeviceManager = getSystemService(CompanionDeviceManager::class.java) ?: return
    val deviceFilter = BluetoothDeviceFilter.Builder()
        .setAddress(address)
        .build()
    val request = AssociationRequest.Builder()
        .addDeviceFilter(deviceFilter)
        .setSingleDevice(true)
        .build()

    companionDeviceManager.associate(
        request,
        mainExecutor,
        object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                onAssociationPending(intentSender)
            }

            override fun onFailure(error: CharSequence?) = Unit
        },
    )
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MainScreenWithDevicesPreview() {
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
            selectedDevice = SelectedBluetoothDevice(
                name = "Sony WH-1000XM5",
                address = "AA:BB:CC:DD:EE:01",
            ),
            bondedDevicesState = BondedDevicesState.Ready(
                devices = listOf(
                    BondedBluetoothDevice(
                        name = "Sony WH-1000XM5",
                        address = "AA:BB:CC:DD:EE:01",
                        isAudioDevice = true,
                    ),
                    BondedBluetoothDevice(
                        name = "Pixel Buds Pro 2",
                        address = "AA:BB:CC:DD:EE:02",
                        isAudioDevice = true,
                    ),
                    BondedBluetoothDevice(
                        name = "Keychron K3 Pro",
                        address = "AA:BB:CC:DD:EE:03",
                        isAudioDevice = false,
                    ),
                ),
            ),
            onPermissionActionClick = {},
            onDeviceClick = {},
            onRefreshDevicesClick = {},
            onOpenBluetoothSettingsClick = {},
            deviceStates = mapOf(
                "AA:BB:CC:DD:EE:01" to DeviceState(bredrConnected = true),
                "AA:BB:CC:DD:EE:02" to DeviceState(),
                "AA:BB:CC:DD:EE:03" to DeviceState(leConnected = true),
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun MainScreenPermissionRequiredPreview() {
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
            selectedDevice = null,
            bondedDevicesState = BondedDevicesState.PermissionRequired,
            onPermissionActionClick = {},
            onDeviceClick = {},
            onRefreshDevicesClick = {},
            onOpenBluetoothSettingsClick = {},
        )
    }
}
