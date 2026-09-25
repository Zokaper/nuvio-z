package com.nuvio.app.features.downloads

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The Downloads screen's Phase 9 surfaces (stage 7). Stateless: data in, callbacks out, so the
 * render harness draws exactly what the screen draws. Every status line comes from
 * [DownloadPresentation] - the same words the notification and the Live Activity use.
 */

// --- storage -----------------------------------------------------------------------------------

@Composable
fun DownloadStorageBar(summary: DownloadStorageSummary, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = if (summary.freeBytes > 0L) {
                stringResource(
                    Res.string.download_storage_summary,
                    formatDownloadBytes(summary.usedBytes),
                    formatDownloadBytes(summary.freeBytes),
                )
            } else {
                stringResource(Res.string.download_storage_used, formatDownloadBytes(summary.usedBytes))
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        summary.usedFraction?.let { fraction ->
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
    }
}

// --- attention ---------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadAttentionCard(
    card: AttentionCard,
    onAction: (AttentionAction) -> Unit,
    onChooseMember: (AttentionMember) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = listOfNotNull(card.title, card.season?.let { seasonName(it) }).joinToString(" · "),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = attentionHeadline(card),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            // The episodes it is about, each with its own Choose when a pick could help it.
            if (card.members.size > 1 || card.season != null) {
                card.members.take(MAX_MEMBER_ROWS).forEach { member ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = memberLabel(member),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (member.offersChooseManually && card.kind != DownloadNeedsYouKind.MANUAL_PICK) {
                            TextButton(onClick = { onChooseMember(member) }) {
                                Text(stringResource(Res.string.download_action_choose))
                            }
                        }
                    }
                }
                if (card.members.size > MAX_MEMBER_ROWS) {
                    Text(
                        text = "+${card.members.size - MAX_MEMBER_ROWS}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (card.members.single().offersChooseManually && card.kind != DownloadNeedsYouKind.MANUAL_PICK) {
                TextButton(onClick = { onChooseMember(card.members.single()) }) {
                    Text(stringResource(Res.string.download_flow_choose_manually))
                }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                card.actions.forEach { action ->
                    val label = actionLabel(card, action)
                    if (action == card.actions.first()) {
                        androidx.compose.material3.Button(onClick = { onAction(action) }) { Text(label) }
                    } else {
                        OutlinedButton(onClick = { onAction(action) }) { Text(label) }
                    }
                }
            }
        }
    }
}

private const val MAX_MEMBER_ROWS = 6

@Composable
private fun attentionHeadline(card: AttentionCard): String {
    val base = when (card.kind) {
        DownloadNeedsYouKind.NO_SOURCE_FITS -> stringResource(noSourceResource(card.noSourceReason))
        DownloadNeedsYouKind.MANUAL_PICK -> stringResource(Res.string.download_phase_manual_pick)
        DownloadNeedsYouKind.GAVE_UP -> stringResource(Res.string.download_phase_gave_up)
        DownloadNeedsYouKind.STORAGE -> stringResource(Res.string.download_phase_storage)
    }
    return if (card.members.size > 1) {
        "${stringResource(Res.string.download_flow_episode_count, card.members.size)} · $base"
    } else {
        base
    }
}

@Composable
private fun actionLabel(card: AttentionCard, action: AttentionAction): String = when (action) {
    AttentionAction.ALLOW -> card.allowBytes?.let {
        stringResource(Res.string.download_action_allow_bytes, formatDownloadBytes(it))
    } ?: stringResource(Res.string.download_action_allow)
    AttentionAction.USE_NEAREST -> stringResource(Res.string.download_action_use_nearest)
    AttentionAction.CHOOSE_SOURCES -> stringResource(Res.string.download_action_choose_sources)
    AttentionAction.PICK_THE_REST -> stringResource(Res.string.download_action_pick_the_rest)
    AttentionAction.CHECK_AGAIN -> stringResource(Res.string.download_flow_check_again)
    AttentionAction.RETRY -> stringResource(Res.string.action_retry)
    AttentionAction.FREE_UP_SPACE -> stringResource(Res.string.download_action_free_up_space)
    AttentionAction.REMOVE -> stringResource(Res.string.download_action_remove)
}

