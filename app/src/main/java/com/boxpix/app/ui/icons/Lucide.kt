package com.boxpix.app.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The app's single icon set: Lucide (https://lucide.dev, ISC license), 24x24,
 * one stroke weight (2), round caps and joins. Generated from the official
 * SVGs — do not edit path data by hand; regenerate instead.
 */
object Lucide {

    val ArrowLeft: ImageVector by lazy {
        lucide(
            "arrow-left",
            autoMirror = true,
            paths = arrayOf(
        "m12 19-7-7 7-7",
        "M19 12H5",
            ),
        )
    }

    val CheckCheck: ImageVector by lazy {
        lucide(
            "check-check",
            autoMirror = false,
            paths = arrayOf(
        "M18 6 7 17l-5-5",
        "m22 10-7.5 7.5L13 16",
            ),
        )
    }

    val Check: ImageVector by lazy {
        lucide(
            "check",
            autoMirror = false,
            paths = arrayOf(
        "M20 6 9 17l-5-5",
            ),
        )
    }

    val ChevronDown: ImageVector by lazy {
        lucide(
            "chevron-down",
            autoMirror = false,
            paths = arrayOf(
        "m6 9 6 6 6-6",
            ),
        )
    }

    val ChevronRight: ImageVector by lazy {
        lucide(
            "chevron-right",
            autoMirror = false,
            paths = arrayOf(
        "m9 18 6-6-6-6",
            ),
        )
    }

    val ChevronUp: ImageVector by lazy {
        lucide(
            "chevron-up",
            autoMirror = false,
            paths = arrayOf(
        "m18 15-6-6-6 6",
            ),
        )
    }

    val Download: ImageVector by lazy {
        lucide(
            "download",
            autoMirror = false,
            paths = arrayOf(
        "M12 15V3",
        "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4",
        "m7 10 5 5 5-5",
            ),
        )
    }

    val EllipsisVertical: ImageVector by lazy {
        lucide(
            "ellipsis-vertical",
            autoMirror = false,
            paths = arrayOf(
        "M11 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0",
        "M11 5a1 1 0 1 0 2 0a1 1 0 1 0 -2 0",
        "M11 19a1 1 0 1 0 2 0a1 1 0 1 0 -2 0",
            ),
        )
    }

    val EyeOff: ImageVector by lazy {
        lucide(
            "eye-off",
            autoMirror = false,
            paths = arrayOf(
        "M10.733 5.076a10.744 10.744 0 0 1 11.205 6.575 1 1 0 0 1 0 .696 10.747 10.747 0 0 1-1.444 2.49",
        "M14.084 14.158a3 3 0 0 1-4.242-4.242",
        "M17.479 17.499a10.75 10.75 0 0 1-15.417-5.151 1 1 0 0 1 0-.696 10.75 10.75 0 0 1 4.446-5.143",
        "m2 2 20 20",
            ),
        )
    }

    val File: ImageVector by lazy {
        lucide(
            "file",
            autoMirror = false,
            paths = arrayOf(
        "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z",
        "M14 2v4a2 2 0 0 0 2 2h4",
            ),
        )
    }

    val FolderInput: ImageVector by lazy {
        lucide(
            "folder-input",
            autoMirror = true,
            paths = arrayOf(
        "M2 9V5a2 2 0 0 1 2-2h3.9a2 2 0 0 1 1.69.9l.81 1.2a2 2 0 0 0 1.67.9H20a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-1",
        "M2 13h10",
        "m9 16 3-3-3-3",
            ),
        )
    }

    val Folder: ImageVector by lazy {
        lucide(
            "folder",
            autoMirror = false,
            paths = arrayOf(
        "M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z",
            ),
        )
    }

    val Hand: ImageVector by lazy {
        lucide(
            "hand",
            autoMirror = false,
            paths = arrayOf(
        "M18 11V6a2 2 0 0 0-2-2a2 2 0 0 0-2 2",
        "M14 10V4a2 2 0 0 0-2-2a2 2 0 0 0-2 2v2",
        "M10 10.5V6a2 2 0 0 0-2-2a2 2 0 0 0-2 2v8",
        "M18 8a2 2 0 1 1 4 0v6a8 8 0 0 1-8 8h-2c-2.8 0-4.5-.86-5.99-2.34l-3.6-3.6a2 2 0 0 1 2.83-2.82L7 15",
            ),
        )
    }

