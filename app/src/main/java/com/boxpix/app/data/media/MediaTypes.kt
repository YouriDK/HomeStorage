package com.boxpix.app.data.media

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

    fun isPhoto(fileName: String): Boolean = extensionOf(fileName) in PHOTO_EXTENSIONS

    fun isVideo(fileName: String): Boolean = extensionOf(fileName) in VIDEO_EXTENSIONS

    private fun extensionOf(fileName: String): String =
        fileName.substringAfterLast('.', "").lowercase()
}
