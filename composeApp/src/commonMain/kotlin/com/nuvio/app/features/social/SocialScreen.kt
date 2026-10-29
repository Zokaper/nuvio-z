package com.nuvio.app.features.social

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.nuvio.app.features.settings.SocialSettingsSections
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
import nuvio.composeapp.generated.resources.social_accept
import nuvio.composeapp.generated.resources.social_add
import nuvio.composeapp.generated.resources.social_add_by_handle
import nuvio.composeapp.generated.resources.social_already_friends
import nuvio.composeapp.generated.resources.social_ask_to_join
import nuvio.composeapp.generated.resources.social_back
import nuvio.composeapp.generated.resources.social_cancel
import nuvio.composeapp.generated.resources.social_cancel_request
import nuvio.composeapp.generated.resources.social_cancelling
import nuvio.composeapp.generated.resources.social_close
import nuvio.composeapp.generated.resources.social_decline
import nuvio.composeapp.generated.resources.social_details
import nuvio.composeapp.generated.resources.social_expired
import nuvio.composeapp.generated.resources.social_friend_paused
import nuvio.composeapp.generated.resources.social_friend_watching
import nuvio.composeapp.generated.resources.social_friends
import nuvio.composeapp.generated.resources.social_handle_friends
import nuvio.composeapp.generated.resources.social_handle_save_failed
import nuvio.composeapp.generated.resources.social_hide_from
import nuvio.composeapp.generated.resources.social_in_party
import nuvio.composeapp.generated.resources.social_inbox
import nuvio.composeapp.generated.resources.social_inbox_empty
import nuvio.composeapp.generated.resources.social_invite_code
import nuvio.composeapp.generated.resources.social_invite_join_title
import nuvio.composeapp.generated.resources.social_join
import nuvio.composeapp.generated.resources.social_join_ask
import nuvio.composeapp.generated.resources.social_join_closed
import nuvio.composeapp.generated.resources.social_join_direct
import nuvio.composeapp.generated.resources.social_join_now
import nuvio.composeapp.generated.resources.social_join_together
import nuvio.composeapp.generated.resources.social_join_waiting
import nuvio.composeapp.generated.resources.social_joining
import nuvio.composeapp.generated.resources.social_no_friends
import nuvio.composeapp.generated.resources.social_nothing_shared
import nuvio.composeapp.generated.resources.social_try_again
import nuvio.composeapp.generated.resources.social_notify_when
import nuvio.composeapp.generated.resources.social_open
import nuvio.composeapp.generated.resources.social_party_count
import nuvio.composeapp.generated.resources.social_privacy
import nuvio.composeapp.generated.resources.social_read
import nuvio.composeapp.generated.resources.social_recently_watched_label
import nuvio.composeapp.generated.resources.social_remove
import nuvio.composeapp.generated.resources.social_remove_friend
import nuvio.composeapp.generated.resources.social_remove_friend_body
import nuvio.composeapp.generated.resources.social_remove_title
import nuvio.composeapp.generated.resources.social_request_incoming
import nuvio.composeapp.generated.resources.social_request_sent
import nuvio.composeapp.generated.resources.social_requests
import nuvio.composeapp.generated.resources.social_return
import nuvio.composeapp.generated.resources.social_rich_asked_join
import nuvio.composeapp.generated.resources.social_rich_asked_join_title
import nuvio.composeapp.generated.resources.social_rich_declined_friend
import nuvio.composeapp.generated.resources.social_rich_declined_invite
import nuvio.composeapp.generated.resources.social_rich_declined_invite_title
import nuvio.composeapp.generated.resources.social_rich_declined_join
import nuvio.composeapp.generated.resources.social_rich_declined_join_title
import nuvio.composeapp.generated.resources.social_rich_friend_accepted
import nuvio.composeapp.generated.resources.social_rich_friend_watching
import nuvio.composeapp.generated.resources.social_rich_friend_watching_title
import nuvio.composeapp.generated.resources.social_rich_invited
import nuvio.composeapp.generated.resources.social_rich_invited_title
import nuvio.composeapp.generated.resources.social_rich_joined_party
import nuvio.composeapp.generated.resources.social_rich_joined_playback
import nuvio.composeapp.generated.resources.social_rich_joined_playback_title
import nuvio.composeapp.generated.resources.social_rich_now_friends
import nuvio.composeapp.generated.resources.social_rich_rec_started
import nuvio.composeapp.generated.resources.social_rich_rec_started_title
import nuvio.composeapp.generated.resources.social_rich_recommendation
import nuvio.composeapp.generated.resources.social_rich_recommends
import nuvio.composeapp.generated.resources.social_rich_wants_friends
import nuvio.composeapp.generated.resources.social_rich_watched_with
import nuvio.composeapp.generated.resources.social_search_failed
import nuvio.composeapp.generated.resources.social_search_handles
import nuvio.composeapp.generated.resources.social_search_no_match
import nuvio.composeapp.generated.resources.social_search_too_short
import nuvio.composeapp.generated.resources.social_sent
import nuvio.composeapp.generated.resources.social_settings_title
import nuvio.composeapp.generated.resources.social_together_line
import nuvio.composeapp.generated.resources.social_unread
import nuvio.composeapp.generated.resources.social_waiting_reply
import nuvio.composeapp.generated.resources.social_wants_friends_subtitle
import nuvio.composeapp.generated.resources.social_watched_together
import nuvio.composeapp.generated.resources.social_watching_now_label
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.StringResource

