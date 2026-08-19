package com.boxpix.app.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.boxpix.app.R
import com.boxpix.app.ui.common.FloatingTabBar
import com.boxpix.app.ui.common.MainTab
import com.boxpix.app.ui.common.PlaceholderTones
import com.boxpix.app.ui.common.ThumbRequest
import com.boxpix.app.ui.common.formatDuration
import com.boxpix.app.ui.theme.boxpixColors
import com.boxpix.app.ui.viewer.MediaRef

private const val TIMELINE_COLUMNS = 4

@Composable
fun TimelineScreen(
    onOpenExplorer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenViewer: () -> Unit,
    viewModel: TimelineViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = boxpixColors

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.tab_gallery),
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.text,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.settings_title),
                        tint = colors.dim,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            if (state.loaded && state.rows.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.timeline_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.dim,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(TIMELINE_COLUMNS),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(
                        count = state.rows.size,
                        key = { index ->
                            when (val row = state.rows[index]) {
                                is TimelineGrouper.Row.Header -> "header:${row.label}"
                                is TimelineGrouper.Row.Media -> row.item.pathB64
                            }
                        },
                        span = { index ->
                            when (state.rows[index]) {
                                is TimelineGrouper.Row.Header -> GridItemSpan(maxLineSpan)
                                is TimelineGrouper.Row.Media -> GridItemSpan(1)
                            }
                        },
                    ) { index ->
                        when (val row = state.rows[index]) {
                            is TimelineGrouper.Row.Header -> SectionHeader(row)
                            is TimelineGrouper.Row.Media -> TimelineCell(
                                item = row.item,
                                onClick = {
                                    viewModel.stageViewer(row.item)
                                    onOpenViewer()
                                },
                            )
                        }
                    }
                }
            }
        }

        FloatingTabBar(
            active = MainTab.GALLERY,
            onSelect = { tab -> if (tab == MainTab.EXPLORER) onOpenExplorer() },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun SectionHeader(header: TimelineGrouper.Row.Header) {
    val colors = boxpixColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.bg)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = header.label,
            style = MaterialTheme.typography.titleMedium,
            color = colors.text,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${header.count}",
            style = MaterialTheme.typography.labelMedium,
            color = colors.faint,
        )
    }
}

@Composable
private fun TimelineCell(item: MediaRef, onClick: () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val tone = PlaceholderTones.tone(item.pathB64, darkTheme)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(tone)
            .clickable(onClick = onClick),
    ) {
        if (!item.isVideo) {
            AsyncImage(
                model = ThumbRequest(item.pathB64, item.displayPath, item.mtime),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                placeholder = ColorPainter(tone),
                error = ColorPainter(tone),
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 6.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
                item.durationSeconds?.let {
                    Text(text = formatDuration(it), fontSize = 10.sp, color = Color.White)
                }
            }
        }
    }
}
