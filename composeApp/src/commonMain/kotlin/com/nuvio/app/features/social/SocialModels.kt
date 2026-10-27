package com.nuvio.app.features.social

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val SocialHomeItemLimit = 18
const val SocialPageSize = 30
const val SocialPresenceHeartbeatMs = 20_000L
const val SocialPresenceStaleMs = 90_000L

@Serializable
data class SocialCapabilities(
    @SerialName("social_enabled") val socialEnabled: Boolean = false,
    @SerialName("watch_party_enabled") val watchPartyEnabled: Boolean = false,
    @SerialName("party_contract_version") val partyContractVersion: Int = 1,
)

@Serializable
enum class WatchJoinPolicy { direct, approval, disabled }

@Serializable
data class SocialProfileSummary(
    @SerialName("profile_id") val profileId: String,
    val handle: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("avatar_color_hex") val avatarColorHex: String = "#1E88E5",
    @SerialName("is_friend") val isFriend: Boolean = false,
    @SerialName("share_watching_now") val shareWatchingNow: Boolean = true,
    @SerialName("share_recently_watched") val shareRecentlyWatched: Boolean = true,
    @SerialName("default_join_policy") val defaultJoinPolicy: WatchJoinPolicy = WatchJoinPolicy.approval,
    /** An https picture the profile page shows instead of the strip of recent titles (2026-10-24). */
    @SerialName("banner_url") val bannerUrl: String? = null,
)

/**
 * Who a setting reaches (2026-10-24), Snapchat-style: all my friends, all my friends except a list,
 * only a list, or no one. The server applies the same rule (`social_audience_allows`).
 */
@Serializable
enum class SocialAudienceMode {
    @SerialName("everyone") Everyone,
    @SerialName("except") Except,
    @SerialName("only") Only,
    @SerialName("nobody") Nobody,
}

/** The three audience settings and their wire names. */
enum class SocialAudienceSetting(val wire: String) {
    /** Who sees my Watching Now. */
    WatchingNow("watching_now"),
    /** Who sees my Recently watched. */
    Recent("recent"),
    /** Whose starting-to-watch I am told about. */
    Notify("notify"),
}

/**
 * One setting's audience. Both lists are kept whichever mode is on, so moving between "except" and
 * "only" never turns one list into the other.
 */
@Serializable
data class SocialAudience(
    val mode: SocialAudienceMode = SocialAudienceMode.Everyone,
    val except: List<String> = emptyList(),
    val only: List<String> = emptyList(),
) {
    fun allows(friendProfileId: String): Boolean = when (mode) {
        SocialAudienceMode.Everyone -> true
        SocialAudienceMode.Nobody -> false
        SocialAudienceMode.Except -> friendProfileId !in except
        SocialAudienceMode.Only -> friendProfileId in only
    }

    /** The list [mode] reads; empty for everyone and nobody. */
    val members: List<String>
        get() = when (mode) {
            SocialAudienceMode.Except -> except
            SocialAudienceMode.Only -> only
            else -> emptyList()
        }

    /**
     * [mode], with [members] replacing that mode's list when given. Mirrors `social_set_audience`: an
     * emptied except list is everyone, an emptied only list is nobody.
     */
    fun withMode(mode: SocialAudienceMode, members: List<String>? = null): SocialAudience {
        val next = when {
            members == null -> copy(mode = mode)
            mode == SocialAudienceMode.Except -> copy(mode = mode, except = members.distinct())
            mode == SocialAudienceMode.Only -> copy(mode = mode, only = members.distinct())
            else -> copy(mode = mode)
        }
        return next.normalized()
    }

    /** One friend let in or kept out under the current mode. Mirrors `social_set_audience_member`. */
    fun withMember(friendProfileId: String, allowed: Boolean): SocialAudience = when (mode) {
        SocialAudienceMode.Everyone ->
            if (allowed) this else copy(mode = SocialAudienceMode.Except, except = listOf(friendProfileId))
        SocialAudienceMode.Nobody ->
            if (allowed) copy(mode = SocialAudienceMode.Only, only = listOf(friendProfileId)) else this
        SocialAudienceMode.Except ->
            copy(except = if (allowed) except - friendProfileId else (except + friendProfileId).distinct()).normalized()
        SocialAudienceMode.Only ->
            copy(only = if (allowed) (only + friendProfileId).distinct() else only - friendProfileId).normalized()
    }

    private fun normalized(): SocialAudience = when {
        mode == SocialAudienceMode.Except && except.isEmpty() -> copy(mode = SocialAudienceMode.Everyone)
        mode == SocialAudienceMode.Only && only.isEmpty() -> copy(mode = SocialAudienceMode.Nobody)
        else -> this
    }
}

