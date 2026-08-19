package com.boxpix.app.ui.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boxpix.app.R
import com.boxpix.app.data.net.ConnectionMode
import com.boxpix.app.data.prefs.UiPrefsStore
import com.boxpix.app.ui.common.formatDate
import com.boxpix.app.ui.explorer.NameDialog
import com.boxpix.app.ui.theme.AccentPresets
import com.boxpix.app.ui.theme.boxpixColors

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenTags: () -> Unit,
    onOpenWorker: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val exportUri by viewModel.exportUri.collectAsStateWithLifecycle()
    val colors = boxpixColors
    val context = LocalContext.current
    var showExportPassphrase by remember { mutableStateOf(false) }

    LaunchedEffect(exportUri) {
        exportUri?.let { uri ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, null))
            viewModel.consumeExport()
        }
    }

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
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleLarge,
                color = colors.text,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
        ) {
            if (!state.useFake) {
                ConnectionCard(state.connection)
            }

            GroupLabel(stringResource(R.string.settings_group_appearance))
            SettingRow(name = stringResource(R.string.settings_theme), sub = null) {
                SegmentedControlText(
                    options = listOf(
                        UiPrefsStore.THEME_SYSTEM to stringResource(R.string.theme_system),
                        UiPrefsStore.THEME_LIGHT to stringResource(R.string.theme_light),
                        UiPrefsStore.THEME_DARK to stringResource(R.string.theme_dark),
                    ),
                    selected = state.themeMode,
                    onSelect = viewModel::setThemeMode,
                )
            }
            SettingRow(name = stringResource(R.string.settings_accent), sub = null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AccentPresets.all.forEach { preset ->
                        val selected = preset.key == state.accentPreset
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .background(preset.dark, CircleShape)
                                .then(
                                    if (selected) {
                                        Modifier.border(2.dp, colors.text, CircleShape)
                                    } else {
                                        Modifier.border(1.dp, colors.hairlineStrong, CircleShape)
                                    },
                                )
                                .clickable { viewModel.setAccentPreset(preset.key) },
                        )
                    }
                }
            }
            SettingRow(
                name = stringResource(R.string.settings_grid_columns),
                sub = stringResource(R.string.settings_grid_columns_hint),
            ) {
                SegmentedControlText(
                    options = listOf("2" to "2", "3" to "3", "4" to "4"),
                    selected = state.gridColumns.toString(),
                    onSelect = { viewModel.setGridColumns(it.toInt()) },
                )
            }
            HairlineDivider()

            GroupLabel(stringResource(R.string.settings_group_sync))
            SettingRow(
                name = stringResource(R.string.settings_thumb_queue),
                sub = pluralStringResource(R.plurals.settings_pending, state.thumbQueue, state.thumbQueue),
            ) {}
            SettingRow(
                name = stringResource(R.string.settings_xmp_switch),
                sub = stringResource(R.string.settings_xmp_switch_hint),
            ) {
                BoxpixSwitch(checked = state.xmpEnabled, onChecked = viewModel::setXmpEnabled)
            }
            if (state.xmpEnabled) {
                SettingRow(
                    name = stringResource(R.string.settings_xmp_queue),
                    sub = pluralStringResource(R.plurals.settings_pending, state.xmpQueue, state.xmpQueue),
                ) {}
            }
            SettingRow(
                name = stringResource(R.string.settings_worker),
                sub = state.workerLastSeenEpochSeconds?.let {
                    stringResource(R.string.settings_worker_seen, formatDate(it))
                } ?: stringResource(R.string.settings_worker_never),
                onClick = onOpenWorker,
            ) {
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = colors.faint,
                    modifier = Modifier.size(18.dp),
                )
            }
            SettingRow(
                name = stringResource(R.string.settings_resync),
                sub = stringResource(
                    R.string.settings_last_pass,
                    state.lastPassAtEpochSeconds?.let { formatDate(it) }
                        ?: stringResource(R.string.settings_never),
                ),
                onClick = if (state.syncing) null else viewModel::resyncNow,
            ) {
                if (state.syncing) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = colors.accent,
                        trackColor = colors.hairline,
                        strokeWidth = 1.5.dp,
                    )
                }
            }
            HairlineDivider()

            GroupLabel(stringResource(R.string.tag_picker_title))
            SettingRow(
                name = stringResource(R.string.settings_manage_tags),
                sub = null,
                onClick = onOpenTags,
            ) {
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = colors.faint,
                    modifier = Modifier.size(18.dp),
                )
            }
            HairlineDivider()

            GroupLabel(stringResource(R.string.settings_group_trash))
            SettingRow(
                name = stringResource(R.string.settings_trash_row),
                sub = stringResource(R.string.settings_trash_auto),
                onClick = onOpenTrash,
            ) {
                Text(
                    text = pluralStringResource(R.plurals.explorer_items_count, state.trashCount, state.trashCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.dim,
                )
                Spacer(Modifier.size(4.dp))
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = colors.faint,
                    modifier = Modifier.size(18.dp),
                )
            }
            HairlineDivider()

            GroupLabel(stringResource(R.string.settings_group_security))
            SettingRow(
                name = stringResource(R.string.settings_app_lock),
                sub = stringResource(R.string.settings_app_lock_hint),
            ) {
                BoxpixSwitch(checked = state.appLockEnabled, onChecked = viewModel::setAppLockEnabled)
            }
            HairlineDivider()

            if (!state.useFake) {
                GroupLabel(stringResource(R.string.settings_group_connection))
                SettingRow(
                    name = stringResource(R.string.settings_export),
                    sub = stringResource(R.string.settings_export_hint),
                    onClick = { showExportPassphrase = true },
                ) {}
                SettingRow(
                    name = stringResource(R.string.settings_change_root),
                    sub = stringResource(R.string.settings_change_root_hint),
                    onClick = viewModel::changeRootFolder,
                ) {}
                SettingRow(
                    name = stringResource(R.string.settings_reset_pairing),
                    sub = stringResource(R.string.settings_reset_pairing_hint),
                    onClick = viewModel::resetPairing,
                ) {}
                HairlineDivider()
            }

            if (state.hasFakeControls) {
                GroupLabel(stringResource(R.string.settings_group_debug))
                SettingRow(
                    name = stringResource(R.string.settings_use_fake),
                    sub = stringResource(R.string.settings_use_fake_hint),
                ) {
                    BoxpixSwitch(checked = state.useFake, onChecked = viewModel::setUseFake)
                }
                if (state.useFake) {
                    SettingRow(
                        name = stringResource(R.string.settings_sleep_disk),
                        sub = stringResource(R.string.settings_sleep_disk_hint),
                        onClick = viewModel::sleepDisk,
                    ) {}
                    SettingRow(
                        name = stringResource(R.string.settings_reset_fake),
                        sub = null,
                        onClick = viewModel::resetFakeData,
                    ) {}
                }
                HairlineDivider()
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showExportPassphrase) {
        NameDialog(
            title = stringResource(R.string.export_passphrase_title),
            initialValue = "",
            confirmLabel = stringResource(R.string.dialog_export),
            onConfirm = { passphrase ->
                showExportPassphrase = false
                if (passphrase.isNotBlank()) viewModel.exportConfig(passphrase)
            },
            onDismiss = { showExportPassphrase = false },
        )
    }
}

