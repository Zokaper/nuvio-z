package com.nuvio.app.features.social

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.social_recommended_also_from
import nuvio.composeapp.generated.resources.social_recommended_count
import nuvio.composeapp.generated.resources.social_recommended_empty
import nuvio.composeapp.generated.resources.social_recommended_to_you
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * "Recommended to you" as its own page (2026-10-22). It used to lead the Inbox as the Home shelf's
 * 300dp tiles squeezed into a ~400dp panel; the maintainer moved it out instead: a header button
 * says how many are waiting (with a dot when any are new) and opens this. Opening it marks them seen.
 * Home keeps [SocialRecommendedShelf].
 */
@Composable
internal fun SocialRecommendedPage(
    state: SocialUiState,
    wide: Boolean,
    topInset: Dp,
    onBack: () -> Unit,
    onMarkInboxRead: (Set<String>) -> Unit,
    onOpenContent: (contentType: String, contentId: String, title: String) -> Unit,
) {
    val titles = remember(state.inbox) { recommendedToYou(state.inbox) }
    val unseen = state.inbox.filter { it.readAt == null && it.kind == SocialInboxEvent.Recommendation }.map { it.id }.toSet()
    LaunchedEffect(unseen) { if (unseen.isNotEmpty()) onMarkInboxRead(unseen) }
    val scope = rememberCoroutineScope()
    var confirmDismiss by remember { mutableStateOf<RecommendedTitle?>(null) }
    SocialPageShell(title = stringResource(Res.string.social_recommended_to_you), wide = wide, topInset = topInset, onDismiss = onBack) { horizontal, bottom ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = horizontal, end = horizontal, top = 8.dp, bottom = bottom),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (titles.isEmpty()) item(key = "empty") { SocialNotice(stringResource(Res.string.social_recommended_empty)) }
            titles.forEach { rec ->
                item(key = rec.contentId) {
                    RecommendedCard(rec, Modifier.fillMaxWidth(), { onOpenContent(rec.contentType, rec.contentId, rec.title) }, { confirmDismiss = rec })
                }
            }
        }
    }
    confirmDismiss?.let { rec ->
        RecommendedDismissDialog(
            rec,
            onKeep = { confirmDismiss = null },
            onRemove = {
                confirmDismiss = null
                scope.launch { SocialRepository.dismissInbox(rec.eventIds) }
            },
        )
    }
}

/** The header button: how many are waiting, and a dot while any of them is new. Nothing when there are none. */
@Composable
internal fun SocialRecommendedButton(count: Int, hasNew: Boolean, wide: Boolean, onClick: () -> Unit) {
    if (count <= 0) return
    val label = stringResource(Res.string.social_recommended_to_you)
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.ThumbUp, contentDescription = null, modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurface)
            Text(
                if (wide) pluralStringResource(Res.plurals.social_recommended_count, count, count) else count.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (hasNew) {
            Box(
                Modifier.align(Alignment.TopEnd).size(10.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.background).padding(2.dp)
                    .clip(CircleShape).background(SocialLiveColor),
            )
        }
    }
}

/** One card per title (picked 2026-10-22): the art leads, full width, with the title on it; notes are speech bubbles under it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecommendedCard(rec: RecommendedTitle, modifier: Modifier, onOpen: () -> Unit, onDismiss: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onOpen, onLongClick = onDismiss, role = Role.Button),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            SocialArtwork(rec.artwork(), rec.title, Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))))
            Column(Modifier.align(Alignment.BottomStart).padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(rec.title, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(rec.facts(), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val notes = rec.from.filter { it.note != null }.take(2)
            if (notes.isEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SocialFaces(rec.people, 22.dp)
                    Text(rec.whoLine(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                notes.forEach { from -> NoteBubble(from, Modifier, faceSize = 26.dp) }
                val quiet = rec.from.filter { it.note == null }
                if (quiet.isNotEmpty()) {
                    Text(
                        stringResource(Res.string.social_recommended_also_from, socialPeopleLabel(quiet.map { it.person })),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteBubble(from: RecommendationFrom, modifier: Modifier, faceSize: Dp = 22.dp) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        SocialAvatar(from.person.displayName, from.person.avatarUrl, from.person.avatarColorHex, faceSize)
        Column(
            Modifier.weight(1f, fill = false)
                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 14.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(from.person.displayName, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(from.note.orEmpty(), style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RecommendedTitle.artwork(): List<String?> =
    SocialArtRef(title, contentType, contentId, poster, listOf(background)).artwork()

@Composable
private fun RecommendedTitle.facts(): String =
    rememberSocialTitleArt(contentType, contentId)?.facts(contentType) ?: socialContentTypeLabel(contentType)
