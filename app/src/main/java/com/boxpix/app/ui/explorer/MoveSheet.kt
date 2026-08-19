package com.boxpix.app.ui.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.boxpix.app.R
import com.boxpix.app.data.storage.StorageEntry
import com.boxpix.app.ui.icons.Lucide
import com.boxpix.app.ui.theme.boxpixColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveSheet(
    move: ExplorerViewModel.MoveState,
    selectionCount: Int,
    onBrowseInto: (StorageEntry) -> Unit,
    onBrowseUp: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = stringResource(R.string.move_here),
) {
    val colors = boxpixColors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.elevated,
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(
                text = pluralStringResource(R.plurals.move_title, selectionCount, selectionCount),
                style = MaterialTheme.typography.titleLarge,
                color = colors.text,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = move.current?.displayPath.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = colors.dim,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
            Spacer(Modifier.height(10.dp))

            if (move.stack.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clickable(onClick = onBrowseUp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Lucide.ArrowLeft,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(12.dp))
                    Text(
                        text = stringResource(R.string.move_up),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.accent,
                    )
                }
            }

            if (move.loading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = colors.accent,
                        trackColor = colors.hairline,
                        strokeWidth = 1.5.dp,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(move.folders.size, key = { move.folders[it].pathB64 }) { index ->
                        val folder = move.folders[index]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clickable { onBrowseInto(folder) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Lucide.Folder,
                                contentDescription = null,
                                tint = colors.dim,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.size(12.dp))
                            Text(
                                text = folder.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.text,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                            )
                            Icon(
                                Lucide.ChevronRight,
                                contentDescription = null,
                                tint = colors.faint,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.dialog_cancel), color = colors.dim)
                }
                Spacer(Modifier.size(8.dp))
                Row(
                    modifier = Modifier
                        .height(40.dp)
                        .border(1.dp, colors.accent, RoundedCornerShape(10.dp))
                        .clickable(onClick = onConfirm)
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = confirmLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.accent,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
