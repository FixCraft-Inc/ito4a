package com.fixcraft.ito.messaging

import org.junit.Assert.*
import org.junit.Test

class RingTokenTest {
    @Test fun onlyTheSameContactAndInvitationCanEndARing() {
        val peer = ByteArray(32) { 7 }
        val id = ByteArray(32) { 9 }
        val end = id + byteArrayOf(2)
        assertTrue(RingToken.matches(peer, id, peer.copyOf(), end))
        assertFalse(RingToken.matches(peer, id, ByteArray(32) { 8 }, end))
        assertFalse(RingToken.matches(peer, id, peer, ByteArray(33) { 1 }))
        assertFalse(RingToken.matches(peer, id, peer, id))
    }
    @Test fun locallyGeneratedInvitationPrefixSuppliesOnlyPublicCorrelation() {
        val prefix = "ito/invitation/2\u0000".toByteArray(Charsets.US_ASCII)
        val id = ByteArray(32) { it.toByte() }
        val invitation = (prefix + id).copyOf(262)
        assertArrayEquals(id, RingToken.outgoing(invitation))
        invitation[0] = 0
        assertThrows(IllegalArgumentException::class.java) { RingToken.outgoing(invitation) }
        assertThrows(IllegalArgumentException::class.java) { RingToken.outgoing(ByteArray(1024)) }
    }
}
