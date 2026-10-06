package com.nuvio.app.features.social

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.profiles.parseHexColor
import com.nuvio.app.features.settings.SettingsPage
import com.nuvio.app.features.settings.ZSettingsNavigation
import com.nuvio.app.features.watchparty.WatchPartyRepository
import com.nuvio.app.features.watchparty.WatchPartyStatus
import com.nuvio.app.features.watchparty.currentEpochMs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.social_disabled
import nuvio.composeapp.generated.resources.social_no_activity
import nuvio.composeapp.generated.resources.social_offline_cache
import nuvio.composeapp.generated.resources.social_title
import org.jetbrains.compose.resources.stringResource

/** Live presence, which is the reason to open this tab; fixed rather than themed, as in the lobby. */
internal val SocialLiveColor = Color(0xFF6FD08C)

/** How a one-line message under the search field should read. */
internal enum class SocialFeedbackTone { Neutral, Positive, Negative }

internal data class SocialFeedback(val message: String, val tone: SocialFeedbackTone)

/** What sits over the tab: the inbox page, a friend's profile, or the join sheet for one session. */
internal sealed interface SocialOverlay {
    data object Inbox : SocialOverlay
    data class Profile(val profileId: String) : SocialOverlay
    data class Join(val sessionKey: String) : SocialOverlay
}

internal fun WatchingNowItem.socialSessionKey(): String = "${profile.profileId}:$sessionId:$videoId"

/** At or above this the tab is a dashboard: activity on the left, the Friends view as a rail. */
private val SocialDashboardBreakpoint = 1040.dp

@Composable
fun SocialScreen(
    modifier: Modifier = Modifier,
    /**
     * The tablet floating top bar's height, when there is one. Null on a phone, where the status-bar
     * inset is the whole answer.
     */
    topChromePadding: Dp? = null,
    scrollToTopRequests: Flow<Unit> = emptyFlow(),
    onOpenContent: (contentType: String, contentId: String, title: String) -> Unit = { _, _, _ -> },
    onJoinParty: (inviteCode: String) -> Unit = {},
    onJoinInvitedParty: (partyId: String) -> Unit = {},
    onReturnParty: (partyId: String) -> Unit = {},
    onStartParty: (WatchingNowItem) -> Unit = {},
    onCancelJoinRequest: () -> Unit = {},
    outgoingRequest: OutgoingJoinRequestState = OutgoingJoinRequestState.Idle,
    onNotificationAction: (SocialNotification, SocialNotificationAction) -> Unit = { _, _ -> },
) {
    val state by SocialRepository.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var handle by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<SocialProfileSummary>>(emptyList()) }
    // A failure, an empty result and a query too short to run used to look the same - which is
    // indistinguishable from a dead button. The tone is what separates them.
    var feedback by remember { mutableStateOf<SocialFeedback?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    var handleMessage by remember { mutableStateOf<String?>(null) }
    var partyCode by rememberSaveable { mutableStateOf("") }

    // Shared by the search button and the keyboard's search action. The server refuses queries under
    // three characters, so that is said here rather than sent and silently dropped.
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
            val alreadyFriends = profile.isFriend || state.friends.any { it.profileId == profile.profileId }
            val incoming = state.requests.any { it.sender.profileId == profile.profileId } ||
                state.notifications.any {
                    it.kind == SocialNotificationKind.FriendRequest &&
                        it.actor.profileId == profile.profileId &&
                        it.availableActions.isNotEmpty()
                }
            if (alreadyFriends || incoming) {
                feedback = SocialFeedback(
                    if (alreadyFriends) "You're already friends." else "They've already sent you a request. Check your inbox.",
                    SocialFeedbackTone.Neutral,
                )
                return@launch
            }
            // On success the row is dropped, because the request is now pending rather than sendable.
            SocialRepository.sendFriendRequest(profile.profileId)
                .onSuccess {
                    searchResults = searchResults.filterNot { it.profileId == profile.profileId }
                    feedback = SocialFeedback("Friend request sent to @${profile.handle}", SocialFeedbackTone.Positive)
                }
                .onFailure { error ->
                    feedback = SocialFeedback(socialFriendRequestMessage(error.message), SocialFeedbackTone.Negative)
                }
        }
    }

    // Opening the tab is the moment someone looks at what activation left behind. A feed stuck on its
    // cache with an error gets another attempt here, not only when the app restarts.
    LaunchedEffect(Unit) { SocialRepository.recoverIfStale() }

    val partyUi by WatchPartyRepository.uiState.collectAsStateWithLifecycle()
    val heldParty = partyUi.party?.takeIf { it.status != WatchPartyStatus.ended }

    // Grouped over every page loaded so far, so a later page folds into rows already on screen.
    val activityGroups = remember(state.activity) { groupFriendActivity(state.activity) }
    val activityNowMs = remember(state.activity) { currentEpochMs() }
    val activityTimeline = remember(state.activity, activityNowMs) {
        friendActivityTimeline(state.activity, activityNowMs, socialUtcOffsetMs(activityNowMs))
    }

    // The next page is asked for as the activity list nears its end. Not keyed on `isLoadingMore`:
    // the request sets it the moment it starts, which cancelled this effect mid-request, surfaced the
    // cancellation as an error, and relaunched it - forever.
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index
            last != null && info.totalItemsCount > 0 && last >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, state.nextCursor) {
        if (nearEnd && state.nextCursor != null && !SocialRepository.uiState.value.isLoadingMore) {
            SocialRepository.refresh(append = true)
        }
    }

    LaunchedEffect(scrollToTopRequests) {
        scrollToTopRequests.collect { listState.animateScrollToItem(0) }
    }

    SocialFeed(
        model = SocialFeedModel(
            state = state,
            handle = handle,
            handleMessage = handleMessage,
            search = search,
            searchResults = searchResults,
            feedback = feedback,
            isSearching = isSearching,
            partyCode = partyCode,
            activePartyId = heldParty?.id,
            activityGroups = activityGroups,
            activityTimeline = activityTimeline,
            activityNowMs = activityNowMs,
            joinAffordance = { watching ->
                if (state.capabilities.watchPartyEnabled) {
                    watchingNowJoinAffordance(watching, outgoingRequest, heldParty)
                } else {
                    WatchingNowJoinAffordance.None
                }
            },
        ),
        actions = SocialFeedActions(
            onRefresh = { scope.launch { SocialRepository.refresh() } },
            onHandleChange = { handle = normalizeSocialHandle(it).take(24) },
            onSaveHandle = {
                scope.launch {
                    // A handle that never saved must not look like one that did.
                    SocialRepository.setupHandle(handle)
                        .onFailure { error -> handleMessage = error.message ?: "Could not save that handle" }
                        .onSuccess { handleMessage = null }
                }
            },
            onPartyCodeChange = { partyCode = it.trim().uppercase().take(32) },
            onJoinParty = { onJoinParty(partyCode) },
            onRespondRequest = { id, accept -> scope.launch { SocialRepository.respondFriendRequest(id, accept) } },
            onJoinInvitedParty = onJoinInvitedParty,
            onReturnParty = onReturnParty,
            onNotificationAction = onNotificationAction,
            onOpenContent = onOpenContent,
            onStartParty = onStartParty,
            onCancelJoinRequest = onCancelJoinRequest,
            onSearchChange = { search = normalizeSocialHandle(it).take(24) },
            onRunSearch = runSearch,
            onSendRequest = sendFriendRequest,
            onRemoveFriend = { id -> scope.launch { SocialRepository.removeFriend(id) } },
            onSelectFriend = SocialRepository::selectFriend,
            onMarkNotificationsRead = { ids -> scope.launch { SocialRepository.markNotificationsRead(ids) } },
            onOpenPrivacySettings = { ZSettingsNavigation.open(SettingsPage.Social) },
            onCancelFriendRequest = { id -> scope.launch { SocialRepository.cancelFriendRequest(id) } },
            onMarkInboxRead = { ids -> scope.launch { SocialRepository.markInboxRead(ids) } },
            onSetFriendPrefs = { friend, hide, notify ->
                scope.launch { SocialRepository.setFriendPrefs(friend, hideActivity = hide, notifyWhenWatching = notify) }
            },
            onLoadTogetherStats = { friend -> SocialRepository.togetherStats(friend).getOrNull() },
        ),
        listState = listState,
        topChromePadding = topChromePadding,
        modifier = modifier,
    )
}

