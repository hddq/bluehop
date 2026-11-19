package org.hddq.bluehop

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.system.OsConstants
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.hddq.bluehop.protocol.LocalDeviceIdentity
import org.hddq.bluehop.protocol.PairedPeersRepository
import org.hddq.bluehop.server.DiscoveredBlueHopPeer
import org.hddq.bluehop.server.NsdAdvertiser
import org.hddq.bluehop.server.NsdPeerDiscovery
import org.hddq.bluehop.server.PairingClient
import org.hddq.bluehop.server.PairingSessionServer
import org.hddq.bluehop.server.PairingSessionState
import org.hddq.bluehop.server.filterAndSortLocalIpAddresses
import org.hddq.bluehop.server.isUsableLocalIpAddress
import org.hddq.bluehop.server.parseManualPairingAddress
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

enum class PairingMode {
    RECEIVE,
    CONNECT,
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PairingDialog(
    pairedPeersRepository: PairedPeersRepository,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableStateOf(PairingMode.RECEIVE) }
    var localIdentity by remember { mutableStateOf<LocalDeviceIdentity?>(null) }
    var localAddresses by remember { mutableStateOf<List<String>>(emptyList()) }

    var selectedPeerKey by rememberSaveable { mutableStateOf<String?>(null) }
    var useManualAddress by rememberSaveable { mutableStateOf(false) }
    var manualAddressInput by rememberSaveable { mutableStateOf("") }
    var isPairing by remember { mutableStateOf(false) }
    var clientSas by remember { mutableStateOf<String?>(null) }
    var clientResponderName by remember { mutableStateOf<String?>(null) }
    var clientError by remember { mutableStateOf<String?>(null) }
    var clientPairedName by remember { mutableStateOf<String?>(null) }
    val pairingJobHolder = remember {
        object {
            var job: Job? = null
        }
    }

    val pairingClient = remember { PairingClient() }

    val sessionServer = remember(pairedPeersRepository) {
        PairingSessionServer(
            identityProvider = { pairedPeersRepository.getOrCreateIdentity() },
            onPeerPaired = { peer -> pairedPeersRepository.upsertPeer(peer) },
            onLog = { message -> android.util.Log.i("BlueHopPairing", message) },
        )
    }
    val nsdAdvertiser = remember(context) { NsdAdvertiser(context.applicationContext) }
    val nsdDiscovery = remember(context) { NsdPeerDiscovery(context.applicationContext) }

