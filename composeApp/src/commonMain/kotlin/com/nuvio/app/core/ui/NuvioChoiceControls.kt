package com.nuvio.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/*
 * Nuvio Z: the choice controls the setup wizard and the settings that mirror it share (Phase 9
 * wizard polish). Three shapes, one visual language - a radio on the left, the selected choice
 * outlined in the accent - so a mode card, a list of levels and a short segmented question read
 * as the same kind of thing on every step:
 *
 *  - [NuvioChoiceCard]: a mode with a sentence or two of explanation (Playback Mode, Download Mode).
 *  - [NuvioChoiceList] + [NuvioChoiceRow]: several options that each need their own description and
 *    figures, all visible at once (download size levels).
 *  - [NuvioSegmentedChoice]: two to five short options where the label says enough (quality limit,
 *    language, HDR, mobile data).
 *
 * ⚠ Painted with `overlayHover`, not a surface colour: the wizard panel *is* `colors.surface`, so a
 * surface-coloured card on it is invisible - the trap the quality sheet and `SetupChoiceGroup` hit.
 */

/** The radio: a ring, filled with a dot when selected. Decorative - the row carries the semantics. */
@Composable
fun NuvioChoiceIndicator(selected: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.nuvio.colors
    Box(
        modifier = modifier
            .size(20.dp)
            .clip(CircleShape)
            .then(
                if (selected) {
                    Modifier.background(colors.accent)
                } else {
                    Modifier.border(2.dp, colors.textMuted, CircleShape)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(colors.onAccent))
        }
    }
}

/** "Recommended", and nothing else earns one: a badge on every option is no badge. */
@Composable
fun NuvioChoiceBadge(text: String) {
    val colors = MaterialTheme.nuvio.colors
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colors.accent.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = colors.accent,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
    )
}

/**
 * One choice among a few, explained in a sentence: radio, title (+ badge), description, and an
 * optional extra line for a state such as "not available in this version".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NuvioChoiceCard(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    badge: String? = null,
    enabled: Boolean = true,
    note: String? = null,
    /** A few words between the title and the description - a mode's one-line promise. */
    tagline: String? = null,
) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.55f)
            .clip(shape)
            .background(if (selected) colors.accent.copy(alpha = 0.10f) else colors.overlayHover)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) colors.accent else colors.borderSubtle.copy(alpha = 0.5f),
                shape = shape,
            )
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        NuvioChoiceIndicator(selected = selected, modifier = Modifier.padding(top = 2.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                if (badge != null) NuvioChoiceBadge(badge)
            }
            if (tagline != null) {
                Text(
                    text = tagline,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textPrimary.copy(alpha = 0.86f),
                    fontWeight = FontWeight.Medium,
                )
            }
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.danger,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/** A grouped list of [NuvioChoiceRow]s: one rounded container, hairlines between rows. */
@Composable
fun NuvioChoiceList(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.overlayHover)
            .border(1.dp, colors.borderSubtle.copy(alpha = 0.5f), shape),
        content = content,
    )
}

/**
 * One option in a [NuvioChoiceList]. Everything the option means is on the row - title, badge,
 * a short description and its figures on the right - so nothing has to be tapped to be read.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NuvioChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    badge: String? = null,
    showDivider: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.nuvio.colors
    Column(modifier.fillMaxWidth()) {
        if (showDivider) {
            Box(
                Modifier
                    .padding(start = 50.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.borderSubtle.copy(alpha = 0.45f)),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (selected) colors.accent.copy(alpha = 0.10f) else Color.Transparent)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .heightIn(min = 52.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NuvioChoiceIndicator(selected = selected)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                // Wraps, so a narrow phone moves the badge under the name rather than cutting it.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.textPrimary,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                    )
                    if (badge != null) NuvioChoiceBadge(badge)
                }
                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
            }
            if (trailing != null) trailing()
        }
    }
}

/**
 * How wide each segment is. On one line when everything fits (each gets its label plus an equal share
 * of the rest); otherwise every segment starts at its longest word - so a label wraps only between
 * words, never inside one ("Automat / ic" is what equal widths did) - and the room left is shared
 * among the segments that still want more. Equal widths only if even the longest words cannot fit.
 */
