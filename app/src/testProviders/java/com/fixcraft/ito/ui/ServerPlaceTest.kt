package com.fixcraft.ito.ui

import com.fixcraft.yume.providerlibrary.R
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerPlaceTest {
    private val names = mapOf("JP" to "Japan", "ZZ" to "ZZ", "QM" to "", "XA" to "Pseudo-Accents")

    private fun place(code: String?) = serverPlace(code) { names[it] ?: it }

    /** The library's flat art, never an emoji, which some phones draw waving. */
    @Test fun aKnownCountryHasItsFlagAndName() = assertEquals(ServerPlace(R.drawable.flag_jp, "Japan"), place("JP"))

    /** A named region without flag art keeps its name, and the screen shows the globe. */
    @Test fun aNamedRegionWithoutArtKeepsItsName() = assertEquals(ServerPlace(null, "Pseudo-Accents"), place("XA"))

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
