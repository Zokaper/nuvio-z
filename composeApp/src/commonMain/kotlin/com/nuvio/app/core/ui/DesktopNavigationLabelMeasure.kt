package com.nuvio.app.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The desktop top bar's track width, sized for its widest label as the jelly tabs will draw it
 * (`JellyTabContent`'s horizontal `labelLarge`, medium weight) - measured, not guessed, so a longer
 * translation or a larger UI zoom is accounted for too. The rule is `DesktopNavigationTrackWidth.kt`.
 */
@Composable
internal fun rememberDesktopNavigationTrackWidth(
    items: List<FloatingNavigationItem>,
    iconSize: Dp,
    labelFraction: Float,
    maxWidth: Dp,
): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium)
    val labels = items.map { it.label }
    val widestLabelDp = remember(labels, style, density) {
        labels.maxOfOrNull { label ->
            with(density) { measurer.measure(label, style = style, maxLines = 1, softWrap = false).size.width.toDp().value }
        } ?: 0f
    }
    return desktopNavigationTrackWidthDp(
        iconSizeDp = iconSize.value,
        labelAllowanceDp = desktopNavigationLabelAllowanceDp(widestLabelDp),
        labelFraction = labelFraction,
        itemCount = items.size,
        maxWidthDp = maxWidth.value,
    ).dp
}
