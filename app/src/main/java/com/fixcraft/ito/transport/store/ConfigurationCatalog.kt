package com.fixcraft.ito.transport.store

import java.io.Closeable
import java.io.File

/** Public local labels. Neither the label nor the random ID grants authority. */
data class ConfigurationMetadata(val id: String, val label: String)

data class ConfigurationCatalog(
    val entries: List<ConfigurationMetadata>,
    val selectedId: String?,
)

enum class StoreFailure { InvalidInput, Limit, Corrupt, KeyUnavailable, Io, NotFound, Busy }

class StoreException(val reason: StoreFailure) : Exception("Transport storage: ${reason.name}")

/** Native handles must be destroyed before the session's credential files. */
class ConfigurationSession internal constructor(
    val directory: File,
    val config: ByteArray,
    private val cleanup: () -> Unit,
) : Closeable {
    private var closed = false

    @Synchronized
    override fun close() {
        if (closed) return
        // A failed cleanup remains retryable, while the in-memory document is
        // cleared even when filesystem cleanup reports an error.
        try {
            cleanup()
            closed = true
        } finally {
            config.fill(0)
        }
    }
}
