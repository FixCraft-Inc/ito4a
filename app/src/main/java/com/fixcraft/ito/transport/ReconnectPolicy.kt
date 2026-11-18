package com.fixcraft.ito.transport

/**
 * Public yume_status codes a run retries: NOT_FOUND, which a TCP connect to an
 * unreachable or restarting server reports, then TIMEOUT, CLOSED and IO_ERROR.
 */
internal val TRANSIENT_NATIVE = setOf(-7, -5, -12, -15)

/** Retry transport loss only. Authority, authentication, input and storage need intervention. */
internal fun reconnectDelay(phase: ConnectionPhase, failure: ConnectionFailure, nativeCode: Int, attempt: Int): Long? {
    if (phase == ConnectionPhase.Idle) return 0
    if (phase != ConnectionPhase.Failed) return null
    val transient = failure in setOf(ConnectionFailure.NetworkUnavailable, ConnectionFailure.NetworkLost, ConnectionFailure.Resolve) ||
        (failure == ConnectionFailure.Native && nativeCode in TRANSIENT_NATIVE)
    if (!transient) return null
    return minOf(30_000L, 1000L shl attempt.coerceIn(0, 5))
}
