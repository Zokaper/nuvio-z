package com.nuvio.app.features.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.details.MetaDetails
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.download_batch_mobile_data
import nuvio.composeapp.generated.resources.download_batch_finding_sources_started
import nuvio.composeapp.generated.resources.download_batch_select_seasons
import nuvio.composeapp.generated.resources.download_preset_title
import org.jetbrains.compose.resources.stringResource

/**
 * Picks the scope and the preset, then hands the work to the coordinator.
 *
 * Review is not shown here any more: discovery is backgrounded, and everything it
 * produces - progress, approvals, manual source picks - is presented in the Downloads
 * tab instead, which survives the user navigating away.
 */
@Composable
fun PresetDownloadDialog(
    meta: MetaDetails,
    initialScope: DownloadScope,
    currentSeason: Int?,
    onDismiss: () -> Unit,
) {
    val availableSeasons = remember(meta.videos) {
        meta.videos.mapNotNull { it.season }.toSortedSet()
    }
    var selectedSeasons by remember(meta.id, initialScope) {
        mutableStateOf(
            if (initialScope is DownloadScope.SelectedSeasons) {
                DownloadBatchPlanner.defaultSelectedSeasons(currentSeason, availableSeasons)
            } else {
                emptySet()
            },
        )
    }
    var allowMetered by remember(meta.id, initialScope) { mutableStateOf(false) }
    val findingSourcesMessage = stringResource(Res.string.download_batch_finding_sources_started)
    val presets by DownloadsRepository.presets.collectAsStateWithLifecycle()

    fun prepare(preset: DownloadPreset) {
        val scope = when (initialScope) {
            is DownloadScope.SelectedSeasons -> DownloadScope.SelectedSeasons(selectedSeasons)
            else -> initialScope
        }
        // Discovery already runs on the coordinator's own scope, persists the batch
        // before it starts, and auto-queues itself when nothing needs review. There
        // is nothing to wait here for, and waiting is what used to trap the user on
        // a spinner and cancel the work if they navigated away.
        PresetDownloadCoordinator.start(
            meta = meta,
            scope = scope,
            preset = preset,
            allowMeteredNetwork = allowMetered,
        )
        NuvioToastController.show(findingSourcesMessage)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.download_preset_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (initialScope is DownloadScope.SelectedSeasons) {
                    Text(
                        stringResource(Res.string.download_batch_select_seasons),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    availableSeasons.forEach { season ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedSeasons = if (season in selectedSeasons) {
                                        selectedSeasons - season
                                    } else {
                                        selectedSeasons + season
                                    }
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = season in selectedSeasons,
                                onCheckedChange = { checked ->
                                    selectedSeasons = if (checked) {
                                        selectedSeasons + season
                                    } else {
                                        selectedSeasons - season
                                    }
                                },
                            )
                            Text(if (season == 0) "Specials" else "Season $season")
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(Res.string.download_batch_mobile_data))
                    Switch(checked = allowMetered, onCheckedChange = { allowMetered = it })
                }
                presets.forEach { preset ->
                    OutlinedButton(
                        onClick = { prepare(preset) },
                        enabled = initialScope !is DownloadScope.SelectedSeasons || selectedSeasons.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(preset.name)
                            Text(
                                "${preset.targetResolution.height}p • ${preset.gigabytesPerHourLimit} GB/hour",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}
