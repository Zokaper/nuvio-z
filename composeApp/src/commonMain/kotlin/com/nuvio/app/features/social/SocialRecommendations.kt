package com.nuvio.app.features.social

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import com.nuvio.app.features.details.components.DetailSecondaryAction
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioToastController
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.social_keep
import nuvio.composeapp.generated.resources.social_pick_someone
import nuvio.composeapp.generated.resources.social_rec_failed
import nuvio.composeapp.generated.resources.social_rec_message
import nuvio.composeapp.generated.resources.social_rec_remove_body
import nuvio.composeapp.generated.resources.social_rec_who
import nuvio.composeapp.generated.resources.social_recommend
import nuvio.composeapp.generated.resources.social_recommend_to
import nuvio.composeapp.generated.resources.social_recommend_to_friends
import nuvio.composeapp.generated.resources.social_recommended_by
import nuvio.composeapp.generated.resources.social_recommended_to_you
import nuvio.composeapp.generated.resources.social_recommended_toast
import nuvio.composeapp.generated.resources.social_recommended_toast_friends
import nuvio.composeapp.generated.resources.social_remove
import nuvio.composeapp.generated.resources.social_remove_title
import nuvio.composeapp.generated.resources.social_search_friends
import nuvio.composeapp.generated.resources.social_sending
import nuvio.composeapp.generated.resources.social_sent
import nuvio.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.getString

/*
 * Recommendations (2026-10-18; decisions and the picked renders in `Nuvio Z/PLAN-social-v2.md`,
 * `SocialRecommendHarness`):
 *
 * - [SocialRecommendSheet]: details page -> three-dot menu -> Recommend. Friends as a face grid on a
 *   phone, a list on a wide window; Sent for anyone who already has this title from you; an optional
 *   message; one button.
 * - [SocialRecommendedShelf]: "Recommended to you" at the top of the Inbox - one A+ tile per title (art,
 *   facts, up to two notes), every friend who recommended it on it. Leaves when you start watching it
 *   (server) or dismiss it (long-press). Home can carry the same tiles as a shelf of its own
 *   (`homeRecommendedSection`, Social settings -> Home, off by default). Placement picked 2026-10-19.
 * - [SocialRecommendedByLine]: the details page's "Recommended to you by ..." with the notes (M1).
 */

/** One title on the shelf, with everyone who recommended it, newest first. */
internal data class RecommendedTitle(
    val contentType: String,
    val contentId: String,
    val title: String,
    val poster: String?,
    val background: String?,
    val from: List<RecommendationFrom>,
) {
    val people: List<SocialProfileSummary> get() = from.map { it.person }
    val eventIds: Set<String> get() = from.mapTo(LinkedHashSet()) { it.eventId }
}

internal data class RecommendationFrom(val person: SocialProfileSummary, val note: String?, val eventId: String)

/**
 * The shelf, from the inbox: every recommendation still waiting (the state only carries undismissed
 * ones), one entry per title, newest title first. Pure, so the grouping is a test.
 */
internal fun recommendedToYou(inbox: List<SocialInboxEvent>): List<RecommendedTitle> {
    val events = inbox
        .filter { it.kind == SocialInboxEvent.Recommendation && !it.payload.contentId.isNullOrBlank() }
        .sortedByDescending { parseSocialTimestampMs(it.createdAt) ?: 0L }
    val byTitle = LinkedHashMap<String, MutableList<SocialInboxEvent>>()
    events.forEach { byTitle.getOrPut(it.payload.contentId!!) { mutableListOf() } += it }
    return byTitle.values.map { group ->
        val newest = group.first().payload
        RecommendedTitle(
            contentType = newest.contentType ?: "movie",
            contentId = newest.contentId!!,
            title = group.firstNotNullOfOrNull { it.payload.title } ?: "",
            poster = group.firstNotNullOfOrNull { it.payload.poster },
            background = group.firstNotNullOfOrNull { it.payload.background },
            from = group.distinctBy { it.actor.profileId }.map { RecommendationFrom(it.actor, it.payload.note?.takeIf(String::isNotBlank), it.id) },
        )
    }
}

@Composable
internal fun RecommendedTitle.whoLine(): String =
    pluralStringResource(Res.plurals.social_rec_who, from.size, socialPeopleLabel(people))

// --- the shelf (A+) -------------------------------------------------------------------------------

