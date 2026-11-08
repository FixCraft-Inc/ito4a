package com.fixcraft.ito.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerPlaceTest {
    private val names = mapOf("JP" to "Japan", "ZZ" to "ZZ", "QM" to "")

    private fun place(code: String?) = serverPlace(code) { names[it] ?: it }

    @Test fun aKnownCountryHasItsFlagAndName() = assertEquals(ServerPlace("🇯🇵", "Japan"), place("JP"))

    @Test fun anAbsentCountryStaysUnknown() = assertEquals(ServerPlace(null, null), place(null))

    /** A region the platform cannot name draws no flag, since a flag must never be the only label. */
    @Test fun aRegionWithoutANameStaysUnknown() {
        assertEquals(ServerPlace(null, null), place("ZZ"))
        assertEquals(ServerPlace(null, null), place("QM"))
    }

    @Test fun anythingButTwoCapitalsStaysUnknown() {
        for (code in listOf("", "J", "JPN", "jp", "J1")) assertEquals(code, ServerPlace(null, null), place(code))
    }
}
