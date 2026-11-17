package com.fixcraft.ito.content

import android.content.Context
import com.fixcraft.ito.security.AppLockController
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.SecureRandom

/** Owned display record. Text never appears in diagnostics. Times are milliseconds. */
class ChatMessage(val id: ByteArray, val outgoing: Boolean, val kind: Int, val sent: Long,
    val arrived: Long, val text: ByteArray, val flags: Int, var status: Int, val timer: Int = 0,
    val operation: ByteArray = ByteArray(16)) : AutoCloseable {
    override fun close() { id.fill(0); text.fill(0); operation.fill(0) }
}

class ChatSettings(val name: String, val readReceipts: Boolean)
class ChatSummary(val text: String, val kind: Int, val time: Long, val unread: Int, val removed: Boolean)
internal class RetiredInbox(val peer: ByteArray, val fingerprint: ByteArray, val service: String,
    val secret: ByteArray) : AutoCloseable {
    override fun close() { secret.fill(0) }
}

internal class DeletedMessage(val id: ByteArray, val outgoing: Boolean, val deleted: Long) : AutoCloseable {
    override fun close() { id.fill(0) }
}

/** A durable response, removed only after its deposit succeeds. */
internal class ChatAction(val token: ByteArray, val kind: Int, val receipt: Int, val ids: ByteArray) : AutoCloseable {
    override fun close() { token.fill(0); ids.fill(0) }
}

/** At most one send is between the history write and the core-assigned ID. */
internal class PendingMessage(val kind: Int, val text: ByteArray, val timer: Int,
    val baseline: ByteArray, val operation: ByteArray = ByteArray(16)) : AutoCloseable {
    override fun close() { text.fill(0); baseline.fill(0); operation.fill(0) }
}

internal class ChatDeposit(val id: ByteArray, val item: ByteArray) : AutoCloseable {
    override fun close() { id.fill(0); item.fill(0) }
}

internal class ChatSnapshot : AutoCloseable {
    val messages = mutableListOf<ChatMessage>()
    val calls = mutableListOf<LocalCall>()
    val deleted = mutableListOf<DeletedMessage>()
    val actions = mutableListOf<ChatAction>()
    val deposits = mutableListOf<ChatDeposit>()
    var pending: PendingMessage? = null
    var timer = 0
    var removed = false
    var holdSeconds = 604800L
    override fun close() {
        messages.forEach(ChatMessage::close); calls.forEach(LocalCall::close)
        deleted.forEach(DeletedMessage::close); actions.forEach(ChatAction::close)
        deposits.forEach(ChatDeposit::close); pending?.close()
    }
    fun holds(id: ByteArray, outgoing: Boolean) = messages.any { it.outgoing == outgoing && it.id.contentEquals(id) } ||
        deleted.any { it.outgoing == outgoing && it.id.contentEquals(id) }
}

/**
 * One writer serializes history, migration and session callbacks. No record is
 * evicted to admit another. Unreadable ciphertext and established ratchets are
 * never replaced with empty state. Synchronous operations belong off Main.
 */
