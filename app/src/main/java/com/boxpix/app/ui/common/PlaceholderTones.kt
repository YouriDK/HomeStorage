package com.boxpix.app.ui.common

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * Neutral placeholder ramp from the design handoff — stands in for thumbnails
 * until the M3 pipeline exists. The tone is a stable function of the path, so a
 * given photo keeps its shade across scrolls and reloads.
 */
object PlaceholderTones {

    private val dark = listOf(
        Color(0xFF17171A), Color(0xFF1F1F23), Color(0xFF28282D), Color(0xFF313137),
        Color(0xFF3B3B42), Color(0xFF46464E), Color(0xFF53535C), Color(0xFF0E0E10),
    )

    private val light = listOf(
        Color(0xFFECECEE), Color(0xFFE0E0E4), Color(0xFFD4D4D9), Color(0xFFC8C8CE),
        Color(0xFFBCBCC3), Color(0xFFB0B0B8), Color(0xFFA3A3AD), Color(0xFFF0F0F2),
    )

    fun tone(key: String, darkTheme: Boolean): Color {
        val ramp = if (darkTheme) dark else light
        return ramp[abs(key.hashCode()) % ramp.size]
    }
}
