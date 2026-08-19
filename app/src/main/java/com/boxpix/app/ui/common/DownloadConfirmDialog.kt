package com.boxpix.app.ui.common

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.boxpix.app.R
import com.boxpix.app.ui.theme.boxpixColors

/** Metered-connection guard: over the threshold, downloading asks first. */
@Composable
fun DownloadConfirmDialog(totalBytes: Long, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = boxpixColors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.elevated,
        shape = RoundedCornerShape(14.dp),
        title = {
            Text(
                stringResource(R.string.download_confirm_title),
                style = MaterialTheme.typography.titleLarge,
                color = colors.text,
            )
        },
        text = {
            Text(
                stringResource(R.string.download_confirm_message, formatBytes(totalBytes)),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.dim,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.dialog_download), color = colors.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel), color = colors.dim)
            }
        },
    )
}