internal fun segmentWidths(available: Int, natural: List<Int>, minimum: List<Int>): List<Int> {
    val count = natural.size.coerceAtLeast(1)
    if (natural.sum() <= available) {
        val extra = (available - natural.sum()) / count
        return natural.map { it + extra }
    }
    if (minimum.sum() > available) return List(natural.size) { available / count }
    val widths = minimum.toMutableList()
    var left = available - widths.sum()
    while (left > 0) {
        val wanting = widths.indices.filter { widths[it] < natural[it] }
        if (wanting.isEmpty()) break
        val share = (left / wanting.size).coerceAtLeast(1)
        for (i in wanting) {
            if (left <= 0) break
            val add = minOf(share, natural[i] - widths[i], left)
            widths[i] += add
            left -= add
        }
    }
    return widths
}

/** One option of a [NuvioSegmentedChoice]: its label and, optionally, a quieter second line. */
data class NuvioSegment<T>(val label: String, val value: T, val sublabel: String? = null)

/**
 * A short question answered in one row: the options sit side by side in a single track and the
 * chosen one is filled. [selected] may be null - a value this row does not offer (a strict HDR rule
 * shown elsewhere) leaves every segment unfilled rather than pretending.
 *
 * ⚠ **Measured, not weighted.** Equal weights truncate the longest label the moment one option is
 * wordier than the rest ("Best available" beside "4K"). Each segment gets its label's own width
 * plus an equal share of what is left; only if the labels cannot fit side by side at all do they
 * share the width equally and wrap to a second line - never an ellipsis, never one letter per line.
 */
@Composable
fun <T> NuvioSegmentedChoice(
    segments: List<NuvioSegment<T>>,
    selected: T?,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    /** Smaller labels and tighter padding, for five options on a phone. */
    compact: Boolean = false,
) {
    val colors = MaterialTheme.nuvio.colors
    val trackShape = RoundedCornerShape(14.dp)
    val segmentShape = RoundedCornerShape(11.dp)
    Layout(
        modifier = modifier
            .fillMaxWidth()
            .clip(trackShape)
            .background(colors.overlayHover)
            .border(1.dp, colors.borderSubtle.copy(alpha = 0.5f), trackShape)
            .padding(3.dp),
        content = {
            segments.forEach { segment ->
                val isSelected = segment.value == selected
                Column(
                    modifier = Modifier
                        .clip(segmentShape)
                        .background(if (isSelected) colors.accent else Color.Transparent)
                        .selectable(selected = isSelected, role = Role.RadioButton) { onSelected(segment.value) }
                        .heightIn(min = 42.dp)
                        .padding(horizontal = if (compact) 4.dp else 8.dp, vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = segment.label,
                        style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (isSelected) colors.onAccent else colors.textPrimary,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                    if (segment.sublabel != null) {
                        Text(
                            text = segment.sublabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) colors.onAccent.copy(alpha = 0.8f) else colors.textMuted,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val available = constraints.maxWidth
        val widths = segmentWidths(
            available = available,
            natural = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) },
            minimum = measurables.map { it.minIntrinsicWidth(Constraints.Infinity) },
        )
        // Every segment as tall as the tallest, so a wrapped label does not leave its neighbours short.
        // ⚠ Read from intrinsics: a measurable may be measured once only (the first version measured
        // twice and crashed every step it was on - the render harness caught it).
        val height = measurables.mapIndexed { i, m -> m.maxIntrinsicHeight(widths[i]) }.maxOrNull() ?: 0
        val placeables = measurables.mapIndexed { i, m ->
            m.measure(Constraints.fixed(widths[i], height))
        }
        layout(available, height) {
            var x = 0
            placeables.forEach { placeable ->
                placeable.placeRelative(x, 0)
                x += placeable.width
            }
        }
    }
}
