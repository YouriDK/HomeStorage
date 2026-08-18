package com.boxpix.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boxpix.app.R
import com.boxpix.app.data.storage.StorageEntry
import com.boxpix.app.ui.common.message
import com.boxpix.app.ui.onboarding.OnboardingViewModel.Step
import com.boxpix.app.ui.theme.boxpixColors

@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val step = state.step) {
        Step.Start -> StartContent(
            state = state,
            onConnect = viewModel::connect,
            onToggleAdvanced = viewModel::toggleAdvanced,
            onHostChange = viewModel::setHost,
        )

        Step.Pairing -> PairingContent(onCancel = viewModel::cancelPairing)

        is Step.ChooseDisk -> DiskContent(
            step = step,
            onSelectDisk = viewModel::selectDisk,
            onOpenFolder = viewModel::openFolder,
            onUpOneLevel = viewModel::upOneLevel,
            onConfirm = viewModel::confirmRoot,
        )
    }
}

@Composable
private fun StartContent(
    state: OnboardingViewModel.UiState,
    onConnect: () -> Unit,
    onToggleAdvanced: () -> Unit,
    onHostChange: (String) -> Unit,
) {
    val colors = boxpixColors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))

        AppIconPlaceholder()
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.onboarding_title),
            style = MaterialTheme.typography.headlineMedium,
            color = colors.text,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.onboarding_explainer),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.dim,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )

        Spacer(Modifier.weight(1f))

        state.error?.let { error ->
            Text(
                text = error.message(),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.text,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, colors.hairlineStrong, RoundedCornerShape(10.dp))
                    .background(colors.elevated, RoundedCornerShape(10.dp))
                    .padding(14.dp),
            )
            Spacer(Modifier.height(12.dp))
        }

        AccentOutlinedButton(
            label = stringResource(R.string.onboarding_connect),
            onClick = onConnect,
            enabled = !state.busy,
            leadingIcon = {
                Icon(Icons.Outlined.Wifi, contentDescription = null, modifier = Modifier.size(20.dp))
            },
        )

        TextButton(onClick = onToggleAdvanced) {
            Text(
                text = stringResource(R.string.onboarding_advanced),
                style = MaterialTheme.typography.bodySmall,
                color = colors.dim,
            )
        }

        AnimatedVisibility(visible = state.advancedOpen) {
            AdvancedCard(state = state, onHostChange = onHostChange)
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AdvancedCard(
    state: OnboardingViewModel.UiState,
    onHostChange: (String) -> Unit,
) {
    val colors = boxpixColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.hairline, RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = state.host,
            onValueChange = onHostChange,
            label = { Text(stringResource(R.string.onboarding_advanced_host_label)) },
            placeholder = {
                Text("mafreebox.freebox.fr", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            },
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                color = colors.text,
            ),
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.hairlineStrong,
                focusedLabelColor = colors.accent,
                unfocusedLabelColor = colors.dim,
                cursorColor = colors.accent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.onboarding_advanced_token_label),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.text,
                modifier = Modifier.weight(1f),
            )
            if (state.hasStoredToken) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(6.dp))
            }
            Text(
                text = stringResource(
                    if (state.hasStoredToken) R.string.onboarding_advanced_token_stored
                    else R.string.onboarding_advanced_token_missing,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (state.hasStoredToken) colors.accent else colors.dim,
            )
        }
    }
}

