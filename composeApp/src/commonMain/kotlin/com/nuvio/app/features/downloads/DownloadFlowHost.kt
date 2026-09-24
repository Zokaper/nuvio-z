package com.nuvio.app.features.downloads

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioActionLabel
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.action_delete
import nuvio.composeapp.generated.resources.download_delete_confirm_body
import nuvio.composeapp.generated.resources.download_delete_confirm_title
import nuvio.composeapp.generated.resources.download_batch_scope_episode
import nuvio.composeapp.generated.resources.download_batch_scope_movie
import nuvio.composeapp.generated.resources.download_batch_scope_season
import nuvio.composeapp.generated.resources.download_batch_scope_season_unwatched
import nuvio.composeapp.generated.resources.download_batch_seasons_all
import nuvio.composeapp.generated.resources.download_batch_seasons_none
import nuvio.composeapp.generated.resources.download_flow_check_again
import nuvio.composeapp.generated.resources.download_flow_choose_manually
import nuvio.composeapp.generated.resources.download_flow_close
import nuvio.composeapp.generated.resources.download_flow_continue
import nuvio.composeapp.generated.resources.download_flow_download
import nuvio.composeapp.generated.resources.download_flow_episode_count
import nuvio.composeapp.generated.resources.download_flow_finding_progress
import nuvio.composeapp.generated.resources.download_flow_finding_title
import nuvio.composeapp.generated.resources.download_flow_mobile_body
import nuvio.composeapp.generated.resources.download_flow_mobile_title
import nuvio.composeapp.generated.resources.download_flow_mobile_use
import nuvio.composeapp.generated.resources.download_flow_mobile_wait
import nuvio.composeapp.generated.resources.download_flow_no_sources_body
import nuvio.composeapp.generated.resources.download_flow_no_sources_title
import nuvio.composeapp.generated.resources.download_flow_nothing_cached_body
import nuvio.composeapp.generated.resources.download_flow_nothing_cached_title
import nuvio.composeapp.generated.resources.download_flow_resolution_missing
import nuvio.composeapp.generated.resources.download_flow_resolution_over_limit
import nuvio.composeapp.generated.resources.download_flow_resolution_season_total
import nuvio.composeapp.generated.resources.download_flow_resolution_title
import nuvio.composeapp.generated.resources.download_flow_seasons_title
import nuvio.composeapp.generated.resources.download_flow_seasons_unwatched
import nuvio.composeapp.generated.resources.download_flow_size_unknown
import nuvio.composeapp.generated.resources.download_flow_space_body
import nuvio.composeapp.generated.resources.download_flow_space_fits
import nuvio.composeapp.generated.resources.download_flow_space_title
import nuvio.composeapp.generated.resources.download_flow_unwatched_only
import nuvio.composeapp.generated.resources.episodes_season
import nuvio.composeapp.generated.resources.episodes_specials
import org.jetbrains.compose.resources.stringResource

/**
 * Draws [DownloadFlowController.step]. Mounted once, at the app shell, so every entry point -
 * a title page, the Downloads screen, a toast - shows the same flow.
 */
@Composable
fun DownloadFlowHost() {
    val step by DownloadFlowController.step.collectAsStateWithLifecycle()
    when (val current = step) {
        DownloadFlowStep.Idle -> Unit
        is DownloadFlowStep.AskMobileData -> DownloadMobileDataDialog(
            onUseMobileData = { DownloadFlowController.answerMobileData(true) },
            onWait = { DownloadFlowController.answerMobileData(false) },
            onDismiss = DownloadFlowController::dismiss,
        )
        is DownloadFlowStep.ChooseSeasons -> DownloadSeasonChooserDialog(
            step = current,
            onChange = DownloadFlowController::updateSeasons,
            onContinue = DownloadFlowController::confirmSeasons,
            onDismiss = DownloadFlowController::dismiss,
        )
        is DownloadFlowStep.FindingSources -> DownloadFindingSourcesDialog(
            step = current,
            onDismiss = DownloadFlowController::dismiss,
        )
        is DownloadFlowStep.ChooseResolution -> DownloadResolutionDialog(
            step = current,
            onDownload = DownloadFlowController::chooseResolution,
            onChooseManually = DownloadFlowController::chooseManually,
            onDismiss = DownloadFlowController::dismiss,
        )
        is DownloadFlowStep.NothingToDownload -> DownloadNothingFoundDialog(
            step = current,
            onCheckAgain = DownloadFlowController::checkAgainFromSheet,
            onChooseManually = DownloadFlowController::chooseManually,
            onDismiss = DownloadFlowController::dismiss,
        )
        is DownloadFlowStep.NotEnoughSpace -> DownloadFreeSpaceDialog(
            step = current,
            onDownloadWhatFits = DownloadFlowController::downloadWhatFits,
            onDismiss = DownloadFlowController::dismiss,
        )
    }
}

