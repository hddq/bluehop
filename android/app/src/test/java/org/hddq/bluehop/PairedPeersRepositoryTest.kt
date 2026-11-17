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
import org.hddq.bluehop.protocol.PairedPeer
import org.hddq.bluehop.protocol.PairedPeersRepository
import org.hddq.bluehop.protocol.SecretBox
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@OptIn(ExperimentalCoroutinesApi::class)
class PairedPeersRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class InMemoryAesSecretBox : SecretBox {
        private var key: SecretKey = generateKey()
        private val random = SecureRandom()

        override fun encrypt(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
            val iv = ByteArray(12).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            return iv to cipher.doFinal(plaintext)
        }

        override fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            return cipher.doFinal(ciphertext)
        }

        override fun deleteKey() {
            key = generateKey()
        }

        private companion object {
            fun generateKey(): SecretKey =
                KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
    }

    @Test
    fun identityIsGeneratedOnceAndPersistsAcrossRecreation() = runTest {
        val file = tempFolder.newFile("peers_identity.preferences_pb")
        val secretBox = InMemoryAesSecretBox()

        val firstScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val firstIdentity = try {
            val dataStore = PreferenceDataStoreFactory.create(
                scope = firstScope,
                produceFile = { file },
            )
            PairedPeersRepository(
                dataStore = dataStore,
                secretBox = secretBox,
                defaultDeviceNameProvider = { "Pixel 9 Pro" },
            ).getOrCreateIdentity()
        } finally {
            firstScope.cancel()
            firstScope.coroutineContext[Job]?.join()
        }

        assertEquals("Pixel 9 Pro", firstIdentity.deviceName)

        val secondScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val dataStore = PreferenceDataStoreFactory.create(
                scope = secondScope,
                produceFile = { file },
            )
            val repo = PairedPeersRepository(
                dataStore = dataStore,
                secretBox = secretBox,
                defaultDeviceNameProvider = { "Different Model" },
            )
            val restored = repo.getOrCreateIdentity()
            assertEquals(firstIdentity, restored)

            repo.setDeviceName("  Desk Phone  ")
            val renamed = repo.getOrCreateIdentity()
            assertEquals(firstIdentity.deviceId, renamed.deviceId)
            assertEquals("Desk Phone", renamed.deviceName)
        } finally {
            secondScope.cancel()
            secondScope.coroutineContext[Job]?.join()
        }
    }

    @Test
    fun upsertGetRemoveAndOverwritePeersWithSecretBox() = runTest {
        val file = tempFolder.newFile("peers_crud.preferences_pb")
        val secretBox = InMemoryAesSecretBox()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val dataStore = PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { file },
            )
            val repo = PairedPeersRepository(
                dataStore = dataStore,
                secretBox = secretBox,
                defaultDeviceNameProvider = { "Phone A" },
            )

            val pskBeta = ByteArray(32) { (it + 10).toByte() }
            val pskAlpha = ByteArray(32) { (it + 50).toByte() }
            val pskAlphaUpdated = ByteArray(32) { (it + 90).toByte() }

            repo.upsertPeer(
                PairedPeer(
                    peerId = "peer-b",
                    peerName = "zeta-laptop",
                    psk = pskBeta,
                    pairedAtEpochSec = 100L,
                ),
            )
            repo.upsertPeer(
                PairedPeer(
                    peerId = "peer-a",
                    peerName = "Alpha Tablet",
                    psk = pskAlpha,
                    pairedAtEpochSec = 200L,
                ),
            )

            val summaries = repo.peerSummaries.first()
            assertEquals(listOf("peer-a", "peer-b"), summaries.map { it.peerId })
            assertArrayEquals(pskAlpha, repo.getPeerPsk("peer-a"))
            assertArrayEquals(pskBeta, repo.getPeerPsk("peer-b"))
            assertNull(repo.getPeerPsk("unknown-peer"))

            repo.upsertPeer(
                PairedPeer(
                    peerId = "peer-a",
                    peerName = "Alpha Tablet",
                    psk = pskAlphaUpdated,
                    pairedAtEpochSec = 300L,
                ),
            )
            assertArrayEquals(pskAlphaUpdated, repo.getPeerPsk("peer-a"))
            assertNotEquals(pskAlpha.toList(), repo.getPeerPsk("peer-a")!!.toList())

            repo.removePeer("peer-a")
            assertNull(repo.getPeerPsk("peer-a"))
            assertEquals(1, repo.getAllPeers().size)

            repo.removePeer("peer-b")
            assertEquals(emptyList<PairedPeer>(), repo.getAllPeers())
        } finally {
            scope.cancel()
            scope.coroutineContext[Job]?.join()
        }
    }
}
