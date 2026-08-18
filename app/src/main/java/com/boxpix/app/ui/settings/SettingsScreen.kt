package com.boxpix.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boxpix.app.R
import com.boxpix.app.ui.theme.boxpixColors

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenTrash: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = boxpixColors

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
            GroupLabel(stringResource(R.string.settings_group_appearance))
            SettingRow(
                name = stringResource(R.string.settings_grid_columns),
                sub = stringResource(R.string.settings_grid_columns_hint),
            ) {
                SegmentedControl(
                    options = listOf(2, 3, 4),
                    selected = state.gridColumns,
                    onSelect = viewModel::setGridColumns,
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

            if (!state.useFake) {
                GroupLabel(stringResource(R.string.settings_group_connection))
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
                    Switch(
                        checked = state.useFake,
                        onCheckedChange = viewModel::setUseFake,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = colors.accent,
                            checkedThumbColor = colors.bg,
                            uncheckedTrackColor = colors.hairline,
                            uncheckedThumbColor = colors.surface,
                            uncheckedBorderColor = colors.hairlineStrong,
                        ),
                    )
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
        }
    }
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
private fun SegmentedControl(
    options: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val colors = boxpixColors
    Row(
        modifier = Modifier
            .border(1.dp, colors.hairlineStrong, RoundedCornerShape(8.dp))
            .padding(2.dp),
    ) {
        options.forEach { option ->
            val active = option == selected
            Box(
                modifier = Modifier
                    .background(
                        if (active) colors.accentSoft else colors.bg.copy(alpha = 0f),
                        RoundedCornerShape(6.dp),
                    )
                    .clickable { onSelect(option) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$option",
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
