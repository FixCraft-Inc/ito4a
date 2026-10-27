package com.fixcraft.ito.provider

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.system.OsConstants
import com.fixcraft.yume.provider.ProviderSecretBackend
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Ito's Keystore owner. It never opens yume4a's vault or Ito's manual catalog. */
class ItoProviderBackend internal constructor(private val records: ProviderRecordStorage) :
    ProviderSecretBackend by records {
    constructor(context: Context) : this(ProviderRecordStorage(
        File(context.applicationContext.noBackupFilesDir, "ito-provider-records"),
        ProviderRecordCipher(AndroidProviderRecordKeys(KEY_ALIAS)),
        ::syncAndroidDirectory,
    ))

    /** Before starting the shared store or any selection operation. */
    fun recoverStaging() = records.recoverStaging()

    companion object {
        private const val KEY_ALIAS = "ito.provider.records.v1"

        private fun syncAndroidDirectory(directory: File) {
            try {
                val descriptor = Os.open(directory.absolutePath,
                    OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
                try {
                    if (!OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) throw ProviderStorageException(ProviderStorageFailure.Io)
                    Os.fsync(descriptor)
                } finally { Os.close(descriptor) }
            } catch (_: android.system.ErrnoException) {
                throw ProviderStorageException(ProviderStorageFailure.Io)
            }
        }
    }
}

internal class AndroidProviderRecordKeys(private val alias: String) : ProviderRecordKeys {
    override fun key(allowCreate: Boolean): SecretKey = try {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        if (store.containsAlias(alias)) {
            store.getKey(alias, null) as? SecretKey ?: throw ProviderStorageException(ProviderStorageFailure.KeyUnavailable)
        } else {
            if (!allowCreate) throw ProviderStorageException(ProviderStorageFailure.KeyUnavailable)
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setUserAuthenticationRequired(false).build())
            generator.generateKey()
        }
    } catch (failure: ProviderStorageException) { throw failure }
    catch (_: GeneralSecurityException) { throw ProviderStorageException(ProviderStorageFailure.KeyUnavailable) }
    catch (_: ProviderException) { throw ProviderStorageException(ProviderStorageFailure.KeyUnavailable) }
    catch (_: IOException) { throw ProviderStorageException(ProviderStorageFailure.KeyUnavailable) }
}
