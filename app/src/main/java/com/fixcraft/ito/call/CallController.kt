package com.fixcraft.ito.call

import com.fixcraft.ito.transport.CallLane
import com.fixcraft.ito.transport.CallLaneUnavailable
import com.fixcraft.ito.transport.ConnectionController
import com.fixcraft.ito.transport.nativebridge.NativeFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.nio.ByteBuffer

enum class CallPhase { Idle, Connecting, Inviting, Waiting, Handshaking, Authenticating, Consent, Media, Ending, Ended }

/** Why an attempt could not start. Ended attempts report the core's status instead. */
enum class CallStartFailure { NoIdentity, NotConnected, Transport, Refused }
enum class CallLocalFailure { Content, Audio }

/**
 * What screens show about the one attempt. [invitation] is the initiator's
 * signed invitation for diagnostic profiles while waiting for the responder.
 * It holds the invitation PSK. Profile 3 sends the invitation through the
 * ratchet callback and keeps it out of screen state.
 */
data class CallView(
    val phase: CallPhase = CallPhase.Idle,
    val initiator: Boolean = false,
    val peer: ByteArray? = null,
    val invitation: ByteArray? = null,
    val result: Int = 0,
    val localEnd: Int = 0,
    val remoteEnd: Int = 0,
    val endAttributed: Boolean = false,
    val peerAuthenticated: Boolean = false,
    val sentBytes: Long = 0,
    val receivedBytes: Long = 0,
    val sentSha256: String = "",
    val receivedSha256: String = "",
    val startFailure: CallStartFailure? = null,
    val failureCode: Int = 0,
    val contentReady: Boolean = false,
    val voiceActive: Boolean = false,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    val durationMs: Long = 0,
    val historySaved: Boolean = false,
    val localFailure: CallLocalFailure? = null,
    val ringEnd: Int = 0,
) {
    /** Leaves out the invitation, which holds the PSK, and the peer key. */
    override fun toString() = "CallView(phase=$phase, initiator=$initiator, result=$result, " +
        "localEnd=$localEnd, remoteEnd=$remoteEnd, endAttributed=$endAttributed, " +
        "peerAuthenticated=$peerAuthenticated, sentBytes=$sentBytes, receivedBytes=$receivedBytes, " +
        "startFailure=$startFailure, failureCode=$failureCode)"
}

/**
 * The process owner of the one call attempt an installation may run. It
 * borrows the connected run's stream through [ConnectionController.serveCall],
 * runs the core on that lane, polls public state for screens, and destroys
 * the call before the lane closes its stream. ContentSession owns application
 * framing; Android's foreground service owns audio devices.
 */