/** The "Recommended to you" shelf for [titles]. Draws nothing while there is nothing waiting. */
@Composable
internal fun SocialRecommendedShelf(
    titles: List<RecommendedTitle>,
    horizontalPadding: Dp,
    wide: Boolean,
    onOpenContent: (contentType: String, contentId: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (titles.isEmpty()) return
    val scope = rememberCoroutineScope()
    SocialRecommendedShelfContent(
        titles = titles,
        horizontalPadding = horizontalPadding,
        wide = wide,
        onOpenContent = onOpenContent,
        onDismiss = { rec -> scope.launch { SocialRepository.dismissInbox(rec.eventIds) } },
        modifier = modifier,
    )
}

/** The shelf itself, for [titles]; [onDismiss] runs after the confirm. Stateless, so harnesses can draw it. */
@Composable
internal fun SocialRecommendedShelfContent(
    titles: List<RecommendedTitle>,
    horizontalPadding: Dp,
    wide: Boolean,
    onOpenContent: (contentType: String, contentId: String, title: String) -> Unit,
    onDismiss: (RecommendedTitle) -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmDismiss by remember { mutableStateOf<RecommendedTitle?>(null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.padding(horizontal = horizontalPadding)) { SocialSectionLabel(stringResource(Res.string.social_recommended_to_you), titles.size) }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            titles.forEach { rec ->
                SocialRecommendedTile(
                    rec,
                    width = if (wide) 300.dp else 260.dp,
                    onOpen = { onOpenContent(rec.contentType, rec.contentId, rec.title) },
                    onDismiss = { confirmDismiss = rec },
                )
            }
        }
    }
    confirmDismiss?.let { rec ->
        RecommendedDismissDialog(
            rec,
            onKeep = { confirmDismiss = null },
            onRemove = {
                confirmDismiss = null
                onDismiss(rec)
            },
        )
    }
}

