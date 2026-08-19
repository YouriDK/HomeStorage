package com.boxpix.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.boxpix.app.R
import com.boxpix.app.ui.common.PlaceholderTones
import com.boxpix.app.ui.common.ThumbRequest
import com.boxpix.app.ui.common.formatDate
import com.boxpix.app.ui.theme.boxpixColors
import com.boxpix.app.ui.viewer.MediaRef

/** Screen 07 — name, combinable tags, date range, folder. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenViewer: () -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val allTags by viewModel.allTags.collectAsStateWithLifecycle()
    val colors = boxpixColors
    var showDatePicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
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
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                placeholder = { Text(stringResource(R.string.search_hint)) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                singleLine = true,
                shape = RoundedCornerShape(100.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.hairlineStrong,
                    cursorColor = colors.accent,
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
            )
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(allTags.size, key = { allTags[it].id }) { index ->
                val tag = allTags[index]
                FilterChip(
                    label = tag.name,
                    selected = tag.id in state.selectedTagIds,
                    onClick = { viewModel.toggleTag(tag.id) },
                )
            }
        }

        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                label = if (state.fromEpochSeconds != null) {
                    "${formatDate(state.fromEpochSeconds!!)} – ${formatDate(state.toEpochSeconds ?: state.fromEpochSeconds!!)}"
                } else {
                    stringResource(R.string.search_date_any)
                },
                selected = state.fromEpochSeconds != null,
                onClick = { showDatePicker = true },
            )
            state.folder?.let { folder ->
                FilterChip(
                    label = stringResource(R.string.search_in_folder, folder.name),
                    selected = true,
                    onClick = viewModel::clearFolder,
                    trailingClose = true,
                )
            }
        }

        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = pluralStringResource(R.plurals.search_results, state.results.size, state.results.size),
                style = MaterialTheme.typography.bodySmall,
                color = colors.dim,
                modifier = Modifier.weight(1f),
            )
            if (state.hasFilters) {
                TextButton(onClick = viewModel::clearFilters) {
                    Text(stringResource(R.string.search_clear), color = colors.accent)
                }
            }
        }

        if (state.searched && state.results.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.search_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.text,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.search_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.dim,
                    modifier = Modifier.padding(horizontal = 40.dp),
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(state.results.size, key = { state.results[it].pathB64 }) { index ->
                    ResultCell(
                        item = state.results[index],
                        onClick = {
                            viewModel.stageViewer(state.results[index])
                            onOpenViewer()
                        },
                    )
                }
            }
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDatePicker = false
                        viewModel.setDateRange(
                            pickerState.selectedStartDateMillis?.div(1000),
                            pickerState.selectedEndDateMillis?.div(1000)?.plus(86_399),
                        )
                    },
                ) { Text(stringResource(R.string.dialog_create), color = colors.accent) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDatePicker = false
                        viewModel.setDateRange(null, null)
                    },
                ) { Text(stringResource(R.string.search_date_any), color = colors.dim) }
            },
            colors = DatePickerDefaults.colors(containerColor = colors.elevated),
        ) {
            DateRangePicker(state = pickerState, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    trailingClose: Boolean = false,
) {
    val colors = boxpixColors
    Row(
        modifier = Modifier
            .height(32.dp)
            .background(
                if (selected) colors.accentSoft else colors.bg.copy(alpha = 0f),
                RoundedCornerShape(100.dp),
            )
            .border(
                1.dp,
                if (selected) colors.accent else colors.hairlineStrong,
                RoundedCornerShape(100.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) colors.accent else colors.dim,
        )
        if (trailingClose) {
            Spacer(Modifier.size(6.dp))
            Icon(
                Icons.Outlined.Close,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

@Composable
private fun ResultCell(item: MediaRef, onClick: () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val tone = PlaceholderTones.tone(item.pathB64, darkTheme)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(tone)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = ThumbRequest(item.pathB64, item.displayPath, item.mtime, isVideo = item.isVideo),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            placeholder = ColorPainter(tone),
            error = ColorPainter(tone),
            modifier = Modifier.matchParentSize(),
        )
        if (item.isVideo) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 6.dp, bottom = 5.dp)
                    .size(14.dp),
            )
        }
    }
}