class ChatRepository internal constructor(private val root: File,
    private val useKey: ((ByteArray) -> ByteArray) -> ByteArray,
    private val requireAccess: () -> Unit, private val syncDirectory: (File) -> Unit,
    private val clock: () -> Long) {
    constructor(context: Context, lock: AppLockController) : this(
        File(context.applicationContext.noBackupFilesDir, "ito-chats"),
        { action -> lock.withContentKey(action) }, lock::requireAccess, ::syncContentDirectory,
        System::currentTimeMillis)

    @Synchronized fun summary(peer: ByteArray): ChatSummary {
        requireAccess()
        return current(peer).use(::summarize)
    }

    @Synchronized internal fun <T> read(peer: ByteArray, action: (ChatSnapshot) -> T): T =
        current(peer).use(action)

    @Synchronized internal fun <T> change(peer: ByteArray, action: (ChatSnapshot) -> T): T = load(peer).use { snapshot ->
        expire(snapshot, clock())
        val result = action(snapshot)
        save(peer, snapshot)
        result
    }

    @Synchronized fun messages(peer: ByteArray): List<ChatMessage> = read(peer) { snapshot ->
        snapshot.messages.map { ChatMessage(it.id.copyOf(), it.outgoing, it.kind, it.sent, it.arrived,
            it.text.copyOf(), it.flags, it.status, it.timer, it.operation.copyOf()) }
    }

    @Synchronized fun history(peer: ByteArray): List<LocalCall> = read(peer) { it.calls.map(LocalCall::copyRecord) }

    @Synchronized fun recordCall(peer: ByteArray, initiator: Boolean, result: Int, time: Long, duration: Long) {
        val call = LocalCall(ByteArray(16).also(SecureRandom()::nextBytes), peer.copyOf(), initiator, time, duration, result)
        try { ContentCodec.requireCall(call); change(peer) { it.calls.add(call.copyRecord()) } }
        finally { call.close() }
    }

    @Synchronized fun recordRing(peer: ByteArray, payload: ByteArray, initiator: Boolean) {
        change(peer) { recordRing(it, peer, payload, initiator, clock()) }
    }

    /** Old in-call messages retain their exact ID, direction and time. */
    @Synchronized fun recordLegacy(id: ByteArray, peer: ByteArray, outgoing: Boolean, text: ByteArray, time: Long) {
        val record = LocalMessage(id, peer, outgoing, time, text)
        ContentCodec.requireMessage(record)
        change(peer) { snapshot ->
            val prior = snapshot.messages.firstOrNull { it.id.contentEquals(id) && it.outgoing == outgoing }
            if (prior != null && !prior.text.contentEquals(text)) throw ContentException(ContentFailure.InvalidInput)
            if (prior == null && !snapshot.holds(id, outgoing)) snapshot.messages.add(
                ChatMessage(id.copyOf(), outgoing, 1, Math.multiplyExact(time, 1000), Math.multiplyExact(time, 1000), text.copyOf(), 0, 2))
        }
    }

    /** Restartable copy: legacy bytes are cleared only after every destination commits. */
    @Synchronized fun migrate(legacy: LocalContentRepository) {
        legacy.migrationSnapshot().use { old ->
            old.messages.forEach { recordLegacy(it.id, it.peer, it.outgoing, it.utf8, it.timeUnix) }
            old.calls.forEach { call -> change(call.peer) { snapshot ->
                if (snapshot.calls.none { it.id.contentEquals(call.id) }) snapshot.calls.add(call.copyRecord())
            } }
            if (old.messages.isNotEmpty() || old.calls.isNotEmpty()) legacy.clear()
        }
    }

    @Synchronized fun deleteMessage(peer: ByteArray, id: ByteArray, outgoing: Boolean) = change(peer) { snapshot ->
        val record = snapshot.messages.firstOrNull { it.id.contentEquals(id) && it.outgoing == outgoing }
        if (record != null) { tombstone(snapshot, record, clock()); snapshot.messages.remove(record); record.close() }
    }

    /** Keeps the ratchet and pending delivery state. This is local history deletion only. */
    @Synchronized fun deleteHistory(peer: ByteArray) = change(peer) { snapshot ->
        val now = clock()
        snapshot.messages.forEach { tombstone(snapshot, it, now); it.close() }; snapshot.messages.clear()
        snapshot.calls.forEach(LocalCall::close); snapshot.calls.clear()
    }
    /** Individual chat transactions; an error preserves all unvisited histories. */
    @Synchronized fun deleteAllHistory() {
        settingsFile().load()?.fill(0) // Validates access and the private directory.
        Files.newDirectoryStream(root.toPath()).use { paths ->
            var count = 0
            paths.forEach { path ->
                val name = path.fileName.toString()
                if (name.matches(Regex("[0-9a-f]{64}\\.history"))) {
                    if (++count > 4096 || !Files.isRegularFile(path, NOFOLLOW_LINKS)) throw ContentException(ContentFailure.Corrupt)
                    deleteHistory(name.substringBefore('.').chunked(2).map { it.toInt(16).toByte() }.toByteArray())
                }
            }
        }
    }

    @Synchronized internal fun session(peer: ByteArray): ByteArray? = file(peer, "session", 717492).load()
    @Synchronized internal fun saveSession(peer: ByteArray, snapshot: ByteArray) =
        file(peer, "session", 717492).replace(snapshot)
    @Synchronized internal fun eraseSession(peer: ByteArray) = file(peer, "session", 717492).delete()

    @Synchronized fun settings(): ChatSettings {
        val bytes = settingsFile().load() ?: return ChatSettings("Ito", true)
        try {
            if (bytes.size !in 6..133) throw ContentException(ContentFailure.Corrupt)
            val b = ByteBuffer.wrap(bytes)
            if (b.int != 1) throw ContentException(ContentFailure.Corrupt)
            val receipts = b.get().toInt()
            val name = ByteArray(b.remaining()).also(b::get)
            try {
                if (receipts !in 0..1 || name.size > 128 || !ContentCodec.validText(name)) throw ContentException(ContentFailure.Corrupt)
                return ChatSettings(name.toString(Charsets.UTF_8), receipts == 1)
            } finally { name.fill(0) }
        } finally { bytes.fill(0) }
    }
    @Synchronized fun settings(value: ChatSettings) {
        val name = value.name.toByteArray(Charsets.UTF_8)
        try {
            if (name.size !in 1..128 || !ContentCodec.validText(name)) throw ContentException(ContentFailure.InvalidInput)
            val bytes = ByteBuffer.allocate(5 + name.size).putInt(1).put(if (value.readReceipts) 1.toByte() else 0.toByte()).put(name).array()
            try { settingsFile().replace(bytes) } finally { bytes.fill(0) }
        } finally { name.fill(0) }
    }
    private fun settingsFile() = EncryptedContentFile(root, "settings.bin", ".settings.tmp", byteArrayOf(73, 84, 79, 83, 1),
        "ito/chat-storage/settings/1".toByteArray(Charsets.US_ASCII), 134, useKey, requireAccess, syncDirectory)

    @Synchronized internal fun retired(): List<RetiredInbox> {
        val bytes = retiredFile().load() ?: return emptyList()
        val result = mutableListOf<RetiredInbox>()
        try {
            val b = ByteBuffer.wrap(bytes)
            if (b.int != 1) throw ContentException(ContentFailure.Corrupt)
            val count = b.int
            if (count !in 0..512) throw ContentException(ContentFailure.Corrupt)
            repeat(count) {
                val peer = ByteArray(32).also(b::get); val fingerprint = ByteArray(32).also(b::get)
                val size = b.int
                if (size !in 1..128 || b.remaining() < size + 32) throw ContentException(ContentFailure.Corrupt)
                val serviceBytes = ByteArray(size).also(b::get)
                if (serviceBytes.any { it.toInt() !in 33..126 }) throw ContentException(ContentFailure.Corrupt)
                result.add(RetiredInbox(peer, fingerprint, serviceBytes.toString(Charsets.US_ASCII), ByteArray(32).also(b::get)))
            }
            if (b.hasRemaining()) throw ContentException(ContentFailure.Corrupt)
            return result
        } catch (failure: Exception) { result.forEach(RetiredInbox::close); throw ContentException(ContentFailure.Corrupt) }
        finally { bytes.fill(0) }
    }

    @Synchronized internal fun retire(inbox: RetiredInbox) {
        val records = retired()
        try {
            val keep = records.filterNot { it.secret.contentEquals(inbox.secret) } + inbox
            if (keep.size > 512) throw ContentException(ContentFailure.InvalidInput)
            saveRetired(keep)
        } finally { records.forEach(RetiredInbox::close) }
    }
    @Synchronized internal fun retiredClosed(secret: ByteArray) {
        val records = retired()
        try { saveRetired(records.filterNot { it.secret.contentEquals(secret) }) }
        finally { records.forEach(RetiredInbox::close) }
    }
    private fun saveRetired(records: List<RetiredInbox>) {
        val b = ByteBuffer.allocate(8 + records.sumOf { 100 + it.service.length }).putInt(1).putInt(records.size)
        try {
            records.forEach { b.put(it.peer).put(it.fingerprint).putInt(it.service.length)
                .put(it.service.toByteArray(Charsets.US_ASCII)).put(it.secret) }
            retiredFile().replace(b.array())
        } finally { b.array().fill(0) }
    }
    private fun retiredFile() = EncryptedContentFile(root, "retired.bin", ".retired.tmp", byteArrayOf(73, 84, 79, 82, 1),
        "ito/chat-storage/retired-inboxes/1".toByteArray(Charsets.US_ASCII), 116744, useKey, requireAccess, syncDirectory)

    private fun current(peer: ByteArray): ChatSnapshot {
        val snapshot = load(peer)
        try {
            val before = Triple(snapshot.messages.size, snapshot.calls.size, snapshot.deleted.size)
            expire(snapshot, clock())
            if (before != Triple(snapshot.messages.size, snapshot.calls.size, snapshot.deleted.size)) save(peer, snapshot)
            return snapshot
        } catch (error: Throwable) { snapshot.close(); throw error }
    }

    private fun load(peer: ByteArray): ChatSnapshot {
        val bytes = file(peer, "history", ChatCodec.MAX_BYTES).load() ?: return ChatSnapshot()
        return try { ChatCodec.decode(bytes) } finally { bytes.fill(0) }
    }
    private fun save(peer: ByteArray, snapshot: ChatSnapshot) {
        val bytes = ChatCodec.encode(snapshot)
        try {
            file(peer, "history", ChatCodec.MAX_BYTES).replace(bytes)
        } finally { bytes.fill(0) }
    }
    private fun summarize(state: ChatSnapshot): ChatSummary {
        val message = state.messages.lastOrNull()
        val call = state.calls.maxByOrNull { it.timeUnix }
        val time = maxOf(message?.arrived ?: 0, (call?.timeUnix ?: 0) * 1000)
        val callLast = call != null && call.timeUnix * 1000 > (message?.arrived ?: 0)
        return ChatSummary(if (callLast) "" else message?.text?.toString(Charsets.UTF_8) ?: "",
            if (callLast) 100 else message?.kind ?: 0, time,
            state.messages.count { !it.outgoing && it.status < 3 }, state.removed)
    }
    private fun file(peer: ByteArray, purpose: String, maximum: Int): EncryptedContentFile {
        if (peer.size != 32) throw ContentException(ContentFailure.InvalidInput)
        val hex = peer.joinToString("") { "%02x".format(it.toInt() and 255) }
        return EncryptedContentFile(root, "$hex.$purpose", ".$hex.$purpose.tmp",
            byteArrayOf(73, 84, 79, 72, 1), "ito/chat-storage/$purpose/1/$hex".toByteArray(Charsets.US_ASCII),
            maximum, useKey, requireAccess, syncDirectory)
    }

    internal companion object {
        internal fun recordRing(state: ChatSnapshot, peer: ByteArray, payload: ByteArray, initiator: Boolean, now: Long) {
            require(payload.size == 33 && payload[32].toInt() in 1..4)
            val id = payload.copyOfRange(0, 16)
            if (state.calls.none { it.id.contentEquals(id) }) state.calls.add(
                LocalCall(id, peer.copyOf(), initiator, now / 1000, 0, -1100 - payload[32].toInt()))
        }
        private const val TOMBSTONE_MS = 30L * 24 * 60 * 60 * 1000
        private val DURATIONS = longArrayOf(0, 86_400_000, 604_800_000, 2_592_000_000)
        fun tombstone(snapshot: ChatSnapshot, record: ChatMessage, now: Long) {
            if (snapshot.deleted.none { it.outgoing == record.outgoing && it.id.contentEquals(record.id) })
                snapshot.deleted.add(DeletedMessage(record.id.copyOf(), record.outgoing, now))
        }
        fun expire(snapshot: ChatSnapshot, now: Long) {
            if (now < 0) throw ContentException(ContentFailure.InvalidInput)
            snapshot.deleted.removeAll { if (it.deleted < now - TOMBSTONE_MS) { it.close(); true } else false }
            val duration = DURATIONS[snapshot.timer]
            if (duration == 0L) return
            snapshot.messages.removeAll { record ->
                if (record.arrived < now - duration) { tombstone(snapshot, record, now); record.close(); true } else false
            }
            snapshot.calls.removeAll { if (it.timeUnix < (now - duration) / 1000) { it.close(); true } else false }
        }
    }
}

