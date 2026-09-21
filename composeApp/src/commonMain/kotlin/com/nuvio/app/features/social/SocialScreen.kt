package com.nuvio.app.features.social

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.profiles.parseHexColor
import com.nuvio.app.features.watchparty.WatchPartyRepository
import com.nuvio.app.features.watchparty.WatchPartyStatus
import com.nuvio.app.features.watchparty.currentEpochMs
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.social_accept
import nuvio.composeapp.generated.resources.social_add_friend
import nuvio.composeapp.generated.resources.social_decline
import nuvio.composeapp.generated.resources.social_disabled
import nuvio.composeapp.generated.resources.social_friends
import nuvio.composeapp.generated.resources.social_handle
import nuvio.composeapp.generated.resources.social_handle_help
import nuvio.composeapp.generated.resources.social_inbox
import nuvio.composeapp.generated.resources.social_no_activity
import nuvio.composeapp.generated.resources.social_offline_cache
import nuvio.composeapp.generated.resources.social_recently_watched
import nuvio.composeapp.generated.resources.social_remove_friend
import nuvio.composeapp.generated.resources.social_save_handle
import nuvio.composeapp.generated.resources.social_search_handle
import nuvio.composeapp.generated.resources.social_share_recent
import nuvio.composeapp.generated.resources.social_share_watching
import nuvio.composeapp.generated.resources.social_title
import nuvio.composeapp.generated.resources.social_watching_now
import org.jetbrains.compose.resources.stringResource

/** Live presence, which is the reason to open this tab; fixed rather than themed, as in the lobby. */
internal val SocialLiveColor = Color(0xFF6FD08C)

/** How a one-line message under the search field should read. */
internal enum class SocialFeedbackTone { Neutral, Positive, Negative }

internal data class SocialFeedback(val message: String, val tone: SocialFeedbackTone)

