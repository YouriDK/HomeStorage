package com.boxpix.app.ui.sortmode

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.boxpix.app.R
import com.boxpix.app.data.prefs.PinnedDestination
import com.boxpix.app.ui.common.HdRequest
import com.boxpix.app.ui.common.formatBytes
import com.boxpix.app.ui.common.message
import com.boxpix.app.ui.explorer.MoveSheet
import com.boxpix.app.ui.explorer.ExplorerViewModel
import com.boxpix.app.ui.icons.Lucide
import com.boxpix.app.ui.theme.Hues
import com.boxpix.app.ui.theme.boxpixColors
import kotlinx.coroutines.delay

/** Screen 06 — the signature sort mode: everything reachable with the thumb. */
@Composable
fun SortModeScreen(
    onBack: () -> Unit,
    viewModel: SortModeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val allTags by viewModel.allTags.collectAsStateWithLifecycle()
    val colors = boxpixColors

    LaunchedEffect(state.confirmation) {
        if (state.confirmation != null) {
            delay(1_400)
            viewModel.dismissConfirmation()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Lucide.X, contentDescription = null, tint = colors.text, modifier = Modifier.size(22.dp))
            }
            Text(
                text = stringResource(R.string.sort_title, state.folder?.name.orEmpty()),
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${(state.total - state.queue.size + state.index).coerceAtMost(state.total)} / ${state.total}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.dim,
            )
            IconButton(onClick = viewModel::undo, enabled = state.lastAction != null) {
                Icon(
                    Lucide.Undo2,
                    contentDescription = null,
                    tint = if (state.lastAction != null) colors.accent else colors.faint,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        LinearProgressIndicator(
            progress = { state.progress },
            color = colors.accent,
            trackColor = colors.hairline,
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp),
        )

        val current = state.current
        if (current == null) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.sort_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.dim,
                )
            }
        } else {
            // The photo card — swipe up to skip
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surface)
                    .pointerInput(current.pathB64) {
                        var accumulated = 0f
                        detectVerticalDragGestures(
                            onDragStart = { accumulated = 0f },
                            onVerticalDrag = { _, dragAmount -> accumulated += dragAmount },
                            onDragEnd = { if (accumulated < -180f) viewModel.skip() },
                        )
                    },
            ) {
                AsyncImage(
                    model = if (current.isVideo) {
                        com.boxpix.app.ui.common.ThumbRequest(
                            current.pathB64, current.displayPath, current.mtime, isVideo = true,
                        )
                    } else {
                        HdRequest(current.pathB64, current.mtime)
                    },
                    contentDescription = current.name,
                    modifier = Modifier.matchParentSize(),
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))),
                        )
                        .padding(12.dp),
                ) {
                    Text(current.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White)
                    Text(formatBytes(current.sizeBytes), fontSize = 10.5.sp, color = Color.White.copy(alpha = 0.7f))
                }
                state.confirmation?.let { destination ->
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(colors.accentSoft),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.sort_moved_to, destination),
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.accent,
                            modifier = Modifier
                                .background(colors.elevated, RoundedCornerShape(100.dp))
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            state.error?.let { error ->
                Text(
                    text = error.message(),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.text,
                    modifier = Modifier
                        .padding(horizontal = 14.dp)
                        .clickable { viewModel.dismissError() },
                )
            }

            // Quick tags
            val currentTagNames by remember(current.pathB64) {
                viewModel.currentTagNamesFlow(current.pathB64)
            }.collectAsStateWithLifecycle(initialValue = emptyList())

            Text(
                text = stringResource(R.string.sort_quick_tags),
                style = MaterialTheme.typography.labelSmall,
                color = colors.faint,
                modifier = Modifier.padding(start = 14.dp, top = 4.dp, bottom = 4.dp),
            )
            Row(
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(state.quickTags.size, key = { state.quickTags[it] }) { index ->
                        val tag = state.quickTags[index]
                        val active = currentTagNames.any { it.equals(tag, ignoreCase = true) }
                        QuickTagChip(tag, active) { viewModel.toggleQuickTag(tag) }
                    }
                }
                Text(
                    text = stringResource(R.string.sort_swipe_hint),
                    fontSize = 10.sp,
                    color = colors.faint,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            // Bottom block: destinations + skip + trash
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.sort_move_to) + " " +
                            stringResource(R.string.sort_pinned_count, state.destinations.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.faint,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { viewModel.setShortcutsOpen(true) }) {
                        Text(stringResource(R.string.sort_edit), color = colors.accent)
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.destinations.size, key = { state.destinations[it].pathB64 }) { index ->
                        DestinationCard(state.destinations[index]) { viewModel.moveTo(state.destinations[index]) }
                    }
                    item(key = "add") {
                        Box(
                            modifier = Modifier
                                .size(width = 56.dp, height = 56.dp)
                                .border(1.dp, colors.hairlineStrong, RoundedCornerShape(10.dp))
                                .clickable { viewModel.setPickerOpen(true) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Lucide.Plus, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .border(1.dp, colors.hairlineStrong, RoundedCornerShape(10.dp))
                            .clickable { viewModel.skip() },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.sort_skip), style = MaterialTheme.typography.bodyLarge, color = colors.text)
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .border(1.dp, colors.hairlineStrong, RoundedCornerShape(10.dp))
                            .clickable { viewModel.trashCurrent() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Lucide.Trash2, contentDescription = stringResource(R.string.explorer_action_trash), tint = colors.dim, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }

    if (state.coachVisible) {
        SortCoachOverlay(onDone = viewModel::dismissCoach)
    }

    if (state.shortcutsOpen) {
        ShortcutsSheet(
            destinations = state.destinations,
            quickTags = state.quickTags,
            onUnpin = viewModel::unpinDestination,
            onMove = viewModel::moveDestination,
            onPinAnother = {
                viewModel.setShortcutsOpen(false)
                viewModel.setPickerOpen(true)
            },
            onAddQuickTag = viewModel::addQuickTag,
            onRemoveQuickTag = viewModel::removeQuickTag,
            onDismiss = { viewModel.setShortcutsOpen(false) },
        )
    }

    if (state.pickerOpen) {
        FolderPickerForPin(
            root = state.folder,
            onPin = viewModel::pinDestination,
            onDismiss = { viewModel.setPickerOpen(false) },
        )
    }
}

