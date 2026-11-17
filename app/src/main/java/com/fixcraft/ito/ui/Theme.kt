package com.fixcraft.ito.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Ito keeps one soft "lab blue" palette on every Android version, so the app
// looks the same on every phone instead of taking the wallpaper's colours.
// Every surface, outline and container role is set, so no Material baseline
// purple shows through a default. surfaceContainerHighest stays distinct from
// surfaceVariant: Material picks the muted onSurfaceVariant for any container
// equal to surfaceVariant, which makes ordinary text look disabled.
private val light = lightColorScheme(
    primary = Color(0xFF2C6A9E), onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E7F8), onPrimaryContainer = Color(0xFF0A2A44),
    inversePrimary = Color(0xFF9DCBF5),
    secondary = Color(0xFF4F6273), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7E5F2), onSecondaryContainer = Color(0xFF0D1F2D),
    tertiary = Color(0xFF2D6C6F), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFB9ECEE), onTertiaryContainer = Color(0xFF002022),
    background = Color(0xFFF7FAFD), onBackground = Color(0xFF171C21),
    surface = Color(0xFFF7FAFD), onSurface = Color(0xFF171C21),
    surfaceVariant = Color(0xFFDCE4EC), onSurfaceVariant = Color(0xFF40484F),
    surfaceTint = Color(0xFF2C6A9E),
    inverseSurface = Color(0xFF2C3136), inverseOnSurface = Color(0xFFEDF1F5),
    outline = Color(0xFF70787F), outlineVariant = Color(0xFFC0C8D0),
    surfaceBright = Color(0xFFF7FAFD), surfaceDim = Color(0xFFD6DBE0),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF1F5F9),
    surfaceContainer = Color(0xFFEBF0F5), surfaceContainerHigh = Color(0xFFE5EAEF),
    surfaceContainerHighest = Color(0xFFDFE5EB),
)
private val dark = darkColorScheme(
    primary = Color(0xFF9DCBF5), onPrimary = Color(0xFF003354),
    primaryContainer = Color(0xFF134A72), onPrimaryContainer = Color(0xFFD3E7F8),
    inversePrimary = Color(0xFF2C6A9E),
    secondary = Color(0xFFB7C8D9), onSecondary = Color(0xFF213241),
    secondaryContainer = Color(0xFF384958), onSecondaryContainer = Color(0xFFD7E5F2),
    tertiary = Color(0xFF9DD0D3), onTertiary = Color(0xFF003739),
    tertiaryContainer = Color(0xFF1E4E50), onTertiaryContainer = Color(0xFFB9ECEE),
    background = Color(0xFF0F1419), onBackground = Color(0xFFDEE3E9),
    surface = Color(0xFF0F1419), onSurface = Color(0xFFDEE3E9),
    surfaceVariant = Color(0xFF40484F), onSurfaceVariant = Color(0xFFC0C8D0),
    surfaceTint = Color(0xFF9DCBF5),
    inverseSurface = Color(0xFFDEE3E9), inverseOnSurface = Color(0xFF2C3136),
    outline = Color(0xFF8A9299), outlineVariant = Color(0xFF40484F),
    surfaceBright = Color(0xFF353A3F), surfaceDim = Color(0xFF0F1419),
    surfaceContainerLowest = Color(0xFF0A0F13), surfaceContainerLow = Color(0xFF171C21),
    surfaceContainer = Color(0xFF1B2025), surfaceContainerHigh = Color(0xFF252A30),
    surfaceContainerHighest = Color(0xFF30353B),
)

/**
 * Colours Material has no role for: answer and hang-up, read ticks and the
 * chat background. Answer and hang-up keep the same hue in both themes, so
 * the two call buttons are recognised before their labels are read.
 */
@Immutable
data class ItoColors(
    val answer: Color, val onAnswer: Color,
    val hangUp: Color, val onHangUp: Color,
    val read: Color, val chatBackground: Color,
    /** Incoming bubbles and timeline notes, raised clearly off [chatBackground] in both themes. */
    val incomingBubble: Color,
    val callBackground: Color, val callBackgroundEnd: Color, val onCallBackground: Color, val callControl: Color,
    /** Background and initials colour pairs for portraits without a photo, all in the cool range. */
    val avatarTints: List<Pair<Color, Color>>,
)

private val lightExtras = ItoColors(
    answer = Color(0xFF1E8E4E), onAnswer = Color.White,
    hangUp = Color(0xFFD32F2F), onHangUp = Color.White,
    read = Color(0xFF2C6A9E), chatBackground = Color(0xFFE8F1F8), incomingBubble = Color.White,
    callBackground = Color(0xFF14456B), callBackgroundEnd = Color(0xFF0B2A44), onCallBackground = Color.White, callControl = Color(0x33FFFFFF),
    avatarTints = listOf(
        Color(0xFFD3E7F8) to Color(0xFF0A2A44), Color(0xFFCDEEF0) to Color(0xFF00363A),
        Color(0xFFDCE1FA) to Color(0xFF151C45), Color(0xFFD9E6EE) to Color(0xFF14242E),
        Color(0xFFD4EFE4) to Color(0xFF0B2E22), Color(0xFFE6E0F5) to Color(0xFF24193F),
    ),
)
private val darkExtras = ItoColors(
    answer = Color(0xFF2EA862), onAnswer = Color.White,
    hangUp = Color(0xFFE5484D), onHangUp = Color.White,
    read = Color(0xFF9DCBF5), chatBackground = Color(0xFF0B1015), incomingBubble = Color(0xFF212A33),
    callBackground = Color(0xFF0F2F4A), callBackgroundEnd = Color(0xFF07182A), onCallBackground = Color.White, callControl = Color(0x2EFFFFFF),
    avatarTints = listOf(
        Color(0xFF134A72) to Color(0xFFD3E7F8), Color(0xFF124E52) to Color(0xFFCDEEF0),
        Color(0xFF2B3466) to Color(0xFFDCE1FA), Color(0xFF2A3D4A) to Color(0xFFD9E6EE),
        Color(0xFF17483A) to Color(0xFFD4EFE4), Color(0xFF3B305B) to Color(0xFFE6E0F5),
    ),
)

val LocalItoColors = staticCompositionLocalOf { lightExtras }

/** The extra colours of the current theme. */
val MaterialTheme.ito: ItoColors
    @Composable get() = LocalItoColors.current

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(32.dp),
)

private val typography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Medium),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Medium),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Medium),
    )
}

@Composable
fun ItoTheme(content: @Composable () -> Unit) {
    val night = isSystemInDarkTheme()
    androidx.compose.runtime.CompositionLocalProvider(LocalItoColors provides if (night) darkExtras else lightExtras) {
        MaterialTheme(colorScheme = if (night) dark else light, shapes = shapes, typography = typography, content = content)
    }
}
