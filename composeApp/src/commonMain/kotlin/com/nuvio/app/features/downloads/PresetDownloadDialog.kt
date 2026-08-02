package com.nuvio.app.features.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.details.MetaDetails
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.download_batch_approve_unknown
import nuvio.composeapp.generated.resources.download_batch_mobile_data
import nuvio.composeapp.generated.resources.download_batch_no_match
import nuvio.composeapp.generated.resources.download_batch_nothing_to_download
import nuvio.composeapp.generated.resources.download_batch_preparing
import nuvio.composeapp.generated.resources.download_batch_queue_ready
import nuvio.composeapp.generated.resources.download_batch_review
import nuvio.composeapp.generated.resources.download_batch_select_seasons
import nuvio.composeapp.generated.resources.download_choose_manual
import nuvio.composeapp.generated.resources.download_preset_title
import org.jetbrains.compose.resources.stringResource

@Composable
fun PresetDownloadDialog(
    meta: MetaDetails,
    initialScope: DownloadScope,
    currentSeason: Int?,
    onDismiss: () -> Unit,
    onQueued: (Int) -> Unit,
    onChooseManually: (DownloadBatchEntry) -> Unit,
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
    var preparing by remember(meta.id, initialScope) { mutableStateOf(false) }
    var batch by remember(meta.id, initialScope) { mutableStateOf<DownloadBatch?>(null) }
    var error by remember(meta.id, initialScope) { mutableStateOf<String?>(null) }
    var approveUnknown by remember(meta.id, initialScope) { mutableStateOf(false) }
    val nothingToDownloadMessage = stringResource(Res.string.download_batch_nothing_to_download)
    val coroutineScope = rememberCoroutineScope()
    val presets by DownloadsRepository.presets.collectAsStateWithLifecycle()

    fun prepare(preset: DownloadPreset) {
        val scope = when (initialScope) {
            is DownloadScope.SelectedSeasons -> DownloadScope.SelectedSeasons(selectedSeasons)
            else -> initialScope
        }
        preparing = true
        error = null
        coroutineScope.launch {
            runCatching {
                PresetDownloadCoordinator.start(
                    meta = meta,
                    scope = scope,
                    preset = preset,
                    allowMeteredNetwork = allowMetered,
                ).await()
            }.onSuccess { prepared ->
                preparing = false
                if (prepared.entries.isEmpty()) {
                    error = nothingToDownloadMessage
                } else if (!prepared.requiresReview(DownloadsPlatformDownloader.freeStorageBytes())) {
                    onQueued(
                        prepared.entries.count {
                            it.selection is SourceSelectionResult.Selected
                        },
                    )
                    onDismiss()
                } else {
                    batch = prepared
                }
            }.onFailure {
                preparing = false
                error = it.message ?: "Download preparation failed"
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!preparing) onDismiss() },
        title = {
            Text(
                if (batch == null) {
                    stringResource(Res.string.download_preset_title)
                } else {
                    stringResource(Res.string.download_batch_review)
                },
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    preparing -> {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator()
                            Text(stringResource(Res.string.download_batch_preparing))
                        }
                    }
                    batch != null -> {
                        val prepared = requireNotNull(batch)
                        prepared.entries.forEach { entry ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                            ) {
                                Text(entry.title, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    when (val result = entry.selection) {
                                        is SourceSelectionResult.Selected ->
                                            "${result.facts.resolution?.height?.let { "${it}p" } ?: "?"} • ${result.facts.sizeBytes.formatBytes()}"
                                        is SourceSelectionResult.ApprovalNeeded -> result.reason
                                        is SourceSelectionResult.NoMatch -> result.reason
                                        null -> entry.failureMessage.orEmpty()
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (entry.selection is SourceSelectionResult.NoMatch || entry.selection == null) {
                                    TextButton(onClick = { onChooseManually(entry) }) {
                                        Text(stringResource(Res.string.download_choose_manual))
                                    }
                                }
                            }
                        }
                        if (prepared.entries.any { it.selection is SourceSelectionResult.ApprovalNeeded }) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { approveUnknown = !approveUnknown },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = approveUnknown,
                                    onCheckedChange = { approveUnknown = it },
                                )
                                Text(stringResource(Res.string.download_batch_approve_unknown))
                            }
                        }
                    }
                    else -> {
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
                        error?.let {
                            Text(it, color = MaterialTheme.colorScheme.error)
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
                }
            }
        },
        confirmButton = {
            val prepared = batch
            if (prepared != null) {
                Button(
                    onClick = {
                        onQueued(DownloadsRepository.queueBatch(prepared.id, approveUnknown))
                        onDismiss()
                    },
                    enabled = prepared.entries.any {
                        it.selection is SourceSelectionResult.Selected ||
                            (approveUnknown && it.selection is SourceSelectionResult.ApprovalNeeded)
                    },
                ) {
                    Text(stringResource(Res.string.download_batch_queue_ready))
                }
            }
        },
        dismissButton = {
            if (!preparing) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(Res.string.action_cancel))
                }
            }
        },
    )
}

private fun Long?.formatBytes(): String {
    val bytes = this ?: return "Unknown size"
    val gb = bytes.toDouble() / 1_000_000_000.0
    return "${(gb * 10).toInt() / 10.0} GB"
}
