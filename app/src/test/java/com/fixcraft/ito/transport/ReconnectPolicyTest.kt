package com.fixcraft.ito.transport

import org.junit.Assert.*
import org.junit.Test

class ReconnectPolicyTest {
    @Test fun waitsAreBoundedAndOnlyTransportLossRetries() {
        assertEquals(0L, reconnectDelay(ConnectionPhase.Idle, ConnectionFailure.None, 0, 0))
        assertEquals(1000L, reconnectDelay(ConnectionPhase.Failed, ConnectionFailure.NetworkLost, 0, 0))
        assertEquals(30000L, reconnectDelay(ConnectionPhase.Failed, ConnectionFailure.NetworkLost, 0, Int.MAX_VALUE))
        for (code in listOf(-7, -5, -12, -15)) assertNotNull(reconnectDelay(ConnectionPhase.Failed, ConnectionFailure.Native, code, 0))
        for (code in -20..0) if (code !in setOf(-7, -5, -12, -15))
            assertNull(reconnectDelay(ConnectionPhase.Failed, ConnectionFailure.Native, code, 0))
        for (failure in listOf(ConnectionFailure.Storage, ConnectionFailure.Provider, ConnectionFailure.InvalidConfiguration,
            ConnectionFailure.Cancelled, ConnectionFailure.DataMismatch, ConnectionFailure.Input))
            assertNull(reconnectDelay(ConnectionPhase.Failed, failure, 0, 0))
        assertNull(reconnectDelay(ConnectionPhase.Stopping, ConnectionFailure.NetworkLost, 0, 0))
    }

    /** libyume reports a refused or failed TCP connect to the server as NOT_FOUND, as during a server restart. */
    @Test fun anUnreachableServerIsRetriedNotAbandoned() {
        assertEquals(1000L, reconnectDelay(ConnectionPhase.Failed, ConnectionFailure.Native, -7, 0))
        assertEquals(30000L, reconnectDelay(ConnectionPhase.Failed, ConnectionFailure.Native, -7, 9))
    }
}
