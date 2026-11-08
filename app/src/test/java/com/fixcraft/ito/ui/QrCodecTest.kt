package com.fixcraft.ito.ui

import com.fixcraft.ito.call.InvitationText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class QrCodecTest {
    /** Renders modules as a camera would see them: dark on light, with a quiet zone and scale. */
    private fun luminance(text: String, scale: Int = 4, quiet: Int = 4): Triple<ByteArray, Int, Int> {
        val matrix = QrCodec.encode(text)
        val side = (matrix.width + 2 * quiet) * scale
        val plane = ByteArray(side * side) { 0xE0.toByte() }
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) if (matrix.get(x, y)) {
            for (dy in 0 until scale) for (dx in 0 until scale) {
                plane[((y + quiet) * scale + dy) * side + (x + quiet) * scale + dx] = 0x20
            }
        }
        return Triple(plane, side, side)
    }

    @Test fun invitationAndContactCodesSurviveARoundTrip() {
        val invitation = InvitationText.encode(ByteArray(InvitationText.MAX_BYTES) { (it * 31).toByte() })
        val contact = "ito-contact:1:" + "A".repeat(43)
        for (text in listOf(invitation, contact)) {
            val (plane, width, height) = luminance(text)
            assertEquals(text, QrCodec.decode(plane, width, height))
        }
    }

    @Test fun framesWithoutACodeDecodeToNothing() {
        assertNull(QrCodec.decode(ByteArray(640 * 480) { 0x80.toByte() }, 640, 480))
        assertNull(QrCodec.decode(ByteArray(640 * 480) { (it * 37).toByte() }, 640, 480))
    }

    @Test fun encodingRefusesNonAsciiAndOversizedText() {
        assertThrows(IllegalArgumentException::class.java) { QrCodec.encode("ito-contact:1:é") }
        assertThrows(IllegalArgumentException::class.java) { QrCodec.encode("x".repeat(QrCodec.MAX_TEXT + 1)) }
    }
}
