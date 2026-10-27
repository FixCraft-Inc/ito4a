package com.fixcraft.ito.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CallPolicyTest {
    @Test fun protectedDefaultNeverAuthorizesDirectMedia() {
        for (peer in listOf(false, true)) for (encrypted in listOf(false, true)) for (yume in listOf(false, true)) {
            assertNotEquals(MediaPathAuthorization.Allowed, authorizeMediaPath(
                Preferences().routing, MediaPath.Direct, CallSecurityFacts(peer, encrypted, yume),
            ))
        }
    }

    @Test fun noRoutingPreferenceCanAuthorizeUnauthenticatedOrPlaintextMedia() {
        for (preference in RoutingPreference.entries) for (path in MediaPath.entries) {
            for (yume in listOf(false, true)) {
                assertNotEquals(MediaPathAuthorization.Allowed, authorizeMediaPath(
                    preference, path, CallSecurityFacts(false, true, yume),
                ))
                assertNotEquals(MediaPathAuthorization.Allowed, authorizeMediaPath(
                    preference, path, CallSecurityFacts(true, false, yume),
                ))
            }
        }
    }

    @Test fun protectedMediaRequiresAuthenticatedYumeEvenWhenDirectIsAllowed() {
        for (preference in RoutingPreference.entries) {
            assertEquals(MediaPathAuthorization.Rejected(CallRefusal.YumeNotAuthenticated),
                authorizeMediaPath(preference, MediaPath.YumeRelay, CallSecurityFacts(true, true, false)))
        }
    }

    @Test fun explicitDirectConsentStillRequiresPeerAuthenticationAndEncryption() {
        assertEquals(MediaPathAuthorization.Allowed,
            authorizeMediaPath(RoutingPreference.DirectAllowed, MediaPath.Direct, CallSecurityFacts(true, true, false)))
        assertEquals(MediaPathAuthorization.Allowed,
            authorizeMediaPath(RoutingPreference.ProtectedOnly, MediaPath.YumeRelay, CallSecurityFacts(true, true, true)))
    }
}