/**
 * Everything the Social tab draws, already gathered.
 *
 * [SocialScreen] reads the repositories and owns every piece of remembered state; [SocialFeed] only
 * lays it out. The split exists so the render harness composes the real layout instead of a hand-built
 * replica that silently stops describing the screen the first time the screen changes.
 */
internal data class SocialFeedModel(
    val state: SocialUiState,
    val handle: String = "",
    val handleMessage: String? = null,
    val search: String = "",
    val searchResults: List<SocialProfileSummary> = emptyList(),
    val feedback: SocialFeedback? = null,
    val isSearching: Boolean = false,
    val partyCode: String = "",
    val activePartyId: String? = null,
    // Privacy moved to Settings → Social in V2 (one place, one wording); kept so callers still compile.
    val shareWatching: Boolean = true,
    val shareRecent: Boolean = true,
    val defaultJoinPolicy: WatchJoinPolicy = WatchJoinPolicy.approval,
    val activityGroups: List<FriendActivityGroup> = emptyList(),
    /** Recently watched as the per-friend timeline the tab draws. */
    val activityTimeline: List<FriendActivityEntry> = emptyList(),
    val activityNowMs: Long = 0L,
    val joinAffordance: (WatchingNowItem) -> WatchingNowJoinAffordance = { WatchingNowJoinAffordance.None },
    /** Where a fresh composition starts - the render harness uses these to draw each view. */
    val initialTab: SocialTab = SocialTab.Activity,
    val initialOverlay: SocialOverlay? = null,
)

/** The Social tab's callbacks, exactly as [SocialScreen] defines them. */
internal class SocialFeedActions(
    val onRefresh: () -> Unit = {},
    val onHandleChange: (String) -> Unit = {},
    val onSaveHandle: () -> Unit = {},
    val onPartyCodeChange: (String) -> Unit = {},
    val onJoinParty: () -> Unit = {},
    val onRespondRequest: (String, Boolean) -> Unit = { _, _ -> },
    val onJoinInvitedParty: (String) -> Unit = {},
    val onReturnParty: (String) -> Unit = {},
    val onNotificationAction: (SocialNotification, SocialNotificationAction) -> Unit = { _, _ -> },
    val onOpenContent: (contentType: String, contentId: String, title: String) -> Unit = { _, _, _ -> },
    val onStartParty: (WatchingNowItem) -> Unit = {},
    val onCancelJoinRequest: () -> Unit = {},
    val onSearchChange: (String) -> Unit = {},
    val onRunSearch: () -> Unit = {},
    val onSendRequest: (SocialProfileSummary) -> Unit = {},
    val onRemoveFriend: (String) -> Unit = {},
    val onSelectFriend: (String?) -> Unit = {},
    val onShareWatching: (Boolean) -> Unit = {},
    val onShareRecent: (Boolean) -> Unit = {},
    val onDefaultJoinPolicy: (WatchJoinPolicy) -> Unit = {},
    val onMarkNotificationsRead: (Set<String>) -> Unit = {},
    val onOpenPrivacySettings: () -> Unit = {},
    val onCancelFriendRequest: (String) -> Unit = {},
    val onMarkInboxRead: (Set<String>) -> Unit = {},
    /** Social V2 per-friend switches; null leaves one as it is. */
    val onSetFriendPrefs: (friendProfileId: String, hide: Boolean?, notify: Boolean?) -> Unit = { _, _, _ -> },
    val onLoadTogetherStats: suspend (friendProfileId: String) -> SocialTogetherStats? = { null },
)

/**
 * The Social tab, V2 (PLAN-social-v2.md).
 *
 * - **Phone and tablet** (< [SocialDashboardBreakpoint]): your identity and the Inbox on top, then
 *   *Activity | Friends*. Activity is Watching Now (backdrop cards) and Recently watched (still rows);
 *   Friends is search, invite code, requests and the roster.
 * - **Desktop**: the same two views side by side - activity as the feed, Friends as a rail.
 * - Over either: the Inbox page, a friend's profile, the join sheet a card tap opens, the invite-code
 *   dialog.
 */
