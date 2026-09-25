package com.nuvio.app.features.downloads

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
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
import nuvio.composeapp.generated.resources.download_batch_scope_seasons_none
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
import nuvio.composeapp.generated.resources.download_flow_resolution_unavailable
import nuvio.composeapp.generated.resources.download_flow_resolution_over_limit
import nuvio.composeapp.generated.resources.download_flow_resolution_title
import nuvio.composeapp.generated.resources.download_flow_seasons_title
import nuvio.composeapp.generated.resources.download_flow_size_unknown
import nuvio.composeapp.generated.resources.download_flow_space_body
import nuvio.composeapp.generated.resources.download_flow_space_fits
import nuvio.composeapp.generated.resources.download_flow_space_title
import nuvio.composeapp.generated.resources.episodes_season
import nuvio.composeapp.generated.resources.episodes_specials
import nuvio.composeapp.generated.resources.download_flow_seasons_mode_unwatched
import nuvio.composeapp.generated.resources.download_flow_seasons_mode_all
import nuvio.composeapp.generated.resources.download_flow_seasons_select_all
import nuvio.composeapp.generated.resources.download_flow_seasons_clear
import nuvio.composeapp.generated.resources.download_flow_season_unwatched_count
import nuvio.composeapp.generated.resources.download_flow_season_watched
import nuvio.composeapp.generated.resources.download_flow_seasons_selected_episodes
import nuvio.composeapp.generated.resources.download_flow_seasons_selected_seasons
import nuvio.composeapp.generated.resources.download_flow_resolution_each
import nuvio.composeapp.generated.resources.download_flow_resolution_episodes
import nuvio.composeapp.generated.resources.download_choose_sources_scope
import org.jetbrains.compose.resources.pluralStringResource
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

@Composable
fun DownloadSeasonChooserDialog(
    step: DownloadFlowStep.ChooseSeasons,
    onChange: (selected: Set<Int>, unwatchedOnly: Boolean) -> Unit,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    // One selection model: the ticked seasons, and - once the show is started - whether
    // "episodes" means the unwatched ones or all of them. No preset row on top of it.
    val offersMode = DownloadFlowRules.offersUnwatchedMode(step.seasons)
    val unwatchedOnly = offersMode && step.unwatchedOnly
    val selectable = step.seasons.filter { DownloadFlowRules.isSelectable(it, unwatchedOnly) && it.season != 0 }
    val allTicked = selectable.isNotEmpty() && selectable.all { it.season in step.selected }
    DownloadFlowDialog(onDismiss = onDismiss) {
        DialogHeading(
            title = stringResource(Res.string.download_flow_seasons_title),
            subtitle = step.title.title,
            poster = step.title.poster ?: step.title.background,
        )
        if (offersMode) {
            SegmentedChoice(
                options = listOf(
                    stringResource(Res.string.download_flow_seasons_mode_unwatched),
                    stringResource(Res.string.download_flow_seasons_mode_all),
                ),
                selectedIndex = if (unwatchedOnly) 0 else 1,
                onSelect = { index ->
                    val unwatched = index == 0
                    onChange(DownloadFlowRules.selectionForMode(step.seasons, step.selected, unwatched), unwatched)
                },
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SeasonSelectionSummary(step.episodeCount, step.selected.size, Modifier.weight(1f))
                NuvioActionLabel(
                    text = stringResource(
                        if (allTicked) Res.string.download_flow_seasons_clear else Res.string.download_flow_seasons_select_all,
                    ),
                    onClick = {
                        onChange(if (allTicked) emptySet() else DownloadFlowRules.selectAll(step.seasons, unwatchedOnly), unwatchedOnly)
                    },
                )
            }
            Surface(
                shape = tokens.shapes.compactCard,
                color = tokens.colors.surfaceCard,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    step.seasons.forEachIndexed { index, choice ->
                        if (index > 0) HorizontalDivider(color = tokens.colors.borderSubtle)
                        SeasonChoiceRow(
                            choice = choice,
                            unwatchedOnly = unwatchedOnly,
                            selected = choice.season in step.selected,
                            onToggle = { ticked ->
                                onChange(if (ticked) step.selected + choice.season else step.selected - choice.season, unwatchedOnly)
                            },
                        )
                    }
                }
            }
        }
        DialogButtons(
            secondary = stringResource(Res.string.action_cancel),
            onSecondary = onDismiss,
            primary = stringResource(Res.string.download_flow_continue),
            onPrimary = onContinue,
            primaryEnabled = step.selected.isNotEmpty() && step.episodeCount > 0,
        )
    }
}

