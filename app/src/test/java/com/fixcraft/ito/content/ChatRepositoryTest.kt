package com.fixcraft.ito.content

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ChatRepositoryTest {
    private lateinit var parent: File
    private val peer = ByteArray(32) { 1 }
    private val other = ByteArray(32) { 2 }
    private val id = ByteArray(16) { 3 }
    private val key = ByteArray(32) { 4 }
    private var now = 1_800_000_000_000L
    private fun owner(sync: (File) -> Unit = {}) = ChatRepository(File(parent, "chats"),
        { action -> action(key) }, {}, sync, { now })
    @Before fun prepare() { parent = Files.createTempDirectory("ito-chats-").toFile() }
    @After fun cleanup() { parent.deleteRecursively() }

    @Test fun migrationKeepsOldHistoryAndIsIdempotentAfterPartialFailure() {
        val old = LocalContentRepository(File(parent, "old"), { action -> action(key) }, {}, {}, { now / 1000 })
        old.recordMessage(id, peer, false, "retained".toByteArray(), now / 1000)
        old.recordCall(other, true, 0, now / 1000, 20)
        now += 40L * 86_400_000
        var writes = 0
        try { owner { if (++writes == 3) throw java.io.IOException() }.migrate(old); fail() }
        catch (_: ContentException) {}
        assertEquals(1, old.migrationSnapshot().use { it.messages.size })
        owner().migrate(old)
        owner().migrate(old)
        owner().messages(peer).single().use { assertEquals("retained", it.text.toString(Charsets.UTF_8)) }
        owner().history(other).single().use { assertEquals(20L, it.durationMs) }
        assertEquals(0, old.migrationSnapshot().use { it.messages.size + it.calls.size })
    }

    @Test fun historyPersistsPastThirtyDaysAndCannotBeMovedToAnotherPeerOrPurpose() {
        owner().recordLegacy(id, peer, true, "kept".toByteArray(), now / 1000)
        now += 100L * 86_400_000
        owner().messages(peer).single().close()
        val file = File(parent, "chats").listFiles()!!.single { it.name.endsWith(".history") }
        val otherName = other.joinToString("") { "%02x".format(it.toInt() and 255) }
        file.copyTo(File(file.parentFile, "$otherName.history"))
        expect(ContentFailure.Corrupt) { owner().messages(other) }
        file.copyTo(File(file.parentFile, file.name.replace("history", "session")))
        expect(ContentFailure.Corrupt) { owner().session(peer) }
    }

    @Test fun photosAreBoundedJpegLabelsThatHistoryDeletionKeeps() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
        assertNull(owner().photo(peer))
        owner().setPhoto(peer, jpeg)
        owner().setPhoto(null, jpeg.copyOf().also { it[6] = 9 })
        assertArrayEquals(jpeg, owner().photo(peer))
        assertEquals(9.toByte(), owner().photo(null)!![6])
        // Only a JPEG within the bound is accepted.
        expect(ContentFailure.InvalidInput) { owner().setPhoto(peer, "not an image".toByteArray()) }
        expect(ContentFailure.InvalidInput) { owner().setPhoto(peer, jpeg.copyOf(ChatRepository.MAX_PHOTO + 1)) }
        // Photos are not history: deleting history keeps them, setting null removes one.
        owner().recordLegacy(id, peer, false, "text".toByteArray(), now / 1000)
        owner().deleteHistory(peer)
        assertArrayEquals(jpeg, owner().photo(peer))
        owner().setPhoto(peer, null)
        assertNull(owner().photo(peer))
        assertNotNull(owner().photo(null))
    }

    @Test fun aPhotoCannotBeMovedToAnotherContactTheOwnerOrAnotherRecord() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDB.toByte())
        owner().setPhoto(peer, jpeg)
        val directory = File(parent, "chats")
        val file = directory.listFiles()!!.single { it.name.endsWith(".photo") }
        val otherName = other.joinToString("") { "%02x".format(it.toInt() and 255) }
        // Private copies, so only the record's authenticated domain can refuse them.
        fun moved(name: String) = file.copyTo(File(directory, name)).also {
            Files.setPosixFilePermissions(it.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
        }
        moved("$otherName.photo")
        expect(ContentFailure.Corrupt) { owner().photo(other) }
        moved("own.photo")
        expect(ContentFailure.Corrupt) { owner().photo(null) }
        moved(file.name.replace("photo", "history"))
        expect(ContentFailure.Corrupt) { owner().messages(peer) }
        // The unchanged record still opens, so the refusals above are the domain's.
        assertArrayEquals(jpeg, owner().photo(peer))
    }

    @Test fun deletionAndTimerKeepTombstonesWithoutResettingSession() {
        owner().saveSession(peer, byteArrayOf(1, 2, 3))
        owner().recordLegacy(id, peer, false, "gone".toByteArray(), now / 1000)
        owner().change(peer) { it.timer = 1 }
        now += 2 * 86_400_000L
        assertTrue(owner().messages(peer).isEmpty())
        assertTrue(owner().read(peer) { it.holds(id, false) })
        owner().recordLegacy(id, peer, false, "gone".toByteArray(), now / 1000)
        assertTrue(owner().messages(peer).isEmpty())
        owner().deleteHistory(peer)
        assertArrayEquals(byteArrayOf(1, 2, 3), owner().session(peer))
    }

    @Test fun pendingAndResponseQueueSurviveReopenAndUnknownFormatCannotBeCleared() {
        owner().change(peer) {
            it.pending = PendingMessage(1, "pending".toByteArray(), 0, ByteArray(16) { 8 })
            it.actions.add(ChatAction(id.copyOf(), 2, 1, id.copyOf()))
        }
        owner().read(peer) {
            assertEquals("pending", it.pending!!.text.toString(Charsets.UTF_8))
            assertArrayEquals(id, it.actions.single().ids)
        }
        val file = File(parent, "chats").listFiles()!!.single { it.name.endsWith(".history") }
        file.writeBytes(file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
        val corrupt = file.readBytes()
        expect(ContentFailure.Corrupt) { owner().deleteHistory(peer) }
        assertArrayEquals(corrupt, file.readBytes())
    }

    @Test fun rejectsLinksAndPostRenameFailureIsAmbiguous() {
        owner().recordLegacy(id, peer, true, "kept".toByteArray(), now / 1000)
        expect(ContentFailure.Ambiguous) { owner { throw java.io.IOException() }.deleteHistory(peer) }
        assertTrue(owner().messages(peer).isEmpty())
        val target = Files.createTempDirectory(parent.toPath(), "target-")
        val root = File(parent, "chats")
        val file = root.listFiles()!!.single { it.name.endsWith(".history") }
        Files.delete(file.toPath()); Files.createSymbolicLink(file.toPath(), target)
        expect(ContentFailure.Corrupt) { owner().messages(peer) }
        assertTrue(Files.isSymbolicLink(file.toPath()))
    }

    @Test fun codecRejectsDuplicateTrailingAndUnboundedRecords() {
        ChatSnapshot().use { state ->
            val bytes = ChatCodec.encode(state)
            expect(ContentFailure.Corrupt) { ChatCodec.decode(bytes + byteArrayOf(0)) }
            expect(ContentFailure.Corrupt) { ChatCodec.decode(bytes.copyOf().also { it[12] = 127 }) }
            state.messages.add(ChatMessage(id.copyOf(), true, 1, now, now, "one".toByteArray(), 0, 0))
            state.messages.add(ChatMessage(id.copyOf(), true, 1, now, now, "two".toByteArray(), 0, 0))
            expect(ContentFailure.InvalidInput) { ChatCodec.encode(state) }
        }
    }

    private fun expect(reason: ContentFailure, action: () -> Unit) {
        try { action(); fail("Accepted invalid content") }
        catch (failure: ContentException) { assertEquals(reason, failure.reason) }
    }
}
