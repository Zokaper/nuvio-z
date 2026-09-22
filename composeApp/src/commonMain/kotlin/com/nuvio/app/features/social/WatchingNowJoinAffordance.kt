package com.nuvio.app.features.social

import com.nuvio.app.features.watchparty.WatchPartyState
import com.nuvio.app.features.watchparty.WatchPartyStatus

/**
 * What a Watching Now card's action says, from one place.
 *
 * The card, the Home shelf and the dock mirror all read this, and none of them keeps its own copy of
 * the request, so a boundary that clears the store clears every button in the same frame.
 */
sealed interface WatchingNowJoinAffordance {
    /** Join policy Off: no button at all. */
    data object None : WatchingNowJoinAffordance
    data object Join : WatchingNowJoinAffordance
    data object AskToJoin : WatchingNowJoinAffordance

    /** A request for this session is waiting. Shows a ring to [expiresAtMs]; Cancel on hover. */
    data class Requested(val expiresAtMs: Long, val cancelling: Boolean) : WatchingNowJoinAffordance
    data object Joining : WatchingNowJoinAffordance

    /** The friend is already in the party this viewer holds. Not interactive. */
    data object InYourParty : WatchingNowJoinAffordance
}

val WatchingNowJoinAffordance.label: String?
    get() = when (this) {
        WatchingNowJoinAffordance.None -> null
        WatchingNowJoinAffordance.Join -> "Join"
        WatchingNowJoinAffordance.AskToJoin -> "Ask to join"
        is WatchingNowJoinAffordance.Requested -> "Requested"
        WatchingNowJoinAffordance.Joining -> "Joining…"
        WatchingNowJoinAffordance.InYourParty -> "In your party"
    }

fun watchingNowJoinAffordance(
    item: WatchingNowItem,
    outgoingRequest: OutgoingJoinRequestState,
    heldParty: WatchPartyState?,
): WatchingNowJoinAffordance {
    val live = heldParty?.takeIf { it.status != WatchPartyStatus.ended }
    val friend = item.profile.profileId
    if (live != null && (live.hostProfileId == friend || live.members.any { it.profileId == friend } ||
            (item.partyId != null && live.id == item.partyId))
    ) {
        return WatchingNowJoinAffordance.InYourParty
    }
    // A party whose host is not on this list: the server refuses a request aimed at a guest, so offer none.
    if (item.isPartyGuest) return WatchingNowJoinAffordance.None
    val request = outgoingRequest as? OutgoingJoinRequestState.Bound
    // Keyed by the session, not the person: a friend who restarted playback is a new thing to join.
    if (request != null && request.target.sessionId == item.sessionId && request.target.profileId == friend) {
        when (request) {
            is OutgoingJoinRequestState.Sending,
            is OutgoingJoinRequestState.Accepted,
            is OutgoingJoinRequestState.Joining,
            -> return WatchingNowJoinAffordance.Joining
            is OutgoingJoinRequestState.Pending ->
                return WatchingNowJoinAffordance.Requested(request.expiresAtMs, cancelling = false)
            is OutgoingJoinRequestState.Cancelling ->
                return WatchingNowJoinAffordance.Requested(request.expiresAtMs, cancelling = true)
            // An outcome is the dock's to say; the card offers the action again ("Ask again").
            is OutgoingJoinRequestState.Outcome -> Unit
        }
    }
    return when (item.effectiveJoinPolicy) {
        WatchJoinPolicy.direct -> WatchingNowJoinAffordance.Join
        WatchJoinPolicy.approval -> WatchingNowJoinAffordance.AskToJoin
        WatchJoinPolicy.disabled -> WatchingNowJoinAffordance.None
    }
}

/**
 * Watching Now's display order: the server's order, except that cards on the same title and episode
 * sit next to each other, so two friends on one episode are easy to spot.
 *
 * ⚠ Adjacent, never merged. Each card is one person's session and carries its own action; sharing
 * `contentId` + `videoId` says nothing about whether those people are in the same party.
 */
fun orderWatchingNowForDisplay(items: List<WatchingNowItem>): List<WatchingNowItem> {
    val byTitle = LinkedHashMap<Pair<String, String>, MutableList<WatchingNowItem>>()
    groupWatchingNowByParty(items).forEach { byTitle.getOrPut(it.contentId to it.videoId) { mutableListOf() } += it }
    return byTitle.values.flatten()
}

/**
 * One entry per Watch Together party, the one grouping Watching Now allows: the server says these
 * friends share a party, so they share one card and one action.
 *
 * The entry kept is the host's when the host is on the list, since a join has to target the host's
 * session; otherwise the first guest's, which [watchingNowJoinAffordance] offers no action for. The
 * rest become [WatchingNowItem.partyCompanions]. Entries outside a party pass through untouched.
 */
fun groupWatchingNowByParty(items: List<WatchingNowItem>): List<WatchingNowItem> {
    val byParty = items.filter { it.partyId != null }.groupBy { it.partyId }
    if (byParty.values.none { it.size > 1 }) return items
    val emitted = HashSet<String>()
    return items.mapNotNull { item ->
        val partyId = item.partyId ?: return@mapNotNull item
        if (!emitted.add(partyId)) return@mapNotNull null
        val members = byParty.getValue(partyId)
        val lead = members.firstOrNull { it.profile.profileId == it.partyHostProfileId } ?: item
        lead.copy(partyCompanions = members.filter { it !== lead }.map { it.profile })
    }
}
