package com.fixcraft.ito.messaging

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fixcraft.ito.call.CallFailure
import com.fixcraft.ito.call.CallNative
import com.fixcraft.ito.call.SnapshotPersistence
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real JNI, composite signatures and ratchets; no Android/relay storage claim. */
@RunWith(AndroidJUnit4::class)
class MessageBoundaryTest {
    private val now get() = System.currentTimeMillis()
    private val fingerprint = ByteArray(32) { 7 }
    private val service = "ito-mailbox"

    private class Peer : AutoCloseable {
        val context = CallNative.contextCreate()
        var saved: ByteArray? = null
        var sessionSaved: ByteArray? = null
        var borrowed: ByteArray? = null
        val persistence = SnapshotPersistence { bytes ->
            saved?.fill(0)
            saved = bytes.copyOf()
            borrowed = bytes
            CallNative.PERSIST_COMMITTED
        }
        val sessionPersistence = SnapshotPersistence { bytes ->
            sessionSaved?.fill(0)
            sessionSaved = bytes.copyOf()
            CallNative.PERSIST_COMMITTED
        }
        val store = CallNative.storeOpen(context, null, persistence)
        init { CallNative.createIdentity(store, System.currentTimeMillis() / 1000) }
        override fun close() {
            try { CallNative.storeDestroy(store) }
            finally { CallNative.contextDestroy(context); saved?.fill(0); sessionSaved?.fill(0) }
        }
    }

    private fun pair(owner: Peer, scanner: Peer): Pair<ByteArray, ByteArray> {
        MessageNative.upgradeIdentity(owner.store)
        MessageNative.upgradeIdentity(scanner.store)
        MessageNative.createCode(owner.store, MessageNative.IN_PERSON, fingerprint, service,
            "Owner".toByteArray(), now / 1000).use { code ->
            MessageNative.scan(scanner.store, code.bytes, MessageNative.SCANNED, fingerprint,
                service, "Scanner".toByteArray(), now / 1000).use { scan ->
                MessageNative.receivePairing(owner.store, scan.output.target.address,
                    scan.output.bytes, now / 1000).use { reply ->
                    assertEquals(MessageNative.REPLIED, reply.kind)
                    val output = requireNotNull(reply.output)
                    MessageNative.receivePairing(scanner.store, output.target.address,
                        output.bytes, now / 1000).use { paired ->
                        assertEquals(MessageNative.PAIRED, paired.kind)
                        val confirm = requireNotNull(paired.output)
                        MessageNative.receivePairing(owner.store, confirm.target.address,
                            confirm.bytes, now / 1000).use { confirmed ->
                            assertEquals(MessageNative.CONFIRMED, confirmed.kind)
                            assertArrayEquals(reply.shortCode, paired.shortCode)
                            MessageNative.decide(owner.store, code.id, true, now / 1000).use { done ->
                                assertEquals(MessageNative.COMPLETED, done.kind)
                            }
                        }
                    }
                }
            }
        }
        assertTrue(requireNotNull(owner.borrowed).all { it == 0.toByte() })
        assertTrue(requireNotNull(scanner.borrowed).all { it == 0.toByte() })
        return requireNotNull(CallNative.identity(owner.store)) to requireNotNull(CallNative.identity(scanner.store))
    }

