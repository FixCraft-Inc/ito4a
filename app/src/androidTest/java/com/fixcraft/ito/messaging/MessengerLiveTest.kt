package com.fixcraft.ito.messaging

import android.net.ConnectivityManager
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.fixcraft.ito.ItoApplication
import com.fixcraft.ito.MainActivity
import com.fixcraft.ito.R
import com.fixcraft.ito.call.BookState
import com.fixcraft.ito.call.CallBook
import com.fixcraft.ito.call.CallNative
import com.fixcraft.ito.call.IdentityStore
import com.fixcraft.ito.content.ChatRepository
import com.fixcraft.ito.content.ChatSettings
import com.fixcraft.ito.content.LocalContentRepository
import com.fixcraft.ito.transport.ConnectionPhase
import com.fixcraft.ito.transport.ManualConfiguration
import com.fixcraft.ito.transport.nativebridge.ItoNative
import com.fixcraft.ito.transport.nativebridge.SocketBinder
import com.fixcraft.ito.transport.store.ConfigurationStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresMessengerFixture

/** Two independent stores through a real yumed/mailbox; Alice uses the real app and UI. */
@RequiresMessengerFixture
@RunWith(AndroidJUnit4::class)
class MessengerLiveTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app get() = context.applicationContext as ItoApplication
    private val fixture get() = File(context.noBackupFilesDir, "m5a-fixtures")
    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 45_000_000_000L
        while (System.nanoTime() < deadline) { if (predicate()) return; Thread.sleep(50) }
        fail("Timed out: $label")
    }
    private fun click(resource: Int) = compose.onNode(hasText(context.getString(resource)) and hasClickAction()).performClick()
    private fun files(directory: File) = directory.walkTopDown().filter(File::isFile).associate {
        it.relativeTo(directory).invariantSeparatorsPath to it.readBytes()
    }

    @Test fun pairChatReceiptsOfflineRecoveryAndRemoval() {
        check(InstrumentationRegistry.getArguments().getString("m5aLive") == "true")
        check(fixture.isDirectory)
        await("configuration initialization") { app.connections.state.value.phase != ConnectionPhase.Loading }
        runBlocking { app.connections.stop().join() }
        app.callBook.open()
        if ((app.callBook.state.value as BookState.Ready).identity == null) app.callBook.createIdentity()
        app.messenger.initialize()
        app.messenger.settings(ChatSettings("Alice", true))
        val aliceKey = (app.callBook.state.value as BookState.Ready).identity!!.key.copyOf()
        val imported = files(File(fixture, "alice"))
        try {
            val config = requireNotNull(imported["yume.json"])
            assertEquals(ItoNative.CLIENT, ItoNative.checkConfig(config))
            val selected = ConfigurationStore(context).importConfiguration("Messenger fixture", ManualConfiguration.referencedFiles(config, imported))
            runBlocking { app.connections.select(selected.id).join(); app.connections.connect().join() }
        } finally { imported.values.forEach { it.fill(0) } }

        val bobBook = CallBook(IdentityStore(File(context.noBackupFilesDir, "m5a-bob-identity")), { System.currentTimeMillis() / 1000 })
        val bobKeyMaterial = ByteArray(32).also(SecureRandom()::nextBytes)
        val bobHistory = ChatRepository(File(context.noBackupFilesDir, "m5a-bob-chats"), { action -> action(bobKeyMaterial) }, {}, {}, System::currentTimeMillis)
        val bobLegacy = LocalContentRepository(File(context.noBackupFilesDir, "m5a-bob-legacy"), { action -> action(bobKeyMaterial) }, {}, {}, { System.currentTimeMillis() / 1000 })
        val bob = Messenger(bobBook, bobHistory, bobLegacy, {})
        bobBook.open(); bobBook.createIdentity(); bob.initialize(); bob.settings(ChatSettings("Bob", true))
        val bobKey = (bobBook.state.value as BookState.Ready).identity!!.key.copyOf()
        var endpoint = 0L
        var stream = 0L
        var worker: Thread? = null
        val active = AtomicBoolean(true)
        try {
            val network = requireNotNull(context.getSystemService(ConnectivityManager::class.java).activeNetwork)
            val config = File(fixture, "bob/yume.json").readBytes()
            try { endpoint = ItoNative.create(config, File(fixture, "bob").absolutePath, SocketBinder { fd ->
                if (context.getSystemService(ConnectivityManager::class.java).activeNetwork != network) false
                else { ParcelFileDescriptor.fromFd(fd).use { network.bindSocket(it.fileDescriptor) }; true }
            }) } finally { config.fill(0) }
            assertEquals(0, ItoNative.start(endpoint, 20000))
            stream = ItoNative.openCallStream(endpoint, "ito-mailbox")
            val fingerprint = ItoNative.callStreamFingerprint(stream)
            val ownedStream = stream
            worker = Thread { bob.run(ownedStream, fingerprint, "ito-mailbox") { active.get() } }.also(Thread::start)
            await("both mailbox streams") { app.messenger.state.value.online && bob.state.value.online }

            val capture = com.fixcraft.ito.ui.GalleryCapture(compose)
            capture.secureFrame("live-chat-list-empty")
            click(R.string.add_contact); click(R.string.chat_show_code)
            await("shown pairing code") { app.messenger.state.value.code != null }
            bob.scan(requireNotNull(app.messenger.state.value.code).copyOf(), true)
            await("six-digit comparison") { app.messenger.state.value.pairings.any { it.shortCode.isNotEmpty() } && bob.state.value.shortCode.isNotEmpty() }
            val pending = app.messenger.state.value.pairings.single { it.shortCode.isNotEmpty() }
            assertEquals(pending.shortCode.toString(Charsets.US_ASCII), bob.state.value.shortCode)
            capture.secureFrame("live-pairing-comparison")
            click(R.string.chat_matches)
            await("verified contacts") {
                (app.callBook.state.value as BookState.Ready).contacts.any { it.key.contentEquals(bobKey) && it.verified } &&
                    (bobBook.state.value as BookState.Ready).contacts.any { it.key.contentEquals(aliceKey) && it.verified }
            }
            UiDevice.getInstance(instrumentation).pressBack()
            capture.secureFrame("live-chat-list-paired")
            compose.onNode(hasText("Bob") and hasClickAction()).performClick()
            val composer = hasText(context.getString(R.string.chat_message)) and androidx.compose.ui.test.hasSetTextAction()
            compose.waitUntil(10_000) { compose.onAllNodes(composer).fetchSemanticsNodes().size == 1 }
            compose.onNode(composer).performTextInput("Public fixture message")
            click(R.string.chat_send)
            await("message and delivered receipt") {
                bobHistory.messages(aliceKey).useRecords { it.any { m -> !m.outgoing && m.text.toString(Charsets.UTF_8) == "Public fixture message" } } &&
                    app.chatHistory.messages(bobKey).useRecords { it.any { m -> m.outgoing && m.status >= 2 } }
            }
            bob.markRead(aliceKey)
            await("read receipt") { app.chatHistory.messages(bobKey).useRecords { it.any { m -> m.outgoing && m.status == 3 } } }
            bob.send(aliceKey, "Reply from the fixture".toByteArray())
            await("reply") { app.chatHistory.messages(bobKey).useRecords { it.any { m -> !m.outgoing && m.text.toString(Charsets.UTF_8) == "Reply from the fixture" } } }
            compose.waitUntil(10_000) { compose.onNode(hasText("Reply from the fixture")).isDisplayed() }
            compose.onNode(hasText("Reply from the fixture")).assertIsDisplayed()
            await("open chat sends read receipt") { bobHistory.messages(aliceKey).useRecords { it.any { m -> m.outgoing && m.status == 3 } } }

            runBlocking { app.connections.stop().join() }
            bob.send(aliceKey, "Held while offline".toByteArray())
            await("mailbox accepted offline item") { bobHistory.messages(aliceKey).useRecords { it.any { m -> m.outgoing && m.status >= 1 && m.text.toString(Charsets.UTF_8) == "Held while offline" } } }
            Thread.sleep(1500)
            assertEquals(ConnectionPhase.Idle, app.connections.state.value.phase)
            val selected = app.connections.state.value.catalog.selectedId
            val selectedNetwork = app.connections.state.value.selectedNetwork
            runBlocking { app.connections.connect().join() }
            await("reconnect redelivers once") { app.chatHistory.messages(bobKey).useRecords { it.count { m -> m.text.toString(Charsets.UTF_8) == "Held while offline" } == 1 } }
            assertEquals(selected, app.connections.state.value.catalog.selectedId)
            assertEquals(selectedNetwork, app.connections.state.value.selectedNetwork)
            val generation = app.connections.state.value.generation
            runBlocking { app.connections.stop(pause = false).join() }
            await("foreground replacement") { app.connections.state.value.generation > generation && app.messenger.state.value.online }
            assertEquals(selected, app.connections.state.value.catalog.selectedId)

            runBlocking { app.connections.stop().join() }
            val offlineBaseline = MessageNative.liveSessions()
            app.messenger.send(bobKey, "Queued before background cleanup".toByteArray())
            assertEquals(offlineBaseline + 1, MessageNative.liveSessions())
            // An offline session has no mailbox worker whose finally can close it.
            // Stop owns cleanup even when its Activity waiter has disappeared.
            val cleanup = app.connections.stop()
            runBlocking {
                val waiter = launch { cleanup.join() }
                waiter.cancel()
                waiter.join()
                cleanup.join()
            }
            assertEquals("Stop must release offline message sessions", offlineBaseline, MessageNative.liveSessions())
            runBlocking { app.connections.connect().join() }
            await("mailbox after offline cleanup") { app.messenger.state.value.online }

            app.messenger.removeContact(bobKey)
            assertTrue(app.chatHistory.summary(bobKey).removed)
            await("blocked inbox closed") {
                val retired = app.chatHistory.retired()
                try { retired.none { it.peer.contentEquals(bobKey) } } finally { retired.forEach { it.close() } }
            }
            assertTrue((app.callBook.state.value as BookState.Ready).contacts.single { it.key.contentEquals(bobKey) }.blocked)
            assertTrue(app.chatHistory.messages(bobKey).isEmpty())
        } finally {
            active.set(false)
            if (endpoint != 0L) ItoNative.stop(endpoint)
            worker?.join(10000)
            check(worker?.isAlive != true)
            bob.suspendAccess(); bobBook.close()
            if (stream != 0L) ItoNative.closeCallStream(stream)
            if (endpoint != 0L) ItoNative.destroy(endpoint)
            app.connections.foreground(false)
            runBlocking { app.connections.stop().join() }
            app.messenger.suspendAccess()
            bobKeyMaterial.fill(0)
        }
    }
    private fun <T> List<com.fixcraft.ito.content.ChatMessage>.useRecords(action: (List<com.fixcraft.ito.content.ChatMessage>) -> T): T =
        try { action(this) } finally { forEach { it.close() } }
}
