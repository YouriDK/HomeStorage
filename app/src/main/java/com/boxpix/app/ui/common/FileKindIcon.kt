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

@Composable
fun FileKindIcon(
    kind: FileKind,
    modifier: Modifier = Modifier,
    tint: Color = boxpixColors.dim,
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
 * photo or a video): surface background, centered kind icon, extension label.
 */
@Composable
fun FileKindPlaceholder(
    kind: FileKind,
    fileName: String,
    modifier: Modifier = Modifier,
    iconSize: Dp = 26.dp,
) {
    val colors = boxpixColors
    Column(
        modifier = modifier.background(colors.surface),
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
                color = colors.faint,
            )
        }
    }
}
