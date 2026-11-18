package com.fixcraft.ito.ui

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionGateTest {
    @Test fun aRepeatedTapWhileRunningIsDroppedAndTheKeyFreesOnRelease() {
        val gate = ActionGate()
        assertTrue(gate.admit(Actions.SHOW_CODE))
        assertFalse(gate.admit(Actions.SHOW_CODE))
        assertEquals(setOf(Actions.SHOW_CODE), gate.busy.value)
        gate.release(Actions.SHOW_CODE)
        assertTrue(gate.busy.value.isEmpty())
        assertTrue(gate.admit(Actions.SHOW_CODE))
    }

    /** One slow action never blocks another, such as a second pairing or a chat. */
    @Test fun differentActionsRunIndependently() {
        val gate = ActionGate()
        val first = byteArrayOf(1, 2); val second = byteArrayOf(1, 3)
        assertTrue(gate.admit(Actions.SHOW_CODE))
        assertTrue(gate.admit(Actions.MAKE_LINK))
        assertTrue(gate.admit(Actions.pairing(first)))
        assertTrue(gate.admit(Actions.pairing(second)))
        assertFalse(gate.admit(Actions.pairing(first.copyOf())))
        assertEquals(4, gate.busy.value.size)
    }

    @Test fun concurrentTapsAdmitExactlyOne() {
        val gate = ActionGate()
        val admitted = AtomicInteger()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        repeat(64) { pool.execute { start.await(); if (gate.admit(Actions.MAKE_LINK)) admitted.incrementAndGet() } }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(1, admitted.get())
    }
}
