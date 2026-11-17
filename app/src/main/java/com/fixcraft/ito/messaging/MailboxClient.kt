package com.fixcraft.ito.messaging

import java.nio.ByteBuffer
import java.util.ArrayDeque

class MailboxFailure(val status: Int) : Exception("Mailbox status $status")

internal class MailboxItem(val kind: Int, val address: ByteArray, val id: ByteArray,
    val deposited: Long, val left: Long, val sealed: ByteArray) : AutoCloseable {
    override fun close() { address.fill(0); id.fill(0); sealed.fill(0) }
}

/** Exact MAILBOX/1 framing. One stream owner; no concurrent requests or reads. */
internal class MailboxClient(private val read: (capacity: Int, timeout: Int) -> ByteArray?,
    private val write: (ByteArray) -> Unit, private val active: () -> Boolean,
    private val nanoTime: () -> Long = System::nanoTime) : AutoCloseable {
    private val items = ArrayDeque<MailboxItem>()
    private var queuedBytes = 0
    private var pingAt = nanoTime()
    private var started = false

    fun open(secrets: List<ByteArray>) {
        require(secrets.size in 1..512 && secrets.all { it.size == 32 })
        val payload = ByteBuffer.allocate(2 + 32 * secrets.size).putShort(1)
        secrets.forEach(payload::put)
        val answer = request(1, payload.array(), 2)
        try {
            checkFrame(answer.size == secrets.size && answer.all { it.toInt() in 1..3 })
            answer.firstOrNull { it != 1.toByte() }?.let { throw MailboxFailure(it.toInt()) }
        } finally { answer.fill(0) }
    }

    fun closeInbox(secret: ByteArray) {
        require(secret.size == 32)
        val reply = request(5, byteArrayOf(0, 1) + secret, 6)
        try { checkFrame(reply.size == 1 && reply[0].toInt() in setOf(1, 3)); if (reply[0] != 1.toByte()) throw MailboxFailure(3) }
        finally { reply.fill(0) }
    }

    fun deposit(target: MailboxTarget, item: ByteArray, ring: Boolean = false): Long {
        require(target.address.size == 32 && item.size in setOf(1024, 4096, 16384))
        val request = ByteBuffer.allocate(39 + item.size).putShort(1).put(target.address).put(if (ring) 2.toByte() else 1.toByte()).putInt(if (ring) 60 else 2592000).put(item).array()
        val reply = request(7, request, 8)
        return try {
            checkFrame(reply.size == 20)
            val granted = ByteBuffer.wrap(reply, 16, 4).int.toLong() and 0xffffffffL
            checkFrame(granted in 1..2592000)
            granted
        } finally { reply.fill(0) }
    }

    fun taken(id: ByteArray) { require(id.size == 16); send(4, id.copyOf()) }

    fun poll(): MailboxItem? {
        if (items.isNotEmpty()) return pop()
        // A new stream's first frame must meet the module's five-second budget,
        // including when this installation has no inbox to OPEN yet.
        if (!started || nanoTime() - pingAt >= 240_000_000_000L) {
            val pong = request(10, byteArrayOf(), 11)
            try { checkFrame(pong.isEmpty()); pingAt = nanoTime() } finally { pong.fill(0) }
            if (items.isNotEmpty()) return pop()
        }
        val frame = frame(200) ?: return null
        acceptItem(frame)
        return pop()
    }

    private fun pop(): MailboxItem = items.removeFirst().also { queuedBytes -= it.sealed.size }

    private fun request(type: Int, payload: ByteArray, reply: Int): ByteArray {
        send(type, payload)
        val deadline = nanoTime() + 5_000_000_000L
        while (active() && nanoTime() < deadline) {
            val frame = frame(200) ?: continue
            if (frame[0] == 3.toByte()) { acceptItem(frame); continue }
            try {
                if (frame[0] == 9.toByte() && type == 7) {
                    checkFrame(frame.size == 2 && frame[1].toInt() in 2..3)
                    throw MailboxFailure(frame[1].toInt())
                }
                checkFrame(frame[0].toInt() == reply)
                return frame.copyOfRange(1, frame.size)
            } finally { frame.fill(0) }
        }
        throw MailboxFailure(5)
    }

    private fun send(type: Int, payload: ByteArray) {
        try {
            checkFrame(active() && payload.size <= 16423)
            val frame = ByteBuffer.allocate(payload.size + 5).putInt(payload.size + 1).put(type.toByte()).put(payload).array()
            try { write(frame); started = true } finally { frame.fill(0) }
        } finally { payload.fill(0) }
    }

    private fun frame(wait: Int): ByteArray? {
        val header = ByteArray(4)
        var body: ByteArray? = null
        try {
            var deadline = 0L
            fun exact(output: ByteArray, first: Boolean): Boolean {
                var offset = 0
                while (offset < output.size) {
                    if (!active()) throw MailboxFailure(5)
                    val left = if (deadline == 0L) wait else ((deadline - nanoTime()) / 1_000_000).toInt().coerceAtMost(200)
                    if (left <= 0) throw MailboxFailure(5)
                    val bytes = read(output.size - offset, left)
                    if (bytes == null) {
                        if (first && offset == 0 && deadline == 0L) return false
                        continue
                    }
                    try {
                        checkFrame(bytes.isNotEmpty() && bytes.size <= output.size - offset)
                        if (deadline == 0L) deadline = nanoTime() + 5_000_000_000L
                        bytes.copyInto(output, offset); offset += bytes.size
                    } finally { bytes.fill(0) }
                }
                return true
            }
            if (!exact(header, true)) return null
            val size = ByteBuffer.wrap(header).int
            checkFrame(size in 1..16442)
            val received = ByteArray(size)
            body = received
            exact(received, false)
            if (received[0] == 12.toByte()) {
                checkFrame(received.size == 2 && received[1].toInt() in setOf(2, 3, 5, 6))
                throw MailboxFailure(received[1].toInt())
            }
            body = null
            return received
        } finally { header.fill(0); body?.fill(0) }
    }

    private fun acceptItem(frame: ByteArray) {
        try {
            checkFrame(frame[0] == 3.toByte() && frame.size in setOf(1082, 4154, 16442))
            val data = ByteBuffer.wrap(frame)
            data.get()
            val kind = data.get().toInt()
            checkFrame(kind in 1..2)
            val address = ByteArray(32).also(data::get)
            val id = ByteArray(16).also(data::get)
            val minute = data.int.toLong() and 0xffffffffL
            val left = data.int.toLong() and 0xffffffffL
            checkFrame(left <= if (kind == 2) 60 else 2592000)
            checkFrame(items.size < 512 && queuedBytes <= 8 * 1024 * 1024 - data.remaining())
            val sealed = ByteArray(data.remaining()).also(data::get)
            items.add(MailboxItem(kind, address, id, minute * 60, left, sealed)); queuedBytes += sealed.size
        } finally { frame.fill(0) }
    }

    override fun close() { items.forEach(MailboxItem::close); items.clear(); queuedBytes = 0 }
    private fun checkFrame(ok: Boolean) { if (!ok) throw MailboxFailure(6) }
}
