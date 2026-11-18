package org.hddq.bluehop.debug

import android.Manifest
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import org.hddq.bluehop.MainScreen
import org.hddq.bluehop.R
import org.hddq.bluehop.bluetooth.BondedBluetoothDevice
import org.hddq.bluehop.bluetooth.BondedDevicesState
import org.hddq.bluehop.bluetooth.DeviceState
import org.hddq.bluehop.permissions.ManagedPermission
import org.hddq.bluehop.permissions.PermissionGrantStatus
import org.hddq.bluehop.protocol.PairedPeerSummary
import org.hddq.bluehop.ui.theme.BlueHopTheme

class DemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BlueHopTheme {
                DemoScreen()
            }
        }
    }
}

private val DEMO_DEVICES = listOf(
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
)

private val DEMO_INITIAL_MANAGED_ADDRESSES = setOf(
    "AA:BB:CC:DD:EE:01",
    "AA:BB:CC:DD:EE:03",
)

private val DEMO_DEVICE_STATES = mapOf(
    "AA:BB:CC:DD:EE:01" to DeviceState(bredrConnected = true),
    "AA:BB:CC:DD:EE:02" to DeviceState(),
    "AA:BB:CC:DD:EE:03" to DeviceState(leConnected = true),
)

private val DEMO_PAIRED_PEERS = listOf(
    PairedPeerSummary(
        peerId = "8f4b2c1a-9d0e-4f1a-8b2c-1234567890ab",
        peerName = "Pixel 9 Pro",
        pairedAtEpochSec = 1_760_000_000L,
    ),
)

@Composable
fun DemoScreen(
    modifier: Modifier = Modifier,
) {
    var managedAddresses by remember { mutableStateOf(DEMO_INITIAL_MANAGED_ADDRESSES) }
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
            status = PermissionGrantStatus.GRANTED,
        ),
    )

    MainScreen(
        permissions = permissions,
        managedAddresses = managedAddresses,
        bondedDevicesState = BondedDevicesState.Ready(DEMO_DEVICES),
        onPermissionActionClick = {},
        onDeviceClick = {},
        onDeviceManagedChange = { device, managed ->
            managedAddresses = if (managed) {
                managedAddresses + device.address
            } else {
                managedAddresses - device.address
            }
        },
        onRefreshDevicesClick = {},
        onOpenBluetoothSettingsClick = {},
        modifier = modifier,
        title = stringResource(R.string.demo_app_name),
        deviceStates = DEMO_DEVICE_STATES,
        pairedPeers = DEMO_PAIRED_PEERS,
        canPairHosts = true,
        onPairHostClick = {},
        onRemovePairedPeerClick = {},
    )
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DemoScreenPreview() {
    BlueHopTheme {
        DemoScreen()
    }
}
