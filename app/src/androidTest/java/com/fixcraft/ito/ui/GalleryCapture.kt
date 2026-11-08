package com.fixcraft.ito.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * Saves rendered frames for visual review. The host sets display size, font scale
 * and night mode between runs and names each run with `gallery.profile`.
 */
internal class GalleryCapture(private val compose: ComposeContentTestRule) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val profile = InstrumentationRegistry.getArguments().getString("gallery.profile") ?: "default"
    private val out = File(instrumentation.targetContext.filesDir, "gallery/$profile").apply { mkdirs() }

    /** The whole display, including system bars and dialog windows. */
    private fun screenshot(): Bitmap {
        compose.waitForIdle()
        Thread.sleep(250)
        return instrumentation.uiAutomation.takeScreenshot() ?: error("No screenshot")
    }

    private fun save(name: String, image: Bitmap) {
        File(out, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** One frame per screenful of [name], so nothing below the fold goes unseen. */
    fun frame(name: String, scroll: Boolean = true) {
        var previous = screenshot()
        save(name, previous)
        if (!scroll) return
        for (index in 1..MAX_SCROLL_FRAMES) {
            if (compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isEmpty()) return
            compose.onAllNodes(hasScrollAction()).onLast()
                .performTouchInput { swipeUp(startY = bottom * 0.85f, endY = top + bottom * 0.2f, durationMillis = 400) }
            val next = screenshot()
            if (next.sameAs(previous)) break
            save("$name-$index", next)
            previous = next
        }
        // The next frame starts from the top of its page.
        repeat(MAX_SCROLL_FRAMES + 1) {
            compose.onAllNodes(hasScrollAction()).onLast()
                .performTouchInput { swipeDown(startY = top + bottom * 0.2f, endY = bottom * 0.9f, durationMillis = 120) }
        }
        compose.waitForIdle()
    }

    /** A window with FLAG_SECURE reads back as black from the display, so this frame comes from Compose. */
    fun secureFrame(name: String) {
        compose.waitForIdle()
        save(name, compose.onRoot().captureToImage().asAndroidBitmap())
    }

    private companion object {
        const val MAX_SCROLL_FRAMES = 5
    }
}
