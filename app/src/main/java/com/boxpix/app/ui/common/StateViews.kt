package com.boxpix.app.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.boxpix.app.R
import com.boxpix.app.ui.theme.boxpixColors

/** S1 — first request after disk sleep; reassuring, never an error. */
@Composable
fun WakingDiskView(modifier: Modifier = Modifier) {
    val colors = boxpixColors
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            color = colors.accent,
            trackColor = colors.hairline,
            strokeWidth = 1.5.dp,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.state_waking_disk),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.text,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.state_waking_disk_hint),
            style = MaterialTheme.typography.bodySmall,
            color = colors.dim,
            textAlign = TextAlign.Center,
        )
    }
}

/** S3 — skeleton grid: pulsing neutral squares, staggered by column. */
@Composable
fun GridSkeleton(columns: Int, modifier: Modifier = Modifier) {
    val darkTheme = isSystemInDarkTheme()
    val tone = PlaceholderTones.tone("skeleton", darkTheme)
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alphas = List(columns) { column ->
        transition.animateFloat(
            initialValue = 0.45f,
            targetValue = 0.8f,
            animationSpec = infiniteRepeatable(
                animation = tween(1_600, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
                initialStartOffset = StartOffset(column * 120),
            ),
            label = "skeleton-col-$column",
        )
    }
    Column(modifier = modifier.fillMaxSize()) {
        repeat(8) {
            Row(Modifier.fillMaxWidth()) {
                repeat(columns) { column ->
                    val alpha by alphas[column]
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .padding(1.dp)
                            .alpha(alpha)
                            .background(tone),
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyFolderView(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.state_empty_folder),
            style = MaterialTheme.typography.bodyMedium,
            color = boxpixColors.dim,
        )
    }
}

@Composable
fun ErrorView(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val colors = boxpixColors
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.dim,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.state_retry), color = colors.accent)
        }
    }
}
