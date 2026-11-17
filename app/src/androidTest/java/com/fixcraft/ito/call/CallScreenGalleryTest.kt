package com.fixcraft.ito.call

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.DarkMode
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.then
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fixcraft.ito.ItoApplication
import com.fixcraft.ito.ui.CallScreen
import com.fixcraft.ito.ui.CallsModel
import com.fixcraft.ito.ui.ItoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A rendering gallery of call screens for visual review, not a call test.
 * Each frame is a fixed [CallView] drawn by the production [CallScreen], with
 * a public fixture contact. No peer, relay, microphone or audio runs, so the
 * frames say nothing about call or audio behaviour. Nothing is clicked.
 * Frames go to the app's private files directory under `u2-gallery`, readable
 * with run-as for the test user.
 */
@RunWith(AndroidJUnit4::class)
class CallScreenGalleryTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as ItoApplication
    private val out = File(context.filesDir, "u2-gallery").apply { mkdirs() }

    // A public key from a fixed public seed, so the fingerprint is stable between runs.
    private val key = ByteArray(32) { (it * 7 + 3).toByte() }
    private val contact = Contact(key, "Aiko", CallNative.TRUST_VERIFIED, "ito-contact:1:fixture",
        "b66dd75f07e8c8c942f8c0929114ae0a707e90ce9bc4949c61498b147c61538b", null, null)

    // Public fixture devices: the phone's own two and one headset.
    private val routes = listOf(AudioRoute(1, AudioRouteKind.Earpiece, ""), AudioRoute(2, AudioRouteKind.Speaker, ""),
        AudioRoute(7, AudioRouteKind.Bluetooth, "Fixture headset"))

    private val frames = listOf(
        "connecting" to CallView(phase = CallPhase.Connecting, initiator = true, peer = key),
        "inviting" to CallView(phase = CallPhase.Inviting, initiator = true, peer = key,
            invitation = ByteArray(331) { (it * 13 + 5).toByte() }),
        "waiting" to CallView(phase = CallPhase.Waiting, initiator = false, peer = key),
        "handshaking" to CallView(phase = CallPhase.Handshaking, initiator = true, peer = key),
        "authenticating" to CallView(phase = CallPhase.Authenticating, peer = key),
        "incoming-consent" to CallView(phase = CallPhase.Consent, peer = key, peerAuthenticated = true),
        "active-before-audio" to CallView(phase = CallPhase.Media, peer = key, peerAuthenticated = true),
        "active-audio-muted" to CallView(phase = CallPhase.Media, peer = key, peerAuthenticated = true,
            voiceActive = true, muted = true, audioRoutes = routes, audioRoute = routes[1],
            connectedAt = android.os.SystemClock.elapsedRealtime() - 83_000, sentBytes = 48_000, receivedBytes = 47_520),
        "active-audio-on" to CallView(phase = CallPhase.Media, peer = key, peerAuthenticated = true,
            voiceActive = true, muted = false, audioRoutes = routes.take(2), audioRoute = routes[0],
            connectedAt = android.os.SystemClock.elapsedRealtime() - 5_000),
        "active-bluetooth" to CallView(phase = CallPhase.Media, peer = key, peerAuthenticated = true,
            voiceActive = true, audioRoutes = routes, audioRoute = routes[2],
            connectedAt = android.os.SystemClock.elapsedRealtime() - 3_725_000),
        "ending" to CallView(phase = CallPhase.Ending, peer = key, peerAuthenticated = true),
        "ended-by-you" to CallView(phase = CallPhase.Ended, peer = key, peerAuthenticated = true, historySaved = true,
            sentBytes = 48_000, receivedBytes = 47_520),
        "ended-by-contact" to CallView(phase = CallPhase.Ended, peer = key, peerAuthenticated = true, historySaved = true,
            result = -23, endAttributed = true, remoteEnd = 1),
        "declined-by-contact" to CallView(phase = CallPhase.Ended, peer = key, peerAuthenticated = true,
            historySaved = true, result = -23, endAttributed = true, remoteEnd = 2),
        "history-not-saved" to CallView(phase = CallPhase.Ended, peer = key, peerAuthenticated = true, historySaved = false),
        "failed-authentication" to CallView(phase = CallPhase.Ended, peer = key, result = -8),
        "not-started-not-connected" to CallView(phase = CallPhase.Ended, initiator = true, peer = key,
            startFailure = CallStartFailure.NotConnected),
        "connection-lost" to CallView(phase = CallPhase.Ended, peer = key, peerAuthenticated = true,
            historySaved = true, result = -25),
        "authority-ended" to CallView(phase = CallPhase.Ended, peer = key, peerAuthenticated = true,
            historySaved = true, result = -27),
        "timed-out" to CallView(phase = CallPhase.Ended, initiator = true, peer = key, result = -19),
        "invitation-refused" to CallView(phase = CallPhase.Ended, peer = key, startFailure = CallStartFailure.Refused, failureCode = -9),
        "busy" to CallView(phase = CallPhase.Ended, initiator = true, peer = key, startFailure = CallStartFailure.Refused, failureCode = -17),
        "no-identity" to CallView(phase = CallPhase.Ended, initiator = true, startFailure = CallStartFailure.NoIdentity),
        "storage-failed" to CallView(phase = CallPhase.Ended, peer = key, peerAuthenticated = true, result = -15),
        "audio-failed" to CallView(phase = CallPhase.Ended, peer = key, peerAuthenticated = true, historySaved = true,
            localFailure = CallLocalFailure.Audio),
        "unknown-caller-consent" to CallView(phase = CallPhase.Consent, peer = ByteArray(32) { 9 }, peerAuthenticated = true),
    )

    @Test fun rendersEveryCallState() {
        val model = CallsModel(app)
        var view by mutableStateOf(frames.first().second)
        var dark by mutableStateOf(false)
        var advanced by mutableStateOf(false)
        compose.setContent {
            val scale = if (dark) 2f else 1f
            DeviceConfigurationOverride(DeviceConfigurationOverride.DarkMode(dark) then DeviceConfigurationOverride.FontScale(scale)) {
                ItoTheme {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        CallScreen(view, listOf(contact), model, advanced) {}
                    }
                }
            }
        }
        for ((variant, settings) in listOf("light" to false, "dark-2x" to true)) {
            dark = settings
            for ((name, frame) in frames) {
                view = frame
                advanced = name == "active-audio-muted" || name == "ended-by-you"
                compose.waitForIdle()
                val image = compose.onRoot().captureToImage().asAndroidBitmap()
                File(out, "$name-$variant.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
}