class CallController(
    private val connections: ConnectionController,
    private val book: CallBook,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val requireAccess: () -> Unit = {},
    private val onEnded: (CallView) -> Unit = {},
    private val onMessage: (ByteArray, ByteArray, Boolean, ByteArray) -> Unit = { _, _, _, _ -> },
    private val profile: Int = 1,
    private val canStart: () -> Boolean = { true },
    private val onInvitation: (ByteArray, ByteArray) -> Unit = { _, _ -> },
    private val onFinished: (CallView) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableView = MutableStateFlow(CallView())
    val view: StateFlow<CallView> = mutableView
    // Serializes read-modify-write of the view, so concurrent publishers
    // (monitor, lane, test payload) cannot lose each other's fields.
    private val viewLock = Any()
    private val handleLock = Any()
    // Written under handleLock. Zero outside the lane's run.
    private var handle = 0L
    // Written under handleLock: the current attempt was canceled, possibly
    // while its lane was still opening the stream.
    private var canceled = false
    // Numbers attempts, so a lane of a canceled attempt cannot write the
    // view of a later one.
    private val generation = AtomicLong()
    // Written under handleLock: the attempt number a dismiss came for while
    // that attempt's lane still held its call, or -1.
    private var dismissAfterLane = -1L
    // Read by dismiss on a completion handler's thread.
    @Volatile private var attempt: Job? = null
    private val received = MessageDigest.getInstance("SHA-256")
    private var receivedBytes = 0L
    private val contentLock = Any()
    private var content: ContentSession? = null
    private var contentRequested = false
    private var lastAudioSample = -1L
    private var contentStartedAt = 0L
    private val audioReceiver = AtomicReference<((Long, ByteArray) -> Unit)?>(null)

    fun beginContent() {
        requireAccess()
        check(profile == 2 || profile == 3)
        check(view.value.phase == CallPhase.Media && view.value.peerAuthenticated)
        synchronized(contentLock) { contentRequested = true }
    }

    fun attachAudio(receiver: ((Long, ByteArray) -> Unit)?) { audioReceiver.set(receiver) }

    fun audioFailed() {
        view { it.copy(localFailure = CallLocalFailure.Audio) }
        cancel()
    }

    fun audioState(active: Boolean, muted: Boolean = false, speaker: Boolean = false) = view {
        it.copy(voiceActive = active, muted = muted, speaker = speaker)
    }

    fun sendOpus(sample: Long, packet: ByteArray): Boolean = synchronized(contentLock) {
        content?.takeIf { it.ready }?.audio(sample, packet) ?: false
    }

    /** Bounded synchronous chat. Admission never promises remote delivery or read. */
    fun sendMessage(id: ByteArray, utf8: ByteArray): Boolean {
        requireAccess()
        val peer = view.value.peer ?: return false
        val sent = synchronized(contentLock) { content?.takeIf { it.ready }?.chat(id, utf8) ?: false }
        if (sent) onMessage(id, peer, true, utf8)
        return sent
    }

    private fun sendContent(kind: Int, sequence: Long, body: ByteArray): Boolean {
        val frame = CallNative.contentEncode(kind, sequence, body)
        return try {
            synchronized(handleLock) { handle != 0L && CallNative.callSend(handle, frame) }
        } finally { frame.fill(0) }
    }

    fun call(peer: ByteArray, lifetimeSeconds: Int = if (profile == 3) 90 else DEFAULT_LIFETIME) = start(true, peer, null) { store, fingerprint ->
        CallNative.callCreateInitiator(store, peer, fingerprint, SERVICE, lifetimeSeconds, clock(), profile)
    }

    /** Takes ownership of invitation, which holds the PSK, and wipes it on every path. */
    fun answer(invitation: ByteArray) = start(false, null, invitation) { store, fingerprint ->
        val opened = try { CallNative.invitationOpen(store, invitation, fingerprint, SERVICE, clock()) }
        finally { invitation.fill(0) }
        try {
            if (CallNative.invitationProfile(opened) != profile) throw CallFailure(-6, "profile")
            CallNative.callCreateResponder(store, opened, fingerprint, SERVICE, clock())
        }
        catch (failure: CallFailure) { CallNative.invitationDestroy(opened); throw failure }
    }

    /** Takes the authenticated handle. Acceptance precedes the core's first JOIN. */
    fun answerRing(invitation: Long, peer: ByteArray) {
        if (profile != 3) {
            CallNative.invitationDestroy(invitation)
            error("Incoming ringing requires profile 3")
        }
        var owned = invitation
        start(false, peer, null, cleanup = {
            if (owned != 0L) { CallNative.invitationDestroy(owned); owned = 0 }
        }) { store, fingerprint ->
            CallNative.callCreateResponder(store, owned, fingerprint, SERVICE, clock()).also { owned = 0 }
        }
    }

    /** Only the ringing owner calls this after matching an authenticated contact and invitation ID. */
    fun remoteRingEnd(reason: Int) {
        require(reason in 1..4)
        view { it.copy(ringEnd = reason) }
        cancel()
    }

    fun decide(accept: Boolean) = withHandle { CallNative.callDecide(it, accept) }
    fun hangup() = withHandle { CallNative.callHangup(it) }

    /** Stops the attempt, including one still waiting for or opening its lane. */
    fun cancel() {
        val waiting = synchronized(handleLock) {
            canceled = true
            if (handle != 0L) {
                try { CallNative.callCancel(handle) } catch (_: CallFailure) {}
                null
            } else {
                attempt
            }
        }
        // Job cancellation runs its handlers at once, so it happens outside
        // the lock. The flag above already reaches a lane that opens meanwhile.
        waiting?.cancel()
    }

    /** Sends the profile-1 test payload: random bytes in DATA frames. */
    fun sendTestPayload(bytes: Int = TEST_PAYLOAD_BYTES) {
        requireAccess()
        if (profile != 1) return
        if (synchronized(contentLock) { contentRequested || content != null }) return
        val number = generation.get()
        scope.launch {
            val digest = MessageDigest.getInstance("SHA-256")
            var sent = 0L
            val random = SecureRandom()
            while (sent < bytes) {
                val chunk = ByteArray(minOf(CallNative.MAX_DATA_BYTES.toLong(), bytes - sent).toInt()).also(random::nextBytes)
                try {
                    var accepted = false
                    while (!accepted) {
                        accepted = synchronized(handleLock) {
                            if (handle == 0L) return@launch
                            CallNative.callSend(handle, chunk)
                        }
                        if (!accepted) delay(10)
                    }
                    digest.update(chunk)
                    sent += chunk.size
                } catch (_: CallFailure) { return@launch } finally { chunk.fill(0) }
            }
            val hash = hex(digest.digest())
            view { if (generation.get() == number) it.copy(sentSha256 = hash) else it }
        }
    }

    /**
     * Returns to Idle after an attempt ended. The view can show Ended while
     * the attempt still closes its lane, so a dismiss then waits for it. A
     * new attempt number keeps any late publisher of the old attempt from
     * rewriting the Idle view.
     */
    fun dismiss() = dismiss(generation.get())

    private fun dismiss(number: Long) {
        if (generation.get() != number) return
        // A canceled attempt is no longer active but may still run its lane.
        val running = attempt
        if (running != null && !running.isCompleted) {
            // Only for the attempt this dismiss saw, so a repeated dismiss
            // cannot clear an attempt started after the first one ran.
            running.invokeOnCompletion { dismiss(number) }
            return
        }
        val dismissedNumber = synchronized(handleLock) {
            if (generation.get() != number) return
            if (handle != 0L) {
                // A canceled attempt's lane still holds its call. The lane
                // dismisses when it lets go, unless another attempt began.
                dismissAfterLane = number
                return
            }
            generation.incrementAndGet()
        }
        view {
            // A new start may publish between releasing handleLock and
            // acquiring viewLock. Its view belongs to that newer attempt.
            if (generation.get() != dismissedNumber) return@view it
            it.invitation?.fill(0)
            CallView()
        }
    }

    private fun withHandle(action: (Long) -> Unit) = synchronized(handleLock) {
        if (handle != 0L) try { action(handle) } catch (_: CallFailure) {}
    }

    private fun start(initiator: Boolean, peer: ByteArray?, secret: ByteArray?,
                      cleanup: () -> Unit = {}, create: (store: Long, fingerprint: ByteArray) -> Long) {
        try { requireAccess() } catch (error: Exception) { secret?.fill(0); cleanup(); throw error }
        // One attempt at a time, including a canceled one whose lane still runs.
        if (attempt?.isCompleted == false || synchronized(handleLock) { handle != 0L }) {
            secret?.fill(0); cleanup()
            return
        }
        if (!canStart()) { secret?.fill(0); cleanup(); return }
        if ((book.state.value as? BookState.Ready)?.identity == null) {
            secret?.fill(0); cleanup()
            view { CallView(phase = CallPhase.Ended, initiator = initiator, peer = peer, startFailure = CallStartFailure.NoIdentity) }
            return
        }
        val number = synchronized(handleLock) {
            canceled = false
            generation.incrementAndGet()
        }
        val startedAt = System.nanoTime()
        received.reset()
        receivedBytes = 0
        synchronized(contentLock) {
            content = null; contentRequested = false; lastAudioSample = -1L; contentStartedAt = 0
        }
        view { CallView(phase = CallPhase.Connecting, initiator = initiator, peer = peer) }
        // ATOMIC: a cancel before dispatch still runs the body, so its end and
        // the invitation wipe in finally are never skipped.
        attempt = scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                connections.serveCall(CallLane(SERVICE) { stream, fingerprint, authorityValid ->
                    lane(number, stream, fingerprint, authorityValid, create)
                })
            } catch (_: CallLaneUnavailable) {
                ended(number, CallStartFailure.NotConnected, 0)
            } catch (failure: CallFailure) {
                ended(number, CallStartFailure.Refused, failure.code)
            } catch (failure: NativeFailure) {
                ended(number, CallStartFailure.Transport, failure.code)
            } catch (_: CancellationException) {
                ended(number, null, CallNative.CANCELED)
            } catch (_: Exception) {
                // The worker entry contains anything else as a typed end.
                ended(number, CallStartFailure.Transport, 0)
            } finally {
                secret?.fill(0); cleanup()
                audioReceiver.set(null)
                val duration = (System.nanoTime() - startedAt) / 1_000_000
                view { if (generation.get() == number) it.copy(durationMs = duration) else it }
                try {
                    onFinished(view.value)
                    onEnded(view.value)
                    view { if (generation.get() == number) it.copy(historySaved = true) else it }
                } catch (_: Exception) {
                    view { if (generation.get() == number) it.copy(historySaved = false) else it }
                }
            }
        }
    }

    /** One attempt on the run's lane, which closes the stream after this returns. */
    private fun lane(number: Long, stream: Long, fingerprint: ByteArray, authorityValid: () -> Boolean,
                     create: (store: Long, fingerprint: ByteArray) -> Long) {
        val created = book.withStore { store -> create(store, fingerprint) }
        synchronized(handleLock) {
            handle = created
            // Canceled while the lane opened its stream: the core then spends
            // nothing and ends at once.
            if (canceled || generation.get() != number) CallNative.callCancel(created)
        }
        try {
            val monitor = scope.launch {
                try { monitor(number, created, authorityValid) } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    // Without its monitor the attempt cannot see authority
                    // loss, so it ends. Cancel only raises the call's flag.
                    view { if (generation.get() == number) it.copy(localFailure = CallLocalFailure.Content) else it }
                    try { CallNative.callCancel(created) } catch (_: CallFailure) {}
                }
            }
            val result = try { CallNative.callRun(created, stream) } finally {
                // The monitor uses the handle, so it finishes first.
                monitor.cancel()
                while (!monitor.isCompleted) Thread.sleep(5)
            }
            drain(number, created)
            val status = CallNative.callStatus(created)
            publish(number, status, result)
            if (status.result == CallNative.STORAGE_AMBIGUOUS) book.publicationAmbiguous()
            val hash = if (receivedBytes > 0) synchronized(received) { hex(received.digest()) } else ""
            view { if (generation.get() == number) it.copy(receivedSha256 = hash) else it }
        } finally {
            // The call's TLS and secrets go before the lane closes the stream.
            val dismissNow = synchronized(handleLock) {
                handle = 0L
                CallNative.callDestroy(created)
                if (dismissAfterLane == number) {
                    dismissAfterLane = -1L
                    generation.get() == number
                } else false
            }
            if (dismissNow) dismiss(number)
        }
    }

    private fun ended(number: Long, failure: CallStartFailure?, code: Int) = view {
        // A dismissed or replaced attempt keeps its end to itself.
        if (generation.get() != number) return@view it
        it.invitation?.fill(0)
        it.copy(phase = CallPhase.Ended, invitation = null,
            startFailure = failure, failureCode = code, result = if (failure == null) code else 0)
    }

    private suspend fun monitor(number: Long, call: Long, authorityValid: () -> Boolean) {
        var authorityReported = false
        var invitationSent = false
        while (scope.isActive) {
            val status = CallNative.callStatus(call)
            if (!authorityReported && !authorityValid()) {
                CallNative.callAuthorityLost(call)
                authorityReported = true
            }
            if (profile == 3 && !invitationSent && status.has(CallNative.FLAG_INITIATOR) &&
                status.has(CallNative.FLAG_INVITATION_READY)) {
                val invitation = requireNotNull(CallNative.callInvitation(call))
                try { onInvitation(status.peerKey, invitation); invitationSent = true }
                finally { invitation.fill(0) }
            }
            drain(number, call, 32)
            publish(number, status, 0)
            if (status.state == CallNative.STATE_MEDIA_AUTHORIZED) {
                val ready = synchronized(contentLock) {
                    if (contentRequested && content == null) {
                        content = ContentSession(::sendContent)
                        contentStartedAt = System.nanoTime()
                    }
                    content?.let {
                        it.hello()
                        check(it.ready || System.nanoTime() - contentStartedAt <= 5_000_000_000L)
                        it.ready
                    } ?: false
                }
                view { if (generation.get() == number) it.copy(contentReady = ready) else it }
            }
            if (status.state == CallNative.STATE_CLOSED) return
            delay(50)
        }
    }

    private fun drain(number: Long, call: Long, maximum: Int = Int.MAX_VALUE) {
        repeat(maximum) {
            val payload = CallNative.callReceive(call) ?: return
            try {
                if (profile == 2 || profile == 3) {
                    val frame = synchronized(contentLock) {
                        if (content == null) {
                            content = ContentSession(::sendContent)
                            contentRequested = true
                            contentStartedAt = System.nanoTime()
                        }
                        requireNotNull(content).let { it.hello(); it.receive(payload) }
                    }
                    try { deliverContent(frame) } finally { frame.payload.fill(0) }
                    return@repeat
                }
                // A canceled attempt's data never reaches the next one's digest.
                if (generation.get() == number) synchronized(received) {
                    received.update(payload)
                    receivedBytes += payload.size
                }
            } finally { payload.fill(0) }
        }
    }

    private fun deliverContent(frame: ContentFrame) {
        when (frame.kind) {
            ContentSession.HELLO -> Unit
            ContentSession.AUDIO -> {
                val sample = ByteBuffer.wrap(frame.payload, 0, 8).long
                check(sample >= 0 && sample > lastAudioSample && sample % 960 == 0L)
                lastAudioSample = sample
                val packet = frame.payload.copyOfRange(8, frame.payload.size)
                val receiver = audioReceiver.get()
                if (receiver == null) packet.fill(0) else receiver(sample, packet)
            }
            ContentSession.CHAT -> {
                try { requireAccess() } catch (_: com.fixcraft.ito.security.AppLockedException) { return }
                val peer = view.value.peer ?: error("Missing authenticated peer")
                val id = frame.payload.copyOfRange(0, 16)
                val text = frame.payload.copyOfRange(16, frame.payload.size)
                try {
                    onMessage(id, peer, false, text)
                    synchronized(contentLock) { requireNotNull(content).acknowledge(id) }
                } finally { id.fill(0); text.fill(0) }
            }
            ContentSession.ACK -> Unit
            else -> error("Unsupported content")
        }
    }

    private fun publish(number: Long, status: CallStatus, result: Int) = view { current ->
        if (generation.get() != number) return@view current
        var invitation = current.invitation
        if (profile != 3 && current.initiator && invitation == null && status.has(CallNative.FLAG_INVITATION_READY)) {
            val call = handleOrZero()
            if (call != 0L) invitation = CallNative.callInvitation(call)
        }
        if (status.state >= CallNative.STATE_HANDSHAKING && invitation != null) {
            invitation.fill(0)
            invitation = null
        }
        val closed = status.state == CallNative.STATE_CLOSED
        current.copy(
            phase = phaseOf(status, current.initiator),
            peer = status.peerKey,
            invitation = invitation,
            result = if (closed && result != 0) result else status.result,
            localEnd = status.localEnd,
            remoteEnd = status.remoteEnd,
            endAttributed = status.has(CallNative.FLAG_REMOTE_END_ATTRIBUTED),
            peerAuthenticated = status.has(CallNative.FLAG_PEER_AUTHENTICATED),
            sentBytes = status.dataSent,
            receivedBytes = status.dataReceived,
        )
    }

    private fun handleOrZero(): Long = synchronized(handleLock) { handle }

    // Lock order: viewLock, then handleLock (publish reads the handle).
    private inline fun view(change: (CallView) -> CallView) =
        synchronized(viewLock) { mutableView.value = change(mutableView.value) }

    companion object {
        /** The relay's named service on the selected transport. */
        const val SERVICE = "ito-call"
        const val DEFAULT_LIFETIME = 300
        const val TEST_PAYLOAD_BYTES = 64 * 1024

        fun phaseOf(status: CallStatus, initiator: Boolean): CallPhase = when (status.state) {
            CallNative.STATE_IDLE, CallNative.STATE_PREPARING -> CallPhase.Connecting
            CallNative.STATE_JOINED -> if (initiator) CallPhase.Inviting else CallPhase.Waiting
            CallNative.STATE_HANDSHAKING -> CallPhase.Handshaking
            CallNative.STATE_AUTHENTICATING -> CallPhase.Authenticating
            CallNative.STATE_AWAITING_ACCEPTANCE ->
                if (status.has(CallNative.FLAG_CONSENT_PENDING)) CallPhase.Consent else CallPhase.Authenticating
            CallNative.STATE_MEDIA_AUTHORIZED -> CallPhase.Media
            CallNative.STATE_CLOSING -> CallPhase.Ending
            else -> CallPhase.Ended
        }

        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
