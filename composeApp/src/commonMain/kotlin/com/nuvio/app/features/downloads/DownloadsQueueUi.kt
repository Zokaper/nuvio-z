package com.nuvio.app.features.downloads

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SdCardAlert
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The Downloads screen's Phase 9 surfaces (stage 7). Stateless: data in, callbacks out, so the
 * render harness draws exactly what the screen draws. Every status line comes from
 * [DownloadPresentation] - the same words the notification and the Live Activity use.
 *
 * Composition (the stage 7 visual pass): every row leads with the title's artwork; the queue is
 * flat rows, not a stack of identical cards; what needs the user is one panel whose rows name the
 * problem in a line, with warning colour only on its icon; one clear action per row.
 */

private val PosterWidth = 48.dp
private val SeasonPosterWidth = 56.dp
private val RowGap = 14.dp

// --- storage -----------------------------------------------------------------------------------

@Composable
fun DownloadStorageBar(summary: DownloadStorageSummary, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
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
            color = tokens.colors.textMuted,
        )
        summary.usedFraction?.let { fraction -> ThinProgress(fraction, color = tokens.colors.textSecondary) }
    }
}

/** A Downloads section's title, with room for a count after it. */
@Composable
internal fun DownloadsSectionHeading(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.nuvio.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        trailing()
    }
}

// --- attention ---------------------------------------------------------------------------------

/**
 * "Needs you" as one panel: the section title with a count, then one row per card. One panel
 * rather than a card each, so four problems read as a list and not as four pink billboards.
 */
