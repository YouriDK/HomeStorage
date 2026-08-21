package com.boxpix.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boxpix.app.R
import com.boxpix.app.data.db.TagWithCount
import com.boxpix.app.data.tags.TagRepository
import com.boxpix.app.ui.common.message
import com.boxpix.app.ui.explorer.NameDialog
import com.boxpix.app.ui.icons.Lucide
import com.boxpix.app.ui.theme.boxpixColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ManageTagsViewModel @Inject constructor(
    private val tagRepository: TagRepository,
) : ViewModel() {

    val tags = tagRepository.tags
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _renameRejected = MutableStateFlow(false)
    val renameRejected = _renameRejected.asStateFlow()

    fun rename(tag: TagWithCount, newName: String) {
        viewModelScope.launch {
            _renameRejected.value = !tagRepository.renameTag(tag.id, newName)
        }
    }

    fun delete(tag: TagWithCount) {
        viewModelScope.launch { tagRepository.deleteTag(tag.id) }
    }

    fun merge(from: TagWithCount, into: TagWithCount) {
        viewModelScope.launch { tagRepository.mergeTags(from.id, into.id) }
    }

    fun dismissRenameRejected() {
        _renameRejected.value = false
    }
}

@Composable
fun ManageTagsScreen(
    onBack: () -> Unit,
    viewModel: ManageTagsViewModel = hiltViewModel(),
) {
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val renameRejected by viewModel.renameRejected.collectAsStateWithLifecycle()
    val colors = boxpixColors

    var renameTarget by remember { mutableStateOf<TagWithCount?>(null) }
    var deleteTarget by remember { mutableStateOf<TagWithCount?>(null) }
    var mergeSource by remember { mutableStateOf<TagWithCount?>(null) }
    var mergeInto by remember { mutableStateOf<Pair<TagWithCount, TagWithCount>?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
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
                    Lucide.ArrowLeft,
                    contentDescription = null,
                    tint = colors.text,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = stringResource(R.string.settings_manage_tags),
                style = MaterialTheme.typography.titleLarge,
                color = colors.text,
            )
        }

        if (renameRejected) {
            Text(
                text = stringResource(R.string.manage_tags_rename_taken),
                style = MaterialTheme.typography.bodySmall,
                color = colors.text,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .background(colors.elevated, RoundedCornerShape(10.dp))
                    .padding(12.dp),
            )
        }

        val editable = tags.filterNot { it.isSystem }
        LazyColumn(modifier = Modifier.padding(horizontal = 18.dp)) {
            items(editable.size, key = { editable[it].id }) { index ->
                val tag = editable[index]
                var menuOpen by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = tag.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.text,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = pluralStringResource(R.plurals.explorer_items_count, tag.usageCount, tag.usageCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.faint,
                    )
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                Lucide.EllipsisVertical,
                                contentDescription = null,
                                tint = colors.dim,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.manage_tags_rename)) },
                                onClick = {
                                    menuOpen = false
                                    renameTarget = tag
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.manage_tags_merge)) },
                                onClick = {
                                    menuOpen = false
                                    mergeSource = tag
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.manage_tags_delete)) },
                                onClick = {
                                    menuOpen = false
                                    deleteTarget = tag
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    renameTarget?.let { tag ->
        NameDialog(
            title = stringResource(R.string.dialog_rename_title),
            initialValue = tag.name,
            confirmLabel = stringResource(R.string.dialog_rename),
            onConfirm = { name ->
                renameTarget = null
                viewModel.dismissRenameRejected()
                if (name.isNotBlank()) viewModel.rename(tag, name)
            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { tag ->
        ImpactConfirmDialog(
            title = "${stringResource(R.string.manage_tags_delete)} “${tag.name}”",
            impact = tag.usageCount,
            confirmLabel = stringResource(R.string.manage_tags_delete),
            onConfirm = {
                viewModel.delete(tag)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }

    mergeSource?.let { source ->
        val candidates = tags.filterNot { it.isSystem || it.id == source.id }
        AlertDialog(
            onDismissRequest = { mergeSource = null },
            containerColor = colors.elevated,
            shape = RoundedCornerShape(14.dp),
            title = {
                Text(
                    "${stringResource(R.string.manage_tags_merge)} “${source.name}”",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.text,
                )
            },
            text = {
                Column {
                    candidates.forEach { candidate ->
                        Text(
                            text = candidate.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.text,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp)
                                .clickable {
                                    mergeInto = source to candidate
                                    mergeSource = null
                                },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { mergeSource = null }) {
                    Text(stringResource(R.string.dialog_cancel), color = colors.dim)
                }
            },
        )
    }

    mergeInto?.let { (source, target) ->
        ImpactConfirmDialog(
            title = "${source.name} → ${target.name}",
            impact = source.usageCount,
            confirmLabel = stringResource(R.string.manage_tags_merge),
            onConfirm = {
                viewModel.merge(source, target)
                mergeInto = null
            },
            onDismiss = { mergeInto = null },
        )
    }
}

@Composable
private fun ImpactConfirmDialog(
    title: String,
    impact: Int,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = boxpixColors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.elevated,
        shape = RoundedCornerShape(14.dp),
        title = { Text(title, style = MaterialTheme.typography.titleLarge, color = colors.text) },
        text = {
            Text(
                pluralStringResource(R.plurals.manage_tags_impact, impact, impact),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.dim,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = colors.accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel), color = colors.dim)
            }
        },
    )
}
