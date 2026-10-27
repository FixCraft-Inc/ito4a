package com.fixcraft.ito.transport.store

import android.content.Context
import java.io.File

/** Ito's process-owned secret boundary. Calls belong on an I/O dispatcher. */
class ConfigurationStore(context: Context) {
    private val repository = ConfigurationRepository(
        File(context.applicationContext.noBackupFilesDir, "ito-transport"),
        AndroidSnapshotCipher("ito.transport.configurations.v1"),
    )

    fun list(): ConfigurationCatalog = repository.list()

    /**
     * Input is borrowed and must already have passed native/client validation.
     * beforePublish checks cancellation before atomic replacement. Cancellation
     * arriving after that checkpoint can leave a complete committed snapshot.
     * It must not reenter the store or perform I/O.
     */
    fun importConfiguration(
        label: String,
        files: Map<String, ByteArray>,
        beforePublish: () -> Unit = {},
    ): ConfigurationMetadata = repository.importConfiguration(label, files, beforePublish)

    fun select(id: String) = repository.select(id)

    fun remove(id: String) = repository.remove(id)

    fun materialize(id: String): ConfigurationSession = repository.materialize(id)

    /** Bounded private staging for native validation. Does not publish a catalog entry. */
    fun stage(files: Map<String, ByteArray>): ConfigurationSession = repository.stage(files)

    /** Call once at process initialization, before native connections or imports. */
    fun recoverStaging() = repository.recoverStaging()
}
