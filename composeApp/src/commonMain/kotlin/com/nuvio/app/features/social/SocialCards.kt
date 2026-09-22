package com.nuvio.app.features.social

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.features.watchparty.currentEpochMs
import kotlinx.coroutines.delay

/**
 * Social's own presentation, deliberately **not** Continue Watching's.
 *
 *   **Continue Watching** - my active playback. Primary, prominent.
 *   **Friends' activity** - ambient history. Text rows with a mini-poster, see [FriendActivityRow].
 *   **Watching Now** - live and actionable. A card, because there is something to *do*.
 *
 * ⚠ **Only low-level primitives are shared.** [SocialCardArtwork] and `SocialAvatar` are shared;
 * the activity row and [SocialWatchingNowCard] are distinct components above them. One universal card
 * with nullable parameters for every difference is how the `TitlePresentationCard` over-reuse
 * happened in the first place.
 */

/** Home's Watching Now shelf card. The Social feed's own cards are sized by [SocialFeedMetrics]. */
internal val SocialWatchingNowCardWidth = 344.dp

/**
 * A minimum rather than a fixed height: it evens a row of cards out and still grows rather than
 * clipping if a translation runs long. Also the number that keeps Watching Now visibly under a
 * Continue Watching card.
 */
internal val SocialWatchingNowCardHeight = 132.dp

/** Artwork widths. Home's shelf is compact; the Social feed passes the wide one on multi-column. */
internal val SocialWatchingNowArtworkWidth = 116.dp
internal val SocialWatchingNowArtworkWidthWide = 160.dp

/** The phone card's still: 96 x 54, beside the text rather than above it. */
internal val SocialWatchingNowArtworkWidthCompact = 96.dp

/** Below this card width the artwork drops above the text instead of beside it (§2). */
internal val SocialWatchingNowStackedBelow = 340.dp

/** The friends roster beside the feed on a wide window, so the feed's own width excludes it. */
internal val SocialFriendsRailWidth = 360.dp

private val SocialPausedColor = Color(0xFFE3B341)

/**
 * Small 16:9 still. Episode thumbnail, then background, then poster - never a cropped 2:3.
 *
 * [progress] draws Continue Watching's grammar: a 3dp bar along the artwork's bottom edge, which is
 * what frees the text column from carrying its own progress row.
 */