@Serializable
data class SocialAudiences(
    @SerialName("watching_now") val watchingNow: SocialAudience = SocialAudience(),
    val recent: SocialAudience = SocialAudience(),
    val notify: SocialAudience = SocialAudience(),
) {
    operator fun get(setting: SocialAudienceSetting): SocialAudience = when (setting) {
        SocialAudienceSetting.WatchingNow -> watchingNow
        SocialAudienceSetting.Recent -> recent
        SocialAudienceSetting.Notify -> notify
    }

    fun with(setting: SocialAudienceSetting, audience: SocialAudience): SocialAudiences = when (setting) {
        SocialAudienceSetting.WatchingNow -> copy(watchingNow = audience)
        SocialAudienceSetting.Recent -> copy(recent = audience)
        SocialAudienceSetting.Notify -> copy(notify = audience)
    }
}

@Serializable
data class FriendRequest(
    val id: String,
    val sender: SocialProfileSummary,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class SocialInboxItem(
    val id: String,
    @SerialName("party_id") val partyId: String,
    val sender: SocialProfileSummary,
    val content: com.nuvio.app.features.watchparty.PartyContent,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
enum class SocialPlaybackState { playing, paused }

@Serializable
data class WatchingNowItem(
    val profile: SocialProfileSummary,
    @SerialName("content_id") val contentId: String,
    @SerialName("content_type") val contentType: String,
    @SerialName("video_id") val videoId: String,
    val title: String,
    val poster: String? = null,
    val background: String? = null,
    @SerialName("episode_thumbnail") val episodeThumbnail: String? = null,
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("effective_join_policy") val effectiveJoinPolicy: WatchJoinPolicy = WatchJoinPolicy.approval,
    val season: Int? = null,
    val episode: Int? = null,
    @SerialName("episode_title") val episodeTitle: String? = null,
    @SerialName("position_ms") val positionMs: Long,
    @SerialName("duration_ms") val durationMs: Long,
    @SerialName("playback_speed") val playbackSpeed: Float = 1f,
    val state: SocialPlaybackState,
    @SerialName("heartbeat_at") val heartbeatAt: String,
    /** The live Watch Together party this friend is in, if any. Null from a backend before 2026-09-17. */
    @SerialName("party_id") val partyId: String? = null,
    @SerialName("party_host_profile_id") val partyHostProfileId: String? = null,
    @SerialName("party_member_count") val partyMemberCount: Int? = null,
    /**
     * Other friends shown in this entry because they are in the same party. Filled only by
     * [groupWatchingNowByParty]; never from the wire.
     */
    @kotlinx.serialization.Transient val partyCompanions: List<SocialProfileSummary> = emptyList(),
) {
    /** A guest in someone else's party: joining must go through the host, never through this session. */
    val isPartyGuest: Boolean
        get() = partyId != null && partyHostProfileId != null && partyHostProfileId != profile.profileId

    val progressFraction: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    val roundedProgressPercent: Int
        get() = (progressFraction * 100f).toInt().coerceIn(0, 100)
}

@Serializable
data class RecentActivityRun(
    @SerialName("run_id") val runId: String,
    val profile: SocialProfileSummary,
    @SerialName("content_id") val contentId: String,
    @SerialName("content_type") val contentType: String,
    @SerialName("video_id") val videoId: String? = null,
    val title: String,
    val poster: String? = null,
    val background: String? = null,
    @SerialName("episode_thumbnail") val episodeThumbnail: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    @SerialName("episode_title") val episodeTitle: String? = null,
    @SerialName("event_count") val eventCount: Int = 1,
    @SerialName("first_event_time") val firstEventTime: String,
    @SerialName("last_event_time") val lastEventTime: String,
)

/**
 * A party invite this profile sent (2026-10-16, `sent_party_invites`): pending while its party is live,
 * or declined while the re-invite cooldown runs. [cooldownSeconds] is what remained when the server
 * answered, so client clocks cannot skew it.
 */
@Serializable
data class SentPartyInvite(
    val id: String,
    @SerialName("party_id") val partyId: String,
    @SerialName("receiver_profile_id") val receiverProfileId: String,
    val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("cooldown_seconds") val cooldownSeconds: Int? = null,
)

/** A friend request this profile sent that is still waiting (Social V2, `sent_requests`). */
@Serializable
data class SentFriendRequest(
    val id: String,
    val receiver: SocialProfileSummary,
    @SerialName("created_at") val createdAt: String,
)

/** This profile's own per-friend switches (Social V2, `friend_prefs`). Absent means both off. */
@Serializable
data class SocialFriendPrefs(
    @SerialName("friend_profile_id") val friendProfileId: String,
    @SerialName("hide_activity") val hideActivity: Boolean = false,
    // On unless turned off (maintainer, 2026-10-23); the server sends only the rows that differ.
    @SerialName("notify_when_watching") val notifyWhenWatching: Boolean = true,
)

/**
 * A stored inbox event (Social V2, `inbox`): something the derived notifications cannot express.
 *
 * ⚠ [kind] is a plain string, never an enum. `SocialNotificationKind` is a closed enum, which is why a
 * new notification kind would fail the whole payload for an older client; a kind this build does not
 * know is simply not drawn.
 */
@Serializable
data class SocialInboxEvent(
    val id: String,
    val kind: String,
    val actor: SocialProfileSummary,
    @SerialName("created_at") val createdAt: String,
    @SerialName("read_at") val readAt: String? = null,
    val payload: SocialInboxPayload = SocialInboxPayload(),
) {
    companion object {
        const val FriendAccepted = "friend_accepted"
        const val FriendWatching = "friend_watching"
        const val Recommendation = "recommendation"
        /** Someone you recommended a title to started it (2026-10-18). In-app only, never a system push. */
        const val RecommendationStarted = "recommendation_started"
        val Known = setOf(FriendAccepted, FriendWatching, Recommendation, RecommendationStarted)
    }
}

@Serializable
data class SocialInboxPayload(
    @SerialName("content_id") val contentId: String? = null,
    @SerialName("content_type") val contentType: String? = null,
    @SerialName("video_id") val videoId: String? = null,
    val title: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val note: String? = null,
)

/** Time and parties shared with one friend, from `social_get_together_stats`. Approximate. */
@Serializable
data class SocialTogetherStats(
    val seconds: Long = 0,
    val parties: Int = 0,
    val titles: List<SocialTogetherTitle> = emptyList(),
)

@Serializable
data class SocialTogetherTitle(
    val title: String,
    @SerialName("content_id") val contentId: String? = null,
    @SerialName("content_type") val contentType: String? = null,
    val poster: String? = null,
    val parties: Int = 1,
)

@Serializable
data class SocialStatePayload(
    val me: SocialProfileSummary? = null,
    val friends: List<SocialProfileSummary> = emptyList(),
    val requests: List<FriendRequest> = emptyList(),
    @SerialName("party_invites") val partyInvites: List<SocialInboxItem> = emptyList(),
    val notifications: List<SocialNotification> = emptyList(),
    @SerialName("watching_now") val watchingNow: List<WatchingNowItem> = emptyList(),
    val activity: List<RecentActivityRun> = emptyList(),
    // Social V2. Null means the backend predates them, which is how the client knows to hide the
    // controls that need them rather than draw switches that cannot save.
    @SerialName("sent_requests") val sentRequests: List<SentFriendRequest>? = null,
    @SerialName("friend_prefs") val friendPrefs: List<SocialFriendPrefs>? = null,
    val inbox: List<SocialInboxEvent>? = null,
    @SerialName("sent_party_invites") val sentPartyInvites: List<SentPartyInvite>? = null,
    /** This profile's own audiences (2026-10-24); null on a backend that predates them. */
    val audiences: SocialAudiences? = null,
)

data class SocialUiState(
    val capabilities: SocialCapabilities = SocialCapabilities(),
    val activeProfileId: String? = null,
    val me: SocialProfileSummary? = null,
    val friends: List<SocialProfileSummary> = emptyList(),
    val requests: List<FriendRequest> = emptyList(),
    val partyInvites: List<SocialInboxItem> = emptyList(),
    val notifications: List<SocialNotification> = emptyList(),
    val watchingNow: List<WatchingNowItem> = emptyList(),
    val activity: List<RecentActivityRun> = emptyList(),
    val selectedFriendId: String? = null,
    val nextCursor: SocialActivityCursor? = null,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isOfflineCache: Boolean = false,
    val errorMessage: String? = null,
    val sentRequests: List<SentFriendRequest> = emptyList(),
    val friendPrefs: List<SocialFriendPrefs> = emptyList(),
    val inbox: List<SocialInboxEvent> = emptyList(),
    /** The backend serves the Social V2 keys (per-friend switches, sent requests, the stored inbox). */
    val socialV2Backend: Boolean = false,
    /** Null when the backend predates `sent_party_invites`: the player then keeps a local "Sent". */
    val sentPartyInvites: List<SentPartyInvite>? = null,
    /** When [sentPartyInvites] arrived, the base for their `cooldownSeconds`. */
    val sentPartyInvitesAtMs: Long = 0L,
    /** This profile's own audiences; null on a backend that predates them. */
    val audiences: SocialAudiences? = null,
) {
    val unreadCount: Int get() = (if (capabilities.partyContractVersion>=2) {
        notifications.count { it.readAt==null && it.availableActions.isNotEmpty() }
    } else requests.size + partyInvites.size) +
        inbox.count { it.readAt == null && it.kind in SocialInboxEvent.Known }

    /** Recommendations nobody has looked at yet: the dot on the header's Recommended button. */
    val unreadRecommendations: Int get() = inbox.count { it.readAt == null && it.kind == SocialInboxEvent.Recommendation }

    /** The Inbox button's count: everything unread except recommendations, which have their own page. */
    val inboxUnreadCount: Int get() = unreadCount - unreadRecommendations

    fun prefsFor(friendProfileId: String): SocialFriendPrefs =
        friendPrefs.firstOrNull { it.friendProfileId == friendProfileId } ?: SocialFriendPrefs(friendProfileId)
    val needsHandleSetup: Boolean get() = capabilities.socialEnabled && activeProfileId != null && me == null
}

@Serializable
data class SocialActivityCursor(val lastEventTime: String, val runId: String)

/** One page of a friend's Recently watched for their profile; [next] is null on the last page. */
data class FriendHistoryPage(val runs: List<RecentActivityRun>, val next: SocialActivityCursor?)

@Serializable
data class SocialPresencePublish(
    @SerialName("session_id") val sessionId: String,
    @SerialName("content_id") val contentId: String,
    @SerialName("content_type") val contentType: String,
    @SerialName("video_id") val videoId: String,
    val title: String,
    val poster: String? = null,
    val background: String? = null,
    @SerialName("episode_thumbnail") val episodeThumbnail: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    @SerialName("episode_title") val episodeTitle: String? = null,
    @SerialName("position_ms") val positionMs: Long,
    @SerialName("duration_ms") val durationMs: Long,
    @SerialName("playback_speed") val playbackSpeed: Float,
    val state: SocialPlaybackState,
    @SerialName("effective_join_policy") val effectiveJoinPolicy: WatchJoinPolicy = WatchJoinPolicy.approval,
    @SerialName("source_fingerprint") val sourceFingerprint: com.nuvio.app.features.watchparty.PartySourceDescriptorV2? = null,
    @SerialName("track_intent") val trackIntent: com.nuvio.app.features.watchparty.PartyTrackIntent? = null,
)

@Serializable
data class SocialWatchedPublish(
    @SerialName("origin_key") val originKey: String,
    @SerialName("content_id") val contentId: String,
    @SerialName("content_type") val contentType: String,
    @SerialName("video_id") val videoId: String? = null,
    val title: String,
    val poster: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    @SerialName("episode_title") val episodeTitle: String? = null,
    @SerialName("watched_at_epoch_ms") val watchedAtEpochMs: Long,
)

fun normalizeSocialHandle(value: String): String = value.trim().lowercase()

fun isValidSocialHandle(value: String): Boolean {
    val normalized = normalizeSocialHandle(value)
    return normalized.length in 3..24 && normalized.all { it == '_' || it in 'a'..'z' || it in '0'..'9' }
}
