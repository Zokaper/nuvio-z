package com.nuvio.app.features.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.downloads.DynamicRangePolicy
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.playback_preferences_description
import nuvio.composeapp.generated.resources.playback_preferences_done
import nuvio.composeapp.generated.resources.playback_preferences_title
import nuvio.composeapp.generated.resources.settings_playback_dynamic_range
import nuvio.composeapp.generated.resources.settings_playback_dynamic_range_avoid
import nuvio.composeapp.generated.resources.settings_playback_dynamic_range_prefer
import nuvio.composeapp.generated.resources.settings_playback_dynamic_range_require
import nuvio.composeapp.generated.resources.settings_playback_dynamic_range_require_dv
import nuvio.composeapp.generated.resources.settings_playback_language_off
import nuvio.composeapp.generated.resources.settings_playback_language_prefer
import nuvio.composeapp.generated.resources.settings_playback_language_require
import nuvio.composeapp.generated.resources.settings_playback_language_strictness
import nuvio.composeapp.generated.resources.settings_playback_preference_any
import nuvio.composeapp.generated.resources.settings_playback_quality_ceiling
import nuvio.composeapp.generated.resources.settings_playback_quality_ceiling_off
import nuvio.composeapp.generated.resources.settings_playback_quality_ceiling_value
import org.jetbrains.compose.resources.stringResource

/**
 * The three preferences that shape the quality sheet, adjustable without leaving it.
 *
 * **Why this exists rather than a link to Settings.** Two of these rows - dynamic range and
 * codec - shipped behind "Show advanced settings", so a user asking where to set an HDR or
 * Dolby Vision preference was looking at a page that appeared not to have one. That is fixed on
 * the settings page itself; this is the other half. The moment anyone wants to change a language
 * rule or a ceiling is the moment they are staring at a row they disagree with, and Settings is
 * on a different back stack - navigating there would pop `StreamRoute` and lose the play, so the
 * answer would cost them the episode they asked for.
 *
 * **Rows cycle rather than opening a picker.** Every option here has three to five values, all
 * of which fit in the row's own subtitle, and a dialog stacked on a dialog stacked on the sheet
 * is three surfaces deep over a screen the user is trying to leave. Cycling shows the current
 * value and what tapping will do, which is the whole interaction.
 *
 * Writes go through the caller and therefore through the real repository setters, so the grid
 * behind this rebuilds on its own - `playbackQualityOptions` is remembered on the selection
 * context. Nothing here holds a second copy of the state.
 */
@Composable
fun PlaybackPreferencesDialog(
    languageStrictness: LanguageStrictness,
    dynamicRangePolicy: DynamicRangePolicy,
    qualityCeilingMbps: Int,
    onLanguageStrictnessChange: (LanguageStrictness) -> Unit,
    onDynamicRangePolicyChange: (DynamicRangePolicy) -> Unit,
    onQualityCeilingChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.playback_preferences_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
                Text(
                    text = stringResource(Res.string.playback_preferences_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textSecondary,
                )
                CyclingPreferenceRow(
                    title = stringResource(Res.string.settings_playback_language_strictness),
                    value = languageStrictnessLabel(languageStrictness),
                    onClick = { onLanguageStrictnessChange(languageStrictness.next()) },
                )
                CyclingPreferenceRow(
                    title = stringResource(Res.string.settings_playback_dynamic_range),
                    value = dynamicRangeLabel(dynamicRangePolicy),
                    onClick = { onDynamicRangePolicyChange(dynamicRangePolicy.next()) },
                )
                CyclingPreferenceRow(
                    title = stringResource(Res.string.settings_playback_quality_ceiling),
                    value = qualityCeilingLabel(qualityCeilingMbps),
                    onClick = { onQualityCeilingChange(nextQualityCeiling(qualityCeilingMbps)) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.playback_preferences_done))
            }
        },
    )
}

@Composable
private fun CyclingPreferenceRow(title: String, value: String, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(tokens.shapes.compactCard)
            .background(tokens.colors.overlayHover)
            .clickable(onClick = onClick)
            .padding(tokens.spacing.cardPaddingCompact),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = tokens.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.accent,
        )
    }
}

/**
 * The ceiling's steps, named by what each admits rather than by its bitrate.
 *
 * Duplicated from `PlaybackSettingsPage` deliberately rather than shared: that file is in a
 * different feature package and importing across for a five-element list would tie the settings
 * screen's layout to the player's. If a third caller appears, move it to this file and let the
 * settings page import it.
 */
private val QUALITY_CEILING_STEPS = listOf(0, 10, 20, 35, 60)

private fun nextQualityCeiling(current: Int): Int {
    val index = QUALITY_CEILING_STEPS.indexOfFirst { it >= current }
    return QUALITY_CEILING_STEPS[(if (index < 0) 0 else index + 1) % QUALITY_CEILING_STEPS.size]
}

private fun LanguageStrictness.next(): LanguageStrictness =
    LanguageStrictness.entries[(ordinal + 1) % LanguageStrictness.entries.size]

private fun DynamicRangePolicy.next(): DynamicRangePolicy =
    DynamicRangePolicy.entries[(ordinal + 1) % DynamicRangePolicy.entries.size]

@Composable
private fun languageStrictnessLabel(strictness: LanguageStrictness): String = when (strictness) {
    LanguageStrictness.OFF -> stringResource(Res.string.settings_playback_language_off)
    LanguageStrictness.PREFER -> stringResource(Res.string.settings_playback_language_prefer)
    LanguageStrictness.REQUIRE -> stringResource(Res.string.settings_playback_language_require)
}

@Composable
private fun dynamicRangeLabel(policy: DynamicRangePolicy): String = when (policy) {
    DynamicRangePolicy.ANY -> stringResource(Res.string.settings_playback_preference_any)
    DynamicRangePolicy.AVOID_HDR -> stringResource(Res.string.settings_playback_dynamic_range_avoid)
    DynamicRangePolicy.PREFER_HDR -> stringResource(Res.string.settings_playback_dynamic_range_prefer)
    DynamicRangePolicy.REQUIRE_HDR -> stringResource(Res.string.settings_playback_dynamic_range_require)
    DynamicRangePolicy.REQUIRE_DOLBY_VISION ->
        stringResource(Res.string.settings_playback_dynamic_range_require_dv)
}

@Composable
private fun qualityCeilingLabel(mbps: Int): String = when {
    mbps <= 0 -> stringResource(Res.string.settings_playback_quality_ceiling_off)
    else -> stringResource(Res.string.settings_playback_quality_ceiling_value, mbps)
}
