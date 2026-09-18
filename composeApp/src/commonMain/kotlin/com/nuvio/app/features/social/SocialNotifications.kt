package com.nuvio.app.features.social

import com.nuvio.app.features.watchparty.PartyContent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SocialNotificationKind {
    @SerialName("friend_request") FriendRequest,
    @SerialName("party_invite") PartyInvitation,
    @SerialName("join_request") WatchingNowJoinRequest,
}
@Serializable
enum class SocialNotificationAction {
    @SerialName("accept") Accept,
    @SerialName("decline") Decline,
    @SerialName("join") Join,
}

@Serializable
data class SocialNotification(
    val id: String,
    val kind: SocialNotificationKind,
    val actor: SocialProfileSummary,
    @SerialName("created_at") val createdAt: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("read_at") val readAt: String? = null,
    val state: String,
    @SerialName("party_id") val partyId: String? = null,
    @SerialName("presence_session_id") val presenceSessionId: String? = null,
    @SerialName("available_actions") val availableActions: Set<SocialNotificationAction> = emptySet(),
    @SerialName("content_summary") val contentSummary: PartyContent? = null,
)

data class SocialNotificationState(
    val items: List<SocialNotification> = emptyList(),
    val staleMessage: String? = null,
) {
    val unreadCount: Int get() = items.count { it.readAt == null && it.availableActions.isNotEmpty() }
}

sealed interface SocialNotificationEvent {
    data class Refreshed(val items: List<SocialNotification>) : SocialNotificationEvent
    data class MarkedRead(val ids: Set<String>, val readAt: String) : SocialNotificationEvent
    data class Consumed(val id: String) : SocialNotificationEvent
    data class ActionStale(val id: String) : SocialNotificationEvent
}

fun reduceSocialNotifications(
    state: SocialNotificationState,
    event: SocialNotificationEvent,
): SocialNotificationState = when(event) {
    is SocialNotificationEvent.Refreshed -> state.copy(
        items=event.items.associateBy(SocialNotification::id).values.sortedByDescending(SocialNotification::createdAt),
        staleMessage=null,
    )
    is SocialNotificationEvent.MarkedRead -> state.copy(items=state.items.map {
        if (it.id in event.ids && it.readAt==null) it.copy(readAt=event.readAt) else it
    })
    is SocialNotificationEvent.Consumed -> state.copy(items=state.items.map {
        if (it.id==event.id) it.copy(state="consumed",availableActions=emptySet()) else it
    })
    is SocialNotificationEvent.ActionStale -> state.copy(
        items=state.items.map { if (it.id==event.id) it.copy(state="stale",availableActions=emptySet()) else it },
        staleMessage="This request is no longer available.",
    )
}
