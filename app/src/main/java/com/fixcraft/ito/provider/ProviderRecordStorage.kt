package com.fixcraft.ito.provider

import com.fixcraft.yume.provider.ProviderSecretBackend
import com.fixcraft.yume.provider.ProviderStore
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
import java.util.UUID

/** One caller-owned backend; ProviderStore owns enrollment publication and its lock. */
internal class ProviderRecordStorage(
    override val directory: File,
    private val cipher: ProviderRecordCipher,
    private val syncDirectory: (File) -> Unit,
    private val checkpoint: (Checkpoint) -> Unit = {},
) : ProviderSecretBackend {
    internal enum class Checkpoint { AfterFileSync, AfterReplace, AfterDirectorySync }

    @Synchronized override fun ensureReady(): Boolean = io {
        prepare()
        cipher.requireKey(allowCreate = !hasRecords())
        true
    }

    @Synchronized override fun isUnlocked(): Boolean = try {
        prepare()
        cipher.requireKey(allowCreate = false)
        true
    } catch (_: ProviderStorageException) { false }
    catch (_: IOException) { false }
    catch (_: SecurityException) { false }

    @Synchronized override fun readBlob(name: String): ByteArray? = io {
        val path = target(name)
        prepare()
        if (!Files.exists(path, NOFOLLOW_LINKS)) return@io null
        val encrypted = readBounded(path)
        try { cipher.decrypt(name, encrypted) } finally { encrypted.fill(0) }
    }

    @Synchronized override fun writeBlob(name: String, plaintext: ByteArray) = io {
        val path = target(name)
        if (plaintext.size > MAX_PLAINTEXT_BYTES) throw ProviderStorageException(ProviderStorageFailure.InvalidInput)
        prepare()
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            val previous = readBounded(path)
            try { cipher.decrypt(name, previous).fill(0) } finally { previous.fill(0) }
        }
        val encrypted = cipher.encrypt(name, plaintext, allowCreate = !hasRecords())
        try {
            val temporary = directory.toPath().resolve(".$name-${UUID.randomUUID()}.tmp")
            try {
                Files.createFile(temporary, PosixFilePermissions.asFileAttribute(FILE_MODE))
                FileChannel.open(temporary, WRITE, NOFOLLOW_LINKS).use { output ->
                    val buffer = ByteBuffer.wrap(encrypted)
                    while (buffer.hasRemaining()) output.write(buffer)
                    output.force(true)
                }
                checkpoint(Checkpoint.AfterFileSync)
                Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
                checkpoint(Checkpoint.AfterReplace)
                syncDirectory(directory)
                checkpoint(Checkpoint.AfterDirectorySync)
            } finally {
                if (Files.deleteIfExists(temporary)) syncDirectory(directory)
            }
        } finally { encrypted.fill(0) }
    }

    /** Epoch tombstones are committed by ProviderStore before this keyless removal. */
    @Synchronized override fun deleteBlob(name: String) = io {
        val path = target(name)
        prepare()
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            requireRecord(path)
            Files.delete(path)
            syncDirectory(directory)
        }
    }

    /** Startup only, before the shared writer or selection owner can publish. */
    @Synchronized fun recoverStaging() = io {
        prepare()
        var count = 0
        Files.newDirectoryStream(directory.toPath()).use { children ->
            for (path in children) {
                if (++count > MAX_RECORD_FILES) throw ProviderStorageException(ProviderStorageFailure.Corrupt)
                val name = path.fileName.toString()
                if (TEMPORARY.matches(name)) {
                    requireRecord(path)
                    Files.delete(path)
                } else if (!name.endsWith(".bin") || !NAME.matches(name.removeSuffix(".bin"))) {
                    throw ProviderStorageException(ProviderStorageFailure.Corrupt)
                }
            }
        }
        syncDirectory(directory)
    }

    private fun target(name: String): Path {
        if (!NAME.matches(name) || name == "." || name == "..") {
            throw ProviderStorageException(ProviderStorageFailure.InvalidInput)
        }
        return directory.toPath().resolve("$name.bin")
    }

    private fun prepare() {
        val path = directory.toPath()
        val parent = directory.parentFile ?: throw ProviderStorageException(ProviderStorageFailure.Io)
        if (!directory.isAbsolute) throw ProviderStorageException(ProviderStorageFailure.Io)
        if (!Files.exists(path, NOFOLLOW_LINKS)) {
            Files.createDirectory(path, PosixFilePermissions.asFileAttribute(DIRECTORY_MODE))
            syncDirectory(parent)
        }
        if (!Files.isDirectory(path, NOFOLLOW_LINKS) || Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) != DIRECTORY_MODE) {
            throw ProviderStorageException(ProviderStorageFailure.Io)
        }
    }

    private fun hasRecords(): Boolean {
        var count = 0
        var found = false
        Files.newDirectoryStream(directory.toPath()).use { children ->
            for (path in children) {
                if (++count > MAX_RECORD_FILES) throw ProviderStorageException(ProviderStorageFailure.Corrupt)
                val name = path.fileName.toString()
                requireRecord(path)
                if (name.endsWith(".bin") && NAME.matches(name.removeSuffix(".bin"))) found = true
                else if (!TEMPORARY.matches(name)) throw ProviderStorageException(ProviderStorageFailure.Corrupt)
            }
        }
        return found
    }

    private fun requireRecord(path: Path): Int {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) != FILE_MODE) {
            throw ProviderStorageException(ProviderStorageFailure.Corrupt)
        }
        val size = Files.size(path)
        if (size !in 0..(MAX_PLAINTEXT_BYTES + 64).toLong()) throw ProviderStorageException(ProviderStorageFailure.Corrupt)
        return size.toInt()
    }

    private fun readBounded(path: Path): ByteArray {
        val size = requireRecord(path)
        if (size < ProviderRecordCipher.OVERHEAD) throw ProviderStorageException(ProviderStorageFailure.Corrupt)
        val bytes = ByteArray(size)
        try {
            Files.newByteChannel(path, setOf(READ, NOFOLLOW_LINKS)).use { input ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) if (input.read(buffer) < 0) throw ProviderStorageException(ProviderStorageFailure.Corrupt)
                if (input.read(ByteBuffer.allocate(1)) != -1) throw ProviderStorageException(ProviderStorageFailure.Corrupt)
            }
            return bytes
        } catch (error: Throwable) { bytes.fill(0); throw error }
    }

    private fun <T> io(operation: () -> T): T = try { operation() }
    catch (failure: ProviderStorageException) { throw failure }
    catch (_: IOException) { throw ProviderStorageException(ProviderStorageFailure.Io) }
    catch (_: SecurityException) { throw ProviderStorageException(ProviderStorageFailure.Io) }
    catch (_: UnsupportedOperationException) { throw ProviderStorageException(ProviderStorageFailure.Io) }

    companion object {
        const val MAX_PLAINTEXT_BYTES = ProviderStore.MAX_BUNDLE_BYTES
        private const val MAX_RECORD_FILES = 512
        private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private val TEMPORARY = Regex("\\.[A-Za-z0-9][A-Za-z0-9._-]{0,127}-[0-9a-f-]{36}\\.tmp")
        private val DIRECTORY_MODE = PosixFilePermissions.fromString("rwx------")
        private val FILE_MODE = PosixFilePermissions.fromString("rw-------")
    }
}