    @Test fun pairingSessionsAndPlaintextBorrowing() {
        val baseline = MessageNative.liveSessions()
        Peer().use { owner -> Peer().use { scanner ->
            val legacy = requireNotNull(CallNative.identity(scanner.store))
            CallNative.addContact(owner.store, legacy, "Existing name".toByteArray(), now / 1000)
            assertNull(MessageNative.contact(owner.store, legacy))
            val (ownerKey, scannerKey) = pair(owner, scanner)
            val ownerContact = requireNotNull(MessageNative.contact(owner.store, scannerKey))
            assertEquals(MessageNative.OWNER, ownerContact.role)
            assertTrue(ownerContact.receive.on(fingerprint, service))
            assertEquals(60, MessageNative.safetyCode(owner.store, scannerKey).length)
            assertEquals(MessageNative.safetyCode(owner.store, scannerKey), MessageNative.safetyCode(scanner.store, ownerKey))
            val receiver = MessageNative.openSession(owner.store, scannerKey, null, owner.sessionPersistence, now)
            try {
                val sender = MessageNative.openSession(scanner.store, ownerKey, null, scanner.sessionPersistence, now)
                try {
                    MessageNative.send(sender, MessageNative.TEXT, "hello".toByteArray(), 0, byteArrayOf(), 0, now).use { sealed ->
                        assertEquals(1, MessageNative.outbox(sender).size)
                        var borrowed: MessageValue? = null
                        val result = MessageNative.receive(receiver, sealed.bytes, now, MessageSink { value ->
                            assertEquals(MessageNative.TEXT, value.kind)
                            assertEquals("hello", value.text.toString(Charsets.UTF_8))
                            assertArrayEquals(sealed.id, value.id)
                            borrowed = value
                            CallNative.PERSIST_COMMITTED
                        })
                        assertEquals(CallNative.OK, result)
                        assertTrue(requireNotNull(borrowed).text.all { it == 0.toByte() })
                        assertTrue(requireNotNull(borrowed).id.all { it == 0.toByte() })
                        assertEquals(MessageNative.ALREADY_SPENT, MessageNative.receive(receiver, sealed.bytes,
                            now, MessageSink { fail("Replay reached history"); CallNative.PERSIST_NOT_COMMITTED }))
                    }
                } finally { MessageNative.closeSession(sender) }
            } finally { MessageNative.closeSession(receiver) }
        } }
        assertEquals(baseline, MessageNative.liveSessions())
    }

    @Test fun aRefusedHistoryWriteLeavesTheItemRetryable() {
        Peer().use { owner -> Peer().use { scanner ->
            val (ownerKey, scannerKey) = pair(owner, scanner)
            val receiver = MessageNative.openSession(owner.store, scannerKey, null, owner.sessionPersistence, now)
            try {
                val sender = MessageNative.openSession(scanner.store, ownerKey, null, scanner.sessionPersistence, now)
                try {
                    MessageNative.send(sender, MessageNative.TEXT, "retry".toByteArray(), 0, byteArrayOf(), 0, now).use { item ->
                        var writes = 0
                        assertEquals(-15, MessageNative.receive(receiver, item.bytes, now, MessageSink {
                            writes++; CallNative.PERSIST_NOT_COMMITTED
                        }))
                        assertEquals(CallNative.OK, MessageNative.receive(receiver, item.bytes, now, MessageSink {
                            writes++; CallNative.PERSIST_COMMITTED
                        }))
                        assertEquals(2, writes)
                    }
                } finally { MessageNative.closeSession(sender) }
            } finally { MessageNative.closeSession(receiver) }
        } }
    }

