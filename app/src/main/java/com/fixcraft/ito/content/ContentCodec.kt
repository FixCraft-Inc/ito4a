package com.fixcraft.ito.content

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

enum class ContentFailure { InvalidInput, Corrupt, KeyUnavailable, Io, Ambiguous }
class ContentException(val reason: ContentFailure) : Exception("Local content: ${reason.name}")

/** Local records contain communication keys, never provider or account identifiers. Owned arrays may be cleared. */
class LocalMessage(val id: ByteArray, val peer: ByteArray, val outgoing: Boolean, val timeUnix: Long, val utf8: ByteArray) : AutoCloseable {
    override fun close() { id.fill(0); peer.fill(0); utf8.fill(0) }
    internal fun copyRecord() = LocalMessage(id.copyOf(), peer.copyOf(), outgoing, timeUnix, utf8.copyOf())
}

class LocalCall(val id: ByteArray, val peer: ByteArray, val initiator: Boolean, val timeUnix: Long,
                val durationMs: Long, val result: Int) : AutoCloseable {
    override fun close() { id.fill(0); peer.fill(0) }

    companion object {
        // Ring ends are stored below every core result, so the two never collide.
        private const val RING_BASE = -1100
        fun ringResult(reason: Int): Int { require(reason in 1..4); return RING_BASE - reason }

        /** The ring end (1 canceled, 2 declined, 3 busy, 4 missed) a stored [result] records, or null for a call that connected. */
        fun ringEnd(result: Int): Int? = (RING_BASE - result).takeIf { it in 1..4 }
    }
    internal fun copyRecord() = LocalCall(id.copyOf(), peer.copyOf(), initiator, timeUnix, durationMs, result)
}

internal class ContentSnapshot(val messages: MutableList<LocalMessage> = mutableListOf(),
                               val calls: MutableList<LocalCall> = mutableListOf()) : AutoCloseable {
    override fun close() { messages.forEach(LocalMessage::close); calls.forEach(LocalCall::close) }
}

/** A closed, bounded local storage format. It is independent of the authenticated chat wire format. */
internal object ContentCodec {
    const val MAX_MESSAGES = 256
    const val MAX_CALLS = 256
    const val MAX_TEXT_BYTES = 4096
    const val MAX_SNAPSHOT_BYTES = 1024 * 1024
    const val RETENTION_SECONDS = 30L * 24 * 60 * 60
    const val MAX_DURATION_MS = 24L * 60 * 60 * 1000
    private const val VERSION = 1

