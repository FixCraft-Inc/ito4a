package com.fixcraft.ito.transport

import com.fixcraft.ito.provider.ItoProviderSession
import com.fixcraft.yume.consumer.ServiceController
import com.fixcraft.yume.consumer.ServiceSelection
import com.fixcraft.yume.provider.ProviderCodec
import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.fixcraft.ito.transport.nativebridge.ItoNative
import com.fixcraft.ito.transport.nativebridge.NativeFailure
import com.fixcraft.ito.transport.nativebridge.SocketBinder
import com.fixcraft.ito.transport.store.ConfigurationCatalog
import com.fixcraft.ito.transport.store.ConfigurationStore
import com.fixcraft.ito.transport.store.ConfigurationSession
import com.fixcraft.ito.transport.store.StoreException
import com.fixcraft.ito.transport.store.StoreFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.FileInputStream
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class ConnectionPhase { Loading, Idle, Starting, Connected, Stopping, Failed }
enum class ConnectionFailure { None, Storage, InvalidConfiguration, Native, NetworkUnavailable, NetworkLost, Resolve, Cancelled, DataMismatch, Input, Provider }
data class NetworkChoice(val handle: Long, val wifi: Boolean, val metered: Boolean)
data class DataReceipt(val kind: String, val bytes: Int, val sha256: String, val peer: String, val generation: Long, val network: Long,
    val nativeBytesSent: Long, val nativeBytesReceived: Long)
data class ConnectionState(
    val phase: ConnectionPhase = ConnectionPhase.Loading,
    val failure: ConnectionFailure = ConnectionFailure.None,
    val nativeCode: Int = 0,
    val providerCode: String = "",
    val catalog: ConfigurationCatalog = ConfigurationCatalog(emptyList(), null),
    val networks: List<NetworkChoice> = emptyList(),
    val selectedNetwork: Long? = null,
    val peer: String = "",
    val localIdentity: String = "",
    val generation: Long = 0,
    val boundSockets: Int = 0,
    val receipts: List<DataReceipt> = emptyList(),
)

