package com.boxpix.app.ui.worker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.boxpix.app.R
import com.boxpix.app.data.db.WorkQueueDao
import com.boxpix.app.data.db.WorkQueueEntity
import com.boxpix.app.data.media.WorkerTelemetry
import com.boxpix.app.data.net.ConnectionMode
import com.boxpix.app.data.net.EndpointResolver
import com.boxpix.app.data.prefs.UiPrefsStore
import com.boxpix.app.data.storage.StorageEnv
import com.boxpix.app.data.trash.TrashRepository
import com.boxpix.app.ui.theme.boxpixColors
import com.boxpix.app.work.WorkerService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WorkerViewModel @Inject constructor(
    private val uiPrefs: UiPrefsStore,
    private val queueDao: WorkQueueDao,
    private val telemetry: WorkerTelemetry,
    private val env: StorageEnv,
    private val resolver: EndpointResolver,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    enum class Connection { FAKE, LAN, REMOTE, NONE }

    data class UiState(
        val enabled: Boolean = false,
        val running: Boolean = false,
        val paused: Boolean = false,
        val cycleActive: Boolean = false,
        val charging: Boolean = false,
        val cycles: Int = 0,
        val startedAtEpochSeconds: Long? = null,
        val nowEpochSeconds: Long = 0,
        val activeJob: WorkerTelemetry.ActiveJob? = null,
        val lastPasses: Map<String, Long> = emptyMap(),
        val errors: List<WorkerTelemetry.RecentError> = emptyList(),
        val pendingThumbs: Int = 0,
        val pendingVideoThumbs: Int = 0,
        val pendingXmp: Int = 0,
        val failedThumbs: Int = 0,
        val failedVideoThumbs: Int = 0,
        val failedXmp: Int = 0,
        val batteryPercent: Int? = null,
        val batteryTempC: Float? = null,
        val batteryCurrentMa: Int? = null,
        val connection: Connection = Connection.NONE,
    ) {
        val failedTotal: Int get() = failedThumbs + failedVideoThumbs + failedXmp
    }

    private data class Loop(
        val running: Boolean,
        val paused: Boolean,
        val cycleActive: Boolean,
        val cycles: Int,
        val startedAt: Long?,
    )

    private data class Work(
        val activeJob: WorkerTelemetry.ActiveJob?,
        val passes: Map<String, Long>,
        val errors: List<WorkerTelemetry.RecentError>,
    )

    private data class Counts(
        val pending: Triple<Int, Int, Int>,
        val failed: Triple<Int, Int, Int>,
    )

    private data class Vitals(
        val percent: Int? = null,
        val tempC: Float? = null,
        val currentMa: Int? = null,
        val charging: Boolean = false,
        val connection: Connection = Connection.NONE,
        val nowEpochSeconds: Long = 0,
    )

    private val loop = combine(
        telemetry.running,
        telemetry.paused,
        telemetry.cycleActive,
        telemetry.cycles,
        telemetry.startedAtEpochSeconds,
        ::Loop,
    )

    private val work = combine(
        telemetry.activeJob,
        telemetry.lastPassEpochSeconds,
        telemetry.recentErrors,
        ::Work,
    )

    private val counts = combine(
        combine(
            queueDao.pendingCountByType(PROVIDER, WorkQueueEntity.TYPE_THUMB),
            queueDao.pendingCountByType(PROVIDER, WorkQueueEntity.TYPE_VIDEO_THUMB),
            queueDao.pendingCountByType(PROVIDER, WorkQueueEntity.TYPE_XMP),
            ::Triple,
        ),
        combine(
            queueDao.failedCountByType(PROVIDER, WorkQueueEntity.TYPE_THUMB),
            queueDao.failedCountByType(PROVIDER, WorkQueueEntity.TYPE_VIDEO_THUMB),
            queueDao.failedCountByType(PROVIDER, WorkQueueEntity.TYPE_XMP),
            ::Triple,
        ),
        ::Counts,
    )

    /** Polled while the screen is visible only — the dashboard costs nothing asleep. */
    private val vitals = flow {
        while (true) {
            emit(readVitals())
            delay(VITALS_POLL_MS)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Vitals())

    val state = combine(
        uiPrefs.workerModeEnabled,
        loop,
        work,
        counts,
        vitals,
    ) { enabled, loop, work, counts, vitals ->
        UiState(
            enabled = enabled,
            running = loop.running,
            paused = loop.paused,
            cycleActive = loop.cycleActive,
            charging = vitals.charging,
            cycles = loop.cycles,
            startedAtEpochSeconds = loop.startedAt,
            nowEpochSeconds = vitals.nowEpochSeconds,
            activeJob = work.activeJob,
            lastPasses = work.passes,
            errors = work.errors,
            pendingThumbs = counts.pending.first,
            pendingVideoThumbs = counts.pending.second,
            pendingXmp = counts.pending.third,
            failedThumbs = counts.failed.first,
            failedVideoThumbs = counts.failed.second,
            failedXmp = counts.failed.third,
            batteryPercent = vitals.percent,
            batteryTempC = vitals.tempC,
            batteryCurrentMa = vitals.currentMa,
            connection = vitals.connection,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            uiPrefs.setWorkerModeEnabled(enabled)
            if (enabled) WorkerService.start(context) else WorkerService.stop(context)
        }
    }

    fun setPaused(paused: Boolean) = telemetry.setPaused(paused)

    fun forceResync() = telemetry.requestResync()

    fun retryErrors() {
        viewModelScope.launch { queueDao.retryFailed(PROVIDER) }
    }

    private suspend fun readVitals(): Vitals {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val tempTenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val microAmps = context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val connection = when {
            env.useFakeProvider.first() -> Connection.FAKE
            resolver.current?.mode == ConnectionMode.LAN -> Connection.LAN
            resolver.current?.mode == ConnectionMode.REMOTE -> Connection.REMOTE
            else -> Connection.NONE
        }
        return Vitals(
            percent = if (level >= 0 && scale > 0) level * 100 / scale else null,
            tempC = tempTenths?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f },
            currentMa = microAmps?.takeIf { it != 0 && it != Int.MIN_VALUE }?.let { it / 1000 },
            charging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0,
            connection = connection,
            nowEpochSeconds = System.currentTimeMillis() / 1000,
        )
    }

    private companion object {
        const val PROVIDER = TrashRepository.PROVIDER_FREEBOX
        const val VITALS_POLL_MS = 2_000L
    }
}