/** Live presence, which is the reason to open this tab; fixed rather than themed, as in the lobby. */
internal val SocialLiveColor = Color(0xFF6FD08C)

/** How a one-line message under the search field should read. */
internal enum class SocialFeedbackTone { Neutral, Positive, Negative }

internal data class SocialFeedback(val message: String, val tone: SocialFeedbackTone)

/** What sits over the tab: the inbox page, a friend's profile, or the join sheet for one session. */
internal sealed interface SocialOverlay {
    data object Inbox : SocialOverlay
    data object Recommended : SocialOverlay
    data object Settings : SocialOverlay
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
                feedback = SocialFeedback(getString(Res.string.social_search_too_short), SocialFeedbackTone.Neutral)
            } else {
                isSearching = true
                feedback = null
                SocialRepository.searchProfiles(query)
                    .onSuccess { results ->
                        searchResults = results
                        feedback = if (results.isEmpty()) {
                            SocialFeedback(getString(Res.string.social_search_no_match, query), SocialFeedbackTone.Neutral)
                        } else {
                            null
                        }
                    }
                    .onFailure { error ->
                        searchResults = emptyList()
                        feedback = SocialFeedback(error.message ?: getString(Res.string.social_search_failed), SocialFeedbackTone.Negative)
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
                    if (alreadyFriends) getString(Res.string.social_already_friends) else getString(Res.string.social_request_incoming),
                    SocialFeedbackTone.Neutral,
                )
                return@launch
            }
            // On success the row is dropped, because the request is now pending rather than sendable.
            SocialRepository.sendFriendRequest(profile.profileId)
                .onSuccess {
                    searchResults = searchResults.filterNot { it.profileId == profile.profileId }
                    feedback = SocialFeedback(getString(Res.string.social_request_sent, profile.handle), SocialFeedbackTone.Positive)
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

    val navigation by SocialNavigation.pending.collectAsStateWithLifecycle()
    SocialFeed(
        model = SocialFeedModel(
            state = state,
            navigation = navigation,
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
            onRecover = { SocialRepository.recoverIfStale() },
            onHandleChange = { handle = normalizeSocialHandle(it).take(24) },
            onSaveHandle = {
                scope.launch {
                    // A handle that never saved must not look like one that did.
                    SocialRepository.setupHandle(handle)
                        .onFailure { error -> handleMessage = error.message ?: getString(Res.string.social_handle_save_failed) }
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
            onCancelFriendRequest = { id -> scope.launch { SocialRepository.cancelFriendRequest(id) } },
            onNavigationConsumed = SocialNavigation::consume,
            onMarkInboxRead = { ids -> scope.launch { SocialRepository.markInboxRead(ids) } },
            onLoadTogetherStats = { friend -> SocialRepository.togetherStats(friend).getOrNull() },
            onSetAudienceMember = { setting, friend, allowed ->
                scope.launch { SocialRepository.setAudienceMember(setting, friend, allowed) }
            },
            onLoadFriendHistory = { friend, before -> SocialRepository.friendHistory(friend, before) },
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
    /** A place a Social notification asked for; opened once, then reported through [SocialFeedActions.onNavigationConsumed]. */
    val navigation: SocialNavigationTarget? = null,
)

/** The Social tab's callbacks, exactly as [SocialScreen] defines them. */
internal class SocialFeedActions(
    val onRefresh: () -> Unit = {},
    val onRecover: () -> Unit = {},
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
    val onCancelFriendRequest: (String) -> Unit = {},
    val onNavigationConsumed: (SocialNavigationTarget) -> Unit = {},
    val onMarkInboxRead: (Set<String>) -> Unit = {},
    val onLoadTogetherStats: suspend (friendProfileId: String) -> SocialTogetherStats? = { null },
    /** A friend's profile switch: let them in, or keep them out, of one of this profile's audiences. */
    val onSetAudienceMember: (SocialAudienceSetting, friendProfileId: String, allowed: Boolean) -> Unit = { _, _, _ -> },
    val onLoadFriendHistory: suspend (friendProfileId: String, before: SocialActivityCursor?) -> Result<FriendHistoryPage> =
        { _, _ -> Result.success(FriendHistoryPage(emptyList(), null)) },
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
            // A Social notification asked for a place in this tab (see `SocialNavigation`).
            val navigation = model.navigation
            LaunchedEffect(navigation) {
                when (navigation) {
                    null -> return@LaunchedEffect
                    SocialNavigationTarget.FriendRequests -> {
                        overlay = null
                        tab = SocialTab.Friends
                    }
                    SocialNavigationTarget.Inbox -> overlay = SocialOverlay.Inbox
                    SocialNavigationTarget.Recommended -> overlay = SocialOverlay.Recommended
                    is SocialNavigationTarget.WatchingSession -> overlay = SocialOverlay.Join(navigation.sessionKey)
                }
                actions.onNavigationConsumed(navigation)
            }
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
                    unread = state.inboxUnreadCount,
                    recommended = remember(state.inbox) { recommendedToYou(state.inbox).size },
                    recommendedNew = state.unreadRecommendations > 0,
                    topInset = topInset,
                    underTopChrome = topChromePadding != null,
                    horizontal = horizontal,
                    wide = wide,
                    showInvite = wide && ready && state.capabilities.watchPartyEnabled,
                    showInbox = ready,
                    onInvite = { inviteOpen = true },
                    onInbox = { overlay = SocialOverlay.Inbox },
                    onRecommended = { overlay = SocialOverlay.Recommended },
                    onSettings = { overlay = SocialOverlay.Settings },
                )
                when {
                    !state.capabilities.socialEnabled && (state.isLoading || state.errorMessage != null) -> SocialMessageColumn(horizontal) {
                        if (state.isLoading) SocialSkeleton(SocialWatchingCardHeight) else {
                            SocialNotice(state.errorMessage.orEmpty(), MaterialTheme.colorScheme.error)
                            SocialPillButton(stringResource(Res.string.social_try_again), actions.onRecover)
                        }
                    }
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
                SocialOverlay.Settings -> SocialSettingsOverlay(wide = wide, topInset = topInset, onDismiss = { overlay = null })
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
                SocialOverlay.Recommended -> SocialRecommendedPage(
                    state = state,
                    wide = wide,
                    topInset = topInset,
                    onBack = { overlay = null },
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
                        val nowMs = remember { currentEpochMs() }
                        SocialFriendProfilePage(
                            friend = friend,
                            friends = state.friends,
                            audiences = state.audiences,
                            watching = watching,
                            affordance = watching?.let(model.joinAffordance) ?: WatchingNowJoinAffordance.None,
                            wide = wide,
                            topInset = topInset,
                            utcOffsetMs = socialUtcOffsetMs(nowMs),
                            onBack = { overlay = null },
                            onJoin = actions.onStartParty,
                            onCancelRequest = actions.onCancelJoinRequest,
                            onWatchingDetails = openDetails,
                            onOpenContent = actions.onOpenContent,
                            onSetAudienceMember = actions.onSetAudienceMember,
                            onRemove = {
                                actions.onRemoveFriend(friend.profileId)
                                overlay = null
                            },
                            loadStats = { id -> if (state.socialV2Backend) actions.onLoadTogetherStats(id) else null },
                            loadHistory = actions.onLoadFriendHistory,
                        )
                    }
                }
                is SocialOverlay.Join -> {
                    val item = orderWatchingNowForDisplay(state.watchingNow).firstOrNull { it.socialSessionKey() == current.sessionKey }
                    if (item == null) {
                        // The session ended while the sheet was open; there is nothing left to join.
                        LaunchedEffect(current) { overlay = null }
                    } else {
                        SocialSheet(wide = wide, onDismiss = { overlay = null }, edgeToEdge = true) {
                            SocialJoinSheetContent(
                                item = item,
                                affordance = model.joinAffordance(item),
                                wide = wide,
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
    recommended: Int,
    recommendedNew: Boolean,
    topInset: Dp,
    underTopChrome: Boolean,
    horizontal: Dp,
    wide: Boolean,
    showInvite: Boolean,
    showInbox: Boolean,
    onInvite: () -> Unit,
    onInbox: () -> Unit,
    onRecommended: () -> Unit,
    onSettings: () -> Unit,
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
                        pluralStringResource(Res.plurals.social_handle_friends, friendCount, me.handle, friendCount),
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
        if (showInvite) SocialPillButton(stringResource(Res.string.social_invite_code), onInvite)
        if (showInbox) SocialRecommendedButton(recommended, recommendedNew, wide, onRecommended)
        if (showInbox) SocialInboxButton(unread, onInbox)
        // Social settings live here, not only in the Settings tab (2026-10-16). Shown whenever the
        // tab itself is: the handle step and the off notice both still have privacy to set.
        SocialSettingsButton(onSettings)
    }
}

@Composable
private fun SocialSettingsButton(onClick: () -> Unit) {
    val label = stringResource(Res.string.social_settings_title)
    Box(
        Modifier.size(34.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurface)
    }
}

// --- settings --------------------------------------------------------------------------------

/**
 * The gear's page: [SocialSettingsSections], the same body Settings -> Social draws. A right-hand
 * panel over the dashboard on wide layouts (the feed stays in view), a full page on phones.
 */
@Composable
private fun SocialSettingsOverlay(wide: Boolean, topInset: Dp, onDismiss: () -> Unit) {
    SocialPageShell(title = stringResource(Res.string.social_settings_title), wide = wide, topInset = topInset, onDismiss = onDismiss) { horizontal, bottom ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = horizontal, end = horizontal, top = 8.dp, bottom = bottom),
        ) {
            SocialSettingsSections(isTablet = false, inSocialTab = true)
        }
    }
}

/**
 * The frame the Social tab's secondary pages share (Inbox, Social settings): a right-hand panel over the
 * dashboard on wide layouts, so the feed stays in view, and a full page with a back arrow on phones.
 * [content] gets the horizontal padding and the bottom padding it should use.
 */
@Composable
internal fun SocialPageShell(
    title: String,
    wide: Boolean,
    topInset: Dp,
    onDismiss: () -> Unit,
    content: @Composable (horizontal: Dp, bottom: Dp) -> Unit,
) {
    PlatformBackHandler(enabled = true, onBack = onDismiss)
    if (wide) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            )
            Surface(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(SocialSidePanelWidth)
                    .padding(top = topInset + 12.dp, bottom = 12.dp, end = 12.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, stringResource(Res.string.social_close)) }
                        }
                        content(20.dp, 20.dp)
                    }
                }
            }
        }
    } else {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(top = topInset)) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(Res.string.social_back)) }
                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                content(16.dp, nuvioSafeBottomPadding(extra = 16.dp))
            }
        }
    }
}

