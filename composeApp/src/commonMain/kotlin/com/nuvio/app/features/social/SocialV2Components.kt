package com.nuvio.app.features.social

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioAsyncImage

/*
 * Social V2's card family (PLAN-social-v2.md, Stage 0 decisions of 2026-10-05).
 *
 * - Watching Now is a backdrop card whose whole surface is the action: a tap opens the join sheet,
 *   a long-press or the info dot opens details. The "▶ Join" line is a hint, not a button.
 * - Recently watched is a 16:9 still beside a sentence that names the person first.
 * - Home gets the backdrop card again, smaller than Continue Watching, with the action folded into
 *   its status chip.
 *
 * ⚠ No small portrait posters. Every iteration that used one was rejected on sight.
 */

internal val SocialWatchingCardHeight = 164.dp
private val SocialCardShape = RoundedCornerShape(20.dp)
private val SocialAwayColor = Color(0xFFE6B341)

// --- artwork ---------------------------------------------------------------------------------

/**
 * Each URL in turn, then a gradient keyed on [seed]. Never a grey box: a title with no artwork still
 * reads as *a* picture, and the same title always gets the same one.
 */
@Composable
internal fun SocialArtwork(urls: List<String?>, seed: String, modifier: Modifier = Modifier) {
    val candidates = remember(urls) { urls.filterNotNull().filter(String::isNotBlank).distinct() }
    var failed by remember(candidates) { mutableStateOf(0) }
    val model = candidates.getOrNull(failed)
    Box(modifier) {
        SocialArtworkFallback(seed, Modifier.matchParentSize())
        if (model != null) {
            NuvioAsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
                onError = { failed += 1 },
            )
        }
    }
}

@Composable
private fun SocialArtworkFallback(seed: String, modifier: Modifier) {
    val h = seed.hashCode()
    val hue = ((h.toLong() and 0x7fffffffL) % 360L).toFloat()
    val top = Color.hsv(hue, 0.5f, 0.5f)
    val bottom = Color.hsv((hue + 38f) % 360f, 0.65f, 0.16f)
    val glow = Color.hsv((hue + 170f) % 360f, 0.4f, 0.9f)
    val gx = 0.15f + ((h ushr 3) and 0xFF) / 255f * 0.7f
    val gy = 0.1f + ((h ushr 11) and 0xFF) / 255f * 0.5f
    val gr = 0.35f + ((h ushr 19) and 0x7F) / 127f * 0.4f
    Canvas(modifier) {
        drawRect(Brush.linearGradient(listOf(top, bottom), start = Offset.Zero, end = Offset(size.width, size.height)))
        val center = Offset(size.width * gx, size.height * gy)
        drawCircle(
            Brush.radialGradient(listOf(glow.copy(alpha = 0.35f), Color.Transparent), center = center, radius = size.maxDimension * gr),
            radius = size.maxDimension * gr,
            center = center,
        )
    }
}

/** Darkens the lower part of a card for the text on it, and a little of the top for the chips. */
@Composable
private fun SocialScrim(modifier: Modifier) {
    Box(
        modifier.background(
            Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color.Black.copy(alpha = 0.22f),
                    0.36f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.92f),
                ),
            ),
        ),
    )
}

// --- small pieces ----------------------------------------------------------------------------

/** One avatar with a live ring, or the overlapping stack for a group. */
@Composable
internal fun SocialFaces(people: List<SocialProfileSummary>, size: Dp, liveRing: Boolean = false) {
    if (people.size > 1) {
        SocialAvatarStack(people.take(3), (people.size - 3).coerceAtLeast(0), size * 0.8f)
        return
    }
    val person = people.firstOrNull() ?: return
    Box(Modifier.size(size + if (liveRing) 7.dp else 0.dp), contentAlignment = Alignment.Center) {
        if (liveRing) Box(Modifier.fillMaxSize().border(2.dp, SocialLiveColor, CircleShape))
        SocialAvatar(person.displayName, person.avatarUrl, person.avatarColorHex, size)
    }
}

@Composable
private fun SocialGlassChip(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(999.dp)).background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun SocialStateDot(playing: Boolean, size: Dp = 7.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(if (playing) SocialLiveColor else SocialAwayColor))
}

