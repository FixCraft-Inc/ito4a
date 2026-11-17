package com.fixcraft.ito.messaging

import java.nio.ByteBuffer
import java.util.ArrayDeque
import org.junit.Assert.*
import org.junit.Test

class MailboxClientTest {
    private fun frame(type: Int, body: ByteArray) = ByteBuffer.allocate(body.size + 5).putInt(body.size + 1).put(type.toByte()).put(body).array()
    private fun client(bytes: ByteArray, written: MutableList<ByteArray>, fragment: Int = 1): MailboxClient {
        val input = ArrayDeque<Byte>().apply { bytes.forEach(::add) }
        return MailboxClient({ size, _ -> if (input.isEmpty()) null else ByteArray(minOf(size, input.size, fragment)) { input.removeFirst() } },
            { written.add(it.copyOf()) }, { true }, { System.nanoTime() })
    }
    @Test fun emptyInstallationSendsAFirstFrameBeforeWaitingForAnInbox() {
        val writes = mutableListOf<ByteArray>()
        client(frame(11, byteArrayOf()), writes).use { assertNull(it.poll()) }
        assertArrayEquals(frame(10, byteArrayOf()), writes.single())
    }
    @Test fun serverRefusalPreservesItsTypedReason() {
        for (reason in listOf(2, 3, 5, 6)) client(frame(12, byteArrayOf(reason.toByte())), mutableListOf()).use { client ->
            try { client.poll(); fail("Server refusal was accepted") }
            catch (failure: MailboxFailure) { assertEquals(reason, failure.status) }
        }
    }
    @Test fun fragmentedFramesAndInterleavedPushPreserveReplyOrder() {
        val sealed = ByteArray(1024) { 8 }
        val pushed = ByteBuffer.allocate(57 + sealed.size).put(1).put(ByteArray(32) { 2 }).put(ByteArray(16) { 3 })
            .putInt(1234).putInt(60).put(sealed).array()
        val input = frame(2, byteArrayOf(1)) + frame(3, pushed) + frame(8, ByteArray(16) { 4 } + byteArrayOf(0, 0, 0, 60))
        val writes = mutableListOf<ByteArray>()
        client(input, writes).use { c ->
            c.open(listOf(ByteArray(32) { 1 }))
            assertEquals(60L, c.deposit(MailboxTarget(ByteArray(32), "ito-mailbox", ByteArray(32)), sealed))
            c.poll()!!.use { item -> assertArrayEquals(sealed, item.sealed); assertEquals(74040L, item.deposited); c.taken(item.id) }
        }
        assertEquals(listOf(1, 7, 4), writes.map { it[4].toInt() })
    }
    @Test fun ringsUseEphemeralClassAndSixtySecondHold() {
        val writes = mutableListOf<ByteArray>()
        client(frame(8, ByteArray(16) { 4 } + byteArrayOf(0, 0, 0, 60)), writes).use {
            it.deposit(MailboxTarget(ByteArray(32), "ito-mailbox", ByteArray(32)), ByteArray(1024), ring = true)
        }
        val sent = ByteBuffer.wrap(writes.single())
        sent.position(39)
        assertEquals(2, sent.get().toInt())
        assertEquals(60, sent.int)
    }

    @Test fun invalidBodyLengthIsRejectedBeforeReadingOrAllocatingBody() {
        var reads = 0
        MailboxClient({ size, _ -> reads++; assertEquals(4, size); byteArrayOf(127, -1, -1, -1) }, {}, { true }).use { c ->
            try { c.poll(); fail() } catch (failure: MailboxFailure) { assertEquals(6, failure.status) }
        }
        assertEquals(1, reads)
    }
    @Test fun rejectedDepositAndUnknownServerReplyAreVisible() {
        client(frame(9, byteArrayOf(2)), mutableListOf()).use { c ->
            try { c.deposit(MailboxTarget(ByteArray(32), "ito-mailbox", ByteArray(32)), ByteArray(1024)); fail() }
            catch (failure: MailboxFailure) { assertEquals(2, failure.status) }
        }
        client(frame(2, byteArrayOf(0)), mutableListOf()).use { c ->
            try { c.open(listOf(ByteArray(32))); fail() } catch (failure: MailboxFailure) { assertEquals(6, failure.status) }
        }
    }
    @Test fun aPartialFrameHasOneDeadlineEvenWhenReadsKeepTimingOut() {
        var ticks = 0L
        var sent = false
        MailboxClient({ _, _ -> if (!sent) { sent = true; byteArrayOf(0) } else null }, {}, { true },
            { ticks += 1_000_000_000; ticks }).use { c ->
            try { c.poll(); fail() } catch (failure: MailboxFailure) { assertEquals(5, failure.status) }
        }
    }
}
