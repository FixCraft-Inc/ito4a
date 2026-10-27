package com.fixcraft.ito.transport.store

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
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

internal interface SnapshotCipher {
    fun encrypt(plaintext: ByteArray, allowNewKey: Boolean): ByteArray
    fun decrypt(ciphertext: ByteArray): ByteArray
}

/** One instance is the process's only writer. No secret plaintext is cached. */
internal class ConfigurationRepository(
    private val root: File,
    private val cipher: SnapshotCipher,
    private val beforeReplace: () -> Unit = {},
) {
    private val snapshotFile get() = root.toPath().resolve("snapshot.bin")
    private val temporaryFile get() = root.toPath().resolve("snapshot.tmp")
    private val sessionsDirectory get() = root.toPath().resolve("sessions")
    private val activeSessions = mutableSetOf<Path>()

    @Synchronized
    fun list(): ConfigurationCatalog = io {
        prepareDirectories()
        read().use { it.catalog() }
    }

    @Synchronized
    fun importConfiguration(
        label: String,
        files: Map<String, ByteArray>,
        beforePublish: () -> Unit = {},
    ): ConfigurationMetadata = io {
        if (!StoreLimits.validLabel(label)) throw StoreException(StoreFailure.InvalidInput)
        StoreLimits.validateFiles(files, StoreFailure.InvalidInput)
        prepareDirectories()
        read().use { state ->
            if (state.entries.size >= StoreLimits.CONFIGURATIONS ||
                state.entries.sumOf { entry -> entry.files.values.sumOf { it.size.toLong() } } +
                files.values.sumOf { it.size.toLong() } > StoreLimits.MATERIAL_BYTES
            ) throw StoreException(StoreFailure.Limit)
            val metadata = ConfigurationMetadata(newId(), label)
            val copied = linkedMapOf<String, ByteArray>()
            state.entries += MaterialEntry(metadata, copied)
            for ((path, data) in files) copied[path] = data.copyOf()
            write(state, beforePublish)
            metadata
        }
    }

    @Synchronized
    fun select(id: String) = io {
        prepareDirectories()
        read().use { state ->
            if (state.entries.none { it.metadata.id == id }) throw StoreException(StoreFailure.NotFound)
            state.selectedId = id
            write(state)
        }
    }

    @Synchronized
    fun remove(id: String) = io {
        prepareDirectories()
        read().use { state ->
            val entry = state.entries.find { it.metadata.id == id }
                ?: throw StoreException(StoreFailure.NotFound)
            state.entries.remove(entry)
            entry.close()
            if (state.selectedId == id) state.selectedId = null
            write(state)
        }
    }

    @Synchronized
    fun materialize(id: String): ConfigurationSession = io {
        prepareDirectories()
        read().use { state ->
            val entry = state.entries.find { it.metadata.id == id }
                ?: throw StoreException(StoreFailure.NotFound)
            stageFiles(entry.files)
        }
    }

    @Synchronized
    fun stage(files: Map<String, ByteArray>): ConfigurationSession = io {
        StoreLimits.validateFiles(files, StoreFailure.InvalidInput)
        prepareDirectories()
        stageFiles(files)
    }

    /** Startup only. Never remove credential files borrowed by a live handle. */
    @Synchronized
    fun recoverStaging() = io {
        prepareDirectories()
        if (activeSessions.isNotEmpty()) throw StoreException(StoreFailure.Busy)
        if (Files.exists(temporaryFile, NOFOLLOW_LINKS)) {
            requireRegular(temporaryFile, StoreLimits.SNAPSHOT_BYTES + ENVELOPE_OVERHEAD)
            Files.delete(temporaryFile)
        }
        Files.newDirectoryStream(sessionsDirectory).use { children ->
            var count = 0
            for (path in children) {
                if (++count > MAX_ABANDONED_SESSIONS ||
                    !SESSION_NAME.matches(path.fileName.toString())
                ) throw StoreException(StoreFailure.Io)
                wipeTree(path)
            }
        }
    }

    @Synchronized
    private fun closeSession(path: Path) = io {
        try {
            wipeTree(path)
        } finally {
            // close() requires every native borrower to have returned. A
            // failed wipe leaves files for explicit recovery, not a live lease
            // that would prevent that recovery indefinitely.
            activeSessions.remove(path)
        }
    }

    private fun stageFiles(files: Map<String, ByteArray>): ConfigurationSession {
        if (activeSessions.size >= MAX_ACTIVE_SESSIONS) throw StoreException(StoreFailure.Limit)
        val session = sessionsDirectory.resolve("session-${newId()}")
        privateDirectory(session)
        var config: ByteArray? = null
        try {
            for ((relative, bytes) in files) {
                val path = session.resolve(relative)
                ensureParents(session, path.parent)
                writePrivateFile(path, bytes)
            }
            config = files.getValue("yume.json").copyOf()
            activeSessions.add(session)
            return ConfigurationSession(session.toFile(), config) { closeSession(session) }
        } catch (failure: Throwable) {
            config?.fill(0)
            try {
                wipeTree(session)
            } catch (cleanup: StoreException) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    private fun read(): CatalogSnapshot {
        if (!Files.exists(snapshotFile, NOFOLLOW_LINKS)) return CatalogSnapshot()
        val ciphertext = readBounded(snapshotFile, StoreLimits.SNAPSHOT_BYTES + ENVELOPE_OVERHEAD)
        try {
            val plaintext = cipher.decrypt(ciphertext)
            try {
                return SnapshotCodec.decode(plaintext)
            } finally {
                plaintext.fill(0)
            }
        } finally {
            ciphertext.fill(0)
        }
    }

    private fun write(state: CatalogSnapshot, beforePublish: () -> Unit = {}) {
        val plaintext = SnapshotCodec.encode(state)
        try {
            val encrypted = cipher.encrypt(plaintext, !Files.exists(snapshotFile, NOFOLLOW_LINKS))
            try {
                if (encrypted.size > StoreLimits.SNAPSHOT_BYTES + ENVELOPE_OVERHEAD) {
                    throw StoreException(StoreFailure.Limit)
                }
                try {
                    writePrivateFile(temporaryFile, encrypted)
                    beforeReplace()
                    beforePublish()
                    // Failure to support atomic rename is terminal. There is
                    // no delete-then-copy fallback exposing partial contents.
                    Files.move(temporaryFile, snapshotFile, ATOMIC_MOVE, REPLACE_EXISTING)
                } finally {
                    Files.deleteIfExists(temporaryFile)
                }
            } finally {
                encrypted.fill(0)
            }
        } finally {
            plaintext.fill(0)
        }
    }

    private fun prepareDirectories() {
        privateDirectory(root.toPath())
        privateDirectory(sessionsDirectory)
    }

    private fun ensureParents(base: Path, parent: Path) {
        var current = base
        for (component in base.relativize(parent)) {
            current = current.resolve(component)
            privateDirectory(current)
        }
    }

    private fun privateDirectory(path: Path) {
        if (!Files.exists(path, NOFOLLOW_LINKS)) {
            Files.createDirectory(path, PosixFilePermissions.asFileAttribute(DIRECTORY_MODE))
        }
        if (!Files.isDirectory(path, NOFOLLOW_LINKS) ||
            Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) != DIRECTORY_MODE
        ) throw StoreException(StoreFailure.Io)
    }

    private fun writePrivateFile(path: Path, bytes: ByteArray) {
        Files.createFile(path, PosixFilePermissions.asFileAttribute(FILE_MODE))
        // Creation is exclusive in an app-private directory. NOFOLLOW on the
        // later open prevents replacing that final component with a link.
        FileChannel.open(path, java.nio.file.StandardOpenOption.WRITE, NOFOLLOW_LINKS).use { channel ->
            val data = ByteBuffer.wrap(bytes)
            while (data.hasRemaining()) channel.write(data)
            channel.force(true)
        }
    }

    private fun readBounded(path: Path, bound: Int): ByteArray {
        val size = requireRegular(path, bound)
        val result = ByteArray(size)
        try {
            Files.newByteChannel(path, setOf(READ, NOFOLLOW_LINKS)).use { channel ->
                val target = ByteBuffer.wrap(result)
                while (target.hasRemaining()) {
                    if (channel.read(target) < 0) throw StoreException(StoreFailure.Corrupt)
                }
                if (channel.read(ByteBuffer.allocate(1)) != -1) throw StoreException(StoreFailure.Corrupt)
            }
            return result
        } catch (failure: Throwable) {
            result.fill(0)
            throw failure
        }
    }

    private fun requireRegular(path: Path, bound: Int): Int {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) throw StoreException(StoreFailure.Io)
        val length = Files.size(path)
        if (length !in 0..bound.toLong()) throw StoreException(StoreFailure.Corrupt)
        return length.toInt()
    }

    private fun wipeTree(rootPath: Path) {
        if (!Files.exists(rootPath, NOFOLLOW_LINKS)) return
        var nodes = 0
        fun wipe(path: Path, depth: Int) {
            if (++nodes > StoreLimits.FILES * 5 || depth > 5) throw StoreException(StoreFailure.Io)
            if (Files.isSymbolicLink(path)) {
                Files.delete(path)
            } else if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
                Files.newDirectoryStream(path).use { children ->
                    for (child in children) wipe(child, depth + 1)
                }
                Files.delete(path)
            } else {
                val length = requireRegular(path, StoreLimits.FILE_BYTES)
                Files.newByteChannel(path, setOf(java.nio.file.StandardOpenOption.WRITE, NOFOLLOW_LINKS)).use { channel ->
                    val zeros = ByteArray(minOf(length, 8192))
                    var left = length
                    while (left > 0) {
                        val chunk = ByteBuffer.wrap(zeros, 0, minOf(left, zeros.size))
                        while (chunk.hasRemaining()) channel.write(chunk)
                        left -= chunk.limit()
                    }
                }
                Files.delete(path)
            }
        }
        wipe(rootPath, 0)
    }

    private fun <T> io(operation: () -> T): T = try {
        operation()
    } catch (_: IOException) {
        throw StoreException(StoreFailure.Io)
    } catch (_: SecurityException) {
        throw StoreException(StoreFailure.Io)
    } catch (_: UnsupportedOperationException) {
        throw StoreException(StoreFailure.Io)
    }

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "")

    private companion object {
        const val ENVELOPE_OVERHEAD = 64
        const val MAX_ACTIVE_SESSIONS = 16
        const val MAX_ABANDONED_SESSIONS = 64
        val SESSION_NAME = Regex("session-[0-9a-f]{32}")
        val DIRECTORY_MODE = setOf(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
        val FILE_MODE = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    }
}