@Composable
private fun PairingContent(onCancel: () -> Unit) {
    val colors = boxpixColors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))

        Box(modifier = Modifier.size(150.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                modifier = Modifier.size(150.dp),
                color = colors.accent,
                trackColor = colors.hairline,
                strokeWidth = 1.5.dp,
            )
            Box(
                modifier = Modifier
                    .size(78.dp)
                    .background(colors.surface, RoundedCornerShape(14.dp))
                    .border(1.dp, colors.hairline, RoundedCornerShape(14.dp)),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 12.dp, end = 12.dp)
                        .size(6.dp)
                        .background(colors.accent, RoundedCornerShape(3.dp)),
                )
            }
        }

        Spacer(Modifier.height(28.dp))
        Text(
            text = stringResource(R.string.pairing_title),
            style = MaterialTheme.typography.titleLarge,
            color = boxpixColors.text,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.pairing_explainer),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.dim,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )

        Spacer(Modifier.weight(1f))

        TextButton(onClick = onCancel) {
            Text(
                text = stringResource(R.string.pairing_cancel),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.dim,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DiskContent(
    step: Step.ChooseDisk,
    onSelectDisk: (StorageEntry) -> Unit,
    onOpenFolder: (StorageEntry) -> Unit,
    onUpOneLevel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = boxpixColors

    // System back walks the browse stack up instead of leaving the app.
    BackHandler(enabled = step.folderStack.isNotEmpty()) {
        onUpOneLevel()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.disk_title),
            style = MaterialTheme.typography.headlineSmall,
            color = colors.text,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.disk_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.dim,
        )
        Spacer(Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(step.disks.size, key = { step.disks[it].pathB64 }) { index ->
                val disk = step.disks[index]
                DiskRow(
                    entry = disk,
                    selected = disk.pathB64 == step.selectedDisk?.pathB64,
                    onClick = { onSelectDisk(disk) },
                )
                if (index < step.disks.lastIndex) HairlineDivider()
            }

            if (step.selectedDisk != null) {
                item(key = "root-header") {
                    RootFolderHeader(
                        step = step,
                        onUpOneLevel = onUpOneLevel,
                    )
                }
                if (step.listing) {
                    item(key = "listing") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = colors.accent,
                                trackColor = colors.hairline,
                                strokeWidth = 1.5.dp,
                            )
                        }
                    }
                } else {
                    items(step.subFolders.size, key = { step.subFolders[it].pathB64 }) { index ->
                        val folder = step.subFolders[index]
                        FolderRow(entry = folder, onClick = { onOpenFolder(folder) })
                        if (index < step.subFolders.lastIndex) HairlineDivider()
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        AccentOutlinedButton(
            label = stringResource(R.string.disk_start_scanning),
            onClick = onConfirm,
            enabled = step.rootCandidate != null && !step.listing,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DiskRow(entry: StorageEntry, selected: Boolean, onClick: () -> Unit) {
    val colors = boxpixColors
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .then(if (selected) Modifier.background(colors.accentSoft, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Storage,
            contentDescription = null,
            tint = if (selected) colors.accent else colors.dim,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.size(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.titleMedium, color = colors.text)
            Text(
                entry.displayPath,
                style = MaterialTheme.typography.labelMedium,
                color = colors.dim,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
        } else {
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = colors.faint, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun RootFolderHeader(step: Step.ChooseDisk, onUpOneLevel: () -> Unit) {
    val colors = boxpixColors
    Column {
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.disk_root_folder).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = colors.faint,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(colors.elevated, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = step.rootCandidate?.displayPath.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = colors.text,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
        if (step.folderStack.isNotEmpty()) {
            TextButton(onClick = onUpOneLevel) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = stringResource(R.string.disk_up_one_level),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.accent,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun FolderRow(entry: StorageEntry, onClick: () -> Unit) {
    val colors = boxpixColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Folder,
            contentDescription = null,
            tint = colors.dim,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(14.dp))
        Text(
            entry.name,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.text,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = colors.faint, modifier = Modifier.size(18.dp))
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

@Composable
private fun AccentOutlinedButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val colors = boxpixColors
    val shape = RoundedCornerShape(10.dp)
    val contentColor = if (enabled) colors.accent else colors.faint
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .border(1.dp, contentColor, shape)
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides contentColor,
            ) {
                leadingIcon()
            }
            Spacer(Modifier.size(10.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor,
        )
    }
}

/** 76 dp dashed slot: the app icon is client-designed and out of scope. */
@Composable
private fun AppIconPlaceholder() {
    val colors = boxpixColors
    Box(
        modifier = Modifier
            .size(76.dp)
            .drawBehind {
                drawRoundRect(
                    color = colors.hairlineStrong,
                    cornerRadius = CornerRadius(16.dp.toPx()),
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
                    ),
                )
            },
    )
}
