package com.nuvio.app.features.playback

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.playback_quality_best
import nuvio.composeapp.generated.resources.playback_quality_description
import nuvio.composeapp.generated.resources.playback_quality_loading
import nuvio.composeapp.generated.resources.playback_quality_manual
import nuvio.composeapp.generated.resources.playback_quality_needs
import nuvio.composeapp.generated.resources.playback_quality_needs_estimated
import nuvio.composeapp.generated.resources.playback_quality_summary_with_size
import nuvio.composeapp.generated.resources.playback_quality_title
import nuvio.composeapp.generated.resources.playback_quality_variant_high
import nuvio.composeapp.generated.resources.playback_quality_variant_low
import com.nuvio.app.features.updater.formatFileSize
import org.jetbrains.compose.resources.stringResource
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Streamlined's quality picker.
 *
 * Every row here came from a source that exists for this title, so the list is different on
 * every title and a quality nobody released simply has no row. The bandwidth figure is the
 * one the chosen file actually needs, not a preset's nominal number.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackQualitySheet(
    options: List<PlaybackQualityOption>,
    isLoading: Boolean,
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
                options.forEach { option ->
                    QualityRow(
                        title = optionTitle(option),
                        summary = optionSummary(option),
                        enabled = !isLoading,
                        onClick = { onOptionSelected(option) },
                    )
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

@Composable
private fun optionTitle(option: PlaybackQualityOption): String = when (option.variant) {
    PlaybackQualityOption.Variant.BEST -> stringResource(Res.string.playback_quality_best)
    PlaybackQualityOption.Variant.HIGH ->
        "${option.resolutionLabel} ${stringResource(Res.string.playback_quality_variant_high)}"
    PlaybackQualityOption.Variant.LOW ->
        "${option.resolutionLabel} ${stringResource(Res.string.playback_quality_variant_low)}"
    PlaybackQualityOption.Variant.SINGLE -> option.resolutionLabel
}

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
    title: String,
    summary: String,
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
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
