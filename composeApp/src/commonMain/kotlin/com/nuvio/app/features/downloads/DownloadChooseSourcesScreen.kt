package com.nuvio.app.features.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.download_choose_sources_all_picked
import nuvio.composeapp.generated.resources.download_choose_sources_left
import nuvio.composeapp.generated.resources.download_choose_sources_pick
import nuvio.composeapp.generated.resources.download_choose_sources_rest
import nuvio.composeapp.generated.resources.download_choose_sources_title
import org.jetbrains.compose.resources.stringResource

/**
 * Manual, several episodes (Phase 9): every episode with a Pick button, and "Let Nuvio pick the
 * rest". The screen reads the batch live, so an episode picked in the download source list shows
 * as downloading when the user comes back.
 */
@Composable
fun DownloadChooseSourcesScreen(
    batchId: String,
    onBack: () -> Unit,
) {
    val batches by remember {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.batches
    }.collectAsStateWithLifecycle()
    val batch = batches.firstOrNull { it.id == batchId }
    val listState = rememberLazyListState()
    NuvioScreen(listState = listState) {
        stickyHeader {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                NuvioScreenHeader(
                    title = stringResource(Res.string.download_choose_sources_title),
                    onBack = onBack,
                )
            }
        }
        if (batch != null) {
            item(key = "choose_sources_summary") {
                ChooseSourcesSummary(
                    batch = batch,
                    onPickTheRest = { DownloadFlowController.pickTheRest(batch) },
                )
            }
            items(batch.entries, key = { it.id }) { entry ->
                ChooseSourcesRow(
                    entry = entry,
                    onPick = { DownloadFlowController.chooseEntryManually(batch, entry) },
                )
            }
        }
    }
}

@Composable
fun ChooseSourcesSummary(batch: DownloadBatch, onPickTheRest: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val left = batch.entries.count { it.isAwaitingPick }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.spacing.screenHorizontal, vertical = NuvioTokens.Space.s8),
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
    ) {
        Text(
            text = batch.title,
            style = MaterialTheme.typography.titleMedium,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = if (left == 0) {
                stringResource(Res.string.download_choose_sources_all_picked)
            } else {
                stringResource(Res.string.download_choose_sources_left, left, batch.entries.size)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textMuted,
        )
        if (left > 0) {
            NuvioPrimaryButton(
                text = stringResource(Res.string.download_choose_sources_rest),
                modifier = Modifier.fillMaxWidth(),
                onClick = onPickTheRest,
            )
        }
    }
}

@Composable
fun ChooseSourcesRow(entry: DownloadBatchEntry, onPick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.spacing.screenHorizontal, vertical = NuvioTokens.Space.s4),
        shape = tokens.shapes.compactCard,
        color = tokens.colors.surfaceCard,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = NuvioTokens.Space.s14, vertical = NuvioTokens.Space.s10),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.episodeLabel(),
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.colors.textMuted,
                )
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = tokens.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                entry.pickedSummary()?.let { summary ->
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (entry.isAwaitingPick) {
                TextButton(onClick = onPick) {
                    Text(
                        text = stringResource(Res.string.download_choose_sources_pick),
                        color = tokens.colors.accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/** Still waiting for the user's pick (or for "Let Nuvio pick the rest"). */
internal val DownloadBatchEntry.isAwaitingPick: Boolean
    get() = state == DownloadBatchEntryState.SKIPPED && decision == DownloadEntryDecisionKind.MANUAL_PICK

private fun DownloadBatchEntry.episodeLabel(): String =
    if (season != null && episode != null) "S$season · E$episode" else ""

private fun DownloadBatchEntry.pickedSummary(): String? = when {
    isAwaitingPick -> null
    state == DownloadBatchEntryState.DISCOVERING -> "…"
    else -> listOfNotNull(
        (selection as? SourceSelectionResult.Selected)?.facts?.resolution?.height?.let(DownloadFlowRules::resolutionLabel),
        (selection as? SourceSelectionResult.Selected)?.facts?.sizeBytes?.let(DownloadFlowRules::sizeLabel),
        streamTitle?.takeIf { it.isNotBlank() },
        failureMessage?.takeIf { state == DownloadBatchEntryState.SKIPPED || state == DownloadBatchEntryState.FAILED },
    ).joinToString(" · ").ifBlank { state.name.lowercase().replaceFirstChar { it.uppercase() } }
}
