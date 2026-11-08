package com.fixcraft.ito.call

import org.junit.Assert.*
import org.junit.Test

class ContentSessionTest {
    @Test fun sequenceAndHelloPrecedeContent() {
        var accepted = false
        val sent = mutableListOf<Pair<Int, Long>>()
        var incoming = ContentFrame(ContentSession.HELLO, 0, ByteArray(8))
        val session = ContentSession({ kind, sequence, _ ->
            if (accepted) sent += kind to sequence
            accepted
        }, { incoming })
        assertFalse(session.hello())
        assertFalse(session.ready)
        accepted = true
        assertTrue(session.hello())
        session.receive(byteArrayOf()).payload.fill(0)
        assertTrue(session.ready)
        assertTrue(session.audio(0, byteArrayOf(1)))
        assertEquals(listOf(1 to 0L, 2 to 1L), sent)
        incoming = ContentFrame(ContentSession.AUDIO, 0, byteArrayOf(9))
        assertThrows(IllegalStateException::class.java) { session.receive(byteArrayOf()) }
        assertEquals(0, incoming.payload[0].toInt())
    }

    @Test fun chatCannotPrecedeHelloAndDuplicateHelloRefused() {
        var incoming = ContentFrame(ContentSession.CHAT, 0, ByteArray(17))
        val session = ContentSession({ _, _, _ -> true }, { incoming })
        assertThrows(IllegalStateException::class.java) { session.receive(byteArrayOf()) }
        incoming = ContentFrame(ContentSession.HELLO, 0, ByteArray(8))
        session.hello()
        session.receive(byteArrayOf())
        incoming = ContentFrame(ContentSession.HELLO, 1, ByteArray(8))
        assertThrows(IllegalStateException::class.java) { session.receive(byteArrayOf()) }
    }
}
