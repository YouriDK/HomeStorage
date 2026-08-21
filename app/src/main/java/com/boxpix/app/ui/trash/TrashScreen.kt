package com.boxpix.app.ui.trash

import androidx.compose.foundation.background
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boxpix.app.R
import com.boxpix.app.data.db.TrashItemEntity
import com.boxpix.app.ui.common.formatBytes
import com.boxpix.app.ui.common.formatDate
import com.boxpix.app.ui.common.message
import com.boxpix.app.ui.icons.Lucide
import com.boxpix.app.ui.theme.boxpixColors

@Composable
fun TrashScreen(
    onBack: () -> Unit,
    viewModel: TrashViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val colors = boxpixColors

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
                text = stringResource(R.string.trash_title),
                style = MaterialTheme.typography.titleLarge,
                color = colors.text,
                modifier = Modifier.weight(1f),
            )
            if (items.isNotEmpty()) {
                TextButton(onClick = viewModel::emptyTrash) {
                    Text(
                        text = stringResource(R.string.trash_empty_all),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.dim,
                    )
                }
            }
        }

        Text(
            text = stringResource(R.string.trash_auto_note),
            style = MaterialTheme.typography.labelMedium,
            color = colors.faint,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
        )

        error?.let {
            Text(
                text = it.message(),
                style = MaterialTheme.typography.bodySmall,
                color = colors.text,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 6.dp),
            )
        }

        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.trash_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.dim,
                )
            }
        } else {
            LazyColumn {
                items(items.size, key = { items[it].trashPathB64 }) { index ->
                    TrashRow(
                        item = items[index],
                        onRestore = { viewModel.restore(items[index]) },
                        onPurge = { viewModel.purge(items[index]) },
                    )
                    HairlineDivider()
                }
            }
        }
    }
}

@Composable
private fun TrashRow(
    item: TrashItemEntity,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
) {
    val colors = boxpixColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (item.isDirectory) Lucide.Folder else Lucide.File,
            contentDescription = null,
            tint = colors.dim,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.text,
                maxLines = 1,
            )
            Text(
                text = "${item.originalParentPath} · ${formatDate(item.trashedAtEpochSeconds)}" +
                    if (!item.isDirectory) " · ${formatBytes(item.sizeBytes)}" else "",
                style = MaterialTheme.typography.labelMedium,
                color = colors.dim,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
        TextButton(onClick = onRestore) {
            Text(
                text = stringResource(R.string.trash_restore),
                style = MaterialTheme.typography.bodySmall,
                color = colors.accent,
            )
        }
        IconButton(onClick = onPurge) {
            Icon(
                Lucide.Trash,
                contentDescription = stringResource(R.string.trash_delete_forever),
                tint = colors.faint,
                modifier = Modifier.size(20.dp),
            )
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
