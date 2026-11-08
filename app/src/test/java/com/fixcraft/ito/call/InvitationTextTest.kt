package com.fixcraft.ito.call

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InvitationTextTest {
    private val invitation = ByteArray(331) { (it * 7 + 3).toByte() }

    @Test fun roundTripsTheExactBytes() {
        val text = InvitationText.encode(invitation)
        assertTrue(text.startsWith("ito-invitation:1:"))
        assertTrue(text.length <= InvitationText.MAX_CHARS)
        assertArrayEquals(invitation, InvitationText.decode(text))
        val largest = ByteArray(InvitationText.MAX_BYTES) { 1 }
        assertArrayEquals(largest, InvitationText.decode(InvitationText.encode(largest)))
    }

    @Test fun refusesEverythingElse() {
        val body = InvitationText.encode(invitation).removePrefix(InvitationText.PREFIX)
        for (text in listOf(
            "", InvitationText.PREFIX, "ito-invitation:2:$body", "ito-contact:1:$body", " ${InvitationText.PREFIX}$body",
            "${InvitationText.PREFIX}$body=", "${InvitationText.PREFIX}$body ", "${InvitationText.PREFIX}+${body.drop(1)}", "${InvitationText.PREFIX}/${body.drop(1)}",
            "${InvitationText.PREFIX}${body.dropLast(1)}", "${InvitationText.PREFIX}A",
            InvitationText.encode(ByteArray(InvitationText.MAX_BYTES)) + "AAAA",
        )) assertNull(text, InvitationText.decode(text))
    }

    @Test fun refusesNonCanonicalTrailingBits() {
        // One byte encodes as two characters whose last four bits must be zero.
        assertEquals(1, InvitationText.decode("${InvitationText.PREFIX}AA")?.size)
        assertNull(InvitationText.decode("${InvitationText.PREFIX}AB"))
    }
}