private val SocialSidePanelWidth = 460.dp

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
                Text(stringResource(Res.string.social_in_party), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                SocialPillButton(stringResource(Res.string.social_return), { actions.onReturnParty(partyId) }, primary = true)
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
        SocialSectionLabel(stringResource(Res.string.social_watching_now_label), watching.size.takeIf { it > 0 }, live = watching.isNotEmpty())
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
        item(key = "recent-label") { SocialSectionLabel(stringResource(Res.string.social_recently_watched_label), modifier = Modifier.padding(top = 8.dp)) }
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
            placeholder = { Text(stringResource(Res.string.social_add_by_handle), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { actions.onRunSearch() }),
            trailingIcon = {
                IconButton(onClick = actions.onRunSearch, enabled = !model.isSearching) {
                    if (model.isSearching) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Search, stringResource(Res.string.social_search_handles))
                    }
                }
            },
        )
        if (showInvite) SocialPillButton(stringResource(Res.string.social_invite_code), onInvite)
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
            trailing = { SocialPillButton(stringResource(Res.string.social_add), { actions.onSendRequest(profile) }, primary = true) },
        )
    }

    // Incoming requests: the notification form on contract v2, the legacy list before it.
    val requestNotifications = state.notifications.filter {
        it.kind == SocialNotificationKind.FriendRequest && it.availableActions.isNotEmpty()
    }
    val legacyRequests = if (state.capabilities.partyContractVersion >= 2) emptyList() else state.requests
    val requestCount = requestNotifications.size + legacyRequests.size
    if (requestCount > 0) {
        SocialSectionLabel(stringResource(Res.string.social_requests), requestCount, modifier = Modifier.padding(top = 10.dp))
        requestNotifications.forEach { request ->
            SocialPersonLine(
                person = request.actor,
                subtitle = stringResource(Res.string.social_wants_friends_subtitle, request.actor.handle),
                below = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (SocialNotificationAction.Accept in request.availableActions) {
                            SocialPillButton(stringResource(Res.string.social_accept), { actions.onNotificationAction(request, SocialNotificationAction.Accept) }, primary = true)
                        }
                        if (SocialNotificationAction.Decline in request.availableActions) {
                            SocialPillButton(stringResource(Res.string.social_decline), { actions.onNotificationAction(request, SocialNotificationAction.Decline) })
                        }
                    }
                },
            )
        }
        legacyRequests.forEach { request ->
            SocialPersonLine(
                person = request.sender,
                subtitle = stringResource(Res.string.social_wants_friends_subtitle, request.sender.handle),
                below = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SocialPillButton(stringResource(Res.string.social_accept), { actions.onRespondRequest(request.id, true) }, primary = true)
                        SocialPillButton(stringResource(Res.string.social_decline), { actions.onRespondRequest(request.id, false) })
                    }
                },
            )
        }
    }

    if (state.sentRequests.isNotEmpty()) {
        SocialSectionLabel(stringResource(Res.string.social_sent), state.sentRequests.size, modifier = Modifier.padding(top = 10.dp))
        state.sentRequests.forEach { sent ->
            SocialPersonLine(
                person = sent.receiver,
                subtitle = stringResource(Res.string.social_waiting_reply, sent.receiver.handle),
                trailing = { SocialPillButton(stringResource(Res.string.social_cancel), { actions.onCancelFriendRequest(sent.id) }) },
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
    SocialSectionLabel(stringResource(Res.string.social_friends), friends.size.takeIf { it > 0 }, modifier = Modifier.padding(top = 10.dp))
    if (friends.isEmpty()) {
        Text(
            stringResource(Res.string.social_no_friends),
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
                    playing -> stringResource(Res.string.social_friend_watching, watching.title)
                    else -> stringResource(Res.string.social_friend_paused, watching.title)
                },
                live = playing,
                onClick = { onOpenProfile(friend.profileId) },
            )
        }
    }
}

