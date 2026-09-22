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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.watchparty.WatchPartyState
import com.nuvio.app.features.watchparty.currentEpochMs
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.social_watching_now
import org.jetbrains.compose.resources.stringResource

/**
 * What friends are up to, on Home, **underneath** the user's own Continue Watching.
 *
 * Watching Now keeps compact cards, because each has an action. Friends' activity is a shelf of
 * text rows with mini-posters (see [FriendActivityRow]): portrait posters beside Continue Watching's
 * landscape tiles, so the two shelves read as different kinds of thing rather than as a big and a
 * small copy of the same one.
 */
fun LazyListScope.homeSocialSections(
    watchingNow: List<WatchingNowItem>,
    activity: List<RecentActivityRun>,
    sectionPadding: Dp,
    /** The width Home is laid out in, for the shelf's row width (§6). */
    availableWidth: Dp,
    watchPartyEnabled: Boolean = false,
    outgoingRequest: OutgoingJoinRequestState = OutgoingJoinRequestState.Idle,
    heldParty: WatchPartyState? = null,
    // ⚠ **Required, deliberately.** This defaulted to `{}` and Home never passed it, so Home's
    // Watching Now showed a live "Join" / "Ask to join" button that did nothing at all - no RPC, no
    // request row, nothing on either client. Hardware Bug 6 (2026-09-15). A button that is drawn must
    // be wired; there is no default that is correct.
    onStartParty: (WatchingNowItem) -> Unit,
    onCancelJoinRequest: () -> Unit = {},
    /** "See all", past [FriendActivityHomeGroupLimit] titles. Null hides it. */
    onSeeAllActivity: (() -> Unit)? = null,
    onOpenContent: (contentType: String, contentId: String, title: String) -> Unit,
) {
    if (watchingNow.isNotEmpty()) {
        item(key = "z-social-watching-now") {
            val ordered = remember(watchingNow) { orderWatchingNowForDisplay(watchingNow).take(SocialHomeItemLimit) }
            SocialHomeShelf(
                title = stringResource(Res.string.social_watching_now),
                sectionPadding = sectionPadding,
                items = ordered,
                key = { "${it.profile.profileId}:${it.sessionId}:${it.videoId}" },
                itemSpacing = 10.dp,
            ) { item ->
                SocialWatchingNowCard(
                    item = item,
                    affordance = if (watchPartyEnabled) {
                        watchingNowJoinAffordance(item, outgoingRequest, heldParty)
                    } else {
                        WatchingNowJoinAffordance.None
                    },
                    onOpen = { onOpenContent(item.contentType, item.contentId, item.title) },
                    onJoin = { onStartParty(item) },
                    onCancelRequest = onCancelJoinRequest,
                    modifier = Modifier.width(SocialWatchingNowCardWidth).height(SocialWatchingNowCardHeight),
                )
            }
        }
    }
    if (activity.isNotEmpty()) {
        item(key = "z-social-recent") {
            val groups = remember(activity) { groupFriendActivity(activity) }
            val nowMs = remember(activity) { currentEpochMs() }
            val rowWidth = friendActivityRowWidth(availableWidth)
            val compact = availableWidth < 720.dp
            SocialHomeShelf(
                title = "Friends' activity",
                sectionPadding = sectionPadding,
                items = groups.take(FriendActivityHomeGroupLimit),
                key = FriendActivityGroup::contentId,
                itemSpacing = 16.dp,
                trailing = if (onSeeAllActivity != null && groups.size > FriendActivityHomeGroupLimit) {
                    { TextButton(onClick = onSeeAllActivity) { Text("See all") } }
                } else {
                    null
                },
            ) { group ->
                FriendActivityRow(
                    group = group,
                    nowMs = nowMs,
                    onOpen = { onOpenContent(group.contentType, group.contentId, group.title) },
                    modifier = Modifier.width(rowWidth).height(FriendActivityRowHeight),
                    compact = compact,
                )
            }
        }
    }
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
