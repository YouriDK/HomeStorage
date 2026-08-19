package com.boxpix.app.ui.common

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.boxpix.app.R
import com.boxpix.app.data.db.TagWithCount
import com.boxpix.app.ui.theme.boxpixColors

/**
 * The tag picker: tags by usage (SPEC §2), checkmarks for the current
 * selection, creation on the fly. Shared by the viewer, the Explorer's
 * batch tagging and search filters.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagPickerSheet(
    tags: List<TagWithCount>,
    selectedIds: Set<Long>,
    onToggle: (TagWithCount) -> Unit,
    onCreate: (String) -> Unit,
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
            Text(
                text = stringResource(R.string.tag_picker_title),
                style = MaterialTheme.typography.titleLarge,
                color = colors.text,
            )
            Spacer(Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                items(tags.size, key = { tags[it].id }) { index ->
                    val tag = tags[index]
                    val selected = tag.id in selectedIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .clickable { onToggle(tag) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = tag.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selected) colors.accent else colors.text,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${tag.usageCount}",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.faint,
                        )
                        if (selected) {
                            Spacer(Modifier.size(10.dp))
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
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
                        val name = newTag.trim()
                        if (name.isNotEmpty()) {
                            onCreate(name)
                            newTag = ""
                        }
                    },
                ) {
                    Text(stringResource(R.string.tag_picker_add), color = colors.accent)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