@Composable
fun DownloadMobileDataDialog(
    onUseMobileData: () -> Unit,
    onWait: () -> Unit,
    onDismiss: () -> Unit,
) {
    DownloadFlowDialog(onDismiss = onDismiss) {
        DialogHeading(
            title = stringResource(Res.string.download_flow_mobile_title),
            subtitle = stringResource(Res.string.download_flow_mobile_body),
        )
        DialogButtons(
            secondary = stringResource(Res.string.download_flow_mobile_wait),
            onSecondary = onWait,
            primary = stringResource(Res.string.download_flow_mobile_use),
            onPrimary = onUseMobileData,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadSeasonChooserDialog(
    step: DownloadFlowStep.ChooseSeasons,
    onChange: (selected: Set<Int>, unwatchedOnly: Boolean) -> Unit,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    DownloadFlowDialog(onDismiss = onDismiss) {
        DialogHeading(
            title = stringResource(Res.string.download_flow_seasons_title),
            subtitle = step.title.title,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s16),
        ) {
            NuvioActionLabel(
                text = stringResource(Res.string.download_batch_seasons_all),
                onClick = { onChange(DownloadFlowRules.allSeasons(step.seasons), false) },
            )
            NuvioActionLabel(
                text = stringResource(Res.string.download_flow_seasons_unwatched),
                onClick = { onChange(DownloadFlowRules.unwatchedSeasons(step.seasons), true) },
            )
            NuvioActionLabel(
                text = stringResource(Res.string.download_batch_seasons_none),
                onClick = { onChange(emptySet(), step.unwatchedOnly) },
            )
        }
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
        ) {
            step.seasons.forEach { choice ->
                val selected = choice.season in step.selected
                FilterChip(
                    selected = selected,
                    onClick = {
                        onChange(
                            if (selected) step.selected - choice.season else step.selected + choice.season,
                            step.unwatchedOnly,
                        )
                    },
                    label = { Text(seasonName(choice.season)) },
                    shape = tokens.shapes.chip,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = tokens.colors.accent,
                        selectedLabelColor = tokens.colors.onAccent,
                        labelColor = tokens.colors.textPrimary,
                    ),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onChange(step.selected, !step.unwatchedOnly) },
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.download_flow_unwatched_only),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textPrimary,
            )
            Switch(
                checked = step.unwatchedOnly,
                onCheckedChange = { onChange(step.selected, it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = tokens.colors.onAccent,
                    checkedTrackColor = tokens.colors.accent,
                    uncheckedThumbColor = tokens.colors.textMuted,
                    uncheckedTrackColor = tokens.colors.borderDefault,
                ),
            )
        }
        DialogButtons(
            secondary = stringResource(Res.string.action_cancel),
            onSecondary = onDismiss,
            primary = if (step.selected.isEmpty()) {
                stringResource(Res.string.download_flow_continue)
            } else {
                "${stringResource(Res.string.download_flow_continue)} · " +
                    stringResource(Res.string.download_flow_episode_count, step.episodeCount)
            },
            onPrimary = onContinue,
            primaryEnabled = step.selected.isNotEmpty() && step.episodeCount > 0,
        )
    }
}