    val HardDrive: ImageVector by lazy {
        lucide(
            "hard-drive",
            autoMirror = false,
            paths = arrayOf(
        "M22 12L2 12",
        "M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z",
        "M6 16L6.01 16",
        "M10 16L10.01 16",
            ),
        )
    }

    val Heart: ImageVector by lazy {
        lucide(
            "heart",
            autoMirror = false,
            paths = arrayOf(
        "M2 9.5a5.5 5.5 0 0 1 9.591-3.676.56.56 0 0 0 .818 0A5.49 5.49 0 0 1 22 9.5c0 2.29-1.5 4-3 5.5l-5.492 5.313a2 2 0 0 1-3 .019L5 15c-1.5-1.5-3-3.2-3-5.5",
            ),
        )
    }

    val HeartFilled: ImageVector by lazy {
        lucide(
            "heart-filled",
            filled = true,
            paths = arrayOf(
        "M2 9.5a5.5 5.5 0 0 1 9.591-3.676.56.56 0 0 0 .818 0A5.49 5.49 0 0 1 22 9.5c0 2.29-1.5 4-3 5.5l-5.492 5.313a2 2 0 0 1-3 .019L5 15c-1.5-1.5-3-3.2-3-5.5",
            ),
        )
    }

    val Info: ImageVector by lazy {
        lucide(
            "info",
            autoMirror = false,
            paths = arrayOf(
        "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0",
        "M12 16v-4",
        "M12 8h.01",
            ),
        )
    }

    val LockOpen: ImageVector by lazy {
        lucide(
            "lock-open",
            autoMirror = false,
            paths = arrayOf(
        "M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-7a2 2 0 0 1 2 -2Z",
        "M7 11V7a5 5 0 0 1 9.9-1",
            ),
        )
    }

    val Lock: ImageVector by lazy {
        lucide(
            "lock",
            autoMirror = false,
            paths = arrayOf(
        "M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-7a2 2 0 0 1 2 -2Z",
        "M7 11V7a5 5 0 0 1 10 0v4",
            ),
        )
    }

    val Pause: ImageVector by lazy {
        lucide(
            "pause",
            autoMirror = false,
            paths = arrayOf(
        "M15 3h3a1 1 0 0 1 1 1v16a1 1 0 0 1 -1 1h-3a1 1 0 0 1 -1 -1v-16a1 1 0 0 1 1 -1Z",
        "M6 3h3a1 1 0 0 1 1 1v16a1 1 0 0 1 -1 1h-3a1 1 0 0 1 -1 -1v-16a1 1 0 0 1 1 -1Z",
            ),
        )
    }

    val Pencil: ImageVector by lazy {
        lucide(
            "pencil",
            autoMirror = false,
            paths = arrayOf(
        "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z",
        "m15 5 4 4",
            ),
        )
    }

    val Play: ImageVector by lazy {
        lucide(
            "play",
            autoMirror = false,
            paths = arrayOf(
        "M5 5a2 2 0 0 1 3.008-1.728l11.997 6.998a2 2 0 0 1 .003 3.458l-12 7A2 2 0 0 1 5 19z",
            ),
        )
    }

    val PlayFilled: ImageVector by lazy {
        lucide(
            "play-filled",
            filled = true,
            paths = arrayOf(
        "M5 5a2 2 0 0 1 3.008-1.728l11.997 6.998a2 2 0 0 1 .003 3.458l-12 7A2 2 0 0 1 5 19z",
            ),
        )
    }

    val Plus: ImageVector by lazy {
        lucide(
            "plus",
            autoMirror = false,
            paths = arrayOf(
        "M5 12h14",
        "M12 5v14",
            ),
        )
    }

    val Search: ImageVector by lazy {
        lucide(
            "search",
            autoMirror = false,
            paths = arrayOf(
        "m21 21-4.34-4.34",
        "M3 11a8 8 0 1 0 16 0a8 8 0 1 0 -16 0",
            ),
        )
    }