@Composable
fun DownloadAttentionSection(
    cards: List<AttentionCard>,
    onAction: (AttentionCard, AttentionAction) -> Unit,
    onChooseMember: (AttentionMember) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DownloadsSectionHeading(stringResource(Res.string.download_section_needs_you)) {
            CountBadge(cards.sumOf { it.members.size })
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = tokens.colors.surfaceCard,
        ) {
            // A title/season with two different problems is one row with two problem blocks,
            // not two neighbouring rows repeating the same poster and name. Display only: the
            // cards (and what each action acts on) are unchanged.
            val groups = remember(cards) { cards.groupBy { it.parentMetaId to it.season }.values.toList() }
            Column(Modifier.fillMaxWidth()) {
                groups.forEachIndexed { index, group ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 12.dp + PosterWidth + RowGap),
                            color = tokens.colors.borderSubtle,
                        )
                    }
                    val card = group.singleOrNull()
                    if (card != null) {
                        DownloadAttentionCard(
                            card = card,
                            onAction = { action -> onAction(card, action) },
                            onChooseMember = onChooseMember,
                        )
                    } else {
                        DownloadAttentionGroup(
                            cards = group,
                            onAction = onAction,
                            onChooseMember = onChooseMember,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CountBadge(count: Int) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = Modifier
            .heightIn(min = 20.dp)
            .clip(CircleShape)
            .background(tokens.colors.danger.copy(alpha = 0.18f))
            .padding(horizontal = 7.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = tokens.colors.danger,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * One problem: the title's poster and name, the problem in one line (the colour is on the icon,
 * not the words), the episodes it is about, then one primary action and quiet secondary ones.
 */
@Composable
fun DownloadAttentionCard(
    card: AttentionCard,
    onAction: (AttentionAction) -> Unit,
    onChooseMember: (AttentionMember) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Column(modifier.fillMaxWidth().padding(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(RowGap),
        ) {
            DownloadPoster(url = card.poster, title = card.title, width = PosterWidth)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = card.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                card.season?.let { season ->
                    Text(
                        text = when {
                            season == 0 -> stringResource(Res.string.episodes_specials)
                            card.members.size == 1 -> stringResource(Res.string.episodes_season, season)
                            else -> stringResource(Res.string.download_attention_episodes_line, season, card.members.size)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                        maxLines = 1,
                    )
                }
                AttentionProblemLine(card)
                AttentionMembers(card, onChooseMember)
            }
            // Remove is always offered and always confirms; as a quiet corner control it stops
            // competing with the one thing that would fix the problem.
            AttentionRemoveControl(card, onAction)
        }
        AttentionActionPills(
            card = card,
            onAction = onAction,
            onChooseMember = onChooseMember,
            modifier = Modifier.padding(start = PosterWidth + RowGap, top = 10.dp),
        )
    }
}

/**
 * One title/season with several different problems: the poster and name once, then each problem
 * as its own block - the problem line with its own Remove, its episodes, its actions.
 */
@Composable
private fun DownloadAttentionGroup(
    cards: List<AttentionCard>,
    onAction: (AttentionCard, AttentionAction) -> Unit,
    onChooseMember: (AttentionMember) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val first = cards.first()
    Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(RowGap),
    ) {
        DownloadPoster(url = cards.firstNotNullOfOrNull { it.poster }, title = first.title, width = PosterWidth)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = first.title,
                style = MaterialTheme.typography.titleSmall,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            first.season?.let { season ->
                Text(
                    text = if (season == 0) {
                        stringResource(Res.string.episodes_specials)
                    } else {
                        stringResource(Res.string.episodes_season, season)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                )
            }
            cards.forEach { card ->
                Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AttentionProblemLine(card, Modifier.weight(1f))
                        AttentionRemoveControl(card) { action -> onAction(card, action) }
                    }
                    AttentionMembers(card, onChooseMember)
                    AttentionActionPills(
                        card = card,
                        onAction = { action -> onAction(card, action) },
                        onChooseMember = onChooseMember,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

/** A lone movie or episode is named by the row itself; everything else lists its episodes. */
private val AttentionCard.singleMember: AttentionMember?
    get() = members.singleOrNull()?.takeIf { season == null }

@Composable
private fun AttentionProblemLine(card: AttentionCard, modifier: Modifier = Modifier) {
    val (icon, tint) = attentionIcon(card)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
        Text(
            text = attentionProblem(card),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.nuvio.colors.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The episodes a card is about, each with its own Choose when a pick could help it. */
@Composable
private fun AttentionMembers(card: AttentionCard, onChooseMember: (AttentionMember) -> Unit) {
    if (card.singleMember != null) return
    Column(Modifier.padding(top = 2.dp).widthIn(max = 440.dp)) {
        card.members.take(MAX_MEMBER_ROWS).forEach { member ->
            MemberRow(
                member = member,
                showChoose = member.offersChooseManually && card.kind != DownloadNeedsYouKind.MANUAL_PICK,
                onChoose = { onChooseMember(member) },
            )
        }
        if (card.members.size > MAX_MEMBER_ROWS) {
            Text(
                text = stringResource(Res.string.download_attention_more, card.members.size - MAX_MEMBER_ROWS),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.nuvio.colors.textMuted,
            )
        }
    }
}

@Composable
private fun AttentionRemoveControl(card: AttentionCard, onAction: (AttentionAction) -> Unit) {
    if (AttentionAction.REMOVE !in card.actions) return
    IconButton(
        onClick = { onAction(AttentionAction.REMOVE) },
        modifier = Modifier.size(32.dp),
    ) {
        Icon(
            Icons.Rounded.Close,
            contentDescription = stringResource(Res.string.download_action_remove),
            tint = MaterialTheme.nuvio.colors.textMuted,
            modifier = Modifier.size(18.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttentionActionPills(
    card: AttentionCard,
    onAction: (AttentionAction) -> Unit,
    onChooseMember: (AttentionMember) -> Unit,
    modifier: Modifier = Modifier,
) {
    val single = card.singleMember
    val chooseSingle = single != null && single.offersChooseManually && card.kind != DownloadNeedsYouKind.MANUAL_PICK
    val choices = card.actions.filter { it != AttentionAction.REMOVE }
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        choices.firstOrNull()?.let { action -> PillButton(actionLabel(card, action), PillStyle.PRIMARY) { onAction(action) } }
        if (chooseSingle) {
            PillButton(stringResource(Res.string.download_flow_choose_manually), PillStyle.SECONDARY) {
                onChooseMember(single!!)
            }
        }
        choices.drop(1).forEach { action ->
            PillButton(actionLabel(card, action), PillStyle.SECONDARY) { onAction(action) }
        }
    }
}

private const val MAX_MEMBER_ROWS = 3

@Composable
private fun MemberRow(member: AttentionMember, showChoose: Boolean, onChoose: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        member.episode?.let { episode ->
            Text(
                text = stringResource(Res.string.download_episode_short, episode),
                modifier = Modifier.width(30.dp),
                style = MaterialTheme.typography.labelMedium,
                color = tokens.colors.textMuted,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = member.title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (showChoose) {
            Text(
                text = stringResource(Res.string.download_action_choose),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onChoose)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun attentionIcon(card: AttentionCard): Pair<ImageVector, Color> {
    val colors = MaterialTheme.nuvio.colors
    return when (card.kind) {
        DownloadNeedsYouKind.STORAGE -> Icons.Rounded.SdCardAlert to colors.danger
        DownloadNeedsYouKind.GAVE_UP -> Icons.Rounded.ErrorOutline to colors.danger
        DownloadNeedsYouKind.MANUAL_PICK -> Icons.Rounded.TouchApp to colors.textSecondary
        DownloadNeedsYouKind.NO_SOURCE_FITS -> when (card.noSourceReason) {
            DownloadEntryDecisionKind.NOTHING_CACHED -> Icons.Rounded.CloudOff to colors.warning
            else -> Icons.Rounded.WarningAmber to colors.warning
        }
    }
}

@Composable
private fun attentionProblem(card: AttentionCard): String = when (card.kind) {
    DownloadNeedsYouKind.NO_SOURCE_FITS -> stringResource(noSourceResource(card.noSourceReason))
    DownloadNeedsYouKind.MANUAL_PICK -> stringResource(Res.string.download_phase_manual_pick)
    DownloadNeedsYouKind.GAVE_UP -> stringResource(Res.string.download_phase_gave_up)
    DownloadNeedsYouKind.STORAGE -> stringResource(Res.string.download_phase_storage)
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

private enum class PillStyle { PRIMARY, SECONDARY }

/**
 * A quiet full-size action: the tonal pill's colours at button height, for a way out of a screen
 * that is not its main task (Choose sources' "Auto-pick remaining").
 */
@Composable
internal fun DownloadsTonalButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 44.dp),
        shape = CircleShape,
        color = tokens.colors.surfaceElevated,
        contentColor = tokens.colors.textPrimary,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

/** A compact action: filled for the one thing to do, tonal for the alternatives. */
@Composable
private fun PillButton(text: String, style: PillStyle, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val container = when (style) {
        PillStyle.PRIMARY -> tokens.colors.accent
        PillStyle.SECONDARY -> tokens.colors.surfaceElevated
    }
    val content = when (style) {
        PillStyle.PRIMARY -> tokens.colors.onAccent
        PillStyle.SECONDARY -> tokens.colors.textPrimary
    }
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = container,
        contentColor = content,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

// --- watched cleanup ---------------------------------------------------------------------------

/** A suggestion, not a problem: one quiet line under the storage bar. */
@Composable
fun DownloadWatchedCleanupCard(cleanup: WatchedCleanup, onReview: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, tokens.colors.borderSubtle),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.DeleteSweep, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = stringResource(Res.string.download_cleanup_title, formatDownloadBytes(cleanup.bytes)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(Res.string.download_cleanup_body, cleanup.items.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onReview) {
                Text(stringResource(Res.string.download_cleanup_action), color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold)
            }
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
 * A season as one row - poster, "Season 2 · 4 left · 2 done", combined progress and the most
 * active episode's plain line - that expands into its episodes, denser and indented under it.
 * A film or a lone episode is a [DownloadQueueItemRow].
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
    val tokens = MaterialTheme.nuvio
    var expanded by rememberSaveable(group.key) { mutableStateOf(initiallyExpanded) }
    val now = tickingNow(group.presentations.any { it.detail.nextRetryAtEpochMs != null })
    val chevron by animateFloatAsState(if (expanded) 180f else 0f)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val indent = if (maxWidth < 480.dp) 0.dp else 4.dp + SeasonPosterWidth + RowGap
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(RowGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DownloadPoster(url = group.poster, title = group.title, width = SeasonPosterWidth)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = group.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = tokens.colors.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Icon(
                            imageVector = Icons.Rounded.ExpandMore,
                            contentDescription = stringResource(
                                if (expanded) Res.string.download_group_episodes_hide else Res.string.download_group_episodes_show,
                            ),
                            tint = tokens.colors.textMuted,
                            modifier = Modifier.size(18.dp).rotate(chevron),
                        )
                    }
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
                        color = tokens.colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = groupStatus(group),
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val progress = group.progressPercent
                    if (group.lead.phase == DownloadUserPhase.DOWNLOADING && progress != null) {
                        ThinProgress(progress / 100f, modifier = Modifier.padding(top = 3.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (group.allPaused) {
                        IconButton(onClick = controls.onResumeAll, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(Res.string.download_action_resume_all), tint = tokens.colors.textPrimary)
                        }
                    } else {
                        IconButton(onClick = controls.onPauseAll, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Rounded.Pause, contentDescription = stringResource(Res.string.download_action_pause_all), tint = tokens.colors.textPrimary)
                        }
                    }
                    GroupMenu(group, controls)
                }
            }
            AnimatedVisibility(visible = expanded) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = indent, top = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = tokens.colors.surfaceCard,
                ) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        group.items.zip(group.presentations).forEachIndexed { index, (item, presentation) ->
                            if (index > 0) HorizontalDivider(Modifier.padding(start = 12.dp), color = tokens.colors.borderSubtle)
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
}

/** The season's own totals while it downloads; otherwise its most active episode's line. */
@Composable
private fun groupStatus(group: DownloadQueueGroup): String {
    val percent = group.progressPercent
    return if (group.lead.phase == DownloadUserPhase.DOWNLOADING && percent != null) {
        stringResource(
            Res.string.download_phase_downloading,
            formatDownloadBytes(group.downloadedBytes),
            formatDownloadBytes(group.knownTotalBytes),
            "$percent%",
        )
    } else {
        group.lead.plainText()
    }
}

@Composable
private fun GroupMenu(group: DownloadQueueGroup, controls: DownloadGroupControls) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Rounded.MoreVert,
                contentDescription = stringResource(Res.string.downloads_queue_actions),
                tint = MaterialTheme.nuvio.colors.textMuted,
            )
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
 * One download in the queue: artwork, title, the plain line, and its one control. Tapping opens
 * the detail. [compact] is the episode row inside an expanded season - no artwork, no show title,
 * the episode number in a fixed column so the titles line up.
 *
 * "Waiting for Wi-Fi" carries its own "Download now anyway" inside the same text column, so the
 * action reads as part of that episode's state rather than as a line between two episodes.
 */
@OptIn(ExperimentalLayoutApi::class)
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
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(if (compact) 0.dp else 12.dp))
            .clickable(onClick = onOpen)
            .padding(
                start = if (compact) 12.dp else 4.dp,
                end = if (compact) 2.dp else 4.dp,
                top = if (compact) 4.dp else 6.dp,
                bottom = if (compact) 4.dp else 6.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else RowGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (compact) {
            Text(
                text = item.episodeNumber?.let { stringResource(Res.string.download_episode_short, it) }.orEmpty(),
                modifier = Modifier.width(30.dp),
                style = MaterialTheme.typography.labelMedium,
                color = tokens.colors.textMuted,
                fontWeight = FontWeight.SemiBold,
            )
        } else {
            DownloadPoster(url = item.posterArt, title = item.title, width = PosterWidth)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (compact) 1.dp else 3.dp)) {
            Text(
                text = if (compact) item.episodeTitle?.trim()?.takeIf { it.isNotBlank() } ?: queueEpisodeLabel(item) else item.title,
                style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
                color = tokens.colors.textPrimary,
                fontWeight = if (compact) FontWeight.Normal else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!compact && item.isEpisode) {
                Text(
                    text = queueEpisodeLabel(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = presentation.plainText(),
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Held back by the mobile-data rule, not broken: the one action worth a button on the row.
                if (presentation.waitReason == DownloadWaitReason.WIFI) {
                    Text(
                        text = stringResource(Res.string.downloads_download_now_anyway),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { DownloadsRepository.allowMobileData(listOf(item.id)) }
                            .padding(vertical = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            val progress = presentation.progressPercent
            if (presentation.phase == DownloadUserPhase.DOWNLOADING && progress != null) {
                ThinProgress(progress / 100f, modifier = Modifier.padding(top = 3.dp))
            }
        }
        val buttonSize = if (compact) 36.dp else 48.dp
        when (presentation.phase) {
            DownloadUserPhase.PAUSED -> IconButton(onClick = onResume, modifier = Modifier.size(buttonSize)) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(Res.string.action_resume), tint = tokens.colors.textPrimary, modifier = Modifier.size(20.dp))
            }
            DownloadUserPhase.COMPLETED, DownloadUserPhase.NEEDS_YOU -> Unit
            else -> IconButton(onClick = onPause, modifier = Modifier.size(buttonSize)) {
                Icon(Icons.Rounded.Pause, contentDescription = stringResource(Res.string.compose_action_pause), tint = tokens.colors.textPrimary, modifier = Modifier.size(20.dp))
            }
        }
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

/** A 3dp bar without Material's stop dot and gap, which read as a second, empty bar. */
@Composable
internal fun ThinProgress(fraction: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.nuvio.colors.accent) {
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
        color = color,
        trackColor = MaterialTheme.nuvio.colors.borderSubtle,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

// --- detail ------------------------------------------------------------------------------------

/** Detail on tap: what it is, the plain line, then the technical facts, then what can be done. */
@OptIn(ExperimentalLayoutApi::class)
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
    val tokens = MaterialTheme.nuvio
    val detail = presentation.detail
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(RowGap), verticalAlignment = Alignment.CenterVertically) {
            DownloadPoster(url = item.posterArt, title = item.title, width = SeasonPosterWidth)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.isEpisode) {
                    Text(
                        text = queueEpisodeLabel(item),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(presentation.plainText(), style = MaterialTheme.typography.labelMedium, color = tokens.colors.textMuted)
            }
        }
        HorizontalDivider(color = tokens.colors.borderSubtle)
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
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            if (presentation.phase == DownloadUserPhase.PAUSED) {
                PillButton(stringResource(Res.string.action_resume), PillStyle.PRIMARY, onResume)
            } else if (presentation.isUnfinished) {
                PillButton(stringResource(Res.string.compose_action_pause), PillStyle.PRIMARY, onPause)
            }
            if (presentation.isUnfinished) {
                PillButton(stringResource(Res.string.download_flow_change), PillStyle.SECONDARY, onChange)
                PillButton(stringResource(Res.string.download_detail_move_to_top), PillStyle.SECONDARY, onDownloadNext)
            }
            Surface(onClick = onDelete, shape = CircleShape, color = tokens.colors.danger.copy(alpha = 0.14f)) {
                Text(
                    text = stringResource(Res.string.action_delete),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = tokens.colors.danger,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    val tokens = MaterialTheme.nuvio
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = label,
            modifier = Modifier.width(96.dp),
            style = MaterialTheme.typography.labelMedium,
            color = tokens.colors.textMuted,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textPrimary,
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