/**
 * What Continue will act on: "81 episodes selected" over "5 seasons". The count already follows
 * Unwatched / All episodes. Two lines by design - one line wrapped mid-phrase on phones.
 */
@Composable
private fun SeasonSelectionSummary(episodes: Int, seasons: Int, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    Column(modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            text = if (seasons == 0) {
                stringResource(Res.string.download_batch_scope_seasons_none)
            } else {
                pluralStringResource(Res.plurals.download_flow_seasons_selected_episodes, episodes, episodes)
            },
            style = MaterialTheme.typography.labelLarge,
            color = tokens.colors.textSecondary,
        )
        if (seasons > 0) {
            Text(
                text = pluralStringResource(Res.plurals.download_flow_seasons_selected_seasons, seasons, seasons),
                style = MaterialTheme.typography.labelMedium,
                color = tokens.colors.textMuted,
            )
        }
    }
}

@Composable
private fun SeasonChoiceRow(
    choice: DownloadFlowRules.SeasonChoice,
    unwatchedOnly: Boolean,
    selected: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val enabled = DownloadFlowRules.isSelectable(choice, unwatchedOnly)
    val ticked = selected && enabled
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onToggle(!ticked) }
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s10),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
    ) {
        CheckMark(ticked)
        Text(
            text = seasonName(choice.season),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = tokens.colors.textPrimary,
            fontWeight = if (ticked) FontWeight.SemiBold else FontWeight.Normal,
        )
        Text(
            text = when {
                !unwatchedOnly -> stringResource(Res.string.download_flow_episode_count, choice.episodeCount)
                choice.unwatchedCount == 0 -> stringResource(Res.string.download_flow_season_watched)
                else -> stringResource(Res.string.download_flow_season_unwatched_count, choice.unwatchedCount)
            },
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
        )
    }
}

/** A square tick: filled accent when on, an outline when off. */
@Composable
private fun CheckMark(checked: Boolean) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (checked) tokens.colors.accent else Color.Transparent)
            .border(1.5.dp, if (checked) tokens.colors.accent else tokens.colors.borderStrong, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = tokens.colors.onAccent, modifier = Modifier.size(15.dp))
        }
    }
}

/** A round radio: a ring, filled with a dot when chosen. */
@Composable
private fun RadioMark(selected: Boolean) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .border(2.dp, if (selected) tokens.colors.accent else tokens.colors.borderStrong, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(tokens.colors.accent))
        }
    }
}

