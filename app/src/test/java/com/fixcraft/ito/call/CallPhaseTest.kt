package com.fixcraft.ito.call

import org.junit.Assert.assertEquals
import org.junit.Test

class CallPhaseTest {
    private fun status(state: Int, flags: Int = 0) =
        CallStatus(longArrayOf(state.toLong(), flags.toLong(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), ByteArray(32))

    @Test fun coreStatesMapToScreens() {
        assertEquals(CallPhase.Connecting, CallController.phaseOf(status(CallNative.STATE_PREPARING), true))
        assertEquals(CallPhase.Inviting, CallController.phaseOf(status(CallNative.STATE_JOINED), true))
        assertEquals(CallPhase.Waiting, CallController.phaseOf(status(CallNative.STATE_JOINED), false))
        assertEquals(CallPhase.Handshaking, CallController.phaseOf(status(CallNative.STATE_HANDSHAKING), true))
        assertEquals(CallPhase.Media, CallController.phaseOf(status(CallNative.STATE_MEDIA_AUTHORIZED), false))
        assertEquals(CallPhase.Ended, CallController.phaseOf(status(CallNative.STATE_CLOSED), false))
    }

    @Test fun consentIsShownOnlyWhileTheCoreAsks() {
        val asking = status(CallNative.STATE_AWAITING_ACCEPTANCE, CallNative.FLAG_CONSENT_PENDING)
        val decided = status(CallNative.STATE_AWAITING_ACCEPTANCE)
        assertEquals(CallPhase.Consent, CallController.phaseOf(asking, false))
        assertEquals(CallPhase.Authenticating, CallController.phaseOf(decided, false))
    }

    @Test fun statusFieldsKeepTheirPositions() {
        val values = LongArray(12) { it.toLong() + 1 }
        val status = CallStatus(values, ByteArray(32))
        assertEquals(1, status.state)
        assertEquals(5, status.remoteEnd)
        assertEquals(8L, status.dataReceived)
        assertEquals(12, status.keyUpdatesReceived)
    }
}
