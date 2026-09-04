package com.nuvio.app.core.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A placeholder in the shape of the thing that is coming.
 *
 * Nuvio's loading surfaces used to be centred spinners over empty space, which say only "wait"
 * - they do not say what is being waited for, how much of it there will be, or that anything
 * has arrived. On the stream list that mattered twice over: results land **progressively**, one
 * addon group at a time, so a spinner that covers the list is hiding results already worth
 * reading, and a spinner that sits under it gives no sense of what is still to come.
 *
 * A skeleton answers both. It occupies the space the rows will occupy, so nothing jumps when
 * they arrive, and it is visibly *the list, loading* rather than a modal wait.
 *
 * The sweep is the same 1.2 s left-to-right motion as the playback loading screen's progress
 * line, deliberately: one indeterminate treatment for the whole app rather than the three
 * different spinners this path used to run.
 */
@Composable
fun NuvioSkeletonBlock(
    modifier: Modifier = Modifier,
    height: Dp = 16.dp,
    cornerRadius: Dp = 6.dp,
) {
    val transition = rememberInfiniteTransition(label = "nuvioSkeleton")
    val head by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "nuvioSkeletonHead",
    )
    val base = MaterialTheme.nuvio.colors.skeleton
    val highlight = MaterialTheme.nuvio.colors.shimmer

    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(cornerRadius))
            .background(base),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colorStops = arrayOf(
                            (head - 0.3f).coerceIn(0f, 1f) to Color.Transparent,
                            head.coerceIn(0f, 1f) to highlight,
                            (head + 0.3f).coerceIn(0f, 1f) to Color.Transparent,
                        ),
                    ),
                ),
        )
    }
}

/**
 * One placeholder stream row: a title line, a shorter detail line, a filename line.
 *
 * Shaped like the rows the stream list actually draws, because a skeleton whose proportions do
 * not match what replaces it produces exactly the layout jump it exists to prevent.
 */
@Composable
fun NuvioSkeletonStreamRow(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NuvioSkeletonBlock(modifier = Modifier.fillMaxWidth(0.55f), height = 18.dp)
        NuvioSkeletonBlock(modifier = Modifier.fillMaxWidth(0.34f), height = 13.dp)
        NuvioSkeletonBlock(modifier = Modifier.fillMaxWidth(0.82f), height = 12.dp)
    }
}
