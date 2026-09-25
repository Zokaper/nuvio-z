package com.nuvio.app.features.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.download_batch_scope_season
import nuvio.composeapp.generated.resources.download_choose_sources_all_picked
import nuvio.composeapp.generated.resources.download_choose_sources_left
import nuvio.composeapp.generated.resources.download_choose_sources_needs_pick
import nuvio.composeapp.generated.resources.download_choose_sources_rest
import nuvio.composeapp.generated.resources.download_choose_sources_scope
import nuvio.composeapp.generated.resources.download_choose_sources_title
import nuvio.composeapp.generated.resources.download_episode_short
import nuvio.composeapp.generated.resources.download_flow_episode_count
import nuvio.composeapp.generated.resources.download_flow_finding_source
import org.jetbrains.compose.resources.stringResource

/**
 * Manual, several episodes (Phase 9): every episode, and "Auto-pick remaining". The screen
 * reads the batch live, so an episode picked in the download source list shows as chosen when the
 * user comes back.
 *
 * Composition: the title's poster and progress on top, then one list in which the three states
 * look different at a glance - a tick for chosen, a spinner for finding, an open ring and a chevron
 * for "still needs a pick" - instead of a Pick label repeated down the screen.
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
                    modifier = Modifier.downloadsContentWidth(ChooseSourcesMaxWidth),
                    title = stringResource(Res.string.download_choose_sources_title),
                    onBack = onBack,
                )
            }
        }
        if (batch != null) {
            item(key = "choose_sources") {
                ChooseSourcesContent(
                    batch = batch,
                    onPickTheRest = { DownloadFlowController.pickTheRest(batch) },
                    onPick = { entry -> DownloadFlowController.chooseEntryManually(batch, entry) },
                    modifier = Modifier.downloadsContentWidth(ChooseSourcesMaxWidth),
                )
            }
        }
    }
}

private val ChooseSourcesMaxWidth = 720.dp

@Composable
fun ChooseSourcesContent(
    batch: DownloadBatch,
    onPickTheRest: () -> Unit,
    onPick: (DownloadBatchEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Column(modifier, verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s16)) {
        ChooseSourcesSummary(batch, onPickTheRest)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = tokens.colors.surfaceCard,
        ) {
            Column(Modifier.fillMaxWidth()) {
                batch.entries.forEachIndexed { index, entry ->
                    if (index > 0) HorizontalDivider(Modifier.padding(start = 56.dp), color = tokens.colors.borderSubtle)
                    ChooseSourcesRow(entry, onPick = { onPick(entry) })
                }
            }
        }
    }
}

@Composable
fun ChooseSourcesSummary(batch: DownloadBatch, onPickTheRest: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val total = batch.entries.size
    val left = batch.entries.count { it.isAwaitingPick }
    val ready = batch.entries.count { !it.isAwaitingPick && it.state != DownloadBatchEntryState.DISCOVERING }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 560.dp
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s14)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s16),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DownloadPoster(url = batch.poster ?: batch.background, title = batch.title, width = if (wide) 72.dp else 60.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4)) {
                    Text(
                        text = batch.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = tokens.colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = scopeLine(batch),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textSecondary,
                    )
                    Text(
                        text = if (left == 0) {
                            stringResource(Res.string.download_choose_sources_all_picked)
                        } else {
                            stringResource(Res.string.download_choose_sources_left, left, total)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.colors.textMuted,
                    )
                    if (total > 0) {
                        ThinProgress(ready.toFloat() / total, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                // Tonal, not filled: in Manual the episode list is the point, and auto-picking the
                // rest is the way out of it, not the thing the screen is for.
                if (wide && left > 0) {
                    DownloadsTonalButton(
                        text = stringResource(Res.string.download_choose_sources_rest),
                        onClick = onPickTheRest,
                    )
                }
            }
            if (!wide && left > 0) {
                DownloadsTonalButton(
                    text = stringResource(Res.string.download_choose_sources_rest),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onPickTheRest,
                )
            }
        }
    }
}

@Composable
private fun scopeLine(batch: DownloadBatch): String {
    val count = batch.entries.size
    return when (val scope = batch.scope) {
        is DownloadScope.Season -> stringResource(
            Res.string.download_choose_sources_scope,
            stringResource(Res.string.download_batch_scope_season, scope.season),
            count,
        )
        else -> stringResource(Res.string.download_flow_episode_count, count)
    }
}

@Composable
fun ChooseSourcesRow(entry: DownloadBatchEntry, onPick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val awaiting = entry.isAwaitingPick
    val finding = entry.state == DownloadBatchEntryState.DISCOVERING
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = awaiting, onClick = onPick)
            .padding(horizontal = NuvioTokens.Space.s14, vertical = NuvioTokens.Space.s10),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
    ) {
        StateMark(awaiting = awaiting, finding = finding)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                entry.episode?.let {
                    Text(
                        text = stringResource(Res.string.download_episode_short, it),
                        modifier = Modifier.width(34.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = tokens.colors.textMuted,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = tokens.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = if (awaiting) {
                    stringResource(Res.string.download_choose_sources_needs_pick)
                } else {
                    entry.pickedSummary(findingText = stringResource(Res.string.download_flow_finding_source)).orEmpty()
                },
                modifier = Modifier.padding(start = if (entry.episode != null) 34.dp else 0.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (awaiting) tokens.colors.textSecondary else tokens.colors.textMuted,
                fontWeight = if (awaiting) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (awaiting) {
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = tokens.colors.textMuted)
        }
    }
}

/** Chosen: a filled tick. Finding: a spinner. Still to pick: an open ring. */
@Composable
private fun StateMark(awaiting: Boolean, finding: Boolean) {
    val tokens = MaterialTheme.nuvio
    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        when {
            finding -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = tokens.colors.textSecondary,
                trackColor = tokens.colors.borderSubtle,
            )
            awaiting -> Box(
                Modifier
                    .size(20.dp)
                    .border(1.5.dp, tokens.colors.borderStrong, CircleShape),
            )
            else -> Box(
                Modifier.size(22.dp).clip(CircleShape).background(tokens.colors.success),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = tokens.colors.background, modifier = Modifier.size(15.dp))
            }
        }
    }
}

/** Still waiting for the user's pick (or for "Auto-pick remaining"). */
internal val DownloadBatchEntry.isAwaitingPick: Boolean
    get() = state == DownloadBatchEntryState.SKIPPED && decision == DownloadEntryDecisionKind.MANUAL_PICK

/**
 * `1080p · 2.3 GB · WEB-DL`: what the pick is, not the release's file name. The raw stream title
 * (`Shogun.S01E01.1080p.WEB...`) stays in the download's detail sheet.
 */
private fun DownloadBatchEntry.pickedSummary(findingText: String): String? = when {
    isAwaitingPick -> null
    state == DownloadBatchEntryState.DISCOVERING -> findingText
    else -> {
        val facts = (selection as? SourceSelectionResult.Selected)?.facts
        listOfNotNull(
            facts?.resolution?.height?.let(DownloadFlowRules::resolutionLabel),
            facts?.sizeBytes?.let(DownloadFlowRules::sizeLabel),
            facts?.releaseQuality?.takeIf { it.isNotBlank() },
            failureMessage?.takeIf { state == DownloadBatchEntryState.SKIPPED || state == DownloadBatchEntryState.FAILED },
        ).joinToString(" · ").ifBlank { state.name.lowercase().replaceFirstChar { it.uppercase() } }
    }
}