/**
 * First-open coach (V1 feedback): three sequential bubbles — pinned
 * destinations, quick tags, swipe-to-skip — each anchored near what it explains.
 */
@Composable
private fun SortCoachOverlay(onDone: () -> Unit) {
    val colors = boxpixColors
    var step by remember { mutableStateOf(0) }
    val steps = listOf(
        Triple(R.string.sort_coach_pins_title, R.string.sort_coach_pins_body, Alignment.BottomCenter),
        Triple(R.string.sort_coach_tags_title, R.string.sort_coach_tags_body, Alignment.BottomCenter),
        Triple(R.string.sort_coach_swipe_title, R.string.sort_coach_swipe_body, Alignment.Center),
    )
    val (title, body, anchor) = steps[step]
    val advance: () -> Unit = { if (step < steps.lastIndex) step++ else onDone() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = advance),
    ) {
        Column(
            modifier = Modifier
                .align(anchor)
                .padding(horizontal = 26.dp, vertical = if (anchor == Alignment.BottomCenter) 130.dp else 0.dp)
                .background(colors.elevated, RoundedCornerShape(14.dp))
                .padding(18.dp),
        ) {
            Text(
                text = stringResource(title),
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(body),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.dim,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${step + 1} / ${steps.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.faint,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = advance) {
                    Text(
                        text = stringResource(
                            if (step < steps.lastIndex) R.string.sort_coach_next else R.string.sort_coach_done,
                        ),
                        color = colors.accent,
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickTagChip(name: String, active: Boolean, onClick: () -> Unit) {
    val colors = boxpixColors
    Row(
        modifier = Modifier
            .height(30.dp)
            .background(if (active) colors.accentSoft else colors.bg.copy(alpha = 0f), RoundedCornerShape(100.dp))
            .border(1.dp, if (active) colors.accent else colors.hairlineStrong, RoundedCornerShape(100.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = MaterialTheme.typography.bodySmall, color = if (active) colors.accent else colors.dim)
    }
}

@Composable
private fun DestinationCard(destination: PinnedDestination, onClick: () -> Unit) {
    val colors = boxpixColors
    Row(
        modifier = Modifier
            .size(width = 134.dp, height = 56.dp)
            .background(colors.elevated, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Lucide.Folder, contentDescription = null, tint = Hues.Folder, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = destination.name,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            color = colors.text,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShortcutsSheet(
    destinations: List<PinnedDestination>,
    quickTags: List<String>,
    onUnpin: (PinnedDestination) -> Unit,
    onMove: (PinnedDestination, Boolean) -> Unit,
    onPinAnother: () -> Unit,
    onAddQuickTag: (String) -> Unit,
    onRemoveQuickTag: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = boxpixColors
    var newTag by remember { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.elevated,
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.sort_shortcuts_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.text,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.sort_shortcuts_done), color = colors.accent)
                }
            }

            LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                items(destinations.size, key = { destinations[it].pathB64 }) { index ->
                    val destination = destinations[index]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = colors.faint, modifier = Modifier.width(20.dp))
                        Text(destination.name, style = MaterialTheme.typography.bodyLarge, color = colors.text, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onMove(destination, true) }, enabled = index > 0) {
                            Icon(Lucide.ChevronUp, contentDescription = null, tint = if (index > 0) colors.dim else colors.faint, modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { onMove(destination, false) }, enabled = index < destinations.lastIndex) {
                            Icon(Lucide.ChevronDown, contentDescription = null, tint = if (index < destinations.lastIndex) colors.dim else colors.faint, modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { onUnpin(destination) }) {
                            Icon(Lucide.X, contentDescription = null, tint = colors.faint, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            TextButton(onClick = onPinAnother) {
                Text(stringResource(R.string.sort_pin_folder), color = colors.accent)
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.sort_quick_tags).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = colors.faint,
            )
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(quickTags.size, key = { quickTags[it] }) { index ->
                    val tag = quickTags[index]
                    Row(
                        modifier = Modifier
                            .height(30.dp)
                            .border(1.dp, colors.hairlineStrong, RoundedCornerShape(100.dp))
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(tag, style = MaterialTheme.typography.bodySmall, color = colors.dim)
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Lucide.X,
                            contentDescription = null,
                            tint = colors.faint,
                            modifier = Modifier
                                .size(13.dp)
                                .clickable { onRemoveQuickTag(tag) },
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newTag,
                    onValueChange = { newTag = it },
                    placeholder = { Text(stringResource(R.string.tag_picker_new_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = colors.hairlineStrong,
                        cursorColor = colors.accent,
                    ),
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        if (newTag.isNotBlank()) {
                            onAddQuickTag(newTag)
                            newTag = ""
                        }
                    },
                ) { Text(stringResource(R.string.tag_picker_add), color = colors.accent) }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.sort_shortcuts_note),
                style = MaterialTheme.typography.labelMedium,
                color = colors.faint,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Folder browser for pinning — reuses the move sheet's browsing UX. */
@Composable
private fun FolderPickerForPin(
    root: ExplorerViewModel.FolderRef?,
    onPin: (com.boxpix.app.data.storage.StorageEntry) -> Unit,
    onDismiss: () -> Unit,
    viewModel: PinPickerViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.start() }
    val move by viewModel.move.collectAsStateWithLifecycle()
    if (!move.visible) return
    MoveSheet(
        move = move,
        selectionCount = 0,
        onBrowseInto = viewModel::browseInto,
        onBrowseUp = viewModel::browseUp,
        onConfirm = {
            viewModel.currentEntry()?.let(onPin)
            onDismiss()
        },
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.sort_pick_here),
    )
}
