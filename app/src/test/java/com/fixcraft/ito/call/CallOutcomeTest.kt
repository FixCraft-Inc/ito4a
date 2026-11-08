package com.fixcraft.ito.call

import org.junit.Assert.assertEquals
import org.junit.Test

class CallOutcomeTest {
    private fun ended(result: Int, remoteEnd: Int = 0, attributed: Boolean = false) =
        CallOutcomes.of(CallView(phase = CallPhase.Ended, result = result, remoteEnd = remoteEnd, endAttributed = attributed))

    @Test fun declineIsTheContactsOnlyWhenAttributed() {
        assertEquals(CallOutcome.DeclinedByContact, ended(-23, remoteEnd = 2, attributed = true))
        // Before the peer's proof, END proves only possession of the invitation.
        assertEquals(CallOutcome.EndedUnattributed, ended(-23, remoteEnd = 2, attributed = false))
        assertEquals(CallOutcome.EndedUnattributed, ended(-23, remoteEnd = 1, attributed = false))
        assertEquals(CallOutcome.EndedByContact, ended(-23, remoteEnd = 1, attributed = true))
        // close_notify without END.
        assertEquals(CallOutcome.EndedUnattributed, ended(-23))
    }

    @Test fun localEndsAndFailuresStayTyped() {
        assertEquals(CallOutcome.YouEnded, ended(0))
        assertEquals(CallOutcome.YouEnded, ended(CallNative.CANCELED))
        assertEquals(CallOutcome.YouDeclined, ended(-21))
        assertEquals(CallOutcome.TimedOut, ended(-19))
        assertEquals(CallOutcome.AuthenticationFailed, ended(-8))
        assertEquals(CallOutcome.ConnectionLost, ended(-25))
        assertEquals(CallOutcome.ConnectionEnded, ended(-27))
        assertEquals(CallOutcome.StorageFailed, ended(CallNative.STORAGE_AMBIGUOUS))
        assertEquals(CallOutcome.Failed, ended(-31))
    }

    @Test fun startFailuresNeverBlameTheContact() {
        fun start(failure: CallStartFailure, code: Int = 0) =
            CallOutcomes.of(CallView(phase = CallPhase.Ended, startFailure = failure, failureCode = code))
        assertEquals(CallOutcome.NoIdentity, start(CallStartFailure.NoIdentity))
        assertEquals(CallOutcome.NotConnected, start(CallStartFailure.NotConnected))
        assertEquals(CallOutcome.ConnectionLost, start(CallStartFailure.Transport, -1))
        assertEquals(CallOutcome.InvitationRefused, start(CallStartFailure.Refused, -11))
        assertEquals(CallOutcome.InvitationRefused, start(CallStartFailure.Refused, -5))
        assertEquals(CallOutcome.AuthenticationFailed, start(CallStartFailure.Refused, -7))
        assertEquals(CallOutcome.Busy, start(CallStartFailure.Refused, -17))
    }

    @Test fun clockRefusalsStayDistinctFromExpiryAndAuthentication() {
        assertEquals(CallOutcome.ClockRegressed, ended(CallNative.CLOCK_REGRESSED))
        assertEquals(CallOutcome.ClockRegressed, CallOutcomes.of(CallView(
            phase = CallPhase.Ended,
            startFailure = CallStartFailure.Refused,
            failureCode = CallNative.CLOCK_REGRESSED,
        )))
        assertEquals(CallOutcome.TimedOut, ended(-9))
        assertEquals(CallOutcome.AuthenticationFailed, ended(-8))
    }
}
