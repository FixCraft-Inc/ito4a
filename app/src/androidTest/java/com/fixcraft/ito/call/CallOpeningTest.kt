package com.fixcraft.ito.call

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fixcraft.ito.ItoApplication
import com.fixcraft.ito.transport.CallLane
import com.fixcraft.ito.transport.ConnectionPhase
import com.fixcraft.ito.transport.ManualConfiguration
import com.fixcraft.ito.transport.nativebridge.ItoNative
import com.fixcraft.ito.transport.store.ConfigurationStore
import com.fixcraft.ito.transport.validateImportStaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/** A public-ABI server withholds acceptance until cancellation has completed. */
@RequiresI4bFixture
@RunWith(AndroidJUnit4::class)
class CallOpeningTest {
    @Test fun canceledOpenClosesItsLateStreamWithoutPublishing() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(InstrumentationRegistry.getArguments().getString("i4bOpening") == "true")
        val context = instrumentation.targetContext
        val app = context.applicationContext as ItoApplication
        val fixture = File(context.noBackupFilesDir, "opening-fixture")
        val settings = JSONObject(File(fixture, "control.json").readText())
        val connections = app.connections
        val store = ConfigurationStore(context)
        val files = File(fixture, "client").walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(File(fixture, "client")).invariantSeparatorsPath to it.readBytes() }
        var imported: String? = null
        try {
            withTimeout(10_000) { connections.state.first { it.phase == ConnectionPhase.Idle } }
            assertEquals(ItoNative.CLIENT, ItoNative.checkConfig(files.getValue("yume.json")))
            val referenced = ManualConfiguration.referencedFiles(files.getValue("yume.json"), files)
            validateImportStaging(store.stage(referenced))
            imported = store.importConfiguration("held call opening", referenced).id
            connections.select(requireNotNull(imported)).join()
            connections.connect().join()
            withTimeout(20_000) { connections.state.first { it.phase == ConnectionPhase.Connected } }
            val transportBaseline = ItoNative.liveHandles()
            Socket().use { control ->
                control.connect(InetSocketAddress("10.0.2.2", settings.getInt("port")), 5_000)
                control.soTimeout = 15_000
                val input = control.getInputStream().bufferedReader()
                val output = control.getOutputStream().bufferedWriter()
                suspend fun release() = withContext(Dispatchers.IO) {
                    output.write("accept\n")
                    output.flush()
                    assertEquals("eof", input.readLine())
                }

                // Cancellation ends the waiter while the actual native OPEN
                // remains blocked. The server accepts only after join returns.
                val uses = AtomicInteger()
                val lane = CallLane(CallController.SERVICE) { _, _, _ -> uses.incrementAndGet() }
                val waiter = launch(Dispatchers.IO) { connections.serveCall(lane) }
                awaitNativeOpen()
                waiter.cancelAndJoin()
                release()
                awaitTransportHandles(transportBaseline)
                assertEquals(0, uses.get())

                // Exercise the application owner too: dismiss before the late
                // success and retain Idle after its stream has been released.
                val book = app.callBook
                book.open()
                if ((book.state.value as BookState.Ready).identity == null) book.createIdentity()
                val coreBaseline = CallNative.liveHandles()
                val calls = app.calls
                calls.call(ByteArray(32) { 7 })
                awaitNativeOpen()
                calls.cancel()
                withTimeout(5_000) { calls.view.first { it.phase == CallPhase.Ended } }
                assertEquals(CallNative.CANCELED, calls.view.value.result)
                assertEquals(null, calls.view.value.startFailure)
                calls.dismiss()
                withTimeout(5_000) { calls.view.first { it.phase == CallPhase.Idle } }
                release()
                awaitTransportHandles(transportBaseline)
                assertEquals(CallPhase.Idle, calls.view.value.phase)
                assertTrue(coreBaseline.contentEquals(CallNative.liveHandles()))
                assertEquals(ConnectionPhase.Connected, connections.state.value.phase)
                output.write("done\n")
                output.flush()
            }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) {
                connections.stop().join()
                imported?.let { connections.remove(it).join() }
            }
            files.values.forEach { it.fill(0) }
        }
    }

    private suspend fun awaitNativeOpen() = withTimeout(5_000) {
        // A stack observation locates the real blocking JNI call. No delay
        // determines ordering, and the server has not accepted any stream yet.
        while (Thread.getAllStackTraces().values.none { frames -> frames.any {
                it.className == ItoNative::class.java.name && it.methodName == "openCallStream"
            } }) yield()
    }

    private suspend fun awaitTransportHandles(expected: LongArray) = withTimeout(5_000) {
        while (!expected.contentEquals(ItoNative.liveHandles())) yield()
    }
}