@Composable
private fun ConnectionCard(info: SettingsViewModel.ConnectionInfo) {
    val colors = boxpixColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            .border(1.dp, colors.hairline, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(
                        if (info.mode != null) colors.accent else colors.faint,
                        CircleShape,
                    ),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = when (info.mode) {
                    ConnectionMode.LAN -> stringResource(R.string.badge_lan)
                    ConnectionMode.REMOTE -> stringResource(R.string.badge_remote)
                    null -> stringResource(R.string.badge_offline)
                } + (info.latencyMs?.let { " · ${it} ms" } ?: ""),
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = listOfNotNull(info.boxName, info.diskName).joinToString(" · ")
                .ifEmpty { info.rootDisplayPath.orEmpty() },
            style = MaterialTheme.typography.bodySmall,
            color = colors.dim,
        )
        info.rootDisplayPath?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = colors.faint,
                fontWeight = FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun BoxpixSwitch(checked: Boolean, onChecked: (Boolean) -> Unit) {
    val colors = boxpixColors
    Switch(
        checked = checked,
        onCheckedChange = onChecked,
        colors = SwitchDefaults.colors(
            checkedTrackColor = colors.accent,
            checkedThumbColor = colors.bg,
            uncheckedTrackColor = colors.hairline,
            uncheckedThumbColor = colors.surface,
            uncheckedBorderColor = colors.hairlineStrong,
        ),
    )
}

@Composable
private fun GroupLabel(label: String) {
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = boxpixColors.faint,
        modifier = Modifier.padding(top = 22.dp, bottom = 6.dp),
    )
}

@Composable
private fun SettingRow(
    name: String,
    sub: String?,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    val colors = boxpixColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, color = colors.text)
            if (sub != null) {
                Text(sub, style = MaterialTheme.typography.labelMedium, color = colors.dim)
            }
        }
        trailing()
    }
}

@Composable
private fun SegmentedControlText(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val colors = boxpixColors
    Row(
        modifier = Modifier
            .border(1.dp, colors.hairlineStrong, RoundedCornerShape(8.dp))
            .padding(2.dp),
    ) {
        options.forEach { (key, label) ->
            val active = key == selected
            Box(
                modifier = Modifier
                    .background(
                        if (active) colors.accentSoft else colors.bg.copy(alpha = 0f),
                        RoundedCornerShape(6.dp),
                    )
                    .clickable { onSelect(key) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    color = if (active) colors.accent else colors.dim,
                )
            }
        }
    }
}

@Composable
private fun HairlineDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(boxpixColors.hairline),
    )
}