/**
 * SPEC M7 — the dedicated worker phone's dashboard. Deliberately no
 * keep-screen-on: the device lives with its screen off, this screen is
 * what you see when you wake it up.
 */
@Composable
fun WorkerScreen(
    onBack: () -> Unit,
    viewModel: WorkerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = boxpixColors

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.setEnabled(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = null,
                    tint = colors.text,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = stringResource(R.string.worker_title),
                style = MaterialTheme.typography.titleLarge,
                color = colors.text,
            )
        }

        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.worker_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.text,
                    )
                    Text(
                        stringResource(R.string.worker_toggle_hint),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.dim,
                    )
                }
                Switch(
                    checked = state.enabled,
                    onCheckedChange = { enable ->
                        if (enable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            viewModel.setEnabled(enable)
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = colors.accent,
                        checkedThumbColor = colors.bg,
                        uncheckedTrackColor = colors.hairline,
                        uncheckedThumbColor = colors.surface,
                        uncheckedBorderColor = colors.hairlineStrong,
                    ),
                )
            }

            Spacer(Modifier.height(8.dp))
            StatusCard(state)

            if (state.running) {
                Spacer(Modifier.height(4.dp))
                CommandsRow(
                    paused = state.paused,
                    failedTotal = state.failedTotal,
                    onPauseToggle = { viewModel.setPaused(!state.paused) },
                    onResync = viewModel::forceResync,
                    onRetry = viewModel::retryErrors,
                )
            }

            SectionTitle(stringResource(R.string.worker_section_queues))
            QueueRow(stringResource(R.string.worker_queue_photos), state.pendingThumbs, state.failedThumbs)
            QueueRow(stringResource(R.string.worker_queue_videos), state.pendingVideoThumbs, state.failedVideoThumbs)
            QueueRow(stringResource(R.string.worker_queue_xmp), state.pendingXmp, state.failedXmp)

            SectionTitle(stringResource(R.string.worker_section_passes))
            PassRow(stringResource(R.string.worker_pass_reconcile), state.lastPasses[WorkerTelemetry.PASS_RECONCILE])
            PassRow(stringResource(R.string.worker_pass_videos), state.lastPasses[WorkerTelemetry.PASS_VIDEO_THUMBS])
            PassRow(stringResource(R.string.worker_pass_xmp), state.lastPasses[WorkerTelemetry.PASS_XMP])
            PassRow(stringResource(R.string.worker_pass_purge), state.lastPasses[WorkerTelemetry.PASS_PURGE])

            if (state.errors.isNotEmpty()) {
                SectionTitle(stringResource(R.string.worker_section_errors))
                state.errors.forEach { ErrorRow(it) }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.worker_note),
                style = MaterialTheme.typography.bodySmall,
                color = colors.faint,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.elevated, RoundedCornerShape(10.dp))
                    .padding(12.dp),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StatusCard(state: WorkerViewModel.UiState) {
    val colors = boxpixColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.elevated, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        if (state.cycleActive) colors.accent else colors.hairlineStrong,
                        CircleShape,
                    ),
            )
            Spacer(Modifier.size(10.dp))
            Text(
                text = stringResource(
                    when {
                        !state.running -> R.string.worker_status_off
                        state.cycleActive -> R.string.worker_status_working
                        state.paused -> R.string.worker_status_paused
                        !state.charging -> R.string.worker_status_waiting
                        else -> R.string.worker_status_idle
                    },
                ),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = colors.text,
                modifier = Modifier.weight(1f),
            )
            state.startedAtEpochSeconds?.let { startedAt ->
                Text(
                    text = stringResource(
                        R.string.worker_uptime,
                        formatUptime(state.nowEpochSeconds - startedAt),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.dim,
                )
            }
        }

        state.activeJob?.let { job ->
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = job.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.text,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.worker_job_progress, job.index, job.total),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.dim,
                )
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { if (job.total > 0) job.index.toFloat() / job.total else 0f },
                color = colors.accent,
                trackColor = colors.hairline,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = listOfNotNull(
                stringResource(
                    when (state.connection) {
                        WorkerViewModel.Connection.FAKE -> R.string.worker_connection_fake
                        WorkerViewModel.Connection.LAN -> R.string.worker_connection_lan
                        WorkerViewModel.Connection.REMOTE -> R.string.worker_connection_remote
                        WorkerViewModel.Connection.NONE -> R.string.worker_connection_none
                    },
                ),
                stringResource(R.string.worker_cycles_count, state.cycles),
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = colors.dim,
        )
        state.batteryPercent?.let { percent ->
            val temp = state.batteryTempC?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "—"
            Spacer(Modifier.height(4.dp))
            Text(
                text = state.batteryCurrentMa?.let { ma ->
                    stringResource(R.string.worker_battery_line, percent, temp, "%+d".format(ma))
                } ?: stringResource(R.string.worker_battery_line_no_current, percent, temp),
                style = MaterialTheme.typography.labelMedium,
                color = colors.dim,
            )
        }
    }
}

