package com.nuvio.app.features.downloads

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioActionLabel
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.NuvioToastAction
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.details.MetaDetails
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.download_batch_finding_sources_started
import nuvio.composeapp.generated.resources.download_batch_mobile_data
import nuvio.composeapp.generated.resources.download_batch_scope_episode
import nuvio.composeapp.generated.resources.download_batch_scope_movie
import nuvio.composeapp.generated.resources.download_batch_scope_season
import nuvio.composeapp.generated.resources.download_batch_scope_season_unwatched
import nuvio.composeapp.generated.resources.download_batch_scope_seasons
import nuvio.composeapp.generated.resources.download_batch_scope_seasons_none
import nuvio.composeapp.generated.resources.download_batch_seasons_all
import nuvio.composeapp.generated.resources.download_batch_seasons_none
import nuvio.composeapp.generated.resources.download_batch_select_seasons
import nuvio.composeapp.generated.resources.download_batch_start
import nuvio.composeapp.generated.resources.download_batch_view_downloads
import nuvio.composeapp.generated.resources.download_preset_title
import nuvio.composeapp.generated.resources.episodes_season
import nuvio.composeapp.generated.resources.episodes_specials
import org.jetbrains.compose.resources.stringResource

/**
 * Picks the scope and the preset, then hands the work to the coordinator.
 *
 * Review is not shown here any more: discovery is backgrounded, and everything it
 * produces - progress, approvals, manual source picks - is presented in the Downloads
 * tab instead, which survives the user navigating away.
 *
 * A preset is selected first and started by the button underneath, because tapping a
 * preset used to queue a whole season of downloads on the spot with nothing in between
 * the tap and gigabytes of transfer.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PresetDownloadDialog(
    meta: MetaDetails,
    initialScope: DownloadScope,
    currentSeason: Int?,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
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
    val viewDownloadsLabel = stringResource(Res.string.download_batch_view_downloads)
    val presets by DownloadsRepository.presets.collectAsStateWithLifecycle()
    val batches by DownloadsRepository.batches.collectAsStateWithLifecycle()
    // Whatever was used last is nearly always what is wanted again, and batches are
    // stored newest first.
    val lastUsedPresetId = remember(batches) { batches.firstOrNull()?.presetSnapshot?.id }
    var selectedPresetId by remember(meta.id, initialScope) { mutableStateOf<String?>(null) }
    val selectedPreset = presets.firstOrNull { it.id == selectedPresetId }
        ?: presets.firstOrNull { it.id == lastUsedPresetId }
        ?: presets.firstOrNull()
    val seasonsChosen = initialScope !is DownloadScope.SelectedSeasons || selectedSeasons.isNotEmpty()

    fun start() {
        val preset = selectedPreset ?: return
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
        NuvioToastController.show(
            message = findingSourcesMessage,
            // Long enough to read the message and still reach the link.
            durationMillis = 5_000L,
            actionLabel = viewDownloadsLabel,
            action = NuvioToastAction.OpenDownloads,
        )
        onDismiss()
    }

    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = tokens.shapes.dialog,
            color = tokens.colors.surfaceDialog,
            tonalElevation = tokens.elevation.modal,
        ) {
            Column(
                modifier = Modifier.padding(tokens.spacing.dialogPadding),
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s16),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4)) {
                    Text(
                        text = stringResource(Res.string.download_preset_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = tokens.colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = downloadScopeSummary(initialScope, selectedSeasons),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textMuted,
                    )
                }

                Column(
                    modifier = Modifier
                        .heightIn(max = tokens.breakpoints.largePhone)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s16),
                ) {
                    if (initialScope is DownloadScope.SelectedSeasons) {
                        Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                NuvioSectionLabel(text = stringResource(Res.string.download_batch_select_seasons))
                                Row(horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12)) {
                                    NuvioActionLabel(
                                        text = stringResource(Res.string.download_batch_seasons_all),
                                        onClick = { selectedSeasons = availableSeasons.toSet() },
                                    )
                                    NuvioActionLabel(
                                        text = stringResource(Res.string.download_batch_seasons_none),
                                        onClick = { selectedSeasons = emptySet() },
                                    )
                                }
                            }
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
                            ) {
                                availableSeasons.forEach { season ->
                                    FilterChip(
                                        selected = season in selectedSeasons,
                                        onClick = {
                                            selectedSeasons = if (season in selectedSeasons) {
                                                selectedSeasons - season
                                            } else {
                                                selectedSeasons + season
                                            }
                                        },
                                        label = { Text(seasonLabel(season)) },
                                        shape = tokens.shapes.chip,
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = tokens.colors.accent,
                                            selectedLabelColor = tokens.colors.onAccent,
                                            labelColor = tokens.colors.textPrimary,
                                        ),
                                    )
                                }
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
                        presets.forEach { preset ->
                            PresetChoiceCard(
                                preset = preset,
                                selected = preset.id == selectedPreset?.id,
                                onClick = { selectedPresetId = preset.id },
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { allowMetered = !allowMetered },
                    horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(Res.string.download_batch_mobile_data),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textPrimary,
                    )
                    Switch(
                        checked = allowMetered,
                        onCheckedChange = { allowMetered = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = tokens.colors.onAccent,
                            checkedTrackColor = tokens.colors.accent,
                            uncheckedThumbColor = tokens.colors.textMuted,
                            uncheckedTrackColor = tokens.colors.borderDefault,
                        ),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = stringResource(Res.string.action_cancel),
                            color = tokens.colors.textMuted,
                        )
                    }
                    NuvioPrimaryButton(
                        text = stringResource(Res.string.download_batch_start),
                        modifier = Modifier.weight(1f),
                        enabled = selectedPreset != null && seasonsChosen,
                        onClick = { start() },
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetChoiceCard(
    preset: DownloadPreset,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = tokens.shapes.compactCard,
        color = if (selected) {
            tokens.colors.accent.copy(alpha = tokens.opacity.selected)
        } else {
            tokens.colors.surfaceCard
        },
        border = BorderStroke(
            width = if (selected) tokens.borders.medium else tokens.borders.hairline,
            color = if (selected) tokens.colors.accent else tokens.colors.borderSubtle,
        ),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = NuvioTokens.Space.s14,
                vertical = NuvioTokens.Space.s12,
            ),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2),
        ) {
            Text(
                text = preset.name,
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = preset.summaryLine(),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
        }
    }
}

@Composable
private fun seasonLabel(season: Int): String = if (season == 0) {
    stringResource(Res.string.episodes_specials)
} else {
    stringResource(Res.string.episodes_season, season)
}

/** What the batch will cover, so the dialog says what it is about to download. */
@Composable
private fun downloadScopeSummary(scope: DownloadScope, selectedSeasons: Set<Int>): String =
    when (scope) {
        is DownloadScope.Movie -> stringResource(Res.string.download_batch_scope_movie)
        is DownloadScope.Episode ->
            stringResource(Res.string.download_batch_scope_episode, scope.season, scope.episode)
        is DownloadScope.Season -> stringResource(Res.string.download_batch_scope_season, scope.season)
        is DownloadScope.SeasonUnwatched ->
            stringResource(Res.string.download_batch_scope_season_unwatched, scope.season)
        is DownloadScope.SelectedSeasons -> if (selectedSeasons.isEmpty()) {
            stringResource(Res.string.download_batch_scope_seasons_none)
        } else {
            stringResource(Res.string.download_batch_scope_seasons, selectedSeasons.size)
        }
    }
