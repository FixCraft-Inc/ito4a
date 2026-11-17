package com.fixcraft.ito.messaging

import org.junit.Assert.*
import org.junit.Test

class SafetyQrTest {
    @Test fun bindsBothIdentitiesAndAllSixtyDigits() {
        val first = "12".repeat(32)
        val second = "ab".repeat(32)
        val digits = "0123456789".repeat(6)
        val text = "ito-safety:1:$first:$second:$digits"
        val qr = requireNotNull(SafetyQr.parse(text))
        assertEquals(second, qr.other(first))
        assertEquals(first, qr.other(second))
        assertNull(qr.other("cd".repeat(32)))
        assertEquals(text, qr.encode())
        for (bad in listOf(text + "0", text.dropLast(1), text.replace(":1:", ":2:"), text.replace(second, first),
            text.replace("ab", "AB"), text.dropLast(1) + "x")) assertNull(SafetyQr.parse(bad))
    }
}
