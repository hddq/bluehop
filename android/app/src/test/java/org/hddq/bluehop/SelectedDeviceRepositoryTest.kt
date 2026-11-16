package org.hddq.bluehop

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.hddq.bluehop.bluetooth.SelectedBluetoothDevice
import org.hddq.bluehop.data.SelectedDeviceRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SelectedDeviceRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun selectedDeviceDefaultsToNull() = runTest {
        val file = tempFolder.newFile("default_device.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val dataStore = PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { file },
            )
            val repository = SelectedDeviceRepository(dataStore)

            assertNull(repository.selectedDevice.first())
        } finally {
            scope.cancel()
            scope.coroutineContext[Job]?.join()
        }
    }

    @Test
    fun selectedDevicePersistsAcrossStoreRecreation() = runTest {
        val file = tempFolder.newFile("persisted_device.preferences_pb")
        val headphones = SelectedBluetoothDevice(
            name = "Sony WH-1000XM5",
            address = "AA:BB:CC:DD:EE:01",
        )

        val firstScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val firstStore = PreferenceDataStoreFactory.create(
                scope = firstScope,
                produceFile = { file },
            )
            SelectedDeviceRepository(firstStore).selectDevice(headphones)
        } finally {
            firstScope.cancel()
            firstScope.coroutineContext[Job]?.join()
        }

        val secondScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val secondStore = PreferenceDataStoreFactory.create(
                scope = secondScope,
                produceFile = { file },
            )
            val restored = SelectedDeviceRepository(secondStore).selectedDevice.first()
            assertEquals(headphones, restored)
        } finally {
            secondScope.cancel()
            secondScope.coroutineContext[Job]?.join()
        }
    }

    @Test
    fun selectingAnotherDeviceOverwritesPreviousAndClearRemovesIt() = runTest {
        val file = tempFolder.newFile("overwrite_device.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val dataStore = PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { file },
            )
            val repository = SelectedDeviceRepository(dataStore)

            val first = SelectedBluetoothDevice("First Buds", "11:22:33:44:55:66")
            val second = SelectedBluetoothDevice("Studio Headphones", "AA:BB:CC:DD:EE:FF")

            repository.selectDevice(first)
            assertEquals(first, repository.selectedDevice.first())

            repository.selectDevice(second)
            assertEquals(second, repository.selectedDevice.first())

            repository.clearSelectedDevice()
            assertNull(repository.selectedDevice.first())
        } finally {
            scope.cancel()
            scope.coroutineContext[Job]?.join()
        }
    }
}
