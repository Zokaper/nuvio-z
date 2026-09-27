package com.nuvio.app.features.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioChoiceCard
import com.nuvio.app.core.ui.NuvioChoiceList
import com.nuvio.app.core.ui.NuvioChoiceRow
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The Download Mode and Download Preferences, described once (Phase 9 stage 8). The wizard's two
 * download steps and Settings -> Downloads both read these, so the words for a mode or a level
 * cannot drift between them - the rule `PlaybackModeCard` exists for. The visuals are the shared
 * choice controls (`core/ui/NuvioChoiceControls.kt`), the same ones the playback steps use.
 */

/** Automatic, Assisted, Manual: the order the wizard and Settings show them in. */
val DownloadModeOrder: List<DownloadMode> = listOf(DownloadMode.AUTOMATIC, DownloadMode.ASSISTED, DownloadMode.MANUAL)

@Composable
fun downloadModeName(mode: DownloadMode): String = when (mode) {
    DownloadMode.AUTOMATIC -> stringResource(Res.string.download_mode_automatic)
    DownloadMode.ASSISTED -> stringResource(Res.string.download_mode_assisted)
    DownloadMode.MANUAL -> stringResource(Res.string.download_mode_manual)
}

@Composable
private fun downloadModeTagline(mode: DownloadMode): String = when (mode) {
    DownloadMode.AUTOMATIC -> stringResource(Res.string.download_mode_automatic_tagline)
    DownloadMode.ASSISTED -> stringResource(Res.string.download_mode_assisted_tagline)
    DownloadMode.MANUAL -> stringResource(Res.string.download_mode_manual_tagline)
}

@Composable
private fun downloadModeDetail(mode: DownloadMode): String = when (mode) {
    DownloadMode.AUTOMATIC -> stringResource(Res.string.download_mode_automatic_detail)
    DownloadMode.ASSISTED -> stringResource(Res.string.download_mode_assisted_detail)
    DownloadMode.MANUAL -> stringResource(Res.string.download_mode_manual_detail)
}

/**
 * The mode the maintainer recommends to users: Assisted (wizard polish, 2026-09-27 - it was
 * Automatic, the plan's first call). Only the badge moved; the preselected mode is still the one
 * Playback Mode implies (`DownloadPolicy.derivedMode`).
 */
val RecommendedDownloadMode: DownloadMode = DownloadMode.ASSISTED

/** One mode as a selectable card: radio, name, and what it does in plain words. */
@Composable
fun DownloadModeCard(
    mode: DownloadMode,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NuvioChoiceCard(
        title = downloadModeName(mode),
        selected = isSelected,
        onClick = onClick,
        modifier = modifier,
        tagline = downloadModeTagline(mode),
        description = downloadModeDetail(mode),
        badge = if (mode == RecommendedDownloadMode) stringResource(Res.string.download_mode_recommended) else null,
    )
}

// --- preference labels -------------------------------------------------------------------------

@Composable
fun downloadResolutionLabel(preference: DownloadResolutionPreference): String = when (preference) {
    DownloadResolutionPreference.BEST_AVAILABLE -> stringResource(Res.string.download_pref_best_available)
    else -> DownloadFlowRules.resolutionLabel(preference.height)
}

@Composable
fun downloadSizeLevelLabel(level: DownloadSizeLevel): String = when (level) {
    DownloadSizeLevel.SMALL -> stringResource(Res.string.download_size_small)
    DownloadSizeLevel.MEDIUM -> stringResource(Res.string.download_size_medium)
    DownloadSizeLevel.LARGE -> stringResource(Res.string.download_size_large)
    DownloadSizeLevel.HUGE -> stringResource(Res.string.download_size_huge)
    DownloadSizeLevel.ANY -> stringResource(Res.string.download_size_any)
}

