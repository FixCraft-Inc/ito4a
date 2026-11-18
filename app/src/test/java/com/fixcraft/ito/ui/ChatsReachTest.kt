package com.fixcraft.ito.ui

import com.fixcraft.ito.transport.ConnectionFailure
import com.fixcraft.ito.transport.ConnectionPhase
import com.fixcraft.ito.transport.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatsReachTest {
    private fun state(phase: ConnectionPhase, failure: ConnectionFailure = ConnectionFailure.None, code: Int = 0,
                      mailbox: Boolean = false, paused: Boolean = false, configured: Boolean = true) =
        ConnectionState(phase = phase, failure = failure, nativeCode = code, mailboxAvailable = mailbox,
            paused = paused, configured = configured)

    @Test fun anOnlineMailboxIsOnlineWhateverTheTransportShows() {
        for (phase in ConnectionPhase.entries) assertEquals(ChatsReach.Online, chatsReach(state(phase), true, false))
    }

    /** Ordinary start-up never reads as a missing mailbox or a failure. */
    @Test fun startingAndRetryingAreWorkNotFaults() {
        for (phase in listOf(ConnectionPhase.Loading, ConnectionPhase.Starting))
            assertEquals(ChatsReach.Connecting, chatsReach(state(phase, configured = false), false, false))
        assertEquals(ChatsReach.Connecting, chatsReach(state(ConnectionPhase.Idle), false, false))
        // Connected with a mailbox while its stream opens or reopens after a drop.
        assertEquals(ChatsReach.Connecting, chatsReach(state(ConnectionPhase.Connected, mailbox = true), false, false))
        assertEquals(ChatsReach.Connecting, chatsReach(state(ConnectionPhase.Stopping), false, false))
        for (code in listOf(-7, -5, -12, -15))
            assertEquals(ChatsReach.Reconnecting, chatsReach(state(ConnectionPhase.Failed, ConnectionFailure.Native, code), false, false))
        assertEquals(ChatsReach.Reconnecting, chatsReach(state(ConnectionPhase.Failed, ConnectionFailure.Resolve), false, false))
        for (failure in listOf(ConnectionFailure.NetworkLost, ConnectionFailure.NetworkUnavailable))
            assertEquals(ChatsReach.WaitingForNetwork, chatsReach(state(ConnectionPhase.Failed, failure), false, false))
        for (reach in listOf(ChatsReach.Connecting, ChatsReach.Reconnecting, ChatsReach.WaitingForNetwork)) assertTrue(reach.working)
    }

    @Test fun eachStopNamesItsCause() {
        assertEquals(ChatsReach.Offline, chatsReach(state(ConnectionPhase.Idle, paused = true), false, false))
        assertEquals(ChatsReach.Offline, chatsReach(state(ConnectionPhase.Stopping, paused = true), false, false))
        assertEquals(ChatsReach.NotSetUp, chatsReach(state(ConnectionPhase.Idle, configured = false), false, false))
        assertEquals(ChatsReach.NoMailbox, chatsReach(state(ConnectionPhase.Connected), false, false))
        assertEquals(ChatsReach.Unavailable, chatsReach(state(ConnectionPhase.Connected, mailbox = true), false, true))
        for (failure in listOf(ConnectionFailure.Storage, ConnectionFailure.Provider, ConnectionFailure.InvalidConfiguration,
            ConnectionFailure.DataMismatch)) assertEquals(ChatsReach.Problem, chatsReach(state(ConnectionPhase.Failed, failure), false, false))
        assertEquals(ChatsReach.Problem, chatsReach(state(ConnectionPhase.Failed, ConnectionFailure.Native, -3), false, false))
        for (reach in listOf(ChatsReach.Offline, ChatsReach.NotSetUp, ChatsReach.NoMailbox, ChatsReach.Unavailable, ChatsReach.Problem))
            assertFalse(reach.working)
    }
}