    val Settings: ImageVector by lazy {
        lucide(
            "settings",
            autoMirror = false,
            paths = arrayOf(
        "M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915",
        "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0",
            ),
        )
    }

    val Share2: ImageVector by lazy {
        lucide(
            "share-2",
            autoMirror = false,
            paths = arrayOf(
        "M15 5a3 3 0 1 0 6 0a3 3 0 1 0 -6 0",
        "M3 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0",
        "M15 19a3 3 0 1 0 6 0a3 3 0 1 0 -6 0",
        "M8.59 13.51L15.42 17.49",
        "M15.41 6.51L8.59 10.49",
            ),
        )
    }

    val SkipBack: ImageVector by lazy {
        lucide(
            "skip-back",
            autoMirror = false,
            paths = arrayOf(
        "M17.971 4.285A2 2 0 0 1 21 6v12a2 2 0 0 1-3.029 1.715l-9.997-5.998a2 2 0 0 1-.003-3.432z",
        "M3 20V4",
            ),
        )
    }

    val SkipForward: ImageVector by lazy {
        lucide(
            "skip-forward",
            autoMirror = false,
            paths = arrayOf(
        "M21 4v16",
        "M6.029 4.285A2 2 0 0 0 3 6v12a2 2 0 0 0 3.029 1.715l9.997-5.998a2 2 0 0 0 .003-3.432z",
            ),
        )
    }

    val SlidersHorizontal: ImageVector by lazy {
        lucide(
            "sliders-horizontal",
            autoMirror = false,
            paths = arrayOf(
        "M10 5H3",
        "M12 19H3",
        "M14 3v4",
        "M16 17v4",
        "M21 12h-9",
        "M21 19h-5",
        "M21 5h-7",
        "M8 10v4",
        "M8 12H3",
            ),
        )
    }

    val Tag: ImageVector by lazy {
        lucide(
            "tag",
            autoMirror = false,
            paths = arrayOf(
        "M12.586 2.586A2 2 0 0 0 11.172 2H4a2 2 0 0 0-2 2v7.172a2 2 0 0 0 .586 1.414l8.704 8.704a2.426 2.426 0 0 0 3.42 0l6.58-6.58a2.426 2.426 0 0 0 0-3.42z",
        "M7 7.5a0.5 0.5 0 1 0 1 0a0.5 0.5 0 1 0 -1 0",
            ),
        )
    }

    val Trash2: ImageVector by lazy {
        lucide(
            "trash-2",
            autoMirror = false,
            paths = arrayOf(
        "M10 11v6",
        "M14 11v6",
        "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6",
        "M3 6h18",
        "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2",
            ),
        )
    }

    val Trash: ImageVector by lazy {
        lucide(
            "trash",
            autoMirror = false,
            paths = arrayOf(
        "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6",
        "M3 6h18",
        "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2",
            ),
        )
    }

    val Undo2: ImageVector by lazy {
        lucide(
            "undo-2",
            autoMirror = true,
            paths = arrayOf(
        "M9 14 4 9l5-5",
        "M4 9h10.5a5.5 5.5 0 0 1 5.5 5.5a5.5 5.5 0 0 1-5.5 5.5H11",
            ),
        )
    }

    val Wifi: ImageVector by lazy {
        lucide(
            "wifi",
            autoMirror = false,
            paths = arrayOf(
        "M12 20h.01",
        "M2 8.82a15 15 0 0 1 20 0",
        "M5 12.859a10 10 0 0 1 14 0",
        "M8.5 16.429a5 5 0 0 1 7 0",
            ),
        )
    }

    val X: ImageVector by lazy {
        lucide(
            "x",
            autoMirror = false,
            paths = arrayOf(
        "M18 6 6 18",
        "m6 6 12 12",
            ),
        )
    }
}

private fun lucide(
    name: String,
    paths: Array<String>,
    autoMirror: Boolean = false,
    filled: Boolean = false,
): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = autoMirror,
    ).apply {
        paths.forEach { d ->
            addPath(
                pathData = addPathNodes(d),
                fill = if (filled) SolidColor(Color.Black) else null,
                stroke = if (filled) null else SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()