    val sessionState by sessionServer.state.collectAsStateWithLifecycle()
    val discoveredPeers by nsdDiscovery.peers.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        onDispose {
            pairingJobHolder.job?.cancel()
        }
    }

    LaunchedEffect(sessionServer) {
        val identity = pairedPeersRepository.getOrCreateIdentity()
        localIdentity = identity
        snapshotFlow { mode }.collectLatest { activeMode ->
            when (activeMode) {
                PairingMode.RECEIVE -> {
                    try {
                        withContext(Dispatchers.IO) {
                            val port = sessionServer.start()
                            nsdAdvertiser.start(
                                port = port,
                                deviceId = identity.deviceId,
                                deviceName = identity.deviceName,
                                pairing = true,
                            )
                        }
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) {
                            nsdAdvertiser.stop()
                            sessionServer.stop()
                        }
                    }
                }

                PairingMode.CONNECT -> {
                    try {
                        withContext(Dispatchers.IO) {
                            nsdDiscovery.start(
                                excludeDeviceId = identity.deviceId,
                                pairingOnly = true,
                            )
                        }
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) {
                            nsdDiscovery.stop()
                        }
                    }
                }
            }
        }
    }

    val isWaitingForPeer = mode == PairingMode.RECEIVE &&
        sessionState is PairingSessionState.WaitingForPeer
    LaunchedEffect(isWaitingForPeer, context) {
        if (!isWaitingForPeer) {
            localAddresses = emptyList()
            return@LaunchedEffect
        }
        val refreshSignals = Channel<Unit>(Channel.CONFLATED)
        refreshSignals.trySend(Unit)
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                refreshSignals.trySend(Unit)
            }

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: LinkProperties,
            ) {
                refreshSignals.trySend(Unit)
            }

            override fun onLost(network: Network) {
                refreshSignals.trySend(Unit)
            }
        }
        val registered = runCatching {
            connectivityManager?.registerNetworkCallback(
                NetworkRequest.Builder().build(),
                callback,
            )
            connectivityManager != null
        }.getOrDefault(false)
        try {
            for (signal in refreshSignals) {
                localAddresses = emptyList()
                localAddresses = withContext(Dispatchers.IO) {
                    resolveLocalIpAddresses(context)
                }
            }
        } finally {
            if (registered) {
                runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
            }
        }
    }

    LaunchedEffect(discoveredPeers, useManualAddress) {
        if (useManualAddress) {
            selectedPeerKey = null
        } else if (discoveredPeers.size == 1) {
            selectedPeerKey = discoveredPeers.first().stableKey
        } else if (discoveredPeers.none { it.stableKey == selectedPeerKey }) {
            selectedPeerKey = null
        }
    }

    val selectedPeer: DiscoveredBlueHopPeer? = discoveredPeers.firstOrNull {
        it.stableKey == selectedPeerKey
    }
    val usingManualEntry = useManualAddress || discoveredPeers.isEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.pairing_dialog_title))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SegmentedButton(
                        selected = mode == PairingMode.RECEIVE,
                        onClick = {
                            pairingJobHolder.job?.cancel()
                            pairingJobHolder.job = null
                            isPairing = false
                            clientSas = null
                            clientResponderName = null
                            mode = PairingMode.RECEIVE
                            clientError = null
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        label = {
                            Text(
                                text = stringResource(R.string.pairing_mode_receive),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                    SegmentedButton(
                        selected = mode == PairingMode.CONNECT,
                        onClick = {
                            mode = PairingMode.CONNECT
                            clientError = null
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        label = {
                            Text(
                                text = stringResource(R.string.pairing_mode_connect),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }

                when (mode) {
                    PairingMode.RECEIVE -> {
                        ReceivePairingContent(
                            deviceName = localIdentity?.deviceName.orEmpty(),
                            localAddresses = localAddresses,
                            sessionState = sessionState,
                            onApprove = { sessionId ->
                                coroutineScope.launch(Dispatchers.IO) {
                                    sessionServer.approveSession(sessionId)
                                }
                            },
                            onReject = { sessionId ->
                                coroutineScope.launch(Dispatchers.IO) {
                                    sessionServer.rejectSession(sessionId)
                                }
                            },
                        )
                    }

                    PairingMode.CONNECT -> {
                        ConnectPairingContent(
                            discoveredPeers = discoveredPeers,
                            selectedPeerKey = selectedPeerKey,
                            useManualAddress = useManualAddress,
                            manualAddressInput = manualAddressInput,
                            isPairing = isPairing,
                            sasCode = clientSas,
                            responderName = clientResponderName,
                            errorMessage = clientError,
                            pairedPeerName = clientPairedName,
                            onPeerSelect = { peer ->
                                useManualAddress = false
                                selectedPeerKey = peer.stableKey
                                clientError = null
                            },
                            onManualAddressSelect = {
                                useManualAddress = true
                                selectedPeerKey = null
                                clientError = null
                            },
                            onManualAddressFocus = {
                                useManualAddress = true
                                selectedPeerKey = null
                                clientError = null
                            },
                            onManualAddressChange = { raw ->
                                manualAddressInput = raw
                                if (raw.isNotBlank()) {
                                    useManualAddress = true
                                    selectedPeerKey = null
                                }
                                clientError = null
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (mode == PairingMode.CONNECT && clientPairedName == null) {
                val canSubmit = !isPairing &&
                    localIdentity != null &&
                    if (usingManualEntry) {
                        manualAddressInput.isNotBlank()
                    } else {
                        selectedPeer != null
                    }
                Button(
                    enabled = canSubmit,
                    shapes = ButtonDefaults.shapes(),
                    onClick = {
                        val identity = localIdentity ?: return@Button
                        val targetHost: String
                        val targetPort: Int
                        if (usingManualEntry) {
                            val parsed = parseManualPairingAddress(manualAddressInput)
                            val target = parsed.getOrElse { error ->
                                clientError = error.message ?: "Invalid host address"
                                return@Button
                            }
                            targetHost = target.host
                            targetPort = target.port
                        } else {
                            val target = selectedPeer ?: return@Button
                            targetHost = target.host
                            targetPort = target.port
                        }
                        pairingJobHolder.job?.cancel()
                        isPairing = true
                        clientSas = null
                        clientResponderName = null
                        clientError = null
                        pairingJobHolder.job = coroutineScope.launch {
                            try {
                                val result = pairingClient.pairWithHost(
                                    host = targetHost,
                                    port = targetPort,
                                    localIdentity = identity,
                                    onSasReady = { sas, responderName ->
                                        clientSas = sas
                                        clientResponderName = responderName
                                    },
                                )
                                result.onSuccess { peer ->
                                    pairedPeersRepository.upsertPeer(peer)
                                    clientPairedName = peer.peerName
                                    clientSas = null
                                    clientResponderName = null
                                }.onFailure { error ->
                                    clientSas = null
                                    clientResponderName = null
                                    clientError = error.message ?: "Pairing failed"
                                }
                            } finally {
                                isPairing = false
                            }
                        }
                    },
                ) {
                    Text(text = stringResource(R.string.pairing_action_connect_pair))
                }
            } else if (mode == PairingMode.RECEIVE &&
                (sessionState is PairingSessionState.Rejected ||
                    sessionState is PairingSessionState.Expired)
            ) {
                FilledTonalButton(
                    shapes = ButtonDefaults.shapes(),
                    onClick = {
                        coroutineScope.launch(Dispatchers.IO) {
                            sessionServer.resetSession()
                        }
                    },
                ) {
                    Text(text = stringResource(R.string.pairing_action_try_again))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.pairing_action_close))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ReceivePairingContent(
    deviceName: String,
    localAddresses: List<String>,
    sessionState: PairingSessionState,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (sessionState) {
            PairingSessionState.Idle -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LoadingIndicator(modifier = Modifier.size(24.dp))
                    Text(
                        text = stringResource(R.string.pairing_receive_waiting),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is PairingSessionState.WaitingForPeer -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LoadingIndicator(modifier = Modifier.size(24.dp))
                    Text(
                        text = stringResource(R.string.pairing_receive_waiting),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (deviceName.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.pairing_show_device_name, deviceName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (sessionState.port > 0) {
                    val endpoints = localAddresses.map { host ->
                        PairingClient.formatHostPort(host, sessionState.port)
                    }
                    if (endpoints.isNotEmpty()) {
                        val context = LocalContext.current
                        val clipboardManager = remember(context) {
                            context.getSystemService(ClipboardManager::class.java)
                        }
                        var copiedEndpoint by remember { mutableStateOf<String?>(null) }
                        LaunchedEffect(copiedEndpoint) {
                            if (copiedEndpoint != null) {
                                delay(2_000)
                                copiedEndpoint = null
                            }
                        }
                        val addressLabel = stringResource(R.string.pairing_show_addresses_label)
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = addressLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            endpoints.forEach { endpoint ->
                                val isCopied = copiedEndpoint == endpoint
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 12.dp, end = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        SelectionContainer(
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Text(
                                                text = endpoint,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontFamily = FontFamily.Monospace,
                                                ),
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                clipboardManager?.setPrimaryClip(
                                                    ClipData.newPlainText(
                                                        addressLabel,
                                                        endpoint,
                                                    ),
                                                )
                                                copiedEndpoint = endpoint
                                            },
                                            shapes = IconButtonDefaults.shapes(),
                                        ) {
                                            Icon(
                                                painter = painterResource(
                                                    if (isCopied) R.drawable.ic_check else R.drawable.ic_content_copy,
                                                ),
                                                contentDescription = stringResource(
                                                    R.string.pairing_action_copy_address,
                                                    endpoint,
                                                ),
                                                tint = if (isCopied) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        SelectionContainer {
                            Text(
                                text = stringResource(R.string.pairing_show_port, sessionState.port),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            is PairingSessionState.PendingConfirmation -> {
                Text(
                    text = stringResource(
                        R.string.pairing_receive_confirm_prompt,
                        sessionState.requesterName,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SasCodeCard(sas = sessionState.sas)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = { onReject(sessionState.sessionId) },
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(text = stringResource(R.string.pairing_action_reject))
                    }
                    Button(
                        onClick = { onApprove(sessionState.sessionId) },
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(text = stringResource(R.string.pairing_action_accept))
                    }
                }
            }

            is PairingSessionState.ApprovedWaitingForConfirm -> {
                SasCodeCard(sas = sessionState.sas)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LoadingIndicator(modifier = Modifier.size(20.dp))
                    Text(
                        text = stringResource(
                            R.string.pairing_receive_confirming,
                            sessionState.requesterName,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is PairingSessionState.Rejected -> {
                Text(
                    text = sessionState.message.ifBlank {
                        stringResource(R.string.pairing_receive_rejected)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            is PairingSessionState.Expired -> {
                Text(
                    text = stringResource(R.string.pairing_receive_expired),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            is PairingSessionState.Completed -> {
                PairedSuccessCard(peerName = sessionState.peer.peerName)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConnectPairingContent(
    discoveredPeers: List<DiscoveredBlueHopPeer>,
    selectedPeerKey: String?,
    useManualAddress: Boolean,
    manualAddressInput: String,
    isPairing: Boolean,
    sasCode: String?,
    responderName: String?,
    errorMessage: String?,
    pairedPeerName: String?,
    onPeerSelect: (DiscoveredBlueHopPeer) -> Unit,
    onManualAddressSelect: () -> Unit,
    onManualAddressFocus: () -> Unit,
    onManualAddressChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (pairedPeerName != null) {
            PairedSuccessCard(peerName = pairedPeerName)
            return@Column
        }

        if (isPairing && sasCode != null) {
            Text(
                text = stringResource(
                    R.string.pairing_connect_sas_prompt,
                    responderName.orEmpty(),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SasCodeCard(sas = sasCode)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LoadingIndicator(modifier = Modifier.size(20.dp))
                Text(
                    text = stringResource(
                        R.string.pairing_receive_confirming,
                        responderName.orEmpty(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        if (discoveredPeers.isEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LoadingIndicator(modifier = Modifier.size(24.dp))
                Text(
                    text = stringResource(R.string.pairing_connect_searching),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val itemCount = discoveredPeers.size + 1
            Column(
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                discoveredPeers.forEachIndexed { index, peer ->
                    val selected = !useManualAddress && peer.stableKey == selectedPeerKey
                    SegmentedListItem(
                        selected = selected,
                        enabled = !isPairing,
                        onClick = { onPeerSelect(peer) },
                        shapes = ListItemDefaults.segmentedShapes(
                            index = index,
                            count = itemCount,
                        ),
                        colors = ListItemDefaults.segmentedColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                        leadingContent = {
                            Surface(
                                shape = CircleShape,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.secondaryContainer
                                },
                                contentColor = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_devices),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        },
                        supportingContent = {
                            Text(
                                text = PairingClient.formatHostPort(peer.host, peer.port),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            RadioButton(
                                selected = selected,
                                onClick = null,
                            )
                        },
                        content = {
                            Text(
                                text = peer.displayName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }

                SegmentedListItem(
                    selected = useManualAddress,
                    enabled = !isPairing,
                    onClick = onManualAddressSelect,
                    shapes = ListItemDefaults.segmentedShapes(
                        index = discoveredPeers.size,
                        count = itemCount,
                    ),
                    colors = ListItemDefaults.segmentedColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    leadingContent = {
                        Surface(
                            shape = CircleShape,
                            color = if (useManualAddress) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            },
                            contentColor = if (useManualAddress) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_devices),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    },
                    trailingContent = {
                        RadioButton(
                            selected = useManualAddress,
                            onClick = null,
                        )
                    },
                    content = {
                        Text(
                            text = stringResource(R.string.pairing_enter_manual_option),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }

        if (discoveredPeers.isEmpty() || useManualAddress) {
            OutlinedTextField(
                value = manualAddressInput,
                onValueChange = onManualAddressChange,
                label = { Text(text = stringResource(R.string.pairing_enter_address_label)) },
                placeholder = { Text(text = stringResource(R.string.pairing_enter_address_placeholder)) },
                singleLine = true,
                enabled = !isPairing,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            onManualAddressFocus()
                        }
                    },
            )
        }

        if (isPairing) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LoadingIndicator(modifier = Modifier.size(20.dp))
                Text(
                    text = stringResource(R.string.pairing_status_pairing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (errorMessage != null) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SasCodeCard(
    sas: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = formatSas(sas),
                style = MaterialTheme.typography.displaySmall.copy(
                    fontFamily = FontFamily.Monospace,
                ),
            )
        }
    }
}

@Composable
private fun PairedSuccessCard(
    peerName: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.pairing_status_success, peerName),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

private fun formatSas(sas: String): String =
    if (sas.length == 6) "${sas.substring(0, 3)} ${sas.substring(3, 6)}" else sas

internal fun resolveLocalIpAddresses(context: Context): List<String> {
    val excludedLinkAddresses = mutableSetOf<InetAddress>()
    val activeLanIps = runCatching {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
            ?: return@runCatching emptyList()
        val activeNetwork = connectivityManager.activeNetwork ?: return@runCatching emptyList()
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
            ?: return@runCatching emptyList()
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        ) {
            return@runCatching emptyList()
        }
        val props = connectivityManager.getLinkProperties(activeNetwork)
            ?: return@runCatching emptyList()
        val invalidFlags = OsConstants.IFA_F_DEPRECATED or
            OsConstants.IFA_F_TENTATIVE or
            OsConstants.IFA_F_DADFAILED
        val nonTemporary = mutableListOf<InetAddress>()
        val temporaryFallback = mutableListOf<InetAddress>()
        for (linkAddress in props.linkAddresses) {
            val address = linkAddress.address
            val flags = linkAddress.flags
            if ((flags and invalidFlags) != 0) {
                excludedLinkAddresses += address
            } else if (address is Inet6Address && (flags and OsConstants.IFA_F_TEMPORARY) != 0) {
                excludedLinkAddresses += address
                temporaryFallback += address
            } else {
                nonTemporary += address
            }
        }
        val hasStableIpv6 = nonTemporary.any { it is Inet6Address && isUsableLocalIpAddress(it) }
        val selected = if (hasStableIpv6) {
            nonTemporary
        } else {
            excludedLinkAddresses.removeAll(temporaryFallback.toSet())
            nonTemporary + temporaryFallback
        }
        filterAndSortLocalIpAddresses(selected, excludedLinkAddresses)
    }.getOrDefault(emptyList())

    val interfaceIps = runCatching {
        NetworkInterface.getNetworkInterfaces()
            ?.toList()
            .orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .filterNot { iface ->
                val name = iface.name.orEmpty()
                name.contains("rmnet", ignoreCase = true) ||
                    name.startsWith("ccmni", ignoreCase = true) ||
                    name.startsWith("dummy", ignoreCase = true)
            }
            .sortedBy { iface ->
                val name = iface.name.orEmpty()
                when {
                    name.startsWith("wlan", ignoreCase = true) ||
                        name.startsWith("ap", ignoreCase = true) -> 0
                    name.startsWith("eth", ignoreCase = true) ||
                        name.startsWith("en", ignoreCase = true) -> 1
                    else -> 2
                }
            }
            .flatMap { iface ->
                filterAndSortLocalIpAddresses(
                    addresses = iface.inetAddresses.toList(),
                    excludedAddresses = excludedLinkAddresses,
                )
            }
    }.getOrDefault(emptyList())

    return (activeLanIps + interfaceIps).distinct()
}
