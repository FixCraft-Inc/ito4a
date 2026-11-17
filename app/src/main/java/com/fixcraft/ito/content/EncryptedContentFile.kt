package com.fixcraft.ito.content

import android.system.Os
import android.system.OsConstants
import com.fixcraft.ito.security.AppLockedException
import com.fixcraft.ito.security.LockUnavailableException
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * One named, bounded content-key record. The process supplies one writer and
 * owns the plaintext format. This owner authenticates existing bytes before
 * replacement, refuses links, and reports publication after rename as ambiguous
 * until its directory sync succeeds. Returned plaintext belongs to the caller.
 */
internal class EncryptedContentFile(
    private val directory: File,
    private val record: String,
    private val temporary: String,
    private val header: ByteArray,
    private val domain: ByteArray,
    private val maximum: Int,
    private val useKey: ((ByteArray) -> ByteArray) -> ByteArray,
    private val requireAccess: () -> Unit,
    private val syncDirectory: (File) -> Unit,
) {
    init {
        require(directory.isAbsolute && maximum > 0 && header.isNotEmpty())
        require(record.matches(Regex("[A-Za-z0-9._-]{1,160}")) && record !in setOf(".", ".."))
        require(temporary.matches(Regex("[A-Za-z0-9._-]{1,170}")) && temporary !in setOf(".", "..", record))
    }
    private val overhead get() = header.size + 12 + 16

    @Synchronized fun load(): ByteArray? = storage {
        requireAccess()
        prepare()
        val path = File(directory, record).toPath()
        if (!Files.exists(path, NOFOLLOW_LINKS)) null else read(path)
    }

    @Synchronized fun delete() = storage {
        requireAccess(); prepare()
        val path = File(directory, record).toPath()
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            read(path).fill(0)
            requireAccess()
            Files.delete(path)
            try { syncDirectory(directory) }
            catch (_: Exception) { throw ContentException(ContentFailure.Ambiguous) }
        }
    }

    /** The input is borrowed. It is never modified, even on failure. */
    @Synchronized fun replace(plain: ByteArray) = storage {
        requireAccess()
        if (plain.size > maximum) throw ContentException(ContentFailure.InvalidInput)
        prepare()
        val path = File(directory, record).toPath()
        if (Files.exists(path, NOFOLLOW_LINKS)) read(path).fill(0)
        val sealed = useKey { key ->
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            cipher.updateAAD(domain)
            if (cipher.iv.size != 12) throw ContentException(ContentFailure.KeyUnavailable)
            val body = cipher.doFinal(plain)
            try { header + cipher.iv + body } finally { body.fill(0) }
        }
        val candidate = File(directory, temporary).toPath()
        var renamed = false
        try {
            Files.createFile(candidate, PosixFilePermissions.asFileAttribute(FILE_MODE))
            FileChannel.open(candidate, WRITE, NOFOLLOW_LINKS).use { output ->
                val bytes = ByteBuffer.wrap(sealed)
                while (bytes.hasRemaining()) output.write(bytes)
                output.force(true)
            }
            requireAccess()
            Files.move(candidate, path, ATOMIC_MOVE, REPLACE_EXISTING)
            renamed = true
            syncDirectory(directory)
        } catch (failure: Exception) {
            if (renamed) throw ContentException(ContentFailure.Ambiguous)
            throw failure
        } finally {
            sealed.fill(0)
            try { Files.deleteIfExists(candidate) }
            catch (_: Exception) {
                throw ContentException(if (renamed) ContentFailure.Ambiguous else ContentFailure.Io)
            }
        }
    }

    private fun read(path: java.nio.file.Path): ByteArray {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) != FILE_MODE)
            throw ContentException(ContentFailure.Corrupt)
        val size = Files.size(path)
        if (size !in overhead.toLong()..(maximum.toLong() + overhead)) throw ContentException(ContentFailure.Corrupt)
        val sealed = ByteArray(size.toInt())
        try {
            Files.newByteChannel(path, setOf(READ, NOFOLLOW_LINKS)).use { channel ->
                val bytes = ByteBuffer.wrap(sealed)
                while (bytes.hasRemaining()) if (channel.read(bytes) < 0) throw ContentException(ContentFailure.Corrupt)
                if (channel.read(ByteBuffer.allocate(1)) != -1) throw ContentException(ContentFailure.Corrupt)
            }
            if (header.indices.any { sealed[it] != header[it] }) throw ContentException(ContentFailure.Corrupt)
            return useKey { key ->
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed, header.size, 12))
                cipher.updateAAD(domain)
                try { cipher.doFinal(sealed, header.size + 12, sealed.size - header.size - 12) }
                catch (_: javax.crypto.BadPaddingException) { throw ContentException(ContentFailure.Corrupt) }
            }
        } finally { sealed.fill(0) }
    }

    private fun prepare() {
        val path = directory.toPath()
        if (!Files.exists(path, NOFOLLOW_LINKS)) {
            Files.createDirectory(path, PosixFilePermissions.asFileAttribute(DIRECTORY_MODE))
            syncDirectory(requireNotNull(directory.parentFile))
        }
        if (!Files.isDirectory(path, NOFOLLOW_LINKS) || Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) != DIRECTORY_MODE)
            throw ContentException(ContentFailure.Io)
        val candidate = File(directory, temporary).toPath()
        if (Files.exists(candidate, NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(candidate, NOFOLLOW_LINKS)) throw ContentException(ContentFailure.Corrupt)
            Files.delete(candidate)
            syncDirectory(directory)
        }
    }

    private fun <T> storage(action: () -> T): T = try { action() }
    catch (failure: ContentException) { throw failure }
    catch (_: AppLockedException) { throw ContentException(ContentFailure.KeyUnavailable) }
    catch (_: LockUnavailableException) { throw ContentException(ContentFailure.KeyUnavailable) }
    catch (_: java.security.GeneralSecurityException) { throw ContentException(ContentFailure.KeyUnavailable) }
    catch (_: java.io.IOException) { throw ContentException(ContentFailure.Io) }
    catch (_: SecurityException) { throw ContentException(ContentFailure.Io) }
    catch (_: UnsupportedOperationException) { throw ContentException(ContentFailure.Io) }

    private companion object {
        val FILE_MODE = PosixFilePermissions.fromString("rw-------")
        val DIRECTORY_MODE = PosixFilePermissions.fromString("rwx------")
    }
}

internal fun syncContentDirectory(directory: File) {
    val descriptor = Os.open(directory.absolutePath,
        OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
    try {
        if (!OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) throw ContentException(ContentFailure.Io)
        Os.fsync(descriptor)
    } finally { Os.close(descriptor) }
}
