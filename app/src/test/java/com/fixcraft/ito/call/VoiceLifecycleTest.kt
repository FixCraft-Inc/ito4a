package com.fixcraft.ito.call

import org.junit.Assert.*
import org.junit.Test

class VoiceLifecycleTest {
    @Test fun focusLossDuringSetupPreventsCaptureAndCannotResume() {
        val lifecycle = VoiceLifecycle()
        lifecycle.beginStart()
        assertTrue(lifecycle.fail())
        var captured = false
        assertThrows(IllegalStateException::class.java) {
            lifecycle.startCapture { captured = true }
        }
        assertFalse(captured)
        assertFalse(lifecycle.running)
        assertFalse(lifecycle.fail())
        assertThrows(IllegalStateException::class.java) { lifecycle.beginStart() }
    }

    @Test fun focusLossAtDeviceStartupCannotPublishRunning() {
        val lifecycle = VoiceLifecycle()
        lifecycle.beginStart()
        assertThrows(IllegalStateException::class.java) {
            lifecycle.startCapture { assertTrue(lifecycle.fail()) }
        }
        assertFalse(lifecycle.running)
        assertFalse(lifecycle.fail())
    }

    @Test fun activeFailureIsReportedOnceAndCloseCannotRestartCapture() {
        val lifecycle = VoiceLifecycle()
        lifecycle.beginStart()
        lifecycle.startCapture {}
        assertTrue(lifecycle.running)
        assertTrue(lifecycle.fail())
        assertFalse(lifecycle.running)
        assertFalse(lifecycle.fail())
        lifecycle.close()
        assertThrows(IllegalStateException::class.java) { lifecycle.startCapture {} }
    }

    @Test fun closeDuringSetupPreventsCaptureWithoutReportingFailure() {
        val lifecycle = VoiceLifecycle()
        lifecycle.beginStart()
        lifecycle.close()
        assertFalse(lifecycle.fail())
        assertThrows(IllegalStateException::class.java) { lifecycle.startCapture {} }
    }
}
