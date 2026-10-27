package com.fixcraft.ito.provider

import java.io.IOException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class ProviderStorageFailure { InvalidInput, Corrupt, KeyUnavailable, Io }

class ProviderStorageException(val reason: ProviderStorageFailure) :
    IOException("Provider storage: ${reason.name}")

internal fun interface ProviderRecordKeys {
    /** A missing key may be created only for a store with no retained records. */
    fun key(allowCreate: Boolean): SecretKey
}

/** Platform app-data encryption, separate from transport and the manual store. */
internal class ProviderRecordCipher(private val keys: ProviderRecordKeys) {
    fun requireKey(allowCreate: Boolean) { keyOperation { keys.key(allowCreate) } }

    fun encrypt(name: String, plaintext: ByteArray, allowCreate: Boolean): ByteArray = keyOperation {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keys.key(allowCreate))
        if (cipher.iv.size != NONCE_BYTES) throw ProviderStorageException(ProviderStorageFailure.KeyUnavailable)
        cipher.updateAAD(aad(name))
        val encrypted = cipher.doFinal(plaintext)
        try { HEADER + cipher.iv + encrypted } finally { encrypted.fill(0) }
    }

    fun decrypt(name: String, ciphertext: ByteArray): ByteArray {
        if (ciphertext.size < OVERHEAD || !ciphertext.copyOfRange(0, HEADER.size).contentEquals(HEADER)) {
            throw ProviderStorageException(ProviderStorageFailure.Corrupt)
        }
        return keyOperation {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keys.key(false),
                GCMParameterSpec(TAG_BYTES * 8, ciphertext, HEADER.size, NONCE_BYTES))
            cipher.updateAAD(aad(name))
            try { cipher.doFinal(ciphertext, HEADER.size + NONCE_BYTES, ciphertext.size - HEADER.size - NONCE_BYTES) }
            catch (_: BadPaddingException) { throw ProviderStorageException(ProviderStorageFailure.Corrupt) }
        }
    }

    private fun aad(name: String): ByteArray {
        val encoded = name.toByteArray(Charsets.US_ASCII)
        return DOMAIN + ByteBuffer.allocate(4).putInt(encoded.size).array() + encoded
    }

    private fun <T> keyOperation(operation: () -> T): T = try { operation() }
    catch (_: GeneralSecurityException) { throw ProviderStorageException(ProviderStorageFailure.KeyUnavailable) }
    catch (_: ProviderException) { throw ProviderStorageException(ProviderStorageFailure.KeyUnavailable) }

    companion object {
        const val OVERHEAD = 33
        private const val NONCE_BYTES = 12
        private const val TAG_BYTES = 16
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private val HEADER = byteArrayOf('I'.code.toByte(), 'T'.code.toByte(), 'O'.code.toByte(), 'P'.code.toByte(), 1)
        private val DOMAIN = "ito/provider-storage/record/1".toByteArray(Charsets.US_ASCII)
    }
}
