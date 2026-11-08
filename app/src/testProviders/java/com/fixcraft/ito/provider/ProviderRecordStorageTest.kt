package com.fixcraft.ito.provider

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real filesystem and JCA; the in-memory key seam does not qualify Keystore. */
class ProviderRecordStorageTest {
    private lateinit var parent: File
    private lateinit var directory: File
    private lateinit var keys: TestKeys

    @Before fun createRoot() {
        parent = Files.createTempDirectory("ito-provider-storage-").toFile()
        directory = File(parent, "records")
        keys = TestKeys()
    }

    @After fun removeRoot() { assertTrue(parent.deleteRecursively()) }

    @Test fun missingIsAbsentAndOwnedBuffersAreIndependent() {
        val storage = storage()
        assertNull(storage.readBlob("service-selection"))
        assertEquals(0, keys.creations)
        val input = "provider credential".toByteArray()
        val expected = input.copyOf()
        storage.writeBlob("service-selection", input)
        assertArrayEquals(expected, input)
        val file = File(directory, "service-selection.bin")
        assertEquals(expected.size + ProviderRecordCipher.OVERHEAD, file.length().toInt())
        assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("provider credential"))
        input.fill(0)
        val first = requireNotNull(storage.readBlob("service-selection"))
        assertArrayEquals(expected, first)
        first.fill(0)
        assertArrayEquals(expected, storage.readBlob("service-selection"))
        assertEquals(1, keys.creations)
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory.toPath()))
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file.toPath()))
    }

    @Test fun exactNameAndDomainAreAuthenticated() {
        val storage = storage()
        storage.writeBlob("alpha", "alpha secret".toByteArray())
        val alpha = File(directory, "alpha.bin")
        val beta = File(directory, "beta.bin")
        Files.copy(alpha.toPath(), beta.toPath())
        refusal(ProviderStorageFailure.Corrupt) { storage.readBlob("beta") }
        assertArrayEquals("alpha secret".toByteArray(), storage.readBlob("alpha"))
        val manualDomain = alpha.readBytes().also { it[3] = 'S'.code.toByte() }
        alpha.writeBytes(manualDomain)
        refusal(ProviderStorageFailure.Corrupt) { storage.readBlob("alpha") }
    }

    @Test fun corruptionCannotBeOverwrittenAndOtherProviderSurvives() {
        val storage = storage()
        storage.writeBlob("alpha", byteArrayOf(1, 2, 3))
        storage.writeBlob("beta", byteArrayOf(4, 5, 6))
        val file = File(directory, "alpha.bin")
        val corrupted = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        file.writeBytes(corrupted)
        refusal(ProviderStorageFailure.Corrupt) { storage.readBlob("alpha") }
        refusal(ProviderStorageFailure.Corrupt) { storage.writeBlob("alpha", byteArrayOf(7)) }
        assertArrayEquals(corrupted, file.readBytes())
        assertArrayEquals(byteArrayOf(4, 5, 6), storage.readBlob("beta"))
    }

    @Test fun missingKeyCannotReplaceRetainedCiphertext() {
        val storage = storage()
        storage.writeBlob("alpha", byteArrayOf(1, 2, 3))
        val before = File(directory, "alpha.bin").readBytes()
        keys.secret = null
        refusal(ProviderStorageFailure.KeyUnavailable) { storage.ensureReady() }
        refusal(ProviderStorageFailure.KeyUnavailable) { storage.readBlob("alpha") }
        refusal(ProviderStorageFailure.KeyUnavailable) { storage.writeBlob("beta", byteArrayOf(4)) }
        assertFalse(storage.isUnlocked())
        assertEquals(1, keys.creations)
        assertArrayEquals(before, File(directory, "alpha.bin").readBytes())
    }

    @Test fun unavailableKeyAllowsOnlyScopedOfflineDeletion() {
        val storage = storage()
        storage.writeBlob("alpha", byteArrayOf(1))
        storage.writeBlob("beta", byteArrayOf(2))
        val before = File(directory, "beta.bin").readBytes()
        keys.available = false
        refusal(ProviderStorageFailure.KeyUnavailable) { storage.readBlob("alpha") }
        storage.deleteBlob("alpha")
        assertFalse(File(directory, "alpha.bin").exists())
        assertArrayEquals(before, File(directory, "beta.bin").readBytes())
        keys.available = true
        assertArrayEquals(byteArrayOf(2), storage.readBlob("beta"))
    }

    @Test fun preReplaceFailurePreservesOldRecord() {
        storage().writeBlob("alpha", byteArrayOf(1))
        val before = File(directory, "alpha.bin").readBytes()
        refusal(ProviderStorageFailure.Io) {
            storage { if (it == ProviderRecordStorage.Checkpoint.AfterFileSync) throw IOException() }
                .writeBlob("alpha", byteArrayOf(2))
        }
        assertArrayEquals(before, File(directory, "alpha.bin").readBytes())
        assertEquals(listOf("alpha.bin"), directory.listFiles().orEmpty().map { it.name })
    }

    @Test fun postReplaceFailureIsAmbiguousAndReopenReadsCompleteNewRecord() {
        storage().writeBlob("alpha", byteArrayOf(1))
        refusal(ProviderStorageFailure.Io) {
            storage { if (it == ProviderRecordStorage.Checkpoint.AfterReplace) throw IOException() }
                .writeBlob("alpha", byteArrayOf(2))
        }
        assertArrayEquals(byteArrayOf(2), storage().readBlob("alpha"))
    }

    @Test fun recoveryRemovesOnlyNamedStagingAndRetainsPublishedCiphertext() {
        val storage = storage()
        storage.writeBlob("alpha", byteArrayOf(1))
        val temporary = File(directory, ".alpha-00000000-0000-0000-0000-000000000000.tmp")
        Files.createFile(temporary.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        temporary.writeBytes(byteArrayOf(9))
        storage.recoverStaging()
        assertFalse(temporary.exists())
        assertArrayEquals(byteArrayOf(1), storage.readBlob("alpha"))
        File(directory, "unowned").writeBytes(byteArrayOf(9))
        refusal(ProviderStorageFailure.Corrupt) { storage.recoverStaging() }
        assertTrue(File(directory, "unowned").isFile)
    }

    @Test fun namesBoundsAndLinksRefuseBeforeKeyCreation() {
        val storage = storage()
        for (name in listOf("", ".", "..", "../escape", "name/child", "x".repeat(129))) {
            refusal(ProviderStorageFailure.InvalidInput) { storage.writeBlob(name, byteArrayOf(1)) }
            refusal(ProviderStorageFailure.InvalidInput) { storage.deleteBlob(name) }
        }
        refusal(ProviderStorageFailure.InvalidInput) {
            storage.writeBlob("large", ByteArray(ProviderRecordStorage.MAX_PLAINTEXT_BYTES + 1))
        }
        storage.readBlob("absent") // Create only the private directory.
        val outside = File(parent, "outside").apply { writeBytes(byteArrayOf(9)) }
        Files.createSymbolicLink(File(directory, "alpha.bin").toPath(), outside.toPath())
        refusal(ProviderStorageFailure.Corrupt) { storage.readBlob("alpha") }
        refusal(ProviderStorageFailure.Corrupt) { storage.writeBlob("alpha", byteArrayOf(2)) }
        refusal(ProviderStorageFailure.Corrupt) { storage.deleteBlob("alpha") }
        assertArrayEquals(byteArrayOf(9), outside.readBytes())
        assertEquals(0, keys.creations)
    }

    private fun storage(checkpoint: (ProviderRecordStorage.Checkpoint) -> Unit = {}) =
        ProviderRecordStorage(directory, ProviderRecordCipher(keys), { dir ->
            FileChannel.open(dir.toPath(), READ).use { it.force(true) }
        }, checkpoint)

    private fun refusal(reason: ProviderStorageFailure, action: () -> Any?) {
        try { action(); fail("Expected typed provider storage failure") }
        catch (failure: ProviderStorageException) { assertEquals(reason, failure.reason) }
    }

    private class TestKeys : ProviderRecordKeys {
        var secret: SecretKey? = null
        var available = true
        var creations = 0
        override fun key(allowCreate: Boolean): SecretKey {
            if (!available) throw ProviderStorageException(ProviderStorageFailure.KeyUnavailable)
            secret?.let { return it }
            if (!allowCreate) throw ProviderStorageException(ProviderStorageFailure.KeyUnavailable)
            return SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES").also { secret = it; creations++ }
        }
    }
}
