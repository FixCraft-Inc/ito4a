package com.fixcraft.ito.ui

import com.fixcraft.yume.consumer.ServiceEntry

/**
 * What the installed server's lease allows, for the provider card only. The
 * shared library still checks every lease, renewal and clock itself.
 */
internal enum class GrantAccess {
    /** Usable, and too early to renew. */
    Active,
    /** Usable, and choosing the server again renews it. */
    Renewable,
    /** Expired or suspended. Choosing the server again requests a new lease. */
    Ended,
}

/** Null when no server is installed. [nowSeconds] is UTC. */
internal fun grantAccess(entry: ServiceEntry, nowSeconds: Long): GrantAccess? {
    if (entry.selection == null) return null
    val expiresAt = entry.expiresAt
    val renewAfter = entry.renewAfter
    return when {
        entry.suspended || (expiresAt != null && nowSeconds >= expiresAt) -> GrantAccess.Ended
        renewAfter != null && nowSeconds >= renewAfter -> GrantAccess.Renewable
        else -> GrantAccess.Active
    }
}