/** Two options side by side in one pill track. */
@Composable
private fun SegmentedChoice(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(tokens.colors.surfaceCard)
            .padding(3.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(if (selected) tokens.colors.accent else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = NuvioTokens.Space.s8),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) tokens.colors.onAccent else tokens.colors.textSecondary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
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
            poster = step.title.poster ?: step.title.background,
            mediaTitle = step.title.title,
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
            poster = step.title.poster ?: step.title.background,
            mediaTitle = step.title.title,
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
        color = if (selected) tokens.colors.accent.copy(alpha = 0.08f) else tokens.colors.surfaceCard,
        border = BorderStroke(
            width = if (selected) 2.dp else tokens.borders.hairline,
            color = if (selected) tokens.colors.accent else tokens.colors.borderSubtle,
        ),
    ) {
        val cautions = buildList {
            if (row.overLimit) add(stringResource(Res.string.download_flow_resolution_over_limit))
            if (isSeason && row.missingCount > 0) {
                add(
                    pluralStringResource(
                        Res.plurals.download_flow_resolution_unavailable,
                        row.missingCount,
                        DownloadFlowRules.resolutionLabel(row.height),
                        row.missingCount,
                    ),
                )
            }
        }
        Row(
            modifier = Modifier.padding(horizontal = NuvioTokens.Space.s14, vertical = NuvioTokens.Space.s12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        ) {
            RadioMark(selected)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6)) {
                // Quality on the left, what it costs on the right: the two things being traded.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2)) {
                        Text(
                            text = DownloadFlowRules.resolutionLabel(row.height),
                            style = MaterialTheme.typography.titleMedium,
                            color = tokens.colors.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        row.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.colors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2)) {
                        val known = row.totalBytes.takeIf { it > 0L }
                        Text(
                            text = known?.let(DownloadFlowRules::sizeLabel) ?: stringResource(Res.string.download_flow_size_unknown),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (known != null) tokens.colors.textPrimary else tokens.colors.textMuted,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        if (isSeason && row.episodeCount > 0) {
                            Text(
                                text = known?.let {
                                    stringResource(Res.string.download_flow_resolution_each, DownloadFlowRules.sizeLabel(it / row.episodeCount))
                                } ?: stringResource(Res.string.download_flow_resolution_episodes, row.episodeCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.colors.textMuted,
                                maxLines = 1,
                            )
                        }
                    }
                }
                // Each caution on its own line under both columns, so neither squeezes the other.
                cautions.forEach { caution ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(
                            Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = tokens.colors.warning,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = caution,
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.colors.textSecondary,
                        )
                    }
                }
            }
        }
    }
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
                step.fitCount,
                step.totalCount,
            ),
        )
        DialogButtons(
            secondary = stringResource(Res.string.action_cancel),
            onSecondary = onDismiss,
            primary = stringResource(Res.string.download_flow_space_fits),
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

/** True in the render harness: the dialogs draw their surface in place, with no window. */
val LocalDownloadFlowInline = staticCompositionLocalOf { false }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadFlowDialog(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalDownloadFlowInline.current) {
        DownloadFlowSurface(content)
        return
    }
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
private fun DialogHeading(title: String, subtitle: String?, poster: String? = null, mediaTitle: String? = null) {
    val tokens = MaterialTheme.nuvio
    Row(
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s14),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The title's poster, when the dialog is about a title: what it is before what to do.
        val media = poster != null || mediaTitle != null
        if (media) {
            DownloadPoster(url = poster, title = mediaTitle ?: subtitle.orEmpty(), width = 48.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4)) {
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
                    // Beside a poster the subtitle is a title line; without one it is the body.
                    maxLines = if (media) 2 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Side by side, a phone-width dialog leaves the primary button about 180dp, and
        // "Download what fits" wrapped inside it. Narrow dialogs stack: primary on top, full width.
        if (maxWidth < 380.dp) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
            ) {
                NuvioPrimaryButton(
                    text = primary,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = primaryEnabled,
                    onClick = onPrimary,
                )
                TextButton(onClick = onSecondary) {
                    Text(text = secondary, color = tokens.colors.textMuted)
                }
            }
        } else {
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
    is DownloadScope.Season -> if (targetCount > 1) {
        stringResource(Res.string.download_choose_sources_scope, stringResource(Res.string.download_batch_scope_season, scope.season), targetCount)
    } else {
        stringResource(Res.string.download_batch_scope_season, scope.season)
    }
    is DownloadScope.SeasonUnwatched -> stringResource(Res.string.download_batch_scope_season_unwatched, scope.season)
    is DownloadScope.SelectedSeasons -> stringResource(Res.string.download_flow_episode_count, targetCount)
}
