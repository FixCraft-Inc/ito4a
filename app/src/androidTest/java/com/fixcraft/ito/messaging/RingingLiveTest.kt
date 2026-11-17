package com.fixcraft.ito.messaging

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.fixcraft.ito.IncomingCallActivity
import com.fixcraft.ito.ItoApplication
import com.fixcraft.ito.MainActivity
import com.fixcraft.ito.R
import com.fixcraft.ito.call.BookState
import com.fixcraft.ito.call.CallBook
import com.fixcraft.ito.call.CallController
import com.fixcraft.ito.call.CallNative
import com.fixcraft.ito.call.CallPhase
import com.fixcraft.ito.call.IdentityStore
import com.fixcraft.ito.content.ChatRepository
import com.fixcraft.ito.content.LocalContentRepository
import com.fixcraft.ito.data.PreferencesStore
import com.fixcraft.ito.security.AppLockedException
import com.fixcraft.ito.transport.ConnectionPhase
import com.fixcraft.ito.transport.ManualConfiguration
import com.fixcraft.ito.transport.nativebridge.ItoNative
import com.fixcraft.ito.transport.nativebridge.SocketBinder
import com.fixcraft.ito.transport.store.ConfigurationStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Actual composite peer proofs, mailbox delivery, service ownership and local acceptance before JOIN. */
@RequiresMessengerFixture
@RunWith(AndroidJUnit4::class)
class RingingLiveTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app get() = context.applicationContext as ItoApplication
    private val fixture get() = File(context.noBackupFilesDir, "m5a-fixtures")
    private fun trace(label: String) { android.util.Log.i("M5bJourney", label) }
    private fun await(label: String, seconds: Long = 45, condition: () -> Boolean) {
        val end = System.nanoTime() + seconds * 1_000_000_000L
        while (System.nanoTime() < end) { if (condition()) { trace(label); return }; Thread.sleep(50) }
        fail("Timed out: $label")
    }
    private fun click(id: Int) {
        trace("before UI action")
        compose.onNode(hasText(context.getString(id)) and hasClickAction()).performClick()
        trace("after UI action")
    }
    private fun foreground() {
        instrumentation.runOnMainSync {
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
        await("visible application") { resumed(MainActivity::class.java) }
    }

    private fun resumed(type: Class<out android.app.Activity>): Boolean {
        var found = false
        instrumentation.runOnMainSync {
            found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any(type::isInstance)
        }
        return found
    }

    @Test fun authenticatedRingsAcceptDeclineCorrelateAndRespectBackgroundOptIn() {
        check(InstrumentationRegistry.getArguments().getString("m5bLive") == "true")
        check(fixture.isDirectory)
        await("connection initialized") { app.connections.state.value.phase != ConnectionPhase.Loading }
        runBlocking { app.connections.stop().join() }
        app.callBook.open()
        if ((app.callBook.state.value as BookState.Ready).identity == null) app.callBook.createIdentity()
        app.messenger.initialize()
        val aliceKey = (app.callBook.state.value as BookState.Ready).identity!!.key.copyOf()
        val imported = File(fixture, "alice").walkTopDown().filter(File::isFile).associate {
            it.relativeTo(File(fixture, "alice")).invariantSeparatorsPath to it.readBytes()
        }
        try {
            val config = requireNotNull(imported["yume.json"])
            val entry = ConfigurationStore(context).importConfiguration("Ring fixture", ManualConfiguration.referencedFiles(config, imported))
            runBlocking { app.connections.select(entry.id).join(); app.connections.connect().join() }
        } finally { imported.values.forEach { it.fill(0) } }
        val book = CallBook(IdentityStore(File(context.noBackupFilesDir, "m5b-bob-identity")), { System.currentTimeMillis() / 1000 })
        val material = ByteArray(32).also(SecureRandom()::nextBytes)
        val access = AtomicBoolean(true)
        val arrivals = LinkedBlockingQueue<Long>()
        val sealedActivity = java.util.concurrent.atomic.AtomicInteger()
        val history = ChatRepository(File(context.noBackupFilesDir, "m5b-bob-chats"), { it(material) }, {}, {}, System::currentTimeMillis)
        val legacy = LocalContentRepository(File(context.noBackupFilesDir, "m5b-bob-legacy"), { it(material) }, {}, {}, { System.currentTimeMillis() / 1000 })
        val bob = Messenger(book, history, legacy, { if (!access.get()) throw AppLockedException() },
            hasAccess = access::get, onRing = { handle, _ -> arrivals.add(handle) }, onSealedActivity = { sealedActivity.incrementAndGet() })
        book.open(); book.createIdentity(); bob.initialize()
        val bobKey = (book.state.value as BookState.Ready).identity!!.key.copyOf()
        val active = AtomicBoolean(true)
        var endpoint = 0L; var mailbox = 0L; var worker: Thread? = null
        val attempts = mutableListOf<Attempt>()
        var completed = false
        try {
            val network = requireNotNull(context.getSystemService(ConnectivityManager::class.java).activeNetwork)
            val config = File(fixture, "bob/yume.json").readBytes()
            try { endpoint = ItoNative.create(config, File(fixture, "bob").absolutePath, SocketBinder { fd ->
                ParcelFileDescriptor.fromFd(fd).use { network.bindSocket(it.fileDescriptor) }; true
            }) } finally { config.fill(0) }
            assertEquals(0, ItoNative.start(endpoint, 20000))
            mailbox = ItoNative.openCallStream(endpoint, "ito-mailbox")
            val fingerprint = ItoNative.callStreamFingerprint(mailbox)
            val owned = mailbox
            worker = Thread { bob.run(owned, fingerprint, "ito-mailbox") { active.get() } }.also(Thread::start)
            await("both mailbox workers") { app.messenger.state.value.online && bob.state.value.online }
            Thread.sleep(6000)
            assertTrue("A new installation stays connected beyond the first-frame deadline",
                app.messenger.state.value.online && bob.state.value.online)
            app.messenger.createCode(false)
            bob.scan(requireNotNull(app.messenger.state.value.code).copyOf(), true)
            await("pairing proof") { app.messenger.state.value.pairings.any { it.shortCode.isNotEmpty() } }
            app.messenger.decide(app.messenger.state.value.pairings.single { it.shortCode.isNotEmpty() }.id, true)
            await("both verified") { (book.state.value as BookState.Ready).contacts.any { it.verified } &&
                (app.callBook.state.value as BookState.Ready).contacts.any { it.key.contentEquals(bobKey) && it.verified } }
            app.messenger.hideCode()
            val capture = com.fixcraft.ito.ui.GalleryCapture(compose)
            val manager = context.getSystemService(NotificationManager::class.java)
            fun incomingNotification(): Notification {
                val token = requireNotNull(app.ringing.incoming.value).actionToken
                val intent = Intent(context, IncomingCallActivity::class.java)
                    .setData(Uri.parse("ito-incoming:$token"))
                var notification: Notification? = null
                // Publication follows the invitation state on another coroutine.
                // Wait for this token so an older notification cannot satisfy the check.
                await("notification for pending invitation") {
                    val action = PendingIntent.getActivity(context, 22, intent,
                        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                    notification = manager.activeNotifications.singleOrNull {
                        it.notification.category == Notification.CATEGORY_CALL && it.notification.contentIntent == action
                    }?.notification
                    notification != null
                }
                return requireNotNull(notification)
            }
            fun ring(expectIncoming: Boolean = true): Attempt {
                val stream = ItoNative.openCallStream(endpoint, "ito-call")
                val call = book.withStore { CallNative.callCreateInitiator(it, aliceKey, fingerprint, "ito-call", 90,
                    System.currentTimeMillis() / 1000, 3) }
                val attempt = Attempt(call, stream).also { attempts.add(it); it.start() }
                await("room reservation") { CallNative.callStatus(call).has(CallNative.FLAG_INVITATION_READY) }
                val invitation = requireNotNull(CallNative.callInvitation(call))
                try { attempt.id = RingToken.outgoing(invitation); bob.sendRing(aliceKey, invitation) }
                finally { invitation.fill(0) }
                if (expectIncoming) await("authenticated incoming screen") { app.ringing.incoming.value != null }
                return attempt
            }
            val baseline = CallNative.liveHandles()
            val first = ring()
            assertEquals("No responder before acceptance", baseline[3] + 1, CallNative.liveHandles()[3])
            assertEquals(baseline[2] + 1, CallNative.liveHandles()[2])
            val staleToken = app.ringing.incoming.value!!.actionToken
            val firstNotice = incomingNotification()
            val staleDecline = firstNotice.actions.first().actionIntent
            val staleOpen = firstNotice.contentIntent
            trace("before incoming capture")
            capture.secureFrame("incoming-authenticated")
            trace("after incoming capture")
            click(R.string.decline)
            await("decline releases invitation") { app.ringing.incoming.value == null && CallNative.liveHandles()[2] == baseline[2] }
            first.close()

            // Each native store permits one active call. Alice's outgoing call
            // and Bob's independent incoming attempt exercise real busy admission.
            compose.runOnIdle { app.ringing.call(bobKey) }
            await("outgoing invitation while testing busy") { arrivals.isNotEmpty() }
            CallNative.invitationDestroy(arrivals.remove())
            val busy = ring(expectIncoming = false)
            await("simultaneous ring receives busy") { history.read(aliceKey) { state ->
                state.calls.any { it.id.contentEquals(busy.id.copyOfRange(0, 16)) && it.result == -1103 }
            } }
            assertNull("Busy does not publish an incoming invitation", app.ringing.incoming.value)
            assertEquals(baseline[2], CallNative.liveHandles()[2])
            busy.close()
            app.calls.cancel()
            await("busy-control caller closes") { app.calls.view.value.phase == CallPhase.Ended }
            compose.runOnIdle { app.calls.dismiss() }
            await("busy-control lane released") { app.calls.view.value.phase == CallPhase.Idle }

            val second = ring()
            staleDecline.send()
            staleOpen.send()
            await("stale screen returns to the original activity", seconds = 5) { resumed(MainActivity::class.java) }
            app.ringing.accept(staleToken)
            app.ringing.decline(staleToken)
            bob.endRing(aliceKey, ByteArray(32) { 99 }, RingToken.CANCELED)
            Thread.sleep(1000)
            assertNotNull("Stale UI actions and unrelated ends cannot decide another invitation", app.ringing.incoming.value)
            bob.endRing(aliceKey, second.id, RingToken.CANCELED)
            await("matching end") { app.ringing.incoming.value == null }
            second.close()

            val accepted = ring()
            incomingNotification().contentIntent.send()
            await("isolated incoming activity") { resumed(IncomingCallActivity::class.java) }
            capture.secureFrame("incoming-isolated-activity")
            Thread.sleep(1000)
            assertNotNull("Opening the notification is not acceptance", app.ringing.incoming.value)
            assertEquals("Opening the call window creates no responder", baseline[3] + 1, CallNative.liveHandles()[3])
            assertFalse("Opening the call window does not authenticate the call",
                CallNative.callStatus(accepted.handle).has(CallNative.FLAG_PEER_AUTHENTICATED))
            click(R.string.accept)
            await("both composite proofs and media authorization") {
                app.calls.view.value.phase == CallPhase.Media && app.calls.view.value.peerAuthenticated &&
                    CallNative.callStatus(accepted.handle).has(CallNative.FLAG_PEER_AUTHENTICATED)
            }
            accepted.close()
            await("accepted call ends and saves history") { app.calls.view.value.phase == CallPhase.Ended && app.calls.view.value.historySaved }
            compose.runOnIdle { app.calls.dismiss() }
            await("call lane released") { app.calls.view.value.phase == CallPhase.Idle }

            val timed = ring()
            // The 45-second protocol budget needs a later observation window
            // for the timer callback and the next framework-thread sample.
            // State publication precedes native destruction on the serialized owner.
            await("unanswered invitation expires and releases its handle", seconds = 50) {
                app.ringing.incoming.value == null && CallNative.liveHandles()[2] == baseline[2]
            }
            timed.close()
            val hidden = ring()
            UiDevice.getInstance(instrumentation).pressHome()
            await("background without opt-in cancels pending invitation") { app.ringing.incoming.value == null &&
                app.connections.state.value.phase == ConnectionPhase.Idle }
            hidden.close()
            foreground()
            await("foreground reconnect") { app.messenger.state.value.online }

            assertTrue(app.notifications.allowed())
            runBlocking { PreferencesStore(context).update { it.copy(backgroundMessages = true) } }
            await("foreground connection service") { app.backgroundForeground.value }
            UiDevice.getInstance(instrumentation).pressHome()
            await("hidden app retains selected connection") { !app.appLock.isForeground() && app.messenger.state.value.online }
            bob.send(aliceKey, "Background fixture text".toByteArray())
            await("background message notification") { manager.activeNotifications.any { it.notification.extras.getCharSequence("android.text")?.toString() == "Background fixture text" } }
            val backgroundRing = ring()
            val backgroundNotice = incomingNotification()
            assertEquals("Full-screen access refusal retains the notification fallback",
                app.notifications.fullScreenAllowed(), backgroundNotice.fullScreenIntent != null)
            backgroundNotice.contentIntent.send()
            await("notification fallback opens only the incoming window") { resumed(IncomingCallActivity::class.java) }
            capture.secureFrame("incoming-from-background")
            click(R.string.decline)
            await("background ring refused") { app.ringing.incoming.value == null }
            backgroundRing.close()
            foreground()

            // Bob's key-access gate closes while its preexisting mailbox stream stays open.
            // A class-2 hint cannot expose an invitation or authenticate a caller until unlock.
            access.set(false)
            Thread.sleep(500)
            compose.runOnIdle { app.ringing.call(bobKey) }
            await("sealed activity while locked") { sealedActivity.get() > 0 }
            assertTrue("No invitation published while keys are locked", arrivals.isEmpty())
            access.set(true)
            await("unlock authenticates queued ring") { arrivals.isNotEmpty() }
            val received = arrivals.remove()
            // A tap queued before lock may arrive after access was revoked.
            // Neither that tap nor a stale token is consent to decline a ring.
            val consentAccess = AtomicBoolean(false)
            val gated = Ringing(CallController(app.connections, book, profile = 3), bob,
                { if (!consentAccess.get()) throw AppLockedException() })
            gated.offer(received, aliceKey)
            await("pending invitation at consent gate") { gated.incoming.value != null }
            val token = gated.incoming.value!!.actionToken
            gated.accept(staleToken); gated.accept(token)
            Thread.sleep(500)
            assertEquals("Denied acceptance retains the exact pending invitation", token, gated.incoming.value?.actionToken)
            consentAccess.set(true)
            gated.decline(token)
            await("explicit decline after denied acceptance") { gated.incoming.value == null }
            await("outgoing ring canceled by matching peer") { app.calls.view.value.phase == CallPhase.Ended }
            compose.runOnIdle { app.calls.dismiss() }
            await("outgoing lane released") { app.calls.view.value.phase == CallPhase.Idle }
            // A failed native receive-ring save must publish no invitation and remain retryable.
            active.set(false); worker.join(10000); check(!worker.isAlive)
            ItoNative.closeCallStream(mailbox); mailbox = 0
            bob.suspendAccess()
            val snapshot = requireNotNull(history.session(aliceKey))
            var save = false
            val session = try { book.withStore { store -> MessageNative.openSession(store, aliceKey, snapshot,
                com.fixcraft.ito.call.SnapshotPersistence { bytes ->
                    if (!save) CallNative.PERSIST_NOT_COMMITTED
                    else { history.saveSession(aliceKey, bytes); CallNative.PERSIST_COMMITTED }
                }, System.currentTimeMillis()) } } finally { snapshot.fill(0) }
            val receiveStream = ItoNative.openCallStream(endpoint, "ito-mailbox")
            try {
                MailboxClient({ size, wait -> MessageNative.read(receiveStream, size, wait) },
                    { MessageNative.write(receiveStream, it, 5000) }, { true }).use { client ->
                    val inboxes = book.withStore(MessageNative::inboxes)
                    try { client.open(inboxes.map { it.secret }) } finally { inboxes.forEach(Inbox::close) }
                    compose.runOnIdle { app.ringing.call(bobKey) }
                    var sealed: MailboxItem? = null
                    await("raw sealed ring") { if (sealed == null) sealed = client.poll(); sealed != null }
                    requireNotNull(sealed).use { item ->
                        val count = CallNative.liveHandles()[2]
                        try {
                            MessageNative.receiveRing(session, item.sealed, fingerprint, "ito-call", System.currentTimeMillis())
                            fail("A refused session save published an invitation")
                        } catch (failure: com.fixcraft.ito.call.CallFailure) { assertEquals(-15, failure.code) }
                        assertEquals(count, CallNative.liveHandles()[2])
                        save = true
                        val invitation = MessageNative.receiveRing(session, item.sealed, fingerprint, "ito-call", System.currentTimeMillis())
                        assertTrue(invitation != 0L)
                        CallNative.invitationDestroy(invitation)
                        client.taken(item.id)
                    }
                }
            } finally { MessageNative.closeSession(session); ItoNative.closeCallStream(receiveStream); app.calls.cancel() }
            await("storage-control caller closes") { app.calls.view.value.phase == CallPhase.Ended }

            runBlocking { PreferencesStore(context).update { it.copy(backgroundMessages = false) } }
            await("service opt-out") { !app.backgroundForeground.value }
            UiDevice.getInstance(instrumentation).pressHome()
            await("opt-out closes ordinary background connection") { app.connections.state.value.phase == ConnectionPhase.Idle }
            assertEquals(baseline[2], CallNative.liveHandles()[2])
            foreground()
            completed = true
        } finally {
            attempts.forEach { it.close() }
            app.ringing.stop()
            runBlocking { PreferencesStore(context).update { it.copy(backgroundMessages = false) }; app.connections.stop().join() }
            active.set(false)
            if (endpoint != 0L) ItoNative.stop(endpoint)
            worker?.join(10000); check(worker?.isAlive != true)
            while (arrivals.isNotEmpty()) CallNative.invitationDestroy(arrivals.remove())
            bob.suspendAccess(); book.close()
            if (mailbox != 0L) ItoNative.closeCallStream(mailbox)
            if (endpoint != 0L) ItoNative.destroy(endpoint)
            try {
                if (completed && InstrumentationRegistry.getArguments().getString("m5bHostExport") == "true") {
                    // Only this synthetic Bob fixture moves to the Linux CLI.
                    // A standalone phone process then receives without instrumentation.
                    val directory = File(context.noBackupFilesDir, "m5b-host")
                    check(directory.mkdir())
                    val sessions = File(directory, "sessions")
                    check(sessions.mkdir())
                    val identity = requireNotNull(IdentityStore(File(context.noBackupFilesDir, "m5b-bob-identity")).load())
                    try { File(directory, "snapshot").writeBytes(identity) } finally { identity.fill(0) }
                    val session = requireNotNull(history.session(aliceKey))
                    try { File(sessions, aliceKey.joinToString("") { "%02x".format(it.toInt() and 255) }).writeBytes(session) }
                    finally { session.fill(0) }
                    File(directory, "peer-code").writeText(CallNative.contactCodeFormat(aliceKey))
                    runBlocking { PreferencesStore(context).update { it.copy(backgroundMessages = true) } }
                }
            } finally { material.fill(0) }
        }
    }
    private class Attempt(val handle: Long, private val stream: Long) : AutoCloseable {
        var id = byteArrayOf()
        private var closed = false
        private val thread = Thread { CallNative.callRun(handle, stream) }
        fun start() = thread.start()
        override fun close() {
            if (closed) return
            CallNative.callCancel(handle); thread.join(10000); check(!thread.isAlive)
            CallNative.callDestroy(handle); ItoNative.closeCallStream(stream)
            closed = true
        }
    }
}
