package com.fixcraft.ito.content

import com.fixcraft.ito.security.AppLockedException
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LocalContentRepositoryTest {
    private lateinit var parent: File
    private lateinit var root: File
    private val key = ByteArray(32) { (it + 7).toByte() }
    private val peer = ByteArray(32) { (it + 1).toByte() }
    private val id = ByteArray(16) { (it + 2).toByte() }
    private var now = 1_800_000_000L
    private var denied = false
    private lateinit var store: LocalContentRepository

    @Before fun prepare() {
        parent = Files.createTempDirectory("ito-content-test-").toFile()
        root = File(parent, "content")
        store = owner()
    }
    @After fun cleanup() { parent.deleteRecursively() }
    private fun owner(sync: (File) -> Unit = {}) = LocalContentRepository(root, { operation -> operation(key) },
        { if (denied) throw AppLockedException() }, sync, { now })
    private fun expect(reason: ContentFailure, operation: () -> Unit) {
        try { operation(); fail("Content operation was accepted") } catch (failure: ContentException) { assertEquals(reason, failure.reason) }
    }

    @Test fun messagesAndHistorySurviveReopenAsEncryptedLocalData() {
        val text = "local message 日本語".toByteArray()
        store.recordMessage(id, peer, false, text, now)
        store.recordCall(peer, true, -20, now, 1200)
        val bytes = File(root, "snapshot.bin").readBytes()
        assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("local message"))
        store = owner()
        store.messages().single().use { assertArrayEquals(text, it.utf8); assertArrayEquals(peer, it.peer) }
        store.history().single().use { assertEquals(-20, it.result); assertEquals(1200L, it.durationMs) }
    }

    @Test fun identicalDuplicateIsIdempotentButConflictingPayloadIsRefused() {
        store.recordMessage(id, peer, false, "same".toByteArray(), now)
        store.recordMessage(id, peer, false, "same".toByteArray(), now + 1)
        assertEquals(1, store.messages().size)
        expect(ContentFailure.InvalidInput) { store.recordMessage(id, peer, false, "different".toByteArray(), now) }
        store.messages().single().use { assertEquals("same", it.utf8.toString(Charsets.UTF_8)) }
    }

    @Test fun corruptCiphertextCannotBeReplacedByAppendOrClear() {
        store.recordMessage(id, peer, true, "kept".toByteArray(), now)
        val file = File(root, "snapshot.bin")
        val bytes = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        file.writeBytes(bytes)
        expect(ContentFailure.Corrupt) { store.messages() }
        expect(ContentFailure.Corrupt) { store.clear() }
        expect(ContentFailure.Corrupt) { store.recordCall(peer, true, 0, now, 1) }
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun lockedAccessCannotReadWriteOrDeleteExistingContent() {
        store.recordMessage(id, peer, true, "kept".toByteArray(), now)
        val before = File(root, "snapshot.bin").readBytes()
        denied = true
        listOf<() -> Unit>({ store.messages() }, { store.history() }, { store.clear() }, { store.deletePeer(peer) },
            { store.recordCall(peer, true, 0, now, 1) }).forEach { operation ->
            try { operation(); fail("Locked content access accepted") } catch (_: AppLockedException) {}
        }
        assertArrayEquals(before, File(root, "snapshot.bin").readBytes())
    }

    @Test fun countAndByteBoundsEvictOldRecords() {
        repeat(260) { number ->
            val next = ByteArray(16).also { it[0] = (number shr 8).toByte(); it[1] = number.toByte() }
            store.recordMessage(next, peer, false, ByteArray(4096) { 97 }, now + number)
        }
        val messages = store.messages()
        try {
            assertTrue(messages.size <= 256)
            assertFalse(messages.any { it.id.contentEquals(ByteArray(16)) })
            assertTrue(File(root, "snapshot.bin").length() <= 1024L * 1024 + 33)
        } finally { messages.forEach(LocalMessage::close) }
    }

    @Test fun thirtyDayRetentionIsAppliedAtNextAccess() {
        store.recordMessage(id, peer, false, "old".toByteArray(), now)
        store.recordCall(peer, false, 0, now, 10)
        now += ContentCodec.RETENTION_SECONDS + 1
        assertTrue(store.messages().isEmpty())
        assertTrue(store.history().isEmpty())
    }

    @Test fun migrationReadsRetainedRecordsBeforeLegacyExpiry() {
        store.recordMessage(id, peer, false, "retained".toByteArray(), now)
        store.recordCall(peer, true, 0, now, 10)
        now += ContentCodec.RETENTION_SECONDS + 1
        val before = File(root, "snapshot.bin").readBytes()
        store.migrationSnapshot().use {
            assertEquals(1, it.messages.size)
            assertEquals(1, it.calls.size)
        }
        assertArrayEquals(before, File(root, "snapshot.bin").readBytes())
    }

    @Test fun deletionPreservesUnrelatedPeerAndDirection() {
        val other = ByteArray(32) { 99 }
        store.recordMessage(id, peer, false, "received".toByteArray(), now)
        store.recordMessage(id, peer, true, "sent".toByteArray(), now)
        store.recordMessage(id, other, false, "unrelated".toByteArray(), now)
        store.deleteMessage(id, peer, false)
        assertEquals(2, store.messages().size)
        store.deletePeer(peer)
        store.messages().single().use { assertArrayEquals(other, it.peer) }
    }

    @Test fun invalidUtf8OversizeAndMalformedIdentifiersAreRefusedBeforePublication() {
        listOf(byteArrayOf(0xc3.toByte(), 0x28), ByteArray(4097) { 97 }, byteArrayOf(0),
            byteArrayOf(0x1b), byteArrayOf(0xc2.toByte(), 0x80.toByte())).forEach { text ->
            expect(ContentFailure.InvalidInput) { store.recordMessage(id, peer, false, text, now) }
        }
        expect(ContentFailure.InvalidInput) { store.recordMessage(ByteArray(15), peer, false, byteArrayOf(97), now) }
        expect(ContentFailure.InvalidInput) { store.recordCall(peer, true, 0, now, -1) }
        assertFalse(File(root, "snapshot.bin").exists())
    }

    @Test fun directorySyncFailureAfterRenameIsAmbiguousAndRequiresReadingDurableState() {
        store.recordMessage(id, peer, true, "old".toByteArray(), now)
        store = owner { throw java.io.IOException("injected") }
        expect(ContentFailure.Ambiguous) { store.recordCall(peer, true, 0, now, 10) }
        store = owner()
        assertEquals(1, store.messages().size)
        assertEquals(1, store.history().size)
    }

    @Test fun codecRejectsTrailingUnknownVersionAndUnboundedLengths() {
        val empty = ContentCodec.encode(ContentSnapshot())
        expect(ContentFailure.Corrupt) { ContentCodec.decode(empty + byteArrayOf(0)) }
        expect(ContentFailure.Corrupt) { ContentCodec.decode(empty.copyOf().also { it[3] = 2 }) }
        expect(ContentFailure.Corrupt) { ContentCodec.decode(empty.copyOf().also { it[4] = 127 }) }
    }
}
