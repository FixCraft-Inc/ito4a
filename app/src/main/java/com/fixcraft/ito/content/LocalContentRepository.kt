package com.fixcraft.ito.content

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.fixcraft.ito.security.AppLockController
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
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Process-owned encrypted local messages and communication history. No audio,
 * invitation secret, account metadata or cloud operation enters this owner.
 * Calls and messages are supplied only by the authenticated application channel.
 * Synchronous disk operations belong on an I/O dispatcher.
 */
class LocalContentRepository internal constructor(
    private val directory: File,
    private val useKey: ((ByteArray) -> ByteArray) -> ByteArray,
    private val requireAccess: () -> Unit,
    private val syncDirectory: (File) -> Unit,
    private val clock: () -> Long,
) {
    constructor(context: Context, lock: AppLockController) : this(
        File(context.applicationContext.noBackupFilesDir, "ito-content"),
        { operation -> lock.withContentKey(operation) }, lock::requireAccess,
        ::syncAndroidDirectory, { System.currentTimeMillis() / 1000 },
    )

    @Synchronized fun recordCall(peer: ByteArray, initiator: Boolean, result: Int, timeUnix: Long, durationMs: Long) {
        if (peer.size != 32 || timeUnix < 0 || durationMs !in 0..ContentCodec.MAX_DURATION_MS)
            throw ContentException(ContentFailure.InvalidInput)
        val record = LocalCall(ByteArray(16).also(SecureRandom()::nextBytes), peer.copyOf(), initiator, timeUnix, durationMs, result)
        try {
            ContentCodec.requireCall(record)
            change { it.calls.add(record.copyRecord()) }
        } finally { record.close() }
    }

    /** Duplicate IDs for this peer/direction are idempotent only when their UTF-8 content is identical. */
    @Synchronized fun recordMessage(id: ByteArray, peer: ByteArray, outgoing: Boolean, utf8: ByteArray, timeUnix: Long) {
        if (id.size != 16 || peer.size != 32 || timeUnix < 0 || !ContentCodec.validText(utf8))
            throw ContentException(ContentFailure.InvalidInput)
        val record = LocalMessage(id.copyOf(), peer.copyOf(), outgoing, timeUnix, utf8.copyOf())
        try {
            ContentCodec.requireMessage(record)
            change { snapshot ->
                val prior = snapshot.messages.firstOrNull { it.id.contentEquals(id) && it.peer.contentEquals(peer) && it.outgoing == outgoing }
                if (prior != null) {
                    if (!prior.utf8.contentEquals(utf8)) throw ContentException(ContentFailure.InvalidInput)
                } else snapshot.messages.add(record.copyRecord())
            }
        } finally { record.close() }
    }

    /** The caller owns the returned records and should close them after display/use. */
    @Synchronized fun messages(): List<LocalMessage> = read { it.messages.map(LocalMessage::copyRecord) }
    @Synchronized fun history(): List<LocalCall> = read { it.calls.map(LocalCall::copyRecord) }

    /** Deletes only current local content for the exact communication key. It preserves contact trust and identity. */
    @Synchronized fun deletePeer(peer: ByteArray) {
        if (peer.size != 32) throw ContentException(ContentFailure.InvalidInput)
        change { snapshot ->
            snapshot.messages.removeAll { if (it.peer.contentEquals(peer)) { it.close(); true } else false }
            snapshot.calls.removeAll { if (it.peer.contentEquals(peer)) { it.close(); true } else false }
        }
    }

    @Synchronized fun deleteMessage(id: ByteArray, peer: ByteArray, outgoing: Boolean) {
        if (id.size != 16 || peer.size != 32) throw ContentException(ContentFailure.InvalidInput)
        change { snapshot ->
            snapshot.messages.removeAll {
                if (it.id.contentEquals(id) && it.peer.contentEquals(peer) && it.outgoing == outgoing) { it.close(); true } else false
            }
        }
    }

    /** Offline authenticated deletion, not flash erasure or remote deletion. */
    @Synchronized fun clear() = change { snapshot -> snapshot.close(); snapshot.messages.clear(); snapshot.calls.clear() }

    private fun change(operation: (ContentSnapshot) -> Unit) {
        requireAccess()
        load().use { snapshot ->
            operation(snapshot)
            ContentCodec.trim(snapshot, clock())
            persist(snapshot)
        }
    }

    private fun <T> read(operation: (ContentSnapshot) -> T): T {
        requireAccess()
        return load().use { snapshot ->
            if (ContentCodec.trim(snapshot, clock())) persist(snapshot)
            operation(snapshot)
        }
    }

    private fun load(): ContentSnapshot = storage {
        prepare()
        val path = File(directory, RECORD).toPath()
        if (!Files.exists(path, NOFOLLOW_LINKS)) return@storage ContentSnapshot()
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) != FILE_MODE)
            throw ContentException(ContentFailure.Corrupt)
        val size = Files.size(path)
        if (size !in OVERHEAD.toLong()..(ContentCodec.MAX_SNAPSHOT_BYTES + OVERHEAD).toLong()) throw ContentException(ContentFailure.Corrupt)
        val sealed = ByteArray(size.toInt())
        try {
            Files.newByteChannel(path, setOf(READ, NOFOLLOW_LINKS)).use { input ->
                val target = ByteBuffer.wrap(sealed)
                while (target.hasRemaining()) if (input.read(target) < 0) throw ContentException(ContentFailure.Corrupt)
                if (input.read(ByteBuffer.allocate(1)) != -1) throw ContentException(ContentFailure.Corrupt)
            }
            if (!sealed.copyOfRange(0, HEADER.size).contentEquals(HEADER)) throw ContentException(ContentFailure.Corrupt)
            val plain = useKey { key ->
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed, HEADER.size, 12))
                cipher.updateAAD(DOMAIN)
                try { cipher.doFinal(sealed, HEADER.size + 12, sealed.size - HEADER.size - 12) }
                catch (_: javax.crypto.BadPaddingException) { throw ContentException(ContentFailure.Corrupt) }
            }
            try { ContentCodec.decode(plain) } finally { plain.fill(0) }
        } finally { sealed.fill(0) }
    }

    private fun persist(snapshot: ContentSnapshot) = storage {
        val plain = ContentCodec.encode(snapshot)
        val sealed = try {
            useKey { key ->
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
                cipher.updateAAD(DOMAIN)
                val body = cipher.doFinal(plain)
                try {
                    if (cipher.iv.size != 12) throw ContentException(ContentFailure.KeyUnavailable)
                    HEADER + cipher.iv + body
                } finally { body.fill(0) }
            }
        } finally { plain.fill(0) }
        var replaced = false
        val temporary = File(directory, TEMPORARY).toPath()
        try {
            Files.createFile(temporary, PosixFilePermissions.asFileAttribute(FILE_MODE))
            FileChannel.open(temporary, WRITE, NOFOLLOW_LINKS).use { output ->
                val source = ByteBuffer.wrap(sealed)
                while (source.hasRemaining()) output.write(source)
                output.force(true)
            }
            Files.move(temporary, File(directory, RECORD).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            replaced = true
            syncDirectory(directory)
        } catch (_: Exception) { throw ContentException(if (replaced) ContentFailure.Ambiguous else ContentFailure.Io) }
        finally { sealed.fill(0); Files.deleteIfExists(temporary) }
    }

    private fun prepare() {
        val path = directory.toPath()
        if (!directory.isAbsolute) throw ContentException(ContentFailure.Io)
        if (!Files.exists(path, NOFOLLOW_LINKS)) {
            Files.createDirectory(path, PosixFilePermissions.asFileAttribute(DIRECTORY_MODE))
            syncDirectory(directory.parentFile)
        }
        if (!Files.isDirectory(path, NOFOLLOW_LINKS) || Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) != DIRECTORY_MODE)
            throw ContentException(ContentFailure.Io)
        val temporary = File(directory, TEMPORARY).toPath()
        if (Files.exists(temporary, NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(temporary, NOFOLLOW_LINKS)) throw ContentException(ContentFailure.Corrupt)
            Files.delete(temporary)
            syncDirectory(directory)
        }
    }

    private fun <T> storage(operation: () -> T): T = try { operation() }
    catch (failure: ContentException) { throw failure }
    catch (_: AppLockedException) { throw ContentException(ContentFailure.KeyUnavailable) }
    catch (_: LockUnavailableException) { throw ContentException(ContentFailure.KeyUnavailable) }
    catch (_: java.security.GeneralSecurityException) { throw ContentException(ContentFailure.KeyUnavailable) }
    catch (_: Exception) { throw ContentException(ContentFailure.Io) }

    companion object {
        private const val RECORD = "snapshot.bin"
        private const val TEMPORARY = ".snapshot.tmp"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private val HEADER = byteArrayOf(73, 84, 79, 67, 1)
        private const val OVERHEAD = 5 + 12 + 16
        private val DOMAIN = "ito/content-storage/snapshot/1".toByteArray(Charsets.US_ASCII)
        private val FILE_MODE = PosixFilePermissions.fromString("rw-------")
        private val DIRECTORY_MODE = PosixFilePermissions.fromString("rwx------")
        private fun syncAndroidDirectory(directory: File) {
            val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
            try {
                if (!OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) throw ContentException(ContentFailure.Io)
                Os.fsync(descriptor)
            } finally { Os.close(descriptor) }
        }
    }
}