// --- inbox -----------------------------------------------------------------------------------

/**
 * Everything that happened to you on Social, newest first, each message its own card, in two groups:
 * Unread and Read. Opening the page marks what is on it read, and so does anything that arrives while
 * it is open; "Unread" is everything that was not already read when the page opened, so the grouping
 * does not shift under the reader once the read marks land.
 *
 * ⚠ **Not "what was unread at open".** That was the rule until 2026-10-19, and it filed an invite that
 * arrived while the Inbox was open (or a moment before its state caught up) under "Earlier" with a
 * "Just now" time on it. The snapshot is now of what was *read*, so a newcomer is unread by default.
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
    // Derived notifications and stored events, one timeline. A kind this build does not know is skipped.
    // A finished party notice leaves after a day: see [SocialFinishedNoticeRetentionMs].
    // A recommendation lives on its own page (2026-10-22), never also a card here.
    val entries = state.notifications.filterNot { it.isRetired(nowMs) }.map { SocialInboxEntry.Notice(it) } +
        state.inbox.filter { it.kind in SocialInboxEvent.Known && it.kind != SocialInboxEvent.Recommendation }
            .map { SocialInboxEntry.Event(it) }
    val timeline = entries.sortedByDescending { parseSocialTimestampMs(it.createdAt) ?: Long.MIN_VALUE }
    val readAtOpen = remember { timeline.filterNot { it.unread }.map { it.key }.toSet() }
    // Keyed on what is unread, so a message that arrives while the page is open is marked read too.
    val unreadNotices = state.notifications.filter { it.readAt == null }.map { it.id }.toSet()
    // Recommendations are seen on their own page, so opening the Inbox leaves them unread.
    val unreadEvents = state.inbox.filter { it.readAt == null && it.kind != SocialInboxEvent.Recommendation }.map { it.id }.toSet()
    LaunchedEffect(unreadNotices, unreadEvents) {
        if (unreadNotices.isNotEmpty()) onMarkRead(unreadNotices)
        if (unreadEvents.isNotEmpty()) onMarkInboxRead(unreadEvents)
    }
    val (fresh, earlier) = splitInboxUnreadRead(timeline, readAtOpen) { it.key }
    // A right-hand panel over the dashboard on wide layouts (2026-10-16), a full page on phones.
    SocialPageShell(title = stringResource(Res.string.social_inbox), wide = wide, topInset = topInset, onDismiss = onBack) { horizontal, bottom ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = horizontal, end = horizontal, bottom = bottom),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val legacy = state.capabilities.partyContractVersion < 2
            if (legacy && (state.requests.isNotEmpty() || state.partyInvites.isNotEmpty())) {
                item(key = "legacy-label") { SocialSectionLabel(stringResource(Res.string.social_unread), state.requests.size + state.partyInvites.size) }
                state.requests.forEach { request ->
                    item(key = "legacy-request:${request.id}") {
                        SocialInboxCard(
                            person = request.sender,
                            text = socialRich(Res.string.social_rich_wants_friends, request.sender.displayName),
                            time = relativeTimeLabel(parseSocialTimestampMs(request.createdAt), nowMs),
                            unread = true,
                            actions = listOf(
                                Triple(stringResource(Res.string.social_accept), true) { onRespondLegacy(request.id, true) },
                                Triple(stringResource(Res.string.social_decline), false) { onRespondLegacy(request.id, false) },
                            ),
                        )
                    }
                }
                state.partyInvites.forEach { invite ->
                    item(key = "legacy-invite:${invite.id}") {
                        SocialInboxCard(
                            person = invite.sender,
                            text = socialRich(Res.string.social_rich_invited_title, invite.sender.displayName, invite.content.title),
                            time = relativeTimeLabel(parseSocialTimestampMs(invite.createdAt), nowMs),
                            unread = true,
                            art = invite.content.artRef(),
                            actions = listOf(Triple(stringResource(Res.string.social_join), true) { onJoinLegacyInvite(invite.partyId) }),
                        )
                    }
                }
            }
            if (!legacy && timeline.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(Res.string.social_inbox_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
            if (fresh.isNotEmpty()) {
                item(key = "new-label") { SocialSectionLabel(stringResource(Res.string.social_unread), fresh.size) }
                fresh.forEach { entry ->
                    item(key = entry.key) { SocialInboxEntryCard(entry, nowMs, unread = true, onAction, onOpenContent) }
                }
            }
            if (earlier.isNotEmpty()) {
                item(key = "earlier-label") { SocialSectionLabel(stringResource(Res.string.social_read), modifier = Modifier.padding(top = 8.dp)) }
                earlier.forEach { entry ->
                    item(key = entry.key) { SocialInboxEntryCard(entry, nowMs, unread = false, onAction, onOpenContent) }
                }
            }
        }
    }
}

/**
 * The Inbox's two groups, newest first in each: Unread is everything whose key was not in [readAtOpen] (the
 * entries already read when the page opened), so a message arriving while the page is open is Unread.
 */