/** The resolution a level is described at: Best available (and Assisted, which asks per download) at 1080p. */
private fun describedHeight(resolution: DownloadResolutionPreference): Int =
    if (resolution == DownloadResolutionPreference.BEST_AVAILABLE) {
        DownloadResolutionPreference.P1080.height
    } else {
        resolution.height
    }

/**
 * A gigabyte figure as a person reads it: one decimal below 10 ("0.4", "1.5", "3"), whole numbers
 * above ("12"). Never "3.0" and never "0".
 */
internal fun downloadSizeFigure(gigabytes: Double): String {
    if (gigabytes >= 10.0) return kotlin.math.round(gigabytes).toLong().toString()
    val tenths = kotlin.math.round(gigabytes * 10.0).toLong().coerceAtLeast(1L)
    return if (tenths % 10L == 0L) (tenths / 10L).toString() else "${tenths / 10L}.${tenths % 10L}"
}

/** The two resolutions every level is quoted at, whatever is chosen: the ones people pick between. */
private val QUOTED_HEIGHTS = listOf(DownloadResolutionPreference.P1080.height, DownloadResolutionPreference.P2160.height)

/**
 * A level's own numbers, shown beside every option so none has to be tapped to be read:
 * ["1080p · 3 GB/h", "4K · 8 GB/h"], or ["No limit"] for Any size.
 */
@Composable
fun downloadSizeLevelFigures(level: DownloadSizeLevel): List<String> {
    if (level == DownloadSizeLevel.ANY) return listOf(stringResource(Res.string.download_size_no_limit))
    return QUOTED_HEIGHTS.map { height ->
        stringResource(
            Res.string.download_size_at_resolution,
            DownloadFlowRules.resolutionLabel(height),
            downloadSizeFigure(DownloadSizeLevels.gigabytesPerHour(level, height)!!),
        )
    }
}

/** What a level is for, in a few words - shown under its name so no level needs a tap to be understood. */
@Composable
fun downloadSizeLevelTagline(level: DownloadSizeLevel): String = when (level) {
    DownloadSizeLevel.SMALL -> stringResource(Res.string.download_size_small_tagline)
    DownloadSizeLevel.MEDIUM -> stringResource(Res.string.download_size_medium_tagline)
    DownloadSizeLevel.LARGE -> stringResource(Res.string.download_size_large_tagline)
    DownloadSizeLevel.HUGE -> stringResource(Res.string.download_size_huge_tagline)
    DownloadSizeLevel.ANY -> stringResource(Res.string.download_size_any_tagline)
}

/** The level people should leave it on. */
val RecommendedDownloadSizeLevel: DownloadSizeLevel = DownloadSizeLevel.MEDIUM

/**
 * A level's GB per hour as (resolution, figure) pairs - [("1080p", "3 GB/h"), ("4K", "8 GB/h")] -
 * for a two-column figure block; empty for Any size, which has none.
 */
@Composable
fun downloadSizeLevelRates(level: DownloadSizeLevel): List<Pair<String, String>> {
    if (level == DownloadSizeLevel.ANY) return emptyList()
    return QUOTED_HEIGHTS.map { height ->
        DownloadFlowRules.resolutionLabel(height) to stringResource(
            Res.string.download_size_per_hour,
            downloadSizeFigure(DownloadSizeLevels.gigabytesPerHour(level, height)!!),
        )
    }
}

/**
 * Every size level as a radio list, each row carrying its name, what it is for and its GB per hour
 * at 1080p and 4K (wizard polish: the pill grid plus one caption that changed with the selection
 * made every level a tap to understand). GB/hour is the one unit - an "episode" is 20 minutes or
 * 70, so it cannot be the headline figure.
 */
