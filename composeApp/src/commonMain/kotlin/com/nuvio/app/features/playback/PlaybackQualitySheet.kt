package com.nuvio.app.features.playback

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.updater.formatFileSize
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.playback_quality_best
import nuvio.composeapp.generated.resources.playback_quality_description
import nuvio.composeapp.generated.resources.playback_quality_loading
import nuvio.composeapp.generated.resources.playback_quality_manual
import nuvio.composeapp.generated.resources.playback_quality_needs
import nuvio.composeapp.generated.resources.playback_quality_needs_estimated
import nuvio.composeapp.generated.resources.playback_quality_over_connection
import nuvio.composeapp.generated.resources.playback_quality_summary_with_size
import nuvio.composeapp.generated.resources.playback_quality_title
import nuvio.composeapp.generated.resources.playback_quality_variant_high
import nuvio.composeapp.generated.resources.playback_quality_variant_low
import nuvio.composeapp.generated.resources.playback_quality_variant_mid
import org.jetbrains.compose.resources.stringResource
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Streamlined's quality picker.
 *
 * Every row here came from a source that exists for this title, so the list is different on
 * every title and a quality nobody released simply has no row. The bandwidth figure is the
 * one the chosen file actually needs, not a preset's nominal number.
 *
 * A thin renderer over pure functions, deliberately: the resolution badge, the tier word, the
 * required speed and the provider line all come from [PlaybackQualityOptions] and
 * [PlaybackSourceSelector], which are testable outside Compose. The same reasoning
 * `PlaybackProgress.step`/`isVisible` are built on.
 *
 * [estimatedMbps] is what the connection is currently thought to carry, and is used only to
 * mark a row as a stretch. It never disables one: the estimate is a guess, and the user may
 * know their line better than the app does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackQualitySheet(
    options: List<PlaybackQualityOption>,
    isLoading: Boolean,
    selectionContext: PlaybackSelectionContext,
    estimatedMbps: Double?,
    onOptionSelected: (PlaybackQualityOption) -> Unit,
    onChooseManually: () -> Unit,
    onDismiss: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.playback_quality_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (isLoading) {
                        stringResource(Res.string.playback_quality_loading)
                    } else {
                        stringResource(Res.string.playback_quality_description)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // A three-way split across several resolutions is more rows than a
                // fixed-height dialog holds, and a row the user cannot reach is a row that
                // does not exist.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    options.forEach { option ->
                        QualityRow(
                            badge = option.resolutionLabel.ifBlank { "★" },
                            title = variantLabel(option),
                            summary = optionSummary(option),
                            source = sourceLine(option, selectionContext),
                            isOverConnection = isOverConnection(option, estimatedMbps),
                            enabled = !isLoading,
                            onClick = { onOptionSelected(option) },
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onChooseManually) {
                        Text(stringResource(Res.string.playback_quality_manual))
                    }
                }
            }
        }
    }
}

/**
 * The row's title, given that the resolution is already in the badge beside it.
 *
 * "4K" belongs in the badge; "High" is a comparison with the rows around it. A resolution
 * with a single row has nothing to compare against, so it says nothing rather than repeating
 * the badge back to the user - the badge alone already names it.
 */
@Composable
private fun variantLabel(option: PlaybackQualityOption): String = when (option.variant) {
    PlaybackQualityOption.Variant.BEST -> stringResource(Res.string.playback_quality_best)
    PlaybackQualityOption.Variant.HIGH -> stringResource(Res.string.playback_quality_variant_high)
    PlaybackQualityOption.Variant.MID -> stringResource(Res.string.playback_quality_variant_mid)
    PlaybackQualityOption.Variant.LOW -> stringResource(Res.string.playback_quality_variant_low)
    PlaybackQualityOption.Variant.SINGLE -> ""
}

/** True when the row asks for more than the connection is currently thought to carry. */
private fun isOverConnection(option: PlaybackQualityOption, estimatedMbps: Double?): Boolean {
    val required = option.requiredMbps ?: return false
    val estimate = estimatedMbps?.takeIf { it > 0.0 } ?: return false
    return required > estimate
}

/**
 * `WEB-DL · TorBox` for the source this row would really open.
 *
 * Not `option.candidates.first()`: the protocol and cache gates can skip several candidates
 * before landing on one, and naming a release the user never receives is the same class of
 * untruth as quoting a season pack's bandwidth for a row.
 */
private fun sourceLine(
    option: PlaybackQualityOption,
    context: PlaybackSelectionContext,
): String = PlaybackSourceSelector.previewSelection(option, context)
    ?.let { PlaybackSourceSelector.describeRelease(it.facts) }
    .orEmpty()

@Composable
private fun optionSummary(option: PlaybackQualityOption): String {
    val required = option.requiredMbps
        ?: return stringResource(Res.string.playback_quality_description)
    // Rounded up: quoting 4 Mb/s for something that needs 4.6 is the one direction that
    // turns an informed choice into a stall.
    val speed = stringResource(
        if (option.isEstimateApproximate) {
            Res.string.playback_quality_needs_estimated
        } else {
            Res.string.playback_quality_needs
        },
        ceil(required).roundToInt(),
    )
    val size = option.representativeSizeBytes?.let(::formatFileSize) ?: return speed
    return stringResource(Res.string.playback_quality_summary_with_size, speed, size)
}

@Composable
private fun QualityRow(
    badge: String,
    title: String,
    summary: String,
    source: String,
    isOverConnection: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.5f else 0.25f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
            ) {
                Text(
                    text = badge,
                    modifier = Modifier
                        .widthIn(min = 52.dp)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (title.isNotBlank()) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                }
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (source.isNotBlank()) {
                    Text(
                        source,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isOverConnection) {
                    // Said, not enforced. The estimate is a guess; the user may know better
                    // than the app does, and a row they cannot pick is worse than a warning.
                    Text(
                        stringResource(Res.string.playback_quality_over_connection),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
