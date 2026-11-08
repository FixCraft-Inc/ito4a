package com.fixcraft.ito.call

import org.junit.Assert.*
import org.junit.Test

class PlayoutClockTest {
    @Test fun stalledSpeechDoesNotReceiveAFreshDeadline() {
        val clock = PlayoutClock()
        assertEquals(1000L, clock.deadlineBase(0, 1000))
        assertEquals(1020L, clock.deadlineBase(960, 1025))
        assertNull(clock.deadlineBase(1920, 1500))
        assertNull(clock.deadlineBase(2880, 1500))
        assertEquals(1500L, clock.deadlineBase(24000, 1500))
    }
    @Test fun firstPacketHasNoAbsoluteDelayClaimAndDuplicatesFail() {
        val clock = PlayoutClock()
        assertEquals(9000L, clock.deadlineBase(48000, 9000))
        assertThrows(IllegalArgumentException::class.java) { clock.deadlineBase(48000, 9001) }
        assertThrows(IllegalArgumentException::class.java) { clock.deadlineBase(48001, 9001) }
    }
}
