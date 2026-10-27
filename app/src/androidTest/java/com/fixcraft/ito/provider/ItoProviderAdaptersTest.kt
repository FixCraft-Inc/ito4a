package com.fixcraft.ito.provider

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fixcraft.ito.transport.nativebridge.ItoNative
import com.fixcraft.ito.transport.store.StoreException
import com.fixcraft.ito.transport.store.StoreFailure
import com.fixcraft.yume.provider.ProviderCodec
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Keystore, filesystem and packaged JNI, without retained credentials. */
@RunWith(AndroidJUnit4::class)
class ItoProviderAdaptersTest {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var alias: String

    @Before fun createIsolatedRoot() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        root = Files.createTempDirectory(app.noBackupFilesDir.toPath(), "provider-adapter-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))).toFile()
        context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        alias = "ito.provider.test.${UUID.randomUUID()}"
        assertArrayEquals(LongArray(7), ItoNative.liveHandles())
    }

    @After fun removeOwnedRootAndKey() {
        assertTrue(root.deleteRecursively())
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keys.deleteEntry(alias)
        assertArrayEquals(LongArray(7), ItoNative.liveHandles())
    }

    @Test fun constructorDoesNoIoAndInitializationFailureIsTypedAndLatched() {
        File(root, "ito-provider-runtime").writeText("owned obstruction")
        val backend = backend()
        val sessions = ItoProviderSessions(context)
        val adapter = ItoProviderServices(backend, sessions)
        assertFalse(backend.directory.exists())
        refused("storage") { adapter.ensureReady() }
        assertTrue(File(root, "ito-provider-runtime").delete())
        refused("storage") { adapter.readSelection() }
        refused("storage") { adapter.writeSelection("manual".toByteArray()) }
        assertFalse(adapter.isUnlocked())
        adapter.recoverStorage()
        assertTrue(adapter.ensureReady())
        assertNull(adapter.readSelection())
        assertFalse(File(root, "ito-transport").exists())
    }

    @Test fun realKeystoreNameBindingAndMissingKeyRefuseButOfflineDeleteWorks() {
        val backend = backend()
        backend.recoverStaging()
        backend.writeBlob("alpha", "nonsecret test token".toByteArray())
        val alpha = File(backend.directory, "alpha.bin")
        val beta = File(backend.directory, "beta.bin")
        Files.copy(alpha.toPath(), beta.toPath())
        try { backend.readBlob("beta"); fail("Expected name authentication failure") }
        catch (failure: ProviderStorageException) { assertEquals(ProviderStorageFailure.Corrupt, failure.reason) }
        backend.deleteBlob("beta")
        val before = alpha.readBytes()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        try { backend.ensureReady(); fail("Expected unavailable retained key") }
        catch (failure: ProviderStorageException) { assertEquals(ProviderStorageFailure.KeyUnavailable, failure.reason) }
        assertArrayEquals(before, alpha.readBytes())
        backend.deleteBlob("alpha")
        assertFalse(alpha.exists())
    }

    @Test fun corruptSelectionCannotBecomeManualOrBeOverwritten() {
        val backend = backend()
        val adapter = ItoProviderServices(backend, ItoProviderSessions(context))
        adapter.ensureReady()
        adapter.writeSelection("manual".toByteArray())
        val selection = File(backend.directory, "service-selection.bin")
        val corrupted = selection.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        selection.writeBytes(corrupted)
        refused("storage") { adapter.readSelection() }
        refused("storage") { adapter.writeSelection("manual".toByteArray()) }
        assertArrayEquals(corrupted, selection.readBytes())
        assertFalse(adapter.isUnlocked())
    }

    @Test fun providerStagingCleanupLatchesAndExplicitRecoveryPreservesManualRoot() {
        val manual = File(root, "ito-transport").apply { mkdir() }
        val unrelated = File(manual, "preserved-foundation").apply { writeText("unrelated") }
        val before = unrelated.readBytes()
        val sessions = ItoProviderSessions(context)
        sessions.recoverStaging()
        val session = sessions.stage(mapOf("yume.json" to "{}".toByteArray()))
        val oversized = File(session.directory, "owned-bound-test")
        RandomAccessFile(oversized, "rw").use { it.setLength(1024L * 1024 + 1) }
        try { session.close(); fail("Expected refused cleanup") }
        catch (failure: StoreException) { assertEquals(StoreFailure.Corrupt, failure.reason) }
        assertTrue(session.config.all { it == 0.toByte() })
        assertFalse(sessions.isReady())
        try { sessions.stage(mapOf("yume.json" to "{}".toByteArray())); fail("Expected staging latch") }
        catch (failure: StoreException) { assertEquals(StoreFailure.Io, failure.reason) }
        assertTrue(oversized.delete())
        sessions.recoverStaging()
        assertTrue(sessions.isReady())
        assertFalse(session.directory.exists())
        assertArrayEquals(before, unrelated.readBytes())
        session.close()
    }

    @Test fun malformedProviderBundleAndConfigRefuseWithNoNativeOwners() {
        val sessions = ItoProviderSessions(context)
        val adapter = ItoProviderServices(backend(), sessions)
        adapter.ensureReady()
        refused("malformed") { adapter.openSession(byteArrayOf(1)) }
        refused("malformed") { ItoProviderNative(sessions).checkClientConfig("{".toByteArray()) }
        assertArrayEquals(LongArray(7), ItoNative.liveHandles())
        assertTrue(File(root, "ito-provider-runtime/ito-transport/sessions").listFiles().orEmpty().isEmpty())
        assertFalse(File(root, "ito-transport").exists())
    }

    private fun backend(): ItoProviderBackend = ItoProviderBackend(ProviderRecordStorage(
        File(root, "records"), ProviderRecordCipher(AndroidProviderRecordKeys(alias)), { directory ->
            val descriptor = android.system.Os.open(directory.absolutePath,
                android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_NOFOLLOW or android.system.OsConstants.O_CLOEXEC, 0)
            try {
                check(android.system.OsConstants.S_ISDIR(android.system.Os.fstat(descriptor).st_mode))
                android.system.Os.fsync(descriptor)
            } finally { android.system.Os.close(descriptor) }
        },
    ))

    private fun refused(code: String, action: () -> Any?) {
        try { action(); fail("Expected typed provider refusal") }
        catch (refusal: ProviderCodec.Refusal) { assertEquals(code, refusal.code) }
    }
}
