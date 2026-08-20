package com.boxpix.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.boxpix.app.data.media.FileKind
import com.boxpix.app.data.media.MediaTypes
import com.boxpix.app.ui.icons.Lucide
import com.boxpix.app.ui.theme.boxpixColors

fun FileKind.icon(): ImageVector = when (this) {
    FileKind.PHOTO -> Lucide.FileImage
    FileKind.VIDEO -> Lucide.FileVideo
    FileKind.PDF -> Lucide.FileText
    FileKind.ARCHIVE -> Lucide.FileArchive
    FileKind.AUDIO -> Lucide.FileAudio
    FileKind.DOCUMENT -> Lucide.FileType
    FileKind.SPREADSHEET -> Lucide.FileSpreadsheet
    FileKind.CODE -> Lucide.FileCode
    FileKind.OTHER -> Lucide.File
}

/**
 * One muted hue per kind — mid-lightness tones stay legible on both the light
 * surface and the AMOLED black one. OTHER stays neutral (theme's dim).
 */
private fun FileKind.hue(): Color? = when (this) {
    FileKind.PHOTO -> Color(0xFF5B9BD5)
    FileKind.VIDEO -> Color(0xFFD98E4A)
    FileKind.PDF -> Color(0xFFD9534F)
    FileKind.ARCHIVE -> Color(0xFFC9A227)
    FileKind.AUDIO -> Color(0xFF9A6BD0)
    FileKind.DOCUMENT -> Color(0xFF4A8FD9)
    FileKind.SPREADSHEET -> Color(0xFF3FA56A)
    FileKind.CODE -> Color(0xFF6B7BD6)
    FileKind.OTHER -> null
}

@Composable
fun FileKindIcon(
    kind: FileKind,
    modifier: Modifier = Modifier,
    tint: Color = kind.hue() ?: boxpixColors.dim,
) {
    Icon(
        imageVector = kind.icon(),
        contentDescription = null,
        tint = tint,
        modifier = modifier,
    )
}

/**
 * Flat stand-in for files that never get a thumbnail (anything that is not a
 * photo or a video): surface with a soft wash of the kind's hue, centered
 * icon, extension label — same tint recipe as accentSoft, both themes.
 */
@Composable
fun FileKindPlaceholder(
    kind: FileKind,
    fileName: String,
    modifier: Modifier = Modifier,
    iconSize: Dp = 26.dp,
) {
    val colors = boxpixColors
    val hue = kind.hue()
    Column(
        modifier = modifier
            .background(colors.surface)
            .then(hue?.let { Modifier.background(it.copy(alpha = 0.10f)) } ?: Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        FileKindIcon(kind = kind, modifier = Modifier.size(iconSize))
        val extension = MediaTypes.extensionOf(fileName)
        if (extension.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = extension.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = hue?.copy(alpha = 0.85f) ?: colors.faint,
            )
        }
    }
}
