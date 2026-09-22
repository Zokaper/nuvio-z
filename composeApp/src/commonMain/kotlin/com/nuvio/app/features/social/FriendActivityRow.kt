package com.nuvio.app.features.social

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioTokens

/**
 * Friends' activity as a row of text, not a card.
 *
 * The previous two iterations were card-sizing tweaks, and the complaint was about the *form*: a
 * surface, a 16:9 still and a stacked title in a uniform grid read as a second Continue Watching wall.
 * This row has no surface at rest, and a 2:3 mini-poster instead of a still. A poster keeps its
 * identity at 44dp where a still turns to mush, and its portrait shape is what separates this shelf
 * from Continue Watching's landscape tiles, so the hierarchy comes from the form rather than from
 * making things smaller.
 *
 * ```
 * ┌──┐  (S)(D) Seraph & debug · 2h
 * │▓▓│  Daredevil
 * └──┘  S2 E4–E9 · 6 episodes
 * ```
 *
 * [compact] is the narrow-window form: two lines, with the names and time folded into the third.
 */
internal val FriendActivityPosterWidth = 44.dp
internal val FriendActivityPosterHeight = 66.dp
internal val FriendActivityRowHeight = 66.dp

/** Home shelf row widths by window class (§6). */
internal fun friendActivityRowWidth(windowWidth: Dp): Dp = when {
    windowWidth < 720.dp -> 260.dp
    windowWidth >= 1440.dp -> 300.dp
    else -> 280.dp
}

@Composable
internal fun FriendActivityRow(
    group: FriendActivityGroup,
    nowMs: Long,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(NuvioTokens.Radius.compactCard)
    val time = relativeTimeLabel(group.lastEventMs, nowMs)
    val names = group.friendNamesLabel()
    val context = group.contextLabel()
    Row(
        modifier = modifier
            .clip(shape)
            .background(if (hovered) MaterialTheme.colorScheme.surface.copy(alpha = 0.5f) else Color.Transparent)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
            .padding(end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FriendActivityPoster(group)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (!compact) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SocialAvatarStack(group.stackedFriends, group.avatarOverflow, size = 16.dp)
                    Text(
                        names,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (time.isNotEmpty()) {
                        Text(
                            "· $time",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
            Text(
                group.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                // The row has no surface, so it names its own colour rather than inheriting a host's.
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val third = if (compact) {
                listOf(names, context, time).filter(String::isNotBlank).joinToString(" · ")
            } else {
                context
            }
            if (third.isNotBlank()) {
                Text(
                    third,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Poster, then background, then a monogram tile - never an empty grey box. Each artwork URL has its
 * own failure latch, so a broken poster still gets to try the background.
 */
@Composable
private fun FriendActivityPoster(group: FriendActivityGroup) {
    val shape = RoundedCornerShape(NuvioTokens.Radius.compactCard)
    val candidates = listOfNotNull(group.poster, group.background).filter(String::isNotBlank)
    var failedCount by remember(candidates) { mutableStateOf(0) }
    val model = candidates.getOrNull(failedCount)
    Box(
        Modifier.width(FriendActivityPosterWidth)
            .height(FriendActivityPosterHeight)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (model != null) {
            NuvioAsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = { failedCount += 1 },
            )
        } else {
            Text(
                group.title.trim().take(1).uppercase().ifBlank { "?" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Up to three overlapping avatars, then "+N". */
@Composable
internal fun SocialAvatarStack(
    friends: List<SocialProfileSummary>,
    overflow: Int,
    size: Dp,
) {
    // Negative spacing rather than offsets, so the stack measures to the width it actually draws and
    // the names beside it start where the last avatar ends.
    val overlap = size * 0.35f
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(-overlap)) {
        friends.forEach { friend ->
            Box(
                Modifier.clip(CircleShape)
                    .background(MaterialTheme.colorScheme.background)
                    .padding(1.5.dp),
            ) {
                SocialAvatar(friend.displayName, friend.avatarUrl, friend.avatarColorHex, size)
            }
        }
        if (overflow > 0) {
            Text(
                "+$overflow",
                modifier = Modifier.padding(start = overlap + 3.dp),
                fontSize = (size.value * 0.6f).sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