@Composable
private fun memberLabel(member: AttentionMember): String {
    val season = member.season
    val episode = member.episode
    val code = if (season != null && episode != null) {
        stringResource(Res.string.compose_player_episode_code_full, season, episode)
    } else {
        null
    }
    return listOfNotNull(code, member.title.takeIf { it.isNotBlank() }).joinToString(" · ")
}

@Composable
private fun seasonName(season: Int): String =
    if (season == 0) stringResource(Res.string.episodes_specials) else stringResource(Res.string.episodes_season, season)

// --- watched cleanup ---------------------------------------------------------------------------

@Composable
fun DownloadWatchedCleanupCard(cleanup: WatchedCleanup, onReview: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(Res.string.download_cleanup_title, formatDownloadBytes(cleanup.bytes)),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(Res.string.download_cleanup_body, cleanup.items.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onReview) { Text(stringResource(Res.string.download_cleanup_action)) }
        }
    }
}

// --- the queue ---------------------------------------------------------------------------------

/** What a queue row may do. Null actions are hidden. */
class DownloadGroupControls(
    val onPauseAll: () -> Unit,
    val onResumeAll: () -> Unit,
    val onCancelRemaining: () -> Unit,
    val onMoveUp: (() -> Unit)?,
    val onMoveDown: (() -> Unit)?,
)

/**
 * A season as one row - combined progress, "Season 2 · 4 left · 3 done", the most active
 * episode's plain line - that expands into its episodes. A film or a lone episode is a
 * [DownloadQueueItemRow].
 */
@Composable
fun DownloadQueueGroupRow(
    group: DownloadQueueGroup,
    controls: DownloadGroupControls,
    onOpenItem: (DownloadItem) -> Unit,
    onPauseItem: (DownloadItem) -> Unit,
    onResumeItem: (DownloadItem) -> Unit,
    initiallyExpanded: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(group.key) { mutableStateOf(initiallyExpanded) }
    val now = tickingNow(group.presentations.any { it.detail.nextRetryAtEpochMs != null })
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = group.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (group.completedCount > 0) {
                            stringResource(
                                Res.string.download_group_season_progress_done,
                                group.season ?: 0,
                                group.items.size,
                                group.completedCount,
                            )
                        } else {
                            stringResource(Res.string.download_group_season_progress, group.season ?: 0, group.items.size)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = group.lead.plainText(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (group.allPaused) {
                    IconButton(onClick = controls.onResumeAll) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(Res.string.download_action_resume_all))
                    }
                } else {
                    IconButton(onClick = controls.onPauseAll) {
                        Icon(Icons.Rounded.Pause, contentDescription = stringResource(Res.string.download_action_pause_all))
                    }
                }
                GroupMenu(group, controls)
            }
            val progress = group.progressPercent
            if (group.lead.phase == DownloadUserPhase.DOWNLOADING && progress != null) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 10.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    group.items.zip(group.presentations).forEach { (item, presentation) ->
                        DownloadQueueItemRow(
                            item = item,
                            presentation = presentation,
                            nowEpochMs = now,
                            compact = true,
                            onOpen = { onOpenItem(item) },
                            onPause = { onPauseItem(item) },
                            onResume = { onResumeItem(item) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupMenu(group: DownloadQueueGroup, controls: DownloadGroupControls) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(Res.string.downloads_queue_actions))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (group.anyPaused && !group.allPaused) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.download_action_resume_all)) },
                    onClick = { open = false; controls.onResumeAll() },
                )
            }
            controls.onMoveUp?.let { move ->
                DropdownMenuItem(text = { Text(stringResource(Res.string.download_action_move_up)) }, onClick = { open = false; move() })
            }
            controls.onMoveDown?.let { move ->
                DropdownMenuItem(text = { Text(stringResource(Res.string.download_action_move_down)) }, onClick = { open = false; move() })
            }
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.download_action_cancel_remaining)) },
                onClick = { open = false; controls.onCancelRemaining() },
            )
        }
    }
}

/**
 * One download in the queue. The plain line only; tapping opens the detail. [compact] is the
 * episode row inside an expanded season, which does without artwork and the show's title.
 */
