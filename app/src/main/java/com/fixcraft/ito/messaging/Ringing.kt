package com.fixcraft.ito.messaging

import com.fixcraft.ito.call.CallController
import com.fixcraft.ito.call.CallNative
import com.fixcraft.ito.call.CallPhase
import com.fixcraft.ito.call.CallView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class IncomingCall(val peer: ByteArray, val id: ByteArray,
    val actionToken: String = java.util.UUID.randomUUID().toString())

/**
 * One serialized owner of authenticated invitations waiting for local consent.
 * All handle transfer, decline and expiry run on its I/O dispatcher. Callbacks
 * enqueue work, so the mailbox and call monitors never acquire each other's locks.
 */
class Ringing(private val calls: CallController, private val messenger: Messenger,
    private val requireAccess: () -> Unit, private val nanoTime: () -> Long = System::nanoTime,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onMissed: (peer: ByteArray) -> Unit = {}) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val mutableIncoming = MutableStateFlow<IncomingCall?>(null)
    val incoming = mutableIncoming.asStateFlow()
    private var pending = 0L
    private var deadline = 0L
    private var outgoing: IncomingCall? = null
    private val admitting = java.util.concurrent.atomic.AtomicBoolean(false)

    fun call(peer: ByteArray) { scope.launch {
        try {
            requireAccess()
            check(pending == 0L)
            messenger.prepareCall(peer)
            calls.call(peer)
        } catch (_: Exception) { messenger.callFailed() }
    } }

    /** The producer transfers ownership immediately, including when admission later refuses. */
    fun offer(invitation: Long, peer: ByteArray) {
        if (!admitting.compareAndSet(false, true)) {
            CallNative.invitationDestroy(invitation)
            return
        }
        scope.launch {
            var owned = invitation
            try {
                val id = CallNative.invitationId(owned)
                val remaining = (CallNative.invitationIssued(owned) + 45) * 1000 - clock()
                val notice = IncomingCall(peer.copyOf(), id)
                if (remaining <= 0) { messenger.endRing(peer, id, RingToken.MISSED, initiator = false); return@launch }
                if (pending != 0L || calls.view.value.phase !in setOf(CallPhase.Idle, CallPhase.Ended)) {
                    messenger.endRing(peer, id, RingToken.BUSY, initiator = false); return@launch
                }
                pending = owned; owned = 0
                deadline = nanoTime() + minOf(45_000L, remaining) * 1_000_000
                mutableIncoming.value = notice
                scope.launch {
                    delay(minOf(45_000L, remaining))
                    if (mutableIncoming.value === notice) finish(RingToken.MISSED)
                }
            } catch (_: Exception) { messenger.callFailed() }
            finally {
                admitting.set(false)
                if (owned != 0L) CallNative.invitationDestroy(owned)
            }
        }
    }

    fun accept(token: String) { scope.launch {
        if (pending == 0L || mutableIncoming.value?.actionToken != token) return@launch
        try {
            requireAccess()
            if (nanoTime() >= deadline) { finish(RingToken.MISSED); return@launch }
            val notice = requireNotNull(mutableIncoming.value)
            if (calls.view.value.phase == CallPhase.Ended) calls.dismiss()
            val handle = pending
            pending = 0; mutableIncoming.value = null
            calls.answerRing(handle, notice.peer)
        } catch (_: Exception) { messenger.callFailed() }
    } }

    fun decline() { scope.launch { finish(RingToken.DECLINED) } }
    fun decline(token: String) { scope.launch {
        if (mutableIncoming.value?.actionToken == token) finish(RingToken.DECLINED)
    } }
    fun stop() { scope.launch { finish(RingToken.CANCELED); calls.cancel() } }

    fun send(peer: ByteArray, invitation: ByteArray) {
        val bytes = invitation.copyOf()
        scope.launch {
            try {
                requireAccess()
                if (calls.view.value.phase in setOf(CallPhase.Idle, CallPhase.Ended, CallPhase.Ending)) return@launch
                outgoing = IncomingCall(peer.copyOf(), RingToken.outgoing(bytes))
                messenger.sendRing(peer, bytes)
            } catch (_: Exception) { calls.cancel(); messenger.callFailed() }
            finally { bytes.fill(0) }
        }
    }

    /** Called only after the receive ratchet has committed this authenticated end. */
    fun ended(peer: ByteArray, payload: ByteArray) {
        val end = payload.copyOf()
        scope.launch {
            try {
                mutableIncoming.value?.let { notice ->
                    // The caller stopped before anyone answered here.
                    if (RingToken.matches(notice.peer, notice.id, peer, end)) { clear(); onMissed(notice.peer) }
                }
                outgoing?.let { notice ->
                    if (RingToken.matches(notice.peer, notice.id, peer, end) && !calls.view.value.peerAuthenticated) {
                        outgoing = null; calls.remoteRingEnd(end[32].toInt())
                    }
                }
            } finally { end.fill(0) }
        }
    }

    fun finished(view: CallView) { scope.launch {
        val notice = outgoing ?: return@launch
        outgoing = null
        if (!view.peerAuthenticated) try {
            messenger.endRing(notice.peer, notice.id,
                if (view.result == CallNative.CANCELED) RingToken.CANCELED else RingToken.MISSED)
        } catch (_: Exception) { messenger.callFailed() }
    } }

    private fun finish(reason: Int) {
        val notice = mutableIncoming.value ?: return
        clear()
        if (reason == RingToken.MISSED) onMissed(notice.peer)
        // Lock may deny new signing/session access. Destruction always happens,
        // even when a decline cannot be sent until the caller's own timeout.
        try { messenger.endRing(notice.peer, notice.id, reason, initiator = false) }
        catch (_: Exception) { messenger.callFailed() }
    }

    private fun clear() {
        val handle = pending
        pending = 0; mutableIncoming.value = null
        if (handle != 0L) CallNative.invitationDestroy(handle)
    }
}
