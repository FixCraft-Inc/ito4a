package com.fixcraft.ito.transport

import kotlinx.coroutines.withTimeout

/**
 * Android's resolver waits five seconds for a DNS server before it asks the
 * next one. The bound covers that wait and the next server's answer, so a
 * first server that never replies does not fail the first Connect. yume4a's
 * VPN service uses the same bound.
 */
internal const val SERVER_RESOLUTION_TIMEOUT_MS = 10_000L

/** Runs one lookup within [timeoutMs]. Expiry throws `TimeoutCancellationException`. */
internal suspend fun <T> boundedResolution(timeoutMs: Long = SERVER_RESOLUTION_TIMEOUT_MS, lookup: suspend () -> T): T =
    withTimeout(timeoutMs) { lookup() }
