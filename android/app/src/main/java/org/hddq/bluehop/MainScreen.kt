package org.hddq.bluehop

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.companion.AssociationInfo
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import org.hddq.bluehop.bluetooth.HopCoordinator
import org.hddq.bluehop.bluetooth.HopOutcome
import org.hddq.bluehop.bluetooth.HopProgress
import org.hddq.bluehop.bluetooth.SelectedBluetoothDevice
import org.hddq.bluehop.bluetooth.findAssociationForAddress
import org.hddq.bluehop.bluetooth.loadBondedDevices
import org.hddq.bluehop.bluetooth.startObservingAssociation
import org.hddq.bluehop.bluetooth.syncObservedCompanionDevices
import org.hddq.bluehop.data.SelectedDeviceRepository
import org.hddq.bluehop.permissions.ManagedPermission
import org.hddq.bluehop.permissions.PermissionGrantStatus
import org.hddq.bluehop.permissions.resolvePermissionStatus
import org.hddq.bluehop.protocol.LocalDeviceIdentity
import org.hddq.bluehop.protocol.PairedPeerSummary
import org.hddq.bluehop.protocol.PairedPeersRepository
import org.hddq.bluehop.server.GrabClient
import org.hddq.bluehop.server.NsdPeerDiscovery
import org.hddq.bluehop.ui.theme.BlueHopTheme
import java.util.Locale

private const val GITHUB_REPOSITORY_URL = "https://github.com/hddq/bluehop"

