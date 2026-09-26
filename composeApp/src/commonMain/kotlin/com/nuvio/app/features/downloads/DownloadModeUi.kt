package com.nuvio.app.features.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The Download Mode and Download Preferences, described once (Phase 9 stage 8). The wizard's two
 * download steps and Settings -> Downloads both read these, so the words for a mode or a level
 * cannot drift between them - the rule `PlaybackModeCard` exists for.
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

/**
 * One mode as a selectable card: name, one line of what it does, and a tick when chosen.
 * Automatic carries "Recommended" - the plan's product call.
 */
@Composable
fun DownloadModeCard(
    mode: DownloadMode,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(
                if (isSelected) {
                    Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                } else {
                    Modifier
                },
            ),
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = downloadModeName(mode),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (mode == DownloadMode.AUTOMATIC) {
                        Text(
                            text = stringResource(Res.string.download_mode_recommended),
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Text(
                    text = downloadModeTagline(mode),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isSelected) {
                Box(
                    modifier = Modifier.size(22.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
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

/** "Standard · 1080p · 3 GB/h, 4K · 8 GB/h" - the option label where a list has one line (Settings' dropdown). */
@Composable
fun downloadSizeLevelOption(level: DownloadSizeLevel): String =
    stringResource(Res.string.download_size_level_option, downloadSizeLevelLabel(level), downloadSizeLevelFigures(level).joinToString(", "))

/**
 * The selected level at the scale people think in - a 20-minute episode, an hour-long one, a
 * 2-hour film - since episodes vary too much for one "per episode" figure: "At 1080p: about 1 GB
 * for a 20-minute episode, 3 GB for an hour-long one, 6 GB for a 2-hour film".
 */
@Composable
fun downloadSizeLevelDetail(level: DownloadSizeLevel, resolution: DownloadResolutionPreference): String {
    val height = describedHeight(resolution)
    val perHour = DownloadSizeLevels.gigabytesPerHour(level, height)
        ?: return stringResource(Res.string.download_size_level_detail_any)
    return stringResource(
        Res.string.download_size_level_detail,
        DownloadFlowRules.resolutionLabel(height),
        downloadSizeFigure(perHour / 3.0),
        downloadSizeFigure(perHour),
        downloadSizeFigure(perHour * 2.0),
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