/** Closed local format, independent of the portable message wire and session formats. */
internal object ChatCodec {
    const val MAX_BYTES = 32 * 1024 * 1024
    private const val MAX_RECORDS = 16384
    private const val MAX_ACTIONS = 512

    fun encode(state: ChatSnapshot): ByteArray {
        validate(state)
        val size = 38L + state.messages.sumOf { 69L + it.text.size } + state.calls.size * 69L +
            state.deleted.size * 25L + state.actions.sumOf { 28L + it.ids.size } +
            state.deposits.sumOf { 20L + it.item.size } +
            (state.pending?.let { 32L + it.text.size + it.baseline.size } ?: 0L)
        if (size > MAX_BYTES) throw ContentException(ContentFailure.InvalidInput)
        val b = ByteBuffer.allocate(size.toInt())
        try {
            b.putInt(1).putInt(state.timer).put(if (state.removed) 1.toByte() else 0.toByte()).putLong(state.holdSeconds)
            b.putInt(state.messages.size)
            state.messages.forEach { m -> b.put(m.id).put(if (m.outgoing) 1.toByte() else 0.toByte())
                .putInt(m.kind).putLong(m.sent).putLong(m.arrived).putInt(m.flags).putInt(m.status)
                .putInt(m.timer).putInt(m.text.size).put(m.text).put(m.operation) }
            b.putInt(state.calls.size)
            state.calls.forEach { c -> b.put(c.id).put(c.peer).put(if (c.initiator) 1.toByte() else 0.toByte())
                .putLong(c.timeUnix).putLong(c.durationMs).putInt(c.result) }
            b.putInt(state.deleted.size)
            state.deleted.forEach { d -> b.put(d.id).put(if (d.outgoing) 1.toByte() else 0.toByte()).putLong(d.deleted) }
            b.putInt(state.actions.size)
            state.actions.forEach { a -> b.put(a.token).putInt(a.kind).putInt(a.receipt).putInt(a.ids.size).put(a.ids) }
            b.putInt(state.deposits.size)
            state.deposits.forEach { d -> b.put(d.id).putInt(d.item.size).put(d.item) }
            b.put(if (state.pending == null) 0.toByte() else 1.toByte())
            state.pending?.let { p -> b.putInt(p.kind).putInt(p.timer).putInt(p.text.size).put(p.text).putInt(p.baseline.size).put(p.baseline).put(p.operation) }
            check(!b.hasRemaining())
            return b.array()
        } catch (error: Throwable) { b.array().fill(0); throw error }
    }

