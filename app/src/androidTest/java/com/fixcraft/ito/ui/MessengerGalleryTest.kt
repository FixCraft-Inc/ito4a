package com.fixcraft.ito.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.DarkMode
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.then
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fixcraft.ito.ItoApplication
import com.fixcraft.ito.call.CallNative
import com.fixcraft.ito.call.Contact
import com.fixcraft.ito.content.ChatSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Public synthetic content in production components. Screenshots establish rendering only. */
@RunWith(AndroidJUnit4::class)
class MessengerGalleryTest {
    @get:Rule val compose = createComposeRule()
    private var screen by mutableStateOf<@Composable () -> Unit>({})
    private var dark by mutableStateOf(false)
    private var scale by mutableFloatStateOf(1f)

    @Test fun messengerScreens() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ItoApplication
        val model = CallsModel(app)
        val key = ByteArray(32) { (it * 7 + 3).toByte() }
        val verified = Contact(key, "Aiko", CallNative.TRUST_VERIFIED, CallNative.contactCodeFormat(key), "b6".repeat(32), null, null)
        val unverified = verified.copy(name = "Anastasia Vasilyevna Kovalenko-Rimskaya", trust = CallNative.TRUST_UNVERIFIED)
        val now = 1_800_000_000_000L
        val data = ChatDisplay(true, "1234567890".repeat(6), "ito-safety:1:" + "ab".repeat(32) + ":" + "cd".repeat(32) + ":" + "1234567890".repeat(6),
            "ab".repeat(32) + "/ito-mailbox", listOf(
                ChatBubble(ByteArray(16) { 1 }, false, 1, "Are we still meeting at six?", now, now, 3, false, 0),
                ChatBubble(ByteArray(16) { 2 }, true, 1, "Yes. I will call when I arrive.", now + 1000, now + 1000, 2, false, 0),
                ChatBubble(ByteArray(16) { 3 }, false, 1, "This message waited while the phone was offline.", now - 5000, now + 3000, 3, true, 0)
            ), listOf(ChatCall(now + 2000, 64_000L, 0)), 0, ChatSettings("Mina", true), true, now / 1000, 1)
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.DarkMode(dark) then DeviceConfigurationOverride.FontScale(scale)) {
                ItoTheme { Surface(color = MaterialTheme.colorScheme.background) {
                    ItoFrame("Ito", null, Page.Calls, SnackbarHostState(), {}) { key(screen, dark, scale) { screen() } }
                } }
            }
        }
        val capture = GalleryCapture(compose)
        for (night in listOf(false, true)) for (font in listOf(1f, 2f)) {
            dark = night; scale = font
            val profile = "${if (night) "dark" else "light"}-${if (font == 1f) "normal" else "large"}"
            screen = { IncomingCallContent(unverified.copy(trust = CallNative.TRUST_VERIFIED), {}, {}) }
            capture.frame("$profile-incoming")
            screen = { LockedScreen(true, true, false, {}, {}, {}, incomingCall = true) }
            capture.frame("$profile-incoming-locked")
            screen = { ChatTimeline(model, verified, data, {}) }
            capture.frame("$profile-chat", scroll = false)
            screen = { ContactProfile(model, verified, data, true, {}) }
            capture.frame("$profile-profile")
            screen = { ChatTimeline(model, unverified, data, {}) }
            capture.frame("$profile-unverified", scroll = false)
            screen = { ContactProfile(model, unverified, data, false, {}) }
            capture.frame("$profile-unverified-profile")
        }
    }
}
