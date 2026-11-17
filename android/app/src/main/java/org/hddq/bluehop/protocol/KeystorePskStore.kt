package org.hddq.bluehop.protocol

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecretBox {
    fun encrypt(plaintext: ByteArray): Pair<ByteArray, ByteArray>
    fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray
    fun deleteKey()
}

class KeystoreSecretBox(
    private val keyAlias: String,
) : SecretBox {
    override fun encrypt(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrappingKey())
        return cipher.iv to cipher.doFinal(plaintext)
    }

    override fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateWrappingKey(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv),
        )
        return cipher.doFinal(ciphertext)
    }

    override fun deleteKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(keyAlias)) {
            keyStore.deleteEntry(keyAlias)
        }
    }

    private fun getOrCreateWrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existingKey = keyStore.getKey(keyAlias, null) as? SecretKey
        if (existingKey != null) {
            return existingKey
        }
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
    }
}

interface PskStore {
    suspend fun getOrCreatePsk(): ByteArray
    suspend fun setPsk(psk: ByteArray)
    suspend fun clearPsk()
}

private val Context.pskDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "bluehop_psk",
)

class KeystorePskStore(
    private val dataStore: DataStore<Preferences>,
    private val secretBox: SecretBox = KeystoreSecretBox(DEFAULT_KEY_ALIAS),
    private val random: SecureRandom = SecureRandom(),
) : PskStore {

    override suspend fun getOrCreatePsk(): ByteArray {
        val preferences = dataStore.data.firstOrNullSafe()
        val ivEncoded = preferences[KEY_PSK_IV]
        val ciphertextEncoded = preferences[KEY_PSK_CIPHERTEXT]
        if (ivEncoded != null && ciphertextEncoded != null) {
            val existing = runCatching {
                secretBox.decrypt(
                    iv = Base64.getDecoder().decode(ivEncoded),
                    ciphertext = Base64.getDecoder().decode(ciphertextEncoded),
                )
            }.getOrNull()
            if (existing != null && existing.size == PSK_BYTE_LENGTH) {
                return existing
            }
        }

        val generated = ByteArray(PSK_BYTE_LENGTH).also(random::nextBytes)
        setPsk(generated)
        return generated
    }

    override suspend fun setPsk(psk: ByteArray) {
        require(psk.size == PSK_BYTE_LENGTH) {
            "Expected $PSK_BYTE_LENGTH-byte PSK, got ${psk.size} bytes"
        }
        val (iv, ciphertext) = secretBox.encrypt(psk)
        val encoder = Base64.getEncoder()
        dataStore.edit { preferences ->
            preferences[KEY_PSK_IV] = encoder.encodeToString(iv)
            preferences[KEY_PSK_CIPHERTEXT] = encoder.encodeToString(ciphertext)
        }
    }

    override suspend fun clearPsk() {
        dataStore.edit { preferences ->
            preferences.remove(KEY_PSK_IV)
            preferences.remove(KEY_PSK_CIPHERTEXT)
        }
        secretBox.deleteKey()
    }

    private suspend fun kotlinx.coroutines.flow.Flow<Preferences>.firstOrNullSafe(): Preferences =
        try {
            first()
        } catch (exception: IOException) {
            emptyPreferences()
        }

    companion object {
        const val PSK_BYTE_LENGTH = 32
        private const val DEFAULT_KEY_ALIAS = "bluehop_psk_wrap_v1"

        private val KEY_PSK_IV = stringPreferencesKey("psk_iv")
        private val KEY_PSK_CIPHERTEXT = stringPreferencesKey("psk_ciphertext")

        fun from(context: Context): KeystorePskStore =
            KeystorePskStore(context.applicationContext.pskDataStore)
    }
}
