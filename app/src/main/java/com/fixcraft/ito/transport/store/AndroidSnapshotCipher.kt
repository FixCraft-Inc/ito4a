package com.fixcraft.ito.transport.store

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class AndroidSnapshotCipher(private val alias: String) : SnapshotCipher {
    override fun encrypt(plaintext: ByteArray, allowNewKey: Boolean): ByteArray = keyOperation {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(allowNewKey))
        if (cipher.iv.size != NONCE_BYTES) throw StoreException(StoreFailure.KeyUnavailable)
        cipher.updateAAD(DOMAIN)
        val encrypted = cipher.doFinal(plaintext)
        try {
            HEADER + cipher.iv + encrypted
        } finally {
            encrypted.fill(0)
        }
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        if (ciphertext.size < HEADER.size + NONCE_BYTES + TAG_BYTES ||
            !ciphertext.copyOfRange(0, HEADER.size).contentEquals(HEADER)
        ) throw StoreException(StoreFailure.Corrupt)
        return keyOperation {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE, key(false),
                GCMParameterSpec(TAG_BYTES * 8, ciphertext, HEADER.size, NONCE_BYTES),
            )
            cipher.updateAAD(DOMAIN)
            try {
                cipher.doFinal(ciphertext, HEADER.size + NONCE_BYTES, ciphertext.size - HEADER.size - NONCE_BYTES)
            } catch (_: BadPaddingException) {
                throw StoreException(StoreFailure.Corrupt)
            }
        }
    }

    private fun key(allowCreate: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        if (store.containsAlias(alias)) {
            return store.getKey(alias, null) as? SecretKey
                ?: throw StoreException(StoreFailure.KeyUnavailable)
        }
        if (!allowCreate) throw StoreException(StoreFailure.KeyUnavailable)
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun <T> keyOperation(operation: () -> T): T = try {
        operation()
    } catch (_: GeneralSecurityException) {
        throw StoreException(StoreFailure.KeyUnavailable)
    } catch (_: ProviderException) {
        throw StoreException(StoreFailure.KeyUnavailable)
    } catch (_: IOException) {
        throw StoreException(StoreFailure.KeyUnavailable)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE_BYTES = 12
        const val TAG_BYTES = 16
        val HEADER = byteArrayOf(0x49, 0x54, 0x4f, 0x53, 1)
        val DOMAIN = "ito/transport-storage/snapshot/1".toByteArray(Charsets.US_ASCII)
    }
}
