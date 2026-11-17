package org.hddq.bluehop.protocol

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.util.Base64
import java.util.UUID

data class LocalDeviceIdentity(
    val deviceId: String,
    val deviceName: String,
)

data class PairedPeer(
    val peerId: String,
    val peerName: String,
    val psk: ByteArray,
    val pairedAtEpochSec: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairedPeer) return false
        return peerId == other.peerId &&
            peerName == other.peerName &&
            psk.contentEquals(other.psk) &&
            pairedAtEpochSec == other.pairedAtEpochSec
    }

    override fun hashCode(): Int {
        var result = peerId.hashCode()
        result = 31 * result + peerName.hashCode()
        result = 31 * result + psk.contentHashCode()
        result = 31 * result + pairedAtEpochSec.hashCode()
        return result
    }
}

data class PairedPeerSummary(
    val peerId: String,
    val peerName: String,
    val pairedAtEpochSec: Long,
)

@Serializable
private data class StoredPeerRecord(
    val peerId: String,
    val peerName: String,
    val ivBase64: String,
    val ciphertextBase64: String,
    val pairedAtEpochSec: Long,
)

private val Context.pairedPeersDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "bluehop_peers",
)

class PairedPeersRepository(
    private val dataStore: DataStore<Preferences>,
    private val secretBox: SecretBox = KeystoreSecretBox(PEER_KEY_ALIAS),
    private val defaultDeviceNameProvider: () -> String = { Build.MODEL.orEmpty().ifBlank { "Android" } },
) {
    val peerSummaries: Flow<List<PairedPeerSummary>> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            decodeRecords(preferences[KEY_PEERS_JSON]).map { record ->
                PairedPeerSummary(
                    peerId = record.peerId,
                    peerName = record.peerName,
                    pairedAtEpochSec = record.pairedAtEpochSec,
                )
            }
        }

    suspend fun getOrCreateIdentity(): LocalDeviceIdentity {
        val current = dataStore.data.firstOrNullSafe()
        val existingId = current[KEY_LOCAL_DEVICE_ID]?.takeIf { it.isNotBlank() }
        val existingName = current[KEY_LOCAL_DEVICE_NAME]?.takeIf { it.isNotBlank() }
        if (existingId != null && existingName != null) {
            return LocalDeviceIdentity(deviceId = existingId, deviceName = existingName)
        }

        val generatedId = existingId ?: UUID.randomUUID().toString()
        val resolvedName = existingName ?: defaultDeviceNameProvider()
        dataStore.edit { preferences ->
            preferences[KEY_LOCAL_DEVICE_ID] = generatedId
            preferences[KEY_LOCAL_DEVICE_NAME] = resolvedName
        }
        return LocalDeviceIdentity(deviceId = generatedId, deviceName = resolvedName)
    }

    suspend fun setDeviceName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        getOrCreateIdentity()
        dataStore.edit { preferences ->
            preferences[KEY_LOCAL_DEVICE_NAME] = trimmed
        }
    }

    suspend fun getPeerPsk(peerId: String): ByteArray? {
        val records = decodeRecords(dataStore.data.firstOrNullSafe()[KEY_PEERS_JSON])
        val record = records.firstOrNull { it.peerId == peerId } ?: return null
        return decryptRecordPsk(record)
    }

    suspend fun getAllPeers(): List<PairedPeer> {
        val records = decodeRecords(dataStore.data.firstOrNullSafe()[KEY_PEERS_JSON])
        return records.mapNotNull { record ->
            val psk = decryptRecordPsk(record) ?: return@mapNotNull null
            PairedPeer(
                peerId = record.peerId,
                peerName = record.peerName,
                psk = psk,
                pairedAtEpochSec = record.pairedAtEpochSec,
            )
        }
    }

    suspend fun upsertPeer(peer: PairedPeer) {
        require(peer.peerId.isNotBlank()) { "Peer ID must not be blank" }
        require(peer.peerName.isNotBlank()) { "Peer name must not be blank" }
        require(peer.psk.size == KeystorePskStore.PSK_BYTE_LENGTH) {
            "Expected ${KeystorePskStore.PSK_BYTE_LENGTH}-byte PSK, got ${peer.psk.size}"
        }

        val (iv, ciphertext) = secretBox.encrypt(peer.psk)
        val encoder = Base64.getEncoder()
        val updatedRecord = StoredPeerRecord(
            peerId = peer.peerId,
            peerName = peer.peerName,
            ivBase64 = encoder.encodeToString(iv),
            ciphertextBase64 = encoder.encodeToString(ciphertext),
            pairedAtEpochSec = peer.pairedAtEpochSec,
        )

        dataStore.edit { preferences ->
            val existing = decodeRecords(preferences[KEY_PEERS_JSON])
                .filterNot { it.peerId == peer.peerId }
            val next = (existing + updatedRecord).sortedBy { it.peerName.lowercase() }
            preferences[KEY_PEERS_JSON] = ProtocolJson.codec.encodeToString(next)
        }
    }

    suspend fun removePeer(peerId: String) {
        dataStore.edit { preferences ->
            val existing = decodeRecords(preferences[KEY_PEERS_JSON])
            val next = existing.filterNot { it.peerId == peerId }
            if (next.isEmpty()) {
                preferences.remove(KEY_PEERS_JSON)
            } else {
                preferences[KEY_PEERS_JSON] = ProtocolJson.codec.encodeToString(next)
            }
        }
    }

    private fun decryptRecordPsk(record: StoredPeerRecord): ByteArray? = runCatching {
        val decoder = Base64.getDecoder()
        secretBox.decrypt(
            iv = decoder.decode(record.ivBase64),
            ciphertext = decoder.decode(record.ciphertextBase64),
        )
    }.getOrNull()?.takeIf { it.size == KeystorePskStore.PSK_BYTE_LENGTH }

    private fun decodeRecords(raw: String?): List<StoredPeerRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            ProtocolJson.codec.decodeFromString<List<StoredPeerRecord>>(raw)
        } catch (_: SerializationException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }

    private suspend fun Flow<Preferences>.firstOrNullSafe(): Preferences =
        try {
            first()
        } catch (_: IOException) {
            emptyPreferences()
        }

    companion object {
        private const val PEER_KEY_ALIAS = "bluehop_peer_wrap_v1"
        private val KEY_LOCAL_DEVICE_ID = stringPreferencesKey("local_device_id")
        private val KEY_LOCAL_DEVICE_NAME = stringPreferencesKey("local_device_name")
        private val KEY_PEERS_JSON = stringPreferencesKey("paired_peers_json")

        fun from(context: Context): PairedPeersRepository =
            PairedPeersRepository(context.applicationContext.pairedPeersDataStore)
    }
}