@Composable
internal fun SocialFeed(
    model: SocialFeedModel,
    actions: SocialFeedActions,
    listState: LazyListState,
    topChromePadding: Dp? = null,
    modifier: Modifier = Modifier,
) {
    val state = model.state
    // Screens here are hosted directly rather than inside a Surface, so LocalContentColor would fall
    // back to black. Providing it once covers the whole screen.
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        // ⚠ Horizontal insets belong here, above the width decisions: a landscape cutout is a side
        // inset, and the column counts must be chosen from width the screen actually owns.
        BoxWithConstraints(
            modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        ) {
            val wide = maxWidth >= SocialDashboardBreakpoint
            val screenWidth = maxWidth
            val screenHeight = maxHeight
            val horizontal = if (wide) 28.dp else 16.dp
            var tab by rememberSaveable { mutableStateOf(model.initialTab) }
            var overlay by remember { mutableStateOf(model.initialOverlay) }
            var inviteOpen by rememberSaveable { mutableStateOf(false) }
            val topInset = topChromePadding
                ?: WindowInsets.safeDrawing.only(WindowInsetsSides.Top).asPaddingValues().calculateTopPadding()
            val ready = state.capabilities.socialEnabled && !state.needsHandleSetup

            // A card tap: the join sheet when there is anything to do about the session, otherwise the
            // title. Details are always one long-press or one info-dot away.
            val openWatching: (WatchingNowItem) -> Unit = { item ->
                when (model.joinAffordance(item)) {
                    WatchingNowJoinAffordance.Join,
                    WatchingNowJoinAffordance.AskToJoin,
                    is WatchingNowJoinAffordance.Requested,
                    -> overlay = SocialOverlay.Join(item.socialSessionKey())
                    else -> actions.onOpenContent(item.contentType, item.contentId, item.title)
                }
            }
            val openDetails: (WatchingNowItem) -> Unit = { item ->
                actions.onOpenContent(item.contentType, item.contentId, item.title)
            }

            Column(Modifier.fillMaxSize()) {
                SocialHeader(
                    me = state.me,
                    friendCount = state.friends.size,
                    unread = state.unreadCount,
                    topInset = topInset,
                    underTopChrome = topChromePadding != null,
                    horizontal = horizontal,
                    wide = wide,
                    showInvite = wide && ready && state.capabilities.watchPartyEnabled,
                    showInbox = ready,
                    onInvite = { inviteOpen = true },
                    onInbox = { overlay = SocialOverlay.Inbox },
                )
                when {
                    !state.capabilities.socialEnabled -> SocialMessageColumn(horizontal) {
                        SocialNotice(stringResource(Res.string.social_disabled))
                    }
                    state.needsHandleSetup -> SocialMessageColumn(horizontal) {
                        SocialPanel {
                            SocialIdentityBody(
                                handle = model.handle,
                                onHandleChange = actions.onHandleChange,
                                message = model.handleMessage,
                                busy = false,
                                onSave = actions.onSaveHandle,
                            )
                        }
                    }
                    wide -> Row(Modifier.fillMaxSize().padding(start = horizontal, end = horizontal), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                            val feedWidth = maxWidth
                            val feedHeight = maxHeight
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(top = 4.dp, bottom = nuvioSafeBottomPadding(extra = 16.dp)),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                socialActivityItems(
                                    model = model,
                                    actions = actions,
                                    contentWidth = feedWidth,
                                    viewportHeight = feedHeight,
                                    onOpenWatching = openWatching,
                                    onWatchingDetails = openDetails,
                                    onOpenProfile = { overlay = SocialOverlay.Profile(it) },
                                )
                            }
                        }
                        Column(
                            Modifier.width(SocialFriendsRailWidth).fillMaxHeight()
                                .padding(bottom = nuvioSafeBottomPadding(extra = 12.dp))
                                .clip(RoundedCornerShape(22.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                .verticalScroll(rememberScrollState())
                                .padding(18.dp),
                        ) {
                            SocialFriendsContent(
                                model = model,
                                actions = actions,
                                showInvite = false,
                                onInvite = { inviteOpen = true },
                                onOpenProfile = { overlay = SocialOverlay.Profile(it) },
                            )
                        }
                    }
                    else -> {
                        SocialSegmentedTabs(tab, { tab = it }, Modifier.padding(horizontal = horizontal).padding(bottom = 6.dp))
                        when (tab) {
                            SocialTab.Activity -> LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    start = horizontal + SocialActivityExtraGutter,
                                    end = horizontal + SocialActivityExtraGutter,
                                    top = 6.dp,
                                    bottom = nuvioSafeBottomPadding(extra = 12.dp),
                                ),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                socialActivityItems(
                                    model = model,
                                    actions = actions,
                                    contentWidth = screenWidth - (horizontal + SocialActivityExtraGutter) * 2,
                                    viewportHeight = screenHeight,
                                    onOpenWatching = openWatching,
                                    onWatchingDetails = openDetails,
                                    onOpenProfile = { overlay = SocialOverlay.Profile(it) },
                                )
                            }
                            SocialTab.Friends -> Column(
                                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                    .padding(start = horizontal, end = horizontal, top = 8.dp)
                                    .padding(bottom = nuvioSafeBottomPadding(extra = 12.dp)),
                            ) {
                                SocialFriendsContent(
                                    model = model,
                                    actions = actions,
                                    showInvite = state.capabilities.watchPartyEnabled,
                                    onInvite = { inviteOpen = true },
                                    onOpenProfile = { overlay = SocialOverlay.Profile(it) },
                                )
                            }
                        }
                    }
                }
            }

            when (val current = overlay) {
                SocialOverlay.Inbox -> SocialInboxPage(
                    state = state,
                    nowMs = model.activityNowMs.takeIf { it > 0 } ?: currentEpochMs(),
                    wide = wide,
                    topInset = topInset,
                    onBack = { overlay = null },
                    onAction = actions.onNotificationAction,
                    onRespondLegacy = actions.onRespondRequest,
                    onJoinLegacyInvite = actions.onJoinInvitedParty,
                    onMarkRead = actions.onMarkNotificationsRead,
                    onMarkInboxRead = actions.onMarkInboxRead,
                    onOpenContent = actions.onOpenContent,
                )
                is SocialOverlay.Profile -> {
                    val friend = state.friends.firstOrNull { it.profileId == current.profileId }
                    if (friend == null) {
                        // Removed, or unfriended from elsewhere: nothing left to show.
                        LaunchedEffect(current) { overlay = null }
                    } else {
                        val watching = state.watchingNow.firstOrNull { it.profile.profileId == friend.profileId }
                            ?: groupWatchingNowByParty(state.watchingNow).firstOrNull { item ->
                                item.partyCompanions.any { it.profileId == friend.profileId }
                            }
                        SocialSheet(wide = wide, onDismiss = { overlay = null }) {
                            var stats by remember(friend.profileId) { mutableStateOf<SocialTogetherStats?>(null) }
                            if (state.socialV2Backend) {
                                LaunchedEffect(friend.profileId) { stats = actions.onLoadTogetherStats(friend.profileId) }
                            }
                            SocialProfileContent(
                                friend = friend,
                                prefs = state.prefsFor(friend.profileId).takeIf { state.socialV2Backend },
                                stats = stats,
                                onSetPrefs = { hide, notify -> actions.onSetFriendPrefs(friend.profileId, hide, notify) },
                                watching = watching,
                                affordance = watching?.let(model.joinAffordance) ?: WatchingNowJoinAffordance.None,
                                groups = model.activityGroups.filter { group -> group.friends.any { it.profileId == friend.profileId } },
                                nowMs = model.activityNowMs,
                                onOpenWatching = openWatching,
                                onWatchingDetails = openDetails,
                                onOpenContent = actions.onOpenContent,
                                onRemove = {
                                    actions.onRemoveFriend(friend.profileId)
                                    overlay = null
                                },
                            )
                        }
                    }
                }
                is SocialOverlay.Join -> {
                    val item = orderWatchingNowForDisplay(state.watchingNow).firstOrNull { it.socialSessionKey() == current.sessionKey }
                    if (item == null) {
                        // The session ended while the sheet was open; there is nothing left to join.
                        LaunchedEffect(current) { overlay = null }
                    } else {
                        SocialSheet(wide = wide, onDismiss = { overlay = null }) {
                            SocialJoinSheetContent(
                                item = item,
                                affordance = model.joinAffordance(item),
                                onJoin = {
                                    actions.onStartParty(item)
                                    overlay = null
                                },
                                onCancelRequest = {
                                    actions.onCancelJoinRequest()
                                    overlay = null
                                },
                                onDetails = {
                                    overlay = null
                                    openDetails(item)
                                },
                            )
                        }
                    }
                }
                null -> Unit
            }

            if (inviteOpen) {
                SocialInviteCodeDialog(
                    code = model.partyCode,
                    onCodeChange = actions.onPartyCodeChange,
                    onJoin = {
                        inviteOpen = false
                        actions.onJoinParty()
                    },
                    onDismiss = { inviteOpen = false },
                )
            }
        }
    }
}

