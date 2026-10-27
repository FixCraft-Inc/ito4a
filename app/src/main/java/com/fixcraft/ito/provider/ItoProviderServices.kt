package com.fixcraft.ito.provider

import com.fixcraft.ito.transport.nativebridge.ItoNative
import com.fixcraft.yume.consumer.ServiceAdapter
import com.fixcraft.yume.provider.ProviderBundleCodec
import com.fixcraft.yume.provider.ProviderCodec

/** Shared enrollment selection and provider-only session materialization. */
class ItoProviderServices(
    private val secrets: ItoProviderBackend,
    private val sessions: ItoProviderSessions,
) : ServiceAdapter<ItoProviderSession> {
    private var initializationAttempted = false
    private var initialized = false

    @Synchronized override fun ensureReady(): Boolean = storageBoundary {
        if (!initializationAttempted) recoverStorage()
        if (!initialized) throw ProviderCodec.Refusal("storage")
        if (!sessions.isReady()) { initialized = false; throw ProviderCodec.Refusal("storage") }
        secrets.ensureReady()
    }

    /** Explicit retry, with no provider operation or JNI borrower still active. */
    @Synchronized fun recoverStorage() = storageBoundary {
        initializationAttempted = true
        initialized = false
        // This refuses Busy while a session is still borrowed. A failed retry
        // never makes either the selection or native staging usable.
        sessions.recoverStaging()
        secrets.recoverStaging()
        if (!secrets.ensureReady()) throw ProviderCodec.Refusal("storage")
        initialized = true
    }

    @Synchronized override fun isUnlocked(): Boolean = initialized && sessions.isReady() && secrets.isUnlocked()
    override fun readSelection(): ByteArray? = storageBoundary {
        ensureReady()
        secrets.readBlob(SELECTION)
    }
    override fun writeSelection(bytes: ByteArray) = storageBoundary {
        ensureReady()
        secrets.writeBlob(SELECTION, bytes)
    }

    override fun openSession(bundle: ByteArray): ItoProviderSession = ItoProviderNative.nativeBoundary {
        ensureReady()
        ItoProviderNative.cancelled()
        val files = try { ProviderBundleCodec.decode(bundle) }
        catch (_: IllegalArgumentException) { throw ProviderCodec.Refusal("malformed") }
        try {
            val config = files.single { it.path == ProviderBundleCodec.CONFIG_PATH }.bytes
            val native = ItoProviderNative(sessions)
            native.checkClientConfig(config)
            native.checkConsumerConfig(config)
            val session = sessions.stage(ItoProviderNative.referenced(files, config))
            try {
                ItoNative.validate(session.config, session.directory.absolutePath).fill(0)
                ItoProviderNative.cancelled()
                session
            } catch (error: Throwable) {
                ItoProviderNative.closeAfterBorrow(session)
                throw error
            }
        } finally { files.forEach { it.bytes.fill(0) } }
    }

    private fun <T> storageBoundary(operation: () -> T): T = try { operation() }
    catch (_: ProviderStorageException) { synchronized(this) { initialized = false }; throw ProviderCodec.Refusal("storage") }
    catch (_: com.fixcraft.ito.transport.store.StoreException) { synchronized(this) { initialized = false }; throw ProviderCodec.Refusal("storage") }

    companion object { private const val SELECTION = "service-selection" }
}
