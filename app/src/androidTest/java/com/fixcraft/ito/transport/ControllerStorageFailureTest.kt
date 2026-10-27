package com.fixcraft.ito.transport

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fixcraft.ito.transport.nativebridge.ItoNative
import com.fixcraft.ito.transport.store.ConfigurationStore
import com.fixcraft.ito.transport.store.StoreException
import com.fixcraft.ito.transport.store.StoreFailure
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real filesystem and packaged JNI failures, without transport credentials. */
@RunWith(AndroidJUnit4::class)
class ControllerStorageFailureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var directory: File
    private lateinit var isolatedContext: Context

    @Before fun createIsolatedNoBackupRoot() {
        directory = Files.createTempDirectory(
            instrumentation.targetContext.noBackupFilesDir.toPath(), "controller-storage-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
        ).toFile()
        isolatedContext = object : ContextWrapper(instrumentation.targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
        assertArrayEquals(LongArray(7), ItoNative.liveHandles())
    }

    @After fun removeOwnedNonsecretTestFiles() {
        assertTrue(directory.deleteRecursively())
        assertArrayEquals(LongArray(7), ItoNative.liveHandles())
    }

    @Test fun corruptStartupBlocksCommandsAndImportUntilExplicitRecovery() {
        val root = File(directory, "ito-transport")
        Files.createDirectory(root.toPath(), PosixFilePermissions.asFileAttribute(
            PosixFilePermissions.fromString("rwx------")))
        val snapshot = File(root, "snapshot.bin").apply { writeText("invalid snapshot") }
        val before = snapshot.readBytes()
        val controller = newController()
        await { controller.state.value.phase == ConnectionPhase.Failed }
        assertStorageFailure(controller)
        blockedCommands(controller)
        assertArrayEquals(before, snapshot.readBytes())
        assertTrue(snapshot.delete())
        // Correcting the filesystem alone must not clear a latched failure.
        blockedCommands(controller)
        join(controller.refresh())
        assertEquals(ConnectionPhase.Idle, controller.state.value.phase)
        assertEquals(ConnectionFailure.None, controller.state.value.failure)
        assertArrayEquals(LongArray(7), ItoNative.liveHandles())
    }

    @Test fun runtimeStorageFailureLatchesEvenAfterTheFileIsCorrected() {
        val controller = newController()
        await { controller.state.value.phase == ConnectionPhase.Idle }
        val snapshot = File(directory, "ito-transport/snapshot.bin").apply { writeText("invalid snapshot") }
        join(controller.select("not-a-configuration"))
        assertStorageFailure(controller)
        assertTrue(snapshot.delete())
        blockedCommands(controller)
        join(controller.refresh())
        assertEquals(ConnectionPhase.Idle, controller.state.value.phase)
        assertTrue(controller.state.value.catalog.entries.isEmpty())
    }

    @Test fun stagedCleanupErrorOutranksNativeValidationFailureAndCanBeRecovered() {
        val store = ConfigurationStore(isolatedContext)
        store.recoverStaging()
        val session = store.stage(mapOf("yume.json" to "{}".toByteArray()))
        // A real file outside the documented material bound makes cleanup fail
        // closed. The native parser also refuses the staged empty object.
        val oversized = File(session.directory, "oversized-test-file")
        RandomAccessFile(oversized, "rw").use { it.setLength(1024L * 1024 + 1) }
        try {
            validateImportStaging(session)
            fail("Expected typed storage cleanup failure")
        } catch (failure: StoreException) {
            assertEquals(StoreFailure.Corrupt, failure.reason)
            assertTrue(session.config.all { it == 0.toByte() })
            assertArrayEquals(LongArray(7), ItoNative.liveHandles())
        }
        assertTrue(oversized.delete())
        store.recoverStaging()
        assertFalse(session.directory.exists())
        assertTrue(store.list().entries.isEmpty())
        session.close()
    }

    private fun newController(): ConnectionController {
        lateinit var controller: ConnectionController
        instrumentation.runOnMainSync { controller = ConnectionController(isolatedContext) }
        return controller
    }

    private fun blockedCommands(controller: ConnectionController) {
        join(controller.connect())
        assertStorageFailure(controller)
        join(controller.selectNetwork(Long.MAX_VALUE))
        assertStorageFailure(controller)
        assertNull(controller.state.value.selectedNetwork)
        instrumentation.runOnMainSync { controller.probe(false, "", "", 0) }
        assertStorageFailure(controller)
        instrumentation.runOnMainSync { controller.probe(false, "", "", 0) }
        assertStorageFailure(controller)
        val code = "0000000000000000000000000".toCharArray()
        instrumentation.runOnMainSync {
            controller.importDocument(Uri.parse("content://ito.storage-test/absent"), "Refused", code)
        }
        await { code.all { it == '\u0000' } }
        assertStorageFailure(controller)
        assertTrue(controller.state.value.catalog.entries.isEmpty())
        assertArrayEquals(LongArray(7), ItoNative.liveHandles())
        assertTrue(File(directory, "ito-transport/sessions").listFiles().orEmpty().isEmpty())
    }

    private fun assertStorageFailure(controller: ConnectionController) {
        assertEquals(ConnectionPhase.Failed, controller.state.value.phase)
        assertEquals(ConnectionFailure.Storage, controller.state.value.failure)
        assertEquals(0, controller.state.value.nativeCode)
        assertArrayEquals(LongArray(7), ItoNative.liveHandles())
    }

    private fun join(job: Job) = runBlocking { withTimeout(10_000) { job.join() } }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
        assertTrue("Bounded controller state did not settle", condition())
    }
}