    @Test fun sendRetrySurvivesSessionAndHistoryAmbiguityWithoutDuplicating() {
        for (failureAt in listOf(2, 3)) Peer().use { owner -> Peer().use { scanner ->
            val (ownerKey, _) = pair(owner, scanner)
            val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            val root = java.io.File(context.noBackupFilesDir, "send-recovery-$failureAt")
            val key = ByteArray(32) { 4 }
            var writes = 0
            var failAt = 0
            val history = com.fixcraft.ito.content.ChatRepository(root, { it(key) }, {}, { directory ->
                if (directory == root && ++writes == failAt) throw java.io.IOException()
            }, { now })
            val token = ByteArray(16) { 5 }
            try {
                MessageConversation(scanner.store, ownerKey, history).use { sender ->
                    writes = 0; failAt = failureAt
                    try { sender.send(MessageNative.TEXT, "one send".toByteArray(), operation = token); fail("Missing injected failure") }
                    catch (_: CallFailure) { assertEquals(2, failureAt) }
                    catch (failure: com.fixcraft.ito.content.ContentException) {
                        assertEquals(3, failureAt)
                        assertEquals(com.fixcraft.ito.content.ContentFailure.Ambiguous, failure.reason)
                    }
                }
                failAt = 0
                MessageConversation(scanner.store, ownerKey, history).use { sender ->
                    sender.send(MessageNative.TEXT, "one send".toByteArray(), operation = token)
                    sender.send(MessageNative.TEXT, "one send".toByteArray(), operation = token)
                    history.messages(ownerKey).let { messages ->
                        try { assertEquals(1, messages.size); assertArrayEquals(token, messages.single().operation) }
                        finally { messages.forEach { it.close() } }
                    }
                    history.read(ownerKey) { assertNull(it.pending); assertEquals(1, it.deposits.size) }
                    // A deliberate second send of equal text remains a distinct message.
                    sender.send(MessageNative.TEXT, "one send".toByteArray(), operation = ByteArray(16) { 6 })
                    history.messages(ownerKey).let { messages ->
                        try { assertEquals(2, messages.size) } finally { messages.forEach { it.close() } }
                    }
                }
            } finally { key.fill(0); root.deleteRecursively() }
        } }
    }

    @Test fun repositoryHistoryCommitsBeforeAmbiguousReceiveRatchet() {
        Peer().use { owner -> Peer().use { scanner ->
            val (ownerKey, scannerKey) = pair(owner, scanner)
            val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            val root = java.io.File(context.noBackupFilesDir, "receive-recovery")
            val key = ByteArray(32) { 4 }
            var writes = 0
            var armed = false
            lateinit var history: com.fixcraft.ito.content.ChatRepository
            history = com.fixcraft.ito.content.ChatRepository(root, { it(key) }, {}, { directory ->
                if (armed && directory == root && ++writes == 2) {
                    history.read(scannerKey) { assertEquals(1, it.messages.size) }
                    throw java.io.IOException()
                }
            }, { now })
            val sender = MessageNative.openSession(scanner.store, ownerKey, null, scanner.sessionPersistence, now)
            try {
                MessageNative.send(sender, MessageNative.TEXT, "durable first".toByteArray(), 0, byteArrayOf(), 0, now).use { item ->
                    MessageConversation(owner.store, scannerKey, history).use { receiver ->
                        armed = true
                        assertEquals(CallNative.STORAGE_AMBIGUOUS, receiver.receive(item.bytes))
                    }
                    armed = false
                    MessageConversation(owner.store, scannerKey, history).use { receiver ->
                        assertEquals(MessageNative.ALREADY_SPENT, receiver.receive(item.bytes))
                    }
                    history.read(scannerKey) { assertEquals(1, it.messages.size); assertEquals(1, it.actions.size) }
                }
            } finally { MessageNative.closeSession(sender); key.fill(0); root.deleteRecursively() }
        } }
    }

    @Test fun inputRefusalsPreserveHandlesAndIdentity() {
        val baseline = MessageNative.liveSessions()
        Peer().use { owner ->
            MessageNative.upgradeIdentity(owner.store)
            val identity = MessageNative.fingerprint(owner.store)
            try {
                MessageNative.createCode(owner.store, MessageNative.IN_PERSON, fingerprint, service,
                    "Owner".toByteArray(), -1)
                fail("Negative clock was accepted")
            } catch (failure: CallFailure) { assertEquals(-1, failure.code) }
            assertTrue(MessageNative.pairings(owner.store).isEmpty())
            assertArrayEquals(identity, MessageNative.fingerprint(owner.store))
            try {
                MessageNative.openSession(owner.store, ByteArray(32) { 8 },
                    ByteArray(MessageNative.MAX_SESSION_BYTES + 1), owner.persistence, now)
                fail("Unbounded snapshot was accepted")
            } catch (failure: CallFailure) { assertEquals(-1, failure.code) }
        }
        assertEquals(baseline, MessageNative.liveSessions())
    }
}
