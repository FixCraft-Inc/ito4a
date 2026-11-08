package com.fixcraft.ito.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Ito owns its visual identity. Android 12+ uses the user's system palette.
// Android 9-11 uses this green palette. Every surface, outline and container
// role is set, so no Material baseline purple shows through a default.
private val light = lightColorScheme(
    primary = Color(0xFF3F6650), onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E8D8), onPrimaryContainer = Color(0xFF182D20),
    inversePrimary = Color(0xFFA2D0AF),
    secondary = Color(0xFF4C6258), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8E7DE), onSecondaryContainer = Color(0xFF182D20),
    tertiary = Color(0xFF3D6473), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC1E9FB), onTertiaryContainer = Color(0xFF001F29),
    background = Color(0xFFF7F9F6), onBackground = Color(0xFF18221D),
    surface = Color(0xFFF7F9F6), onSurface = Color(0xFF18221D),
    surfaceVariant = Color(0xFFDCE5DD), onSurfaceVariant = Color(0xFF414942),
    surfaceTint = Color(0xFF3F6650),
    inverseSurface = Color(0xFF2D322E), inverseOnSurface = Color(0xFFEEF2EC),
    outline = Color(0xFF717972), outlineVariant = Color(0xFFC0C9C0),
    surfaceBright = Color(0xFFF7F9F6), surfaceDim = Color(0xFFD7DBD6),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF1F4F0),
    surfaceContainer = Color(0xFFEBEFEA), surfaceContainerHigh = Color(0xFFE5E9E4),
    surfaceContainerHighest = Color(0xFFE0E4DF),
)
private val dark = darkColorScheme(
    primary = Color(0xFFA2D0AF), onPrimary = Color(0xFF103822),
    primaryContainer = Color(0xFF254D35), onPrimaryContainer = Color(0xFFD5E8D8),
    inversePrimary = Color(0xFF3F6650),
    secondary = Color(0xFFB7CDC0), onSecondary = Color(0xFF22352B),
    secondaryContainer = Color(0xFF354C40), onSecondaryContainer = Color(0xFFD8E7DE),
    tertiary = Color(0xFFA5CDDE), onTertiary = Color(0xFF063543),
    tertiaryContainer = Color(0xFF244C5A), onTertiaryContainer = Color(0xFFC1E9FB),
    background = Color(0xFF101612), onBackground = Color(0xFFE0E7DE),
    surface = Color(0xFF101612), onSurface = Color(0xFFE0E7DE),
    surfaceVariant = Color(0xFF414942), onSurfaceVariant = Color(0xFFC0C9C0),
    surfaceTint = Color(0xFFA2D0AF),
    inverseSurface = Color(0xFFE0E7DE), inverseOnSurface = Color(0xFF2D322E),
    outline = Color(0xFF8A938B), outlineVariant = Color(0xFF414942),
    surfaceBright = Color(0xFF353B37), surfaceDim = Color(0xFF101612),
    surfaceContainerLowest = Color(0xFF0B100D), surfaceContainerLow = Color(0xFF181E1A),
    surfaceContainer = Color(0xFF1C221E), surfaceContainerHigh = Color(0xFF262C28),
    surfaceContainerHighest = Color(0xFF313733),
)

@Composable
fun ItoTheme(content: @Composable () -> Unit) {
    val night = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && night -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        night -> dark
        else -> light
    }
    MaterialTheme(colorScheme = colors, content = content)
}
