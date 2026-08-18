package com.boxpix.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Type scale from the design handoff: sentence case, weights 400/500 only,
 * system sans (Inter is the reference but optional). Sizes map onto the M3 slots
 * the app actually uses.
 */
val BoxpixTypography = Typography(
    // Large title (onboarding "Boxpix", "Choose a disk")
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Medium),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium),
    // Screen title
    titleLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Medium),
    // List row
    titleMedium = TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal),
    // Body
    bodyMedium = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.Normal, lineHeight = 1.55.em),
    // Secondary
    bodySmall = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Normal),
    // Meta
    labelMedium = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Normal),
    // Section label (used uppercase with letter-spacing at call sites)
    labelSmall = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.em),
)
