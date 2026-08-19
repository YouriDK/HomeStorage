package com.boxpix.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import com.boxpix.app.R
import com.boxpix.app.data.db.TagWithCount
import com.boxpix.app.ui.theme.boxpixColors

/**
 * Batch metadata sheet (V1 feedback): tags to add, capture date, place —
 * applied to the whole selection. Key use case: fixing the date of a stack
 * of scanned photos in one gesture.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MetadataSheet(
    selectionCount: Int,
    tags: List<TagWithCount>,
    onApply: (tagIds: Set<Long>, takenAtEpochSeconds: Long?, location: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = boxpixColors
    var selectedTagIds by remember { mutableStateOf(emptySet<Long>()) }
    var takenAtEpochSeconds by remember { mutableStateOf<Long?>(null) }
    var location by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }

    val hasChanges = selectedTagIds.isNotEmpty() || takenAtEpochSeconds != null || location.isNotBlank()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.elevated,
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp)) {
            Text(
                text = stringResource(R.string.metadata_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                color = colors.text,
            )
            Text(
                text = pluralStringResource(R.plurals.metadata_sheet_count, selectionCount, selectionCount),
                style = MaterialTheme.typography.bodySmall,
                color = colors.dim,
            )
            Spacer(Modifier.height(14.dp))

            if (tags.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.metadata_tags_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.dim,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    tags.forEach { tag ->
                        val selected = tag.id in selectedTagIds
                        Text(
                            text = tag.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (selected) colors.accent else colors.dim,
                            modifier = Modifier
                                .background(
                                    if (selected) colors.accentSoft else colors.bg.copy(alpha = 0f),
                                    RoundedCornerShape(100.dp),
                                )
                                .border(
                                    1.dp,
                                    if (selected) colors.accent else colors.hairlineStrong,
                                    RoundedCornerShape(100.dp),
                                )
                                .clickable {
                                    selectedTagIds = if (selected) {
                                        selectedTagIds - tag.id
                                    } else {
                                        selectedTagIds + tag.id
                                    }
                                }
                                .padding(horizontal = 13.dp, vertical = 7.dp),
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clickable { showDatePicker = true },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.metadata_date_label),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.text,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = takenAtEpochSeconds?.let { formatDate(it) }
                        ?: stringResource(R.string.metadata_unchanged),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (takenAtEpochSeconds != null) colors.accent else colors.faint,
                )
            }

            OutlinedTextField(
                value = location,
                onValueChange = { location = it },
                placeholder = { Text(stringResource(R.string.metadata_location_hint)) },
                label = { Text(stringResource(R.string.metadata_location_label)) },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.hairlineStrong,
                    cursorColor = colors.accent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.metadata_sheet_hint),
                style = MaterialTheme.typography.bodySmall,
                color = colors.faint,
            )

            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.dialog_cancel), color = colors.dim)
                }
                TextButton(
                    enabled = hasChanges,
                    onClick = {
                        onApply(
                            selectedTagIds,
                            takenAtEpochSeconds,
                            location.trim().takeIf { it.isNotEmpty() },
                        )
                    },
                ) {
                    Text(
                        stringResource(R.string.metadata_apply),
                        color = if (hasChanges) colors.accent else colors.faint,
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = takenAtEpochSeconds?.times(1000),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDatePicker = false
                        takenAtEpochSeconds = pickerState.selectedDateMillis?.div(1000)
                    },
                ) { Text(stringResource(R.string.dialog_ok), color = colors.accent) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDatePicker = false
                        takenAtEpochSeconds = null
                    },
                ) { Text(stringResource(R.string.metadata_unchanged), color = colors.dim) }
            },
            colors = DatePickerDefaults.colors(containerColor = colors.elevated),
        ) {
            DatePicker(state = pickerState)
        }
    }
}
