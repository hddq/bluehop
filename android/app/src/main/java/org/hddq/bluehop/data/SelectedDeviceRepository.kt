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
import org.hddq.bluehop.bluetooth.SelectedBluetoothDevice
import java.io.IOException

private val Context.selectedDeviceDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "selected_bluetooth_device",
)

class SelectedDeviceRepository(
    private val dataStore: DataStore<Preferences>,
) {
    val selectedDevice: Flow<SelectedBluetoothDevice?> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val address = preferences[KEY_DEVICE_ADDRESS]?.takeIf { it.isNotBlank() }
                ?: return@map null
            val name = preferences[KEY_DEVICE_NAME]?.takeIf { it.isNotBlank() } ?: address
            SelectedBluetoothDevice(
                name = name,
                address = address,
            )
        }

    suspend fun selectDevice(device: SelectedBluetoothDevice) {
        dataStore.edit { preferences ->
            preferences[KEY_DEVICE_NAME] = device.name
            preferences[KEY_DEVICE_ADDRESS] = device.address
        }
    }

    suspend fun clearSelectedDevice() {
        dataStore.edit { preferences ->
            preferences.remove(KEY_DEVICE_NAME)
            preferences.remove(KEY_DEVICE_ADDRESS)
        }
    }

    companion object {
        private val KEY_DEVICE_NAME = stringPreferencesKey("selected_device_name")
        private val KEY_DEVICE_ADDRESS = stringPreferencesKey("selected_device_address")

        fun from(context: Context): SelectedDeviceRepository =
            SelectedDeviceRepository(context.applicationContext.selectedDeviceDataStore)
    }
}
