package com.boxpix.app.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.boxpix.app.R
import javax.inject.Inject

/** Progress surface for the download queue — stubbed in JVM tests. */
interface DownloadNotifier {
    fun progress(fileName: String, index: Int, total: Int)
    fun done(savedCount: Int, failedCount: Int)
}

class AndroidDownloadNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : DownloadNotifier {

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    private fun ensureChannel() {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.download_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    override fun progress(fileName: String, index: Int, total: Int) {
        ensureChannel()
        manager.notify(
            NOTIFICATION_ID,
            android.app.Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(context.getString(R.string.download_channel))
                .setContentText(context.getString(R.string.download_progress, fileName, index, total))
                .setProgress(total, index - 1, false)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build(),
        )
    }

    override fun done(savedCount: Int, failedCount: Int) {
        ensureChannel()
        val summary = context.resources.getQuantityString(
            R.plurals.download_done, savedCount, savedCount,
        ) + if (failedCount > 0) {
            " · " + context.getString(R.string.download_failed_count, failedCount)
        } else {
            ""
        }
        manager.notify(
            NOTIFICATION_ID,
            android.app.Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(context.getString(R.string.download_done_title))
                .setContentText(summary)
                .setOngoing(false)
                .build(),
        )
    }

    private companion object {
        const val CHANNEL_ID = "boxpix_downloads"
        const val NOTIFICATION_ID = 77
    }
}
