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
private val light = lightColorScheme(
    primary = Color(0xFF3F6650), onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E8D8), onPrimaryContainer = Color(0xFF182D20),
    secondary = Color(0xFF4C6258), secondaryContainer = Color(0xFFD8E7DE),
    onSecondaryContainer = Color(0xFF182D20),
    background = Color(0xFFF7F9F6), surface = Color(0xFFF7F9F6),
    onBackground = Color(0xFF18221D), onSurface = Color(0xFF18221D),
)
private val dark = darkColorScheme(
    primary = Color(0xFFA2D0AF), onPrimary = Color(0xFF103822),
    primaryContainer = Color(0xFF254D35), onPrimaryContainer = Color(0xFFD5E8D8),
    secondary = Color(0xFFB7CDC0), secondaryContainer = Color(0xFF354C40),
    onSecondaryContainer = Color(0xFFD8E7DE),
    background = Color(0xFF101612), surface = Color(0xFF101612),
    onBackground = Color(0xFFE0E7DE), onSurface = Color(0xFFE0E7DE),
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
