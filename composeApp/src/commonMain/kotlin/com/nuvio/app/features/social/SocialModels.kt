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
)

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
    @SerialName("notify_when_watching") val notifyWhenWatching: Boolean = false,
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
        val Known = setOf(FriendAccepted, FriendWatching, Recommendation)
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
) {
    val unreadCount: Int get() = (if (capabilities.partyContractVersion>=2) {
        notifications.count { it.readAt==null && it.availableActions.isNotEmpty() }
    } else requests.size + partyInvites.size) +
        inbox.count { it.readAt == null && it.kind in SocialInboxEvent.Known }

    fun prefsFor(friendProfileId: String): SocialFriendPrefs =
        friendPrefs.firstOrNull { it.friendProfileId == friendProfileId } ?: SocialFriendPrefs(friendProfileId)
    val needsHandleSetup: Boolean get() = capabilities.socialEnabled && activeProfileId != null && me == null
}

@Serializable
data class SocialActivityCursor(val lastEventTime: String, val runId: String)

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
