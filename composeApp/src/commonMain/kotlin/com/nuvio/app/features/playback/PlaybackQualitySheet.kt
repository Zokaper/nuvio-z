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
import nuvio.composeapp.generated.resources.playback_quality_summary
import nuvio.composeapp.generated.resources.playback_quality_title
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackQualitySheet(
    tiers: List<PlaybackQualityTier>,
    isLoading: Boolean,
    onTierSelected: (PlaybackQualityTier?) -> Unit,
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
                QualityRow(
                    title = stringResource(Res.string.playback_quality_best),
                    summary = stringResource(Res.string.playback_quality_description),
                    enabled = !isLoading,
                    onClick = { onTierSelected(null) },
                )
                tiers.sortedByDescending { it.targetResolution.height }.forEach { tier ->
                    QualityRow(
                        title = tier.name,
                        summary = stringResource(
                            Res.string.playback_quality_summary,
                            tier.targetResolution.height,
                            tier.megabitsPerSecond.roundToInt(),
                        ),
                        enabled = !isLoading,
                        onClick = { onTierSelected(tier) },
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
