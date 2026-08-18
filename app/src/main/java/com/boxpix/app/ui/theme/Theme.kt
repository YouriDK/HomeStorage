package com.boxpix.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Design tokens from design_handoff_boxpix/README.md. Dark is true black (AMOLED);
 * hierarchy comes from very dark elevated surfaces and hairlines, never washed-out greys.
 */
@Immutable
data class BoxpixColors(
    val bg: Color,
    val surface: Color,
    val elevated: Color,
    val text: Color,
    val dim: Color,
    val faint: Color,
    val hairline: Color,
    val hairlineStrong: Color,
    val scrim: Color,
    val accent: Color,
    val accentSoft: Color,
)

private val DarkText = Color(0xFFE9E9ED)
private val DarkAccent = Color(0xFF6FC0B3)

val DarkColors = BoxpixColors(
    bg = Color(0xFF000000),
    surface = Color(0xFF0E0E10),
    elevated = Color(0xFF16161A),
    text = DarkText,
    dim = DarkText.copy(alpha = 0.55f),
    faint = DarkText.copy(alpha = 0.38f),
    hairline = DarkText.copy(alpha = 0.12f),
    hairlineStrong = DarkText.copy(alpha = 0.20f),
    scrim = Color.Black.copy(alpha = 0.72f),
    accent = DarkAccent,
    accentSoft = DarkAccent.copy(alpha = 0.16f),
)

private val LightText = Color(0xFF14141A)
private val LightAccent = Color(0xFF2F7F74)

val LightColors = BoxpixColors(
    bg = Color(0xFFFBFBFC),
    surface = Color(0xFFFFFFFF),
    elevated = Color(0xFFF2F2F4),
    text = LightText,
    dim = LightText.copy(alpha = 0.58f),
    faint = LightText.copy(alpha = 0.38f),
    hairline = LightText.copy(alpha = 0.10f),
    hairlineStrong = LightText.copy(alpha = 0.16f),
    scrim = Color(0xFFFBFBFC).copy(alpha = 0.88f),
    accent = LightAccent,
    accentSoft = LightAccent.copy(alpha = 0.12f),
)

val LocalBoxpixColors = staticCompositionLocalOf { DarkColors }

/** Token accessor: `boxpixColors.accent` anywhere below BoxpixTheme. */
val boxpixColors: BoxpixColors
    @Composable get() = LocalBoxpixColors.current

@Composable
fun BoxpixTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.accent,
            background = colors.bg,
            surface = colors.bg,
            surfaceVariant = colors.elevated,
            onPrimary = colors.bg,
            onBackground = colors.text,
            onSurface = colors.text,
            onSurfaceVariant = colors.dim,
            outline = colors.hairlineStrong,
            outlineVariant = colors.hairline,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            background = colors.bg,
            surface = colors.bg,
            surfaceVariant = colors.elevated,
            onPrimary = colors.surface,
            onBackground = colors.text,
            onSurface = colors.text,
            onSurfaceVariant = colors.dim,
            outline = colors.hairlineStrong,
            outlineVariant = colors.hairline,
        )
    }
    CompositionLocalProvider(LocalBoxpixColors provides colors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = BoxpixTypography,
            content = content,
        )
    }
}
