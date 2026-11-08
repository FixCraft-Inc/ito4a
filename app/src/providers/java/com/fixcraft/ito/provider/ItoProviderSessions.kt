package com.fixcraft.ito.provider

import android.content.Context
import android.content.ContextWrapper
import com.fixcraft.ito.transport.store.ConfigurationSession
import com.fixcraft.ito.transport.store.ConfigurationStore
import com.fixcraft.ito.transport.store.StoreException
import com.fixcraft.ito.transport.store.StoreFailure
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermissions

/** Reuses I1's bounded staging implementation in a separate provider-only root. */
class ItoProviderSessions(context: Context) {
    private val app = context.applicationContext
    private val root = File(app.noBackupFilesDir, "ito-provider-runtime")
    private val store: ConfigurationStore
    @Volatile private var ready = false

    init {
        val isolated = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        store = ConfigurationStore(isolated)
    }

    /** Explicit initialization/recovery only, after every previous borrower ends. */
    @Synchronized fun recoverStaging() {
        ready = false
        val mode = PosixFilePermissions.fromString("rwx------")
        try {
            if (!Files.exists(root.toPath(), NOFOLLOW_LINKS)) {
                Files.createDirectory(root.toPath(), PosixFilePermissions.asFileAttribute(mode))
            }
            if (!Files.isDirectory(root.toPath(), NOFOLLOW_LINKS) || Files.getPosixFilePermissions(root.toPath(), NOFOLLOW_LINKS) != mode) {
                throw StoreException(StoreFailure.Io)
            }
        } catch (_: java.io.IOException) { throw StoreException(StoreFailure.Io) }
        catch (_: SecurityException) { throw StoreException(StoreFailure.Io) }
        store.recoverStaging()
        ready = true
    }

    fun isReady(): Boolean = ready

    @Synchronized fun stage(files: Map<String, ByteArray>): ItoProviderSession {
        if (!ready) throw StoreException(StoreFailure.Io)
        return try { ItoProviderSession(store.stage(files), ::cleanupFailed) }
        catch (failure: StoreException) {
            if (failure.reason in UNRECOVERABLE) ready = false
            throw failure
        }
    }

    private fun cleanupFailed() { ready = false }

    private companion object {
        val UNRECOVERABLE = setOf(StoreFailure.Corrupt, StoreFailure.KeyUnavailable, StoreFailure.Io, StoreFailure.Busy)
    }
}

/** The connection owner ends all JNI borrowing before close; a failed wipe latches staging. */
class ItoProviderSession internal constructor(
    private val delegate: ConfigurationSession,
    private val onCleanupFailure: () -> Unit,
) : AutoCloseable {
    val config: ByteArray get() = delegate.config
    val directory: File get() = delegate.directory

    override fun close() {
        try { delegate.close() }
        catch (failure: StoreException) { onCleanupFailure(); throw failure }
    }
}