@Composable
fun MainRoute(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val uriHandler = LocalUriHandler.current
    val coroutineScope = rememberCoroutineScope()
    val repository = remember(context) { SelectedDeviceRepository.from(context) }
    val pairedPeersRepository = remember(context) { PairedPeersRepository.from(context) }
    val bluetoothController: BluetoothController = remember(context) {
        Api37BluetoothController(context.applicationContext)
    }
    val stateObserver = remember(context) {
        BluetoothStateObserver(
            context = context.applicationContext,
            profileConfirmation = AudioProfileConfirmation(context.applicationContext),
        )
    }
    val peerDiscovery = remember(context) { NsdPeerDiscovery(context) }
    val grabClient = remember { GrabClient() }
    val hopCoordinator = remember(bluetoothController, grabClient, stateObserver) {
        HopCoordinator(
            bluetoothController = bluetoothController,
            holderClient = grabClient,
            profileConfirmation = stateObserver.profileConfirmation,
        )
    }
    val jobHolder = remember {
        object {
            var hopJob: Job? = null
        }
    }

    val managedDevices by repository.managedDevices.collectAsStateWithLifecycle(initialValue = emptyList())
    val managedAddresses = remember(managedDevices) {
        managedDevices.map { it.address.uppercase(Locale.ROOT) }.toSet()
    }
    val pairedPeers by pairedPeersRepository.peerSummaries.collectAsStateWithLifecycle(initialValue = emptyList())
    val deviceStates by stateObserver.deviceStates.collectAsStateWithLifecycle()
    var localIdentity by remember { mutableStateOf<LocalDeviceIdentity?>(null) }
    var isScreenResumed by remember { mutableStateOf(false) }
    var hopStatusByAddress by remember { mutableStateOf<Map<String, HopUiStatus>>(emptyMap()) }
    var showPairingDialog by rememberSaveable { mutableStateOf(false) }

    var hasRequestedNearbyDevices by rememberSaveable { mutableStateOf(false) }
    var isBluetoothConnectGranted by rememberSaveable {
        mutableStateOf(context.hasPermission(Manifest.permission.BLUETOOTH_CONNECT))
    }
    var isLocalNetworkGranted by rememberSaveable {
        mutableStateOf(context.hasPermission(Manifest.permission.ACCESS_LOCAL_NETWORK))
    }
    var bondedDevicesState by remember(context) {
        mutableStateOf(loadBondedDevices(context))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        val bluetoothGranted = context.hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        val localNetworkGranted = context.hasPermission(Manifest.permission.ACCESS_LOCAL_NETWORK)
        isBluetoothConnectGranted = bluetoothGranted
        isLocalNetworkGranted = localNetworkGranted
        hasRequestedNearbyDevices = !(bluetoothGranted && localNetworkGranted)
        bondedDevicesState = loadBondedDevices(context)
        stateObserver.refresh()
    }

    val associationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val association = result.data?.getParcelableExtra(
                CompanionDeviceManager.EXTRA_ASSOCIATION,
                AssociationInfo::class.java,
            )
            if (association != null) {
                startObservingAssociation(context, association)
            } else {
                syncObservedCompanionDevices(context, managedAddresses)
            }
        }
    }

    LaunchedEffect(pairedPeersRepository) {
        localIdentity = pairedPeersRepository.getOrCreateIdentity()
    }

    LaunchedEffect(managedAddresses) {
        syncObservedCompanionDevices(context, managedAddresses)
    }

    LifecycleResumeEffect(context) {
        isScreenResumed = true
        val bluetoothGranted = context.hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        val localNetworkGranted = context.hasPermission(Manifest.permission.ACCESS_LOCAL_NETWORK)
        isBluetoothConnectGranted = bluetoothGranted
        isLocalNetworkGranted = localNetworkGranted
        if (bluetoothGranted && localNetworkGranted) {
            hasRequestedNearbyDevices = false
        }
        bondedDevicesState = loadBondedDevices(context)
        stateObserver.refresh()
        onPauseOrDispose {
            isScreenResumed = false
        }
    }

    DisposableEffect(peerDiscovery, isScreenResumed, isLocalNetworkGranted, showPairingDialog, localIdentity?.deviceId) {
        val myId = localIdentity?.deviceId
        if (isScreenResumed && isLocalNetworkGranted && !showPairingDialog && myId != null) {
            peerDiscovery.start(
                excludeDeviceId = myId,
                pairingOnly = false,
            )
        }
        onDispose {
            peerDiscovery.stop()
        }
    }

    DisposableEffect(stateObserver, peerDiscovery) {
        stateObserver.start()
        onDispose {
            jobHolder.hopJob?.cancel()
            peerDiscovery.shutdown()
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

    val shouldShowNearbyRationale = activity?.let {
        it.shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT) ||
            it.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_LOCAL_NETWORK)
    } ?: false

    val nearbyDevicesStatus = resolvePermissionStatus(
        isGranted = isBluetoothConnectGranted && isLocalNetworkGranted,
        shouldShowRationale = shouldShowNearbyRationale,
        hasRequestedOnce = hasRequestedNearbyDevices,
    )

    val permissions = listOf(
        ManagedPermission(
            id = "nearby_devices",
            permissions = listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_LOCAL_NETWORK,
            ),
            title = stringResource(R.string.permission_nearby_devices_title),
            description = stringResource(R.string.permission_nearby_devices_description),
            iconRes = R.drawable.ic_bluetooth,
            status = nearbyDevicesStatus,
        ),
    )

    MainScreen(
        permissions = permissions,
        managedAddresses = managedAddresses,
        bondedDevicesState = bondedDevicesState,
        onPermissionActionClick = { permission ->
            when (permission.status) {
                PermissionGrantStatus.GRANTED -> Unit
                PermissionGrantStatus.NOT_GRANTED -> {
                    permissionLauncher.launch(permission.permissions.toTypedArray())
                }
                PermissionGrantStatus.PERMANENTLY_DENIED -> {
                    context.openAppSettings()
                }
            }
        },
        onDeviceClick = { device ->
            val key = device.address.uppercase(Locale.ROOT)
            if (hopStatusByAddress[key] is HopUiStatus.InProgress) {
                return@MainScreen
            }
            val isLocallyConnected = deviceStates.entries
                .firstOrNull { (addr, _) -> addr.equals(key, ignoreCase = true) }
                ?.value
                ?.isConnected == true

            jobHolder.hopJob?.cancel()
            hopStatusByAddress = mapOf(key to HopUiStatus.InProgress(HopProgress.Searching))
            jobHolder.hopJob = coroutineScope.launch {
                try {
                    val identity = localIdentity ?: pairedPeersRepository.getOrCreateIdentity().also {
                        localIdentity = it
                    }
                    val outcome = hopCoordinator.hopToDevice(
                        targetAddress = key,
                        targetName = device.name,
                        isAudioDevice = device.isAudioDevice,
                        isLocallyConnected = isLocallyConnected,
                        localIdentity = identity,
                        peersFlow = peerDiscovery.peers,
                        hasPairedPeers = pairedPeers.isNotEmpty() && isLocalNetworkGranted,
                        peerPskProvider = { peerId -> pairedPeersRepository.getPeerPsk(peerId) },
                        onProgress = { progress ->
                            hopStatusByAddress = mapOf(key to HopUiStatus.InProgress(progress))
                        },
                    )
                    hopStatusByAddress = mapOf(key to HopUiStatus.Finished(outcome))
                    when (outcome) {
                        is HopOutcome.Connected,
                        is HopOutcome.ConnectedUnconfirmed,
                        -> stateObserver.refresh()

                        HopOutcome.NeedsAssociation -> {
                            context.associateDevice(
                                address = key,
                                managedAddresses = managedAddresses,
                                onAssociationPending = { intentSender ->
                                    associationLauncher.launch(
                                        IntentSenderRequest.Builder(intentSender).build(),
                                    )
                                },
                            )
                        }

                        HopOutcome.AlreadyConnected,
                        is HopOutcome.Failed,
                        -> Unit
                    }
                } finally {
                    if (hopStatusByAddress[key] is HopUiStatus.InProgress) {
                        hopStatusByAddress = hopStatusByAddress - key
                    }
                }
            }
        },
        onDeviceManagedChange = { device, managed ->
            val normalized = SelectedBluetoothDevice(
                name = device.name,
                address = device.address.uppercase(Locale.ROOT),
            )
            val nextAddresses = if (managed) {
                managedAddresses + normalized.address
            } else {
                managedAddresses - normalized.address
            }
            coroutineScope.launch {
                repository.setDeviceManaged(normalized, managed)
            }
            if (managed) {
                context.associateDevice(
                    address = normalized.address,
                    managedAddresses = nextAddresses,
                    onAssociationPending = { intentSender ->
                        associationLauncher.launch(
                            IntentSenderRequest.Builder(intentSender).build(),
                        )
                    },
                )
            }
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
        hopStatusByAddress = hopStatusByAddress,
        pairedPeers = pairedPeers,
        canPairHosts = isLocalNetworkGranted,
        onPairHostClick = {
            if (isLocalNetworkGranted) {
                showPairingDialog = true
            } else {
                val localNetPermission = permissions.firstOrNull {
                    Manifest.permission.ACCESS_LOCAL_NETWORK in it.permissions
                }
                if (localNetPermission != null) {
                    when (localNetPermission.status) {
                        PermissionGrantStatus.GRANTED -> showPairingDialog = true
                        PermissionGrantStatus.NOT_GRANTED -> {
                            permissionLauncher.launch(localNetPermission.permissions.toTypedArray())
                        }
                        PermissionGrantStatus.PERMANENTLY_DENIED -> context.openAppSettings()
                    }
                }
            }
        },
        onRemovePairedPeerClick = { peer ->
            coroutineScope.launch {
                pairedPeersRepository.removePeer(peer.peerId)
            }
        },
        versionName = BuildConfig.VERSION_NAME,
        onVersionClick = {
            runCatching {
                uriHandler.openUri(GITHUB_REPOSITORY_URL)
            }
        },
    )

    if (showPairingDialog) {
        PairingDialog(
            pairedPeersRepository = pairedPeersRepository,
            onDismiss = { showPairingDialog = false },
        )
    }
}

