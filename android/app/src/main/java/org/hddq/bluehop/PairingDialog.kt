package org.hddq.bluehop

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.hddq.bluehop.protocol.LocalDeviceIdentity
import org.hddq.bluehop.protocol.PairedPeersRepository
import org.hddq.bluehop.protocol.PairingCrypto
import org.hddq.bluehop.server.DiscoveredBlueHopPeer
import org.hddq.bluehop.server.NsdAdvertiser
import org.hddq.bluehop.server.NsdPeerDiscovery
import org.hddq.bluehop.server.PairingClient
import org.hddq.bluehop.server.PairingSessionServer
import org.hddq.bluehop.server.PairingSessionState

enum class PairingMode {
    SHOW_CODE,
    ENTER_CODE,
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PairingDialog(
    pairedPeersRepository: PairedPeersRepository,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableStateOf(PairingMode.SHOW_CODE) }
    var localIdentity by remember { mutableStateOf<LocalDeviceIdentity?>(null) }

    var selectedPeerKey by rememberSaveable { mutableStateOf<String?>(null) }
    var pinInput by rememberSaveable { mutableStateOf("") }
    var isPairing by remember { mutableStateOf(false) }
    var clientError by remember { mutableStateOf<String?>(null) }
    var clientPairedName by remember { mutableStateOf<String?>(null) }

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

    LaunchedEffect(sessionServer) {
        val identity = pairedPeersRepository.getOrCreateIdentity()
        localIdentity = identity
        snapshotFlow { mode }.collectLatest { activeMode ->
            when (activeMode) {
                PairingMode.SHOW_CODE -> {
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

                PairingMode.ENTER_CODE -> {
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

    LaunchedEffect(discoveredPeers) {
        if (discoveredPeers.size == 1) {
            selectedPeerKey = discoveredPeers.first().stableKey
        } else if (discoveredPeers.none { it.stableKey == selectedPeerKey }) {
            selectedPeerKey = null
        }
    }

    val selectedPeer: DiscoveredBlueHopPeer? = discoveredPeers.firstOrNull {
        it.stableKey == selectedPeerKey
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.pairing_dialog_title))
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SegmentedButton(
                        selected = mode == PairingMode.SHOW_CODE,
                        onClick = {
                            mode = PairingMode.SHOW_CODE
                            clientError = null
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        label = {
                            Text(
                                text = stringResource(R.string.pairing_mode_show_code),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                    SegmentedButton(
                        selected = mode == PairingMode.ENTER_CODE,
                        onClick = {
                            mode = PairingMode.ENTER_CODE
                            clientError = null
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        label = {
                            Text(
                                text = stringResource(R.string.pairing_mode_enter_code),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }

                when (mode) {
                    PairingMode.SHOW_CODE -> {
                        ShowCodeContent(
                            deviceName = localIdentity?.deviceName.orEmpty(),
                            sessionState = sessionState,
                        )
                    }

                    PairingMode.ENTER_CODE -> {
                        EnterCodeContent(
                            discoveredPeers = discoveredPeers,
                            selectedPeerKey = selectedPeerKey,
                            pinInput = pinInput,
                            isPairing = isPairing,
                            errorMessage = clientError,
                            pairedPeerName = clientPairedName,
                            onPeerSelect = { peer ->
                                selectedPeerKey = peer.stableKey
                                clientError = null
                            },
                            onPinChange = { raw ->
                                pinInput = PairingCrypto.normalizePin(raw).take(6)
                                clientError = null
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (mode == PairingMode.ENTER_CODE && clientPairedName == null) {
                val canSubmit = !isPairing &&
                    localIdentity != null &&
                    selectedPeer != null &&
                    pinInput.length == 6
                Button(
                    enabled = canSubmit,
                    shapes = ButtonDefaults.shapes(),
                    onClick = {
                        val identity = localIdentity ?: return@Button
                        val target = selectedPeer ?: return@Button
                        isPairing = true
                        clientError = null
                        coroutineScope.launch {
                            val result = pairingClient.pairWithHost(
                                host = target.host,
                                port = target.port,
                                pin = pinInput,
                                localIdentity = identity,
                            )
                            isPairing = false
                            result.onSuccess { peer ->
                                pairedPeersRepository.upsertPeer(peer)
                                clientPairedName = peer.peerName
                            }.onFailure { error ->
                                clientError = error.message ?: "Pairing failed"
                            }
                        }
                    },
                ) {
                    Text(text = stringResource(R.string.pairing_action_connect_pair))
                }
            } else if (mode == PairingMode.SHOW_CODE &&
                sessionState is PairingSessionState.LockedOut
            ) {
                FilledTonalButton(
                    shapes = ButtonDefaults.shapes(),
                    onClick = {
                        coroutineScope.launch(Dispatchers.IO) {
                            sessionServer.regenerateSession()
                        }
                    },
                ) {
                    Text(text = stringResource(R.string.pairing_action_new_code))
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
private fun ShowCodeContent(
    deviceName: String,
    sessionState: PairingSessionState,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
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
                        text = stringResource(R.string.pairing_show_waiting),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is PairingSessionState.WaitingForPeer -> {
                Text(
                    text = stringResource(R.string.pairing_show_waiting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = formatPin(sessionState.pin),
                            style = MaterialTheme.typography.displaySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                        )
                    }
                }
                if (deviceName.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.pairing_show_device_name, deviceName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (sessionState.attemptsRemaining < PairingSessionServer.DEFAULT_MAX_ATTEMPTS) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.pairing_show_attempts_left,
                            sessionState.attemptsRemaining,
                            sessionState.attemptsRemaining,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            is PairingSessionState.LockedOut -> {
                Text(
                    text = stringResource(R.string.pairing_show_locked_out),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            is PairingSessionState.Completed -> {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.fillMaxWidth(),
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
                            text = stringResource(
                                R.string.pairing_status_success,
                                sessionState.peer.peerName,
                            ),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EnterCodeContent(
    discoveredPeers: List<DiscoveredBlueHopPeer>,
    selectedPeerKey: String?,
    pinInput: String,
    isPairing: Boolean,
    errorMessage: String?,
    pairedPeerName: String?,
    onPeerSelect: (DiscoveredBlueHopPeer) -> Unit,
    onPinChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (pairedPeerName != null) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.fillMaxWidth(),
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
                        text = stringResource(R.string.pairing_status_success, pairedPeerName),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
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
                    text = stringResource(R.string.pairing_enter_searching),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                discoveredPeers.forEachIndexed { index, peer ->
                    val selected = peer.stableKey == selectedPeerKey
                    SegmentedListItem(
                        selected = selected,
                        onClick = { onPeerSelect(peer) },
                        shapes = ListItemDefaults.segmentedShapes(
                            index = index,
                            count = discoveredPeers.size,
                        ),
                        colors = ListItemDefaults.segmentedColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
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
                                text = "${peer.host}:${peer.port}",
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
            }
        }

        OutlinedTextField(
            value = pinInput,
            onValueChange = onPinChange,
            label = { Text(text = stringResource(R.string.pairing_enter_pin_label)) },
            singleLine = true,
            enabled = !isPairing,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

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

private fun formatPin(pin: String): String =
    if (pin.length == 6) "${pin.substring(0, 3)} ${pin.substring(3, 6)}" else pin
