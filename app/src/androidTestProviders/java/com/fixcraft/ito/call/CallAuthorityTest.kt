package com.fixcraft.ito.call

import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import com.fixcraft.ito.ItoApplication
import com.fixcraft.yume.provider.ProviderTransport
import com.fixcraft.yume.provider.ProviderBearer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fixcraft.ito.provider.ItoProviderBackend
import com.fixcraft.ito.provider.ItoProviderNative
import com.fixcraft.ito.provider.ItoProviderServices
import com.fixcraft.ito.provider.ItoProviderSessions
import com.fixcraft.ito.transport.ConnectionController
import com.fixcraft.ito.transport.ConnectionFailure
import com.fixcraft.ito.transport.ConnectionPhase
import com.fixcraft.ito.transport.ManualConfiguration
import com.fixcraft.ito.transport.nativebridge.ItoNative
import com.fixcraft.ito.integration.transportServices
import com.fixcraft.yume.consumer.ServiceController
import com.fixcraft.yume.consumer.ServiceSelection
import com.fixcraft.yume.provider.ProviderBundleCodec
import com.fixcraft.yume.provider.ProviderClient
import com.fixcraft.yume.provider.ProviderClock
import com.fixcraft.yume.provider.ProviderCodec
import com.fixcraft.yume.provider.ProviderFile
import com.fixcraft.yume.provider.ProviderKitValidator
import com.fixcraft.yume.provider.ProviderStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong

