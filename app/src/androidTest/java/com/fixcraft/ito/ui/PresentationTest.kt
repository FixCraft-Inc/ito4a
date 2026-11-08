package com.fixcraft.ito.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DarkMode
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fixcraft.ito.ItoApplication
import com.fixcraft.ito.R
import com.fixcraft.ito.call.CallNative
import com.fixcraft.ito.call.CallPhase
import com.fixcraft.ito.call.CallStartFailure
import com.fixcraft.ito.call.CallView
import com.fixcraft.ito.call.Contact
import com.fixcraft.ito.domain.Preferences
import com.fixcraft.ito.transport.ConnectionPhase
import com.fixcraft.ito.transport.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Screen states that once read wrongly: dark lock text, a faint Disconnect, inert settings and a false history warning. */
@RunWith(AndroidJUnit4::class)
class PresentationTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun lockedScreenTextIsReadableInDarkMode() {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.DarkMode(true)) {
                ItoTheme { LockedScreen(locked = true, canStop = false, callActive = false, {}, {}, {}) }
            }
        }
        for (resource in listOf(R.string.app_locked_title, R.string.app_locked)) {
            val image = compose.onNode(hasText(context.getString(resource))).captureToImage().asAndroidBitmap()
            // The title's own pixels hold both its glyphs and the screen behind them.
            assertTrue("locked-screen text has too little contrast in dark mode", contrast(image) >= 4.5)
        }
    }

    @Test fun disconnectIsReadableOnTheConnectedCard() {
        val app = context.applicationContext as ItoApplication
        var night by mutableStateOf(false)
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.DarkMode(night)) {
                ItoTheme {
                    ConnectionStatus(ConnectionState(phase = ConnectionPhase.Connected), app.connections,
                        SelectedRoute("Relay", "United States"), authorityReady = true, advanced = false,
                        setupHint = R.string.transport_idle)
                }
            }
        }
        for (dark in listOf(false, true)) {
            compose.runOnIdle { night = dark }
            val image = compose.onNode(hasText(context.getString(R.string.stop_transport)) and hasClickAction())
                .captureToImage().asAndroidBitmap()
            assertTrue("Disconnect has too little contrast on the connected card (dark=$dark)", contrast(image) >= 4.5)
        }
    }

    @Test fun settingsOfferOnlyControlsThatWork() {
        compose.setContent { ItoTheme { Surface(color = MaterialTheme.colorScheme.background) { SettingsPage(Preferences()) } } }
        // App lock and Advanced mode change behaviour. Routing and audio are fixed, so they are statements.
        assertEquals(2, compose.onAllNodes(role(Role.Switch)).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(role(Role.RadioButton)).fetchSemanticsNodes().size)
        assertTrue(exists(hasText(context.getString(R.string.call_route_value))))
        assertTrue(exists(hasText(context.getString(R.string.call_audio_value))))
    }

    @Test fun onlyAnAuthenticatedCallCanReportMissingHistory() {
        val key = ByteArray(32) { (it * 7 + 3).toByte() }
        val contact = Contact(key, "Aiko", CallNative.TRUST_VERIFIED, "ito-contact:1:fixture", "0".repeat(64), null, null)
        val model = CallsModel(context.applicationContext as ItoApplication)
        var view by mutableStateOf(CallView(phase = CallPhase.Ended, initiator = true, peer = key,
            startFailure = CallStartFailure.NotConnected))
        compose.setContent {
            ItoTheme { Surface(color = MaterialTheme.colorScheme.background) { CallScreen(view, listOf(contact), model, false) {} } }
        }
        // A refusal before the call started has no history to save.
        assertTrue(exists(hasText(context.getString(R.string.call_not_started))))
        assertFalse(exists(hasText(context.getString(R.string.history_not_saved))))
        // A call with a proven contact whose record failed says so.
        compose.runOnIdle { view = CallView(phase = CallPhase.Ended, peer = key, peerAuthenticated = true, historySaved = false) }
        assertTrue(exists(hasText(context.getString(R.string.call_ended))))
        assertTrue(exists(hasText(context.getString(R.string.history_not_saved))))
    }

    private fun role(value: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, value)

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /** WCAG contrast between the lightest and darkest pixels of an image. */
    private fun contrast(image: Bitmap): Double {
        var lightest = 0.0
        var darkest = 1.0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val luminance = luminance(image.getPixel(x, y))
            lightest = max(lightest, luminance)
            darkest = min(darkest, luminance)
        }
        return (lightest + 0.05) / (darkest + 0.05)
    }

    private fun luminance(pixel: Int): Double {
        fun channel(value: Int): Double {
            val c = value / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(Color.red(pixel)) + 0.7152 * channel(Color.green(pixel)) + 0.0722 * channel(Color.blue(pixel))
    }
}
