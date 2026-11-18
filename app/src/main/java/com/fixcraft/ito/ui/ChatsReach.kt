package com.fixcraft.ito.ui

import androidx.annotation.StringRes
import com.fixcraft.ito.R
import com.fixcraft.ito.transport.ConnectionFailure
import com.fixcraft.ito.transport.ConnectionPhase
import com.fixcraft.ito.transport.ConnectionState
import com.fixcraft.ito.transport.reconnectDelay

/**
 * Whether chats can reach the mailbox right now, and if not, why. Only
 * [Unavailable], [NoMailbox] and [Problem] are faults. Starting, retrying
 * and waiting for a network are ordinary and never read as errors.
 */
internal enum class ChatsReach(@StringRes val label: Int, val working: Boolean) {
    Online(R.string.transport_connected, false),
    Connecting(R.string.transport_starting, true),
    Reconnecting(R.string.chats_reconnecting, true),
    WaitingForNetwork(R.string.chats_waiting_network, true),
    /** Disconnect was chosen. Connect on the connection tab resumes. */
    Offline(R.string.transport_idle, false),
    NotSetUp(R.string.chats_not_set_up, false),
    /** Connected, but the selected server offers no Ito mailbox. */
    NoMailbox(R.string.chats_no_mailbox, false),
    /** Connected, but the mailbox stopped for a reason a retry on this connection cannot fix. */
    Unavailable(R.string.chats_unavailable, false),
    Problem(R.string.transport_failed, false),
}

internal fun chatsReach(connection: ConnectionState, messengerOnline: Boolean, mailboxStopped: Boolean): ChatsReach {
    if (messengerOnline) return ChatsReach.Online
    return when (connection.phase) {
        ConnectionPhase.Loading, ConnectionPhase.Starting -> ChatsReach.Connecting
        ConnectionPhase.Stopping -> if (connection.paused) ChatsReach.Offline else ChatsReach.Connecting
        ConnectionPhase.Idle -> when {
            connection.paused -> ChatsReach.Offline
            !connection.configured -> ChatsReach.NotSetUp
            else -> ChatsReach.Connecting
        }
        ConnectionPhase.Connected -> when {
            !connection.mailboxAvailable -> ChatsReach.NoMailbox
            mailboxStopped -> ChatsReach.Unavailable
            else -> ChatsReach.Connecting
        }
        ConnectionPhase.Failed -> when {
            connection.paused -> ChatsReach.Offline
            connection.failure == ConnectionFailure.NetworkUnavailable || connection.failure == ConnectionFailure.NetworkLost ->
                ChatsReach.WaitingForNetwork
            reconnectDelay(connection.phase, connection.failure, connection.nativeCode, 0) != null -> ChatsReach.Reconnecting
            else -> ChatsReach.Problem
        }
    }
}