@Composable
fun DownloadFindingSourcesDialog(
    step: DownloadFlowStep.FindingSources,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    DownloadFlowDialog(onDismiss = onDismiss) {
        DialogHeading(
            title = stringResource(Res.string.download_flow_finding_title),
            subtitle = if (step.total > 1) {
                stringResource(Res.string.download_flow_finding_progress, step.done, step.total)
            } else {
                step.title.title
            },
        )
        LinearProgressIndicator(
            progress = { if (step.total > 1) step.done.toFloat() / step.total else 0f },
            modifier = Modifier.fillMaxWidth(),
            color = tokens.colors.accent,
            trackColor = tokens.colors.borderSubtle,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel), color = tokens.colors.textMuted)
            }
        }
    }
}

@Composable
fun DownloadResolutionDialog(
    step: DownloadFlowStep.ChooseResolution,
    onDownload: (height: Int) -> Unit,
    onChooseManually: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    var selected by remember(step) { mutableStateOf(step.preselectedHeight ?: step.rows.firstOrNull()?.height) }
    DownloadFlowDialog(onDismiss = onDismiss) {
        DialogHeading(
            title = stringResource(Res.string.download_flow_resolution_title),
            subtitle = "${step.title.title} · ${scopeSummary(step.scope, step.targetCount)}",
        )
        Column(
            modifier = Modifier
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        ) {
            step.rows.forEach { row ->
                ResolutionRowCard(
                    row = row,
                    isSeason = step.targetCount > 1,
                    selected = row.height == selected,
                    onClick = { selected = row.height },
                )
            }
        }
        if (step.offersChooseManually) {
            NuvioActionLabel(
                text = stringResource(Res.string.download_flow_choose_manually),
                onClick = onChooseManually,
            )
        }
        DialogButtons(
            secondary = stringResource(Res.string.action_cancel),
            onSecondary = onDismiss,
            primary = stringResource(Res.string.download_flow_download),
            onPrimary = { selected?.let(onDownload) },
            primaryEnabled = selected != null,
        )
    }
}

