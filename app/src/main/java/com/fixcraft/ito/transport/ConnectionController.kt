package com.fixcraft.ito.transport

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
import kotlinx.coroutines.CoroutineStart
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.selects.select
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

/**
 * One call attempt's use of the connected run: a named-service stream on its
 * endpoint, lent for the duration of [use]. The run serializes it with other
 * data operations. [use] receives the stream handle, the stream's verified
 * server fingerprint and a check that the run's transport authority still
 * holds. Stopping the run ends the stream and so the attempt.
 */
class CallLane(val service: String, val use: (stream: Long, fingerprint: ByteArray, authorityValid: () -> Boolean) -> Unit) {
    internal val done = CompletableDeferred<Unit>()
}

class CallLaneUnavailable : Exception("no connected run")
/** A run-owned mailbox worker; true asks for a bounded retry on this same endpoint. */
class MailboxLane(val service: String, val failed: () -> Unit = {}, val stopped: () -> Unit = {},
    val use: (stream: Long, fingerprint: ByteArray, authorityValid: () -> Boolean) -> Boolean)
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
    val mailboxAvailable: Boolean = false,
)

/** One process owns the store and every native handle. Activity recreation retains no secret. */
class ConnectionController(context: Context, private val services: ConnectionServices? = null,
    private val requireAccess: () -> Unit = {}, private val mailbox: MailboxLane? = null) {
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
    private var foreground = false
    private var background = false
    private var paused = false
    private var automatic: Job? = null
    private var reconnectAttempt = 0

    private class Run(val generation: Long, val network: Network, val route: CapturedConnectionNetwork, val provider: String?) {
        val monitor = Any()
        val stopped = AtomicBoolean(false)
        val networkLost = AtomicBoolean(false)
        val boundSockets = AtomicInteger()
        val probes = Channel<Probe>(1)
        // A lane the run never serves, whether dropped by the poll's timeout
        // or left buffered when the run ends, is refused rather than waiting.
        val calls = Channel<CallLane>(1, onUndeliveredElement = { it.done.completeExceptionally(CallLaneUnavailable()) })
        var handle = 0L
        lateinit var job: Job
        // Written and read on Main, after native borrowers have returned.
        var cleanupFailure: RunCleanupFailure? = null
    }
    private class RunCleanupFailure(val failure: ConnectionFailure, val nativeCode: Int = 0)
    private class RunNetworkLost : Exception()
    /** A renewed lease is ready, so the run reconnects with it. */
    private class RenewalDue(val next: SelectedService) : Exception()
    private class NetworkMonitorUnavailable : Exception()
    private data class Probe(val packet: Boolean, val service: String, val host: String, val port: Int)

    init {
        scope.launch { state.collect { value ->
            if (value.phase == ConnectionPhase.Connected) reconnectAttempt = 0
            scheduleConnection()
        } }
        scope.launch {
            operations.withLock {
                try {
                    withContext(Dispatchers.IO) { ItoNative.liveHandles(); store.recoverStaging() }
                    initialized = true
                    reload()
                } catch (_: com.fixcraft.ito.security.AppLockedException) { initializationFailed(ConnectionFailure.Storage) }
                catch (_: StoreException) { initializationFailed(ConnectionFailure.Storage) }
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
            } catch (_: com.fixcraft.ito.security.AppLockedException) { initializationFailed(ConnectionFailure.Storage) }
            catch (failure: StoreException) { storageFailed(failure) }
            catch (_: LinkageError) { initializationFailed(ConnectionFailure.Native, -13) }
        }
    }
    fun select(id: String) = command {
        stopRun()
        withContext(Dispatchers.IO) { store.select(id); services?.selectManual() }
        reload()
    }
    /**
     * Runs [action], which changes service authority. A run on another provider
     * stops first. With [move], a run on [provider] reconnects afterwards, so a
     * newly chosen server takes effect at once.
     */
    fun changeAuthority(provider: String? = null, move: Boolean = false, action: suspend () -> Unit) = command {
        if (provider == null || current?.provider != provider) stopRun()
        action()
        if (move && provider != null && current?.provider == provider) startRun()
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
        try { requireAccess() } catch (_: com.fixcraft.ito.security.AppLockedException) {
            openingCode.fill('\u0000'); fail(ConnectionFailure.Storage); return
        }
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
            catch (_: com.fixcraft.ito.security.AppLockedException) { fail(ConnectionFailure.Storage) }
            catch (refusal: ServiceRefusal) { providerFailed(refusal.code) }
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
                store.importConfiguration(label, referenced) { coroutine.ensureActive(); requireAccess() }
            } finally { opened.forEach { it.bytes.fill(0) } }
        } finally { code.fill(0) }
    }

    fun connect(): Job { paused = false; automatic?.cancel(); automatic = null; return command { startRun() } }

    /** Foreground messaging never changes the selected service, Network or VPN policy. */
    fun foreground(active: Boolean) {
        foreground = active
        scheduleConnection()
    }

    /** Set only while the explicit opt-in foreground service owns reachability. */
    fun background(active: Boolean) {
        background = active
        scheduleConnection()
    }

    private fun scheduleConnection() {
        automatic?.cancel(); automatic = null
        if ((!foreground && !background) || paused || !initialized) return
        val state = mutableState.value
        val wait = reconnectDelay(state.phase, state.failure, state.nativeCode, reconnectAttempt) ?: return
        automatic = scope.launch {
            delay(wait)
            automatic = null
            command {
                if ((!foreground && !background) || paused) return@command
                if (withContext(Dispatchers.IO) { store.list().selectedId == null && services?.selectedForRestart() == null }) return@command
                reconnectAttempt++
                startRun()
            }
        }
    }

    /** Reconnects on a renewed lease, unless a later Connect or Stop replaced [previous]. */
    private fun reconnectRenewed(previous: Run, next: SelectedService) = command {
        if (current === previous) startRun(next)
    }

    /** [prepared] is a lease the previous run already renewed for the same route. */
    private suspend fun startRun(prepared: SelectedService? = null) {
        stopRun()
        val stored = withContext(Dispatchers.IO) { services?.selectedForRestart() }
        // The chosen server's lease is acquired here, or renewed once due, so coming back after an hour just connects.
        val provider = prepared?.takeIf { stored?.provider == it.provider } ?: stored?.let { route ->
            mutableState.value = mutableState.value.copy(phase = ConnectionPhase.Starting, failure = ConnectionFailure.None)
            withContext(Dispatchers.IO) { requireNotNull(services).prepare(route, renew = false) }
        }
        val selected = if (provider == null) withContext(Dispatchers.IO) { store.list().selectedId }
            ?: throw IllegalArgumentException() else null
        val (network, route) = chosenNetwork() ?: run { fail(ConnectionFailure.NetworkUnavailable); return }
        val run = Run(++generation, network, route, provider?.provider)
        current = run
        mutableState.value = mutableState.value.copy(phase = ConnectionPhase.Starting, failure = ConnectionFailure.None,
            nativeCode = 0, providerCode = "", peer = "", generation = run.generation, boundSockets = 0, receipts = emptyList())
        // A network callback may request Stop as soon as it is registered.
        // Publish the job before its worker can install those callbacks.
        run.job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) { execute(run, selected, provider, stored) }
        run.job.start()
    }

    fun stop(pause: Boolean = true): Job {
        if (pause) paused = true
        automatic?.cancel(); automatic = null
        val wasStarting = mutableState.value.phase == ConnectionPhase.Starting
        importSignal?.cancel()
        importer?.cancel()
        return command(accessRequired = false) {
            stopRun()
            // Process-owned cleanup also releases sessions opened while offline.
            // An Activity's cancelled waiter must not cancel this work.
            withContext(Dispatchers.IO) { mailbox?.stopped?.invoke() }
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

    /** Runs lane on the connected run and returns when its attempt ended. */
    suspend fun serveCall(lane: CallLane) {
        val run = current
        if (run == null || mutableState.value.phase != ConnectionPhase.Connected || !run.calls.trySend(lane).isSuccess) {
            throw CallLaneUnavailable()
        }
        try { lane.done.await() } finally {
            // A lane the run never reached is refused, not left waiting.
            lane.done.completeExceptionally(CallLaneUnavailable())
        }
    }

    private fun serveLane(run: Run, lane: CallLane, authorityValid: () -> Boolean) {
        // Its caller stopped waiting before the run reached it.
        if (lane.done.isCompleted) return
        val stream = try { ItoNative.openCallStream(run.handle, lane.service) }
        catch (failure: NativeFailure) { lane.done.completeExceptionally(failure); return }
        try {
            // Its caller may also have stopped waiting while the stream opened.
            if (lane.done.isCompleted) return
            val fingerprint = ItoNative.callStreamFingerprint(stream)
            lane.use(stream, fingerprint, authorityValid)
            lane.done.complete(Unit)
        } catch (error: Exception) {
            lane.done.completeExceptionally(error)
        } finally { ItoNative.closeCallStream(stream) }
    }

    private suspend fun execute(run: Run, selected: String?, provider: SelectedService?, serviceRoute: ChosenRoute?) {
        var callback: ConnectivityManager.NetworkCallback? = null
        var callbackRegistered = false
        var defaultCallback: ConnectivityManager.NetworkCallback? = null
        var defaultCallbackRegistered = false
        var session: AutoCloseable? = null
        var authorityWatch: Job? = null
        var permit: ServicePermit? = null
        var mailboxJob: Job? = null
        try {
                callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onLost(network: Network) {
                        if (network == run.network) networkLost(run)
                    }
                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                        if (network == run.network && !run.route.acceptsNetwork(describeNetwork(network, capabilities))) networkLost(run)
                    }
                    override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                        if (network == run.network && blocked) networkLost(run)
                    }
                }
                defaultCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (network.networkHandle != run.route.defaultNetwork.handle) networkLost(run)
                    }
                    override fun onLost(network: Network) {
                        if (network.networkHandle == run.route.defaultNetwork.handle) networkLost(run)
                    }
                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                        if (!run.route.acceptsDefault(describeNetwork(network, capabilities))) networkLost(run)
                    }
                    override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                        if (network.networkHandle == run.route.defaultNetwork.handle && blocked) networkLost(run)
                    }
                }
                try {
                    // NetworkRequest otherwise excludes VPNs by default.
                    connectivity.registerNetworkCallback(NetworkRequest.Builder()
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback!!)
                    callbackRegistered = true
                    connectivity.registerDefaultNetworkCallback(defaultCallback!!)
                    defaultCallbackRegistered = true
                } catch (_: RuntimeException) { throw NetworkMonitorUnavailable() }
                requireCapturedNetwork(run)
                requireAccess()
                val config: ByteArray
                val directory: java.io.File
                if (provider != null) {
                    val opened = requireNotNull(services).open(provider)
                    session = opened
                    permit = opened.permit
                    config = opened.config
                    directory = opened.directory
                    authorityWatch = scope.launch(Dispatchers.IO) {
                        // Keystore/time checks stay off Main; the atomic captured-run
                        // stop flag and the Main stop owner settle lifetime separately.
                        while (!run.stopped.get()) {
                            if (!opened.permit.valid()) {
                                // A separate stop owner must survive cancellation of this monitor
                                // when the native borrower exits its finally block.
                                scope.launch {
                                    operations.withLock {
                                        if (current === run) {
                                            try { stopRun(); providerFailed("revoked") }
                                            catch (failure: StoreException) { storageFailed(failure) }
                                        }
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
                    if (permit?.valid() == false) throw ServiceRefusal("revoked")
                }
                checkAuthority()
                var renewal = provider?.let { requireNotNull(services).renewal(it) }
                val description = ManualConfiguration.describe(config)
                requireCapturedNetwork(run)
                val address = resolve(run.network, description.dialAddress ?: description.host)
                currentCoroutineContext().ensureActive()
                requireCapturedNetwork(run)
                val adapted = ManualConfiguration.forNetwork(config, address.hostAddress ?: throw IOException())
                try {
                    val identity = ItoNative.validate(adapted, directory.absolutePath)
                    try {
                        if (provider != null && hex(identity) != provider.identity) throw ServiceRefusal("binding")
                        publish(run) { it.copy(localIdentity = hex(identity)) }
                    } finally { identity.fill(0) }
                    synchronized(run.monitor) {
                        if (run.stopped.get()) throw CancellationException()
                        run.handle = ItoNative.create(adapted, directory.absolutePath, SocketBinder { fd ->
                            if (run.stopped.get()) false else try {
                                if (!capturedNetworkCurrent(run)) {
                                    networkLost(run)
                                    return@SocketBinder false
                                }
                                ParcelFileDescriptor.fromFd(fd).use { run.network.bindSocket(it.fileDescriptor) }
                                if (run.stopped.get()) return@SocketBinder false
                                if (!capturedNetworkCurrent(run)) {
                                    networkLost(run)
                                    return@SocketBinder false
                                }
                                run.boundSockets.incrementAndGet()
                                true
                            } catch (_: IOException) { false } catch (_: RuntimeException) { false }
                        })
                    }
                } finally { adapted.fill(0) }
                requireCapturedNetwork(run)
                checkAuthority()
                val result = ItoNative.start(run.handle, 20_000)
                if (result != ItoNative.OK) throw NativeFailure(result, "start")
                val messaging = mailbox?.takeIf { it.service in description.streams }
                publish(run) { it.copy(mailboxAvailable = messaging != null) }
                if (messaging != null) mailboxJob = scope.launch(Dispatchers.IO) {
                    var retry = 0
                    while (!run.stopped.get()) {
                        val again = try {
                            val stream = ItoNative.openCallStream(run.handle, messaging.service)
                            try {
                                messaging.use(stream, ItoNative.callStreamFingerprint(stream)) {
                                    !run.stopped.get() && capturedNetworkCurrent(run) && permit?.valid() != false
                                }
                            } finally { ItoNative.closeCallStream(stream) }
                        } catch (failure: NativeFailure) {
                            if (!run.stopped.get()) messaging.failed()
                            failure.code in setOf(-5, -12, -15)
                        }
                        if (!again || run.stopped.get()) break
                        delay(minOf(30_000L, 1000L shl retry.coerceAtMost(5))); retry++
                    }
                }
                while (!run.stopped.get()) {
                    requireCapturedNetwork(run)
                    checkAuthority()
                    // A call holds this loop in serveLane, so renewal waits for it to end. The next
                    // lease is prepared while this one serves, and the run moves only once it exists.
                    if (renewal?.due() == true) {
                        val next = requireNotNull(services).prepare(requireNotNull(serviceRoute), renew = true)
                        if (next.identity != requireNotNull(provider).identity) throw RenewalDue(next)
                        renewal = requireNotNull(services).renewal(next)
                    }
                    currentCoroutineContext().ensureActive()
                    val status = ItoNative.status(run.handle)
                    if (status.session != ItoNative.ACTIVE || status.sessions != 1L) {
                        throw NativeFailure(if (status.lastFailure != 0) status.lastFailure else -12, "session")
                    }
                    publish(run) { it.copy(phase = ConnectionPhase.Connected, peer = hex(status.peerFingerprint), boundSockets = run.boundSockets.get()) }
                    val request = withTimeoutOrNull(200) {
                        select<Any> {
                            run.probes.onReceive { it }
                            run.calls.onReceive { it }
                        }
                    } ?: continue
                    if (request is CallLane) {
                        try { checkAuthority() } catch (refusal: Throwable) {
                            request.done.completeExceptionally(CallLaneUnavailable())
                            throw refusal
                        }
                        serveLane(run, request) { !run.stopped.get() && permit?.valid() != false }
                        continue
                    }
                    val probe = request as Probe
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
        } catch (renewed: RenewalDue) {
            reconnectRenewed(run, renewed.next)
        } catch (_: RunNetworkLost) {
            // The network stop owner publishes after joining all native borrowers.
        } catch (_: NetworkMonitorUnavailable) {
            publish(run) { it.copy(phase = ConnectionPhase.Failed, failure = ConnectionFailure.NetworkUnavailable) }
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
        } catch (_: com.fixcraft.ito.security.AppLockedException) {
            publish(run) { it.copy(phase = ConnectionPhase.Failed, failure = ConnectionFailure.Storage) }
        } catch (refusal: ServiceRefusal) {
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
            run.stopped.set(true)
            // The mailbox must return its stream before the endpoint is freed.
            withContext(kotlinx.coroutines.NonCancellable) { mailboxJob?.cancelAndJoin() }
            authorityWatch?.cancel()
            // Refuses a lane still buffered when the run ended by itself.
            run.calls.cancel()
            var cleanupFailed = false
            try {
                if (callbackRegistered) callback?.let { connectivity.unregisterNetworkCallback(it) }
            } catch (_: RuntimeException) { cleanupFailed = true }
            try {
                if (defaultCallbackRegistered) defaultCallback?.let { connectivity.unregisterNetworkCallback(it) }
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
                    catch (_: ServiceRefusal) { cleanupFailed = true }
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
            run.calls.close()
            // A lane queued behind the stop never runs.
            run.calls.tryReceive().getOrNull()?.done?.completeExceptionally(CallLaneUnavailable())
            current = null
        }
        val failure = if (stopFailure) RunCleanupFailure(ConnectionFailure.Native, -3)
        else run.cleanupFailure?.takeIf { it !== previousCleanup }
        if (failure != null) initializationFailed(failure.failure, failure.nativeCode)
        failure
    }

    private suspend fun reload() {
        val catalog = withContext(Dispatchers.IO) { requireAccess(); store.list() }
        val networks = knownNetworks()
        val selectable = selectableConnectionNetworks(currentDefaultNetwork(), networks.map { (network, capabilities) ->
            describeNetwork(network, capabilities)
        }).map { it.handle }.toSet()
        mutableState.value = mutableState.value.copy(phase = ConnectionPhase.Idle, failure = ConnectionFailure.None, nativeCode = 0, providerCode = "", mailboxAvailable = false,
            catalog = catalog, networks = networks.filter { it.first.networkHandle in selectable }.map { (network, capabilities) ->
                NetworkChoice(network.networkHandle, capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                    !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            })
    }

    private fun knownNetworks() = connectivity.allNetworks.mapNotNull { network ->
        connectivity.getNetworkCapabilities(network)?.let { network to it }
    }

    private fun describeNetwork(network: Network, capabilities: NetworkCapabilities) = ConnectionNetwork(
        network.networkHandle, capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
    )

    private fun currentNetwork(network: Network): ConnectionNetwork? =
        connectivity.getNetworkCapabilities(network)?.let { describeNetwork(network, it) }

    private fun currentDefaultNetwork(): ConnectionNetwork? = connectivity.activeNetwork?.let(::currentNetwork)

    private fun chosenNetwork(): Pair<Network, CapturedConnectionNetwork>? {
        val active = connectivity.activeNetwork ?: return null
        val networks = knownNetworks()
        val route = captureConnectionNetwork(mutableState.value.selectedNetwork, currentNetwork(active),
            networks.map { (network, capabilities) -> describeNetwork(network, capabilities) }) ?: return null
        val network = if (route.network.handle == active.networkHandle) active
        else networks.firstOrNull { it.first.networkHandle == route.network.handle }?.first ?: return null
        return network to route
    }

    private fun capturedNetworkCurrent(run: Run): Boolean = !run.networkLost.get() &&
        run.route.acceptsDefault(currentDefaultNetwork()) && run.route.acceptsNetwork(currentNetwork(run.network))

    private fun requireCapturedNetwork(run: Run) {
        if (!capturedNetworkCurrent(run)) {
            networkLost(run)
            throw RunNetworkLost()
        }
    }

    /** Latch on the callback's thread; the Main owner stops and joins the run. */
    private fun networkLost(run: Run) {
        if (!run.networkLost.compareAndSet(false, true)) return
        run.stopped.set(true)
        scope.launch {
            operations.withLock {
                if (current === run) {
                    try { stopRun(); fail(ConnectionFailure.NetworkLost) }
                    catch (failure: StoreException) { storageFailed(failure) }
                }
            }
        }
    }

    private suspend fun resolve(network: Network, host: String): InetAddress = boundedResolution {
        if (Build.VERSION.SDK_INT < 29) throw IOException("bounded DNS unavailable")
        if (android.net.InetAddresses.isNumericAddress(host)) return@boundedResolution android.net.InetAddresses.parseNumericAddress(host)
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

    private fun command(accessRequired: Boolean = true, action: suspend () -> Unit): Job = scope.launch {
        operations.withLock {
            try {
                if (!initialized) { fail(initializationFailure.first, initializationFailure.second); return@withLock }
                if (accessRequired) requireAccess()
                action()
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: com.fixcraft.ito.security.AppLockedException) { fail(ConnectionFailure.Storage) }
            catch (refusal: ServiceRefusal) { providerFailed(refusal.code) }
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
