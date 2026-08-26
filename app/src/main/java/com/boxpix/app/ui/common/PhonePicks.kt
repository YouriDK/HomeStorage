package com.boxpix.app.ui.common

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.boxpix.app.data.upload.PhoneUploader.PhoneFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns system-picker results into lazy upload sources: bytes are read from
 * the ContentResolver only when a file's turn comes, one at a time, on IO.
 */
object PhonePicks {

    fun mediaSources(context: Context, uris: List<Uri>): List<suspend () -> PhoneFile?> =
        uris.map { uri ->
            {
                withContext(Dispatchers.IO) {
                    val name = displayNameOf(context, uri) ?: return@withContext null
                    readBytes(context, uri)?.let { PhoneFile(name, it) }
                }
            }
        }

    /**
     * Everything under a picked folder (SAF tree), subfolders recreated on the
     * disk; hidden entries (dot names) are skipped.
     */
    fun treeSources(context: Context, treeUri: Uri): List<suspend () -> PhoneFile?> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val files = ArrayList<Pair<DocumentFile, String>>()
        fun walk(dir: DocumentFile, relative: String) {
            dir.listFiles().forEach { child ->
                val name = child.name ?: return@forEach
                if (name.startsWith(".")) return@forEach
                if (child.isDirectory) {
                    walk(child, if (relative.isEmpty()) name else "$relative/$name")
                } else if (child.isFile) {
                    files += child to relative
                }
            }
        }
        walk(root, "")
        return files.map { (doc, relative) ->
            {
                withContext(Dispatchers.IO) {
                    val name = doc.name ?: return@withContext null
                    readBytes(context, doc.uri)?.let { PhoneFile(name, it, relative) }
                }
            }
        }
    }

    private fun displayNameOf(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun readBytes(context: Context, uri: Uri): ByteArray? =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
}
