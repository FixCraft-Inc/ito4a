package com.fixcraft.ito.integration

import com.fixcraft.ito.transport.ServiceRefusal
import org.junit.Assert.*
import org.junit.Test

class MinimalServicesTest {
    @Test fun retainedProviderNeverFallsBackToSavedManualChoice() {
        val owner = MinimalServices({ true }, {})
        try { owner.selectedForRestart(); fail("Retained provider authority was ignored") }
        catch (refusal: ServiceRefusal) { assertEquals("providers_disabled", refusal.code) }
        owner.selectManual()
        assertNull(owner.selectedForRestart())
        // An explicit choice is process-local, so stale disabled-build state
        // cannot override a provider selected by an intervening enabled build.
        try { MinimalServices({ true }, {}).selectedForRestart(); fail("Restart silently chose manual") }
        catch (refusal: ServiceRefusal) { assertEquals("providers_disabled", refusal.code) }
    }

    @Test fun freshMinimalBuildNeedsNoProviderStateOrKey() {
        assertNull(MinimalServices({ false }, {}).selectedForRestart())
    }

    @Test fun lockRefusalCannotAuthorizeManualSelection() {
        var locked = true
        val owner = MinimalServices({ true }) { check(!locked) }
        try { owner.selectManual(); fail("Locked mutation admitted") } catch (_: IllegalStateException) { }
        locked = false
        try { owner.selectedForRestart(); fail("Canceled selection became authority") }
        catch (refusal: ServiceRefusal) { assertEquals("providers_disabled", refusal.code) }
    }
}
