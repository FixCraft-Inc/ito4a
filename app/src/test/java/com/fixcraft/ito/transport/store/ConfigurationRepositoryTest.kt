package com.fixcraft.ito.transport.store

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CancellationException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ConfigurationRepositoryTest {
    private lateinit var parent: File
    private lateinit var root: File
    private lateinit var cipher: TestCipher
    private lateinit var store: ConfigurationRepository

    @Before fun setUp() {
        parent = Files.createTempDirectory("ito-store-test-").toFile()
        root = File(parent, "store")
        cipher = TestCipher()
        store = ConfigurationRepository(root, cipher)
    }

    @After fun tearDown() {
        parent.deleteRecursively()
    }

    @Test fun importDoesNotSelectAndMaterialIsCopied() {
        val files = bundle()
        val imported = store.importConfiguration("Manual", files)
        files.values.forEach { it.fill(7) }
        assertEquals(ConfigurationCatalog(listOf(imported), null), store.list())
        store.select(imported.id)
        val session = store.materialize(imported.id)
        assertArrayEquals("{}".toByteArray(), session.config)
        assertArrayEquals("fixture-secret".toByteArray(), File(session.directory, "credentials/key.pem").readBytes())
        assertEquals(setOf(imported.id), store.list().entries.map { it.id }.toSet())
        session.close()
        assertFalse(session.directory.exists())
        assertTrue(session.config.all { it == 0.toByte() })
        session.close()
    }

    @Test fun selectionAndMaterialSurviveReopen() {
        val imported = store.importConfiguration("Manual", bundle())
        store.select(imported.id)
        store = ConfigurationRepository(root, cipher)
        assertEquals(imported.id, store.list().selectedId)
        store.materialize(imported.id).use { session ->
            assertArrayEquals("{}".toByteArray(), session.config)
        }
    }

    @Test fun deletionIsLocalAndPreservesOtherConfigurations() {
        val first = store.importConfiguration("First", bundle())
        val second = store.importConfiguration("Second", bundle())
        store.select(first.id)
        store.remove(first.id)
        assertEquals(ConfigurationCatalog(listOf(second), null), store.list())
        store.materialize(second.id).close()
        expect(StoreFailure.NotFound) { store.materialize(first.id) }
    }

    @Test fun failedReplacementPreservesCompleteOldSnapshot() {
        var failPublication = false
        store = ConfigurationRepository(root, cipher) {
            if (failPublication) throw IOException("injected publication fault")
        }
        val first = store.importConfiguration("First", bundle())
        val before = File(root, "snapshot.bin").readBytes()
        failPublication = true
        expect(StoreFailure.Io) { store.importConfiguration("Second", bundle()) }
        assertArrayEquals(before, File(root, "snapshot.bin").readBytes())
        assertEquals(listOf(first), store.list().entries)
        assertFalse(File(root, "snapshot.tmp").exists())
    }

    @Test fun authenticatedCorruptionIsNotReplacedByImportOrDeletion() {
        val first = store.importConfiguration("First", bundle())
        val file = File(root, "snapshot.bin")
        val damaged = file.readBytes()
        damaged[damaged.lastIndex] = (damaged.last().toInt() xor 1).toByte()
        file.writeBytes(damaged)
        expect(StoreFailure.Corrupt) { store.list() }
        expect(StoreFailure.Corrupt) { store.importConfiguration("Second", bundle()) }
        expect(StoreFailure.Corrupt) { store.select(first.id) }
        expect(StoreFailure.Corrupt) { store.remove(first.id) }
        assertArrayEquals(damaged, file.readBytes())
    }

    @Test fun cancellationObservedAtPublicationCheckpointPreservesSnapshotAndRemovesCandidate() {
        val first = store.importConfiguration("First", bundle())
        val before = File(root, "snapshot.bin").readBytes()
        try {
            store.importConfiguration("Cancelled", bundle()) { throw CancellationException("cancelled") }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertArrayEquals(before, File(root, "snapshot.bin").readBytes())
            assertEquals(listOf(first), store.list().entries)
            assertFalse(File(root, "snapshot.tmp").exists())
            assertTrue(File(root, "sessions").listFiles()!!.isEmpty())
        }
    }

    @Test fun absentExistingKeyFailsClosedWithoutReplacementKey() {
        store.importConfiguration("First", bundle())
        val before = File(root, "snapshot.bin").readBytes()
        val created = cipher.created
        cipher.available = false
        expect(StoreFailure.KeyUnavailable) { store.list() }
        expect(StoreFailure.KeyUnavailable) { store.importConfiguration("Second", bundle()) }
        assertEquals(created, cipher.created)
        assertArrayEquals(before, File(root, "snapshot.bin").readBytes())
    }

    @Test fun startupDiscardsTemporaryCiphertextWithoutPromotingIt() {
        val first = store.importConfiguration("First", bundle())
        File(root, "snapshot.tmp").writeText("incomplete ciphertext")
        store.recoverStaging()
        assertFalse(File(root, "snapshot.tmp").exists())
        assertEquals(listOf(first), store.list().entries)
    }

    @Test fun startupRemovesAbandonedPlaintextAndNeverFollowsLinks() {
        val session = store.stage(bundle())
        val outside = File(parent, "outside-secret").apply { writeText("preserve") }
        Files.createSymbolicLink(session.directory.toPath().resolve("external"), outside.toPath())
        val reopened = ConfigurationRepository(root, cipher)
        reopened.recoverStaging()
        assertFalse(session.directory.exists())
        assertEquals("preserve", outside.readText())
    }

    @Test fun recoveryRefusesWhileNativeMayBorrowSessionFiles() {
        val session = store.stage(bundle())
        expect(StoreFailure.Busy) { store.recoverStaging() }
        assertTrue(session.directory.exists())
        session.close()
        store.recoverStaging()
    }

    @Test fun stageDoesNotPublishAndHasPrivateFiles() {
        store.stage(bundle()).use { session ->
            assertTrue(store.list().entries.isEmpty())
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(session.directory.toPath())))
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(session.directory.toPath().resolve("yume.json"))))
        }
        assertFalse(File(root, "snapshot.bin").exists())
    }

    @Test fun invalidPathsAndOverlappingFilesAreRefusedBeforePublication() {
        for (path in listOf("../key", "/absolute", "a//b", "a/../b", "./key", "a/./b", "a\\b")) {
            expect(StoreFailure.InvalidInput) {
                store.importConfiguration("Manual", bundle() + (path to byteArrayOf(1)))
            }
        }
        expect(StoreFailure.InvalidInput) {
            store.importConfiguration("Manual", bundle() + ("credentials" to byteArrayOf(1)))
        }
        assertFalse(File(root, "snapshot.bin").exists())
    }

    @Test fun oversizeMissingConfigAndControlLabelsAreRefused() {
        expect(StoreFailure.InvalidInput) {
            store.importConfiguration("Manual", mapOf("yume.json" to ByteArray(StoreLimits.FILE_BYTES + 1)))
        }
        expect(StoreFailure.InvalidInput) {
            store.importConfiguration("Manual", mapOf("key.pem" to byteArrayOf(1)))
        }
        for (label in listOf("", "\nsecret", "x".repeat(StoreLimits.LABEL_BYTES + 1), "\uD800")) {
            expect(StoreFailure.InvalidInput) { store.importConfiguration(label, bundle()) }
        }
        assertFalse(File(root, "snapshot.bin").exists())
    }

    @Test fun ninthConfigurationIsRefusedWithoutChangingSelection() {
        val first = store.importConfiguration("First", bundle())
        store.select(first.id)
        repeat(StoreLimits.CONFIGURATIONS - 1) { store.importConfiguration("Next", bundle()) }
        val before = File(root, "snapshot.bin").readBytes()
        expect(StoreFailure.Limit) { store.importConfiguration("Overflow", bundle()) }
        assertEquals(first.id, store.list().selectedId)
        assertArrayEquals(before, File(root, "snapshot.bin").readBytes())
    }

    @Test fun storeRootSymlinkDoesNotAccessTheTarget() {
        val target = File(parent, "unrelated").apply { mkdir() }
        Files.createSymbolicLink(root.toPath(), target.toPath())
        expect(StoreFailure.Io) { store.list() }
        assertTrue(target.listFiles()!!.isEmpty())
    }

    @Test fun ciphertextNeverContainsTheCredentialOrConfig() {
        val secret = "a sufficiently long fixture credential sentinel".toByteArray()
        val files = bundle() + ("credentials/sentinel" to secret)
        store.importConfiguration("Manual", files)
        val encrypted = File(root, "snapshot.bin").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(encrypted.contains(secret.toString(Charsets.ISO_8859_1)))
    }

    private fun bundle() = mapOf(
        "yume.json" to "{}".toByteArray(),
        "credentials/key.pem" to "fixture-secret".toByteArray(),
    )

    private fun expect(reason: StoreFailure, action: () -> Any?) {
        try {
            action()
            fail("Expected ${reason.name}")
        } catch (failure: StoreException) {
            assertEquals(reason, failure.reason)
        }
    }

    private class TestCipher : SnapshotCipher {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        var available = false
        var created = 0

        override fun encrypt(plaintext: ByteArray, allowNewKey: Boolean): ByteArray {
            if (!available) {
                if (!allowNewKey) throw StoreException(StoreFailure.KeyUnavailable)
                available = true
                created++
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            return cipher.iv + cipher.doFinal(plaintext)
        }

        override fun decrypt(ciphertext: ByteArray): ByteArray {
            if (!available) throw StoreException(StoreFailure.KeyUnavailable)
            if (ciphertext.size < 28) throw StoreException(StoreFailure.Corrupt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ciphertext, 0, 12))
            return try {
                cipher.doFinal(ciphertext, 12, ciphertext.size - 12)
            } catch (_: AEADBadTagException) {
                throw StoreException(StoreFailure.Corrupt)
            }
        }
    }
}
