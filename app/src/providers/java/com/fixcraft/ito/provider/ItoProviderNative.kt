package com.fixcraft.ito.provider

import com.fixcraft.ito.transport.ManualConfiguration
import com.fixcraft.ito.transport.nativebridge.ItoNative
import com.fixcraft.ito.transport.nativebridge.NativeFailure
import com.fixcraft.ito.transport.store.StoreException
import com.fixcraft.yume.provider.ProviderBundleCodec
import com.fixcraft.yume.provider.ProviderCodec
import com.fixcraft.yume.provider.ProviderFile
import com.fixcraft.yume.provider.ProviderKitValidator
import java.util.concurrent.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** Ito owns its named relay-service policy and qualified JNI/staging adapter. */
class ItoProviderNative(private val sessions: ItoProviderSessions) : ProviderKitValidator.Native {
    override fun openKit(sealed: ByteArray, code: ByteArray): List<ProviderFile> = nativeBoundary {
        cancelled()
        val opened = ItoNative.openKit(sealed, code)
        try {
            cancelled()
            opened.map { ProviderFile(it.path, it.executable, it.bytes) }
        } catch (error: Throwable) { opened.forEach { it.bytes.fill(0) }; throw error }
    }

    override fun checkClientConfig(config: ByteArray) = nativeBoundary {
        cancelled()
        if (ItoNative.checkConfig(config) != ItoNative.CLIENT) throw ProviderCodec.Refusal("unsupported")
    }

    override fun checkConsumerConfig(config: ByteArray) = nativeBoundary {
        checkClientConfig(config)
        val description = ManualConfiguration.describe(config)
        if (description.dialAddress != null) throw ProviderCodec.Refusal("unsupported")
        // This is the device-free portion of Ito's runtime adaptation. The
        // captured Network supplies its resolved numeric dial address later.
        val document = JSONObject(config.toString(Charsets.UTF_8))
        checkProviderServicePolicy(document)
        document.put("adapters", JSONArray())
        document.remove("control")
        val adapted = document.toString().toByteArray(Charsets.UTF_8)
        try { checkClientConfig(adapted) } finally { adapted.fill(0) }
    }

    override fun fingerprint(files: List<ProviderFile>, originalConfig: ByteArray): String = nativeBoundary {
        checkClientConfig(originalConfig)
        val referenced = referenced(files, originalConfig)
        val session = sessions.stage(referenced)
        try {
            cancelled()
            val identity = ItoNative.validate(originalConfig, session.directory.absolutePath)
            try {
                cancelled()
                identity.joinToString("") { "%02x".format(it.toInt() and 255) }
            } finally { identity.fill(0) }
        } finally { closeAfterBorrow(session) }
    }

    companion object {
        /** The call controller opens only this named stream; no local network adapter is needed. */
        internal fun checkProviderServicePolicy(document: JSONObject) {
            val services = document.getJSONArray("services")
            if (services.length() != 1) throw ProviderCodec.Refusal("unsupported")
            val service = services.getJSONObject(0)
            if (service.getString("name") != "ito-call" || service.getString("kind") != "stream" ||
                document.getJSONArray("adapters").length() != 0) {
                throw ProviderCodec.Refusal("unsupported")
            }
        }

        internal fun referenced(files: List<ProviderFile>, config: ByteArray): Map<String, ByteArray> {
            if (ProviderBundleCodec.problem(files) != null) throw ProviderCodec.Refusal("malformed")
            val indexed = files.associateBy { it.path }
            if (!indexed.getValue(ProviderBundleCodec.CONFIG_PATH).bytes.contentEquals(config)) throw ProviderCodec.Refusal("binding")
            val referenced = ManualConfiguration.referencedFiles(config, indexed.mapValues { it.value.bytes })
            if (referenced.keys.any { indexed.getValue(it).executable }) throw ProviderCodec.Refusal("unsupported")
            return referenced
        }

        internal fun cancelled() {
            if (Thread.currentThread().isInterrupted) throw CancellationException()
        }

        internal fun closeAfterBorrow(session: ItoProviderSession) {
            // File channels refuse work on an interrupted thread. Cleanup still
            // runs after cancellation, and the caller retains its interrupt.
            val interrupted = Thread.interrupted()
            try { session.close() } finally { if (interrupted) Thread.currentThread().interrupt() }
        }

        internal fun <T> nativeBoundary(operation: () -> T): T = try { operation() }
        catch (failure: ProviderCodec.Refusal) { throw failure }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: StoreException) { throw ProviderCodec.Refusal("storage") }
        catch (failure: NativeFailure) { throw ProviderCodec.Refusal(if (failure.code == -9 || failure.code == -8) "malformed" else "unsupported") }
        catch (_: IllegalArgumentException) { throw ProviderCodec.Refusal("unsupported") }
        catch (_: org.json.JSONException) { throw ProviderCodec.Refusal("malformed") }
    }
}
