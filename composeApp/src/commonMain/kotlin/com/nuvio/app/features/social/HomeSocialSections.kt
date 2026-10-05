package com.nuvio.app.features.social

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.watchparty.WatchPartyState
import com.nuvio.app.features.watchparty.currentEpochMs

/**
 * What friends are up to, on Home, **underneath** the user's own Continue Watching.
 *
 * Social V2: one "Friends" shelf instead of two unrelated ones (a boxed Watching Now card beside bare
 * text rows). Live sessions lead as backdrop tiles - tap opens the join sheet, long-press or the info
 * dot opens the title - and recent activity follows in the same frame. Every tile is smaller than a
 * Continue Watching tile, so the shelf reads as secondary. Nothing is drawn when there is nothing to
 * show.
 */
fun LazyListScope.homeSocialSections(
    watchingNow: List<WatchingNowItem>,
    activity: List<RecentActivityRun>,
    sectionPadding: Dp,
    /** The width Home is laid out in, for the tile width. */
    availableWidth: Dp,
    watchPartyEnabled: Boolean = false,
    outgoingRequest: OutgoingJoinRequestState = OutgoingJoinRequestState.Idle,
    heldParty: WatchPartyState? = null,
    // ⚠ **Required, deliberately.** This defaulted to `{}` and Home never passed it, so Home's
    // Watching Now showed a live "Join" / "Ask to join" button that did nothing at all - no RPC, no
    // request row, nothing on either client. Hardware Bug 6 (2026-09-15). An action that is offered
    // must be wired; there is no default that is correct.
    onStartParty: (WatchingNowItem) -> Unit,
    onCancelJoinRequest: () -> Unit = {},
    /** "See all", which opens the Social tab. Null hides it. */
    onSeeAllActivity: (() -> Unit)? = null,
    onOpenContent: (contentType: String, contentId: String, title: String) -> Unit,
) {
    if (watchingNow.isEmpty() && activity.isEmpty()) return
    item(key = "z-social-friends") {
        val live = remember(watchingNow) { orderWatchingNowForDisplay(watchingNow).take(SocialHomeItemLimit) }
        val groups = remember(activity) { groupFriendActivity(activity).take(FriendActivityHomeGroupLimit) }
        val nowMs = remember(activity) { currentEpochMs() }
        val tileWidth = socialHomeTileWidth(availableWidth)
        val wide = availableWidth >= 1040.dp
        var sheetFor by remember { mutableStateOf<String?>(null) }
        val affordanceOf: (WatchingNowItem) -> WatchingNowJoinAffordance = { item ->
            if (watchPartyEnabled) watchingNowJoinAffordance(item, outgoingRequest, heldParty) else WatchingNowJoinAffordance.None
        }
        val entries: List<Any> = live + groups
        SocialHomeShelf(
            title = "Friends",
            sectionPadding = sectionPadding,
            items = entries,
            key = { entry ->
                when (entry) {
                    is WatchingNowItem -> "live:${entry.socialSessionKey()}"
                    is FriendActivityGroup -> "recent:${entry.contentId}"
                    else -> entry.hashCode()
                }
            },
            itemSpacing = 12.dp,
            trailing = onSeeAllActivity?.let { open -> { TextButton(onClick = open) { Text("See all") } } },
        ) { entry ->
            when (entry) {
                is WatchingNowItem -> SocialHomeLiveTile(
                    item = entry,
                    affordance = affordanceOf(entry),
                    onClick = {
                        when (affordanceOf(entry)) {
                            WatchingNowJoinAffordance.Join,
                            WatchingNowJoinAffordance.AskToJoin,
                            is WatchingNowJoinAffordance.Requested,
                            -> sheetFor = entry.socialSessionKey()
                            else -> onOpenContent(entry.contentType, entry.contentId, entry.title)
                        }
                    },
                    onDetails = { onOpenContent(entry.contentType, entry.contentId, entry.title) },
                    modifier = Modifier.width(tileWidth),
                )
                is FriendActivityGroup -> SocialHomeActivityTile(
                    group = entry,
                    nowMs = nowMs,
                    onClick = { onOpenContent(entry.contentType, entry.contentId, entry.title) },
                    modifier = Modifier.width(tileWidth),
                )
            }
        }
        val sheetItem = sheetFor?.let { key -> live.firstOrNull { it.socialSessionKey() == key } }
        if (sheetItem != null) {
            SocialSheet(wide = wide, onDismiss = { sheetFor = null }) {
                SocialJoinSheetContent(
                    item = sheetItem,
                    affordance = affordanceOf(sheetItem),
                    onJoin = {
                        sheetFor = null
                        onStartParty(sheetItem)
                    },
                    onCancelRequest = {
                        sheetFor = null
                        onCancelJoinRequest()
                    },
                    onDetails = {
                        sheetFor = null
                        onOpenContent(sheetItem.contentType, sheetItem.contentId, sheetItem.title)
                    },
                )
            }
        }
    }
}

/** Home tile width by window class: always below a Continue Watching tile at the same width. */
internal fun socialHomeTileWidth(windowWidth: Dp): Dp = when {
    windowWidth < 720.dp -> 216.dp
    windowWidth >= 1440.dp -> 300.dp
    else -> 272.dp
}

/**
 * One horizontal shelf.
 *
 * The heading is `titleSmall`: this sits below Continue Watching and the catalogue shelves use
 * `titleLarge`, so the heading is the first thing that says the shelf is secondary.
 */
@Composable
internal fun <T> SocialHomeShelf(
    title: String,
    sectionPadding: Dp,
    items: List<T>,
    key: (T) -> Any,
    itemSpacing: Dp,
    trailing: (@Composable () -> Unit)? = null,
    state: LazyListState = rememberLazyListState(),
    card: @Composable (T) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = sectionPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                // Outside any Surface, so LocalContentColor would fall back to black.
                color = MaterialTheme.colorScheme.onBackground,
            )
            trailing?.invoke()
        }
        // ⚠ **Keep an unscrolled shelf at its start.** `LazyRow` anchors its scroll to the first
        // visible item's key, so when a newer item arrives in front - a friend's new title moving to
        // the head of the shelf - the old first item stays where it was and the new one lands
        // scrolled most of the way off the left edge. That is the cut-off first card from the
        // screenshot, reproduced in `SocialRenderHarness` (offset 216px after one prepend). A shelf
        // the viewer has scrolled is left alone. Read in composition, before the next measure, so the
        // request lands in the same frame as the new item.
        val firstKey = items.firstOrNull()?.let(key)
        val anchor = remember { ShelfStartAnchor(firstKey) }
        if (firstKey != anchor.firstKey) {
            if (state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0) {
                state.requestScrollToItem(0)
            }
            anchor.firstKey = firstKey
        }
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = sectionPadding),
            horizontalArrangement = Arrangement.spacedBy(itemSpacing),
        ) { items(items, key = key) { card(it) } }
    }
}

/** Plain holder, deliberately not state: updating it must not recompose. */
private class ShelfStartAnchor(var firstKey: Any?)
