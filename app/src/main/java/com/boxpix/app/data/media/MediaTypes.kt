package com.boxpix.app.data.media

/** What a file is, judged by its extension alone — drives icons and placeholders. */
enum class FileKind { PHOTO, VIDEO, PDF, ARCHIVE, AUDIO, DOCUMENT, SPREADSHEET, CODE, OTHER }

/**
 * Extension gate for background jobs (V1 feedback): a file that is not a
 * known photo or video by extension never creates a job — no thumbnail
 * attempt for pdf/zip/sidecars, whatever mime the box reports.
 */
object MediaTypes {

    private val PHOTO_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "avif", "dng",
    )
    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "m4v", "mov", "mkv", "webm", "3gp", "avi", "mts", "m2ts",
    )
    private val ARCHIVE_EXTENSIONS = setOf(
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz",
    )
    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "flac", "wav", "aac", "ogg", "opus", "m4a", "wma",
    )
    private val DOCUMENT_EXTENSIONS = setOf(
        "doc", "docx", "odt", "rtf", "txt", "md", "epub", "pages",
    )
    private val SPREADSHEET_EXTENSIONS = setOf(
        "xls", "xlsx", "ods", "csv", "numbers",
    )
    private val CODE_EXTENSIONS = setOf(
        "json", "xml", "html", "css", "js", "ts", "kt", "java", "py", "sh",
        "yaml", "yml", "sql", "c", "cpp", "h",
    )

    fun isPhoto(fileName: String): Boolean = extensionOf(fileName) in PHOTO_EXTENSIONS

    fun isVideo(fileName: String): Boolean = extensionOf(fileName) in VIDEO_EXTENSIONS

    fun kindOf(fileName: String): FileKind = when (extensionOf(fileName)) {
        in PHOTO_EXTENSIONS -> FileKind.PHOTO
        in VIDEO_EXTENSIONS -> FileKind.VIDEO
        "pdf" -> FileKind.PDF
        in ARCHIVE_EXTENSIONS -> FileKind.ARCHIVE
        in AUDIO_EXTENSIONS -> FileKind.AUDIO
        in DOCUMENT_EXTENSIONS -> FileKind.DOCUMENT
        in SPREADSHEET_EXTENSIONS -> FileKind.SPREADSHEET
        in CODE_EXTENSIONS -> FileKind.CODE
        else -> FileKind.OTHER
    }

    fun extensionOf(fileName: String): String =
        fileName.substringAfterLast('.', "").lowercase()
}