@Composable
internal fun SocialCardArtwork(
    poster: String?,
    background: String?,
    episodeThumbnail: String?,
    width: Dp?,
    progress: Float? = null,
) {
    val candidates = listOfNotNull(episodeThumbnail, background, poster).filter(String::isNotBlank)
    val shape = RoundedCornerShape(NuvioTokens.Radius.compactCard)
    Box(
        (if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .aspectRatio(16f / 9f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        // One latch per candidate, so a dead thumbnail still lets the background try.
        var failed by remember(candidates) { mutableStateOf(0) }
        candidates.getOrNull(failed)?.let { model ->
            NuvioAsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = { failed += 1 },
            )
        }
        if (progress != null) {
            Box(
                Modifier.align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color.Black.copy(alpha = 0.45f)),
            ) {
                Box(
                    Modifier.fillMaxWidth(progress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

/** `S1 E2 · 3AM`: numbering, then the episode title when there is one. */
internal fun watchingNowMetadataLine(season: Int?, episode: Int?, episodeTitle: String?): String =
    buildList {
        when {
            season != null && episode != null -> add("S$season E$episode")
            episode != null -> add("E$episode")
        }
        episodeTitle?.takeIf { it.isNotBlank() }?.let(::add)
    }.joinToString(" · ")

/**
 * "Ana", "Ana & Ben", "Ana, Ben & Cy", or "Ana & 3 others" - friends on the card first, then anyone
 * else in the party this viewer cannot see.
 */
internal fun watchingNowPeopleLabel(item: WatchingNowItem): String {
    val names = (listOf(item.profile) + item.partyCompanions).map { it.displayName.ifBlank { it.handle } }
    val total = maxOf(names.size, item.partyMemberCount?.takeIf { item.partyId != null } ?: 0)
    return when {
        total <= 1 -> names.first()
        total == names.size && total == 2 -> "${names[0]} & ${names[1]}"
        total == names.size && total == 3 -> "${names[0]}, ${names[1]} & ${names[2]}"
        total == 2 -> "${names[0]} & 1 other"
        else -> "${names[0]} & ${total - 1} others"
    }
}

/**
 * A friend, mid-episode, with something to do about it.
 *
 * *Who*, then *what*, then *can I join*, left to right. The identity leads with a 28dp avatar whose
 * dot carries the play state, replacing the uppercase `PAUSED` pill that was the label wrapping one
 * letter per line. The action is a proper tonal button in the corner whose label comes only from
 * [watchingNowJoinAffordance], so the card, the Home shelf and the dock can never disagree about it.
 *
 * ⚠ Two friends on the same episode are two cards. The action belongs to one person's session.
 */
@Composable
internal fun SocialWatchingNowCard(
    item: WatchingNowItem,
    affordance: WatchingNowJoinAffordance,
    onOpen: () -> Unit,
    onJoin: () -> Unit,
    onCancelRequest: () -> Unit,
    modifier: Modifier = Modifier,
    artworkWidth: Dp = SocialWatchingNowArtworkWidth,
    /**
     * Artwork above the text, for a card narrower than [SocialWatchingNowStackedBelow]. Passed in
     * rather than measured: the grid rows ask their cards for intrinsic sizes, which a
     * `BoxWithConstraints` cannot answer.
     */
    stacked: Boolean = false,
    /**
     * Phone density: one row - still, two lines of text, the action on the second line's right.
     * ~84dp for one friend where the stacked card was ~150dp. [SocialFeedMetrics.phone] decides it.
     */
    compact: Boolean = false,
) {
    if (compact) {
        SocialCardSurface(onClick = onOpen, modifier = modifier) {
            CompactWatchingNowBody(item, affordance, artworkWidth, onJoin, onCancelRequest)
        }
        return
    }
    SocialCardSurface(
        onClick = onOpen,
        modifier = modifier.heightIn(min = SocialWatchingNowCardHeight),
    ) {
        run {
            val art: @Composable () -> Unit = {
                SocialCardArtwork(
                    poster = item.poster,
                    background = item.background,
                    episodeThumbnail = item.episodeThumbnail,
                    width = if (stacked) null else artworkWidth,
                    progress = item.progressFraction.takeIf { item.durationMs > 0 },
                )
            }
            if (stacked) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    art()
                    WatchingNowText(item, affordance, onJoin, onCancelRequest)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    art()
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        WatchingNowText(item, affordance, onJoin, onCancelRequest)
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.WatchingNowText(
    item: WatchingNowItem,
    affordance: WatchingNowJoinAffordance,
    onJoin: () -> Unit,
    onCancelRequest: () -> Unit,
) {
    val playing = item.state == SocialPlaybackState.playing
    val companions = item.partyCompanions
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (companions.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy((-14).dp)) {
                companions.take(1).forEach { friend ->
                    Box(
                        Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        SocialAvatar(friend.displayName, friend.avatarUrl, friend.avatarColorHex, 24.dp)
                    }
                }
            }
        }
        Box {
            SocialAvatar(item.profile.displayName, item.profile.avatarUrl, item.profile.avatarColorHex, 28.dp)
            Box(
                Modifier.align(Alignment.BottomEnd)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(7.dp).clip(CircleShape)
                        .background(if (playing) SocialLiveColor else SocialPausedColor),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                watchingNowPeopleLabel(item),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                watchingNowStateLabel(item),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        item.title,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    val meta = watchingNowMetadataLine(item.season, item.episode, item.episodeTitle)
    if (meta.isNotBlank()) {
        Text(
            meta,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (affordance != WatchingNowJoinAffordance.None) {
        Spacer(Modifier.weight(1f).heightIn(min = 6.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomEnd) {
            WatchingNowJoinButton(affordance, onJoin, onCancelRequest)
        }
    }
}

/** "Playing", "Paused", "Watch party", "Party paused". */
internal fun watchingNowStateLabel(item: WatchingNowItem): String {
    val playing = item.state == SocialPlaybackState.playing
    return when {
        item.partyId == null || (item.partyMemberCount ?: 0) < 2 -> if (playing) "Playing" else "Paused"
        playing -> "Watch party"
        else -> "Party paused"
    }
}

/**
 * [SocialWatchingNowCard]'s phone form. The same facts in the same order - who, what, can I join -
 * with the still beside them and the join action sharing the episode line, so a card is two lines
 * of text tall rather than a still plus four lines.
 */
@Composable
private fun CompactWatchingNowBody(
    item: WatchingNowItem,
    affordance: WatchingNowJoinAffordance,
    artworkWidth: Dp,
    onJoin: () -> Unit,
    onCancelRequest: () -> Unit,
) {
    val playing = item.state == SocialPlaybackState.playing
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        SocialCardArtwork(
            poster = item.poster,
            background = item.background,
            episodeThumbnail = item.episodeThumbnail,
            width = artworkWidth,
            progress = item.progressFraction.takeIf { item.durationMs > 0 },
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box {
                    SocialAvatar(item.profile.displayName, item.profile.avatarUrl, item.profile.avatarColorHex, 20.dp)
                    Box(
                        Modifier.align(Alignment.BottomEnd)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(6.dp).clip(CircleShape)
                                .background(if (playing) SocialLiveColor else SocialPausedColor),
                        )
                    }
                }
                Text(
                    watchingNowPeopleLabel(item),
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "· ${watchingNowStateLabel(item)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Text(
                item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = watchingNowMetadataLine(item.season, item.episode, item.episodeTitle)
            if (meta.isNotBlank() || affordance != WatchingNowJoinAffordance.None) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        meta,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    WatchingNowJoinButton(affordance, onJoin, onCancelRequest)
                }
            }
        }
    }
}

/**
 * The one join control. Requested shows a ring draining to the request's expiry and becomes Cancel on
 * hover; Joining spins; In your party is a label, not a button.
 */
@Composable
internal fun WatchingNowJoinButton(
    affordance: WatchingNowJoinAffordance,
    onJoin: () -> Unit,
    onCancelRequest: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val padding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
    when (affordance) {
        WatchingNowJoinAffordance.None -> Unit
        WatchingNowJoinAffordance.InYourParty -> Text(
            "In your party",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(padding),
            maxLines = 1,
            softWrap = false,
        )
        WatchingNowJoinAffordance.Join,
        WatchingNowJoinAffordance.AskToJoin,
        -> FilledTonalButton(onClick = onJoin, contentPadding = padding, modifier = Modifier.heightIn(min = 32.dp)) {
            Icon(Icons.Rounded.Groups, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            JoinLabel(affordance.label.orEmpty())
        }
        WatchingNowJoinAffordance.Joining -> FilledTonalButton(
            onClick = {},
            enabled = false,
            contentPadding = padding,
            modifier = Modifier.heightIn(min = 32.dp),
        ) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            JoinLabel("Joining…")
        }
        is WatchingNowJoinAffordance.Requested -> {
            var nowMs by remember { mutableLongStateOf(currentEpochMs()) }
            LaunchedEffect(affordance.expiresAtMs) {
                while (true) {
                    nowMs = currentEpochMs()
                    delay(1_000)
                }
            }
            val remaining = ((affordance.expiresAtMs - nowMs).toFloat() / OutgoingJoinDefaultLifetimeMs).coerceIn(0f, 1f)
            FilledTonalButton(
                onClick = onCancelRequest,
                enabled = !affordance.cancelling,
                contentPadding = padding,
                modifier = Modifier.heightIn(min = 32.dp).hoverable(interaction),
                colors = if (hovered) {
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                } else {
                    ButtonDefaults.filledTonalButtonColors()
                },
                interactionSource = interaction,
            ) {
                CircularProgressIndicator(
                    progress = { remaining },
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
                )
                Spacer(Modifier.width(8.dp))
                JoinLabel(if (hovered || affordance.cancelling) "Cancel" else "Requested")
            }
        }
    }
}

@Composable
private fun JoinLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        // A guard, not a layout: when a card was once measured at 180dp this label came out one
        // character per line. The next constraint mistake ellipsizes where it can be seen.
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * An empty Watching Now: one muted line with an icon, no bordered box. The old copy promised "start a
 * party on it", which was never the action on offer.
 */
@Composable
internal fun WatchingNowEmptyLine(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.Tv, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "Nobody's watching right now. When a friend presses play, you'll see it here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The container Watching Now sits in. */
@Composable
private fun SocialCardSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(NuvioTokens.Radius.compactCard),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        // ⚠ **Name it.** `surface.copy(alpha = …)` matches no colour-scheme role, so `contentColorFor`
        // falls through to the host's `LocalContentColor` - black on Home, where every card title
        // once rendered invisible. The card owns its content colour.
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
    ) {
        Box(Modifier.fillMaxHeight().padding(10.dp)) { content() }
    }
}

/**
 * Lays Social items out across the width instead of one per row.
 *
 * A `LazyVerticalGrid` cannot be nested in the feed's `LazyColumn`, so rows are chunked here and each
 * chunk is one lazy item, keyed by its first entry so identity survives a column-count change.
 * Weighted cells divide the space a row is actually given; a short final row is padded with empty
 * weights so its cells do not stretch.
 */
internal fun <T> LazyListScope.socialGridItems(
    items: List<T>,
    columns: Int,
    key: (T) -> Any,
    card: @Composable (T, Modifier) -> Unit,
) {
    val rows = items.chunked(columns.coerceAtLeast(1))
    rows.forEach { row ->
        item(key = key(row.first())) {
            // `IntrinsicSize.Min` measures the row to its tallest card and `fillMaxHeight` brings the
            // rest up to it, so a card with a join action and one without still make a row.
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(SocialGridGap),
            ) {
                row.forEach { entry -> card(entry, Modifier.weight(1f).fillMaxHeight()) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
