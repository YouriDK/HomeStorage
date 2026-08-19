package com.boxpix.app.ui.worker

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.boxpix.app.R
import com.boxpix.app.data.db.WorkQueueDao
import com.boxpix.app.data.db.WorkQueueEntity
import com.boxpix.app.data.prefs.UiPrefsStore
import com.boxpix.app.data.trash.TrashRepository
import com.boxpix.app.ui.theme.boxpixColors
import com.boxpix.app.work.WorkerService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WorkerViewModel @Inject constructor(
    private val uiPrefs: UiPrefsStore,
    queueDao: WorkQueueDao,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    data class UiState(
        val enabled: Boolean = false,
        val pendingThumbs: Int = 0,
        val pendingVideoThumbs: Int = 0,
        val pendingXmp: Int = 0,
    )

    val state = combine(
        uiPrefs.workerModeEnabled,
        queueDao.pendingCountByType(TrashRepository.PROVIDER_FREEBOX, WorkQueueEntity.TYPE_THUMB),
        queueDao.pendingCountByType(TrashRepository.PROVIDER_FREEBOX, WorkQueueEntity.TYPE_VIDEO_THUMB),
        queueDao.pendingCountByType(TrashRepository.PROVIDER_FREEBOX, WorkQueueEntity.TYPE_XMP),
    ) { enabled, thumbs, videos, xmp ->
        UiState(enabled, thumbs, videos, xmp)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            uiPrefs.setWorkerModeEnabled(enabled)
            if (enabled) WorkerService.start(context) else WorkerService.stop(context)
        }
    }
}

/** SPEC M7 — the dedicated worker phone's control panel. */
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

        Column(modifier = Modifier.padding(horizontal = 18.dp)) {
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

            Spacer(Modifier.height(16.dp))
            QueueRow(stringResource(R.string.worker_queue_photos), state.pendingThumbs)
            QueueRow(stringResource(R.string.worker_queue_videos), state.pendingVideoThumbs)
            QueueRow(stringResource(R.string.worker_queue_xmp), state.pendingXmp)

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
        }
    }
}

@Composable
private fun QueueRow(label: String, pending: Int) {
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
        Text(
            text = pluralStringResource(R.plurals.settings_pending, pending, pending),
            style = MaterialTheme.typography.labelMedium,
            color = colors.dim,
        )
    }
}
