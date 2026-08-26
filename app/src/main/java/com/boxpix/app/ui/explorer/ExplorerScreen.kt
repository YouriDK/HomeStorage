package com.boxpix.app.ui.explorer

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.boxpix.app.R
import com.boxpix.app.data.media.FileKind
import com.boxpix.app.data.net.ConnectionMode
import com.boxpix.app.data.prefs.SortOrder
import com.boxpix.app.data.storage.StorageEntry
import com.boxpix.app.ui.common.EmptyFolderView
import com.boxpix.app.ui.common.ErrorView
import com.boxpix.app.ui.common.FileKindPlaceholder
import com.boxpix.app.ui.common.GridSkeleton
import com.boxpix.app.ui.common.PhonePicks
import com.boxpix.app.ui.common.PlaceholderTones
import com.boxpix.app.ui.common.TagPickerSheet
import com.boxpix.app.ui.common.ThumbRequest
import com.boxpix.app.ui.common.TrashConfirmDialog
import com.boxpix.app.ui.common.WakingDiskView
import com.boxpix.app.ui.common.formatDuration
import com.boxpix.app.ui.common.message
import com.boxpix.app.data.vault.VaultState
import com.boxpix.app.ui.explorer.ExplorerViewModel.AlbumUi
import com.boxpix.app.ui.icons.Lucide
import com.boxpix.app.ui.theme.Hues
import com.boxpix.app.ui.theme.boxpixColors

