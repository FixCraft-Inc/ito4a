package com.fixcraft.ito.call

import org.junit.Assert.assertEquals
import org.junit.Test

class CallLocalFailureTest {
    @Test fun automaticAudioOrContentFailureIsNotAttributedToUserHangup() {
        for (failure in CallLocalFailure.entries) {
            assertEquals(CallOutcome.Failed, CallOutcomes.of(CallView(
                phase = CallPhase.Ended, result = CallNative.CANCELED, localFailure = failure)))
        }
    }
}
