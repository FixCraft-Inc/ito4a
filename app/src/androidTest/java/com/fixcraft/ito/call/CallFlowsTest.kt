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
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
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
import com.fixcraft.ito.ui.ChatsPage
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

/** Identity, legacy-contact migration, shared scanner and accessibility on a fresh install. */
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
        assertTrue("the application window must stay secure", secure())
        snapshot("chats-light")
        withPeer { peer, _ ->
            book.addContact(peer.code, "Rin")
            app.messenger.initialize()
            click("Rin")
            compose.waitUntil(5000) { exists(hasText(text(R.string.chat_pair_again)) and hasClickAction()) }
            assertFalse("legacy contact exposed a message composer", exists(hasSetTextAction()))
            snapshot("legacy-pair-again")
            device.pressBack()
            waitForContacts()
        }
    }

    @Test fun b_scanningAsksForTheCameraOnlyWhenItStarts() {
        ensureIdentity()
        assertEquals("the camera is granted before any scan", PackageManager.PERMISSION_DENIED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA))
        click(text(R.string.chat_scan))
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
                    ItoTheme { Surface(color = MaterialTheme.colorScheme.background) { ChatsPage(model, {}) {} } }
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
        snapshot("chats-dark-2x-end")
    }

    @Test fun e_keyboardReachesAndActivatesActions() {
        ensureIdentity()
        compose.onNode(hasText(text(R.string.chats)) and isSelectable()).performClick()
        // Add contact is the list's floating button, outside any scrolling content.
        val target = hasText(text(R.string.add_contact)) and hasClickAction()
        // Real key events from the emulator's hardware keyboard, through the window.
        var focused = false
        for (step in 1..60) {
            device.pressKeyCode(KeyEvent.KEYCODE_TAB)
            compose.waitForIdle()
            if (exists(target and SemanticsMatcher.expectValue(SemanticsProperties.Focused, true))) { focused = true; break }
        }
        assertTrue("Tab never reached Add contact", focused)
        device.pressKeyCode(KeyEvent.KEYCODE_ENTER)
        compose.waitUntil(5000) { exists(hasText(text(R.string.chat_show_code)) and hasClickAction()) }
        device.pressBack()
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
            click(text(R.string.chat_scan))
            click(text(R.string.cancel))
            Thread.sleep(2000)
            await { available.get() == true }
            // Leaving the app ends the scan and releases a running camera.
            click(text(R.string.chat_scan))
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
        compose.waitUntil(10_000) { exists(hasText(text(R.string.add_contact)) and hasClickAction()) }
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
                    .let { book ->
                        try { book.open(); book.createIdentity(); (book.state.value as BookState.Ready).identity!! }
                        finally { book.close() }
                    }
            }
            block(identities[0], identities[1])
        } finally {
            directories.forEach { it.deleteRecursively() }
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            directories.indices.forEach { keys.deleteEntry("$alias.$it") }
        }
    }

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
        // Icon buttons carry their label as a content description.
        val matcher = (hasText(value) or hasContentDescription(value)) and hasClickAction()
        compose.waitUntil(5000) { exists(matcher) }
        // Scroll only the list that holds the target. Top-bar and floating buttons are outside any list.
        val list = hasScrollAction() and hasAnyDescendant(matcher)
        if (exists(list)) compose.onNode(list).performScrollToNode(matcher)
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
