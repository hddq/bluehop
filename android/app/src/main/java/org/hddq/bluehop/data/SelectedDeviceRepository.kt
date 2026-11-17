package org.hddq.bluehop.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import org.hddq.bluehop.bluetooth.SelectedBluetoothDevice
import org.hddq.bluehop.protocol.ProtocolJson
import java.io.IOException
import java.util.Locale

private val Context.selectedDeviceDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "selected_bluetooth_device",
)

class SelectedDeviceRepository(
    private val dataStore: DataStore<Preferences>,
) {
    val managedDevices: Flow<List<SelectedBluetoothDevice>> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            decodeManagedDevices(preferences)
        }

    val selectedDevice: Flow<SelectedBluetoothDevice?> = managedDevices.map { devices ->
        devices.firstOrNull()
    }

    suspend fun setDeviceManaged(device: SelectedBluetoothDevice, managed: Boolean) {
        val normalized = device.copy(address = device.address.uppercase(Locale.ROOT))
        dataStore.edit { preferences ->
            val current = decodeManagedDevices(preferences)
            val withoutTarget = current.filterNot {
                it.address.equals(normalized.address, ignoreCase = true)
            }
            val next = if (managed) withoutTarget + normalized else withoutTarget
            writeManagedDevices(preferences, next)
        }
    }

    suspend fun selectDevice(device: SelectedBluetoothDevice) {
        setDeviceManaged(device = device, managed = true)
    }

    suspend fun clearSelectedDevice() {
        dataStore.edit { preferences ->
            preferences.remove(KEY_MANAGED_DEVICES_JSON)
            preferences.remove(KEY_DEVICE_NAME)
            preferences.remove(KEY_DEVICE_ADDRESS)
        }
    }

    private fun decodeManagedDevices(preferences: Preferences): List<SelectedBluetoothDevice> {
        val rawJson = preferences[KEY_MANAGED_DEVICES_JSON]
        if (rawJson != null) {
            return try {
                ProtocolJson.codec.decodeFromString<List<SelectedBluetoothDevice>>(rawJson)
                    .filter { it.address.isNotBlank() }
                    .distinctBy { it.address.uppercase(Locale.ROOT) }
            } catch (_: SerializationException) {
                emptyList()
            } catch (_: IllegalArgumentException) {
                emptyList()
            }
        }

        val legacyAddress = preferences[KEY_DEVICE_ADDRESS]?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        val legacyName = preferences[KEY_DEVICE_NAME]?.takeIf { it.isNotBlank() } ?: legacyAddress
        return listOf(
            SelectedBluetoothDevice(
                name = legacyName,
                address = legacyAddress.uppercase(Locale.ROOT),
            ),
        )
    }

    private fun writeManagedDevices(
        preferences: androidx.datastore.preferences.core.MutablePreferences,
        devices: List<SelectedBluetoothDevice>,
    ) {
        if (devices.isEmpty()) {
            preferences[KEY_MANAGED_DEVICES_JSON] = "[]"
        } else {
            preferences[KEY_MANAGED_DEVICES_JSON] = ProtocolJson.codec.encodeToString(devices)
        }
        preferences.remove(KEY_DEVICE_NAME)
        preferences.remove(KEY_DEVICE_ADDRESS)
    }

    companion object {
        private val KEY_MANAGED_DEVICES_JSON = stringPreferencesKey("managed_devices_json")
        private val KEY_DEVICE_NAME = stringPreferencesKey("selected_device_name")
        private val KEY_DEVICE_ADDRESS = stringPreferencesKey("selected_device_address")

        fun from(context: Context): SelectedDeviceRepository =
            SelectedDeviceRepository(context.applicationContext.selectedDeviceDataStore)
    }
}