@Composable
fun DownloadSizeLevelList(
    selected: DownloadSizeLevel,
    onSelected: (DownloadSizeLevel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    NuvioChoiceList(modifier) {
        DownloadSizeLevel.entries.forEachIndexed { index, level ->
            val rates = downloadSizeLevelRates(level)
            NuvioChoiceRow(
                title = downloadSizeLevelLabel(level),
                selected = level == selected,
                onClick = { onSelected(level) },
                description = downloadSizeLevelTagline(level),
                badge = if (level == RecommendedDownloadSizeLevel) stringResource(Res.string.download_mode_recommended) else null,
                showDivider = index > 0,
                trailing = {
                    if (rates.isEmpty()) {
                        Text(
                            text = stringResource(Res.string.download_size_no_limit),
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.textSecondary,
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            rates.forEachIndexed { rateIndex, (resolution, figure) ->
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = resolution,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.textMuted,
                                    )
                                    Text(
                                        text = figure,
                                        style = if (rateIndex == 0) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
                                        color = if (rateIndex == 0) colors.textPrimary else colors.textSecondary,
                                        fontWeight = if (rateIndex == 0) FontWeight.SemiBold else FontWeight.Normal,
                                        textAlign = TextAlign.End,
                                        modifier = Modifier.widthIn(min = 52.dp),
                                    )
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}

/** "Standard · 1080p · 3 GB/h, 4K · 8 GB/h" - the option label where a list has one line (Settings' dropdown). */
@Composable
fun downloadSizeLevelOption(level: DownloadSizeLevel): String =
    stringResource(Res.string.download_size_level_option, downloadSizeLevelLabel(level), downloadSizeLevelFigures(level).joinToString(", "))

/**
 * The selected level in one line for Settings: "Typical streaming quality · 3 GB/h at 1080p".
 * GB per hour, like the wizard - an "episode" is 20 minutes or 70, so it is never the headline.
 */
@Composable
fun downloadSizeLevelDetail(level: DownloadSizeLevel, resolution: DownloadResolutionPreference): String {
    val height = describedHeight(resolution)
    val perHour = DownloadSizeLevels.gigabytesPerHour(level, height)
        ?: return stringResource(Res.string.download_size_level_detail_any)
    return stringResource(
        Res.string.download_size_level_summary,
        downloadSizeLevelTagline(level),
        downloadSizeFigure(perHour),
        DownloadFlowRules.resolutionLabel(height),
    )
}

@Composable
fun downloadFallbackLabel(fallback: DownloadResolutionFallback): String = when (fallback) {
    DownloadResolutionFallback.LOWER -> stringResource(Res.string.download_fallback_lower)
    DownloadResolutionFallback.HIGHER -> stringResource(Res.string.download_fallback_higher)
    DownloadResolutionFallback.ASK -> stringResource(Res.string.download_fallback_ask)
}

@Composable
fun downloadPickRuleLabel(rule: DownloadPickRule): String = when (rule) {
    DownloadPickRule.BEST_THAT_FITS -> stringResource(Res.string.download_pick_best)
    DownloadPickRule.BALANCED -> stringResource(Res.string.download_pick_balanced)
    DownloadPickRule.SMALLEST_THAT_FITS -> stringResource(Res.string.download_pick_smallest)
}

@Composable
fun downloadRangeLabel(range: DownloadRange): String = when (range) {
    DownloadRange.SAME_AS_PLAYBACK -> stringResource(Res.string.download_range_same)
    DownloadRange.PREFER_HDR -> stringResource(Res.string.download_range_prefer)
    DownloadRange.AVOID_HDR -> stringResource(Res.string.download_range_avoid)
    DownloadRange.ANY -> stringResource(Res.string.download_range_any)
}

@Composable
fun downloadMobileDataLabel(rule: DownloadMobileDataRule): String = when (rule) {
    DownloadMobileDataRule.WIFI_ONLY -> stringResource(Res.string.download_mobile_wifi_only)
    DownloadMobileDataRule.ASK -> stringResource(Res.string.download_mobile_ask)
    DownloadMobileDataRule.ALWAYS -> stringResource(Res.string.download_mobile_always)
}
