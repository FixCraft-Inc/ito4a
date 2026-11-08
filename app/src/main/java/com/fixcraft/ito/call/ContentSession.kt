package com.fixcraft.ito.call

import java.nio.ByteBuffer

/** Payload has one caller, which wipes it after delivery. No content in toString. */
class ContentFrame(val kind: Int, val sequence: Long, val payload: ByteArray) {
    override fun toString() = "ContentFrame(kind=$kind, sequence=$sequence, bytes=${payload.size})"
}

/** One consented TLS DATA stream. The caller serializes all operations. */
class ContentSession(
    private val sendFrame: (Int, Long, ByteArray) -> Boolean,
    private val decode: (ByteArray) -> ContentFrame = CallNative::contentDecode,
) {
    private var nextSend = 0L
    private var nextReceive = 0L
    private var helloSent = false
    private var helloReceived = false
    val ready: Boolean get() = helloSent && helloReceived

    fun hello(): Boolean {
        if (helloSent) return true
        val body = byteArrayOf(0, 0, 0xbb.toByte(), 0x80.toByte(), 0, 1, 3, 0xc0.toByte())
        return send(HELLO, body).also { helloSent = it }
    }

    /** False drops the fresh audio packet under backpressure. Never retries stale speech. */
    fun audio(sample: Long, packet: ByteArray): Boolean {
        check(ready && sample >= 0 && packet.size in 1..1275)
        val body = ByteBuffer.allocate(8 + packet.size).putLong(sample).put(packet).array()
        return try { send(AUDIO, body) } finally { body.fill(0) }
    }

    fun chat(id: ByteArray, utf8: ByteArray): Boolean {
        check(ready && id.size == 16 && utf8.size in 1..4096)
        val body = id + utf8
        return try { send(CHAT, body) } finally { body.fill(0) }
    }

    fun acknowledge(id: ByteArray): Boolean { check(ready); return send(ACK, id) }

    fun receive(bytes: ByteArray): ContentFrame {
        val frame = decode(bytes)
        try {
            check(frame.sequence == nextReceive && nextReceive <= 0xffff_ffffL)
            check(if (!helloReceived) frame.kind == HELLO else frame.kind != HELLO)
            if (frame.kind == HELLO) helloReceived = true
            else check(ready)
            nextReceive++
            return frame
        } catch (error: Throwable) { frame.payload.fill(0); throw error }
    }

    private fun send(kind: Int, body: ByteArray): Boolean {
        check(nextSend <= 0xffff_ffffL)
        if (!sendFrame(kind, nextSend, body)) return false
        nextSend++
        return true
    }

    companion object {
        const val HELLO = 1
        const val AUDIO = 2
        const val CHAT = 3
        const val ACK = 4
    }
}
