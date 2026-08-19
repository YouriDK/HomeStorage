package com.boxpix.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.boxpix.app.R
import com.boxpix.app.ui.theme.boxpixColors

enum class MainTab { EXPLORER, GALLERY }

/** Bottom floating 2-tab pill from the design, shared by both tabs. */
@Composable
fun FloatingTabBar(
    active: MainTab,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = boxpixColors
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .padding(bottom = 16.dp)
            .shadow(14.dp, shape, clip = false)
            .background(colors.surface, shape)
            .border(1.dp, colors.hairlineStrong, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabPillItem(
            icon = Icons.Outlined.Folder,
            label = stringResource(R.string.tab_explorer),
            active = active == MainTab.EXPLORER,
            onClick = { onSelect(MainTab.EXPLORER) },
        )
        Spacer(Modifier.size(18.dp))
        TabPillItem(
            icon = Icons.Outlined.CalendarMonth,
            label = stringResource(R.string.tab_gallery),
            active = active == MainTab.GALLERY,
            onClick = { onSelect(MainTab.GALLERY) },
        )
    }
}

@Composable
private fun TabPillItem(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val colors = boxpixColors
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (active) colors.accent else colors.dim,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(7.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            color = if (active) colors.accent else colors.dim,
        )
    }
}
