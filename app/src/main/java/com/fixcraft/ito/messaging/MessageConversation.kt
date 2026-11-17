package com.fixcraft.ito.messaging

import com.fixcraft.ito.call.CallFailure
import com.fixcraft.ito.call.CallNative
import com.fixcraft.ito.call.SnapshotPersistence
import com.fixcraft.ito.content.ChatAction
import com.fixcraft.ito.content.ChatDeposit
import com.fixcraft.ito.content.ChatMessage
import com.fixcraft.ito.content.ChatRepository
import com.fixcraft.ito.content.ChatSnapshot
import com.fixcraft.ito.content.ContentException
import com.fixcraft.ito.content.ContentFailure
import com.fixcraft.ito.content.PendingMessage
import java.security.SecureRandom

/** One serialized contact session. Close it before its borrowed identity store. */
internal class MessageConversation(private val store: Long, private val peer: ByteArray,
    private val history: ChatRepository, private val clock: () -> Long = System::currentTimeMillis,
    private val onMessage: (ByteArray, ByteArray) -> Unit = { _, _ -> },
    private val onRingEnd: (ByteArray, ByteArray) -> Unit = { _, _ -> }) : AutoCloseable {
    private var session = 0L
    init {
        val snapshot = history.session(peer)
        try {
            val persistence = SnapshotPersistence { bytes ->
                try { history.saveSession(peer, bytes); CallNative.PERSIST_COMMITTED }
                catch (failure: ContentException) {
                    if (failure.reason == ContentFailure.Ambiguous) CallNative.PERSIST_AMBIGUOUS else CallNative.PERSIST_NOT_COMMITTED
                }
            }
            session = try { MessageNative.openSession(store, peer, snapshot, persistence, clock()) }
            catch (failure: CallFailure) {
                // Only a completed new pairing can supply a new seed. The core
                // refuses an established session's missing seed as corrupt.
                if (failure.code != MessageNative.WRONG_STATE || snapshot == null) throw failure
                MessageNative.openSession(store, peer, null, persistence, clock())
            }
        } finally { snapshot?.fill(0) }
    }

    fun send(kind: Int, text: ByteArray = byteArrayOf(), timer: Int = 0,
        operation: ByteArray = ByteArray(16).also(SecureRandom()::nextBytes)) {
        require(operation.size == 16 && operation.any { it != 0.toByte() })
        recover()
        // A UI retry keeps its operation ID even if the last history rename
        // committed but reported ambiguous. Equal text alone is not a retry.
        val completed = history.read(peer) { state ->
            state.messages.firstOrNull { it.outgoing && it.operation.contentEquals(operation) }?.let {
                if (it.kind != kind || it.timer != timer || !it.text.contentEquals(text))
                    throw ContentException(ContentFailure.InvalidInput)
                true
            } ?: false
        }
        if (completed) return
        val baseline = MessageNative.outbox(session).flatMap { it.id.asIterable() }.toByteArray()
        try { history.change(peer) { check(it.pending == null); it.pending = PendingMessage(kind, text.copyOf(), timer, baseline.copyOf(), operation.copyOf()) } }
        finally { baseline.fill(0) }
        recover()
    }

    /** Resume the single publication whose core-assigned ID may have been lost. */
    private fun recover() {
        history.read(peer) { snapshot ->
            val pending = snapshot.pending ?: return@read
            val before = ids(pending.baseline)
            val fresh = MessageNative.outbox(session).filter { entry -> before.none { it.contentEquals(entry.id) } }
            if (fresh.size > 1) throw ContentException(ContentFailure.Corrupt)
            val sealed = if (fresh.isEmpty()) MessageNative.send(session, pending.kind, pending.text, 0,
                byteArrayOf(), pending.timer, clock()) else MessageNative.resend(session, fresh.single().id, clock())
            sealed.use { item ->
                val id = requireNotNull(item.id)
                val entry = MessageNative.outbox(session).single { it.id.contentEquals(id) }
                history.change(peer) { state ->
                    check(state.pending != null)
                    if (!state.holds(id, true)) state.messages.add(ChatMessage(id.copyOf(), true, pending.kind,
                        entry.sentMs, clock(), pending.text.copyOf(), if (fresh.isEmpty()) 0 else 1, 0, pending.timer, pending.operation.copyOf()))
                    if (state.deposits.none { it.id.contentEquals(id) }) state.deposits.add(ChatDeposit(id.copyOf(), item.bytes.copyOf()))
                    if (pending.kind == MessageNative.TIMER) state.timer = pending.timer
                    state.pending?.close(); state.pending = null
                }
            }
        }
    }

    fun receiveRing(item: ByteArray, fingerprint: ByteArray, service: String): Long =
        MessageNative.receiveRing(session, item, fingerprint, service, clock())

    /** Rings are ephemeral. Ring ends can be retried as sealed, nonsecret deposits. */
    fun signal(kind: Int, payload: ByteArray): SealedItem =
        MessageNative.send(session, kind, payload, 0, byteArrayOf(), 0, clock())

    fun receive(item: ByteArray): Int {
        var notify: ByteArray? = null
        var end: ByteArray? = null
        try {
            val result = MessageNative.receive(session, item, clock(), MessageSink { message ->
                try {
                    history.change(peer) { state ->
                        val fresh = !state.holds(message.id, false)
                        receive(state, message)
                        if (fresh && message.kind == MessageNative.TEXT) notify = message.text.copyOf()
                        if (message.kind == MessageNative.RING_END) end = message.text.copyOf()
                    }
                    CallNative.PERSIST_COMMITTED
                } catch (failure: ContentException) {
                    if (failure.reason == ContentFailure.Ambiguous) CallNative.PERSIST_AMBIGUOUS else CallNative.PERSIST_NOT_COMMITTED
                }
            })
            if (result == CallNative.OK) {
                notify?.let { onMessage(peer, it) }
                end?.let { onRingEnd(peer, it) }
            }
            return result
        } finally { notify?.fill(0); end?.fill(0) }
    }

    private fun receive(state: ChatSnapshot, message: MessageValue) {
        when (message.kind) {
            MessageNative.TEXT, MessageNative.PROFILE, MessageNative.TIMER -> {
                val prior = state.messages.firstOrNull { !it.outgoing && it.id.contentEquals(message.id) }
                if (prior != null && (prior.kind != message.kind || prior.sent != message.sentMs ||
                    !prior.text.contentEquals(message.text) || prior.timer != message.timer)) throw ContentException(ContentFailure.Corrupt)
                if (!state.holds(message.id, false)) {
                    state.messages.add(ChatMessage(message.id.copyOf(), false, message.kind, message.sentMs,
                        clock(), message.text.copyOf(), message.flags, 2, message.timer))
                    if (message.kind == MessageNative.TIMER) state.timer = message.timer
                }
                action(state, message.id, MessageNative.RECEIPT, MessageNative.DELIVERED, message.id)
            }
            MessageNative.RING_END -> ChatRepository.recordRing(state, peer, message.text, false, clock())
            MessageNative.RECEIPT -> ids(message.ids).forEach { id ->
                state.messages.filter { it.outgoing && it.id.contentEquals(id) }.forEach {
                    it.status = maxOf(it.status, if (message.receipt == MessageNative.READ) 3 else 2)
                }
                state.deposits.removeAll { if (it.id.contentEquals(id)) { it.close(); true } else false }
            }
            MessageNative.CHECK -> {
                val groups = ids(message.ids).partition { state.holds(it, false) }
                if (groups.first.isNotEmpty()) action(state, message.id, MessageNative.RECEIPT,
                    MessageNative.DELIVERED, groups.first.flatMap { it.asIterable() }.toByteArray())
                if (groups.second.isNotEmpty()) action(state, message.id, MessageNative.MISSING, 0,
                    groups.second.flatMap { it.asIterable() }.toByteArray())
            }
            MessageNative.MISSING -> action(state, message.id, RESEND, 0, message.ids)
            else -> throw ContentException(ContentFailure.InvalidInput)
        }
    }

    fun markRead(enabled: Boolean) {
        history.change(peer) { state ->
            val unread = state.messages.filter { !it.outgoing && it.status < 3 }
            if (enabled) unread.chunked(64).forEach { batch ->
                action(state, ByteArray(16).also(SecureRandom()::nextBytes), MessageNative.RECEIPT,
                    MessageNative.READ, batch.flatMap { it.id.asIterable() }.toByteArray())
            }
            unread.forEach { it.status = 3 }
        }
    }

    fun flush(client: MailboxClient, fingerprint: ByteArray, service: String): Boolean {
        val target = requireNotNull(MessageNative.contact(store, peer)).send
        if (!target.on(fingerprint, service)) throw MailboxFailure(2)
        var changed = history.read(peer) { it.pending != null || it.deposits.isNotEmpty() || it.actions.isNotEmpty() }
        recover()
        // Read snapshots own each payload until its synchronous deposit returns.
        history.read(peer) { snapshot -> snapshot.deposits.forEach { d ->
            val hold = client.deposit(target, d.item)
            history.change(peer) { state ->
                state.holdSeconds = minOf(state.holdSeconds, hold)
                state.messages.firstOrNull { it.outgoing && it.id.contentEquals(d.id) }?.let { it.status = maxOf(1, it.status) }
                state.deposits.removeAll { if (it.id.contentEquals(d.id)) { it.close(); true } else false }
            }
        } }
        history.read(peer) { snapshot -> snapshot.actions.forEach { a ->
            if (a.kind == RESEND) ids(a.ids).forEach { id ->
                val item = try { MessageNative.resend(session, id, clock()) }
                catch (failure: CallFailure) { if (failure.code != CallNative.NOT_FOUND) throw failure; null }
                item?.use { client.deposit(it.target, it.bytes) }
            } else if (a.kind != MessageNative.RECEIPT || a.receipt != MessageNative.READ || history.settings().readReceipts) {
                MessageNative.send(session, a.kind, byteArrayOf(), a.receipt, a.ids, 0, clock()).use {
                    client.deposit(it.target, it.bytes)
                }
            }
            history.change(peer) { state -> state.actions.removeAll {
                if (it.kind == a.kind && it.token.contentEquals(a.token)) { it.close(); true } else false
            } }
        } }
        val hold = history.read(peer) { it.holdSeconds }
        // One bounded check per turn; the next turn handles another batch.
        MessageNative.check(session, hold, clock())?.use { client.deposit(it.target, it.bytes); changed = true }
        return changed
    }

    override fun close() { if (session != 0L) { MessageNative.closeSession(session); session = 0 } }

    private companion object {
        const val RESEND = 100
        fun ids(bytes: ByteArray): List<ByteArray> = bytes.asList().chunked(16).map { it.toByteArray() }
        fun action(state: ChatSnapshot, token: ByteArray, kind: Int, receipt: Int, ids: ByteArray) {
            if (state.actions.none { it.kind == kind && it.token.contentEquals(token) })
                state.actions.add(ChatAction(token.copyOf(), kind, receipt, ids.copyOf()))
        }
    }
}