    fun decode(bytes: ByteArray): ChatSnapshot {
        if (bytes.size !in 38..MAX_BYTES) throw ContentException(ContentFailure.Corrupt)
        val state = ChatSnapshot()
        val allocated = mutableListOf<ByteArray>()
        try {
            val b = ByteBuffer.wrap(bytes)
            fun data(n: Int): ByteArray {
                if (n < 0 || n > b.remaining()) throw ContentException(ContentFailure.Corrupt)
                return ByteArray(n).also { allocated.add(it); b.get(it) }
            }
            fun bool(): Boolean = when (b.get().toInt()) { 0 -> false; 1 -> true; else -> throw ContentException(ContentFailure.Corrupt) }
            fun count(max: Int): Int = b.int.also { if (it !in 0..max) throw ContentException(ContentFailure.Corrupt) }
            if (b.int != 1) throw ContentException(ContentFailure.Corrupt)
            state.timer = b.int; state.removed = bool(); state.holdSeconds = b.long
            repeat(count(MAX_RECORDS)) {
                val id = data(16); val outgoing = bool(); val kind = b.int; val sent = b.long; val arrived = b.long
                val flags = b.int; val status = b.int; val timer = b.int; val text = data(count(4096))
                state.messages.add(ChatMessage(id, outgoing, kind, sent, arrived, text, flags, status, timer, data(16)))
            }
            repeat(count(MAX_RECORDS)) { state.calls.add(LocalCall(data(16), data(32), bool(), b.long, b.long, b.int)) }
            repeat(count(MAX_RECORDS)) { state.deleted.add(DeletedMessage(data(16), bool(), b.long)) }
            repeat(count(MAX_ACTIONS)) { state.actions.add(ChatAction(data(16), b.int, b.int, data(count(1024)))) }
            repeat(count(128)) { state.deposits.add(ChatDeposit(data(16), data(count(16384)))) }
            if (bool()) { val kind = b.int; val timer = b.int; val text = data(count(4096))
                state.pending = PendingMessage(kind, text, timer, data(count(2048)), data(16)) }
            if (b.hasRemaining()) throw ContentException(ContentFailure.Corrupt)
            validate(state)
            return state
        } catch (error: Throwable) {
            allocated.forEach { it.fill(0) }; state.close()
            if (error !is Exception) throw error
            throw ContentException(ContentFailure.Corrupt)
        }
    }