/** Actual run/call/JNI owners. Host supplies an authenticated, waiting relay. */
@RequiresI4bFixture
@RunWith(AndroidJUnit4::class)
class CallAuthorityTest {
    @Test fun selectedNetworkLoss() = exercise("network")
    @Test fun providerRemoval() = exercise("remove")
    @Test fun providerExpiry() = exercise("expiry")

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)).use { it.copyTo(java.io.OutputStream.nullOutputStream()) }
    }

    private fun exercise(cause: String) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(InstrumentationRegistry.getArguments().getString("i4bAuthority") == "true")
        val app = instrumentation.targetContext
        val baseline = ItoNative.liveHandles()
        val book = (app.applicationContext as ItoApplication).callBook
        book.open()
        if ((book.state.value as BookState.Ready).identity == null) book.createIdentity()
        val coreBaseline = CallNative.liveHandles()
        val root = Files.createTempDirectory(app.noBackupFilesDir.toPath(), "authority-").toFile()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        var cleanupOwner: AutoCloseable? = null
        var storeOwner: ProviderStore? = null
        var connectionOwner: ConnectionController? = null
        var files: Map<String, ByteArray> = emptyMap()
        var registeredCallback: ConnectivityManager.NetworkCallback? = null
        val manager = context.getSystemService(ConnectivityManager::class.java)
        try {
        val fixture = File(app.noBackupFilesDir, "authority-fixture/client")
        files = fixture.walkTopDown().filter { it.isFile }.associate { it.relativeTo(fixture).invariantSeparatorsPath to it.readBytes() }
        val shift = AtomicLong()
        val clock = ProviderClock { ProviderClock.Reading(System.currentTimeMillis() / 1000 + shift.get() / 1000,
            SystemClock.elapsedRealtime() + shift.get()) }
        val backend = ItoProviderBackend(context)
        val sessions = ItoProviderSessions(context)
        val adapter = ItoProviderServices(backend, sessions)
        check(adapter.ensureReady())
        val store = ProviderStore(File(root, "provider-state"), backend)
        storeOwner = store
        val provider = "https://lifecycle.example"
        val now = System.currentTimeMillis() / 1000
        val identity = ItoProviderNative(sessions).fingerprint(files.map { ProviderFile(it.key, false, it.value) }, files.getValue("yume.json"))
        val description = ManualConfiguration.describe(files.getValue("yume.json"))
        val config = JSONObject(String(files.getValue("yume.json")))
        val serverKey = java.security.MessageDigest.getInstance("SHA-256").digest(
            files.getValue(config.getJSONObject("credentials").getJSONObject("server_identity").getString("file")))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val epoch = store.add(provider)
        val request = ProviderCodec.GrantRequest(provider, "device", "request", "call", "edge", "relay", null)
        val grant = ProviderCodec.Grant(request, "grant", 1, now, now + 1800, now + 3600, identity, "c".repeat(64), 1068)
        val descriptor = ProviderCodec.Descriptor(provider, "Lifecycle", now + 3600,
            listOf("sealed-kit-1", "device-grants-1", "anonymous-device-1"), listOf("relay"), null)
        val metadata = JSONObject().put("provider", provider).put("device", "device").put("role", "relay")
            .put("issuer", JSONObject.NULL).put("descriptor", JSONObject(descriptor.fields())).put("saved", now)
            .put("deviceCredential", "synthetic-local-only").put("deviceUntil", now + 3600).put("suspended", false)
            .put("grant", JSONObject(grant.fields())).put("server", JSONObject().put("id", "edge").put("label", "Edge")
                .put("host", description.host).put("port", config.getJSONObject("endpoint").getInt("port")).put("key", serverKey))
            .put("pending", JSONObject.NULL).put("retries", 0).toString().toByteArray()
        val bundle = ProviderBundleCodec.encode(files.map { ProviderFile(it.key, false, it.value) })
        try { store.commit(provider, epoch, metadata, mapOf("grant" to bundle)) { true } }
        finally { metadata.fill(0); bundle.fill(0) }
        val services = ServiceController(context, null, "relay", adapter, clock) { connections ->
            ProviderClient(context, null, store, ProviderKitValidator(ItoProviderNative(sessions)), clock, connections,
                http = object : ProviderTransport {
                    override suspend fun request(origin: String, endpoint: String, body: ByteArray?, bearer: ProviderBearer?,
                        limit: Int, responseType: String, requestType: String, proof: String?): ByteArray = throw ProviderCodec.Refusal("unavailable")
                    override fun close() {}
                })
        }
        cleanupOwner = services
        val connections = ConnectionController(context, transportServices(services))
        connectionOwner = connections
        val calls = CallController(connections, book)
        val lost = CompletableDeferred<Unit>()
        var captured: Network? = null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) { if (network == captured) lost.complete(Unit) }
        }

            services.reload()
            withTimeout(10_000) { services.state.first { it.loaded } }
            val selection = requireNotNull(services.state.value.entries.single().selection)
            services.useProvider(selection.provider)
            withTimeout(10_000) { services.state.first { it.selection?.provider == selection.provider && it.busy.isEmpty() } }
            withTimeout(10_000) { connections.state.first { it.phase == ConnectionPhase.Idle } }
            connections.refresh().join()
            val wifi = connections.state.value.networks.first { it.wifi }
            captured = manager.allNetworks.single { it.networkHandle == wifi.handle }
            connections.selectNetwork(wifi.handle).join()
            manager.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback)
            registeredCallback = callback
            connections.connect().join()
            withTimeout(20_000) { connections.state.first {
                it.phase == ConnectionPhase.Connected || it.phase == ConnectionPhase.Failed
            } }
            val connected = connections.state.value
            assertEquals("Connect failure ${connected.failure}/${connected.nativeCode}/${connected.providerCode}",
                ConnectionPhase.Connected, connected.phase)
            val generation = connections.state.value.generation
            // An independent real identity supplies a valid public contact;
            // no peer proof is claimed before the waiting relay pairs it.
            val peerContext = CallNative.contextCreate()
            var peerStore = 0L
            val peerKey = try {
                peerStore = CallNative.storeOpen(peerContext, null, SnapshotPersistence { CallNative.PERSIST_COMMITTED })
                CallNative.createIdentity(peerStore, now)
                requireNotNull(CallNative.identity(peerStore))
            } finally {
                try { if (peerStore != 0L) CallNative.storeDestroy(peerStore) }
                finally { CallNative.contextDestroy(peerContext) }
            }
            val code = CallNative.contactCodeFormat(peerKey)
            book.addContact(code, "waiting peer")
            val peer = (book.state.value as BookState.Ready).contacts.single { it.key.contentEquals(peerKey) }
            book.verify(peerKey, peer.fingerprint)
            calls.call(peerKey)
            withTimeout(15_000) { calls.view.first { it.invitation != null || it.phase == CallPhase.Ended } }
            val invitationBytes = requireNotNull(calls.view.value.invitation)
            val nativeBefore = CallNative.liveHandles()
            assertTrue(nativeBefore.sum() > coreBaseline.sum())
            when (cause) {
                "network" -> {
                    shell("svc wifi disable")
                    withTimeout(10_000) { lost.await() }
                }
                "remove" -> services.remove(provider)
                "expiry" -> shift.set(3_600_000)
            }
            withTimeout(15_000) { connections.state.first { it.phase == ConnectionPhase.Failed } }
            assertEquals(if (cause == "network") ConnectionFailure.NetworkLost else ConnectionFailure.Provider,
                connections.state.value.failure)
            assertEquals(generation, connections.state.value.generation)
            withTimeout(10_000) { calls.view.first { it.phase == CallPhase.Ended } }
            assertNull(calls.view.value.invitation)
            assertTrue(invitationBytes.all { it == 0.toByte() })
            assertNull(calls.view.value.startFailure)
            assertFalse(calls.view.value.peerAuthenticated)
            assertTrue(calls.view.value.result in setOf(-25, -27))
            if (cause != "network") assertEquals("revoked", connections.state.value.providerCode)
            val callResult = calls.view.value.result
            calls.dismiss()
            withTimeout(10_000) { calls.view.first { it.phase == CallPhase.Idle } }
            withTimeout(10_000) { while (!baseline.contentEquals(ItoNative.liveHandles())) yield() }
            // The actual process book keeps its context/store. Calls and
            // invitation handles must return to that exact warmed baseline.
            assertTrue(coreBaseline.contentEquals(CallNative.liveHandles()))
            assertTrue(File(root, "ito-provider-runtime/ito-transport/sessions").listFiles().orEmpty().isEmpty())
            assertEquals(generation, connections.state.value.generation)
            assertEquals(selection, services.state.value.selection)
            assertFalse(services.state.value.manual)
            if (cause != "network") {
                try { services.open(selection).close(); fail("Ended authority admitted another run") }
                catch (_: ProviderCodec.Refusal) { }
            }
            assertEquals(CallPhase.Idle, calls.view.value.phase)
            assertEquals(ConnectionPhase.Failed, connections.state.value.phase)
            File(app.noBackupFilesDir, "authority-$cause.json").writeText(JSONObject()
                .put("passed", true).put("cause", cause).put("on_lost_observed", cause == "network")
                .put("generation", generation).put("failure", connections.state.value.failure.name)
                .put("call_result", callResult).put("borrowers_settled", true).toString())
        } finally {
            try {
                withContext(NonCancellable) { withTimeout(15_000) { connectionOwner?.stop()?.join() } }
            } finally {
                try {
                    registeredCallback?.let { manager.unregisterNetworkCallback(it) }
                } finally {
                    try {
                        if (cause == "network") {
                            val restored = CompletableDeferred<Unit>()
                            val restoration = object : ConnectivityManager.NetworkCallback() {
                                override fun onAvailable(network: Network) {
                                    if (manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
                                        restored.complete(Unit)
                                }
                            }
                            manager.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), restoration)
                            try { shell("svc wifi enable"); withTimeout(15_000) { restored.await() } }
                            finally { manager.unregisterNetworkCallback(restoration) }
                        }
                    } finally {
                        try {
                            cleanupOwner?.close()
                        } finally {
                            try {
                                storeOwner?.close()
                            } finally {
                                files.values.forEach { it.fill(0) }
                                assertTrue(root.deleteRecursively())
                            }
                        }
                    }
                }
            }
            assertTrue(baseline.contentEquals(ItoNative.liveHandles()))
            assertTrue(coreBaseline.contentEquals(CallNative.liveHandles()))
        }
    }
}
