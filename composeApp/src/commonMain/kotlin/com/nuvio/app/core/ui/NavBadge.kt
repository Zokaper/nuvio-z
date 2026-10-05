package com.nuvio.app.core.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Social's live green, so the badge reads as "something is waiting on Social". */
private val NavBadgeColor = Color(0xFF6FD08C)

/**
 * A small dot on the top-end corner of a navigation icon - Nuvio Z: the Social tab's unread badge.
 *
 * Drawn over the content rather than laid out beside it, so a badged icon measures exactly like an
 * unbadged one and the bar does not shift when it appears. Put it **first** in the chain: modifiers
 * after it (the selected icon's gradient mask) would otherwise mask the dot too.
 */
internal fun Modifier.navBadgeDot(visible: Boolean): Modifier =
    if (!visible) {
        this
    } else {
        drawWithContent {
            drawContent()
            val radius = size.minDimension * 0.18f
            val center = Offset(size.width - radius * 0.5f, radius * 0.5f)
            drawCircle(Color.Black.copy(alpha = 0.65f), radius = radius + 1.5.dp.toPx(), center = center)
            drawCircle(NavBadgeColor, radius = radius, center = center)
        }
    }
