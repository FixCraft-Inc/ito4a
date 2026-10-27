package com.fixcraft.ito.transport.store

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

internal object StoreLimits {
    const val CONFIGURATIONS = 8
    const val FILES = 32
    const val FILE_BYTES = 1024 * 1024
    const val CONFIGURATION_BYTES = 2 * 1024 * 1024
    const val MATERIAL_BYTES = 12 * 1024 * 1024
    const val SNAPSHOT_BYTES = 13 * 1024 * 1024
    const val PATH_BYTES = 192
    const val LABEL_BYTES = 256
    val idPattern = Regex("[0-9a-f]{32}")
    val componentPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    fun validPath(path: String): Boolean = path.length <= PATH_BYTES &&
        path.split('/').let { parts ->
            parts.size in 1..4 && parts.all {
                it != "." && it != ".." && componentPattern.matches(it)
            }
        }

    fun validLabel(label: String): Boolean = label.length in 1..LABEL_BYTES && label.isNotBlank() &&
        label.toByteArray(Charsets.UTF_8).size <= LABEL_BYTES &&
        label.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == label &&
        label.none { it.isISOControl() }

    fun validateFiles(files: Map<String, ByteArray>, failure: StoreFailure) {
        if (files.size !in 1..FILES || "yume.json" !in files ||
            files.any { (path, data) ->
                !validPath(path) || data.isEmpty() || data.size > FILE_BYTES
            } || files.keys.any { path -> files.keys.any { it.startsWith("$path/") } } ||
            files.values.sumOf { it.size.toLong() } > CONFIGURATION_BYTES
        ) throw StoreException(failure)
    }
}

internal class MaterialEntry(
    val metadata: ConfigurationMetadata,
    val files: Map<String, ByteArray>,
) : AutoCloseable {
    override fun close() = files.values.forEach { it.fill(0) }
}

internal class CatalogSnapshot(
    val entries: MutableList<MaterialEntry> = mutableListOf(),
    var selectedId: String? = null,
) : AutoCloseable {
    override fun close() = entries.forEach { it.close() }

    fun catalog() = ConfigurationCatalog(entries.map { it.metadata }, selectedId)
}

internal object SnapshotCodec {
    private const val VERSION = 1

    fun encode(snapshot: CatalogSnapshot): ByteArray {
        val capacity = 12 + (snapshot.selectedId?.length ?: 0) + snapshot.entries.sumOf { entry ->
            12 + entry.metadata.id.length + entry.metadata.label.toByteArray(Charsets.UTF_8).size +
                entry.files.entries.sumOf { (path, content) -> 8 + path.length + content.size }
        }
        if (capacity > StoreLimits.SNAPSHOT_BYTES) throw StoreException(StoreFailure.Limit)
        val bytes = WipingOutput(capacity)
        try {
            DataOutputStream(bytes).use { out ->
                out.writeInt(VERSION)
                out.writeInt(snapshot.entries.size)
                writeText(out, snapshot.selectedId ?: "")
                for (entry in snapshot.entries) {
                    writeText(out, entry.metadata.id)
                    writeText(out, entry.metadata.label)
                    out.writeInt(entry.files.size)
                    for ((path, content) in entry.files.toSortedMap()) {
                        writeText(out, path)
                        out.writeInt(content.size)
                        out.write(content)
                    }
                }
            }
            if (bytes.size() > StoreLimits.SNAPSHOT_BYTES) throw StoreException(StoreFailure.Limit)
            return bytes.toByteArray()
        } finally {
            bytes.wipe()
        }
    }

    fun decode(bytes: ByteArray): CatalogSnapshot {
        val result = CatalogSnapshot()
        try {
            if (bytes.size > StoreLimits.SNAPSHOT_BYTES) corrupt()
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                if (input.readInt() != VERSION) corrupt()
                val count = input.readInt()
                if (count !in 0..StoreLimits.CONFIGURATIONS) corrupt()
                result.selectedId = readText(input, 32).ifEmpty { null }
                var total = 0L
                repeat(count) {
                    val id = readText(input, 32)
                    val label = readText(input, StoreLimits.LABEL_BYTES)
                    if (!StoreLimits.idPattern.matches(id) || !StoreLimits.validLabel(label) ||
                        result.entries.any { it.metadata.id == id }
                    ) corrupt()
                    val fileCount = input.readInt()
                    if (fileCount !in 1..StoreLimits.FILES) corrupt()
                    val files = linkedMapOf<String, ByteArray>()
                    // Register the owner before reading material so a later
                    // malformed field still clears every completed allocation.
                    result.entries += MaterialEntry(ConfigurationMetadata(id, label), files)
                    var entryBytes = 0L
                    repeat(fileCount) {
                        val path = readText(input, StoreLimits.PATH_BYTES)
                        if (!StoreLimits.validPath(path) || path in files) corrupt()
                        val length = input.readInt()
                        if (length !in 1..StoreLimits.FILE_BYTES || length > input.available()) corrupt()
                        entryBytes += length
                        total += length
                        if (entryBytes > StoreLimits.CONFIGURATION_BYTES ||
                            total > StoreLimits.MATERIAL_BYTES
                        ) corrupt()
                        val content = ByteArray(length)
                        files[path] = content
                        input.readFully(content)
                    }
                    StoreLimits.validateFiles(files, StoreFailure.Corrupt)
                }
                if (input.available() != 0 ||
                    (result.selectedId != null && result.entries.none { it.metadata.id == result.selectedId })
                ) corrupt()
            }
            return result
        } catch (failure: StoreException) {
            result.close()
            throw failure
        } catch (_: EOFException) {
            result.close()
            corrupt()
        } catch (_: IOException) {
            result.close()
            corrupt()
        } catch (failure: Throwable) {
            result.close()
            throw failure
        }
    }

    private fun writeText(out: DataOutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        try {
            out.writeInt(bytes.size)
            out.write(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun readText(input: DataInputStream, bound: Int): String {
        val length = input.readInt()
        if (length !in 0..bound || length > input.available()) corrupt()
        val bytes = ByteArray(length)
        return try {
            input.readFully(bytes)
            val text = bytes.toString(Charsets.UTF_8)
            // Refuse malformed UTF-8 instead of accepting replacement characters.
            if (!text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) corrupt()
            text
        } finally {
            bytes.fill(0)
        }
    }

    private fun corrupt(): Nothing = throw StoreException(StoreFailure.Corrupt)

    private class WipingOutput(capacity: Int) : ByteArrayOutputStream(capacity) {
        fun wipe() = buf.fill(0)
    }
}
