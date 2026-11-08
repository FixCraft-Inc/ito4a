package com.fixcraft.ito.transport

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerResolutionTest {
    /**
     * Android's resolver gives an unanswering first server its full five
     * seconds, then the second server answers. This runs in real time.
     */
    @Test fun aLookupThatNeedsOneResolverFailoverSucceeds() = runBlocking {
        val address = boundedResolution {
            delay(ANDROID_PER_SERVER_TIMEOUT_MS + SECOND_SERVER_REPLY_MS)
            "192.0.2.10"
        }
        assertEquals("192.0.2.10", address)
    }

    @Test fun aLookupThatNeverAnswersEndsAtItsBound() {
        assertThrows(TimeoutCancellationException::class.java) {
            runBlocking { boundedResolution(timeoutMs = 50) { awaitCancellation() } }
        }
    }

    private companion object {
        const val ANDROID_PER_SERVER_TIMEOUT_MS = 5_000L
        const val SECOND_SERVER_REPLY_MS = 150L
    }
}
