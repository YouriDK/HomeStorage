package com.boxpix.app.ui.explorer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.boxpix.app.R
import com.boxpix.app.data.prefs.SortOrder
import com.boxpix.app.data.storage.StorageEntry
import com.boxpix.app.ui.common.EmptyFolderView
import com.boxpix.app.ui.common.ErrorView
import com.boxpix.app.ui.common.FloatingTabBar
import com.boxpix.app.ui.common.MainTab
import com.boxpix.app.ui.common.GridSkeleton
import com.boxpix.app.ui.common.PlaceholderTones
import com.boxpix.app.ui.common.ThumbRequest
import com.boxpix.app.ui.common.WakingDiskView
import com.boxpix.app.ui.common.formatDuration
import com.boxpix.app.ui.common.message
import com.boxpix.app.ui.explorer.ExplorerViewModel.AlbumUi
import com.boxpix.app.ui.theme.boxpixColors

@Composable
fun ExplorerScreen(
    onOpenSettings: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenViewer: () -> Unit,
    viewModel: ExplorerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    BackHandler(enabled = state.selectionMode || state.depth > 0) {
        viewModel.onBack()
    }

    // The disk is the source of truth: silently re-list whenever the Explorer
    // comes back into view (return from Trash/Settings, app foregrounded) so
    // restores and out-of-app changes appear without a manual rescan. The first
    // resume is skipped — the ViewModel already loads on creation.
    var firstResume by rememberSaveable { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        if (firstResume) firstResume = false else viewModel.reload()
        onPauseOrDispose { }
    }

    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (state.selectionMode) {
                SelectionBar(
                    count = state.selection.size,
                    canRename = state.selection.size == 1,
                    onClose = viewModel::clearSelection,
                    onRename = { showRenameDialog = true },
                    onMove = viewModel::openMoveSheet,
                    onTrash = viewModel::trashSelected,
                    onSelectAll = viewModel::selectAll,
                )
            } else {
                ExplorerTopBar(
                    state = state,
                    onBack = { viewModel.onBack() },
                    onOpenSettings = onOpenSettings,
                )
                SortRow(
                    sort = state.sort,
                    onSortSelected = viewModel::setSort,
                    onNewFolder = { showNewFolderDialog = true },
                )
            }

            state.error?.let { error ->
                ErrorBanner(message = error.message(), onDismiss = viewModel::dismissError)
            }

            when {
                state.loading && state.wakingDisk -> WakingDiskView()

                state.loading && state.initialLoad ->
                    GridSkeleton(columns = if (state.depth == 0) state.albumColumns else state.photoColumns)

                state.error != null && state.folders.isEmpty() && state.media.isEmpty() ->
                    ErrorView(message = state.error?.message().orEmpty(), onRetry = viewModel::reload)

                state.depth == 0 && state.albums.isNotEmpty() ->
                    AlbumGrid(state = state, viewModel = viewModel)

                state.folders.isEmpty() && state.media.isEmpty() -> EmptyFolderView()

                else -> FolderContent(state = state, viewModel = viewModel, onOpenViewer = onOpenViewer)
            }
        }

        if (!state.selectionMode) {
            FloatingTabBar(
                active = MainTab.EXPLORER,
                onSelect = { tab -> if (tab == MainTab.GALLERY) onOpenGallery() },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (showNewFolderDialog) {
        NameDialog(
            title = stringResource(R.string.dialog_new_folder_title),
            initialValue = "",
            confirmLabel = stringResource(R.string.dialog_create),
            onConfirm = { name ->
                showNewFolderDialog = false
                if (name.isNotBlank()) viewModel.createFolder(name)
            },
            onDismiss = { showNewFolderDialog = false },
        )
    }

    if (showRenameDialog) {
        val selectedName = remember(state.selection) {
            (state.folders + state.media)
                .firstOrNull { it.pathB64 == state.selection.singleOrNull() }
                ?.name.orEmpty()
        }
        NameDialog(
            title = stringResource(R.string.dialog_rename_title),
            initialValue = selectedName,
            confirmLabel = stringResource(R.string.dialog_rename),
            onConfirm = { name ->
                showRenameDialog = false
                if (name.isNotBlank()) viewModel.renameSelected(name)
            },
            onDismiss = { showRenameDialog = false },
        )
    }

    if (state.move.visible) {
        MoveSheet(
            move = state.move,
            selectionCount = state.selection.size,
            onBrowseInto = viewModel::moveBrowseInto,
            onBrowseUp = viewModel::moveBrowseUp,
            onConfirm = viewModel::confirmMove,
            onDismiss = viewModel::closeMoveSheet,
        )
    }
}

@Composable
private fun ExplorerTopBar(
    state: ExplorerViewModel.UiState,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = boxpixColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.depth > 0) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = null,
                    tint = colors.text,
                    modifier = Modifier.size(22.dp),
                )
            }
        } else {
            Spacer(Modifier.size(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.current?.name.orEmpty(),
                style = MaterialTheme.typography.titleLarge,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.depth > 0) {
                Text(
                    text = state.breadcrumb,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (state.isFake) {
            Badge(stringResource(R.string.badge_fake))
            Spacer(Modifier.size(4.dp))
        }
        IconButton(onClick = onOpenSettings) {
            Icon(
                Icons.Outlined.Settings,
                contentDescription = stringResource(R.string.settings_title),
                tint = colors.dim,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun Badge(label: String) {
    val colors = boxpixColors
    Row(
        modifier = Modifier
            .height(26.dp)
            .border(1.dp, colors.hairlineStrong, RoundedCornerShape(100.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(colors.accent, CircleShape),
        )
        Spacer(Modifier.size(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.dim)
    }
}

@Composable
private fun SortRow(
    sort: SortOrder,
    onSortSelected: (SortOrder) -> Unit,
    onNewFolder: () -> Unit,
) {
    val colors = boxpixColors
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            TextButton(onClick = { menuOpen = true }) {
                Text(
                    text = "${stringResource(R.string.explorer_sort)} · ${sortLabel(sort)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.dim,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                SortOrder.entries.forEach { order ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                sortLabel(order),
                                color = if (order == sort) colors.accent else colors.text,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onSortSelected(order)
                        },
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onNewFolder) {
            Icon(
                Icons.Outlined.Add,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = stringResource(R.string.explorer_new_folder),
                style = MaterialTheme.typography.bodySmall,
                color = colors.accent,
            )
        }
    }
}

@Composable
private fun sortLabel(order: SortOrder): String = stringResource(
    when (order) {
        SortOrder.NAME -> R.string.explorer_sort_name
        SortOrder.DATE -> R.string.explorer_sort_date
        SortOrder.SIZE -> R.string.explorer_sort_size
    },
)

@Composable
private fun SelectionBar(
    count: Int,
    canRename: Boolean,
    onClose: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onTrash: () -> Unit,
    onSelectAll: () -> Unit,
) {
    val colors = boxpixColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(colors.elevated)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Outlined.Close, contentDescription = null, tint = colors.text, modifier = Modifier.size(22.dp))
        }
        Text(
            text = pluralStringResource(R.plurals.explorer_selected, count, count),
            style = MaterialTheme.typography.titleMedium,
            color = colors.text,
        )
        Spacer(Modifier.weight(1f))
        if (canRename) {
            IconButton(onClick = onRename) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.explorer_action_rename),
                    tint = colors.dim,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        IconButton(onClick = onMove) {
            Icon(
                Icons.AutoMirrored.Outlined.DriveFileMove,
                contentDescription = stringResource(R.string.explorer_action_move),
                tint = colors.dim,
                modifier = Modifier.size(22.dp),
            )
        }
        IconButton(onClick = onTrash) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.explorer_action_trash),
                tint = colors.dim,
                modifier = Modifier.size(22.dp),
            )
        }
        IconButton(onClick = onSelectAll) {
            Icon(
                Icons.Outlined.SelectAll,
                contentDescription = stringResource(R.string.explorer_action_select_all),
                tint = colors.dim,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    val colors = boxpixColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .background(colors.elevated, RoundedCornerShape(10.dp))
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = colors.text,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(Icons.Outlined.Close, contentDescription = null, tint = colors.dim, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun AlbumGrid(
    state: ExplorerViewModel.UiState,
    viewModel: ExplorerViewModel,
) {
    val gap = if (state.albumColumns >= 3) 8.dp else 14.dp
    LazyVerticalGrid(
        columns = GridCells.Fixed(state.albumColumns),
        modifier = Modifier
            .fillMaxSize()
            .pinchToAdjustColumns(viewModel::adjustColumns),
        contentPadding = PaddingValues(start = gap, end = gap, top = 2.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        items(state.albums.size, key = { state.albums[it].entry.pathB64 }) { index ->
            AlbumCell(
                album = state.albums[index],
                selected = state.albums[index].entry.pathB64 in state.selection,
                viewModel = viewModel,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumCell(
    album: AlbumUi,
    selected: Boolean,
    viewModel: ExplorerViewModel,
) {
    val colors = boxpixColors
    val darkTheme = isSystemInDarkTheme()
    val tone = PlaceholderTones.tone(album.cover?.pathB64 ?: album.entry.pathB64, darkTheme)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(tone)
            .then(
                if (selected) {
                    Modifier
                        .border(2.dp, colors.accent)
                        .background(colors.accentSoft)
                } else {
                    Modifier
                },
            )
            .combinedClickable(
                onClick = { viewModel.openFolder(album.entry) },
                onLongClick = { viewModel.startSelection(album.entry.pathB64) },
            ),
    ) {
        album.cover
            ?.takeIf { it.mimeType?.startsWith("image/") == true }
            ?.let { cover ->
                AsyncImage(
                    model = ThumbRequest(cover.pathB64, cover.displayPath, cover.modifiedEpochSeconds),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    placeholder = ColorPainter(tone),
                    error = ColorPainter(tone),
                    modifier = Modifier.matchParentSize(),
                )
            }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)),
                    ),
                )
                .padding(start = 10.dp, end = 10.dp, top = 22.dp, bottom = 9.dp),
        ) {
            Text(
                text = album.entry.name,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = pluralStringResource(R.plurals.explorer_items_count, album.mediaCount, album.mediaCount),
                fontSize = 9.5.sp,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
        if (selected) SelectionCheck(Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun FolderContent(
    state: ExplorerViewModel.UiState,
    viewModel: ExplorerViewModel,
    onOpenViewer: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (state.albums.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.padding(vertical = 6.dp),
                contentPadding = PaddingValues(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.albums.size, key = { state.albums[it].entry.pathB64 }) { index ->
                    SubfolderChip(album = state.albums[index], viewModel = viewModel)
                }
            }
        }
        if (state.media.isEmpty() && state.albums.isEmpty()) {
            EmptyFolderView()
        } else if (state.media.isEmpty()) {
            EmptyFolderView(Modifier.weight(1f))
        } else {
            MediaGrid(
                state = state,
                viewModel = viewModel,
                onOpenViewer = onOpenViewer,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SubfolderChip(album: AlbumUi, viewModel: ExplorerViewModel) {
    val colors = boxpixColors
    Row(
        modifier = Modifier
            .height(32.dp)
            .border(1.dp, colors.hairlineStrong, RoundedCornerShape(100.dp))
            .clickable { viewModel.openFolder(album.entry) }
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = album.entry.name,
            style = MaterialTheme.typography.bodySmall,
            color = colors.text,
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = "${album.mediaCount}",
            style = MaterialTheme.typography.labelMedium,
            color = colors.dim,
        )
    }
}

@Composable
private fun MediaGrid(
    state: ExplorerViewModel.UiState,
    viewModel: ExplorerViewModel,
    onOpenViewer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(state.photoColumns),
        modifier = modifier
            .fillMaxSize()
            .pinchToAdjustColumns(viewModel::adjustColumns),
        contentPadding = PaddingValues(bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(state.media.size, key = { state.media[it].pathB64 }) { index ->
            MediaCell(
                entry = state.media[index],
                selected = state.media[index].pathB64 in state.selection,
                selectionActive = state.selectionMode,
                viewModel = viewModel,
                onOpenViewer = onOpenViewer,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaCell(
    entry: StorageEntry,
    selected: Boolean,
    selectionActive: Boolean,
    viewModel: ExplorerViewModel,
    onOpenViewer: () -> Unit,
) {
    val colors = boxpixColors
    val darkTheme = isSystemInDarkTheme()
    val tone = PlaceholderTones.tone(entry.pathB64, darkTheme)
    val isVideo = entry.mimeType?.startsWith("video/") == true
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(tone)
            .then(
                if (selected) {
                    Modifier
                        .border(2.dp, colors.accent)
                        .background(colors.accentSoft)
                } else {
                    Modifier
                },
            )
            .combinedClickable(
                onClick = {
                    if (selectionActive) {
                        viewModel.toggleSelection(entry.pathB64)
                    } else {
                        viewModel.stageViewer(entry)
                        onOpenViewer()
                    }
                },
                onLongClick = { viewModel.startSelection(entry.pathB64) },
            ),
    ) {
        if (!isVideo) {
            AsyncImage(
                model = ThumbRequest(entry.pathB64, entry.displayPath, entry.modifiedEpochSeconds),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                placeholder = ColorPainter(tone),
                error = ColorPainter(tone),
                modifier = Modifier.matchParentSize(),
            )
        }
        if (isVideo) {
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
                entry.durationSeconds?.let { duration ->
                    Text(
                        text = formatDuration(duration),
                        fontSize = 10.sp,
                        color = Color.White,
                    )
                }
            }
        }
        if (selected) SelectionCheck(Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun SelectionCheck(modifier: Modifier = Modifier) {
    val colors = boxpixColors
    Box(
        modifier = modifier
            .padding(6.dp)
            .size(18.dp)
            .background(colors.accent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Check,
            contentDescription = null,
            tint = colors.bg,
            modifier = Modifier.size(12.dp),
        )
    }
}

/**
 * Two-finger pinch changes the column count (persisted preference) without
 * stealing single-finger scrolling from the grid.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.pinchToAdjustColumns(onAdjust: (Int) -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            var zoom = 1f
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent()
                if (event.changes.size > 1) {
                    zoom *= event.calculateZoom()
                    event.changes.forEach { it.consume() }
                    if (zoom > 1.25f) {
                        onAdjust(-1) // zoom in = bigger cells = fewer columns
                        zoom = 1f
                    } else if (zoom < 0.8f) {
                        onAdjust(+1)
                        zoom = 1f
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }
