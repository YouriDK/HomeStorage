package com.boxpix.app.ui.viewer

import android.content.Intent
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import com.boxpix.app.R
import com.boxpix.app.data.media.FileKind
import com.boxpix.app.data.vault.VaultPaths
import com.boxpix.app.data.vault.VaultState
import com.boxpix.app.ui.common.FileKindPlaceholder
import com.boxpix.app.ui.common.HdRequest
import com.boxpix.app.ui.common.TagPickerSheet
import com.boxpix.app.ui.common.ThumbRequest
import com.boxpix.app.ui.common.TrashConfirmDialog
import com.boxpix.app.ui.common.formatBytes
import com.boxpix.app.ui.common.formatDate
import com.boxpix.app.ui.common.formatDuration
import com.boxpix.app.ui.common.message
import com.boxpix.app.ui.explorer.MoveSheet
import com.boxpix.app.ui.explorer.NameDialog
import com.boxpix.app.ui.icons.Lucide
import com.boxpix.app.ui.theme.Hues
import com.boxpix.app.ui.theme.boxpixColors
import kotlinx.coroutines.delay

/** Screen 05 — always a dark room, whatever the app theme. */
@Composable
fun ViewerScreen(
    onBack: () -> Unit,
    viewModel: ViewerViewModel = hiltViewModel(),
    vaultViewModel: com.boxpix.app.ui.vault.VaultViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val shareUri by viewModel.shareUri.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    if (state.items.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val pagerState = rememberPagerState(initialPage = state.startIndex) { state.items.size }
    val current = state.items[pagerState.currentPage.coerceIn(0, state.items.lastIndex)]

    var showRenameDialog by remember { mutableStateOf(false) }
    var showTrashConfirm by remember { mutableStateOf(false) }
    var showTagPicker by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }

    val allTags by viewModel.allTags.collectAsStateWithLifecycle()
    val favoritePaths by viewModel.favoritePaths.collectAsStateWithLifecycle()
    val currentTagIds by remember(current.pathB64) { viewModel.tagIdsFlow(current.pathB64) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val isFavorite = current.pathB64 in favoritePaths

    // M8 lot 2: vault media are view-only — every hidden action would write to
    // Room, to a clear mirror or to the device (share cache, MediaStore).
    val inVault = VaultPaths.isVaultPath(current.displayPath)

    // Locking the vault while viewing its content closes the viewer: nothing
    // decrypted stays on screen.
    val vaultState by vaultViewModel.vaultState.collectAsStateWithLifecycle()
    LaunchedEffect(inVault, vaultState) {
        if (inVault && vaultState != VaultState.Unlocked) onBack()
    }

    LaunchedEffect(shareUri) {
        shareUri?.let { uri ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = current.mimeType ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, null))
            viewModel.consumeShare()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val item = state.items[page]
            MediaPage(
                item = item,
                isCurrent = page == pagerState.currentPage,
                streamingUrl = viewModel.streamingUrl(item),
                headers = state.videoAccess?.headers.orEmpty(),
                chromeVisible = state.chromeVisible,
                onTap = viewModel::toggleChrome,
            )
        }

        AnimatedVisibility(
            visible = state.chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ViewerTopBar(
                item = current,
                inVault = inVault,
                onBack = onBack,
                onOverflow = { overflowOpen = true },
                overflowOpen = overflowOpen,
                onDismissOverflow = { overflowOpen = false },
                onRename = {
                    overflowOpen = false
                    showRenameDialog = true
                },
                onCopyPath = {
                    overflowOpen = false
                    clipboard.setText(AnnotatedString(current.displayPath))
                },
                onSave = {
                    overflowOpen = false
                    viewModel.saveToDevice(current)
                },
                onMove = {
                    overflowOpen = false
                    viewModel.openMoveSheet()
                },
                onTrash = {
                    overflowOpen = false
                    showTrashConfirm = true
                },
                onShare = {
                    overflowOpen = false
                    viewModel.share(current)
                },
                onInfo = {
                    overflowOpen = false
                    viewModel.setInfoOpen(true)
                },
            )
        }

        state.error?.let { error ->
            Text(
                text = error.message(),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 110.dp)
                    .background(Color(0xCC16161A), RoundedCornerShape(10.dp))
                    .clickable { viewModel.dismissError() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        // On video pages the stock player controller owns the bottom edge; the
        // file actions move into the top-right overflow menu instead.
        AnimatedVisibility(
            visible = state.chromeVisible && !current.isVideo,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                ),
        ) {
            ViewerActionBar(
                isFavorite = isFavorite,
                inVault = inVault,
                onTag = { showTagPicker = true },
                onFavorite = { viewModel.toggleFavorite(current) },
                onMove = viewModel::openMoveSheet,
                onTrash = { showTrashConfirm = true },
                onShare = { viewModel.share(current) },
                onInfo = { viewModel.setInfoOpen(true) },
            )
        }
    }

    val downloadConfirm by viewModel.downloadConfirm.collectAsStateWithLifecycle()
    downloadConfirm?.let { pending ->
        com.boxpix.app.ui.common.DownloadConfirmDialog(
            totalBytes = pending.totalBytes,
            onConfirm = viewModel::confirmDownload,
            onDismiss = viewModel::dismissDownloadConfirm,
        )
    }

    if (showTrashConfirm) {
        TrashConfirmDialog(
            count = 1,
            onConfirm = {
                showTrashConfirm = false
                viewModel.trash(current)
            },
            onDismiss = { showTrashConfirm = false },
        )
    }

    if (showRenameDialog) {
        NameDialog(
            title = stringResource(R.string.dialog_rename_title),
            initialValue = current.name,
            confirmLabel = stringResource(R.string.dialog_rename),
            onConfirm = { name ->
                showRenameDialog = false
                if (name.isNotBlank()) viewModel.rename(current, name)
            },
            onDismiss = { showRenameDialog = false },
        )
    }

    if (state.move.visible) {
        MoveSheet(
            move = state.move,
            selectionCount = 1,
            onBrowseInto = viewModel::moveBrowseInto,
            onBrowseUp = viewModel::moveBrowseUp,
            onConfirm = { viewModel.confirmMove(current) },
            onDismiss = viewModel::closeMoveSheet,
        )
    }

    if (showTagPicker) {
        val vaultTags by viewModel.vaultTags.collectAsStateWithLifecycle()
        TagPickerSheet(
            tags = if (inVault) vaultTags else allTags.filterNot { it.isSystem },
            selectedIds = currentTagIds.toSet(),
            onToggle = { tag -> viewModel.toggleTag(current, tag, tag.id in currentTagIds) },
            onCreate = { name -> viewModel.createAndTag(current, name) },
            onDismiss = { showTagPicker = false },
        )
    }

    if (state.infoOpen) {
        InfoSheet(
            item = current,
            tagNames = allTags.filter { it.id in currentTagIds && !it.isSystem }.map { it.name },
            onAddTag = {
                viewModel.setInfoOpen(false)
                showTagPicker = true
            },
            onClose = { viewModel.setInfoOpen(false) },
        )
    }
}

@Composable
private fun MediaPage(
    item: MediaRef,
    isCurrent: Boolean,
    streamingUrl: String?,
    headers: Map<String, String>,
    chromeVisible: Boolean,
    onTap: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (!item.isVideo) {
            // Progressive: blurred thumbnail immediately, HD once downloaded.
            SubcomposeAsyncImage(
                model = HdRequest(item.pathB64, item.mtime),
                contentDescription = item.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                loading = {
                    AsyncImage(
                        model = ThumbRequest(item.pathB64, item.displayPath, item.mtime),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .blur(8.dp),
                    )
                },
            )
        } else if (isCurrent && streamingUrl != null) {
            VideoPlayer(
                url = streamingUrl,
                headers = headers,
                controlsVisible = chromeVisible,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Lucide.PlayFilled,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(66.dp),
                )
                if (streamingUrl == null) {
                    Text(
                        text = stringResource(R.string.viewer_video_fake_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = 40.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPlayer(
    url: String,
    headers: Map<String, String>,
    controlsVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val player = remember(url) {
        val dataSourceFactory = DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
                prepare()
            }
    }
    var isPlaying by remember(player) { mutableStateOf(false) }
    var positionMs by remember(player) { mutableStateOf(0L) }
    var durationMs by remember(player) { mutableStateOf(0L) }

    androidx.compose.runtime.DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                durationMs = player.duration.coerceAtLeast(0L)
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player, controlsVisible, isPlaying) {
        while (controlsVisible || isPlaying) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.coerceAtLeast(0L)
            delay(250)
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    // V1 feedback: the stock controller centers play/pause — ours
                    // anchors every control at the bottom, in the thumb zone.
                    useController = false
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            VideoControls(
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                onPlayPause = { if (player.isPlaying) player.pause() else player.play() },
                onSeek = { player.seekTo(it) },
                onSkipBack = { player.seekTo((player.currentPosition - SKIP_MS).coerceAtLeast(0L)) },
                onSkipForward = {
                    val target = player.currentPosition + SKIP_MS
                    player.seekTo(if (durationMs > 0) target.coerceAtMost(durationMs) else target)
                },
            )
        }
    }
}

private const val SKIP_MS = 10_000L

/** Bottom-anchored transport (V1 feedback): everything reachable with the thumb. */
@Composable
private fun VideoControls(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
) {
    val colors = boxpixColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))),
            )
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
            )
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Slider(
            value = if (durationMs > 0) positionMs / durationMs.toFloat() else 0f,
            onValueChange = { fraction ->
                if (durationMs > 0) onSeek((fraction * durationMs).toLong())
            },
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                inactiveTrackColor = Color.White.copy(alpha = 0.25f),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatPlaybackTime(positionMs),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSkipBack) {
                Icon(
                    Lucide.SkipBack,
                    contentDescription = stringResource(R.string.viewer_video_skip_back),
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
            IconButton(onClick = onPlayPause, modifier = Modifier.size(56.dp)) {
                Icon(
                    if (isPlaying) Lucide.Pause else Lucide.PlayFilled,
                    contentDescription = stringResource(
                        if (isPlaying) R.string.viewer_video_pause else R.string.viewer_video_play,
                    ),
                    tint = Color.White,
                    modifier = Modifier.size(34.dp),
                )
            }
            IconButton(onClick = onSkipForward) {
                Icon(
                    Lucide.SkipForward,
                    contentDescription = stringResource(R.string.viewer_video_skip_forward),
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = formatPlaybackTime(durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.8f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun formatPlaybackTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

@Composable
private fun ViewerTopBar(
    item: MediaRef,
    inVault: Boolean,
    onBack: () -> Unit,
    onOverflow: () -> Unit,
    overflowOpen: Boolean,
    onDismissOverflow: () -> Unit,
    onRename: () -> Unit,
    onCopyPath: () -> Unit,
    onSave: () -> Unit,
    onMove: () -> Unit,
    onTrash: () -> Unit,
    onShare: () -> Unit,
    onInfo: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.74f), Color.Transparent),
                ),
            )
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Lucide.ArrowLeft,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                maxLines = 1,
            )
            Text(
                text = formatDate(item.takenAtEpochSeconds ?: item.mtime),
                fontSize = 11.5.sp,
                color = Color.White.copy(alpha = 0.6f),
            )
        }
        Box {
            IconButton(onClick = onOverflow) {
                Icon(
                    Lucide.EllipsisVertical,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
            DropdownMenu(expanded = overflowOpen, onDismissRequest = onDismissOverflow) {
                if (!inVault) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.viewer_menu_rename)) },
                        onClick = onRename,
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.viewer_menu_copy_path)) },
                    onClick = onCopyPath,
                )
                if (!inVault) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.viewer_menu_save)) },
                        onClick = onSave,
                    )
                }
                // Videos have no bottom action bar (the player controller owns
                // that edge): file actions live here instead.
                if (item.isVideo && !inVault) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.viewer_action_move)) },
                        onClick = onMove,
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.viewer_action_trash)) },
                        onClick = onTrash,
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.viewer_action_share)) },
                        onClick = onShare,
                    )
                }
                if (item.isVideo) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.viewer_action_info)) },
                        onClick = onInfo,
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewerActionBar(
    isFavorite: Boolean,
    inVault: Boolean,
    onTag: () -> Unit,
    onFavorite: () -> Unit,
    onMove: () -> Unit,
    onTrash: () -> Unit,
    onShare: () -> Unit,
    onInfo: () -> Unit,
) {
    Row(
        modifier = Modifier
            .padding(bottom = 14.dp)
            .background(Color(0xD10E0E10), RoundedCornerShape(18.dp))
            .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(18.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Tags and favourites live in the in-vault meta for vault media; the
        // remaining actions stay Room/mirror/device-bound, so vault-hidden.
        ViewerAction(Lucide.Tag, stringResource(R.string.viewer_action_tag), onClick = onTag)
        ViewerAction(
            icon = if (isFavorite) Lucide.HeartFilled else Lucide.Heart,
            label = stringResource(R.string.viewer_action_favourite),
            tint = if (isFavorite) Hues.Favorite else Color.White,
            onClick = onFavorite,
        )
        if (!inVault) {
            ViewerAction(Lucide.FolderInput, stringResource(R.string.viewer_action_move), onClick = onMove)
            ViewerAction(Lucide.Trash2, stringResource(R.string.viewer_action_trash), onClick = onTrash)
            ViewerAction(Lucide.Share2, stringResource(R.string.viewer_action_share), onClick = onShare)
        }
        ViewerAction(Lucide.Info, stringResource(R.string.viewer_action_info), onClick = onInfo)
    }
}

@Composable
private fun ViewerAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
        Text(text = label, fontSize = 9.5.sp, color = Color.White.copy(alpha = 0.8f))
    }
}

@androidx.compose.runtime.Composable
@ExperimentalMaterial3Api
private fun InfoSheetScaffold(onClose: () -> Unit, content: @Composable () -> Unit) {
    val colors = boxpixColors
    ModalBottomSheet(
        onDismissRequest = onClose,
        containerColor = colors.elevated,
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
    ) {
        content()
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfoSheet(
    item: MediaRef,
    tagNames: List<String>,
    onAddTag: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = boxpixColors
    InfoSheetScaffold(onClose = onClose) {
        Column(modifier = Modifier.padding(horizontal = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.viewer_info_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.text,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClose) {
                    Text(stringResource(R.string.viewer_info_close), color = colors.accent)
                }
            }
            Spacer(Modifier.height(6.dp))
            if (item.kind != FileKind.PHOTO && item.kind != FileKind.VIDEO) {
                FileKindPlaceholder(
                    kind = item.kind,
                    fileName = item.name,
                    iconSize = 34.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp),
                )
                Spacer(Modifier.height(6.dp))
            }
            InfoRow(
                label = stringResource(R.string.viewer_info_taken),
                value = formatDate(item.takenAtEpochSeconds ?: item.mtime),
            )
            InfoRow(
                label = stringResource(R.string.viewer_info_size),
                value = formatBytes(item.sizeBytes),
            )
            InfoRow(
                label = stringResource(R.string.viewer_info_path),
                value = item.displayPath,
                monospace = true,
            )
            InfoRow(
                label = stringResource(R.string.tag_picker_title),
                value = if (tagNames.isEmpty()) "—" else tagNames.joinToString(", "),
            )
            TextButton(onClick = onAddTag) {
                Text(stringResource(R.string.tag_picker_new_hint), color = colors.accent)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, monospace: Boolean = false) {
    val colors = boxpixColors
    Row(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = colors.dim,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = colors.text,
            fontFamily = if (monospace) FontFamily.Monospace else null,
        )
    }
}
