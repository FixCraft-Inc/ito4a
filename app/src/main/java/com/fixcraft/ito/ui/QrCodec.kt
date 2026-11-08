package com.fixcraft.ito.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * QR encoding and decoding through ZXing core, kept free of Android types so
 * the JVM tests exercise the same code. Payloads are ASCII Ito texts, and
 * decoded text longer than [MAX_TEXT] is ignored before anyone parses it.
 */
object QrCodec {
    const val MAX_TEXT = 1024

    private val encodeHints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 0,
        EncodeHintType.CHARACTER_SET to "ISO-8859-1",
    )
    private val decodeHints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.CHARACTER_SET to "ISO-8859-1",
    )

    /** Modules without a quiet zone, which the drawing adds. The caller clears it after use. */
    fun encode(text: String): BitMatrix {
        require(text.length <= MAX_TEXT && text.all { it.code in 0x21..0x7e })
        return QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, encodeHints)
    }

    /** Text of one QR code in a width x height luminance plane, or null. */
    fun decode(luma: ByteArray, width: Int, height: Int): String? {
        require(width > 0 && height > 0 && luma.size >= width * height)
        val source = PlanarYUVLuminanceSource(luma, width, height, 0, 0, width, height, false)
        return try {
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), decodeHints).text
                ?.takeIf { it.length <= MAX_TEXT }
        } catch (_: ReaderException) {
            null
        } catch (_: RuntimeException) {
            // A frame the decoder cannot handle is not a scan result.
            null
        }
    }
}
