package com.boxpix.app.data.download

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.OutputStream
import javax.inject.Inject

/**
 * Writes a file into the device's Downloads — abstracted so the download queue
 * stays JVM-testable. The Android implementation uses MediaStore (API 29+,
 * no legacy storage permission); MediaStore auto-uniquifies conflicting names
 * ("photo (1).jpg").
 */
interface DeviceSaver {
    /** Streams content into Downloads; false when saving is impossible. */
    suspend fun save(displayName: String, mimeType: String?, write: suspend (OutputStream) -> Unit): Boolean
}

class AndroidDeviceSaver @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeviceSaver {

    override suspend fun save(
        displayName: String,
        mimeType: String?,
        write: suspend (OutputStream) -> Unit,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false // MediaStore.Downloads needs Q

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            mimeType?.let { put(MediaStore.Downloads.MIME_TYPE, it) }
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { write(it) } ?: return false
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            true
        } catch (e: Exception) {
            resolver.delete(uri, null, null) // discard the partial; the queue retries the file
            false
        }
    }
}
