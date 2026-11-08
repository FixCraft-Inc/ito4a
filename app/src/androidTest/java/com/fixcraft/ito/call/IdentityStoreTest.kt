package com.fixcraft.ito.call

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore
import java.util.UUID

/** The Keystore-sealed identity snapshot: publication, refusal and preservation. */
@RunWith(AndroidJUnit4::class)
class IdentityStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val alias = "ito.identity.test.${UUID.randomUUID()}"
    private lateinit var directory: File
    private var failAt: IdentityStore.Checkpoint? = null

    @Before fun setUp() {
        directory = File(context.cacheDir, "identity-${UUID.randomUUID()}")
    }

    @After fun tearDown() {
        directory.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    private fun store() = IdentityStore(directory, AndroidIdentityKeys(alias), { }) { reached ->
        if (reached == failAt) throw java.io.IOException("injected")
    }

    private fun snapshot() = File(directory, "snapshot.bin")

    @Test fun publishesReadsAndReplaces() {
        val store = store()
        assertNull(store.load())
        assertEquals(CallNative.PERSIST_COMMITTED, store.persist(byteArrayOf(1, 2, 3)))
        assertArrayEquals(byteArrayOf(1, 2, 3), store.load())
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(snapshot().toPath()))
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory.toPath()))
        assertEquals(CallNative.PERSIST_COMMITTED, store.persist(byteArrayOf(4, 5)))
        assertArrayEquals(byteArrayOf(4, 5), store.load())
    }

    @Test fun persistWipesTheCallersArray() {
        val plaintext = byteArrayOf(9, 9, 9)
        store().persist(plaintext)
        assertArrayEquals(byteArrayOf(0, 0, 0), plaintext)
    }

    @Test fun corruptCiphertextIsPreservedNeverReplaced() {
        val store = store()
        store.persist(byteArrayOf(7))
        val bytes = snapshot().readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        snapshot().writeBytes(bytes)
        assertEquals(IdentityFailure.Corrupt, failure { store.load() })
        // Old state must authenticate before a replacement is written.
        assertEquals(CallNative.PERSIST_NOT_COMMITTED, store.persist(byteArrayOf(8)))
        assertArrayEquals(bytes, snapshot().readBytes())
    }

    @Test fun missingKeyWithCiphertextIsNeverRecreated() {
        val store = store()
        store.persist(byteArrayOf(7))
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        assertEquals(IdentityFailure.KeyUnavailable, failure { store.load() })
        assertEquals(CallNative.PERSIST_NOT_COMMITTED, store.persist(byteArrayOf(8)))
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
    }

    @Test fun interruptedPublicationKeepsOneCompleteSnapshot() {
        val store = store()
        store.persist(byteArrayOf(1))
        failAt = IdentityStore.Checkpoint.AfterFileSync
        assertEquals(CallNative.PERSIST_NOT_COMMITTED, store.persist(byteArrayOf(2)))
        failAt = null
        assertArrayEquals(byteArrayOf(1), store.load())
        failAt = IdentityStore.Checkpoint.AfterReplace
        // The new snapshot may already be what storage holds.
        assertEquals(CallNative.PERSIST_AMBIGUOUS, store.persist(byteArrayOf(3)))
        failAt = null
        assertArrayEquals(byteArrayOf(3), store.load())
        assertFalse(File(directory, ".snapshot.tmp").exists())
    }

    @Test fun anotherFormatVersionIsCorrupt() {
        val store = store()
        store.persist(byteArrayOf(1))
        val bytes = snapshot().readBytes()
        bytes[4] = 2
        snapshot().writeBytes(bytes)
        assertEquals(IdentityFailure.Corrupt, failure { store.load() })
    }

    @Test fun linkedSnapshotIsRefused() {
        val store = store()
        store.persist(byteArrayOf(1))
        val target = File(directory.parentFile, "elsewhere-${UUID.randomUUID()}").also { snapshot().copyTo(it) }
        try {
            snapshot().delete()
            Files.createSymbolicLink(snapshot().toPath(), target.toPath())
            assertTrue(failure { store.load() } != null)
        } finally { target.delete() }
    }

    private fun failure(block: () -> Unit): IdentityFailure? = try { block(); null }
    catch (error: IdentityStorageException) { error.reason }
}
