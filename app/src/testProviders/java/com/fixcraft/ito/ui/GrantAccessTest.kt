package com.fixcraft.ito.ui

import com.fixcraft.yume.consumer.ServiceEntry
import com.fixcraft.yume.consumer.ServiceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GrantAccessTest {
    private val selection = ServiceSelection("https://provider.example", "00000000-0000-4000-8000-000000000000",
        "device", "relay", "server", "grant", "a".repeat(64), "b".repeat(64), "edge.example", 24444, "Server", "relay")
    private fun entry(suspended: Boolean = false, installed: Boolean = true) = ServiceEntry("https://provider.example",
        "Provider", null, selection.takeIf { installed }, expiresAt = 3_600, suspended = suspended, renewAfter = 1_800)

    @Test fun aLeaseIsActiveUntilItMayBeRenewed() {
        assertEquals(GrantAccess.Active, grantAccess(entry(), 0))
        assertEquals(GrantAccess.Active, grantAccess(entry(), 1_799))
    }

    @Test fun choosingTheServerAgainRenewsFromItsRenewalTime() {
        assertEquals(GrantAccess.Renewable, grantAccess(entry(), 1_800))
        assertEquals(GrantAccess.Renewable, grantAccess(entry(), 3_599))
    }

    @Test fun anExpiredOrSuspendedLeaseHasEnded() {
        assertEquals(GrantAccess.Ended, grantAccess(entry(), 3_600))
        assertEquals(GrantAccess.Ended, grantAccess(entry(suspended = true), 0))
    }

    @Test fun noInstalledServerHasNoLease() {
        assertNull(grantAccess(entry(installed = false), 0))
    }
}