sealed interface HopUiStatus {
    data class InProgress(val progress: HopProgress) : HopUiStatus

    data class Finished(val outcome: HopOutcome) : HopUiStatus
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MainScreen(
    permissions: List<ManagedPermission>,
    managedAddresses: Set<String>,
    bondedDevicesState: BondedDevicesState,
    onPermissionActionClick: (ManagedPermission) -> Unit,
    onDeviceClick: (BondedBluetoothDevice) -> Unit,
    onDeviceManagedChange: (BondedBluetoothDevice, Boolean) -> Unit,
    onRefreshDevicesClick: () -> Unit,
    onOpenBluetoothSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.app_name),
    deviceStates: Map<String, DeviceState> = emptyMap(),
    hopStatusByAddress: Map<String, HopUiStatus> = emptyMap(),
    pairedPeers: List<PairedPeerSummary> = emptyList(),
    canPairHosts: Boolean = true,
    onPairHostClick: () -> Unit = {},
    onRemovePairedPeerClick: (PairedPeerSummary) -> Unit = {},
    versionName: String = BuildConfig.VERSION_NAME,
    onVersionClick: () -> Unit = {},
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
                        text = title,
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
                                actionLabel = permissions.firstOrNull {
                                    Manifest.permission.BLUETOOTH_CONNECT in it.permissions
                                }?.let { permission ->
                                    when (permission.status) {
                                        PermissionGrantStatus.GRANTED -> null
                                        PermissionGrantStatus.NOT_GRANTED -> stringResource(R.string.permission_action_allow)
                                        PermissionGrantStatus.PERMANENTLY_DENIED -> stringResource(R.string.permission_action_open_settings)
                                    }
                                },
                                onActionClick = {
                                    permissions.firstOrNull {
                                        Manifest.permission.BLUETOOTH_CONNECT in it.permissions
                                    }?.let(onPermissionActionClick)
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
                                val key = device.address.uppercase(Locale.ROOT)
                                val isManaged = key in managedAddresses
                                BondedDeviceListItem(
                                    device = device,
                                    deviceState = deviceStates[device.address],
                                    isManaged = isManaged,
                                    hopStatus = hopStatusByAddress[key],
                                    index = index,
                                    count = bondedDevicesState.devices.size,
                                    onClick = { onDeviceClick(device) },
                                    onManagedChange = { managed -> onDeviceManagedChange(device, managed) },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }
                }

