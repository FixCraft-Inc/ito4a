package com.fixcraft.ito.transport

import org.junit.Assert.*
import org.junit.Test

class ConnectionNetworkTest {
    private val wifi = ConnectionNetwork(1, internet = true, vpn = false)
    private val cellular = ConnectionNetwork(2, internet = true, vpn = false)
    private val vpn = ConnectionNetwork(3, internet = true, vpn = true)

    @Test fun defaultConnectionUsesVpnEvenWhenPhysicalNetworksAreAvailable() {
        val captured = requireNotNull(captureConnectionNetwork(null, vpn, listOf(wifi, cellular, vpn)))
        assertEquals(vpn, captured.network)
        assertTrue(captured.acceptsDefault(vpn))
        assertTrue(captured.acceptsNetwork(vpn))
        assertFalse(captured.acceptsNetwork(wifi))
    }

    @Test fun defaultConnectionNeverChoosesAnotherAvailableNetwork() {
        assertEquals(cellular, requireNotNull(captureConnectionNetwork(null, cellular, listOf(wifi, cellular))).network)
        assertNull(captureConnectionNetwork(null, null, listOf(wifi, cellular)))
        assertNull(captureConnectionNetwork(null, wifi.copy(internet = false), listOf(cellular)))
    }

    @Test fun explicitPhysicalChoiceIsAvailableWithoutAnActiveVpn() {
        val captured = requireNotNull(captureConnectionNetwork(wifi.handle, cellular, listOf(wifi, cellular, vpn)))
        assertEquals(wifi, captured.network)
        assertTrue(captured.acceptsDefault(cellular))
        assertTrue(captured.acceptsNetwork(wifi))
        assertEquals(listOf(wifi, cellular), selectableConnectionNetworks(cellular, listOf(wifi, cellular, vpn)))
    }

    @Test fun explicitChoiceCannotBypassAnActiveVpn() {
        assertTrue(selectableConnectionNetworks(vpn, listOf(wifi, cellular, vpn)).isEmpty())
        for (selected in listOf(wifi.handle, cellular.handle, vpn.handle)) {
            assertNull(captureConnectionNetwork(selected, vpn, listOf(wifi, cellular, vpn)))
        }
    }

    @Test fun staleOrIneligibleExplicitChoiceNeverBecomesTheDefault() {
        assertNull(captureConnectionNetwork(wifi.handle, cellular, listOf(cellular)))
        assertNull(captureConnectionNetwork(wifi.handle, cellular, listOf(wifi.copy(internet = false), cellular)))
        assertNull(captureConnectionNetwork(vpn.handle, cellular, listOf(wifi, cellular, vpn)))
        assertNull(captureConnectionNetwork(wifi.handle, null, listOf(wifi)))
        assertTrue(selectableConnectionNetworks(null, listOf(wifi)).isEmpty())
    }

    @Test fun vpnStartingEndsAnAlreadyCapturedPhysicalChoice() {
        val captured = requireNotNull(captureConnectionNetwork(wifi.handle, cellular, listOf(wifi, cellular)))
        assertTrue(captured.acceptsNetwork(wifi))
        assertFalse(captured.acceptsDefault(vpn))
        assertNull(captureConnectionNetwork(wifi.handle, vpn, listOf(wifi, cellular, vpn)))
    }

    @Test fun defaultReplacementEndsTheRunEvenIfItsSelectedNetworkSurvives() {
        val captured = requireNotNull(captureConnectionNetwork(null, vpn, listOf(wifi, vpn)))
        assertTrue(captured.acceptsNetwork(vpn))
        assertFalse(captured.acceptsDefault(wifi))
        assertFalse(captured.acceptsDefault(null))
        // The new default is available only to a new explicit Connect action.
        assertEquals(wifi, requireNotNull(captureConnectionNetwork(null, wifi, listOf(wifi))).network)
    }

    @Test fun selectedLossAndInternetLossInvalidateCapturedAuthority() {
        val captured = requireNotNull(captureConnectionNetwork(wifi.handle, cellular, listOf(wifi, cellular)))
        assertFalse(captured.acceptsNetwork(null))
        assertFalse(captured.acceptsNetwork(wifi.copy(internet = false)))
        assertFalse(captured.acceptsDefault(cellular.copy(internet = false)))
        assertTrue(selectableConnectionNetworks(cellular.copy(internet = false), listOf(wifi)).isEmpty())
    }

    @Test fun aRouteCannotChangeItsVpnAuthorityUnderTheSameHandle() {
        val captured = requireNotNull(captureConnectionNetwork(null, wifi, listOf(wifi)))
        assertFalse(captured.acceptsDefault(wifi.copy(vpn = true)))
        assertFalse(captured.acceptsNetwork(wifi.copy(vpn = true)))
    }
}
