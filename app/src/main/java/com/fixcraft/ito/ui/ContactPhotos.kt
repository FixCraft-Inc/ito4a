package com.fixcraft.ito.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fixcraft.ito.content.ChatRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pictures people choose for their contacts and themselves. A picked image is
 * decoded at reduced size, cropped to a centred square and stored as a new
 * JPEG, so camera metadata such as location never reaches Ito's storage.
 */
internal object ContactPhotos {
    private const val SIDE = 320
    private const val MAX_SOURCE_PIXELS = 200_000_000L

    /** Runs off the main thread. Throws when the image cannot be read. */
    fun encode(context: Context, uri: Uri): ByteArray {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            require(width > 0 && height > 0 && width.toLong() * height <= MAX_SOURCE_PIXELS)
            // Decode only as many pixels as the square needs.
            val scale = min(1f, SIDE.toFloat() / min(width, height))
            decoder.setTargetSize(max(1, (width * scale).roundToInt()), max(1, (height * scale).roundToInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val side = min(decoded.width, decoded.height)
        val square = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
        if (square !== decoded) decoded.recycle()
        return try {
            ByteArrayOutputStream().use { out ->
                check(square.compress(Bitmap.CompressFormat.JPEG, 85, out))
                out.toByteArray().also { check(it.size <= ChatRepository.MAX_PHOTO) }
            }
        } finally { square.recycle() }
    }

    /** A stored photo for display, or null when it cannot be decoded. */
    fun decode(jpeg: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth !in 1..2048 || bounds.outHeight !in 1..2048) return null
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap()
    }
}

/**
 * The photo of a contact, or the owner's for a null [peer], reloaded when a
 * photo changes. It reads nothing while the app is locked.
 */
@Composable
internal fun rememberPhoto(model: CallsModel, peer: ByteArray?): ImageBitmap? {
    val revision by model.photos.collectAsStateWithLifecycle()
    return rememberPhoto(model.history, peer, revision)
}

/** The same, for screens without a [CallsModel], such as the lock-screen call window. */
@Composable
internal fun rememberPhoto(history: ChatRepository, peer: ByteArray?, revision: Int = 0): ImageBitmap? {
    val key = peer?.joinToString("") { "%02x".format(it.toInt() and 255) } ?: "own"
    val photo by produceState<ImageBitmap?>(null, key, revision) {
        value = withContext(Dispatchers.IO) {
            try {
                history.photo(peer)?.let { jpeg -> try { ContactPhotos.decode(jpeg) } finally { jpeg.fill(0) } }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { null }
        }
    }
    return photo
}