internal fun <T> splitInboxUnreadRead(timeline: List<T>, readAtOpen: Set<String>, key: (T) -> String): Pair<List<T>, List<T>> =
    timeline.partition { key(it) !in readAtOpen }

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
        SocialInboxEvent.FriendAccepted -> socialRich(Res.string.social_rich_friend_accepted, name)
        SocialInboxEvent.FriendWatching ->
            if (title != null) socialRich(Res.string.social_rich_friend_watching_title, name, title)
            else socialRich(Res.string.social_rich_friend_watching, name)
        SocialInboxEvent.RecommendationStarted ->
            if (title != null) socialRich(Res.string.social_rich_rec_started_title, name, title)
            else socialRich(Res.string.social_rich_rec_started, name)
        else ->
            if (title != null) socialRich(Res.string.social_rich_recommends, name, title)
            else socialRich(Res.string.social_rich_recommendation, name)
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
        actions = if (event.kind == SocialInboxEvent.Recommendation && open != null) listOf(Triple(stringResource(Res.string.social_open), true, open)) else emptyList(),
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
            "accepted", "consumed" -> socialRich(Res.string.social_rich_now_friends, name)
            "declined" -> socialRich(Res.string.social_rich_declined_friend, name)
            else -> socialRich(Res.string.social_rich_wants_friends, name)
        }
        SocialNotificationKind.PartyInvitation -> when (outcome) {
            SocialPartyNoticeOutcome.Accepted ->
                if (title != null) socialRich(Res.string.social_rich_watched_with, title, name)
                else socialRich(Res.string.social_rich_joined_party, name)
            SocialPartyNoticeOutcome.Declined ->
                if (title != null) socialRich(Res.string.social_rich_declined_invite_title, name, title)
                else socialRich(Res.string.social_rich_declined_invite, name)
            else ->
                if (title != null) socialRich(Res.string.social_rich_invited_title, name, title)
                else socialRich(Res.string.social_rich_invited, name)
        }
        SocialNotificationKind.WatchingNowJoinRequest -> when (outcome) {
            SocialPartyNoticeOutcome.Accepted ->
                if (title != null) socialRich(Res.string.social_rich_joined_playback_title, name, title)
                else socialRich(Res.string.social_rich_joined_playback, name)
            SocialPartyNoticeOutcome.Declined ->
                if (title != null) socialRich(Res.string.social_rich_declined_join_title, name, title)
                else socialRich(Res.string.social_rich_declined_join, name)
            else ->
                if (title != null) socialRich(Res.string.social_rich_asked_join_title, name, title)
                else socialRich(Res.string.social_rich_asked_join, name)
        }
    }
    val time = relativeTimeLabel(parseSocialTimestampMs(notification.createdAt), nowMs)
    val actions = notification.availableActions
        .sortedBy { it.ordinal }
        .map { action ->
            Triple(
                when (action) {
                    SocialNotificationAction.Accept -> stringResource(Res.string.social_accept)
                    SocialNotificationAction.Decline -> stringResource(Res.string.social_decline)
                    SocialNotificationAction.Join -> stringResource(Res.string.social_join)
                },
                action != SocialNotificationAction.Decline,
            ) { onAction(notification, action) }
        }
    SocialInboxCard(
        person = notification.actor,
        text = text,
        time = if (lapsed) listOf(time, stringResource(Res.string.social_expired)).filter(String::isNotBlank).joinToString(" · ") else time,
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

/**
 * "**Seraph** recommends **Andor**": [line]'s arguments (`%1$s`, `%2$s`) are drawn emphasised, the words
 * around them soft. The template comes from the strings file, so a translation is free to move them.
 */
@Composable
private fun socialRich(line: StringResource, vararg emphasised: String): AnnotatedString {
    val template = stringResource(line)
    val strong = MaterialTheme.colorScheme.onBackground
    val soft = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.78f)
    return buildAnnotatedString {
        var at = 0
        SocialRichArgument.findAll(template).forEach { match ->
            withStyle(SpanStyle(color = soft)) { append(template.substring(at, match.range.first)) }
            withStyle(SpanStyle(color = strong, fontWeight = FontWeight.SemiBold)) {
                append(emphasised.getOrNull(match.groupValues[1].toInt() - 1).orEmpty())
            }
            at = match.range.last + 1
        }
        withStyle(SpanStyle(color = soft)) { append(template.substring(at)) }
    }
}

