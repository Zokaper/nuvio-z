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

/**
 * How long a finished party invite or join request stays in the inbox.
 *
 * ⚠ These rows are *derived* from `watch_party_invites` / `watch_join_requests`, which nothing
 * prunes, so an invite from a party that ended last week stayed in the inbox for good. The row
 * carries no party end time, only `available_actions`, which the server empties the moment the
 * party ends or the item is answered or lapses. So "nothing can be done with it and it is over a day
 * old" is the retirement rule; a day keeps last night's party visible as history the next morning.
 */
const val SocialFinishedNoticeRetentionMs = 24L * 60L * 60L * 1000L

/** A party invite or join request with nothing left to do: answered, lapsed, or its party is over. */
val SocialNotification.isFinishedPartyNotice: Boolean
    get() = kind != SocialNotificationKind.FriendRequest && availableActions.isEmpty()

/** True when [isFinishedPartyNotice] and older than [SocialFinishedNoticeRetentionMs]; the inbox drops it. */
fun SocialNotification.isRetired(nowMs: Long): Boolean {
    if (!isFinishedPartyNotice) return false
    val created = parseSocialTimestampMs(createdAt) ?: return false
    return nowMs - created > SocialFinishedNoticeRetentionMs
}

/** What a party notice reads as once it is over. */
enum class SocialPartyNoticeOutcome { Open, Accepted, Declined, Lapsed }

fun SocialNotification.partyNoticeOutcome(): SocialPartyNoticeOutcome = when {
    !isFinishedPartyNotice -> SocialPartyNoticeOutcome.Open
    state.lowercase() in setOf("accepted", "consumed", "approved") -> SocialPartyNoticeOutcome.Accepted
    state.lowercase() in setOf("declined", "rejected") -> SocialPartyNoticeOutcome.Declined
    // Expired, cancelled, stale - and "pending" on a party that has since ended.
    else -> SocialPartyNoticeOutcome.Lapsed
}
