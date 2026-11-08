package com.fixcraft.ito.call

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fixcraft.ito.ItoApplication
import com.fixcraft.ito.transport.nativebridge.ItoNative
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** The call core through JNI in the app process, beside libyume. */
@RunWith(AndroidJUnit4::class)
class CallBoundaryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val alias = "ito.identity.test.${UUID.randomUUID()}"
    private lateinit var directory: File

    @Before fun setUp() { directory = File(context.cacheDir, "book-${UUID.randomUUID()}") }

    @After fun tearDown() {
        directory.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    private fun book() = CallBook(IdentityStore(directory, AndroidIdentityKeys(alias), { })) { 1_800_000_000 }
        .also { it.open() }

    @Test fun bothNativeLibrariesServeOneProcess() {
        // libitocall's own OpenSSL answers, and libyume's adapter is loaded too.
        assertTrue(CallNative.componentVersion().startsWith("OpenSSL 3.5.9 "))
        assertEquals(7, ItoNative.liveHandles().size)
    }

    @Test fun identityContactsAndVerificationPersist() {
        val alice = book()
        assertNull((alice.state.value as BookState.Ready).identity)
        alice.createIdentity()
        val own = (alice.state.value as BookState.Ready).identity!!
        assertTrue(own.code.startsWith("ito-contact:1:") && own.fingerprint.length == 64)
        refused(BookRefusal.OwnCode) { alice.addContact(own.code, "me") }
        refused(BookRefusal.InvalidCode) { alice.addContact("ito-contact:1:xyz", "bad") }

        val peerDirectory = File(context.cacheDir, "peer-${UUID.randomUUID()}")
        val peerAlias = "$alias.peer"
        try {
            val peer = CallBook(IdentityStore(peerDirectory, AndroidIdentityKeys(peerAlias), { })) { 1_800_000_000 }
                .also { it.open(); it.createIdentity() }
            val peerIdentity = (peer.state.value as BookState.Ready).identity!!
            val key = alice.addContact(peerIdentity.code, "Bob 😀")
            refused(BookRefusal.Exists) { alice.addContact(peerIdentity.code, "again") }
            val added = (alice.state.value as BookState.Ready).contacts.single()
            assertEquals("Bob 😀", added.name)
            assertFalse(added.verified)
            // Only the complete fingerprint verifies.
            refused(BookRefusal.NotVerifiedFingerprint) { alice.verify(key, peerIdentity.fingerprint.take(32)) }
            alice.verify(key, peerIdentity.fingerprint.chunked(4).joinToString(" ").uppercase())
            assertTrue((alice.state.value as BookState.Ready).contacts.single().verified)

            // A changed key: the old one is blocked and points at the new one.
            val replacement = CallBook(IdentityStore(File(peerDirectory, "x"), AndroidIdentityKeys("$peerAlias.2"), { })) { 1 }
                .also { it.open(); it.createIdentity() }
            val newCode = (replacement.state.value as BookState.Ready).identity!!.code
            val newKey = alice.replace(key, newCode)
            val contacts = (alice.state.value as BookState.Ready).contacts
            assertTrue(contacts.single { it.key.contentEquals(key) }.blocked)
            assertFalse(contacts.single { it.key.contentEquals(newKey) }.verified)
            refused(BookRefusal.Blocked) { alice.verify(key, peerIdentity.fingerprint) }

            // Reopening reads the same sealed state.
            val reopened = book()
            assertEquals((alice.state.value as BookState.Ready).contacts, (reopened.state.value as BookState.Ready).contacts)
        } finally {
            peerDirectory.deleteRecursively()
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keys.deleteEntry(peerAlias)
            keys.deleteEntry("$peerAlias.2")
        }
    }

    @Test fun unreadableStoreIsNeverReset() {
        val book = book()
        book.createIdentity()
        File(directory, "snapshot.bin").writeBytes(ByteArray(64))
        val reopened = CallBook(IdentityStore(directory, AndroidIdentityKeys(alias), { })) { 1 }
        reopened.open()
        assertEquals(BookState.Failed(IdentityFailure.Corrupt), reopened.state.value)
        assertEquals(64L, File(directory, "snapshot.bin").length())
    }

    @Test fun malformedInvitationIsRefusedBeforeAnyCall() {
        val book = book()
        book.createIdentity()
        try {
            book.withStore { CallNative.invitationOpen(it, ByteArray(40), ByteArray(32), "ito-call", 1_800_000_000) }
            fail("a malformed invitation opened")
        } catch (failure: CallFailure) {
            assertEquals(-5, failure.code)
        }
        try {
            book.withStore { CallNative.callCreateInitiator(it, ByteArray(32), ByteArray(32), "ito-call", 60, 1_800_000_000) }
            fail("a call to a stranger was created")
        } catch (failure: CallFailure) {
            assertEquals(-7, failure.code)
        }
    }

    @Test fun ambiguousPublicationNeverShowsReady() {
        var failAt: IdentityStore.Checkpoint? = null
        val book = CallBook(IdentityStore(directory, AndroidIdentityKeys(alias), { }) { reached ->
            if (reached == failAt) throw java.io.IOException("injected")
        }) { 1_800_000_000 }.also { it.open(); it.createIdentity() }
        val code = CallNative.contactCodeFormat(ByteArray(32) { 7 })
        // A failure after the rename leaves storage holding the new snapshot or not.
        failAt = IdentityStore.Checkpoint.AfterReplace
        refused(BookRefusal.Storage) { book.addContact(code, "peer") }
        assertEquals(BookState.Failed(IdentityFailure.Io), book.state.value)
        failAt = null
        // The core refuses every change until the store is reopened. Its memory
        // never gained the contact, so adding it again reaches the latch.
        refused(BookRefusal.Storage) { book.addContact(code, "again") }
        assertEquals(BookState.Failed(IdentityFailure.Io), book.state.value)
        book.open()
        val reopened = book.state.value as BookState.Ready
        assertNotNull(reopened.identity)
        assertEquals("peer", reopened.contacts.single().name)
    }

    @Test fun callControllerRefusesWithoutIdentityOrConnection() {
        val connections = (context.applicationContext as ItoApplication).connections
        val book = book()
        val controller = CallController(connections, book)
        controller.call(ByteArray(32) { 1 })
        assertEquals(CallPhase.Ended, controller.view.value.phase)
        assertEquals(CallStartFailure.NoIdentity, controller.view.value.startFailure)

        book.createIdentity()
        val invitation = ByteArray(64) { 9 }
        controller.answer(invitation)
        // No connected run: the attempt ends without a lane and wipes the invitation it owns.
        val deadline = System.currentTimeMillis() + 10_000
        while (controller.view.value.phase != CallPhase.Ended || invitation.any { it != 0.toByte() }) {
            assertTrue("attempt did not end: ${controller.view.value}", System.currentTimeMillis() < deadline)
            Thread.sleep(20)
        }
        assertEquals(CallStartFailure.NotConnected, controller.view.value.startFailure)
        assertFalse(controller.view.value.toString().contains("invitation"))
    }

    @Test fun aDeferredDismissKeepsTheNewAttemptsResult() {
        val connections = (context.applicationContext as ItoApplication).connections
        val book = book().also { it.createIdentity() }
        val controller = CallController(connections, book)
        val generationField = CallController::class.java.getDeclaredField("generation").apply { isAccessible = true }
        val generation = generationField.get(controller) as AtomicLong
        val attemptField = CallController::class.java.getDeclaredField("attempt").apply { isAccessible = true }
        fun awaitEnded() {
            val deadline = System.currentTimeMillis() + 10_000
            while (controller.view.value.phase != CallPhase.Ended ||
                (attemptField.get(controller) as Job?)?.isCompleted == false) {
                assertTrue("attempt did not end: ${controller.view.value}", System.currentTimeMillis() < deadline)
                Thread.sleep(20)
            }
            assertEquals(CallStartFailure.NotConnected, controller.view.value.startFailure)
        }

        controller.call(ByteArray(32) { 1 })
        awaitEnded()
        val oldAttempt = generation.get()
        controller.dismiss()
        assertEquals(CallPhase.Idle, controller.view.value.phase)

        controller.call(ByteArray(32) { 2 })
        awaitEnded()
        val current = controller.view.value
        val currentAttempt = generation.get()
        assertTrue(currentAttempt > oldAttempt)

        // Deliver the attempt-bound callback used by both lane teardown and
        // Job completion after a newer attempt has already published its end.
        // This controls delivery order without changing production scheduling.
        CallController::class.java.getDeclaredMethod("dismiss", java.lang.Long.TYPE)
            .apply { isAccessible = true }.invoke(controller, oldAttempt)
        assertSame(current, controller.view.value)
        assertEquals(currentAttempt, generation.get())

        controller.dismiss()
        assertEquals(CallPhase.Idle, controller.view.value.phase)
    }

    @Test fun invalidArgumentsBecomeTypedFailures() {
        try { CallNative.contactCodeParse("not a code"); fail() } catch (failure: CallFailure) {
            assertEquals("contact-code-parse", failure.operation)
        }
        try { CallNative.callStatus(0); fail() } catch (failure: CallFailure) {
            assertEquals(-1, failure.code)
        }
        assertNotNull(CallNative.componentVersion())
    }

    @Test fun callRunRefusesAZeroStreamAsInvalid() {
        val alice = book()
        alice.createIdentity()
        val peerDirectory = File(context.cacheDir, "peer-${UUID.randomUUID()}")
        val peerAlias = "$alias.peer"
        try {
            val peer = CallBook(IdentityStore(peerDirectory, AndroidIdentityKeys(peerAlias), { })) { 1_800_000_000 }
                .also { it.open(); it.createIdentity() }
            val peerIdentity = (peer.state.value as BookState.Ready).identity!!
            val key = alice.addContact(peerIdentity.code, "Bob")
            alice.verify(key, peerIdentity.fingerprint)
            val call = alice.withStore { store ->
                CallNative.callCreateInitiator(store, key, ByteArray(32), CallController.SERVICE, 60, 1_800_000_000)
            }
            try {
                CallNative.callRun(call, 0)
                fail("a call ran without a stream")
            } catch (failure: CallFailure) {
                // ITO_STATUS_INVALID_ARGUMENT, not an internal adapter failure.
                assertEquals(-1, failure.code)
                assertEquals("call-stream", failure.operation)
            } finally {
                CallNative.callDestroy(call)
            }
        } finally {
            peerDirectory.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(peerAlias)
        }
    }

    private fun refused(expected: BookRefusal, block: () -> Unit) {
        try { block(); fail("expected $expected") } catch (refusal: BookException) { assertEquals(expected, refusal.refusal) }
    }
}
