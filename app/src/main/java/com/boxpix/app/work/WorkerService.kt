package com.boxpix.app.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.IBinder
import com.boxpix.app.MainActivity
import com.boxpix.app.R
import com.boxpix.app.data.media.Reconciler
import com.boxpix.app.data.media.VideoThumbProcessor
import com.boxpix.app.data.media.WorkerStatusFile
import com.boxpix.app.data.media.WorkerTelemetry
import com.boxpix.app.data.media.XmpQueueProcessor
import com.boxpix.app.data.net.NetworkStatus
import com.boxpix.app.data.trash.TrashRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * SPEC M7 — the dedicated worker phone: a foreground service that grinds the
 * heavy queues in cycles while plugged in and on unmetered network — full
 * reconciliation, photo thumbnail backlog, video posters, XMP drain (if the
 * switch is on), trash purge — and heartbeats to /.meta/worker-status.json.
 */
@AndroidEntryPoint
class WorkerService : Service() {

    @Inject lateinit var reconciler: Reconciler

    @Inject lateinit var videoThumbs: VideoThumbProcessor
    @Inject lateinit var backupMirror: com.boxpix.app.data.backup.BackupMirror
    @Inject lateinit var uiPrefs: com.boxpix.app.data.prefs.UiPrefsStore

    private suspend fun passEnabled(pass: String): Boolean =
        uiPrefs.workerPassEnabled(pass).first()

    @Inject lateinit var xmpProcessor: XmpQueueProcessor

    @Inject lateinit var trashRepository: TrashRepository

    @Inject lateinit var statusFile: WorkerStatusFile

    @Inject lateinit var network: NetworkStatus

    @Inject lateinit var telemetry: WorkerTelemetry

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var cycles = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startForeground(NOTIFICATION_ID, notification(getString(R.string.worker_notif_starting)))
        if (loop == null) {
            telemetry.serviceStarted()
            loop = serviceScope.launch { runLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        telemetry.serviceStopped()
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun runLoop() {
        while (serviceScope.isActive) {
            // A forced resync bypasses pause and charger — never a metered network.
            val forced = telemetry.consumeForcedResync()
            val canRun = network.isUnmetered() &&
                (forced || (!telemetry.paused.value && isCharging()))
            if (canRun) {
                notify(getString(R.string.worker_notif_working))
                telemetry.cycleStarted()
                runCatching {
                    // Every pass has its own switch in the dashboard (owner
                    // control); XMP keeps the global Settings switch instead.
                    if (passEnabled(WorkerTelemetry.PASS_RECONCILE)) {
                        reconciler.runPass(maxFolders = Int.MAX_VALUE, processLimit = HEAVY_LIMIT)
                        telemetry.passDone(WorkerTelemetry.PASS_RECONCILE)
                    }
                    if (passEnabled(WorkerTelemetry.PASS_VIDEO_THUMBS)) {
                        videoThumbs.process(HEAVY_LIMIT)
                        telemetry.passDone(WorkerTelemetry.PASS_VIDEO_THUMBS)
                    }
                    xmpProcessor.process(HEAVY_LIMIT)
                    telemetry.passDone(WorkerTelemetry.PASS_XMP)
                    if (passEnabled(WorkerTelemetry.PASS_PURGE)) {
                        trashRepository.purgeOlderThan()
                        telemetry.passDone(WorkerTelemetry.PASS_PURGE)
                    }
                    // Disk-to-disk mirror at the configured cadence: the box
                    // copies server-side; additive only, .trash excluded.
                    if (passEnabled(WorkerTelemetry.PASS_BACKUP) && backupMirror.runIfDue()) {
                        telemetry.passDone(WorkerTelemetry.PASS_BACKUP)
                    }
                    cycles++
                    statusFile.write(cycles)
                }
                telemetry.cycleEnded(cycles)
                notify(getString(R.string.worker_notif_idle, cycles))
                telemetry.awaitWake(uiPrefs.workerCycleMinutes.first() * 60_000L)
            } else {
                notify(
                    getString(
                        if (telemetry.paused.value) R.string.worker_notif_paused
                        else R.string.worker_notif_waiting,
                    ),
                )
                telemetry.awaitWake(WAIT_INTERVAL_MS)
            }
        }
    }

    private fun isCharging(): Boolean {
        val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.worker_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.worker_channel))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(text))
    }

    companion object {
        private const val CHANNEL_ID = "boxpix_worker"
        private const val NOTIFICATION_ID = 42
        private const val HEAVY_LIMIT = 2_000
        private const val CYCLE_INTERVAL_MS = 15L * 60 * 1000
        private const val WAIT_INTERVAL_MS = 5L * 60 * 1000

        fun start(context: Context) {
            context.startForegroundService(Intent(context, WorkerService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WorkerService::class.java))
        }
    }
}