@Composable
private fun CommandsRow(
    paused: Boolean,
    failedTotal: Int,
    onPauseToggle: () -> Unit,
    onResync: () -> Unit,
    onRetry: () -> Unit,
) {
    val colors = boxpixColors
    Row(modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = onPauseToggle) {
            Text(
                stringResource(if (paused) R.string.worker_action_resume else R.string.worker_action_pause),
                color = colors.accent,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        TextButton(onClick = onResync) {
            Text(
                stringResource(R.string.worker_action_resync),
                color = colors.accent,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        if (failedTotal > 0) {
            TextButton(onClick = onRetry) {
                Text(
                    stringResource(R.string.worker_action_retry) + " ($failedTotal)",
                    color = colors.accent,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = boxpixColors.faint,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun QueueRow(label: String, pending: Int, failed: Int) {
    val colors = boxpixColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.text,
            modifier = Modifier.weight(1f),
        )
        if (failed > 0) {
            Text(
                text = stringResource(R.string.worker_failed_count, failed),
                style = MaterialTheme.typography.labelMedium,
                color = colors.faint,
                modifier = Modifier.padding(end = 10.dp),
            )
        }
        Text(
            text = pluralStringResource(R.plurals.settings_pending, pending, pending),
            style = MaterialTheme.typography.labelMedium,
            color = colors.dim,
        )
    }
}

@Composable
private fun PassRow(label: String, atEpochSeconds: Long?) {
    val colors = boxpixColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.text,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = atEpochSeconds?.let { relativeTime(it) }
                ?: stringResource(R.string.worker_pass_never),
            style = MaterialTheme.typography.labelMedium,
            color = colors.dim,
        )
    }
}

@Composable
private fun ErrorRow(error: WorkerTelemetry.RecentError) {
    val colors = boxpixColors
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = error.fileName,
                style = MaterialTheme.typography.bodySmall,
                color = colors.text,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = relativeTime(error.atEpochSeconds),
                style = MaterialTheme.typography.labelSmall,
                color = colors.faint,
            )
        }
        Text(
            text = error.message,
            style = MaterialTheme.typography.labelSmall,
            color = colors.dim,
            maxLines = 2,
        )
    }
}

private fun relativeTime(epochSeconds: Long): String =
    DateUtils.getRelativeTimeSpanString(
        epochSeconds * 1000,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()

private fun formatUptime(seconds: Long): String {
    val clamped = seconds.coerceAtLeast(0)
    val days = clamped / 86_400
    val hours = clamped % 86_400 / 3_600
    val minutes = clamped % 3_600 / 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}