/** The visible way into details on a card whose tap joins. Long-press does the same. */
@Composable
internal fun SocialInfoDot(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(28.dp).clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Details" },
        contentAlignment = Alignment.Center,
    ) {
        Text("i", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SocialPlayGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 9.dp, height = 10.dp)) {
        drawPath(
            Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, size.height / 2f)
                lineTo(0f, size.height)
                close()
            },
            color,
        )
    }
}

/** What a tap on the card will do. Not a button: the card is the target. */
@Composable
private fun SocialJoinHint(affordance: WatchingNowJoinAffordance, modifier: Modifier = Modifier) {
    val label = affordance.label ?: return
    val actionable = affordance == WatchingNowJoinAffordance.Join || affordance == WatchingNowJoinAffordance.AskToJoin
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (actionable) SocialPlayGlyph(SocialLiveColor)
        Text(
            label,
            color = if (actionable) Color.White else Color.White.copy(alpha = 0.75f),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** A pill-shaped button. Primary is the white one; secondary is a translucent fill that shows on any surface. */
@Composable
internal fun SocialPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    compact: Boolean = true,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val background = when {
        primary -> Color.White
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
    }
    val foreground = when {
        primary -> Color.Black
        danger -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier.clip(RoundedCornerShape(999.dp))
            .background(if (enabled) background else background.copy(alpha = background.alpha * 0.5f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (compact) 14.dp else 20.dp, vertical = if (compact) 7.dp else 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (enabled) foreground else foreground.copy(alpha = 0.5f),
            style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** "Inbox (2)" - the persistent way into notifications, top-right of every Social layout. */
@Composable
internal fun SocialInboxButton(unread: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Inbox", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        if (unread > 0) {
            Box(
                Modifier.size(18.dp).clip(CircleShape).background(SocialLiveColor),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (unread > 9) "9+" else unread.toString(),
                    color = Color.Black,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

internal enum class SocialTab { Activity, Friends }

@Composable
internal fun SocialSegmentedTabs(selected: SocialTab, onSelect: (SocialTab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(3.dp),
    ) {
        SocialTab.entries.forEach { tab ->
            val isSelected = tab == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(999.dp))
                    .background(if (isSelected) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.14f) else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(tab) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    tab.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "● WATCHING NOW 4", "TODAY". Small caps-style labels that separate without boxing. */
@Composable
internal fun SocialSectionLabel(text: String, count: Int? = null, live: Boolean = false, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (live) Box(Modifier.size(7.dp).clip(CircleShape).background(SocialLiveColor))
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (count != null) {
            Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** What the Social nav item's badge counts: actionable, unread inbox items. Zero while Social is off. */
@Composable
internal fun socialUnreadCount(): Int {
    val state by SocialRepository.uiState.collectAsStateWithLifecycle()
    return state.unreadCount
}

// --- labels ----------------------------------------------------------------------------------

internal fun socialPeopleLabel(people: List<SocialProfileSummary>): String {
    val names = people.map { it.displayName.ifBlank { it.handle } }
    return when {
        names.size <= 1 -> names.firstOrNull().orEmpty()
        names.size == 2 -> "${names[0]} & ${names[1]}"
        names.size == 3 -> "${names[0]}, ${names[1]} & ${names[2]}"
        else -> "${names[0]} + ${names.size - 1}"
    }
}

internal fun WatchingNowItem.socialPeople(): List<SocialProfileSummary> = listOf(profile) + partyCompanions

internal fun WatchingNowItem.socialSentence(): String {
    val people = socialPeople()
    return "${socialPeopleLabel(people)} ${if (people.size > 1) "are" else "is"} watching"
}

/** "S1 E2 · Two Dead Men", "Movie". */
internal fun WatchingNowItem.socialMeta(): String {
    val code = when {
        season != null && episode != null -> "S$season E$episode"
        episode != null -> "E$episode"
        contentType.lowercase() == "movie" -> "Movie"
        else -> ""
    }
    return listOfNotNull(code.ifBlank { null }, episodeTitle?.takeIf(String::isNotBlank)).joinToString(" · ")
}

internal fun WatchingNowItem.socialStatusLabel(): String = when {
    partyCompanions.isNotEmpty() || (partyMemberCount ?: 0) > 1 -> "WATCH PARTY"
    state == SocialPlaybackState.playing -> "LIVE"
    else -> "PAUSED"
}

internal fun socialAgo(time: String): String = when {
    time.isBlank() -> ""
    time == "Just now" -> time
    else -> "$time ago"
}

// --- Watching Now: the backdrop card ---------------------------------------------------------

/**
 * One friend's (or one party's) live session. [onClick] opens the join sheet when there is anything to
 * join, and details otherwise - the caller decides from [affordance]; [onDetails] is long-press and the
 * info dot.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SocialWatchingCard(
    item: WatchingNowItem,
    affordance: WatchingNowJoinAffordance,
    onClick: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
    /** Null: 16:9, the backdrop's own shape (the Social tab). A fixed height crops it to a strip. */
    height: Dp? = SocialWatchingCardHeight,
) {
    val playing = item.state == SocialPlaybackState.playing
    val people = item.socialPeople()
    Box(
        modifier.then(if (height != null) Modifier.height(height) else Modifier.aspectRatio(16f / 9f)).clip(SocialCardShape)
            .pointerHoverIcon(PointerIcon.Hand)
            .combinedClickable(onClick = onClick, onLongClick = onDetails, role = Role.Button),
    ) {
        SocialArtwork(listOf(item.background, item.episodeThumbnail, item.poster), item.title, Modifier.matchParentSize())
        SocialScrim(Modifier.matchParentSize())
        SocialGlassChip(Modifier.align(Alignment.TopStart).padding(12.dp)) {
            SocialStateDot(playing)
            Text(
                item.socialStatusLabel(),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
            )
        }
        SocialInfoDot(onDetails, Modifier.align(Alignment.TopEnd).padding(10.dp))
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            SocialFaces(people, 38.dp, liveRing = playing)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    item.socialSentence(),
                    color = Color.White.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    item.title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = item.socialMeta()
                if (meta.isNotBlank()) {
                    Text(
                        meta,
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            SocialJoinHint(affordance, Modifier.padding(bottom = 2.dp))
        }
        SocialProgressEdge(item.progressFraction, Modifier.align(Alignment.BottomStart))
    }
}

@Composable
private fun SocialProgressEdge(fraction: Float, modifier: Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.18f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp).background(SocialLiveColor))
    }
}

// --- Recently watched: the landscape still row ------------------------------------------------

/**
 * "**Seraph** watched / Daredevil / S2 E4–E9 · 3 episodes · 3h ago" beside a 16:9 still with the
 * avatars on its corner. The still scales with the row (~38%, capped) so a 320dp phone keeps room for
 * the words. [showNames] is off on a friend's own profile, where the name would only repeat.
 */
@Composable
internal fun FriendActivityStillRow(
    group: FriendActivityGroup,
    nowMs: Long,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    showNames: Boolean = true,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val time = socialAgo(relativeTimeLabel(group.lastEventMs, nowMs))
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.fillMaxWidth(0.38f).widthIn(max = 168.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))) {
            SocialArtwork(
                group.artwork(),
                group.title,
                Modifier.matchParentSize(),
            )
            if (showNames) {
                Box(Modifier.align(Alignment.BottomStart).padding(6.dp)) { SocialFaces(group.friends, 22.dp) }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (showNames) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold)) {
                            append(group.friendNamesLabel())
                        }
                        append(" watched")
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                group.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOf(group.contextLabel(), time).filter(String::isNotBlank).joinToString(" · ")
            if (sub.isNotBlank()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// --- Recently watched: the timeline of piles --------------------------------------------------

/**
 * Columns of the Recently watched grid for a content area [width] wide: two on a phone, then as many
 * ~200dp cards as fit. Watching Now reads the same number, so a live card is exactly two cells wide.
 */
internal fun socialPileColumns(width: Dp): Int =
    if (width < SocialPilePhoneBelow) 2 else ((width + SocialPileGap) / (SocialPileMinCell + SocialPileGap)).toInt().coerceIn(3, 6)

internal fun socialPileGap(columns: Int): Dp = if (columns <= 2) SocialPileGapPhone else SocialPileGap

internal val SocialPilePhoneBelow = 600.dp
internal val SocialPileMinCell = 200.dp
internal val SocialPileGap = 18.dp
internal val SocialPileGapPhone = 16.dp
private val SocialPileShift = 22.dp

/**
 * One timeline card (2026-10-06 redesign, chosen from rendered candidates A-F): the art is a deck of
 * the entry's stills, the words sit under it. One friend's titles in a bucket are one card; a title
 * several friends watched is its own. Replaces the per-title still rows, which put fourteen identical
 * tiles and "faisal watched" eight times on one screen.
 */
@Composable
internal fun SocialActivityPileCard(
    entry: FriendActivityEntry,
    nowMs: Long,
    compact: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hovered: Boolean = false,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val strong = MaterialTheme.colorScheme.onBackground
    val titles = entry.titles
    val spread by animateFloatAsState(if (hovered) 1f else 0f, tween(220), label = "pile-spread")
    Column(
        // ⚠ No rounded clip on the card: its corner sliced the first letter off the last text line.
        modifier.pointerHoverIcon(PointerIcon.Hand)
            .clickable(role = Role.Button, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SocialPile(titles, spread = spread)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            SocialAvatarStack(entry.people.take(3), (entry.people.size - 3).coerceAtLeast(0), if (compact) 20.dp else 22.dp)
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = strong, fontWeight = FontWeight.SemiBold)) { append(entry.peopleLabel()) }
                    val time = relativeTimeLabel(entry.lastEventMs, nowMs)
                    if (time.isNotBlank()) withStyle(SpanStyle(color = muted)) { append(" · $time") }
                },
                style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val single = titles.singleOrNull()
        Text(
            single?.title ?: entry.countLabel(),
            style = MaterialTheme.typography.bodyMedium,
            color = strong,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val detail = single?.contextLabel() ?: titles.joinToString(", ") { it.title }
        if (detail.isNotBlank()) {
            Text(
                detail,
                style = MaterialTheme.typography.labelMedium,
                color = muted,
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Up to three stills as a fanned deck, like a hand of cards: the newest in front, older ones behind
 * and to the right, each a step smaller, tilted up and slightly darker, pivoting near the bottom-left.
 * [spread] 0 is rest; 1 (hover) spins them further out and lifts the front still. Every pile keeps
 * the same 16:9 footprint, so a line of cards lines up whatever the counts; the spin happens in the
 * draw layer and may overhang its cell, which is why the grid raises a hovered card.
 *
 * 2026-10-06: the first fan (12dp steps, no tilt) was "too subtle" on a real screen.
 */
@Composable
internal fun SocialPile(titles: List<FriendActivityGroup>, modifier: Modifier = Modifier, spread: Float = 0f) {
    val shown = titles.take(3)
    val back = (shown.size - 1).coerceAtLeast(0)
    BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
        val cardWidth = maxWidth - SocialPileShift * back
        shown.indices.reversed().forEach { depth ->
            val group = shown[depth]
            key(group.contentId) {
                Box(
                    Modifier.width(cardWidth).fillMaxHeight()
                        .graphicsLayer {
                            transformOrigin = TransformOrigin(0.3f, 1f)
                            if (depth == 0) {
                                val lift = 1f + 0.03f * spread
                                scaleX = lift
                                scaleY = lift
                                rotationZ = 1.5f * spread
                                translationY = -4.dp.toPx() * spread
                            } else {
                                translationX = (SocialPileShift.value + 22f * spread).dp.toPx() * depth
                                translationY = -(4f + 4f * spread).dp.toPx() * depth
                                rotationZ = -(4f + 5f * spread) * depth
                                val scale = 1f - 0.06f * depth
                                scaleX = scale
                                scaleY = scale
                            }
                            shadowElevation = 10.dp.toPx()
                            shape = SocialPileShape
                            clip = true
                        },
                ) {
                    SocialArtwork(group.artwork(), group.title, Modifier.matchParentSize())
                    if (depth > 0) {
                        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = (0.12f + 0.1f * depth) * (1f - 0.6f * spread))))
                    }
                }
            }
        }
    }
}

private val SocialPileShape = RoundedCornerShape(12.dp)

// --- Home: the quieter tile ------------------------------------------------------------------

/** Live first on Home: the backdrop card at shelf size, the action folded into the status chip. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SocialHomeLiveTile(
    item: WatchingNowItem,
    affordance: WatchingNowJoinAffordance,
    onClick: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playing = item.state == SocialPlaybackState.playing
    SocialTileFrame(
        art = listOf(item.background, item.episodeThumbnail, item.poster),
        seed = item.title,
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onDetails, role = Role.Button),
        progress = item.progressFraction,
        topStart = {
            SocialGlassChip {
                SocialStateDot(playing, 6.dp)
                Text(
                    if (item.socialStatusLabel() == "WATCH PARTY") "PARTY" else item.socialStatusLabel(),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                )
                affordance.label?.let {
                    Text(
                        "· $it",
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        },
        topEnd = { SocialInfoDot(onDetails) },
        people = item.socialPeople(),
        liveRing = playing,
        title = item.title,
        subtitle = listOf(socialPeopleLabel(item.socialPeople()), item.socialMeta()).filter(String::isNotBlank).joinToString(" · "),
    )
}

/** Recent activity on the Home shelf, in the same frame as the live tiles. */
@Composable
internal fun SocialHomeActivityTile(
    group: FriendActivityGroup,
    nowMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val time = socialAgo(relativeTimeLabel(group.lastEventMs, nowMs))
    SocialTileFrame(
        art = group.artwork(),
        seed = group.title,
        modifier = modifier.clickable(role = Role.Button, onClick = onClick),
        progress = null,
        topStart = {},
        topEnd = {
            if (time.isNotBlank()) {
                Text(time, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            }
        },
        people = group.friends,
        liveRing = false,
        title = group.title,
        subtitle = listOf(group.friendNamesLabel(), group.contextLabel()).filter(String::isNotBlank).joinToString(" · "),
    )
}

@Composable
private fun SocialTileFrame(
    art: List<String?>,
    seed: String,
    modifier: Modifier,
    progress: Float?,
    topStart: @Composable () -> Unit,
    topEnd: @Composable () -> Unit,
    people: List<SocialProfileSummary>,
    liveRing: Boolean,
    title: String,
    subtitle: String,
) {
    Box(modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).pointerHoverIcon(PointerIcon.Hand)) {
        SocialArtwork(art, seed, Modifier.matchParentSize())
        SocialScrim(Modifier.matchParentSize())
        Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            topStart()
            Spacer(Modifier.weight(1f))
            topEnd()
        }
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 11.dp, end = 11.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SocialFaces(people, 28.dp, liveRing)
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) {
                    Text(subtitle, color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (progress != null) SocialProgressEdge(progress, Modifier.align(Alignment.BottomStart))
    }
}

// --- people ----------------------------------------------------------------------------------

/**
 * A person in the Friends view: avatar (ringed when live), name, one line under it, then either
 * actions under the text ([below], which keeps a 320dp row from squeezing the name) or a trailing slot.
 */
@Composable
internal fun SocialPersonLine(
    person: SocialProfileSummary,
    subtitle: String,
    modifier: Modifier = Modifier,
    live: Boolean = false,
    onClick: (() -> Unit)? = null,
    below: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(50.dp), contentAlignment = Alignment.Center) {
            if (live) Box(Modifier.fillMaxSize().border(2.dp, SocialLiveColor, CircleShape))
            SocialAvatar(person.displayName, person.avatarUrl, person.avatarColorHex, 42.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(
                person.displayName.ifBlank { "@${person.handle}" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (live) SocialLiveColor else muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (below != null) {
                Spacer(Modifier.height(8.dp))
                below()
            }
        }
        when {
            trailing != null -> trailing()
            below == null && onClick != null -> Text("›", style = MaterialTheme.typography.titleLarge, color = muted)
        }
    }
}

/** A fixed-width still used by the Watched-together strip and the join sheet. */
@Composable
internal fun SocialStill(urls: List<String?>, seed: String, width: Dp, modifier: Modifier = Modifier, progress: Float? = null) {
    Box(modifier.width(width).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))) {
        SocialArtwork(urls, seed, Modifier.matchParentSize())
        if (progress != null) SocialProgressEdge(progress, Modifier.align(Alignment.BottomStart))
    }
}
