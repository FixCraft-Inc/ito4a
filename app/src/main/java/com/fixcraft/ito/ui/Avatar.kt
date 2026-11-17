package com.fixcraft.ito.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fixcraft.ito.R
import com.fixcraft.ito.call.Contact

/**
 * How the ring around a portrait reads. Ito's mark is a thread, so the ring
 * is one: solid once a contact is verified, dashed until then. A ring never
 * appears alone: the same trust is also given in words.
 */
internal enum class Trust { Verified, Unverified, Blocked, None }

internal val Contact.trustRing get() = when { blocked -> Trust.Blocked; verified -> Trust.Verified; else -> Trust.Unverified }

/** A contact's portrait: their photo on this phone, or initials on a colour chosen from their key. */
@Composable
internal fun ContactAvatar(model: CallsModel?, contact: Contact, size: Dp, describe: Boolean = true) {
    val photo = model?.let { rememberPhoto(it, contact.key) }
    Avatar(contact.name, photo, size, contact.trustRing, contact.key, describe)
}

@Composable
internal fun Avatar(name: String, photo: ImageBitmap?, size: Dp, trust: Trust, seed: ByteArray?, describe: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    val label = when (trust) {
        Trust.Verified -> stringResource(R.string.trust_verified)
        Trust.Unverified -> stringResource(R.string.trust_unverified)
        Trust.Blocked -> stringResource(R.string.trust_blocked)
        Trust.None -> null
    }
    val ring = when (trust) { Trust.Verified -> colors.primary; Trust.Blocked -> colors.error; else -> colors.outline }
    val (fill, ink) = tint(seed, MaterialTheme.ito.avatarTints)
    val stroke = (size.value / 24f).coerceIn(1.5f, 3f).dp
    Box(Modifier.size(size).then(if (describe && label != null) Modifier.semantics { contentDescription = label } else Modifier),
        contentAlignment = Alignment.Center) {
        if (trust != Trust.None) Canvas(Modifier.fillMaxSize()) {
            val width = stroke.toPx()
            val dash = if (trust == Trust.Verified) null
                else PathEffect.dashPathEffect(floatArrayOf(width * 2.5f, width * 2f))
            drawCircle(ring, radius = this.size.minDimension / 2 - width / 2, style = Stroke(width, pathEffect = dash))
        }
        val inset = if (trust == Trust.None) 0.dp else stroke * 2
        val face = Modifier.fillMaxSize().padding(inset).clip(CircleShape)
        if (photo != null) Image(photo, null, face, contentScale = ContentScale.Crop)
        else Box(face.background(fill), contentAlignment = Alignment.Center) {
            Text(initials(name), color = ink, maxLines = 1,
                style = TextStyle(fontSize = (size.value * 0.36f).sp, fontWeight = FontWeight.Medium))
        }
    }
}

/** Up to two initials, from the first two words of a name. */
internal fun initials(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return "?"
    return words.take(2).joinToString("") { word -> String(Character.toChars(word.codePointAt(0))) }.uppercase()
}

private fun tint(seed: ByteArray?, tints: List<Pair<Color, Color>>): Pair<Color, Color> {
    if (seed == null || seed.isEmpty()) return tints.first()
    val index = seed.fold(0) { sum, byte -> (sum * 31 + (byte.toInt() and 255)) and 0x7fffffff }
    return tints[index % tints.size]
}
