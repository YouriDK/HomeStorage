package com.boxpix.app.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTypesTest {

    @Test
    fun `kind follows the extension`() {
        assertEquals(FileKind.PHOTO, MediaTypes.kindOf("scan-001.jpg"))
        assertEquals(FileKind.PHOTO, MediaTypes.kindOf("shot.heic"))
        assertEquals(FileKind.VIDEO, MediaTypes.kindOf("clip.mp4"))
        assertEquals(FileKind.PDF, MediaTypes.kindOf("notes.pdf"))
        assertEquals(FileKind.ARCHIVE, MediaTypes.kindOf("backup.zip"))
        assertEquals(FileKind.ARCHIVE, MediaTypes.kindOf("dump.tar"))
        assertEquals(FileKind.AUDIO, MediaTypes.kindOf("song.flac"))
        assertEquals(FileKind.DOCUMENT, MediaTypes.kindOf("letter.docx"))
        assertEquals(FileKind.DOCUMENT, MediaTypes.kindOf("readme.md"))
        assertEquals(FileKind.SPREADSHEET, MediaTypes.kindOf("budget.xlsx"))
        assertEquals(FileKind.SPREADSHEET, MediaTypes.kindOf("export.csv"))
        assertEquals(FileKind.CODE, MediaTypes.kindOf("config.json"))
        assertEquals(FileKind.CODE, MediaTypes.kindOf("Main.kt"))
        assertEquals(FileKind.OTHER, MediaTypes.kindOf("mystery.xyz"))
    }

    @Test
    fun `extension matching is case-insensitive`() {
        assertEquals(FileKind.PHOTO, MediaTypes.kindOf("IMG_0001.JPG"))
        assertEquals(FileKind.PDF, MediaTypes.kindOf("SCAN.PDF"))
        assertTrue(MediaTypes.isPhoto("photo.JPEG"))
        assertTrue(MediaTypes.isVideo("FILM.MP4"))
    }

    @Test
    fun `no extension or dotfile means Other`() {
        assertEquals(FileKind.OTHER, MediaTypes.kindOf("README"))
        assertEquals(FileKind.OTHER, MediaTypes.kindOf(".hidden"))
        assertEquals("", MediaTypes.extensionOf("README"))
    }

    @Test
    fun `only the last extension counts`() {
        assertEquals(FileKind.ARCHIVE, MediaTypes.kindOf("photos.tar.gz"))
        assertEquals(FileKind.PHOTO, MediaTypes.kindOf("archive.zip.jpg"))
    }

    @Test
    fun `job gate agrees with kind`() {
        assertTrue(MediaTypes.isPhoto("a.png") && MediaTypes.kindOf("a.png") == FileKind.PHOTO)
        assertTrue(MediaTypes.isVideo("a.mkv") && MediaTypes.kindOf("a.mkv") == FileKind.VIDEO)
        assertFalse(MediaTypes.isPhoto("a.pdf"))
        assertFalse(MediaTypes.isVideo("a.pdf"))
    }
}