                item(key = "paired_hosts_spacer") {
                    Spacer(modifier = Modifier.height(14.dp))
                }

                item(key = "paired_hosts_header") {
                    SectionHeader(
                        title = stringResource(R.string.paired_hosts_section_title),
                    )
                }

                if (!canPairHosts && pairedPeers.isEmpty()) {
                    item(key = "paired_hosts_permission_required") {
                        DeviceStateListItem(
                            iconRes = R.drawable.ic_devices,
                            title = stringResource(R.string.bluetooth_devices_permission_required_title),
                            description = stringResource(R.string.paired_hosts_permission_required_description),
                            actionLabel = permissions.firstOrNull {
                                Manifest.permission.ACCESS_LOCAL_NETWORK in it.permissions
                            }?.let { permission ->
                                when (permission.status) {
                                    PermissionGrantStatus.GRANTED -> null
                                    PermissionGrantStatus.NOT_GRANTED -> stringResource(R.string.permission_action_allow)
                                    PermissionGrantStatus.PERMANENTLY_DENIED -> stringResource(R.string.permission_action_open_settings)
                                }
                            },
                            onActionClick = onPairHostClick,
                        )
                    }
                } else if (pairedPeers.isEmpty()) {
                    item(key = "paired_hosts_empty") {
                        DeviceStateListItem(
                            iconRes = R.drawable.ic_devices,
                            title = stringResource(R.string.paired_hosts_empty_title),
                            description = stringResource(R.string.paired_hosts_empty_description),
                        )
                    }
                } else {
                    itemsIndexed(
                        items = pairedPeers,
                        key = { _, peer -> "peer_${peer.peerId}" },
                    ) { index, peer ->
                        PairedHostListItem(
                            peer = peer,
                            index = index,
                            count = pairedPeers.size,
                            onRemoveClick = { onRemovePairedPeerClick(peer) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }

                if (canPairHosts || pairedPeers.isNotEmpty()) {
                    item(key = "paired_hosts_pair_action") {
                        FilledTonalButton(
                            onClick = onPairHostClick,
                            shapes = ButtonDefaults.shapes(),
                            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                            modifier = Modifier
                                .animateItem()
                                .padding(top = 6.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_add),
                                contentDescription = null,
                                modifier = Modifier.size(ButtonDefaults.IconSize),
                            )
                            Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                            Text(text = stringResource(R.string.paired_hosts_action_pair))
                        }
                    }
                }

                item(key = "about_spacer") {
                    Spacer(modifier = Modifier.height(14.dp))
                }

                item(key = "about_header") {
                    SectionHeader(
                        title = stringResource(R.string.about_section_title),
                    )
                }

                item(key = "about_version") {
                    VersionListItem(
                        versionName = versionName,
                        onClick = onVersionClick,
                    )
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
        verticalAlignment = Alignment.CenterVertically,
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
    isManaged: Boolean,
    hopStatus: HopUiStatus?,
    index: Int,
    count: Int,
    onClick: () -> Unit,
    onManagedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isConnected = deviceState?.isConnected == true
    val connectionLabel = when {
        deviceState?.bredrConnected == true && deviceState.leConnected ->
            stringResource(R.string.bluetooth_state_connected_both)
        deviceState?.bredrConnected == true ->
            stringResource(R.string.bluetooth_state_connected_bredr)
        deviceState?.leConnected == true ->
            stringResource(R.string.bluetooth_state_connected_le)
        else ->
            stringResource(R.string.bluetooth_state_disconnected)
    }

    val hopLabel = when (hopStatus) {
        null -> null
        is HopUiStatus.InProgress -> when (val progress = hopStatus.progress) {
            HopProgress.Searching -> stringResource(R.string.hop_status_searching)
            is HopProgress.Releasing -> stringResource(R.string.hop_status_releasing, progress.holderName)
            is HopProgress.Connecting -> stringResource(R.string.hop_status_connecting, progress.deviceName)
            is HopProgress.ConfirmingAudio ->
                stringResource(R.string.hop_status_confirming_audio, progress.deviceName)
        }
        is HopUiStatus.Finished -> when (val outcome = hopStatus.outcome) {
            HopOutcome.AlreadyConnected -> stringResource(R.string.hop_status_already_connected)
            is HopOutcome.Connected -> if (outcome.releasedFromHolderName != null) {
                stringResource(R.string.hop_status_connected_from, outcome.releasedFromHolderName)
            } else {
                stringResource(R.string.hop_status_connected)
            }
            is HopOutcome.ConnectedUnconfirmed ->
                stringResource(R.string.hop_status_connected_unconfirmed)
            HopOutcome.NeedsAssociation ->
                stringResource(R.string.hop_status_needs_association)
            is HopOutcome.Failed -> outcome.message
        }
    }
    val hopIsError = hopStatus is HopUiStatus.Finished &&
        (hopStatus.outcome is HopOutcome.Failed || hopStatus.outcome is HopOutcome.NeedsAssociation)

    val iconContainerColor by animateColorAsState(
        targetValue = if (isManaged) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        label = "deviceIconContainer",
    )
    val iconContentColor by animateColorAsState(
        targetValue = if (isManaged) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
        label = "deviceIconContent",
    )
    val manageSwitchDescription = stringResource(
        R.string.bluetooth_switch_content_description,
        device.name,
    )

    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceBright,
        ),
        verticalAlignment = Alignment.CenterVertically,
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
                    text = hopLabel ?: connectionLabel,
                    color = when {
                        hopIsError -> MaterialTheme.colorScheme.error
                        hopStatus is HopUiStatus.InProgress || isConnected -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = if (hopLabel != null) 2 else 1,
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (hopStatus is HopUiStatus.InProgress) {
                    LoadingIndicator(
                        modifier = Modifier.size(24.dp),
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = stringResource(R.string.hop_action_connect),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                VerticalDivider(
                    modifier = Modifier.height(28.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                Switch(
                    checked = isManaged,
                    onCheckedChange = onManagedChange,
                    thumbContent = if (isManaged) {
                        {
                            Icon(
                                painter = painterResource(R.drawable.ic_check),
                                contentDescription = null,
                                modifier = Modifier.size(SwitchDefaults.IconSize),
                            )
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.semantics {
                        contentDescription = manageSwitchDescription
                    },
                )
            }
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
        verticalAlignment = Alignment.CenterVertically,
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
private fun PairedHostListItem(
    peer: PairedPeerSummary,
    index: Int,
    count: Int,
    onRemoveClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceBright,
        ),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
        leadingContent = {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_devices),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        },
        supportingContent = {
            Text(
                text = stringResource(R.string.paired_hosts_id_format, peer.peerId),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            IconButton(
                onClick = onRemoveClick,
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.paired_hosts_action_remove),
                )
            }
        },
        content = {
            Text(
                text = peer.peerName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VersionListItem(
    versionName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceBright,
        ),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
        leadingContent = {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_info),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        },
        supportingContent = {
            Text(
                text = versionName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        content = {
            Text(
                text = stringResource(R.string.about_version_title),
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

private fun Context.openBluetoothSettings() {
    val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
}

private fun Context.associateDevice(
    address: String,
    managedAddresses: Set<String>,
    onAssociationPending: (IntentSender) -> Unit,
) {
    if (!packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
        return
    }
    val companionDeviceManager = getSystemService(CompanionDeviceManager::class.java) ?: return
    if (findAssociationForAddress(companionDeviceManager.myAssociations, address) != null) {
        syncObservedCompanionDevices(this, managedAddresses)
        return
    }

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
            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                startObservingAssociation(this@associateDevice, associationInfo)
            }

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
                    permissions = listOf(
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.ACCESS_LOCAL_NETWORK,
                    ),
                    title = "Nearby devices",
                    description = "Connect to Bluetooth devices and reach paired hosts on Wi-Fi",
                    iconRes = R.drawable.ic_bluetooth,
                    status = PermissionGrantStatus.GRANTED,
                ),
            ),
            managedAddresses = setOf(
                "AA:BB:CC:DD:EE:01",
                "AA:BB:CC:DD:EE:03",
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
            onDeviceManagedChange = { _, _ -> },
            onRefreshDevicesClick = {},
            onOpenBluetoothSettingsClick = {},
            deviceStates = mapOf(
                "AA:BB:CC:DD:EE:01" to DeviceState(bredrConnected = true),
                "AA:BB:CC:DD:EE:02" to DeviceState(),
                "AA:BB:CC:DD:EE:03" to DeviceState(leConnected = true),
            ),
            pairedPeers = listOf(
                PairedPeerSummary(
                    peerId = "8f4b2c1a-9d0e-4f1a-8b2c-1234567890ab",
                    peerName = "Pixel 9 Pro",
                    pairedAtEpochSec = 1_760_000_000L,
                ),
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
                    permissions = listOf(
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.ACCESS_LOCAL_NETWORK,
                    ),
                    title = "Nearby devices",
                    description = "Connect to Bluetooth devices and reach paired hosts on Wi-Fi",
                    iconRes = R.drawable.ic_bluetooth,
                    status = PermissionGrantStatus.NOT_GRANTED,
                ),
            ),
            managedAddresses = emptySet(),
            bondedDevicesState = BondedDevicesState.PermissionRequired,
            onPermissionActionClick = {},
            onDeviceClick = {},
            onDeviceManagedChange = { _, _ -> },
            onRefreshDevicesClick = {},
            onOpenBluetoothSettingsClick = {},
        )
    }
}