private val SocialRichArgument = Regex("%(\\d+)\\\$s")

// --- sheets ----------------------------------------------------------------------------------

/**
 * A bottom sheet on phones and tablets, a centred dialog on a desktop window. [edgeToEdge] drops the
 * content padding, for content that opens with full-bleed art and pads its own text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SocialSheet(wide: Boolean, onDismiss: () -> Unit, edgeToEdge: Boolean = false, content: @Composable () -> Unit) {
    if (wide) {
        Dialog(onDismissRequest = onDismiss) {
            Surface(
                modifier = Modifier.widthIn(max = 560.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(if (edgeToEdge) 0.dp else 24.dp)) { content() }
                }
            }
        }
    } else {
        NuvioModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).then(
                    if (edgeToEdge) Modifier else Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                ),
            ) { content() }
        }
    }
}

/**
 * A friend picker's frame (Recommend, Watch Together invites; 2026-10-22). [header] and [footer] stay
 * put and only [list] scrolls, capped at [SocialPickerListMax] - about seven friends - so a long list
 * never pushes the action button away. The dialog also keeps [SocialPickerWindowMargin] clear of the
 * window's edges, and the list gives up its height first when the window is short. Not [SocialSheet]
 * on a desktop window: that dialog scrolls as a whole, which is what put Send below the window.
 */
