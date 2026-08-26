package com.boxpix.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Semantic accent hues shared across screens — mid-lightness so they read on
 * both the light and AMOLED-black surfaces (same spirit as FileKind hues).
 */
object Hues {
    /** Folders everywhere: tiles, pickers, pinned destinations. */
    val Folder = Color(0xFFD9B25F)

    /** Favourites: the heart, in grids and the viewer. */
    val Favorite = Color(0xFFF06292)

    /** Inline error accents (wrong vault passphrase, destructive hints). */
    val Danger = Color(0xFFE57373)
}