@Composable
fun SocialScreen(
    modifier: Modifier = Modifier,
    /**
     * The tablet floating top bar's height, when there is one.
     *
     * Every other tab takes this and this screen did not, so on a tablet its header sat under the
     * floating bar. Null on a phone, where the status-bar inset below is the whole answer.
     */
    topChromePadding: Dp? = null,
    scrollToTopRequests: Flow<Unit> = emptyFlow(),
    onOpenContent: (contentType: String, contentId: String, title: String) -> Unit = { _, _, _ -> },
    onJoinParty: (inviteCode: String) -> Unit = {},
    onJoinInvitedParty: (partyId: String) -> Unit = {},
    onStartParty: (WatchingNowItem) -> Unit = {},
    onCancelJoinRequest: () -> Unit = {},
    outgoingRequest: OutgoingJoinRequestState = OutgoingJoinRequestState.Idle,
    onNotificationAction: (SocialNotification, SocialNotificationAction) -> Unit = { _, _ -> },
) {
    val state by SocialRepository.uiState.collectAsStateWithLifecycle()
    val titlePreferences by ContinueWatchingPreferencesRepository.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var handle by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<SocialProfileSummary>>(emptyList()) }
    // Search previously rendered every outcome identically - a failure, an empty result and a query
    // too short to run all left the screen unchanged, which is indistinguishable from a dead button.
    // Since then it said all three in one undifferentiated grey line, so "request sent" and "search
    // failed" still looked the same; the tone is what separates them.
    var feedback by remember { mutableStateOf<SocialFeedback?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    var handleMessage by remember { mutableStateOf<String?>(null) }
    var partyCode by rememberSaveable { mutableStateOf("") }
    var shareWatching by rememberSaveable(state.me?.profileId) { mutableStateOf(true) }
    var shareRecent by rememberSaveable(state.me?.profileId) { mutableStateOf(true) }
    var defaultJoinPolicy by rememberSaveable(state.me?.profileId) {
        mutableStateOf(WatchJoinPolicy.approval)
    }

    // Shared by the search button and the keyboard's search action, so pressing Enter does what the
    // button does. The server refuses queries under three characters, so that is reported here
    // rather than sent and silently dropped.
    val runSearch: () -> Unit = {
        scope.launch {
            val query = search.trim()
            if (query.length < 3) {
                searchResults = emptyList()
                feedback = SocialFeedback("Type at least 3 characters to search", SocialFeedbackTone.Neutral)
            } else {
                isSearching = true
                feedback = null
                SocialRepository.searchProfiles(query)
                    .onSuccess { results ->
                        searchResults = results
                        feedback = if (results.isEmpty()) {
                            SocialFeedback("No one is using @$query", SocialFeedbackTone.Neutral)
                        } else {
                            null
                        }
                    }
                    .onFailure { error ->
                        searchResults = emptyList()
                        feedback = SocialFeedback(error.message ?: "Search failed", SocialFeedbackTone.Negative)
                    }
                isSearching = false
            }
        }
    }

    val sendFriendRequest: (SocialProfileSummary) -> Unit = { profile ->
        scope.launch {
            // The result was previously discarded, so a sent request and a refused one both looked
            // like a button that did nothing. On success the row is dropped, because the request is
            // now pending rather than sendable.
            SocialRepository.sendFriendRequest(profile.profileId)
                .onSuccess {
                    searchResults = searchResults.filterNot { it.profileId == profile.profileId }
                    feedback = SocialFeedback("Friend request sent to @${profile.handle}", SocialFeedbackTone.Positive)
                }
                .onFailure { error ->
                    feedback = SocialFeedback(
                        error.message ?: "Could not send that friend request",
                        SocialFeedbackTone.Negative,
                    )
                }
        }
    }

    LaunchedEffect(
        state.me?.profileId,
        state.me?.shareWatchingNow,
        state.me?.shareRecentlyWatched,
        state.me?.defaultJoinPolicy,
    ) {
        shareWatching = state.me?.shareWatchingNow ?: true
        shareRecent = state.me?.shareRecentlyWatched ?: true
        defaultJoinPolicy = state.me?.defaultJoinPolicy ?: WatchJoinPolicy.approval
    }

    val partyUi by WatchPartyRepository.uiState.collectAsStateWithLifecycle()
    val heldParty = partyUi.party?.takeIf { it.status != WatchPartyStatus.ended }

    // Grouped over every page loaded so far, so a later page folds into rows already on screen.
    val activityGroups = remember(state.activity) { groupFriendActivity(state.activity) }
    val activityNowMs = remember(state.activity) { currentEpochMs() }
    val activityBuckets = remember(activityGroups, activityNowMs) {
        bucketFriendActivity(activityGroups, activityNowMs, socialUtcOffsetMs(activityNowMs))
    }

    // "Load more" was a button at the bottom of a list people scroll with a wheel. The next page is
    // asked for as the list nears its end instead.
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index
            last != null && info.totalItemsCount > 0 && last >= info.totalItemsCount - 4
        }
    }
    // Not keyed on `isLoadingMore`: the page request sets it the moment it starts, which cancelled
    // this effect mid-request, surfaced the cancellation as an error, and relaunched it - forever.
    LaunchedEffect(nearEnd, state.nextCursor) {
        if (nearEnd && state.nextCursor != null && !SocialRepository.uiState.value.isLoadingMore) {
            SocialRepository.refresh(append = true)
        }
    }

    LaunchedEffect(scrollToTopRequests) {
        scrollToTopRequests.collect { listState.animateScrollToItem(0) }
    }

    // Screens here are hosted directly rather than inside a Surface, so LocalContentColor falls back
    // to black - the app's other screens compensate by naming a colour at every call site. The port
    // from mobile did not, which left every bare Text and Icon black on the dark background: the
    // search button was invisible rather than broken. Providing it once covers the whole screen.
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        // ⚠ **Horizontal insets belong here, above the metrics.** A display cutout in landscape
        // is a *side* inset, and `socialFeedMetrics` divides `maxWidth` into columns - so consuming
        // the cutout after the division would have sized the cards from width the screen does not
        // own. Applying it on the constraint source means the column count and the cards agree.
        BoxWithConstraints(
            modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
            contentAlignment = Alignment.TopCenter,
        ) {
            // Below this the friends rail cannot hold a search field and a roster side by side with
            // the feed, so it folds back into the feed as a final section - which is also the phone
            // layout, since this screen is shared with mobile.
            val wideDashboard = maxWidth >= 1040.dp

            // Desktop width used as width, rather than as one very long column. The Social tab was
            // a vertical poster wall: one card per row at any size, so a 2560px window showed the
            // same single file a phone does. The cards are compact and weighted now, so the
            // question is how many fit and how wide each one then is.
            // ⚠ One source of truth for the feed's width **and** its column count. These were two
            // separate numbers and they disagreed: the columns were chosen from the full window
            // minus the rail, and then spent inside a list capped at 600dp. See [socialFeedMetrics].
            val railVisible = wideDashboard && state.capabilities.socialEnabled && !state.needsHandleSetup
            val feed = socialFeedMetrics(maxWidth, railVisible)

            // ⚠ `fillMaxSize()` here, and the cap below it never applied: filling sets the minimum
            // width to the parent's, which `widthIn(max = …)` cannot then go under. So the dashboard
            // ran edge to edge on a wide monitor while [socialFeedMetrics] divided a capped width,
            // and the difference came out as dead space between the feed and the rail. Height fills;
            // width is capped and the parent centres what is left.
            Column(Modifier.fillMaxHeight().widthIn(max = SocialDashboardMaxWidth)) {
                SocialIdentityHeader(
                    topChromePadding = topChromePadding,
                    me = state.me,
                    friendCount = state.friends.size,
                    watchingCount = state.watchingNow.size,
                    // This header does not scroll, so it has to fit the narrowest window the
                    // screen runs in - and it is shared with mobile, where a 200dp field plus a
                    // button beside an avatar and a name simply does not. Below the dashboard
                    // breakpoint the invite code moves into the feed instead.
                    showJoinField = wideDashboard &&
                        state.capabilities.watchPartyEnabled &&
                        !state.needsHandleSetup,
                    partyCode = partyCode,
                    onPartyCodeChange = { partyCode = it.trim().uppercase().take(32) },
                    onJoinParty = { onJoinParty(partyCode) },
                    isRefreshing = state.isLoading,
                    onRefresh = { scope.launch { SocialRepository.refresh() } },
                )

                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.widthIn(max = feed.feedWidth).fillMaxWidth().fillMaxHeight(),
                        // ⚠ Was a flat `110.dp`, which is neither the navigation bar nor the
                        // floating nav pill but a guess that happened to cover both on one device.
                        // `nuvioSafeBottomPadding` is the shared answer every other scrollable
                        // screen already uses, and it tracks the pill's real height.
                        contentPadding = PaddingValues(
                            start = SocialFeedHorizontalPadding,
                            end = SocialFeedHorizontalPadding,
                            top = 16.dp,
                            bottom = nuvioSafeBottomPadding(extra = 12.dp),
                        ),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        when {
                            !state.capabilities.socialEnabled -> item {
                                SocialNotice(stringResource(Res.string.social_disabled))
                            }

                            state.needsHandleSetup -> {
                                item {
                                    SocialHandleSetup(
                                        handle = handle,
                                        onHandleChange = { handle = normalizeSocialHandle(it).take(24) },
                                        message = handleMessage,
                                        onSave = {
                                            scope.launch {
                                                // The result was discarded here, so a handle that
                                                // never saved looked exactly like one that did -
                                                // which is how an empty database went unnoticed
                                                // while the screen appeared to work.
                                                SocialRepository.setupHandle(handle)
                                                    .onFailure { error ->
                                                        handleMessage = error.message
                                                            ?: "Could not save that handle"
                                                    }
                                                    .onSuccess { handleMessage = null }
                                            }
                                        },
                                    )
                                }
                            }

                            else -> {
                                if (state.isOfflineCache) {
                                    item { SocialNotice(stringResource(Res.string.social_offline_cache)) }
                                }
                                state.errorMessage?.let { error ->
                                    item { SocialNotice(error, MaterialTheme.colorScheme.error) }
                                }

                                if (!wideDashboard && state.capabilities.watchPartyEnabled) {
                                    // The counterpart to the header dropping the field when narrow.
                                    item {
                                        SocialPanel {
                                            Text(
                                                "Watch Together",
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                OutlinedTextField(
                                                    value = partyCode,
                                                    onValueChange = {
                                                        partyCode = it.trim().uppercase().take(32)
                                                    },
                                                    modifier = Modifier.weight(1f),
                                                    singleLine = true,
                                                    label = { Text("Invite code") },
                                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                                    keyboardActions = KeyboardActions(
                                                        onGo = { if (partyCode.isNotBlank()) onJoinParty(partyCode) },
                                                    ),
                                                )
                                                Button(
                                                    onClick = { onJoinParty(partyCode) },
                                                    enabled = partyCode.isNotBlank(),
                                                ) { Text("Join") }
                                            }
                                        }
                                    }
                                }

                                socialInbox(
                                    state = state,
                                    onRespond = { id, accept ->
                                        scope.launch { SocialRepository.respondFriendRequest(id, accept) }
                                    },
                                    onJoinInvitedParty = onJoinInvitedParty,
                                    onNotificationAction = onNotificationAction,
                                )

                                item {
                                    SocialSectionHeader(
                                        stringResource(Res.string.social_watching_now),
                                        state.watchingNow.size.takeIf { it > 0 },
                                        live = state.watchingNow.isNotEmpty(),
                                    )
                                }
                                if (state.watchingNow.isEmpty()) {
                                    item {
                                        if (state.isLoading) SocialSkeletonCard() else WatchingNowEmptyLine()
                                    }
                                } else {
                                    socialGridItems(
                                        items = orderWatchingNowForDisplay(state.watchingNow),
                                        columns = feed.watchingNowColumns,
                                        // Per session, not per title: two friends on one episode are two cards.
                                        key = { "watching:${it.profile.profileId}:${it.sessionId}:${it.videoId}" },
                                    ) { watching, cardModifier ->
                                        SocialWatchingNowCard(
                                            item = watching,
                                            affordance = if (state.capabilities.watchPartyEnabled) {
                                                watchingNowJoinAffordance(watching, outgoingRequest, heldParty)
                                            } else {
                                                WatchingNowJoinAffordance.None
                                            },
                                            onOpen = {
                                                onOpenContent(
                                                    watching.contentType,
                                                    watching.contentId,
                                                    watching.title,
                                                )
                                            },
                                            onJoin = { onStartParty(watching) },
                                            onCancelRequest = onCancelJoinRequest,
                                            modifier = cardModifier,
                                            artworkWidth = feed.watchingNowArtworkWidth,
                                            stacked = feed.watchingNowStacked,
                                        )
                                    }
                                }

                                item { Spacer(Modifier.height(6.dp)) }
                                item {
                                    SocialSectionHeader(stringResource(Res.string.social_recently_watched))
                                }
                                if (activityGroups.isEmpty()) {
                                    item {
                                        if (state.isLoading) {
                                            SocialSkeletonCard()
                                        } else {
                                            SocialEmptyState(stringResource(Res.string.social_no_activity))
                                        }
                                    }
                                } else {
                                    activityBuckets.forEach { (bucket, groups) ->
                                        item(key = "activity-bucket:${bucket.name}") {
                                            Text(
                                                bucket.label.uppercase(),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                letterSpacing = 0.8.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(top = 4.dp),
                                            )
                                        }
                                        socialGridItems(
                                            items = groups,
                                            columns = feed.activityColumns,
                                            key = { "activity:${it.contentId}" },
                                        ) { group, cellModifier ->
                                            FriendActivityRow(
                                                group = group,
                                                nowMs = activityNowMs,
                                                onOpen = { onOpenContent(group.contentType, group.contentId, group.title) },
                                                modifier = cellModifier.height(FriendActivityRowHeight),
                                            )
                                        }
                                    }
                                }
                                // Paging is automatic (see the effect on `listState`); this is only the
                                // inline sign that more is on its way.
                                if (state.isLoadingMore) {
                                    item(key = "activity-loading-more") {
                                        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                        }
                                    }
                                }

                                if (!wideDashboard) {
                                    item { Spacer(Modifier.height(6.dp)) }
                                    item {
                                        SocialFriendsPanel(
                                            state = state,
                                            search = search,
                                            onSearchChange = { search = normalizeSocialHandle(it).take(24) },
                                            onRunSearch = runSearch,
                                            isSearching = isSearching,
                                            feedback = feedback,
                                            searchResults = searchResults,
                                            onSendRequest = sendFriendRequest,
                                            onRemoveFriend = { id ->
                                                scope.launch { SocialRepository.removeFriend(id) }
                                            },
                                            onSelectFriend = SocialRepository::selectFriend,
                                            shareWatching = shareWatching,
                                            shareRecent = shareRecent,
                                            defaultJoinPolicy = defaultJoinPolicy,
                                            onShareWatching = {
                                                shareWatching = it
                                                scope.launch {
                                                    SocialRepository.setPrivacy(shareWatching, shareRecent)
                                                }
                                            },
                                            onShareRecent = {
                                                shareRecent = it
                                                scope.launch {
                                                    SocialRepository.setPrivacy(shareWatching, shareRecent)
                                                }
                                            },
                                            onDefaultJoinPolicy = { policy ->
                                                defaultJoinPolicy = policy
                                                scope.launch { SocialRepository.setDefaultJoinPolicy(policy) }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    }

                    if (railVisible) {
                        // Its own scroll, so the feed stays lazy: folding the roster into the feed's
                        // LazyColumn would have meant rendering every paged activity row eagerly to
                        // get two columns.
                        Column(
                            Modifier.width(SocialFriendsRailWidth)
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(start = 4.dp, end = 24.dp, top = 4.dp)
                                .padding(bottom = nuvioSafeBottomPadding(extra = 12.dp)),
                        ) {
                            SocialFriendsPanel(
                                state = state,
                                search = search,
                                onSearchChange = { search = normalizeSocialHandle(it).take(24) },
                                onRunSearch = runSearch,
                                isSearching = isSearching,
                                feedback = feedback,
                                searchResults = searchResults,
                                onSendRequest = sendFriendRequest,
                                onRemoveFriend = { id -> scope.launch { SocialRepository.removeFriend(id) } },
                                onSelectFriend = SocialRepository::selectFriend,
                                shareWatching = shareWatching,
                                shareRecent = shareRecent,
                                defaultJoinPolicy = defaultJoinPolicy,
                                onShareWatching = {
                                    shareWatching = it
                                    scope.launch { SocialRepository.setPrivacy(shareWatching, shareRecent) }
                                },
                                onShareRecent = {
                                    shareRecent = it
                                    scope.launch { SocialRepository.setPrivacy(shareWatching, shareRecent) }
                                },
                                onDefaultJoinPolicy = { policy ->
                                    defaultJoinPolicy = policy
                                    scope.launch { SocialRepository.setDefaultJoinPolicy(policy) }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Who you are here, and the two things you do from anywhere on the tab.
 *
 * Both used to be cards in the scroll: identity as a grey panel, and joining by code as a whole
 * second panel wrapped around a single text field, which is a lot of screen for one action.
 */
@Composable
private fun SocialIdentityHeader(
    topChromePadding: Dp?,
    me: SocialProfileSummary?,
    friendCount: Int,
    watchingCount: Int,
    showJoinField: Boolean,
    partyCode: String,
    onPartyCodeChange: (String) -> Unit,
    onJoinParty: () -> Unit,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
) {
    // A horizontal gradient here left two hard edges - one mid-width where it reached transparent,
    // one across the bottom where the Box ended - which read as a mis-drawn panel rather than as a
    // header. Fading downward has no edge to see.
    // ⚠ **The inset goes on the Row, not on the Box.** The gradient is the header, and a screen
    // running edge-to-edge wants it to reach up behind the status bar rather than to start below
    // it - padding the Box would leave a hard-edged band of bare background above the tint, which
    // is the edge this gradient was reshaped to avoid in the first place.
    //
    // `safeDrawing` rather than `statusBars`: it is the same value wherever there is no cutout,
    // and it is the larger one where there is. This screen is shared with desktop, where both are
    // zero, so it costs that build nothing.
    val topInset = topChromePadding
        ?: WindowInsets.safeDrawing.only(WindowInsetsSides.Top).asPaddingValues().calculateTopPadding()
    Box(
        Modifier.fillMaxWidth().background(
            Brush.verticalGradient(
                0f to MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                1f to Color.Transparent,
            ),
        ),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .padding(top = topInset)
                .padding(horizontal = 24.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (me != null) {
                SocialAvatar(me.displayName, me.avatarUrl, me.avatarColorHex, 56.dp)
                // Weighted rather than intrinsic: an unbounded name column pushed the join field
                // and the refresh button off the right edge as soon as a display name got long.
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        me.displayName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "@${me.handle} · $friendCount ${if (friendCount == 1) "friend" else "friends"}" +
                            if (watchingCount > 0) " · $watchingCount watching now" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(
                    stringResource(Res.string.social_title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (showJoinField) {
                OutlinedTextField(
                    value = partyCode,
                    onValueChange = onPartyCodeChange,
                    modifier = Modifier.width(200.dp),
                    singleLine = true,
                    label = { Text("Invite code") },
                    leadingIcon = { Icon(Icons.Rounded.Groups, null, Modifier.size(18.dp)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (partyCode.isNotBlank()) onJoinParty() }),
                )
                Button(onClick = onJoinParty, enabled = partyCode.isNotBlank()) { Text("Join") }
            }
            IconButton(onClick = onRefresh, enabled = !isRefreshing) {
                if (isRefreshing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.Refresh, "Refresh")
                }
            }
        }
    }
}

/**
 * The tab's own route into handle setup.
 *
 * ⚠ **A frame around [SocialIdentityBody], not a second copy of it.** This screen and the setup
 * wizard both ask for a handle, and the last time two surfaces described the same thing
 * independently - the playback modes - one of them kept a stale caption for a whole release. The
 * panel is this screen's furniture; everything inside it is shared.
 */
@Composable
private fun SocialHandleSetup(
    handle: String,
    onHandleChange: (String) -> Unit,
    message: String?,
    onSave: () -> Unit,
) {
    SocialPanel {
        SocialIdentityBody(
            handle = handle,
            onHandleChange = onHandleChange,
            message = message,
            busy = false,
            onSave = onSave,
        )
    }
}

/** Friend requests and party invites, together, because both are "somebody is waiting on you". */
private fun LazyListScope.socialInbox(
    state: SocialUiState,
    onRespond: (String, Boolean) -> Unit,
    onJoinInvitedParty: (String) -> Unit,
    onNotificationAction: (SocialNotification, SocialNotificationAction) -> Unit,
) {
    if (state.capabilities.partyContractVersion >= 2) {
        val actionable = state.notifications.filter { it.availableActions.isNotEmpty() }
        if (actionable.isEmpty()) return
        item {
            SocialSectionHeader(stringResource(Res.string.social_inbox), state.unreadCount)
        }
        items(actionable, key = { "notification:${it.id}" }) { notification ->
            SocialPanel(accent = notification.readAt == null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SocialAvatar(
                        notification.actor.displayName,
                        notification.actor.avatarUrl,
                        notification.actor.avatarColorHex,
                        40.dp,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(notification.actor.displayName, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(
                            when (notification.kind) {
                                SocialNotificationKind.FriendRequest -> "sent a friend request"
                                SocialNotificationKind.PartyInvitation -> "invited you to Watch Together"
                                SocialNotificationKind.WatchingNowJoinRequest -> "asked to join your playback"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    notification.availableActions.sortedBy { it.name }.forEach { action ->
                        TextButton(onClick = { onNotificationAction(notification, action) }) {
                            Text(
                                when (action) {
                                    SocialNotificationAction.Accept -> "Accept"
                                    SocialNotificationAction.Decline -> "Decline"
                                    SocialNotificationAction.Join -> "Join"
                                },
                            )
                        }
                    }
                }
            }
        }
        return
    }
    if (state.requests.isEmpty() && state.partyInvites.isEmpty()) return
    item {
        SocialSectionHeader(stringResource(Res.string.social_inbox), state.unreadCount)
    }
    items(state.requests, key = { "request:${it.id}" }) { request ->
        SocialPanel(accent = true) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SocialAvatar(
                    request.sender.displayName,
                    request.sender.avatarUrl,
                    request.sender.avatarColorHex,
                    40.dp,
                )
                Column(Modifier.weight(1f)) {
                    Text(request.sender.displayName, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(
                        "wants to be friends · @${request.sender.handle}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { onRespond(request.id, true) }) {
                    Icon(Icons.Rounded.Check, stringResource(Res.string.social_accept), tint = SocialLiveColor)
                }
                IconButton(onClick = { onRespond(request.id, false) }) {
                    Icon(
                        Icons.Rounded.Close,
                        stringResource(Res.string.social_decline),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    items(state.partyInvites, key = { "party-invite:${it.id}" }) { invite ->
        SocialPanel(accent = true) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SocialPoster(invite.content.poster, 40.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        "${invite.sender.displayName} invited you to watch",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        invite.content.title,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Button(onClick = { onJoinInvitedParty(invite.partyId) }) { Text("Join") }
            }
        }
    }
}



/**
 * Search, roster and privacy in one place.
 *
 * These were four separate stretches of the scroll - a heading, a search field, an unscrollable
 * `Row` of one FilterChip per friend that ran off the edge past about eight of them, the friend
 * rows, and then the privacy panel - with the roster telling you nothing the feed above had not
 * already said better.
 */
@Composable
internal fun SocialFriendsPanel(
    state: SocialUiState,
    search: String,
    onSearchChange: (String) -> Unit,
    onRunSearch: () -> Unit,
    isSearching: Boolean,
    feedback: SocialFeedback?,
    searchResults: List<SocialProfileSummary>,
    onSendRequest: (SocialProfileSummary) -> Unit,
    onRemoveFriend: (String) -> Unit,
    onSelectFriend: (String?) -> Unit,
    shareWatching: Boolean,
    shareRecent: Boolean,
    defaultJoinPolicy: WatchJoinPolicy,
    onShareWatching: (Boolean) -> Unit,
    onShareRecent: (Boolean) -> Unit,
    onDefaultJoinPolicy: (WatchJoinPolicy) -> Unit,
) {
    // What each friend is watching, so the roster answers the same question the feed does rather
    // than listing names next to nothing.
    val watchingByProfile = remember(state.watchingNow) {
        state.watchingNow.associateBy { it.profile.profileId }
    }
    val friends = remember(state.friends, watchingByProfile) {
        state.friends.sortedWith(
            compareByDescending<SocialProfileSummary> { watchingByProfile.containsKey(it.profileId) }
                .thenBy { it.displayName.lowercase() },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SocialSectionHeader(stringResource(Res.string.social_friends), state.friends.size.takeIf { it > 0 })

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = search,
                onValueChange = onSearchChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text(stringResource(Res.string.social_search_handle)) },
                prefix = { Text("@") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onRunSearch() }),
            )
            IconButton(onClick = onRunSearch, enabled = !isSearching) {
                if (isSearching) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.Search, "Search handles")
                }
            }
        }

        feedback?.let { entry ->
            Text(
                entry.message,
                style = MaterialTheme.typography.bodySmall,
                color = when (entry.tone) {
                    SocialFeedbackTone.Positive -> SocialLiveColor
                    SocialFeedbackTone.Negative -> MaterialTheme.colorScheme.error
                    SocialFeedbackTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        searchResults.forEach { profile ->
            SocialPersonRow(
                profile = profile,
                subtitle = "@${profile.handle}",
                onClick = null,
            ) {
                IconButton(onClick = { onSendRequest(profile) }) {
                    Icon(
                        Icons.Rounded.PersonAdd,
                        stringResource(Res.string.social_add_friend),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        if (state.friends.isEmpty()) {
            SocialEmptyState("No friends yet", "Search a handle above to send the first request.")
        } else {
            if (state.selectedFriendId != null) {
                // The filter is real - it goes to the server as p_filter_profile_id - but it used to
                // be one chip in a row of every friend, with no way to tell the feed was filtered.
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { onSelectFriend(null) },
                    shape = RoundedCornerShape(NuvioTokens.Radius.chip),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Showing only " +
                                (
                                    state.friends
                                        .firstOrNull { it.profileId == state.selectedFriendId }
                                        ?.displayName ?: "one friend"
                                    ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            Icons.Rounded.Close,
                            "Show everyone",
                            Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            friends.forEach { friend ->
                val watching = watchingByProfile[friend.profileId]
                SocialPersonRow(
                    profile = friend,
                    subtitle = watching?.title ?: "@${friend.handle}",
                    live = watching != null,
                    onClick = {
                        onSelectFriend(friend.profileId.takeIf { it != state.selectedFriendId })
                    },
                ) {
                    IconButton(onClick = { onRemoveFriend(friend.profileId) }) {
                        Icon(
                            Icons.Rounded.DeleteOutline,
                            stringResource(Res.string.social_remove_friend),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        SocialPanel {
            Text(
                "Privacy",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrivacyToggle(stringResource(Res.string.social_share_watching), shareWatching, onShareWatching)
            PrivacyToggle(stringResource(Res.string.social_share_recent), shareRecent, onShareRecent)
            Text(
                "Who can join new playback",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WatchJoinPolicy.entries.forEach { policy ->
                    FilterChip(
                        selected = defaultJoinPolicy == policy,
                        onClick = { onDefaultJoinPolicy(policy) },
                        label = {
                            Text(
                                when (policy) {
                                    WatchJoinPolicy.direct -> "Direct"
                                    WatchJoinPolicy.approval -> "Ask first"
                                    WatchJoinPolicy.disabled -> "Off"
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun SocialLiveBadge(playing: Boolean) {
    val color = if (playing) SocialLiveColor else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = RoundedCornerShape(NuvioTokens.Radius.chip), color = color.copy(alpha = 0.16f)) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (playing) SocialPulsingDot(color) else Box(Modifier.size(6.dp).clip(CircleShape).background(color))
            Text(
                if (playing) "PLAYING" else "PAUSED",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
                color = color,
                // A one-word label has no business wrapping; when the card collapsed this one
                // still did, letter by letter. It clips now, which is visible and survivable.
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun SocialPulsingDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "social-live")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "social-live-alpha",
    )
    Box(Modifier.size(6.dp).alpha(pulse).clip(CircleShape).background(color))
}

@Composable
private fun SocialSectionHeader(title: String, count: Int? = null, live: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        count?.let {
            Surface(
                shape = RoundedCornerShape(NuvioTokens.Radius.chip),
                color = if (live) {
                    SocialLiveColor.copy(alpha = 0.18f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ) {
                Text(
                    it.toString(),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (live) SocialLiveColor else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SocialPanel(
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(NuvioTokens.Radius.compactCard),
        color = MaterialTheme.colorScheme.surface.copy(alpha = if (accent) 0.9f else 0.6f),
        border = if (accent) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
        } else {
            null
        },
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun SocialNotice(message: String, accent: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(NuvioTokens.Radius.compactCard),
        color = accent.copy(alpha = 0.12f),
    ) {
        Text(message, Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall, color = accent)
    }
}

/** An empty section that says why it is empty and what fills it. */
@Composable
private fun SocialEmptyState(title: String, detail: String? = null) {
    Surface(
        // Left to fill the feed, this was a 1250px-wide box holding two short lines, which makes an
        // empty tab look broken rather than empty.
        modifier = Modifier.widthIn(max = 560.dp),
        shape = RoundedCornerShape(NuvioTokens.Radius.compactCard),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A card-shaped placeholder for the first load, so the tab does not open as a blank column. */
@Composable
private fun SocialSkeletonCard() {
    val transition = rememberInfiniteTransition(label = "social-skeleton")
    val shimmer by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "social-skeleton-alpha",
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(NuvioTokens.Radius.card),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
    ) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.width(74.dp).aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(NuvioTokens.Radius.poster))
                    .alpha(shimmer)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                listOf(0.4f, 0.8f, 0.55f).forEach { fraction ->
                    Box(
                        Modifier.fillMaxWidth(fraction).height(12.dp)
                            .clip(CircleShape)
                            .alpha(shimmer)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                }
            }
        }
    }
}

@Composable
private fun SocialPersonRow(
    profile: SocialProfileSummary,
    subtitle: String,
    live: Boolean = false,
    onClick: (() -> Unit)? = null,
    actions: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(NuvioTokens.Radius.compactCard),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box {
                // Carried on the model since the feature shipped and drawn by nothing here, so the
                // same person was an avatar in the feed and a bare name in the roster.
                SocialAvatar(profile.displayName, profile.avatarUrl, profile.avatarColorHex, 34.dp)
                if (live) {
                    Box(
                        Modifier.align(Alignment.BottomEnd)
                            .size(11.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(SocialLiveColor))
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    profile.displayName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (live) SocialLiveColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            actions()
        }
    }
}

@Composable
internal fun SocialAvatar(name: String, avatarUrl: String?, colorHex: String?, size: Dp) {
    val background = colorHex?.let(::parseHexColor) ?: MaterialTheme.colorScheme.primaryContainer
    Box(
        Modifier.size(size)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.14f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        // ⚠ **A blank URL was the only case that fell back.** A non-blank one that failed to load -
        // a deleted avatar, an expired signed URL, a friend's host being down, or simply being
        // offline - drew nothing at all, leaving an empty coloured circle with no initial and no
        // way to tell one friend from another. Keyed on the URL so a later, working avatar is
        // attempted rather than being permanently written off by one earlier failure. This is the
        // same `onError` latch the detail hero uses for a logo that will not load.
        var avatarLoadError by remember(avatarUrl) { mutableStateOf(false) }
        if (!avatarUrl.isNullOrBlank() && !avatarLoadError) {
            NuvioAsyncImage(
                model = avatarUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = { avatarLoadError = true },
            )
        } else {
            Text(
                // A blank name would `take(1)` to nothing and leave the same empty circle this
                // fallback exists to prevent.
                name.trim().take(1).uppercase().ifBlank { "?" },
                fontSize = (size.value * 0.42f).sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
    }
}

/** A poster at the 2:3 it actually is, given a width. */
@Composable
private fun SocialPoster(poster: String?, width: Dp) {
    val modifier = Modifier.width(width).aspectRatio(2f / 3f)
        .clip(RoundedCornerShape(NuvioTokens.Radius.compactCard))
    if (poster.isNullOrBlank()) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    } else {
        NuvioAsyncImage(
            model = poster,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable private fun PrivacyToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