// --- header ----------------------------------------------------------------------------------

/** You, top-left, with the tab's two persistent actions on the same line. */
@Composable
private fun SocialHeader(
    me: SocialProfileSummary?,
    friendCount: Int,
    unread: Int,
    topInset: Dp,
    underTopChrome: Boolean,
    horizontal: Dp,
    wide: Boolean,
    showInvite: Boolean,
    showInbox: Boolean,
    onInvite: () -> Unit,
    onInbox: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .padding(top = topInset)
            // The shell's top-bar reservation already holds the gap below the nav pill; adding more here
            // left the identity row floating ~70dp under it on desktop (2026-10-06 QA). Same as Downloads.
            .padding(start = horizontal, end = horizontal, top = if (underTopChrome) 0.dp else 12.dp, bottom = if (wide) 18.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (me != null) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SocialAvatar(me.displayName, me.avatarUrl, me.avatarColorHex, if (wide) 52.dp else 42.dp)
                Column {
                    Text(
                        me.displayName,
                        style = if (wide) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "@${me.handle} · $friendCount ${if (friendCount == 1) "friend" else "friends"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        } else {
            Text(
                stringResource(Res.string.social_title),
                modifier = Modifier.weight(1f),
                style = if (wide) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        if (showInvite) SocialPillButton("Invite code", onInvite)
        if (showInbox) SocialInboxButton(unread, onInbox)
    }
}

@Composable
private fun SocialMessageColumn(horizontal: Dp, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = horizontal, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

// --- activity --------------------------------------------------------------------------------

/** Phones felt cramped at the header's 16dp; the activity grid gets a little more air on each side. */
private val SocialActivityExtraGutter = 4.dp

private fun LazyListScope.socialActivityItems(
    model: SocialFeedModel,
    actions: SocialFeedActions,
    contentWidth: Dp,
    viewportHeight: Dp,
    onOpenWatching: (WatchingNowItem) -> Unit,
    onWatchingDetails: (WatchingNowItem) -> Unit,
    onOpenProfile: (profileId: String) -> Unit,
) {
    val state = model.state
    val columns = socialPileColumns(contentWidth)
    val gap = socialPileGap(columns)
    val compact = columns <= 2
    model.activePartyId?.let { partyId ->
        item(key = "active-party-return") {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(SocialLiveColor.copy(alpha = 0.14f))
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(SocialLiveColor))
                Spacer(Modifier.width(10.dp))
                Text("You're in a Watch Together party", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                SocialPillButton("Return", { actions.onReturnParty(partyId) }, primary = true)
            }
        }
    }
    if (state.isOfflineCache) {
        item(key = "offline") { SocialNotice(stringResource(Res.string.social_offline_cache)) }
    }
    state.errorMessage?.let { error ->
        item(key = "error") { SocialNotice(error, MaterialTheme.colorScheme.error) }
    }

    val watching = orderWatchingNowForDisplay(state.watchingNow)
    item(key = "watching-label") {
        SocialSectionLabel("Watching now", watching.size.takeIf { it > 0 }, live = watching.isNotEmpty())
    }
    if (watching.isEmpty()) {
        item(key = "watching-empty") {
            if (state.isLoading) SocialSkeleton(SocialWatchingCardHeight) else WatchingNowEmptyLine()
        }
    } else {
        // Per session, not per title: two friends on one episode are two cards. 16:9, the backdrop's
        // own shape: full width on a phone; above the grid the line is split evenly, which is exactly
        // two timeline cells at 4 and 6 columns (2026-10-06).
        val perLine = if (compact) 1 else (columns + 1) / 2
        // A landscape phone has the width but not the height: 16:9 at that width is taller than the
        // screen. Never more than ~55% of the viewport tall.
        val liveWidth = minOf((contentWidth - gap * (perLine - 1)) / perLine, viewportHeight * 0.55f * 16f / 9f)
        watching.chunked(perLine).forEach { line ->
            item(key = "watching:${line.first().socialSessionKey()}") {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    line.forEach { item ->
                        SocialWatchingCard(
                            item = item,
                            affordance = model.joinAffordance(item),
                            onClick = { onOpenWatching(item) },
                            onDetails = { onWatchingDetails(item) },
                            modifier = Modifier.width(liveWidth),
                            height = null,
                        )
                    }
                }
            }
        }
    }

    if (model.activityGroups.isEmpty()) {
        item(key = "recent-label") { SocialSectionLabel("Recently watched", modifier = Modifier.padding(top = 8.dp)) }
        item(key = "recent-empty") {
            if (state.isLoading) {
                SocialSkeleton(84.dp)
            } else {
                Text(
                    stringResource(Res.string.social_no_activity),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
        // The timeline without holes: a bucket's label sits above its first card, inside the grid, so
        // Today, Yesterday and This week share a line when they are short. A one-title card opens the
        // title; a friend's several titles open their profile, which lists them.
        val entries = model.activityTimeline
        val cells = entries.mapIndexed { i, entry ->
            (if (i == 0 || entries[i - 1].bucket != entry.bucket) entry.bucket.label else null) to entry
        }
        cells.chunked(columns).forEach { line ->
            item(key = "timeline:${line.first().second.key}") {
                val labelled = line.any { it.first != null }
                Row(
                    Modifier.fillMaxWidth().padding(top = if (labelled) 10.dp else 0.dp, bottom = if (compact) 14.dp else 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    line.forEach { (label, entry) ->
                        Column(Modifier.weight(1f)) {
                            if (labelled) {
                                Box(Modifier.height(30.dp)) { if (label != null) SocialSectionLabel(label) }
                            }
                            SocialActivityPileCard(
                                entry = entry,
                                nowMs = model.activityNowMs,
                                compact = compact,
                                onClick = {
                                    val single = entry.titles.singleOrNull()
                                    if (single != null) {
                                        actions.onOpenContent(single.contentType, single.contentId, single.title)
                                    } else {
                                        onOpenProfile(entry.people.first().profileId)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    // Paging is automatic (see the effect on the list); this is only the sign that more is coming.
    if (state.isLoadingMore) {
        item(key = "activity-loading-more") {
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
    }
}

// --- friends ---------------------------------------------------------------------------------

/**
 * Add a friend, answer requests, see who you have. The same body is the phone's Friends tab and the
 * desktop rail.
 */
@Composable
private fun SocialFriendsContent(
    model: SocialFeedModel,
    actions: SocialFeedActions,
    showInvite: Boolean,
    onInvite: () -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    val state = model.state
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = model.search,
            onValueChange = actions.onSearchChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            shape = RoundedCornerShape(999.dp),
            placeholder = { Text("Add by @handle", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { actions.onRunSearch() }),
            trailingIcon = {
                IconButton(onClick = actions.onRunSearch, enabled = !model.isSearching) {
                    if (model.isSearching) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Search, "Search handles")
                    }
                }
            },
        )
        if (showInvite) SocialPillButton("Invite code", onInvite)
    }
    model.feedback?.let { entry ->
        Text(
            entry.message,
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = when (entry.tone) {
                SocialFeedbackTone.Positive -> SocialLiveColor
                SocialFeedbackTone.Negative -> MaterialTheme.colorScheme.error
                SocialFeedbackTone.Neutral -> muted
            },
        )
    }
    model.searchResults.forEach { profile ->
        SocialPersonLine(
            person = profile,
            subtitle = "@${profile.handle}",
            trailing = { SocialPillButton("Add", { actions.onSendRequest(profile) }, primary = true) },
        )
    }

    // Incoming requests: the notification form on contract v2, the legacy list before it.
    val requestNotifications = state.notifications.filter {
        it.kind == SocialNotificationKind.FriendRequest && it.availableActions.isNotEmpty()
    }
    val legacyRequests = if (state.capabilities.partyContractVersion >= 2) emptyList() else state.requests
    val requestCount = requestNotifications.size + legacyRequests.size
    if (requestCount > 0) {
        SocialSectionLabel("Requests", requestCount, modifier = Modifier.padding(top = 10.dp))
        requestNotifications.forEach { request ->
            SocialPersonLine(
                person = request.actor,
                subtitle = "@${request.actor.handle} · wants to be friends",
                below = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (SocialNotificationAction.Accept in request.availableActions) {
                            SocialPillButton("Accept", { actions.onNotificationAction(request, SocialNotificationAction.Accept) }, primary = true)
                        }
                        if (SocialNotificationAction.Decline in request.availableActions) {
                            SocialPillButton("Decline", { actions.onNotificationAction(request, SocialNotificationAction.Decline) })
                        }
                    }
                },
            )
        }
        legacyRequests.forEach { request ->
            SocialPersonLine(
                person = request.sender,
                subtitle = "@${request.sender.handle} · wants to be friends",
                below = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SocialPillButton("Accept", { actions.onRespondRequest(request.id, true) }, primary = true)
                        SocialPillButton("Decline", { actions.onRespondRequest(request.id, false) })
                    }
                },
            )
        }
    }

    if (state.sentRequests.isNotEmpty()) {
        SocialSectionLabel("Sent", state.sentRequests.size, modifier = Modifier.padding(top = 10.dp))
        state.sentRequests.forEach { sent ->
            SocialPersonLine(
                person = sent.receiver,
                subtitle = "@${sent.receiver.handle} · waiting for a reply",
                trailing = { SocialPillButton("Cancel", { actions.onCancelFriendRequest(sent.id) }) },
            )
        }
    }

    // Live first, then by name, so the roster answers the same question the feed does.
    val watchingByProfile = remember(state.watchingNow) {
        buildMap {
            groupWatchingNowByParty(state.watchingNow).forEach { item ->
                // `putIfAbsent` is JVM-only; this file also compiles for iOS.
                item.socialPeople().forEach { person -> if (person.profileId !in this) put(person.profileId, item) }
            }
        }
    }
    val friends = remember(state.friends, watchingByProfile) {
        state.friends.sortedWith(
            compareByDescending<SocialProfileSummary> { watchingByProfile[it.profileId]?.state == SocialPlaybackState.playing }
                .thenByDescending { watchingByProfile.containsKey(it.profileId) }
                .thenBy { it.displayName.lowercase() },
        )
    }
    SocialSectionLabel("Friends", friends.size.takeIf { it > 0 }, modifier = Modifier.padding(top = 10.dp))
    if (friends.isEmpty()) {
        Text(
            "No friends yet. Search a handle above to send the first request.",
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
            modifier = Modifier.padding(vertical = 6.dp),
        )
    } else {
        friends.forEach { friend ->
            val watching = watchingByProfile[friend.profileId]
            val playing = watching?.state == SocialPlaybackState.playing
            SocialPersonLine(
                person = friend,
                subtitle = when {
                    watching == null -> "@${friend.handle}"
                    playing -> "Watching ${watching.title}"
                    else -> "Paused · ${watching.title}"
                },
                live = playing,
                onClick = { onOpenProfile(friend.profileId) },
            )
        }
    }
    TextButton(onClick = actions.onOpenPrivacySettings, modifier = Modifier.padding(top = 6.dp)) {
        Text("Privacy and sharing  ›", style = MaterialTheme.typography.labelLarge, color = muted)
    }
}

// --- inbox -----------------------------------------------------------------------------------

/**
 * Everything that happened to you on Social, newest first, each message its own card. Opening the
 * page marks what is on it read; "New" is what was unread when it opened, so the grouping does not
 * shift under the reader once the read marks land.
 */
@Composable
private fun SocialInboxPage(
    state: SocialUiState,
    nowMs: Long,
    wide: Boolean,
    topInset: Dp,
    onBack: () -> Unit,
    onAction: (SocialNotification, SocialNotificationAction) -> Unit,
    onRespondLegacy: (String, Boolean) -> Unit,
    onJoinLegacyInvite: (String) -> Unit,
    onMarkRead: (Set<String>) -> Unit,
    onMarkInboxRead: (Set<String>) -> Unit,
    onOpenContent: (contentType: String, contentId: String, title: String) -> Unit,
) {
    PlatformBackHandler(enabled = true, onBack = onBack)
    // Derived notifications and stored events, one timeline. A kind this build does not know is skipped.
    // A finished party notice leaves after a day: see [SocialFinishedNoticeRetentionMs].
    val entries = state.notifications.filterNot { it.isRetired(nowMs) }.map { SocialInboxEntry.Notice(it) } +
        state.inbox.filter { it.kind in SocialInboxEvent.Known }.map { SocialInboxEntry.Event(it) }
    val timeline = entries.sortedByDescending { parseSocialTimestampMs(it.createdAt) ?: Long.MIN_VALUE }
    val unreadAtOpen = remember { timeline.filter { it.unread }.map { it.key }.toSet() }
    LaunchedEffect(Unit) {
        val notices = state.notifications.filter { it.readAt == null }.map { it.id }.toSet()
        val events = state.inbox.filter { it.readAt == null }.map { it.id }.toSet()
        if (notices.isNotEmpty()) onMarkRead(notices)
        if (events.isNotEmpty()) onMarkInboxRead(events)
    }
    val (fresh, earlier) = timeline.partition { it.key in unreadAtOpen }
    val horizontal = if (wide) 28.dp else 16.dp
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(top = topInset)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = horizontal, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Text("Inbox", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            LazyColumn(
                Modifier.fillMaxSize().widthIn(max = 760.dp),
                contentPadding = PaddingValues(start = horizontal, end = horizontal, bottom = nuvioSafeBottomPadding(extra = 16.dp)),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val legacy = state.capabilities.partyContractVersion < 2
                if (legacy && (state.requests.isNotEmpty() || state.partyInvites.isNotEmpty())) {
                    item(key = "legacy-label") { SocialSectionLabel("New", state.requests.size + state.partyInvites.size) }
                    state.requests.forEach { request ->
                        item(key = "legacy-request:${request.id}") {
                            SocialInboxCard(
                                person = request.sender,
                                text = socialRich(request.sender.displayName to true, " wants to be friends" to false),
                                time = relativeTimeLabel(parseSocialTimestampMs(request.createdAt), nowMs),
                                unread = true,
                                actions = listOf(
                                    Triple("Accept", true) { onRespondLegacy(request.id, true) },
                                    Triple("Decline", false) { onRespondLegacy(request.id, false) },
                                ),
                            )
                        }
                    }
                    state.partyInvites.forEach { invite ->
                        item(key = "legacy-invite:${invite.id}") {
                            SocialInboxCard(
                                person = invite.sender,
                                text = socialRich(invite.sender.displayName to true, " invited you to watch " to false, invite.content.title to true),
                                time = relativeTimeLabel(parseSocialTimestampMs(invite.createdAt), nowMs),
                                unread = true,
                                art = invite.content.artRef(),
                                actions = listOf(Triple("Join", true) { onJoinLegacyInvite(invite.partyId) }),
                            )
                        }
                    }
                }
                if (!legacy && timeline.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "Nothing here yet. Friend requests and Watch Together invites land here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
                if (fresh.isNotEmpty()) {
                    item(key = "new-label") { SocialSectionLabel("New", fresh.size) }
                    fresh.forEach { entry ->
                        item(key = entry.key) { SocialInboxEntryCard(entry, nowMs, unread = true, onAction, onOpenContent) }
                    }
                }
                if (earlier.isNotEmpty()) {
                    item(key = "earlier-label") { SocialSectionLabel("Earlier", modifier = Modifier.padding(top = 8.dp)) }
                    earlier.forEach { entry ->
                        item(key = entry.key) { SocialInboxEntryCard(entry, nowMs, unread = false, onAction, onOpenContent) }
                    }
                }
            }
        }
    }
}

/** One line of the inbox: a derived notification or a stored event. */
private sealed interface SocialInboxEntry {
    val key: String
    val createdAt: String
    val unread: Boolean

    data class Notice(val notification: SocialNotification) : SocialInboxEntry {
        override val key get() = "n:${notification.id}"
        override val createdAt get() = notification.createdAt
        override val unread get() = notification.readAt == null
    }

    data class Event(val event: SocialInboxEvent) : SocialInboxEntry {
        override val key get() = "e:${event.id}"
        override val createdAt get() = event.createdAt
        override val unread get() = event.readAt == null
    }
}

@Composable
private fun SocialInboxEntryCard(
    entry: SocialInboxEntry,
    nowMs: Long,
    unread: Boolean,
    onAction: (SocialNotification, SocialNotificationAction) -> Unit,
    onOpenContent: (contentType: String, contentId: String, title: String) -> Unit,
) {
    when (entry) {
        is SocialInboxEntry.Notice -> SocialNotificationCard(entry.notification, nowMs, unread, onAction)
        is SocialInboxEntry.Event -> SocialInboxEventCard(entry.event, nowMs, unread, onOpenContent)
    }
}

@Composable
private fun SocialInboxEventCard(
    event: SocialInboxEvent,
    nowMs: Long,
    unread: Boolean,
    onOpenContent: (contentType: String, contentId: String, title: String) -> Unit,
) {
    val name = event.actor.displayName.ifBlank { "@${event.actor.handle}" }
    val payload = event.payload
    val title = payload.title
    val open: (() -> Unit)? = if (payload.contentId != null && payload.contentType != null && title != null) {
        { onOpenContent(payload.contentType, payload.contentId, title) }
    } else {
        null
    }
    val text = when (event.kind) {
        SocialInboxEvent.FriendAccepted -> socialRich(name to true, " accepted your friend request" to false)
        SocialInboxEvent.FriendWatching ->
            if (title != null) socialRich(name to true, " started watching " to false, title to true)
            else socialRich(name to true, " started watching" to false)
        else ->
            if (title != null) socialRich(name to true, " recommends " to false, title to true)
            else socialRich(name to true, " sent you a recommendation" to false)
    }
    SocialInboxCard(
        person = event.actor,
        text = text,
        note = payload.note?.let { "“$it”" },
        time = relativeTimeLabel(parseSocialTimestampMs(event.createdAt), nowMs),
        unread = unread,
        art = title?.let {
            SocialArtRef(it, payload.contentType, payload.contentId, payload.poster, listOf(payload.background), payload.videoId, payload.season, payload.episode)
        },
        actions = if (event.kind == SocialInboxEvent.Recommendation && open != null) listOf(Triple("Open", true, open)) else emptyList(),
        onClick = open,
    )
}

@Composable
private fun SocialNotificationCard(
    notification: SocialNotification,
    nowMs: Long,
    unread: Boolean,
    onAction: (SocialNotification, SocialNotificationAction) -> Unit,
) {
    val name = notification.actor.displayName.ifBlank { "@${notification.actor.handle}" }
    val title = notification.contentSummary?.title
    val outcome = notification.partyNoticeOutcome()
    val lapsed = if (notification.kind == SocialNotificationKind.FriendRequest) {
        notification.state.lowercase() in setOf("cancelled", "canceled", "expired")
    } else {
        outcome == SocialPartyNoticeOutcome.Lapsed
    }
    val text = when (notification.kind) {
        SocialNotificationKind.FriendRequest -> when (notification.state.lowercase()) {
            "accepted", "consumed" -> socialRich("You and " to false, name to true, " are now friends" to false)
            "declined" -> socialRich("You declined " to false, name to true, "'s friend request" to false)
            else -> socialRich(name to true, " wants to be friends" to false)
        }
        SocialNotificationKind.PartyInvitation -> when (outcome) {
            SocialPartyNoticeOutcome.Accepted ->
                if (title != null) socialRich("You watched " to false, title to true, " with " to false, name to true)
                else socialRich("You joined " to false, name to true, "'s Watch Together" to false)
            SocialPartyNoticeOutcome.Declined ->
                if (title != null) socialRich("You declined " to false, name to true, "'s invite to " to false, title to true)
                else socialRich("You declined " to false, name to true, "'s invite" to false)
            else ->
                if (title != null) socialRich(name to true, " invited you to watch " to false, title to true)
                else socialRich(name to true, " invited you to Watch Together" to false)
        }
        SocialNotificationKind.WatchingNowJoinRequest -> when (outcome) {
            SocialPartyNoticeOutcome.Accepted ->
                if (title != null) socialRich(name to true, " joined your playback of " to false, title to true)
                else socialRich(name to true, " joined your playback" to false)
            SocialPartyNoticeOutcome.Declined ->
                if (title != null) socialRich("You declined " to false, name to true, "'s request to join " to false, title to true)
                else socialRich("You declined " to false, name to true, "'s request to join" to false)
            else ->
                if (title != null) socialRich(name to true, " asked to join your playback of " to false, title to true)
                else socialRich(name to true, " asked to join your playback" to false)
        }
    }
    val time = relativeTimeLabel(parseSocialTimestampMs(notification.createdAt), nowMs)
    val actions = notification.availableActions
        .sortedBy { it.ordinal }
        .map { action ->
            Triple(
                when (action) {
                    SocialNotificationAction.Accept -> "Accept"
                    SocialNotificationAction.Decline -> "Decline"
                    SocialNotificationAction.Join -> "Join"
                },
                action != SocialNotificationAction.Decline,
            ) { onAction(notification, action) }
        }
    SocialInboxCard(
        person = notification.actor,
        text = text,
        time = if (lapsed) listOf(time, "expired").filter(String::isNotBlank).joinToString(" · ") else time,
        unread = unread && !lapsed,
        dim = lapsed,
        art = notification.contentSummary?.artRef(),
        actions = actions,
    )
}

@Composable
private fun SocialInboxCard(
    person: SocialProfileSummary,
    text: AnnotatedString,
    time: String,
    unread: Boolean,
    dim: Boolean = false,
    art: SocialArtRef? = null,
    actions: List<Triple<String, Boolean, () -> Unit>> = emptyList(),
    note: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().alpha(if (dim) 0.55f else 1f)
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (unread) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(42.dp)) {
            SocialAvatar(person.displayName, person.avatarUrl, person.avatarColorHex, 42.dp)
            if (unread) {
                Box(
                    Modifier.align(Alignment.TopEnd).size(12.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant).padding(2.dp)
                        .clip(CircleShape).background(SocialLiveColor),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (note != null) {
                    Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                if (time.isNotBlank()) {
                    Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (actions.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    actions.forEach { (label, primary, run) -> SocialPillButton(label, run, primary = primary) }
                }
            }
        }
        if (art != null) SocialStill(art.artwork(), art.title, 72.dp)
    }
}

/** "**Seraph** recommends **Andor**": the parts flagged true are emphasised. */
@Composable
private fun socialRich(vararg parts: Pair<String, Boolean>): AnnotatedString {
    val strong = MaterialTheme.colorScheme.onBackground
    val soft = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.78f)
    return buildAnnotatedString {
        parts.forEach { (text, bold) ->
            withStyle(SpanStyle(color = if (bold) strong else soft, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)) {
                append(text)
            }
        }
    }
}

// --- sheets ----------------------------------------------------------------------------------

/** A bottom sheet on phones and tablets, a centred dialog on a desktop window. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SocialSheet(wide: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    if (wide) {
        Dialog(onDismissRequest = onDismiss) {
            Surface(
                modifier = Modifier.widthIn(max = 560.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) { content() }
                }
            }
        }
    } else {
        NuvioModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            ) { content() }
        }
    }
}

/**
 * What a tap on a Watching Now card opens: the session, what joining will do, and the one action -
 * so a stray tap never starts playback.
 */
@Composable
internal fun SocialJoinSheetContent(
    item: WatchingNowItem,
    affordance: WatchingNowJoinAffordance,
    onJoin: () -> Unit,
    onCancelRequest: () -> Unit,
    onDetails: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val who = socialPeopleLabel(item.socialPeople())
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            SocialStill(listOf(item.background, item.episodeThumbnail, item.poster), item.title, 124.dp, progress = item.progressFraction)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    SocialFaces(item.socialPeople(), 20.dp)
                    Text(item.socialSentence(), style = MaterialTheme.typography.labelLarge, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(item.socialMeta(), if (item.state == SocialPlaybackState.playing) "Playing" else "Paused")
                        .filter(String::isNotBlank).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            when (affordance) {
                WatchingNowJoinAffordance.Join -> "$who lets friends join directly. You'll start in sync with them."
                WatchingNowJoinAffordance.AskToJoin -> "$who gets a request. You'll join as soon as they accept."
                is WatchingNowJoinAffordance.Requested -> "Waiting for $who to accept your request."
                WatchingNowJoinAffordance.Joining -> "Joining…"
                WatchingNowJoinAffordance.InYourParty -> "You're already watching together."
                WatchingNowJoinAffordance.None -> "$who isn't taking joiners right now."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (affordance) {
                WatchingNowJoinAffordance.Join ->
                    SocialPillButton("Join now", onJoin, Modifier.fillMaxWidth(), primary = true, compact = false)
                WatchingNowJoinAffordance.AskToJoin ->
                    SocialPillButton("Ask to join", onJoin, Modifier.fillMaxWidth(), primary = true, compact = false)
                is WatchingNowJoinAffordance.Requested ->
                    SocialPillButton(
                        if (affordance.cancelling) "Cancelling…" else "Cancel request",
                        onCancelRequest,
                        Modifier.fillMaxWidth(),
                        compact = false,
                        enabled = !affordance.cancelling,
                    )
                else -> Unit
            }
            SocialPillButton("View details", onDetails, Modifier.fillMaxWidth(), compact = false)
        }
    }
}

/**
 * A friend: who they are, what they are doing now, what they have shared lately, and removing them.
 * The together stat, per-friend privacy and Recommend land with the Stage 2 backend.
 */
@Composable
internal fun SocialProfileContent(
    friend: SocialProfileSummary,
    /** Null on a backend without Social V2: the switches are not drawn rather than drawn dead. */
    prefs: SocialFriendPrefs?,
    stats: SocialTogetherStats?,
    onSetPrefs: (hide: Boolean?, notify: Boolean?) -> Unit,
    watching: WatchingNowItem?,
    affordance: WatchingNowJoinAffordance,
    groups: List<FriendActivityGroup>,
    nowMs: Long,
    onOpenWatching: (WatchingNowItem) -> Unit,
    onWatchingDetails: (WatchingNowItem) -> Unit,
    onOpenContent: (contentType: String, contentId: String, title: String) -> Unit,
    onRemove: () -> Unit,
) {
    var confirmRemove by remember { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val live = watching?.state == SocialPlaybackState.playing
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(80.dp), contentAlignment = Alignment.Center) {
                if (live) Box(Modifier.fillMaxSize().border(2.5.dp, SocialLiveColor, CircleShape))
                SocialAvatar(friend.displayName, friend.avatarUrl, friend.avatarColorHex, 70.dp)
            }
            Column(Modifier.weight(1f)) {
                Text(friend.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("@${friend.handle}", style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (watching != null) {
            Spacer(Modifier.height(16.dp))
            SocialWatchingCard(
                item = watching,
                affordance = affordance,
                onClick = { onOpenWatching(watching) },
                onDetails = { onWatchingDetails(watching) },
                modifier = Modifier.fillMaxWidth(),
                height = 150.dp,
            )
        }
        // Quiet on purpose: a fun fact, not a scoreboard.
        stats?.takeIf { it.parties > 0 }?.let { together ->
            Text(
                "${socialTogetherDuration(together.seconds)} watched together · " +
                    "${together.parties} ${if (together.parties == 1) "party" else "parties"}",
                style = MaterialTheme.typography.labelMedium,
                color = muted,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        SocialSectionLabel("Recently watched")
        if (groups.isEmpty()) {
            Text(
                "Nothing shared recently.",
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        } else {
            groups.take(6).forEach { group ->
                FriendActivityStillRow(
                    group = group,
                    nowMs = nowMs,
                    onOpen = { onOpenContent(group.contentType, group.contentId, group.title) },
                    showNames = false,
                )
            }
        }
        stats?.titles?.takeIf { it.isNotEmpty() }?.let { titles ->
            SocialSectionLabel("Watched together", modifier = Modifier.padding(top = 12.dp))
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                titles.take(3).forEach { together ->
                    Column(
                        Modifier.weight(1f).then(
                            if (together.contentId != null && together.contentType != null) {
                                Modifier.clickable { onOpenContent(together.contentType, together.contentId, together.title) }
                            } else {
                                Modifier
                            },
                        ),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        SocialArtwork(
                            SocialArtRef(together.title, together.contentType, together.contentId, together.poster).artwork(),
                            together.title,
                            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)),
                        )
                        Text(together.title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${together.parties} ${if (together.parties == 1) "party" else "parties"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = muted,
                        )
                    }
                }
                repeat(3 - titles.take(3).size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (prefs != null) {
            SocialSectionLabel("Privacy", modifier = Modifier.padding(top = 12.dp))
            SocialSwitchRow("Hide my activity from ${friend.displayName}", prefs.hideActivity) { onSetPrefs(it, null) }
            SocialSwitchRow("Notify me when ${friend.displayName} starts watching", prefs.notifyWhenWatching) { onSetPrefs(null, it) }
        }
        TextButton(onClick = { confirmRemove = true }, modifier = Modifier.padding(top = 8.dp)) {
            Text("Remove friend", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${friend.displayName}?") },
            text = { Text("You'll stop seeing each other's activity. You can send a new request later.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    onRemove()
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SocialSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** "14 h", "45 min", "under a minute". */
internal fun socialTogetherDuration(seconds: Long): String = when {
    seconds >= 3600 -> "${(seconds + 1800) / 3600} h"
    seconds >= 60 -> "${seconds / 60} min"
    else -> "under a minute"
}

@Composable
private fun SocialInviteCodeDialog(
    code: String,
    onCodeChange: (String) -> Unit,
    onJoin: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join with an invite code") },
        text = {
            OutlinedTextField(
                value = code,
                onValueChange = onCodeChange,
                singleLine = true,
                label = { Text("Invite code") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (code.isNotBlank()) onJoin() }),
            )
        },
        confirmButton = { TextButton(onClick = onJoin, enabled = code.isNotBlank()) { Text("Join") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// --- shared furniture ------------------------------------------------------------------------

@Composable
private fun SocialPanel(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(NuvioTokens.Radius.compactCard),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
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

/** A card-shaped placeholder for the first load, so the tab does not open as a blank column. */
@Composable
private fun SocialSkeleton(height: Dp) {
    val transition = rememberInfiniteTransition(label = "social-skeleton")
    val shimmer by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "social-skeleton-alpha",
    )
    Box(
        Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(20.dp))
            .alpha(shimmer).background(MaterialTheme.colorScheme.surfaceVariant),
    )
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
        // ⚠ A non-blank URL that failed to load - a deleted avatar, an expired signed URL, being
        // offline - must still fall back to the initial, or friends become indistinguishable coloured
        // circles. Keyed on the URL so a later, working avatar is attempted.
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
                // A blank name would `take(1)` to nothing and leave the same empty circle.
                name.trim().take(1).uppercase().ifBlank { "?" },
                fontSize = (size.value * 0.42f).sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
    }
}