    private fun validate(s: ChatSnapshot) {
        fun need(ok: Boolean) { if (!ok) throw ContentException(ContentFailure.InvalidInput) }
        need(s.timer in 0..3 && s.holdSeconds in 1..2592000 && s.deposits.size <= 128 && s.messages.size <= MAX_RECORDS && s.calls.size <= MAX_RECORDS &&
            s.deleted.size <= MAX_RECORDS && s.actions.size <= MAX_ACTIONS)
        val ids = mutableSetOf<String>()
        val operations = mutableSetOf<String>()
        s.messages.forEach { m ->
            need(m.operation.size == 16 && m.id.size == 16 && m.sent >= 0 && m.arrived >= 0 && m.flags in 0..1 && m.status in 0..3 && m.timer in 0..3)
            need(when (m.kind) { 1 -> ContentCodec.validText(m.text); 8 -> m.text.size in 1..128 && ContentCodec.validText(m.text)
                9 -> m.text.isEmpty(); else -> false })
            need(ids.add("${m.outgoing}:" + m.id.joinToString(",")))
            if (m.operation.any { it != 0.toByte() }) need(m.outgoing && operations.add(m.operation.joinToString(",")))
        }
        s.calls.forEach(ContentCodec::requireCall)
        val deposits = mutableSetOf<String>()
        s.deposits.forEach { need(it.id.size == 16 && it.item.size in setOf(1024, 4096, 16384) && deposits.add(it.id.joinToString(","))) }
        s.deleted.forEach { need(it.id.size == 16 && it.deleted >= 0 && ids.add("${it.outgoing}:" + it.id.joinToString(","))) }
        val actions = mutableSetOf<String>()
        s.actions.forEach { a -> need(a.token.size == 16 && a.kind in setOf(2, 4, 100) && a.receipt in 0..2 &&
            a.ids.size in 16..1024 && a.ids.size % 16 == 0 && actions.add("${a.kind}:" + a.token.joinToString(","))) }
        s.pending?.let { p -> need(p.operation.size == 16 && p.kind in setOf(1, 8, 9) && p.timer in 0..3 && p.baseline.size <= 2048 && p.baseline.size % 16 == 0)
            need(if (p.kind == 9) p.text.isEmpty() else ContentCodec.validText(p.text) && (p.kind != 8 || p.text.size <= 128)) }
    }


}