@Composable
fun DownloadQueueItemRow(
    item: DownloadItem,
    presentation: DownloadPresentation,
    nowEpochMs: Long,
    onOpen: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val content = @Composable {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = 14.dp, vertical = if (compact) 8.dp else 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = if (compact) queueEpisodeLabel(item) else item.title,
                    style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (compact) FontWeight.Normal else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!compact && item.isEpisode) {
                    Text(
                        text = queueEpisodeLabel(item),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = presentation.plainText(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val progress = presentation.progressPercent
                if (presentation.phase == DownloadUserPhase.DOWNLOADING && progress != null) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
            when (presentation.phase) {
                DownloadUserPhase.PAUSED -> IconButton(onClick = onResume) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(Res.string.action_resume))
                }
                DownloadUserPhase.COMPLETED, DownloadUserPhase.NEEDS_YOU -> Unit
                else -> IconButton(onClick = onPause) {
                    Icon(Icons.Rounded.Pause, contentDescription = stringResource(Res.string.compose_action_pause))
                }
            }
        }
    }
    if (compact) {
        Box(modifier.fillMaxWidth()) { content() }
    } else {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) { content() }
    }
    // Held back by the mobile-data rule, not broken: the one action worth a button on the row.
    if (presentation.waitReason == DownloadWaitReason.WIFI) {
        TextButton(
            onClick = { DownloadsRepository.allowMobileData(listOf(item.id)) },
            modifier = Modifier.padding(horizontal = 16.dp),
        ) { Text(stringResource(Res.string.downloads_download_now_anyway)) }
    }
}

@Composable
private fun queueEpisodeLabel(item: DownloadItem): String {
    val season = item.seasonNumber
    val episode = item.episodeNumber
    if (season == null || episode == null) return item.title
    val code = stringResource(Res.string.compose_player_episode_code_full, season, episode)
    val name = item.episodeTitle?.trim()?.takeIf { it.isNotBlank() }
    return listOfNotNull(code, name).joinToString(" · ")
}

// --- detail ------------------------------------------------------------------------------------

/** Detail on tap: the plain line, then the technical facts, then what can be done. */
@Composable
fun DownloadDetailContent(
    item: DownloadItem,
    presentation: DownloadPresentation,
    nowEpochMs: Long,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onChange: () -> Unit,
    onDownloadNext: () -> Unit,
    onDelete: () -> Unit,
) {
    val detail = presentation.detail
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = item.displayTitle(),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(presentation.plainText(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        DetailLine(stringResource(Res.string.download_detail_source), detail.source)
        DetailLine(stringResource(Res.string.download_detail_provider), detail.provider)
        if (detail.attempts > 0) {
            DetailLine(
                stringResource(Res.string.download_detail_attempts),
                stringResource(Res.string.download_detail_attempts_value, detail.attempts, detail.maxAttempts),
            )
        }
        detail.nextRetryAtEpochMs?.let { at ->
            val seconds = ((at - nowEpochMs + 999L) / 1000L).toInt().coerceAtLeast(0)
            DetailLine(
                stringResource(Res.string.download_detail_next_retry),
                stringResource(Res.string.download_detail_next_retry_value, seconds),
            )
        }
        DetailLine(stringResource(Res.string.download_detail_last_error), detail.lastError)
        DetailLine(stringResource(Res.string.download_detail_engine), detail.engineState)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (presentation.phase == DownloadUserPhase.PAUSED) {
                OutlinedButton(onClick = onResume) { Text(stringResource(Res.string.action_resume)) }
            } else if (presentation.isUnfinished) {
                OutlinedButton(onClick = onPause) { Text(stringResource(Res.string.compose_action_pause)) }
            }
            if (presentation.isUnfinished) {
                OutlinedButton(onClick = onChange) { Text(stringResource(Res.string.download_flow_change)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (presentation.isUnfinished) {
                TextButton(onClick = onDownloadNext) { Text(stringResource(Res.string.download_detail_move_to_top)) }
            }
            TextButton(onClick = onDelete) {
                Text(stringResource(Res.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = label,
            modifier = Modifier.width(96.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** A clock that ticks only while something counts down (a retry), like the old status line. */
@Composable
internal fun tickingNow(active: Boolean): Long {
    var now by remember { mutableStateOf(DownloadsClock.nowEpochMs()) }
    LaunchedEffect(active) {
        while (active) {
            now = DownloadsClock.nowEpochMs()
            delay(1_000L)
        }
    }
    return now
}
