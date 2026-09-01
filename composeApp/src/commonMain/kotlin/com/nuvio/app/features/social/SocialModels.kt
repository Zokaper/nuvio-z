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
)

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
    val season: Int? = null,
    val episode: Int? = null,
    @SerialName("episode_title") val episodeTitle: String? = null,
    @SerialName("position_ms") val positionMs: Long,
    @SerialName("duration_ms") val durationMs: Long,
    @SerialName("playback_speed") val playbackSpeed: Float = 1f,
    val state: SocialPlaybackState,
    @SerialName("heartbeat_at") val heartbeatAt: String,
) {
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
    val season: Int? = null,
    val episode: Int? = null,
    @SerialName("episode_title") val episodeTitle: String? = null,
    @SerialName("event_count") val eventCount: Int = 1,
    @SerialName("first_event_time") val firstEventTime: String,
    @SerialName("last_event_time") val lastEventTime: String,
)

@Serializable
data class SocialStatePayload(
    val me: SocialProfileSummary? = null,
    val friends: List<SocialProfileSummary> = emptyList(),
    val requests: List<FriendRequest> = emptyList(),
    @SerialName("party_invites") val partyInvites: List<SocialInboxItem> = emptyList(),
    @SerialName("watching_now") val watchingNow: List<WatchingNowItem> = emptyList(),
    val activity: List<RecentActivityRun> = emptyList(),
)

data class SocialUiState(
    val capabilities: SocialCapabilities = SocialCapabilities(),
    val activeProfileId: String? = null,
    val me: SocialProfileSummary? = null,
    val friends: List<SocialProfileSummary> = emptyList(),
    val requests: List<FriendRequest> = emptyList(),
    val partyInvites: List<SocialInboxItem> = emptyList(),
    val watchingNow: List<WatchingNowItem> = emptyList(),
    val activity: List<RecentActivityRun> = emptyList(),
    val selectedFriendId: String? = null,
    val nextCursor: SocialActivityCursor? = null,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isOfflineCache: Boolean = false,
    val errorMessage: String? = null,
) {
    val unreadCount: Int get() = requests.size + partyInvites.size
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
    val season: Int? = null,
    val episode: Int? = null,
    @SerialName("episode_title") val episodeTitle: String? = null,
    @SerialName("position_ms") val positionMs: Long,
    @SerialName("duration_ms") val durationMs: Long,
    @SerialName("playback_speed") val playbackSpeed: Float,
    val state: SocialPlaybackState,
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