/** The long-press confirm, shared by the Inbox and Home shelves. */
@Composable
internal fun RecommendedDismissDialog(rec: RecommendedTitle, onKeep: () -> Unit, onRemove: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(stringResource(Res.string.social_remove_title, rec.title)) },
        text = { Text(stringResource(Res.string.social_rec_remove_body, socialPeopleLabel(rec.people))) },
        confirmButton = { TextButton(onClick = onRemove) { Text(stringResource(Res.string.social_remove)) } },
        dismissButton = { TextButton(onClick = onKeep) { Text(stringResource(Res.string.social_keep)) } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SocialRecommendedTile(rec: RecommendedTitle, width: Dp, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val sized = if (width == Dp.Unspecified) Modifier.fillMaxWidth() else Modifier.width(width)
    val art = SocialArtRef(rec.title, rec.contentType, rec.contentId, rec.poster, listOf(rec.background)).artwork()
    val facts = rememberSocialTitleArt(rec.contentType, rec.contentId)?.facts(rec.contentType)
        ?: socialContentTypeLabel(rec.contentType)
    Column(
        sized.combinedClickable(onClick = onOpen, onLongClick = onDismiss, role = Role.Button),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))) {
            SocialArtwork(art, rec.title, Modifier.matchParentSize())
            Box(Modifier.align(Alignment.BottomStart).padding(8.dp)) { SocialFaces(rec.people, 22.dp) }
        }
        Text(rec.title, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "$facts · ${rec.whoLine()}",
            color = Color.White.copy(alpha = 0.5f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        rec.from.filter { it.note != null }.take(2).forEach { from ->
            Text(
                "“${from.note}” — ${from.person.displayName}",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal fun socialContentTypeLabel(contentType: String): String =
    if (contentType.equals("movie", ignoreCase = true)) "Movie" else "Series"

/** "Series · 2017 · 2 seasons", from the metadata the art lookup already loaded. */
internal fun SocialTitleArt.facts(contentType: String): String = listOfNotNull(
    socialContentTypeLabel(contentType),
    releaseInfo?.takeIf(String::isNotBlank),
    seasonCount.takeIf { it > 0 }?.let { if (it == 1) "1 season" else "$it seasons" },
).joinToString(" · ")

// --- the details page (M1) ------------------------------------------------------------------------

/** "Recommended to you by Seraph & ben", and each note under it. Nothing when nobody recommended [contentId]. */
@Composable
internal fun SocialRecommendedByLine(contentId: String, modifier: Modifier = Modifier) {
    if (!rememberSocialEnabled()) return
    val state by SocialRepository.uiState.collectAsStateWithLifecycle()
    val rec = remember(state.inbox, contentId) { recommendedToYou(state.inbox).firstOrNull { it.contentId == contentId } } ?: return
    SocialRecommendedByContent(rec, modifier)
}

/** The line for [rec]. Stateless, so harnesses can draw it. */
@Composable
internal fun SocialRecommendedByContent(rec: RecommendedTitle, modifier: Modifier = Modifier) {
    // The details page hosts this outside any Surface, so the headline would inherit black.
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        SocialRecommendedByRow(rec, modifier)
    }
}

@Composable
private fun SocialRecommendedByRow(rec: RecommendedTitle, modifier: Modifier) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(2.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(SocialLiveColor.copy(alpha = 0.8f)))
        Column(Modifier.padding(start = 12.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SocialFaces(rec.people, 20.dp)
                Text(
                    stringResource(Res.string.social_recommended_by, socialPeopleLabel(rec.people)),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            rec.from.filter { it.note != null }.forEach { from ->
                Text(
                    "“${from.note}” — ${from.person.displayName}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// --- the picker -------------------------------------------------------------------------------------

/** What is being recommended: a movie, or a series as the show. */
data class SocialRecommendContent(
    val contentType: String,
    val contentId: String,
    val title: String,
    val poster: String?,
    val background: String?,
)

/**
 * The details page's Recommend action, or null when there is nothing to offer. Provided by each repo's
 * `MetaDetailsScreen`, read by its action rows through [socialRecommendAction], so neither upstream-owned
 * row needs a new parameter.
 */
val LocalSocialRecommendAction = staticCompositionLocalOf<(() -> Unit)?> { null }

/** The three-dot row's Recommend entry, when [LocalSocialRecommendAction] has one. */
@Composable
fun socialRecommendAction(): DetailSecondaryAction? = LocalSocialRecommendAction.current?.let { onClick ->
    DetailSecondaryAction(label = stringResource(Res.string.social_recommend), icon = Icons.AutoMirrored.Rounded.Send, onClick = onClick)
}

/** Whether this profile can recommend at all: Social on, and someone to send to. */
@Composable
fun rememberCanRecommend(): Boolean {
    if (!rememberSocialEnabled()) return false
    val state by SocialRepository.uiState.collectAsStateWithLifecycle()
    return state.friends.isNotEmpty() && state.socialV2Backend
}

/** The Recommend sheet. [wide] picks the desktop list over the phone's face grid. */
@Composable
fun SocialRecommendSheet(content: SocialRecommendContent, wide: Boolean, onDismiss: () -> Unit) {
    val state by SocialRepository.uiState.collectAsStateWithLifecycle()
    SocialRecommendSheetContent(content, state.friends, wide, onDismiss)
}

/** The sheet for [allFriends]; split from [SocialRecommendSheet] so a render harness can supply the friends. */
@Composable
internal fun SocialRecommendSheetContent(content: SocialRecommendContent, allFriends: List<SocialProfileSummary>, wide: Boolean, onDismiss: () -> Unit) {
    val friends = remember(allFriends) { allFriends.sortedBy { it.displayName.ifBlank { it.handle }.lowercase() } }
    var sentTo by remember(content.contentId) { mutableStateOf<Set<String>>(emptySet()) }
    var picked by remember(content.contentId) { mutableStateOf<Set<String>>(emptySet()) }
    var note by remember(content.contentId) { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(content.contentId) {
        SocialRepository.recommendationSentTo(content.contentId).onSuccess { sentTo = it }
    }
    val shown = friends.filter { friend ->
        query.isBlank() || friend.displayName.contains(query, ignoreCase = true) || friend.handle.contains(query, ignoreCase = true)
    }
    val toggle: (SocialProfileSummary) -> Unit = { friend ->
        if (friend.profileId !in sentTo) picked = if (friend.profileId in picked) picked - friend.profileId else picked + friend.profileId
    }
    SocialPickerFrame(
        wide = wide,
        onDismiss = onDismiss,
        header = {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(96.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))) {
                    SocialArtwork(
                        SocialArtRef(content.title, content.contentType, content.contentId, content.poster, listOf(content.background)).artwork(),
                        content.title,
                        Modifier.matchParentSize(),
                    )
                }
                Column {
                    Text(stringResource(Res.string.social_recommend), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(content.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (friends.size > 8) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(Res.string.social_search_friends)) },
                    shape = RoundedCornerShape(12.dp),
                )
            }
        },
        list = {
            if (wide) {
                shown.forEach { friend -> RecommendFriendRow(friend, friend.profileId in picked, friend.profileId in sentTo) { toggle(friend) } }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    shown.chunked(4).forEach { row ->
                        Row(Modifier.fillMaxWidth()) {
                            row.forEach { friend ->
                                RecommendFriendFace(friend, friend.profileId in picked, friend.profileId in sentTo, Modifier.weight(1f)) { toggle(friend) }
                            }
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        },
        footer = {
            OutlinedTextField(
                value = note,
                onValueChange = { if (it.length <= RecommendNoteLimit) note = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
                placeholder = { Text(stringResource(Res.string.social_rec_message)) },
                supportingText = { Text("${note.length} / $RecommendNoteLimit") },
                shape = RoundedCornerShape(14.dp),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            SocialPillButton(
                when {
                    sending -> stringResource(Res.string.social_sending)
                    picked.isEmpty() -> stringResource(Res.string.social_pick_someone)
                    picked.size == 1 -> friends.firstOrNull { it.profileId in picked }?.displayName
                        ?.let { stringResource(Res.string.social_recommend_to, it) }
                        ?: pluralStringResource(Res.plurals.social_recommend_to_friends, 1, 1)
                    else -> pluralStringResource(Res.plurals.social_recommend_to_friends, picked.size, picked.size)
                },
                onClick = {
                    sending = true
                    error = null
                    scope.launch {
                        SocialRepository.recommendTitle(
                            picked,
                            SocialInboxPayload(
                                contentId = content.contentId,
                                contentType = content.contentType,
                                title = content.title,
                                poster = content.poster,
                                background = content.background,
                            ),
                            note.trim().ifBlank { null },
                        ).onSuccess { delivered ->
                            NuvioToastController.show(if (delivered == 1) getString(Res.string.social_recommended_toast) else getString(Res.string.social_recommended_toast_friends, delivered))
                            onDismiss()
                        }.onFailure {
                            error = it.message ?: getString(Res.string.social_rec_failed)
                            sending = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                primary = true,
                compact = false,
                enabled = picked.isNotEmpty() && !sending,
            )
        },
    )
}

@Composable
private fun RecommendFriendRow(friend: SocialProfileSummary, picked: Boolean, sent: Boolean, onToggle: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable(enabled = !sent, role = Role.Checkbox, onClick = onToggle)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SocialAvatar(friend.displayName, friend.avatarUrl, friend.avatarColorHex, 40.dp)
        Column(Modifier.weight(1f)) {
            Text(friend.displayName.ifBlank { friend.handle }, fontWeight = FontWeight.SemiBold, color = if (sent) muted else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("@${friend.handle}", style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (sent) Text(stringResource(Res.string.social_sent), style = MaterialTheme.typography.labelLarge, color = muted) else RecommendCheck(picked)
    }
}

@Composable
private fun RecommendFriendFace(friend: SocialProfileSummary, picked: Boolean, sent: Boolean, modifier: Modifier, onToggle: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = !sent, role = Role.Checkbox, onClick = onToggle).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            Box(
                Modifier.size(64.dp).clip(CircleShape).then(if (picked) Modifier.border(3.dp, Color.White, CircleShape) else Modifier),
                contentAlignment = Alignment.Center,
            ) { SocialAvatar(friend.displayName, friend.avatarUrl, friend.avatarColorHex, if (picked) 56.dp else 64.dp) }
            if (picked) Box(Modifier.align(Alignment.BottomEnd)) { RecommendCheck(true, 22.dp) }
        }
        Text(
            if (sent) stringResource(Res.string.social_sent) else friend.displayName.ifBlank { friend.handle },
            style = MaterialTheme.typography.labelMedium,
            color = if (sent) muted else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (picked) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RecommendCheck(on: Boolean, size: Dp = 24.dp) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (on) Color.White else Color.Transparent)
            .then(if (on) Modifier else Modifier.border(2.dp, Color.White.copy(alpha = 0.4f), CircleShape)),
        contentAlignment = Alignment.Center,
    ) { if (on) Text("✓", color = Color.Black, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium) }
}

private const val RecommendNoteLimit = 280
