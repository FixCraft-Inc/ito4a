package com.fixcraft.ito.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalCallTest {
    @Test fun ringEndsRoundTripBelowCoreResults() {
        for (reason in 1..4) assertEquals(reason, LocalCall.ringEnd(LocalCall.ringResult(reason)))
        assertNull(LocalCall.ringEnd(0))
        assertNull(LocalCall.ringEnd(-23))
        assertNull(LocalCall.ringEnd(-1000))
    }
}
