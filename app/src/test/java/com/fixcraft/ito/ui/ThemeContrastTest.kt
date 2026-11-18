package com.fixcraft.ito.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every Ito background that carries text has its own ink, readable in both themes. */
class ThemeContrastTest {
    private fun contrast(ink: Color, background: Color): Double {
        val a = ink.compositeOver(background).luminance() + 0.05
        val b = background.luminance() + 0.05
        return maxOf(a, b).toDouble() / minOf(a, b)
    }

    private fun check(theme: String, name: String, ink: Color, background: Color, minimum: Double = 4.5) {
        val ratio = contrast(ink, background)
        assertTrue("$theme $name contrast %.2f is below %.1f".format(ratio, minimum), ratio >= minimum)
    }

    @Test fun chatAndCallColoursKeepTheirInkReadable() {
        for ((theme, scheme, extras) in listOf(Triple("light", lightScheme, lightExtras), Triple("dark", darkScheme, darkExtras))) {
            val note = extras.chatNote.compositeOver(extras.chatBackground)
            check(theme, "incoming bubble", extras.onIncomingBubble, extras.incomingBubble)
            check(theme, "chat note", extras.onChatNote, note)
            check(theme, "outgoing bubble", scheme.onPrimaryContainer, scheme.primaryContainer)
            check(theme, "chat background", scheme.onBackground, extras.chatBackground)
            check(theme, "missed call icon", scheme.error, extras.incomingBubble, 3.0)
            check(theme, "call icon", scheme.primary, extras.incomingBubble, 3.0)
            check(theme, "answer", extras.onAnswer, extras.answer, 3.0)
            check(theme, "hang up", extras.onHangUp, extras.hangUp, 3.0)
            check(theme, "call screen", extras.onCallBackground, extras.callBackground)
            extras.avatarTints.forEachIndexed { index, (background, ink) -> check(theme, "avatar $index", ink, background) }
        }
    }
}
