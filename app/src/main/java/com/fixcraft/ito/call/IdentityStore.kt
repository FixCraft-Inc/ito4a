package com.fixcraft.ito.call

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.fixcraft.ito.security.AppLockedException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class IdentityFailure { Corrupt, KeyUnavailable, Io }

class IdentityStorageException(val reason: IdentityFailure) : IOException("Identity storage: ${reason.name}")

internal fun interface IdentityKeys {
    /** A missing key may be created only while no snapshot ciphertext exists. */
    fun key(allowCreate: Boolean): SecretKey
}

/**
 * The call core's identity, contact and spent-invitation snapshot, sealed
 * with a separate non-exportable Keystore AES-256-GCM key and published as
 * one atomically replaced file in noBackupFilesDir/ito-identity/. The core
 * owns the plaintext format and every policy. This class owns only sealing,
 * publication and refusal to touch unreadable state.
 *
 * The AAD binds the format version, the domain ito/identity-storage/1 and the
 * record name, so ciphertext copied from another store cannot be opened here.
 */
class IdentityStore internal constructor(
    private val directory: File,
    private val keys: IdentityKeys,
    private val syncDirectory: (File) -> Unit,
    private val requireAccess: () -> Unit = {},
    private val checkpoint: (Checkpoint) -> Unit = {},
) {
    internal enum class Checkpoint { AfterFileSync, AfterReplace, AfterDirectorySync }

    constructor(directory: File, requireAccess: () -> Unit = {}) : this(
        directory, AndroidIdentityKeys(KEY_ALIAS), ::syncAndroidDirectory, requireAccess = requireAccess,
    )

    /** The snapshot plaintext, or null when none was ever published. */
    @Synchronized fun load(): ByteArray? = io {
        requireAccess()
        prepare()
        removeTemporary()
        val path = snapshotPath()
        if (!Files.exists(path, NOFOLLOW_LINKS)) return@io null
        val sealed = readBounded(path)
        try { open(sealed) } finally { sealed.fill(0) }
    }

    /**
     * Seals and publishes snapshot, then wipes it. Returns PERSIST_COMMITTED,
     * PERSIST_NOT_COMMITTED when the old snapshot is untouched, or
     * PERSIST_AMBIGUOUS when the rename happened but its durability is unknown.
     */
    @Synchronized fun persist(snapshot: ByteArray): Int {
        var replaced = false
        return try {
            requireAccess()
            prepare()
            val path = snapshotPath()
            val existing = Files.exists(path, NOFOLLOW_LINKS)
            if (existing) {
                // Old state is authenticated before it is replaced.
                val previous = readBounded(path)
                try { open(previous).fill(0) } finally { previous.fill(0) }
            }
            val sealed = seal(snapshot, allowCreate = !existing)
            try {
                val temporary = directory.toPath().resolve(TEMPORARY)
                try {
                    Files.deleteIfExists(temporary)
                    Files.createFile(temporary, PosixFilePermissions.asFileAttribute(FILE_MODE))
                    FileChannel.open(temporary, WRITE, NOFOLLOW_LINKS).use { output ->
                        val buffer = ByteBuffer.wrap(sealed)
                        while (buffer.hasRemaining()) output.write(buffer)
                        output.force(true)
                    }
                    checkpoint(Checkpoint.AfterFileSync)
                    Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
                    replaced = true
                    checkpoint(Checkpoint.AfterReplace)
                    syncDirectory(directory)
                    checkpoint(Checkpoint.AfterDirectorySync)
                } finally { Files.deleteIfExists(temporary) }
            } finally { sealed.fill(0) }
            CallNative.PERSIST_COMMITTED
        } catch (_: Exception) {
            if (replaced) CallNative.PERSIST_AMBIGUOUS else CallNative.PERSIST_NOT_COMMITTED
        } finally { snapshot.fill(0) }
    }

    private fun seal(plaintext: ByteArray, allowCreate: Boolean): ByteArray = keyOperation {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keys.key(allowCreate))
        if (cipher.iv.size != NONCE_BYTES) throw IdentityStorageException(IdentityFailure.KeyUnavailable)
        cipher.updateAAD(AAD)
        val encrypted = cipher.doFinal(plaintext)
        try { HEADER + cipher.iv + encrypted } finally { encrypted.fill(0) }
    }

    private fun open(sealed: ByteArray): ByteArray {
        if (sealed.size < OVERHEAD || !sealed.copyOfRange(0, HEADER.size).contentEquals(HEADER)) {
            throw IdentityStorageException(IdentityFailure.Corrupt)
        }
        return keyOperation {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keys.key(false), GCMParameterSpec(TAG_BYTES * 8, sealed, HEADER.size, NONCE_BYTES))
            cipher.updateAAD(AAD)
            try { cipher.doFinal(sealed, HEADER.size + NONCE_BYTES, sealed.size - HEADER.size - NONCE_BYTES) }
            catch (_: BadPaddingException) { throw IdentityStorageException(IdentityFailure.Corrupt) }
        }
    }

    private fun snapshotPath(): Path = directory.toPath().resolve(RECORD)

    private fun prepare() {
        val path = directory.toPath()
        val parent = directory.parentFile ?: throw IdentityStorageException(IdentityFailure.Io)
        if (!directory.isAbsolute) throw IdentityStorageException(IdentityFailure.Io)
        if (!Files.exists(path, NOFOLLOW_LINKS)) {
            Files.createDirectory(path, PosixFilePermissions.asFileAttribute(DIRECTORY_MODE))
            syncDirectory(parent)
        }
        if (!Files.isDirectory(path, NOFOLLOW_LINKS) ||
            Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) != DIRECTORY_MODE) {
            throw IdentityStorageException(IdentityFailure.Io)
        }
    }

    /** Startup: an interrupted publication's candidate is never promoted. */
    private fun removeTemporary() {
        val temporary = directory.toPath().resolve(TEMPORARY)
        if (Files.exists(temporary, NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(temporary, NOFOLLOW_LINKS)) throw IdentityStorageException(IdentityFailure.Corrupt)
            Files.delete(temporary)
            syncDirectory(directory)
        }
    }

    private fun readBounded(path: Path): ByteArray {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS) ||
            Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) != FILE_MODE) {
            throw IdentityStorageException(IdentityFailure.Corrupt)
        }
        val size = Files.size(path)
        if (size !in OVERHEAD.toLong()..(MAX_PLAINTEXT_BYTES + OVERHEAD).toLong()) {
            throw IdentityStorageException(IdentityFailure.Corrupt)
        }
        val bytes = ByteArray(size.toInt())
        try {
            Files.newByteChannel(path, setOf(READ, NOFOLLOW_LINKS)).use { input ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) if (input.read(buffer) < 0) throw IdentityStorageException(IdentityFailure.Corrupt)
                if (input.read(ByteBuffer.allocate(1)) != -1) throw IdentityStorageException(IdentityFailure.Corrupt)
            }
            return bytes
        } catch (error: Throwable) { bytes.fill(0); throw error }
    }

    private fun <T> keyOperation(operation: () -> T): T = try { operation() }
    catch (_: GeneralSecurityException) { throw IdentityStorageException(IdentityFailure.KeyUnavailable) }
    catch (_: ProviderException) { throw IdentityStorageException(IdentityFailure.KeyUnavailable) }

    private fun <T> io(operation: () -> T): T = try { operation() }
    catch (failure: IdentityStorageException) { throw failure }
    catch (_: AppLockedException) { throw IdentityStorageException(IdentityFailure.KeyUnavailable) }
    catch (_: IOException) { throw IdentityStorageException(IdentityFailure.Io) }
    catch (_: SecurityException) { throw IdentityStorageException(IdentityFailure.Io) }
    catch (_: UnsupportedOperationException) { throw IdentityStorageException(IdentityFailure.Io) }

    companion object {
        const val KEY_ALIAS = "ito.identity.v1"
        const val MAX_PLAINTEXT_BYTES = 1024 * 1024
        private const val RECORD = "snapshot.bin"
        private const val TEMPORARY = ".snapshot.tmp"
        private const val NONCE_BYTES = 12
        private const val TAG_BYTES = 16
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION: Byte = 1
        private val HEADER = byteArrayOf('I'.code.toByte(), 'T'.code.toByte(), 'O'.code.toByte(), 'I'.code.toByte(), FORMAT_VERSION)
        private const val OVERHEAD = 5 + NONCE_BYTES + TAG_BYTES
        private val AAD: ByteArray = run {
            val domain = "ito/identity-storage/1".toByteArray(Charsets.US_ASCII)
            val name = "snapshot".toByteArray(Charsets.US_ASCII)
            byteArrayOf(FORMAT_VERSION) + domain + ByteBuffer.allocate(4).putInt(name.size).array() + name
        }
        private val DIRECTORY_MODE = PosixFilePermissions.fromString("rwx------")
        private val FILE_MODE = PosixFilePermissions.fromString("rw-------")

        private fun syncAndroidDirectory(directory: File) {
            try {
                val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
                try {
                    if (!OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) throw IdentityStorageException(IdentityFailure.Io)
                    Os.fsync(descriptor)
                } finally { Os.close(descriptor) }
            } catch (_: ErrnoException) { throw IdentityStorageException(IdentityFailure.Io) }
        }
    }
}

/** A non-exportable AES-256-GCM Keystore key that is never created over retained ciphertext. */
internal class AndroidIdentityKeys(private val alias: String) : IdentityKeys {
    override fun key(allowCreate: Boolean): SecretKey = try {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey) ?: run {
            if (!allowCreate || store.containsAlias(alias)) throw IdentityStorageException(IdentityFailure.KeyUnavailable)
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setUserAuthenticationRequired(false).build())
            generator.generateKey()
        }
    } catch (failure: IdentityStorageException) { throw failure }
    catch (_: GeneralSecurityException) { throw IdentityStorageException(IdentityFailure.KeyUnavailable) }
    catch (_: ProviderException) { throw IdentityStorageException(IdentityFailure.KeyUnavailable) }
    catch (_: IOException) { throw IdentityStorageException(IdentityFailure.KeyUnavailable) }
}