/** One process owns the store and every native handle. Activity recreation retains no secret. */
class ConnectionController(context: Context, private val services: ServiceController<ItoProviderSession>? = null) {
    private val app = context.applicationContext
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val store = ConfigurationStore(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val operations = Mutex()
    private val mutableState = MutableStateFlow(ConnectionState())
    val state = mutableState.asStateFlow()
    private var current: Run? = null
    private var generation = 0L
    private var initialized = false
    private var initializationFailure = ConnectionFailure.Storage to 0
    private var importer: Job? = null
    private var importSignal: CancellationSignal? = null

    private class Run(val generation: Long, val network: Network, val provider: String?) {
        val monitor = Any()
        val stopped = AtomicBoolean(false)
        val boundSockets = AtomicInteger()
        val probes = Channel<Probe>(1)
        var handle = 0L
        lateinit var job: Job
        // Written and read on Main, after native borrowers have returned.
        var cleanupFailure: RunCleanupFailure? = null
    }
    private class RunCleanupFailure(val failure: ConnectionFailure, val nativeCode: Int = 0)
    private data class Probe(val packet: Boolean, val service: String, val host: String, val port: Int)

    init {
        scope.launch {
            operations.withLock {
                try {
                    withContext(Dispatchers.IO) { ItoNative.liveHandles(); store.recoverStaging() }
                    initialized = true
                    reload()
                } catch (_: StoreException) { initializationFailed(ConnectionFailure.Storage) }
                catch (_: LinkageError) { initializationFailed(ConnectionFailure.Native, -13) }
            }
        }
    }

    fun refresh() = scope.launch {
        operations.withLock {
            try {
                if (settleRun() != null) return@withLock
                if (!initialized) {
                    withContext(Dispatchers.IO) { ItoNative.liveHandles(); store.recoverStaging() }
                    initialized = true
                }
                reload()
            } catch (failure: StoreException) { storageFailed(failure) }
            catch (_: LinkageError) { initializationFailed(ConnectionFailure.Native, -13) }
        }
    }
    fun select(id: String) = command {
        stopRun()
        withContext(Dispatchers.IO) { store.select(id); services?.selectManual() }
        reload()
    }
    fun selectProvider(selection: ServiceSelection) = command {
        stopRun()
        requireNotNull(services).select(selection)
    }

    fun provisionProvider(provider: String, catalog: ProviderCodec.Catalog, service: String, server: String) = command {
        // Rotation invalidates its captured permit through the shared owner. A
        // different authority ends the current run before publication can select it.
        if (current?.provider != provider) stopRun()
        requireNotNull(services).install(provider, catalog, service, server)
    }

    fun selectNetwork(handle: Long?) = command {
        stopRun()
        mutableState.value = mutableState.value.copy(selectedNetwork = handle)
        reload()
    }
    fun remove(id: String) = command {
        stopRun()
        withContext(Dispatchers.IO) { store.remove(id) }
        reload()
    }

    /** The URI and opening code are process-local and never enter saved state or logs. */
    fun importDocument(uri: Uri, label: String, openingCode: CharArray) {
        if (importer?.isCompleted == false) {
            openingCode.fill('\u0000')
            fail(ConnectionFailure.Input)
            return
        }
        importer = scope.launch {
            try {
                if (!initialized) { fail(initializationFailure.first, initializationFailure.second); return@launch }
                require(uri.scheme == "content")
                val signal = CancellationSignal()
                importSignal = signal
                val deadline = scope.launch { delay(10_000); signal.cancel() }
                val sealed = try {
                    withTimeout(10_000) {
                        runInterruptible(Dispatchers.IO) {
                            app.contentResolver.openFileDescriptor(uri, "r", signal)?.use { descriptor ->
                                require(OsConstants.S_ISREG(Os.fstat(descriptor.fileDescriptor).st_mode))
                                FileInputStream(descriptor.fileDescriptor).use { input ->
                                    val output = java.io.ByteArrayOutputStream()
                                    val buffer = ByteArray(8192)
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        require(count > 0 && output.size() <= MAX_SEALED_BYTES - count)
                                        output.write(buffer, 0, count)
                                    }
                                    output.toByteArray().also { require(it.size in 1068..MAX_SEALED_BYTES) }
                                }
                            } ?: throw IOException()
                        }
                    }
                } finally { signal.cancel(); deadline.cancel(); if (importSignal === signal) importSignal = null }
                try {
                    operations.withLock {
                        if (!initialized) {
                            fail(initializationFailure.first, initializationFailure.second)
                            return@withLock
                        }
                        stopRun()
                        withContext(Dispatchers.IO) { importSealed(label, sealed, openingCode) }
                        reload()
                    }
                } finally { sealed.fill(0) }
            } catch (_: TimeoutCancellationException) { fail(ConnectionFailure.Input) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (refusal: ProviderCodec.Refusal) { providerFailed(refusal.code) }
            catch (failure: NativeFailure) { fail(ConnectionFailure.Native, failure.code) }
            catch (failure: StoreException) { storageFailed(failure) }
            catch (_: IOException) { fail(ConnectionFailure.Input) }
            catch (_: IllegalArgumentException) { fail(ConnectionFailure.InvalidConfiguration) }
            catch (_: IllegalStateException) { fail(ConnectionFailure.Storage) }
            catch (_: org.json.JSONException) { fail(ConnectionFailure.InvalidConfiguration) }
            catch (_: android.system.ErrnoException) { fail(ConnectionFailure.Input) }
            catch (_: android.os.OperationCanceledException) { fail(ConnectionFailure.Cancelled) }
            catch (_: SecurityException) { fail(ConnectionFailure.Input) }
            finally { openingCode.fill('\u0000') }
        }
    }

    private suspend fun importSealed(label: String, sealed: ByteArray, openingCode: CharArray) {
        require(openingCode.size in 25..64 && openingCode.all { it.code in 0x20..0x7e })
        val code = ByteArray(openingCode.size) { openingCode[it].code.toByte() }
        openingCode.fill('\u0000')
        try {
            val opened = ItoNative.openKit(sealed, code)
            try {
                require(opened.map { it.path }.distinct().size == opened.size)
                val files = opened.associate { it.path to it.bytes }
                val config = requireNotNull(files["yume.json"])
                require(ItoNative.checkConfig(config) == ItoNative.CLIENT)
                val referenced = ManualConfiguration.referencedFiles(config, files)
                require(opened.filter { it.path in referenced }.none { it.executable })
                validateImportStaging(store.stage(referenced))
                currentCoroutineContext().ensureActive()
                val coroutine = currentCoroutineContext()
                store.importConfiguration(label, referenced) { coroutine.ensureActive() }
            } finally { opened.forEach { it.bytes.fill(0) } }
        } finally { code.fill(0) }
    }

    fun connect() = command {
        stopRun()
        val provider = withContext(Dispatchers.IO) { services?.selectedForRestart() }
        val selected = if (provider == null) withContext(Dispatchers.IO) { store.list().selectedId }
            ?: throw IllegalArgumentException() else null
        val network = chosenNetwork() ?: run { fail(ConnectionFailure.NetworkUnavailable); return@command }
        val run = Run(++generation, network, provider?.provider)
        current = run
        mutableState.value = mutableState.value.copy(phase = ConnectionPhase.Starting, failure = ConnectionFailure.None,
            nativeCode = 0, providerCode = "", peer = "", generation = run.generation, boundSockets = 0, receipts = emptyList())
        run.job = scope.launch(Dispatchers.IO) { execute(run, selected, provider) }
    }

    fun stop(): Job {
        val wasStarting = mutableState.value.phase == ConnectionPhase.Starting
        importSignal?.cancel()
        importer?.cancel()
        return command {
            stopRun()
            reload()
            if (wasStarting) mutableState.value = mutableState.value.copy(failure = ConnectionFailure.Cancelled, nativeCode = ItoNative.CANCELLED)
        }
    }

    fun probe(packet: Boolean, service: String, host: String, port: Int) {
        if (service.length !in 1..128 || host.length !in 1..253 || port !in 1..65535) {
            fail(ConnectionFailure.Input)
            return
        }
        val run = current
        if (run == null || mutableState.value.phase != ConnectionPhase.Connected ||
            !run.probes.trySend(Probe(packet, service, host, port)).isSuccess) fail(ConnectionFailure.Input)
    }

    private suspend fun execute(run: Run, selected: String?, provider: ServiceSelection?) {
        var callback: ConnectivityManager.NetworkCallback? = null
        var callbackRegistered = false
        var session: AutoCloseable? = null
        var authorityWatch: Job? = null
        var permit: ServiceController<ItoProviderSession>.RunPermit? = null
        try {
                val config: ByteArray
                val directory: java.io.File
                if (provider != null) {
                    val opened = requireNotNull(services).open(provider)
                    session = opened
                    permit = opened.permit
                    config = opened.session.config
                    directory = opened.session.directory
                    authorityWatch = scope.launch(Dispatchers.IO) {
                        // Keystore/time checks stay off Main; the atomic captured-run
                        // stop flag and the Main stop owner settle lifetime separately.
                        while (!run.stopped.get()) {
                            if (!opened.permit.valid()) {
                                // A separate stop owner must survive cancellation of this monitor
                                // when the native borrower exits its finally block.
                                scope.launch {
                                    operations.withLock {
                                        if (current === run) { stopRun(); providerFailed("revoked") }
                                    }
                                }
                                break
                            }
                            delay(100)
                        }
                    }
                } else {
                    val materialized = store.materialize(requireNotNull(selected))
                    session = materialized
                    config = materialized.config
                    directory = materialized.directory
                }
                fun checkAuthority() {
                    if (permit?.valid() == false) throw ProviderCodec.Refusal("revoked")
                }
                checkAuthority()
                val description = ManualConfiguration.describe(config)
                val address = resolve(run.network, description.dialAddress ?: description.host)
                currentCoroutineContext().ensureActive()
                val adapted = ManualConfiguration.forNetwork(config, address.hostAddress ?: throw IOException())
                try {
                    val identity = ItoNative.validate(adapted, directory.absolutePath)
                    try {
                        if (provider != null && hex(identity) != provider.identity) throw ProviderCodec.Refusal("binding")
                        publish(run) { it.copy(localIdentity = hex(identity)) }
                    } finally { identity.fill(0) }
                    synchronized(run.monitor) {
                        if (run.stopped.get()) throw CancellationException()
                        run.handle = ItoNative.create(adapted, directory.absolutePath, SocketBinder { fd ->
                            if (run.stopped.get()) false else try {
                                ParcelFileDescriptor.fromFd(fd).use { run.network.bindSocket(it.fileDescriptor) }
                                run.boundSockets.incrementAndGet()
                                true
                            } catch (_: IOException) { false } catch (_: RuntimeException) { false }
                        })
                    }
                } finally { adapted.fill(0) }
                callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onLost(network: Network) {
                        if (network == run.network) scope.launch {
                            operations.withLock {
                                if (current === run) { stopRun(); fail(ConnectionFailure.NetworkLost) }
                            }
                        }
                    }
                }
                connectivity.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback!!)
                callbackRegistered = true
                checkAuthority()
                val result = ItoNative.start(run.handle, 20_000)
                if (result != ItoNative.OK) throw NativeFailure(result, "start")
                while (!run.stopped.get()) {
                    checkAuthority()
                    currentCoroutineContext().ensureActive()
                    val status = ItoNative.status(run.handle)
                    if (status.session != ItoNative.ACTIVE || status.sessions != 1L) {
                        throw NativeFailure(if (status.lastFailure != 0) status.lastFailure else -12, "session")
                    }
                    publish(run) { it.copy(phase = ConnectionPhase.Connected, peer = hex(status.peerFingerprint), boundSockets = run.boundSockets.get()) }
                    val probe = withTimeoutOrNull(200) { run.probes.receive() } ?: continue
                    checkAuthority()
                    require(probe.service in if (probe.packet) description.packets else description.streams)
                    val payload = ByteArray(if (probe.packet) 1024 else 65_536).also { SecureRandom().nextBytes(it) }
                    try {
                        val response = if (probe.packet) ItoNative.packetEcho(run.handle, probe.service, destinationType(probe.host), probe.host, probe.port, payload)
                        else ItoNative.streamEcho(run.handle, probe.service, destinationType(probe.host), probe.host, probe.port, payload)
                        try {
                            if (!response.bytes.contentEquals(payload) || !response.peerFingerprint.contentEquals(status.peerFingerprint)) {
                                throw DataMismatch()
                            }
                            val after = ItoNative.status(run.handle)
                            val sent = after.bytesSent - status.bytesSent
                            val received = after.bytesReceived - status.bytesReceived
                            if (after.session != ItoNative.ACTIVE || !after.peerFingerprint.contentEquals(status.peerFingerprint) ||
                                sent < payload.size || received < payload.size) throw DataMismatch()
                            checkAuthority()
                            val receipt = DataReceipt(if (probe.packet) "udp" else "tcp", payload.size, hex(MessageDigest.getInstance("SHA-256").digest(payload)),
                                hex(response.peerFingerprint), run.generation, run.network.networkHandle, sent, received)
                            publish(run) { it.copy(receipts = (it.receipts + receipt).takeLast(32)) }
                        } finally { response.bytes.fill(0); response.peerFingerprint.fill(0) }
                    } finally { payload.fill(0) }
                }
        } catch (_: TimeoutCancellationException) {
            publish(run) { it.copy(phase = ConnectionPhase.Failed, failure = ConnectionFailure.Resolve) }
        } catch (_: CancellationException) {
            // The stopping owner settles visible state after all borrowed handles return.
        } catch (failure: NativeFailure) {
            val revoked = permit?.valid() == false
            publish(run) {
                if (revoked) it.copy(phase = ConnectionPhase.Failed, failure = ConnectionFailure.Provider,
                    providerCode = "revoked", nativeCode = 0, boundSockets = run.boundSockets.get())
                else it.copy(phase = ConnectionPhase.Failed, failure = ConnectionFailure.Native,
                    nativeCode = failure.code, boundSockets = run.boundSockets.get())
            }
        } catch (refusal: ProviderCodec.Refusal) {
            publish(run) { it.copy(phase = ConnectionPhase.Failed, failure = ConnectionFailure.Provider, providerCode = refusal.code) }
        } catch (_: DataMismatch) {
            publish(run) { it.copy(phase = ConnectionPhase.Failed, failure = ConnectionFailure.DataMismatch) }
        } catch (failure: StoreException) {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate) {
                if (current === run && !run.stopped.get()) storageFailed(failure)
            }
        } catch (_: IllegalArgumentException) {
            publish(run) { it.copy(phase = ConnectionPhase.Failed, failure = ConnectionFailure.InvalidConfiguration) }
        } catch (_: IOException) {
            publish(run) { it.copy(phase = ConnectionPhase.Failed, failure = ConnectionFailure.Resolve) }
        } catch (_: org.json.JSONException) {
            publish(run) { it.copy(phase = ConnectionPhase.Failed, failure = ConnectionFailure.InvalidConfiguration) }
        } catch (_: SecurityException) {
            publish(run) { it.copy(phase = ConnectionPhase.Failed, failure = ConnectionFailure.NetworkUnavailable) }
        } finally {
            authorityWatch?.cancel()
            var cleanupFailed = false
            try {
                if (callbackRegistered) callback?.let { connectivity.unregisterNetworkCallback(it) }
            } catch (_: RuntimeException) { cleanupFailed = true }
            try {
                try {
                    synchronized(run.monitor) {
                        if (run.handle != 0L) {
                            try {
                                if (ItoNative.stop(run.handle) != ItoNative.OK) cleanupFailed = true
                            } finally { ItoNative.destroy(run.handle); run.handle = 0 }
                        }
                    }
                } catch (_: NativeFailure) { cleanupFailed = true }
                finally {
                    try { session?.close() }
                    catch (_: StoreException) { cleanupFailed = true }
                    catch (_: ProviderCodec.Refusal) { cleanupFailed = true }
                }
            } finally {
                if (cleanupFailed) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate) {
                    val failure = RunCleanupFailure(ConnectionFailure.Storage)
                    run.cleanupFailure = failure
                    initializationFailed(failure.failure, failure.nativeCode)
                }
            }
        }
    }

    private suspend fun stopRun() {
        val failure = settleRun()
        if (failure != null || !initialized) throw StoreException(StoreFailure.Io)
    }

    /** Returns only failures newly observed while settling this run. */
    private suspend fun settleRun(): RunCleanupFailure? = withContext(kotlinx.coroutines.NonCancellable) {
        val run = current ?: return@withContext null
        val previousCleanup = run.cleanupFailure
        mutableState.value = mutableState.value.copy(phase = ConnectionPhase.Stopping)
        run.stopped.set(true)
        var stopFailure = false
        try {
            withContext(Dispatchers.IO) {
                synchronized(run.monitor) { if (run.handle != 0L && ItoNative.stop(run.handle) != ItoNative.OK) stopFailure = true }
            }
        } catch (_: NativeFailure) { stopFailure = true }
        finally {
            run.job.cancelAndJoin()
            run.probes.close()
            current = null
        }
        val failure = if (stopFailure) RunCleanupFailure(ConnectionFailure.Native, -3)
        else run.cleanupFailure?.takeIf { it !== previousCleanup }
        if (failure != null) initializationFailed(failure.failure, failure.nativeCode)
        failure
    }

    private suspend fun reload() {
        val catalog = withContext(Dispatchers.IO) { store.list() }
        mutableState.value = mutableState.value.copy(phase = ConnectionPhase.Idle, failure = ConnectionFailure.None, nativeCode = 0, providerCode = "",
            catalog = catalog, networks = availableNetworks().map { (network, capabilities) ->
                NetworkChoice(network.networkHandle, capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                    !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            })
    }

    private fun availableNetworks() = connectivity.allNetworks.mapNotNull { network ->
        connectivity.getNetworkCapabilities(network)?.takeIf {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }?.let { network to it }
    }
    private fun chosenNetwork(): Network? {
        val selected = mutableState.value.selectedNetwork
        return if (selected == null) connectivity.activeNetwork?.takeIf { active -> availableNetworks().any { it.first == active } }
        else availableNetworks().firstOrNull { it.first.networkHandle == selected }?.first
    }

    private suspend fun resolve(network: Network, host: String): InetAddress = withTimeout(5000) {
        if (Build.VERSION.SDK_INT < 29) throw IOException("bounded DNS unavailable")
        if (android.net.InetAddresses.isNumericAddress(host)) return@withTimeout android.net.InetAddresses.parseNumericAddress(host)
        suspendCancellableCoroutine { continuation ->
            val cancellation = CancellationSignal()
            continuation.invokeOnCancellation { cancellation.cancel() }
            DnsResolver.getInstance().query(network, host, DnsResolver.FLAG_EMPTY, app.mainExecutor, cancellation,
                object : DnsResolver.Callback<List<InetAddress>> {
                    override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                        if (continuation.isActive) {
                            val address = answer.firstOrNull()
                            if (rcode == 0 && address != null) continuation.resume(address)
                            else continuation.resumeWithException(IOException("resolution refused"))
                        }
                    }
                    override fun onError(error: DnsResolver.DnsException) {
                        if (continuation.isActive) continuation.resumeWithException(IOException("resolution failed"))
                    }
                })
        }
    }

    private fun command(action: suspend () -> Unit): Job = scope.launch {
        operations.withLock {
            try {
                if (!initialized) { fail(initializationFailure.first, initializationFailure.second); return@withLock }
                action()
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (refusal: ProviderCodec.Refusal) { providerFailed(refusal.code) }
            catch (failure: NativeFailure) { fail(ConnectionFailure.Native, failure.code) }
            catch (failure: StoreException) { storageFailed(failure) }
            catch (_: IOException) { fail(ConnectionFailure.Input) }
            catch (_: IllegalArgumentException) { fail(ConnectionFailure.InvalidConfiguration) }
            catch (_: org.json.JSONException) { fail(ConnectionFailure.InvalidConfiguration) }
            catch (_: SecurityException) { fail(ConnectionFailure.Input) }
        }
    }
    private suspend fun publish(run: Run, change: (ConnectionState) -> ConnectionState) = withContext(Dispatchers.Main.immediate) {
        if (current === run && !run.stopped.get()) mutableState.value = change(mutableState.value)
    }
    private fun providerFailed(code: String) {
        fail(ConnectionFailure.Provider)
        mutableState.value = mutableState.value.copy(providerCode = code)
    }
    private fun fail(failure: ConnectionFailure, code: Int = 0) {
        mutableState.value = mutableState.value.copy(phase = ConnectionPhase.Failed,
            failure = if (initialized) failure else initializationFailure.first,
            nativeCode = if (initialized) code else initializationFailure.second)
    }
    private fun initializationFailed(failure: ConnectionFailure, code: Int = 0) {
        initialized = false
        initializationFailure = failure to code
        fail(failure, code)
    }

    private fun storageFailed(failure: StoreException) {
        // Keep a previously latched cleanup failure until explicit recovery.
        // An input rejection cannot make an unsafe storage boundary usable.
        if (!initialized) {
            fail(initializationFailure.first, initializationFailure.second)
            return
        }
        when (failure.reason) {
            StoreFailure.InvalidInput -> fail(ConnectionFailure.InvalidConfiguration)
            StoreFailure.Limit, StoreFailure.NotFound -> fail(ConnectionFailure.Input)
            StoreFailure.Corrupt, StoreFailure.KeyUnavailable, StoreFailure.Io, StoreFailure.Busy ->
                initializationFailed(ConnectionFailure.Storage)
        }
    }

    companion object {
        const val MAX_SEALED_BYTES = 1_049_648
        private fun destinationType(host: String): Int = when {
            host.matches(Regex("[0-9]+(\\.[0-9]+){3}")) -> ItoNative.DESTINATION_IPV4
            host.contains(':') -> ItoNative.DESTINATION_IPV6
            else -> ItoNative.DESTINATION_HOSTNAME
        }
        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private class DataMismatch : Exception()
}

/** A cleanup failure takes precedence: abandoned private material blocks new work. */
internal fun validateImportStaging(candidate: ConfigurationSession) {
    try {
        ItoNative.validate(candidate.config, candidate.directory.absolutePath).fill(0)
    } finally {
        // Kotlin use() suppresses close failure behind the validation error.
        // This explicit finally retains the storage error for the latch owner.
        candidate.close()
    }
}