    fun validText(utf8: ByteArray): Boolean = utf8.size in 1..MAX_TEXT_BYTES && !utf8.contains(0.toByte()) && try {
        val decoded = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(utf8))
        !decoded.any { Character.isISOControl(it) && it != '\t' && it != '\n' }
    } catch (_: Exception) { false }

    fun encode(snapshot: ContentSnapshot): ByteArray {
        if (snapshot.messages.size > MAX_MESSAGES || snapshot.calls.size > MAX_CALLS) throw ContentException(ContentFailure.InvalidInput)
        val size = 12L + snapshot.messages.sumOf { 61L + it.utf8.size } + snapshot.calls.size * 69L
        if (size > MAX_SNAPSHOT_BYTES) throw ContentException(ContentFailure.InvalidInput)
        val output = ByteBuffer.allocate(size.toInt())
        try {
            output.putInt(VERSION)
            output.putInt(snapshot.messages.size)
            snapshot.messages.forEach { message ->
                requireMessage(message)
                output.put(message.id); output.put(message.peer)
                output.put(if (message.outgoing) 1.toByte() else 0.toByte()); output.putLong(message.timeUnix)
                output.putInt(message.utf8.size); output.put(message.utf8)
            }
            output.putInt(snapshot.calls.size)
            snapshot.calls.forEach { call ->
                requireCall(call)
                output.put(call.id); output.put(call.peer)
                output.put(if (call.initiator) 1.toByte() else 0.toByte()); output.putLong(call.timeUnix)
                output.putLong(call.durationMs); output.putInt(call.result)
            }
            return output.array()
        } catch (failure: Throwable) {
            output.array().fill(0)
            throw failure
        }
    }

    fun decode(bytes: ByteArray): ContentSnapshot {
        if (bytes.size !in 12..MAX_SNAPSHOT_BYTES) throw ContentException(ContentFailure.Corrupt)
        val snapshot = ContentSnapshot()
        var pendingId: ByteArray? = null
        var pendingPeer: ByteArray? = null
        var pendingText: ByteArray? = null
        try {
            DataInputStream(ByteArrayInputStream(bytes)).use { data ->
                if (data.readInt() != VERSION) throw ContentException(ContentFailure.Corrupt)
                val messages = data.readInt()
                if (messages !in 0..MAX_MESSAGES) throw ContentException(ContentFailure.Corrupt)
                repeat(messages) {
                    val id = ByteArray(16).also { pendingId = it }.also(data::readFully)
                    val peer = ByteArray(32).also { pendingPeer = it }.also(data::readFully)
                    val direction = data.readUnsignedByte()
                    if (direction !in 0..1) throw ContentException(ContentFailure.Corrupt)
                    val time = data.readLong()
                    val size = data.readInt()
                    if (size !in 1..MAX_TEXT_BYTES || size > data.available()) throw ContentException(ContentFailure.Corrupt)
                    val message = LocalMessage(id, peer, direction == 1, time,
                        ByteArray(size).also { pendingText = it }.also(data::readFully))
                    snapshot.messages.add(message)
                    pendingId = null; pendingPeer = null; pendingText = null
                    requireMessage(message)
                    if (snapshot.messages.dropLast(1).any { it.id.contentEquals(id) && it.peer.contentEquals(peer) && it.outgoing == message.outgoing })
                        throw ContentException(ContentFailure.Corrupt)
                }
                val calls = data.readInt()
                if (calls !in 0..MAX_CALLS) throw ContentException(ContentFailure.Corrupt)
                repeat(calls) {
                    val id = ByteArray(16).also { pendingId = it }.also(data::readFully)
                    val peer = ByteArray(32).also { pendingPeer = it }.also(data::readFully)
                    val direction = data.readUnsignedByte()
                    if (direction !in 0..1) throw ContentException(ContentFailure.Corrupt)
                    val call = LocalCall(id, peer, direction == 1, data.readLong(), data.readLong(), data.readInt())
                    snapshot.calls.add(call)
                    pendingId = null; pendingPeer = null
                    requireCall(call)
                    if (snapshot.calls.dropLast(1).any { it.id.contentEquals(id) }) throw ContentException(ContentFailure.Corrupt)
                }
                if (data.available() != 0) throw ContentException(ContentFailure.Corrupt)
            }
            return snapshot
        } catch (failure: Throwable) {
            pendingId?.fill(0); pendingPeer?.fill(0); pendingText?.fill(0)
            snapshot.close()
            if (failure !is Exception) throw failure
            throw ContentException(ContentFailure.Corrupt)
        }
    }

    fun requireMessage(message: LocalMessage) {
        if (message.id.size != 16 || message.peer.size != 32 || message.timeUnix < 0 || !validText(message.utf8))
            throw ContentException(ContentFailure.InvalidInput)
    }

    fun requireCall(call: LocalCall) {
        if (call.id.size != 16 || call.peer.size != 32 || call.timeUnix < 0 || call.durationMs !in 0..MAX_DURATION_MS)
            throw ContentException(ContentFailure.InvalidInput)
    }

    /** Count/byte bounds hold even when the wall clock moves backwards. Time retention is evaluated on access. */
    fun trim(snapshot: ContentSnapshot, nowUnix: Long): Boolean {
        if (nowUnix < 0) throw ContentException(ContentFailure.InvalidInput)
        var changed = false
        val floor = (nowUnix - RETENTION_SECONDS).coerceAtLeast(0)
        fun <T : AutoCloseable> removeOld(records: MutableList<T>, expired: (T) -> Boolean) {
            val iterator = records.iterator()
            while (iterator.hasNext()) {
                val record = iterator.next()
                if (expired(record)) { record.close(); iterator.remove(); changed = true }
            }
        }
        removeOld(snapshot.messages) { it.timeUnix < floor }
        removeOld(snapshot.calls) { it.timeUnix < floor }
        while (snapshot.messages.size > MAX_MESSAGES) { snapshot.messages.removeAt(0).close(); changed = true }
        while (snapshot.calls.size > MAX_CALLS) { snapshot.calls.removeAt(0).close(); changed = true }
        // A full set of maximum-sized messages is slightly larger than one MiB.
        while (12L + snapshot.messages.sumOf { 61L + it.utf8.size } + snapshot.calls.size * 69L > MAX_SNAPSHOT_BYTES) {
            snapshot.messages.removeAt(0).close()
            changed = true
        }
        return changed
    }
}
