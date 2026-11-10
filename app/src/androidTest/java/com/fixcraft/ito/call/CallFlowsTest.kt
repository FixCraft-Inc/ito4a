package com.fixcraft.ito.call

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.DarkMode
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.then
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.fixcraft.ito.ItoApplication
import com.fixcraft.ito.MainActivity
import com.fixcraft.ito.R
import com.fixcraft.ito.ui.CallsModel
import com.fixcraft.ito.ui.CallsPage
import com.fixcraft.ito.ui.ItoTheme
import com.fixcraft.ito.ui.QrCameraChoice
import com.fixcraft.ito.ui.QrCode
import com.fixcraft.ito.ui.QrCodec
import com.fixcraft.ito.ui.SecureWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Call screens on the app's own book, driven only through the UI: identity,
 * contact code, adding and verifying a contact, a changed key, scanning with
 * the camera permission requested at scan time, the secure invitation code,
 * and the checks for dark theme, large text, keyboard and screen-reader
 * labels. A fresh install is assumed: the worker installs without grants.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class CallFlowsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app get() = context.applicationContext as ItoApplication
    private val device = UiDevice.getInstance(instrumentation)
    private val screens = File(context.getExternalFilesDir(null), "wp4-screens").apply { mkdirs() }

    @Test fun a_identityContactsVerificationAndKeyChange() {
        val book = app.callBook
        await { book.state.value is BookState.Ready }
        assertTrue("the test needs a fresh install", (book.state.value as BookState.Ready).identity == null)
        // The explanation's first line stays on screen. The rest opens from its info button.
        compose.waitUntil(5000) { exists(hasText(text(R.string.create_identity_summary))) }
        assertFalse(exists(hasText(text(R.string.create_identity_description))))
        val info = context.getString(R.string.more_about, text(R.string.create_identity_title))
        compose.onNode(hasContentDescription(info) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5000) { exists(hasText(text(R.string.create_identity_description))) }
        // The dialog is its own window, outside the page's scroll container.
        compose.onNode(hasText(text(R.string.done)) and hasClickAction() and hasAnyAncestor(isDialog()))
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertFalse(exists(hasText(text(R.string.create_identity_description))))
        click(text(R.string.create_identity))
        waitForContacts()
        val own = (book.state.value as BookState.Ready).identity!!
        assertTrue("the application window must stay secure", secure())
        snapshot("home-light")
        scrollTo(hasContentDescription(text(R.string.your_contact_code_qr)))
        assertTrue(exists(hasContentDescription(text(R.string.your_contact_code_qr))))
        assertTrue(exists(hasText(own.code)))
        assertTrue(exists(hasText(grouped(own.fingerprint))))
        snapshot("contact-code-light")

        withPeer { peer, replacement ->
            // A refusal the screen explains, with nothing added.
            addThroughScreen("Me", own.code)
            compose.waitUntil(5000) { exists(hasText(text(R.string.refusal_own_code))) }
            assertTrue((book.state.value as BookState.Ready).contacts.isEmpty())
            click(text(R.string.cancel))

            addThroughScreen("Rin", peer.code)
            compose.waitUntil(5000) { exists(hasText(text(R.string.trust_unverified))) }
            assertTrue(exists(hasText(grouped(peer.fingerprint))))
            assertFalse("an unverified contact offers a call", exists(hasText(text(R.string.call_contact)) and hasClickAction()))
            snapshot("contact-unverified")
            click(text(R.string.fingerprints_match))
            compose.waitUntil(5000) { exists(hasText(text(R.string.trust_verified))) }
            assertTrue(contact(peer.code).verified)

            // Without a connection a call cannot start, and the contact's screen says why.
            compose.waitUntil(5000) { exists(hasText(text(R.string.call_needs_connection))) }
            assertTrue(exists(hasText(text(R.string.call_contact)) and isNotEnabled()))
            assertTrue(exists(hasText(text(R.string.open_service)) and hasClickAction()))

            // The contact's key changed: the old key is blocked, the new one starts unverified.
            click(text(R.string.key_changed))
            compose.onNode(hasText(text(R.string.new_contact_code)) and hasSetTextAction()).performTextReplacement(replacement.code)
            click(text(R.string.replace_key))
            compose.waitUntil(5000) { exists(hasText(text(R.string.key_new_notice))) }
            assertTrue(contact(peer.code).blocked)
            assertFalse(contact(replacement.code).verified)
            snapshot("contact-new-key")
            // The top bar's Back returns to the list, where the old key reads as replaced.
            compose.onNode(hasContentDescription(text(R.string.back)) and hasClickAction()).performClick()
            compose.waitUntil(5000) { exists(hasText(text(R.string.trust_replaced))) }
        }
    }

    @Test fun b_scanningAsksForTheCameraOnlyWhenItStarts() {
        ensureIdentity()
        assertEquals("the camera is granted before any scan", PackageManager.PERMISSION_DENIED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA))
        click(text(R.string.answer_invitation))
        compose.waitUntil(5000) { secure() }
        val allow = device.wait(Until.findObject(By.res("com.android.permissioncontroller:id/permission_allow_foreground_only_button")), 10_000)
            ?: device.wait(Until.findObject(By.textContains("While using")), 2000)
            ?: throw AssertionError("no permission prompt")
        allow.click()
        // Frames from the emulator's back camera reach the decoder.
        compose.waitUntil(20_000) {
            exists(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.camera_scanning)))
        }
        // Registering reports the current state first, so the scanner's camera
        // shows as unavailable, then as available once the scanner released it.
        val manager = context.getSystemService(CameraManager::class.java)
        val scanned = requireNotNull(QrCameraChoice.select(manager)).id
        val busy = CountDownLatch(1)
        val released = CountDownLatch(1)
        val watcher = object : CameraManager.AvailabilityCallback() {
            override fun onCameraUnavailable(cameraId: String) { if (cameraId == scanned) busy.countDown() }
            override fun onCameraAvailable(cameraId: String) { if (cameraId == scanned && busy.count == 0L) released.countDown() }
        }
        manager.registerAvailabilityCallback(watcher, Handler(Looper.getMainLooper()))
        try {
            assertTrue("the scanner's camera was not open", busy.await(5, TimeUnit.SECONDS))
            click(text(R.string.cancel))
            assertTrue("the camera stayed open", released.await(10, TimeUnit.SECONDS))
        } finally {
            manager.unregisterAvailabilityCallback(watcher)
        }
        waitForContacts()
        assertTrue("leaving the scanner must preserve the application's secure window", secure())
    }

    @Test fun c_invitationCodeIsSecureAndScannable() {
        val invitation = ByteArray(331) { (it * 13 + 5).toByte() }
        val text = InvitationText.encode(invitation)
        var showing by mutableStateOf(true)
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                ItoTheme { if (showing) { SecureWindow(); QrCode(text, "invitation") } }
            }
        }
        compose.waitUntil(5000) { secure() }
        val image = compose.onNode(hasContentDescription("invitation")).captureToImage().asAndroidBitmap()
        assertEquals(text, decode(image))
        compose.runOnIdle { showing = false }
        compose.waitUntil(5000) { !exists(hasContentDescription("invitation")) }
        assertTrue("disposing the invitation must preserve the application's secure window", secure())
        invitation.fill(0)
    }

    @Test fun d_darkThemeLargeTextAndScreenReaderLabels() {
        ensureIdentity()
        val model = CallsModel(app)
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                DeviceConfigurationOverride(DeviceConfigurationOverride.DarkMode(true) then DeviceConfigurationOverride.FontScale(2f)) {
                    // The app's Scaffold paints the theme's background behind the page.
                    ItoTheme { Surface(color = MaterialTheme.colorScheme.background) { CallsPage(model) {} } }
                }
            }
        }
        waitForContacts()
        snapshot("home-dark-2x")
        // Everything that can be activated has a spoken label, and the
        // page's sections are headings.
        val actions = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue(actions.isNotEmpty())
        for (node in actions) {
            // The merged tree is what TalkBack reads for each focusable action.
            val spoken = node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString() +
                node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.joinToString()
            assertTrue("an action without a label: ${node.config}", spoken.isNotBlank())
        }
        assertTrue(compose.onAllNodes(isHeading()).fetchSemanticsNodes().size >= 2)
        // The primary action and collapsed public code remain reachable at 2x.
        scrollTo(hasText(text(R.string.answer_invitation)))
        snapshot("home-dark-2x-end")
        click(text(R.string.show_contact_code))
        scrollTo(hasContentDescription(text(R.string.your_contact_code_qr)))
        assertTrue(exists(hasText((app.callBook.state.value as BookState.Ready).identity!!.code)))
        snapshot("contact-code-dark-2x")
    }

    @Test fun e_keyboardReachesAndActivatesActions() {
        ensureIdentity()
        compose.onNode(hasText(text(R.string.calls)) and isSelectable()).performClick()
        val target = hasText(text(R.string.add_contact)) and hasClickAction()
        compose.onNode(hasScrollAction()).performScrollToNode(target)
        // Real key events from the emulator's hardware keyboard, through the window.
        var focused = false
        for (step in 1..60) {
            device.pressKeyCode(KeyEvent.KEYCODE_TAB)
            compose.waitForIdle()
            if (exists(target and SemanticsMatcher.expectValue(SemanticsProperties.Focused, true))) { focused = true; break }
        }
        assertTrue("Tab never reached Add contact", focused)
        device.pressKeyCode(KeyEvent.KEYCODE_ENTER)
        compose.waitUntil(5000) { exists(hasText(text(R.string.contact_name)) and hasSetTextAction()) }
        click(text(R.string.cancel))
    }

    @Test fun f_scannerReleasesTheCameraOnFastCancelAndWhenLeaving() {
        ensureIdentity()
        assertEquals("b_ grants the camera", PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA))
        val manager = context.getSystemService(CameraManager::class.java)
        val scanned = requireNotNull(QrCameraChoice.select(manager)).id
        val available = AtomicReference<Boolean?>(null)
        val watcher = object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(cameraId: String) { if (cameraId == scanned) available.set(true) }
            override fun onCameraUnavailable(cameraId: String) { if (cameraId == scanned) available.set(false) }
        }
        manager.registerAvailabilityCallback(watcher, Handler(Looper.getMainLooper()))
        try {
            // Cancel while the camera may still be opening. A late open must
            // still close: after the scanner settles, the camera is free.
            click(text(R.string.answer_invitation))
            click(text(R.string.cancel))
            Thread.sleep(2000)
            await { available.get() == true }
            // Leaving the app ends the scan and releases a running camera.
            click(text(R.string.answer_invitation))
            compose.waitUntil(20_000) {
                exists(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.camera_scanning)))
            }
            assertEquals(false, available.get())
            device.pressHome()
            await { available.get() == true }
        } finally {
            manager.unregisterAvailabilityCallback(watcher)
        }
    }

    private fun ensureIdentity() {
        val book = app.callBook
        await { book.state.value is BookState.Ready }
        if ((book.state.value as BookState.Ready).identity == null) book.createIdentity()
        waitForContacts()
    }

    private fun waitForContacts() {
        compose.waitUntil(10_000) { exists(hasText(text(R.string.contacts)) and isHeading()) }
    }

    private fun scrollTo(matcher: SemanticsMatcher) {
        compose.onNode(hasScrollAction()).performScrollToNode(matcher)
    }

    /** Two identities in throwaway stores: a contact, then the same contact's new key. */
    private fun withPeer(block: (OwnIdentity, OwnIdentity) -> Unit) {
        val alias = "ito.identity.test.${UUID.randomUUID()}"
        val directories = listOf(File(context.cacheDir, "peer-${UUID.randomUUID()}"), File(context.cacheDir, "peer-${UUID.randomUUID()}"))
        try {
            val identities = directories.mapIndexed { index, directory ->
                CallBook(IdentityStore(directory, AndroidIdentityKeys("$alias.$index"), { })) { 1_800_000_000 }
                    .also { it.open(); it.createIdentity() }
                    .let { (it.state.value as BookState.Ready).identity!! }
            }
            block(identities[0], identities[1])
        } finally {
            directories.forEach { it.deleteRecursively() }
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            directories.indices.forEach { keys.deleteEntry("$alias.$it") }
        }
    }

    private fun addThroughScreen(name: String, code: String) {
        click(text(R.string.add_contact))
        val field = hasText(text(R.string.contact_name)) and hasSetTextAction()
        try {
            compose.waitUntil(5000) { exists(field) }
        } catch (timeout: Throwable) {
            throw AssertionError("no name field on: " + compose.onRoot(useUnmergedTree = true).printToString(), timeout)
        }
        compose.onNode(hasText(text(R.string.contact_name)) and hasSetTextAction()).performTextReplacement(name)
        compose.onNode(hasText(text(R.string.contact_code)) and hasSetTextAction()).performTextReplacement(code)
        click(text(R.string.add))
    }

    private fun contact(code: String) = (app.callBook.state.value as BookState.Ready).contacts.single { it.code == code }

    private fun grouped(fingerprint: String) = fingerprint.chunked(4).chunked(4).joinToString("\n") { it.joinToString(" ") }

    private fun secure(): Boolean {
        var flags = 0
        instrumentation.runOnMainSync { flags = compose.activity.window.attributes.flags }
        return flags and WindowManager.LayoutParams.FLAG_SECURE != 0
    }

    private fun decode(bitmap: Bitmap): String? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val luma = ByteArray(pixels.size) { (((pixels[it] shr 16 and 255) + (pixels[it] shr 8 and 255) + (pixels[it] and 255)) / 3).toByte() }
        return QrCodec.decode(luma, bitmap.width, bitmap.height)
    }

    /** Keeps a screenshot for the run's evidence. Only public codes appear on these screens. */
    private fun snapshot(name: String) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(screens, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun click(value: String) {
        val matcher = hasText(value) and hasClickAction()
        compose.waitUntil(5000) { exists(matcher) }
        if (exists(hasScrollAction())) compose.onNode(hasScrollAction()).performScrollToNode(matcher)
        // The node's click action, as TalkBack activates it. Injected touches
        // can land on the IME or system bars over the button; test e_ covers
        // real key input.
        compose.onNode(matcher).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun text(resource: Int) = context.getString(resource)

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(50)
        }
    }
}