@Composable
internal fun SocialPickerFrame(
    wide: Boolean,
    onDismiss: () -> Unit,
    header: @Composable ColumnScope.() -> Unit,
    list: @Composable ColumnScope.() -> Unit,
    footer: @Composable ColumnScope.() -> Unit,
) {
    val body: @Composable ColumnScope.() -> Unit = {
        header()
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = SocialPickerListMax)
                .verticalScroll(rememberScrollState()),
        ) { list() }
        footer()
    }
    if (wide) {
        Dialog(onDismissRequest = onDismiss) {
            BoxWithConstraints(contentAlignment = Alignment.Center) {
                Surface(
                    modifier = Modifier.widthIn(max = 520.dp)
                        .heightIn(max = (maxHeight - SocialPickerWindowMargin * 2).coerceAtLeast(240.dp)),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = body)
                    }
                }
            }
        }
    } else {
        // The sheet scrolls as a whole on a short phone; the list still stops at the same cap inside it.
        SocialSheet(wide = false, onDismiss = onDismiss) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                header()
                Column(Modifier.fillMaxWidth().heightIn(max = SocialPickerListMax).verticalScroll(rememberScrollState())) { list() }
                footer()
            }
        }
    }
}

internal val SocialPickerListMax = 392.dp
internal val SocialPickerWindowMargin = 48.dp

