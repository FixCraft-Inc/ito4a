package com.fixcraft.ito.security

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class LockState { Disabled, Locked, Unlocked, Unavailable }
enum class LockAction { Enable, Unlock, Disable }
class AppLockedException : IOException("Application access is locked")
class LockUnavailableException(cause: Throwable? = null) : IOException("Application authentication is unavailable", cause)

internal interface LockKeys {
    fun key(authenticated: Boolean, allowCreate: Boolean): SecretKey
    fun authenticationAvailable(): Boolean
    fun hasExistingKey(): Boolean
    fun retireDeviceKey()
}

/** A single pending platform CryptoObject operation. Never persist or save it in an Activity bundle. */
class LockChallenge internal constructor(
    val action: LockAction,
    val cipher: Cipher,
    internal val generation: Long,
    internal val material: ByteArray,
) {
    internal fun clear() = material.fill(0)
}

/**
 * Wraps the local content key, and controls new application secret borrows.
 * Platform authentication performs the actual per-use wrapping-key operation.
 * Legacy identity/configuration/provider keys retain their own encryption and
 * require [requireAccess] at their adapters. Existing native call borrowers may
 * finish while the app is locked. They cannot obtain a new store borrow.
 *
 * All disk and Keystore methods are synchronous and belong on an I/O dispatcher.
 */