@Composable
private fun ResolutionRowCard(
    row: DownloadResolutionRow,
    isSeason: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = tokens.shapes.compactCard,
        color = if (selected) tokens.colors.accent.copy(alpha = tokens.opacity.selected) else tokens.colors.surfaceCard,
        border = BorderStroke(
            width = if (selected) tokens.borders.medium else tokens.borders.hairline,
            color = if (selected) tokens.colors.accent else tokens.colors.borderSubtle,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = NuvioTokens.Space.s14, vertical = NuvioTokens.Space.s12),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = DownloadFlowRules.resolutionLabel(row.height),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = sizeText(row, isSeason),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textPrimary,
                )
            }
            val notes = buildList {
                row.detail?.takeIf { it.isNotBlank() }?.let(::add)
                if (row.overLimit) add(stringResource(Res.string.download_flow_resolution_over_limit))
                if (isSeason && row.missingCount > 0) {
                    add(stringResource(Res.string.download_flow_resolution_missing, row.missingCount))
                }
            }
            if (notes.isNotEmpty()) {
                Text(
                    text = notes.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun sizeText(row: DownloadResolutionRow, isSeason: Boolean): String {
    val known = row.totalBytes.takeIf { it > 0L }?.let(DownloadFlowRules::sizeLabel)
    if (!isSeason) return known ?: stringResource(Res.string.download_flow_size_unknown)
    return stringResource(
        Res.string.download_flow_resolution_season_total,
        known ?: stringResource(Res.string.download_flow_size_unknown),
        row.episodeCount,
    )
}

@Composable
fun DownloadNothingFoundDialog(
    step: DownloadFlowStep.NothingToDownload,
    onCheckAgain: () -> Unit,
    onChooseManually: () -> Unit,
    onDismiss: () -> Unit,
) {
    val nothingCached = step.kind == DownloadEntryDecisionKind.NOTHING_CACHED
    DownloadFlowDialog(onDismiss = onDismiss) {
        DialogHeading(
            title = stringResource(
                if (nothingCached) Res.string.download_flow_nothing_cached_title else Res.string.download_flow_no_sources_title,
            ),
            subtitle = stringResource(
                if (nothingCached) Res.string.download_flow_nothing_cached_body else Res.string.download_flow_no_sources_body,
            ),
        )
        if (step.offersChooseManually) {
            NuvioActionLabel(
                text = stringResource(Res.string.download_flow_choose_manually),
                onClick = onChooseManually,
            )
        }
        DialogButtons(
            secondary = stringResource(Res.string.download_flow_close),
            onSecondary = onDismiss,
            primary = stringResource(Res.string.download_flow_check_again),
            onPrimary = onCheckAgain,
        )
    }
}

@Composable
fun DownloadFreeSpaceDialog(
    step: DownloadFlowStep.NotEnoughSpace,
    onDownloadWhatFits: () -> Unit,
    onDismiss: () -> Unit,
) {
    DownloadFlowDialog(onDismiss = onDismiss) {
        DialogHeading(
            title = stringResource(Res.string.download_flow_space_title),
            subtitle = stringResource(
                Res.string.download_flow_space_body,
                DownloadFlowRules.sizeLabel(step.neededBytes),
                DownloadFlowRules.sizeLabel(step.freeBytes),
            ),
        )
        DialogButtons(
            secondary = stringResource(Res.string.action_cancel),
            onSecondary = onDismiss,
            primary = stringResource(Res.string.download_flow_space_fits, step.fitCount, step.totalCount),
            onPrimary = onDownloadWhatFits,
            primaryEnabled = step.fitCount > 0,
        )
    }
}

/** Delete always confirms (Phase 9). [what] names it: a title, "Season 2", an episode. */
@Composable
fun DownloadDeleteConfirmDialog(what: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    NuvioStatusModal(
        title = stringResource(Res.string.download_delete_confirm_title),
        message = stringResource(Res.string.download_delete_confirm_body, what),
        isVisible = true,
        confirmText = stringResource(Res.string.action_delete),
        dismissText = stringResource(Res.string.action_cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

// --- shared shell ------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadFlowDialog(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        DownloadFlowSurface(content)
    }
}

/** The dialog's body without the window, for the render harness. */
@Composable
fun DownloadFlowSurface(content: @Composable ColumnScope.() -> Unit) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 520.dp),
        shape = tokens.shapes.dialog,
        color = tokens.colors.surfaceDialog,
        tonalElevation = tokens.elevation.modal,
    ) {
        Column(
            modifier = Modifier.padding(tokens.spacing.dialogPadding),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s16),
            content = content,
        )
    }
}

@Composable
private fun DialogHeading(title: String, subtitle: String?) {
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
            )
        }
    }
}

@Composable
private fun DialogButtons(
    secondary: String,
    onSecondary: () -> Unit,
    primary: String,
    onPrimary: () -> Unit,
    primaryEnabled: Boolean = true,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onSecondary) {
            Text(text = secondary, color = tokens.colors.textMuted)
        }
        NuvioPrimaryButton(
            text = primary,
            modifier = Modifier.weight(1f),
            enabled = primaryEnabled,
            onClick = onPrimary,
        )
    }
}

@Composable
private fun seasonName(season: Int): String = if (season == 0) {
    stringResource(Res.string.episodes_specials)
} else {
    stringResource(Res.string.episodes_season, season)
}

@Composable
private fun scopeSummary(scope: DownloadScope, targetCount: Int): String = when (scope) {
    is DownloadScope.Movie -> stringResource(Res.string.download_batch_scope_movie)
    is DownloadScope.Episode -> stringResource(Res.string.download_batch_scope_episode, scope.season, scope.episode)
    is DownloadScope.Season -> stringResource(Res.string.download_batch_scope_season, scope.season)
    is DownloadScope.SeasonUnwatched -> stringResource(Res.string.download_batch_scope_season_unwatched, scope.season)
    is DownloadScope.SelectedSeasons -> stringResource(Res.string.download_flow_episode_count, targetCount)
}
