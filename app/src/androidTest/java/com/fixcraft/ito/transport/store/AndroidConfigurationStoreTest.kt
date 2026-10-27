package com.fixcraft.ito.transport.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidConfigurationStoreTest {
    private lateinit var root: File
    private lateinit var alias: String
    private lateinit var cipher: AndroidSnapshotCipher
    private lateinit var store: ConfigurationRepository

    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        root = File(context.noBackupFilesDir, "store-test-$id")
        alias = "ito.storage.test.$id"
        cipher = AndroidSnapshotCipher(alias)
        store = ConfigurationRepository(root, cipher)
    }

    @After fun tearDown() {
        root.deleteRecursively()
        keyStore().deleteEntry(alias)
    }

    @Test fun realKeystorePersistsSelectedConfigurationAndCleansSessions() {
        val entry = store.importConfiguration("Manual", files())
        store.select(entry.id)
        val reopened = ConfigurationRepository(root, AndroidSnapshotCipher(alias))
        assertEquals(entry.id, reopened.list().selectedId)
        val session = reopened.materialize(entry.id)
        assertArrayEquals("{}".toByteArray(), session.config)
        assertEquals("fixture-secret", File(session.directory, "credentials/key").readText())
        session.close()
        assertFalse(session.directory.exists())
        assertTrue(session.config.all { it == 0.toByte() })
        reopened.remove(entry.id)
        assertEquals(ConfigurationCatalog(emptyList(), null), reopened.list())
    }

    @Test fun missingKeystoreKeyDoesNotCreateReplacementOrOverwriteCiphertext() {
        store.importConfiguration("Manual", files())
        val before = File(root, "snapshot.bin").readBytes()
        keyStore().deleteEntry(alias)
        expect(StoreFailure.KeyUnavailable) { store.list() }
        expect(StoreFailure.KeyUnavailable) { store.importConfiguration("Second", files()) }
        assertFalse(keyStore().containsAlias(alias))
        assertArrayEquals(before, File(root, "snapshot.bin").readBytes())
    }

    @Test fun realAuthenticatedCorruptionCannotBeReplaced() {
        store.importConfiguration("Manual", files())
        val snapshot = File(root, "snapshot.bin")
        val damaged = snapshot.readBytes()
        damaged[damaged.lastIndex] = (damaged.last().toInt() xor 1).toByte()
        snapshot.writeBytes(damaged)
        expect(StoreFailure.Corrupt) { store.list() }
        expect(StoreFailure.Corrupt) { store.importConfiguration("Second", files()) }
        assertArrayEquals(damaged, snapshot.readBytes())
    }

    @Test fun restartedOwnerRemovesAbandonedPrivateStage() {
        val session = store.stage(files())
        assertTrue(session.directory.exists())
        val reopened = ConfigurationRepository(root, AndroidSnapshotCipher(alias))
        reopened.recoverStaging()
        assertFalse(session.directory.exists())
        assertTrue(reopened.list().entries.isEmpty())
    }

    private fun files() = mapOf(
        "yume.json" to "{}".toByteArray(),
        "credentials/key" to "fixture-secret".toByteArray(),
    )

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun expect(reason: StoreFailure, action: () -> Any?) {
        try {
            action()
            fail("Expected ${reason.name}")
        } catch (failure: StoreException) {
            assertEquals(reason, failure.reason)
        }
    }
}