class AppLockController internal constructor(
    private val directory: File,
    private val keys: LockKeys,
    private val retainedContent: () -> Boolean,
    private val syncDirectory: (File) -> Unit,
    private val authenticationCipher: () -> Cipher = { Cipher.getInstance(TRANSFORMATION) },
) {
    constructor(context: Context) : this(
        File(context.applicationContext.noBackupFilesDir, "ito-access"),
        AndroidLockKeys(context.applicationContext),
        { File(context.applicationContext.noBackupFilesDir, "ito-content/snapshot.bin").exists() },
        ::syncAndroidDirectory,
    )

    private val mutableState = MutableStateFlow(LockState.Unavailable)
    val state: StateFlow<LockState> = mutableState
    private var descriptor: ByteArray? = null
    private var contentKey: ByteArray? = null
    private var pending: LockChallenge? = null
    private var generation = 0L
    private var inForeground = true

    init { reload() }

    /** Authenticates a disabled wrapper. Enabled protection starts locked after process death. */
    @Synchronized fun reload() {
        mutableState.value = LockState.Unavailable
        discardKey()
        pending?.clear()
        pending = null
        generation++
        try {
            prepareDirectory()
            val path = File(directory, RECORD).toPath()
            descriptor?.fill(0)
            descriptor = if (!Files.exists(path, NOFOLLOW_LINKS)) {
                if (retainedContent() || keys.hasExistingKey()) throw LockUnavailableException()
                null
            } else {
                if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.size(path) != DESCRIPTOR_BYTES.toLong() ||
                    Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) != FILE_MODE) throw LockUnavailableException()
                val bytes = ByteArray(DESCRIPTOR_BYTES)
                Files.newByteChannel(path, setOf(READ, NOFOLLOW_LINKS)).use { input ->
                    val target = ByteBuffer.wrap(bytes)
                    while (target.hasRemaining()) if (input.read(target) < 0) throw LockUnavailableException()
                    if (input.read(ByteBuffer.allocate(1)) != -1) throw LockUnavailableException()
                }
                if (!bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC) || bytes[5].toInt() !in 0..1)
                    throw LockUnavailableException()
                bytes
            }
            // The persisted disabled flag is not authority until its wrapper
            // authenticates. State observers must never see Disabled while
            // validation is pending, even though secret borrows hold this lock.
            val saved = descriptor
            if (saved?.get(5)?.toInt() == 1) {
                mutableState.value = LockState.Locked
            } else {
                if (saved != null) contentKey = openDisabledKey(saved)
                mutableState.value = LockState.Disabled
            }
        } catch (_: Exception) {
            discardKey()
            mutableState.value = LockState.Unavailable
        }
    }

    /** Lock never closes or changes a native handle already borrowed by an authorized call. */
    @Synchronized fun lock() {
        pending?.clear()
        pending = null
        generation++
        if (mutableState.value == LockState.Unlocked) mutableState.value = LockState.Locked
        discardKey()
    }

    /**
     * The system credential prompt can stop its caller Activity. Preserve only
     * its exact pending CryptoObject, while denying all enabled app access.
     * Completion still requires a subsequent [foreground] notification.
     */
    @Synchronized fun background(preserveChallenge: LockChallenge? = null) {
        inForeground = false
        if (preserveChallenge == null || pending !== preserveChallenge) {
            pending?.clear()
            pending = null
            generation++
        }
        if (mutableState.value == LockState.Unlocked) mutableState.value = LockState.Locked
        discardKey()
    }

    @Synchronized fun foreground() { inForeground = true }

    @Synchronized fun isForeground(): Boolean = inForeground

    @Synchronized fun requireAccess() {
        if (mutableState.value != LockState.Disabled && mutableState.value != LockState.Unlocked)
            throw AppLockedException()
    }

    /** The caller owns the wait's deadline and cancellation; this never prompts or grants access. */
    suspend fun awaitAccess() {
        state.first { it == LockState.Disabled || it == LockState.Unlocked }
        requireAccess()
    }

    /** No key is returned beyond the callback. Its temporary owned copy is cleared on every exit. */
    @Synchronized fun <T> withContentKey(operation: (ByteArray) -> T): T {
        requireAccess()
        ensureDisabledKey()
        val borrowed = contentKey?.copyOf() ?: throw AppLockedException()
        try { return operation(borrowed) } finally { borrowed.fill(0) }
    }

    /**
     * Prepare before showing BiometricPrompt, then pass [LockChallenge.cipher]
     * as its CryptoObject. A successful Activity result alone never unlocks.
     */
    @Synchronized fun prepare(action: LockAction): LockChallenge {
        if (!inForeground || pending != null || !keys.authenticationAvailable()) throw LockUnavailableException()
        val current = mutableState.value
        when (action) {
            LockAction.Enable -> if (current != LockState.Disabled) throw AppLockedException()
            LockAction.Unlock -> if (current != LockState.Locked) throw AppLockedException()
            LockAction.Disable -> if (current != LockState.Locked && current != LockState.Unlocked) throw AppLockedException()
        }
        try {
            val cipher = authenticationCipher()
            val material: ByteArray
            if (action == LockAction.Enable) {
                ensureDisabledKey()
                material = requireNotNull(contentKey).copyOf()
                try { cipher.init(Cipher.ENCRYPT_MODE, keys.key(true, true)) }
                catch (error: Exception) { material.fill(0); throw error }
            } else {
                val saved = descriptor ?: throw LockUnavailableException()
                cipher.init(Cipher.DECRYPT_MODE, keys.key(true, false), GCMParameterSpec(128, saved, HEADER_BYTES, NONCE_BYTES))
                material = saved.copyOfRange(HEADER_BYTES + NONCE_BYTES, saved.size)
            }
            return LockChallenge(action, cipher, ++generation, material).also { pending = it }
        } catch (failure: AppLockedException) { throw failure }
        catch (failure: Exception) { throw LockUnavailableException(failure) }
    }

    /** Called only with the Cipher returned by a successful platform CryptoObject authentication. */
    @Synchronized fun complete(challenge: LockChallenge, authenticatedCipher: Cipher) {
        if (!inForeground || pending !== challenge || challenge.generation != generation || authenticatedCipher !== challenge.cipher)
            throw AppLockedException()
        pending = null
        // Publication/key-retirement can abandon this operation. Access stays
        // refused until every step succeeds, even if a fatal VM error escapes.
        mutableState.value = LockState.Unavailable
        discardKey()
        var plain: ByteArray? = null
        try {
            // AndroidKeyStore sends AAD to KeyMint immediately. A per-use
            // operation must already be authenticated, or its cached refusal
            // survives the prompt and makes doFinal fail afterwards.
            authenticatedCipher.updateAAD(header(true) + DOMAIN)
            val result = authenticatedCipher.doFinal(challenge.material)
            when (challenge.action) {
                LockAction.Enable -> {
                    try {
                        if (authenticatedCipher.iv.size != NONCE_BYTES || result.size != KEY_BYTES + TAG_BYTES) throw LockUnavailableException()
                        publish(header(true) + authenticatedCipher.iv + result)
                        // Older disabled wrappers must not retain a usable
                        // unauthenticated route to this same content key.
                        keys.retireDeviceKey()
                    } finally { result.fill(0) }
                    discardKey()
                    contentKey = challenge.material.copyOf()
                    mutableState.value = LockState.Unlocked
                }
                LockAction.Unlock, LockAction.Disable -> {
                    plain = result
                    if (result.size != KEY_BYTES) throw LockUnavailableException()
                    if (challenge.action == LockAction.Disable) publish(wrapDisabled(result, allowCreate = true))
                    else keys.retireDeviceKey()
                    discardKey()
                    contentKey = result.copyOf()
                    mutableState.value = if (challenge.action == LockAction.Disable) LockState.Disabled else LockState.Unlocked
                }
            }
        } catch (failure: Exception) {
            discardKey()
            mutableState.value = LockState.Unavailable
            throw LockUnavailableException(failure)
        } finally { plain?.fill(0); challenge.clear() }
    }

    @Synchronized fun cancel(challenge: LockChallenge) {
        if (pending === challenge) {
            pending = null
            generation++
        }
        challenge.clear()
    }

    private fun ensureDisabledKey() {
        if (contentKey != null) return
        if (mutableState.value != LockState.Disabled) throw AppLockedException()
        try {
            val saved = descriptor
            if (saved == null) {
                val generated = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
                try {
                    publish(wrapDisabled(generated, allowCreate = true))
                    contentKey = generated.copyOf()
                } finally { generated.fill(0) }
            } else {
                contentKey = openDisabledKey(saved)
            }
        } catch (failure: Exception) {
            discardKey()
            mutableState.value = LockState.Unavailable
            throw LockUnavailableException(failure)
        }
    }

    private fun openDisabledKey(saved: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keys.key(false, false), GCMParameterSpec(128, saved, HEADER_BYTES, NONCE_BYTES))
        cipher.updateAAD(header(false) + DOMAIN)
        val opened = cipher.doFinal(saved, HEADER_BYTES + NONCE_BYTES, KEY_BYTES + TAG_BYTES)
        if (opened.size != KEY_BYTES) { opened.fill(0); throw LockUnavailableException() }
        return opened
    }

    private fun wrapDisabled(plain: ByteArray, allowCreate: Boolean): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keys.key(false, allowCreate))
        cipher.updateAAD(header(false) + DOMAIN)
        val sealed = cipher.doFinal(plain)
        return try {
            if (cipher.iv.size != NONCE_BYTES) throw LockUnavailableException()
            header(false) + cipher.iv + sealed
        } finally { sealed.fill(0) }
    }

    private fun publish(bytes: ByteArray) {
        try {
            prepareDirectory()
            val temporary = File(directory, TEMPORARY).toPath()
            Files.deleteIfExists(temporary)
            Files.createFile(temporary, PosixFilePermissions.asFileAttribute(FILE_MODE))
            try {
                FileChannel.open(temporary, WRITE, NOFOLLOW_LINKS).use { output ->
                    val source = ByteBuffer.wrap(bytes)
                    while (source.hasRemaining()) output.write(source)
                    output.force(true)
                }
                Files.move(temporary, File(directory, RECORD).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                syncDirectory(directory)
                descriptor?.fill(0)
                descriptor = bytes.copyOf()
            } finally { Files.deleteIfExists(temporary) }
        } finally { bytes.fill(0) }
    }

    private fun prepareDirectory() {
        val path = directory.toPath()
        if (!directory.isAbsolute) throw LockUnavailableException()
        if (!Files.exists(path, NOFOLLOW_LINKS)) {
            Files.createDirectory(path, PosixFilePermissions.asFileAttribute(DIRECTORY_MODE))
            syncDirectory(directory.parentFile)
        }
        if (!Files.isDirectory(path, NOFOLLOW_LINKS) || Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) != DIRECTORY_MODE)
            throw LockUnavailableException()
    }

    private fun discardKey() { contentKey?.fill(0); contentKey = null }

    companion object {
        private const val RECORD = "access.bin"
        private const val TEMPORARY = ".access.tmp"
        private const val KEY_BYTES = 32
        private const val NONCE_BYTES = 12
        private const val TAG_BYTES = 16
        private const val HEADER_BYTES = 6
        private const val DESCRIPTOR_BYTES = HEADER_BYTES + NONCE_BYTES + KEY_BYTES + TAG_BYTES
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private val MAGIC = byteArrayOf(73, 84, 79, 76, 1)
        private val DOMAIN = "ito/local-access/content-key/1".toByteArray(Charsets.US_ASCII)
        private val FILE_MODE = PosixFilePermissions.fromString("rw-------")
        private val DIRECTORY_MODE = PosixFilePermissions.fromString("rwx------")
        private fun header(locked: Boolean) = MAGIC + if (locked) byteArrayOf(1) else byteArrayOf(0)

        private fun syncAndroidDirectory(directory: File) {
            val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
            try {
                if (!OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) throw LockUnavailableException()
                Os.fsync(descriptor)
            } finally { Os.close(descriptor) }
        }
    }
}

internal class AndroidLockKeys(private val context: Context, private val aliasPrefix: String = "ito.access") : LockKeys {
    override fun authenticationAvailable(): Boolean = Build.VERSION.SDK_INT >= 30 &&
        context.getSystemService(KeyguardManager::class.java).isDeviceSecure

    override fun hasExistingKey(): Boolean = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.run {
        containsAlias("$aliasPrefix.auth.v1") || containsAlias("$aliasPrefix.device.v1")
    }

    override fun retireDeviceKey() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("$aliasPrefix.device.v1")
    }

    override fun key(authenticated: Boolean, allowCreate: Boolean): SecretKey {
        if (authenticated && !authenticationAvailable()) throw LockUnavailableException()
        val alias = if (authenticated) "$aliasPrefix.auth.v1" else "$aliasPrefix.device.v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) return store.getKey(alias, null) as? SecretKey ?: throw LockUnavailableException()
        if (!allowCreate) throw LockUnavailableException()
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setInvalidatedByBiometricEnrollment(false)
            .setUserAuthenticationRequired(authenticated)
        if (authenticated) {
            if (Build.VERSION.SDK_INT < 30) throw LockUnavailableException()
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(builder.build())
            generateKey()
        }
    }
}