/**
 * What a tap on a Watching Now card opens: the session, what joining will do, and the one action -
 * so a stray tap never starts playback.
 *
 * The art leads (2026-10-18, design B of `SocialJoinSheetHarness`): the backdrop full-bleed with who,
 * title and episode on it, the state chip and progress on its edges, the buttons side by side under
 * it. Open it with `SocialSheet(edgeToEdge = true)`; [wide] is the sheet's own flag.
 */
@Composable
internal fun SocialJoinSheetContent(
    item: WatchingNowItem,
    affordance: WatchingNowJoinAffordance,
    wide: Boolean,
    onJoin: () -> Unit,
    onCancelRequest: () -> Unit,
    onDetails: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val who = socialPeopleLabel(item.socialPeople())
    val pad = if (wide) 24.dp else 20.dp
    val playing = item.state == SocialPlaybackState.playing
    Box(Modifier.fillMaxWidth().aspectRatio(if (wide) 16f / 9f else 4f / 3f)) {
        SocialArtwork(listOf(item.background, item.episodeThumbnail, item.poster), item.title, Modifier.matchParentSize())
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.25f),
                    0.35f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.92f),
                ),
            ),
        )
        SocialGlassChip(Modifier.align(Alignment.TopStart).padding(start = pad, top = if (wide) 20.dp else 16.dp)) {
            SocialStateDot(playing)
            Text(
                if (playing) "PLAYING" else "PAUSED",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
            )
        }
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = pad, end = pad, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SocialFaces(item.socialPeople(), 22.dp)
                Text(
                    item.socialSentence(),
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                item.title,
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            item.socialMeta().takeIf(String::isNotBlank)?.let { meta ->
                Text(meta, color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        SocialProgressEdge(item.progressFraction, Modifier.align(Alignment.BottomStart))
    }
    Column(Modifier.padding(start = pad, end = pad, top = 16.dp, bottom = pad), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            when (affordance) {
                WatchingNowJoinAffordance.Join -> stringResource(Res.string.social_join_direct, who)
                WatchingNowJoinAffordance.AskToJoin -> stringResource(Res.string.social_join_ask, who)
                is WatchingNowJoinAffordance.Requested -> stringResource(Res.string.social_join_waiting, who)
                WatchingNowJoinAffordance.Joining -> stringResource(Res.string.social_joining)
                WatchingNowJoinAffordance.InYourParty -> stringResource(Res.string.social_join_together)
                WatchingNowJoinAffordance.None -> stringResource(Res.string.social_join_closed, who)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when (affordance) {
                WatchingNowJoinAffordance.Join ->
                    SocialPillButton(stringResource(Res.string.social_join_now), onJoin, Modifier.weight(1f), primary = true, compact = false)
                WatchingNowJoinAffordance.AskToJoin ->
                    SocialPillButton(stringResource(Res.string.social_ask_to_join), onJoin, Modifier.weight(1f), primary = true, compact = false)
                is WatchingNowJoinAffordance.Requested ->
                    SocialPillButton(
                        if (affordance.cancelling) stringResource(Res.string.social_cancelling) else stringResource(Res.string.social_cancel_request),
                        onCancelRequest,
                        Modifier.weight(1f),
                        compact = false,
                        enabled = !affordance.cancelling,
                    )
                else -> Unit
            }
            SocialPillButton(stringResource(Res.string.social_details), onDetails, Modifier.weight(1f), compact = false)
        }
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
        title = { Text(stringResource(Res.string.social_invite_join_title)) },
        text = {
            OutlinedTextField(
                value = code,
                onValueChange = onCodeChange,
                singleLine = true,
                label = { Text(stringResource(Res.string.social_invite_code)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (code.isNotBlank()) onJoin() }),
            )
        },
        confirmButton = { TextButton(onClick = onJoin, enabled = code.isNotBlank()) { Text(stringResource(Res.string.social_join)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.social_cancel)) } },
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
internal fun SocialNotice(message: String, accent: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
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
            SocialCircleGlyph(
                // A blank name would `take(1)` to nothing and leave the same empty circle.
                name.trim().take(1).uppercase().ifBlank { "?" },
                fontSize = (size.value * 0.42f).sp,
                color = Color.White,
            )
        }
    }
}