@Composable
fun ExplorerScreen(
    onOpenSettings: () -> Unit,
    onOpenViewer: () -> Unit,
    onOpenSearch: () -> Unit,
    viewModel: ExplorerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val vaultLabel = stringResource(R.string.vault_title)

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

    LaunchedEffect(state.pendingVaultEntry) {
        if (state.pendingVaultEntry) viewModel.consumeVaultEntry(vaultLabel)
    }

    // Phone -> disk uploads: system pickers, resolved lazily on IO.
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val uploadScope = rememberCoroutineScope()
    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.uploadToCurrentFolder(PhonePicks.mediaSources(appContext, uris))
    }
    val pickTree = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { tree ->
        if (tree != null) {
            uploadScope.launch {
                val sources = withContext(Dispatchers.IO) { PhonePicks.treeSources(appContext, tree) }
                viewModel.uploadToCurrentFolder(sources)
            }
        }
    }
    val uploadProgress by viewModel.uploadProgress.collectAsStateWithLifecycle()
    val uploadOutcome by viewModel.uploadOutcome.collectAsStateWithLifecycle()

    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showTrashConfirm by remember { mutableStateOf(false) }
    var showTagPicker by remember { mutableStateOf(false) }
    var showMetadataSheet by remember { mutableStateOf(false) }

    val allTags by viewModel.allTags.collectAsStateWithLifecycle()
    val exportConflict by viewModel.exportConflict.collectAsStateWithLifecycle()

    val singleFolder = state.selection.singleOrNull()
        ?.let { selected -> state.folders.firstOrNull { it.pathB64 == selected } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (state.selectionMode) {
                SelectionBar(
                    count = state.selection.size,
                    canRename = state.selection.size == 1,
                    canProtect = singleFolder != null,
                    isProtected = singleFolder?.displayPath in state.protectedPaths,
                    isExcluded = singleFolder?.displayPath in state.excludedPaths,
                    writeEnabled = !state.offline,
                    inVault = state.inVault,
                    onClose = viewModel::clearSelection,
                    onRename = { showRenameDialog = true },
                    onTag = { showTagPicker = true },
                    onEditMetadata = { showMetadataSheet = true },
                    onDownload = viewModel::downloadSelected,
                    onMove = viewModel::openMoveSheet,
                    onTrash = { showTrashConfirm = true },
                    onSelectAll = viewModel::selectAll,
                    onToggleProtect = viewModel::toggleProtection,
                    onToggleExclude = viewModel::toggleExclusion,
                )
            } else {
                ExplorerTopBar(
                    state = state,
                    onBack = { viewModel.onBack() },
                    onOpenSettings = onOpenSettings,
                    onOpenSearch = {
                        viewModel.stageSearch()
                        onOpenSearch()
                    },
                )
                SortRow(
                    sort = state.sort,
                    onSortSelected = viewModel::setSort,
                    writeEnabled = !state.offline && uploadProgress == null,
                    onNewFolder = { showNewFolderDialog = true },
                    onUploadPhotos = {
                        pickMedia.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageAndVideo,
                            ),
                        )
                    },
                    onUploadFolder = { pickTree.launch(null) },
                )
            }

            if (state.offline) {
                Text(
                    text = stringResource(R.string.offline_banner),
                    style = MaterialTheme.typography.bodySmall,
                    color = boxpixColors.dim,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp)
                        .background(boxpixColors.elevated, RoundedCornerShape(10.dp))
                        .padding(12.dp),
                )
            }

            state.error?.let { error ->
                ErrorBanner(message = error.message(), onDismiss = viewModel::dismissError)
            }

            when {
                state.loading && state.wakingDisk -> WakingDiskView()

                state.loading && state.initialLoad -> GridSkeleton(columns = state.photoColumns)

                state.error != null && state.folders.isEmpty() && state.media.isEmpty() ->
                    ErrorView(message = state.error?.message().orEmpty(), onRetry = viewModel::reload)

                state.folders.isEmpty() && state.media.isEmpty() -> EmptyFolderView()

                else -> ContentGrid(state = state, viewModel = viewModel, onOpenViewer = onOpenViewer)
            }
        }

        // Upload status: a quiet pill at the bottom — progress while sending,
        // then the summary, which dismisses itself after a few seconds.
        androidx.compose.animation.AnimatedVisibility(
            visible = uploadProgress != null || uploadOutcome != null,
            enter = androidx.compose.animation.fadeIn() +
                androidx.compose.animation.slideInVertically { it / 2 },
            exit = androidx.compose.animation.fadeOut() +
                androidx.compose.animation.slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val progress = uploadProgress
            val outcome = uploadOutcome
            LaunchedEffect(outcome) {
                if (outcome != null && progress == null) {
                    kotlinx.coroutines.delay(5_000)
                    viewModel.consumeUploadOutcome()
                }
            }
            Row(
                modifier = Modifier
                    .padding(bottom = 24.dp)
                    .background(boxpixColors.elevated, RoundedCornerShape(18.dp))
                    .border(1.dp, boxpixColors.hairlineStrong, RoundedCornerShape(18.dp))
                    .clickable { if (progress == null) viewModel.consumeUploadOutcome() }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (progress != null) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = boxpixColors.accent,
                        trackColor = boxpixColors.hairline,
                        strokeWidth = 1.5.dp,
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(
                        text = stringResource(
                            R.string.upload_progress,
                            progress.fileName.orEmpty(),
                            progress.index,
                            progress.total,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = boxpixColors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else if (outcome != null) {
                    Text(
                        text = buildString {
                            append(pluralStringResource(R.plurals.upload_done, outcome.uploaded, outcome.uploaded))
                            if (outcome.failed > 0) {
                                append(" · ").append(stringResource(R.string.upload_failed, outcome.failed))
                            }
                            if (outcome.skippedTooLarge > 0) {
                                append(" · ").append(stringResource(R.string.upload_skipped_large, outcome.skippedTooLarge))
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = boxpixColors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }


    if (showMetadataSheet) {
        val vaultTagsForSheet by viewModel.vaultTags.collectAsStateWithLifecycle()
        com.boxpix.app.ui.common.MetadataSheet(
            selectionCount = state.selection.size,
            tags = if (state.inVault) vaultTagsForSheet else allTags.filterNot { it.isSystem },
            onApply = { tagIds, takenAt, location ->
                showMetadataSheet = false
                viewModel.applyMetadataToSelection(tagIds, takenAt, location)
            },
            onDismiss = { showMetadataSheet = false },
        )
    }

    if (showTagPicker) {
        val vaultTags by viewModel.vaultTags.collectAsStateWithLifecycle()
        TagPickerSheet(
            tags = if (state.inVault) vaultTags else allTags.filterNot { it.isSystem },
            selectedIds = emptySet(),
            onToggle = { tag -> viewModel.applyTagToSelection(tag) },
            onCreate = { name -> viewModel.createTagAndApply(name) },
            onDismiss = { showTagPicker = false },
        )
    }

    val downloadConfirm by viewModel.downloadConfirm.collectAsStateWithLifecycle()
    downloadConfirm?.let { pending ->
        com.boxpix.app.ui.common.DownloadConfirmDialog(
            totalBytes = pending.totalBytes,
            onConfirm = viewModel::confirmDownload,
            onDismiss = viewModel::dismissDownloadConfirm,
        )
    }

    exportConflict?.let { conflict ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = viewModel::dismissConflict,
            containerColor = boxpixColors.elevated,
            shape = RoundedCornerShape(14.dp),
            title = { Text(stringResource(R.string.conflict_title), color = boxpixColors.text) },
            text = {
                Text(
                    stringResource(R.string.conflict_message, conflict.remoteDevice),
                    color = boxpixColors.dim,
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmConflictedExport) {
                    Text(stringResource(R.string.conflict_overwrite), color = boxpixColors.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissConflict) {
                    Text(stringResource(R.string.dialog_cancel), color = boxpixColors.dim)
                }
            },
        )
    }

    if (showTrashConfirm) {
        TrashConfirmDialog(
            count = state.selection.size,
            onConfirm = {
                showTrashConfirm = false
                viewModel.trashSelected()
            },
            onDismiss = { showTrashConfirm = false },
        )
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
    onOpenSearch: () -> Unit,
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
                    Lucide.ArrowLeft,
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
        when {
            state.isFake -> Badge(stringResource(R.string.badge_fake), dot = true)
            state.offline -> Badge(stringResource(R.string.badge_offline), dot = false)
            state.connection == ConnectionMode.LAN -> Badge(stringResource(R.string.badge_lan), dot = true)
            state.connection == ConnectionMode.REMOTE -> Badge(stringResource(R.string.badge_remote), dot = false)
        }
        Spacer(Modifier.size(4.dp))
        IconButton(onClick = onOpenSearch) {
            Icon(
                Lucide.Search,
                contentDescription = stringResource(R.string.search_title),
                tint = colors.dim,
                modifier = Modifier.size(22.dp),
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(
                Lucide.Settings,
                contentDescription = stringResource(R.string.settings_title),
                tint = colors.dim,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun Badge(label: String, dot: Boolean) {
    val colors = boxpixColors
    Row(
        modifier = Modifier
            .height(26.dp)
            .border(1.dp, colors.hairlineStrong, RoundedCornerShape(100.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(colors.accent, CircleShape),
            )
            Spacer(Modifier.size(6.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.dim)
    }
}

@Composable
private fun SortRow(
    sort: SortOrder,
    onSortSelected: (SortOrder) -> Unit,
    writeEnabled: Boolean,
    onNewFolder: () -> Unit,
    onUploadPhotos: () -> Unit,
    onUploadFolder: () -> Unit,
) {
    val colors = boxpixColors
    var menuOpen by remember { mutableStateOf(false) }
    var addMenuOpen by remember { mutableStateOf(false) }
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
        Box {
            TextButton(onClick = { addMenuOpen = true }, enabled = writeEnabled) {
                val tint = if (writeEnabled) colors.accent else colors.faint
                Icon(
                    Lucide.Upload,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = stringResource(R.string.explorer_add),
                    style = MaterialTheme.typography.bodySmall,
                    color = tint,
                )
            }
            DropdownMenu(expanded = addMenuOpen, onDismissRequest = { addMenuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.explorer_upload_photos), color = colors.text, style = MaterialTheme.typography.bodyMedium) },
                    onClick = {
                        addMenuOpen = false
                        onUploadPhotos()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.explorer_upload_folder), color = colors.text, style = MaterialTheme.typography.bodyMedium) },
                    onClick = {
                        addMenuOpen = false
                        onUploadFolder()
                    },
                )
            }
        }
        TextButton(onClick = onNewFolder, enabled = writeEnabled) {
            val tint = if (writeEnabled) colors.accent else colors.faint
            Icon(
                Lucide.Plus,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = stringResource(R.string.explorer_new_folder),
                style = MaterialTheme.typography.bodySmall,
                color = tint,
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
    canProtect: Boolean,
    isProtected: Boolean,
    isExcluded: Boolean,
    writeEnabled: Boolean,
    inVault: Boolean,
    onClose: () -> Unit,
    onRename: () -> Unit,
    onTag: () -> Unit,
    onEditMetadata: () -> Unit,
    onDownload: () -> Unit,
    onMove: () -> Unit,
    onTrash: () -> Unit,
    onSelectAll: () -> Unit,
    onToggleProtect: () -> Unit,
    onToggleExclude: () -> Unit,
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
            Icon(Lucide.X, contentDescription = null, tint = colors.text, modifier = Modifier.size(22.dp))
        }
        Text(
            text = pluralStringResource(R.plurals.explorer_selected, count, count),
            style = MaterialTheme.typography.titleMedium,
            color = colors.text,
        )
        Spacer(Modifier.weight(1f))
        // Inside the vault: rename/move/trash/tag/metadata are vault-native
        // (in-vault trash and meta); protection, scan exclusion and device
        // download stay outside — they are Room/mirror/device concepts.
        if (canProtect && !inVault) {
            IconButton(onClick = onToggleProtect, enabled = writeEnabled) {
                Icon(
                    if (isProtected) Lucide.LockOpen else Lucide.Lock,
                    contentDescription = stringResource(
                        if (isProtected) R.string.explorer_action_unprotect else R.string.explorer_action_protect,
                    ),
                    tint = if (isProtected) colors.accent else colors.dim,
                    modifier = Modifier.size(22.dp),
                )
            }
            IconButton(onClick = onToggleExclude) {
                Icon(
                    Lucide.EyeOff,
                    contentDescription = stringResource(
                        if (isExcluded) R.string.explorer_action_include else R.string.explorer_action_exclude,
                    ),
                    tint = if (isExcluded) colors.accent else colors.dim,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        if (canRename) {
            IconButton(onClick = onRename, enabled = writeEnabled) {
                Icon(
                    Lucide.Pencil,
                    contentDescription = stringResource(R.string.explorer_action_rename),
                    tint = colors.dim,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        IconButton(onClick = onTag) {
            Icon(
                Lucide.Tag,
                contentDescription = stringResource(R.string.explorer_action_tag),
                tint = colors.dim,
                modifier = Modifier.size(22.dp),
            )
        }
        // Room-first outside the vault; the in-vault index inside.
        IconButton(onClick = onEditMetadata) {
            Icon(
                Lucide.SlidersHorizontal,
                contentDescription = stringResource(R.string.explorer_action_metadata),
                tint = colors.dim,
                modifier = Modifier.size(22.dp),
            )
        }
        if (!inVault) {
            IconButton(onClick = onDownload) {
                Icon(
                    Lucide.Download,
                    contentDescription = stringResource(R.string.viewer_menu_save),
                    tint = colors.dim,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        IconButton(onClick = onMove, enabled = writeEnabled) {
            Icon(
                Lucide.FolderInput,
                contentDescription = stringResource(R.string.explorer_action_move),
                tint = colors.dim,
                modifier = Modifier.size(22.dp),
            )
        }
        IconButton(onClick = onTrash, enabled = writeEnabled) {
            Icon(
                Lucide.Trash2,
                contentDescription = stringResource(R.string.explorer_action_trash),
                tint = colors.dim,
                modifier = Modifier.size(22.dp),
            )
        }
        IconButton(onClick = onSelectAll) {
            Icon(
                Lucide.CheckCheck,
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
            Icon(Lucide.X, contentDescription = null, tint = colors.dim, modifier = Modifier.size(16.dp))
        }
    }
}

/** One grid for everything: folder tiles first (like files, per feedback), then medias. */
@Composable
private fun ContentGrid(
    state: ExplorerViewModel.UiState,
    viewModel: ExplorerViewModel,
    onOpenViewer: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(state.photoColumns),
        modifier = Modifier
            .fillMaxSize()
            .pinchToAdjustColumns(viewModel::adjustColumns),
        contentPadding = PaddingValues(bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(state.albums.size, key = { "folder:" + state.albums[it].entry.pathB64 }) { index ->
            FolderTile(
                album = state.albums[index],
                selected = state.albums[index].entry.pathB64 in state.selection,
                isProtected = state.albums[index].entry.displayPath in state.protectedPaths,
                viewModel = viewModel,
            )
        }
        items(state.media.size, key = { state.media[it].pathB64 }) { index ->
            MediaCell(
                entry = state.media[index],
                selected = state.media[index].pathB64 in state.selection,
                selectionActive = state.selectionMode,
                isFavorite = state.media[index].pathB64 in state.favoritePaths,
                viewModel = viewModel,
                onOpenViewer = onOpenViewer,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderTile(
    album: AlbumUi,
    selected: Boolean,
    isProtected: Boolean,
    viewModel: ExplorerViewModel,
) {
    val colors = boxpixColors
    val darkTheme = isSystemInDarkTheme()
    val tone = PlaceholderTones.tone(album.cover?.pathB64 ?: album.entry.pathB64, darkTheme)
    val cover = album.cover?.takeIf { it.mimeType?.startsWith("image/") == true }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(if (cover != null) tone else colors.elevated)
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
        if (cover != null) {
            AsyncImage(
                model = ThumbRequest(cover.pathB64, cover.displayPath, cover.modifiedEpochSeconds),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                placeholder = ColorPainter(tone),
                error = ColorPainter(tone),
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Hues.Folder.copy(alpha = 0.08f)),
            )
            Icon(
                Lucide.Folder,
                contentDescription = null,
                tint = Hues.Folder,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(30.dp),
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
                .padding(start = 8.dp, end = 8.dp, top = 18.dp, bottom = 7.dp),
        ) {
            Text(
                text = album.entry.name,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = pluralStringResource(R.plurals.explorer_items_count, album.mediaCount, album.mediaCount),
                fontSize = 9.sp,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
        if (isProtected) {
            Icon(
                Lucide.Lock,
                contentDescription = stringResource(R.string.explorer_protected),
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(13.dp),
            )
        }
        if (selected) SelectionCheck(Modifier.align(Alignment.TopEnd))
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaCell(
    entry: StorageEntry,
    selected: Boolean,
    selectionActive: Boolean,
    isFavorite: Boolean,
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
        if (entry.kind != FileKind.PHOTO && entry.kind != FileKind.VIDEO) {
            // Non-media never get a thumbnail (extension gate): flat stand-in.
            FileKindPlaceholder(
                kind = entry.kind,
                fileName = entry.name,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            AsyncImage(
                model = ThumbRequest(
                    entry.pathB64,
                    entry.displayPath,
                    entry.modifiedEpochSeconds,
                    isVideo = isVideo,
                ),
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
                    Lucide.PlayFilled,
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
        if (isFavorite) {
            Icon(
                Lucide.HeartFilled,
                contentDescription = null,
                tint = Hues.Favorite,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(13.dp),
            )
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
            Lucide.Check,
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
