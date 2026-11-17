package com.fixcraft.ito.call

/** How an ended attempt is explained. Screens choose wording from this alone. */
enum class CallOutcome {
    /** The call ended after a hangup, from either side's stated cancel. */
    YouEnded,
    YouDeclined,
    /** The authenticated contact stated a decline. */
    DeclinedByContact,
    /** The authenticated contact ended the call for its stated reason. */
    EndedByContact,
    /** The other side ended before proving it holds the contact's key. */
    EndedUnattributed,
    TimedOut,
    AuthenticationFailed,
    InvitationRefused,
    ClockRegressed,
    ConnectionLost,
    ConnectionEnded,
    NotConnected,
    NoIdentity,
    StorageFailed,
    Busy,
    Failed,
}

object CallOutcomes {
    // ito_status values from call.h that screens distinguish.
    private const val OK = 0
    private const val MALFORMED = -5
    private const val UNSUPPORTED = -6
    private const val UNTRUSTED = -7
    private const val AUTHENTICATION_FAILED = -8
    private const val EXPIRED = -9
    private const val ALREADY_SPENT = -11
    private const val CAPACITY = -12
    private const val STORAGE_FAILED = -15
    private const val BUSY = -17
    private const val TIMEOUT = -19
    private const val DECLINED = -21
    private const val PEER_ENDED = -23
    private const val RELAY_REFUSED = -24
    private const val TRANSPORT_FAILED = -25
    private const val AUTHORITY_MISMATCH = -26
    private const val AUTHORITY_ENDED = -27
    private const val END_DECLINED = 2

    fun of(view: CallView): CallOutcome = if (view.localFailure != null) CallOutcome.Failed else when (view.startFailure) {
        CallStartFailure.NoIdentity -> CallOutcome.NoIdentity
        CallStartFailure.NotConnected -> CallOutcome.NotConnected
        CallStartFailure.Transport -> CallOutcome.ConnectionLost
        CallStartFailure.Refused -> refused(view.failureCode)
        null -> ended(view)
    }

    /** A call or invitation the core would not create. */
    private fun refused(code: Int) = when (code) {
        MALFORMED, UNSUPPORTED, AUTHENTICATION_FAILED, EXPIRED, ALREADY_SPENT -> CallOutcome.InvitationRefused
        CallNative.CLOCK_REGRESSED -> CallOutcome.ClockRegressed
        UNTRUSTED -> CallOutcome.AuthenticationFailed
        CAPACITY, BUSY -> CallOutcome.Busy
        STORAGE_FAILED, CallNative.STORAGE_AMBIGUOUS -> CallOutcome.StorageFailed
        else -> CallOutcome.Failed
    }

    private fun ended(view: CallView): CallOutcome {
        when (view.ringEnd) {
            1 -> return CallOutcome.EndedByContact
            2 -> return CallOutcome.DeclinedByContact
            3 -> return CallOutcome.Busy
            4 -> return CallOutcome.TimedOut
        }
        return when (view.result) {
            OK, CallNative.CANCELED -> CallOutcome.YouEnded
            DECLINED -> CallOutcome.YouDeclined
            // Remote END reasons are statements by the peer. Only one that
            // arrived after its proof is the contact's (CALL_SECURITY).
            PEER_ENDED -> when {
                !view.endAttributed -> CallOutcome.EndedUnattributed
                view.remoteEnd == END_DECLINED -> CallOutcome.DeclinedByContact
                else -> CallOutcome.EndedByContact
            }
            TIMEOUT, EXPIRED -> CallOutcome.TimedOut
            AUTHENTICATION_FAILED, UNTRUSTED -> CallOutcome.AuthenticationFailed
            MALFORMED, UNSUPPORTED, ALREADY_SPENT -> CallOutcome.InvitationRefused
            CallNative.CLOCK_REGRESSED -> CallOutcome.ClockRegressed
            RELAY_REFUSED, TRANSPORT_FAILED -> CallOutcome.ConnectionLost
            AUTHORITY_MISMATCH, AUTHORITY_ENDED -> CallOutcome.ConnectionEnded
            STORAGE_FAILED, CallNative.STORAGE_AMBIGUOUS -> CallOutcome.StorageFailed
            CAPACITY, BUSY -> CallOutcome.Busy
            else -> CallOutcome.Failed
        }
    }
}
