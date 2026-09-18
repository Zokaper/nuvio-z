package com.nuvio.app.features.watchparty

/**
 * The lobby route's exit invariant: **the lobby is on the back stack exactly as long as its party is.**
 *
 * Every way out of the lobby is one of three things, and nothing else:
 *
 *  1. **Leaving the party** - the header arrow, the Leave button, or a system back (Escape). All of
 *     them ask the same question - leave, transfer, or end - and the route only goes once the
 *     server has accepted the answer. A departure that fails keeps the lobby, so the user can retry,
 *     rather than removing the only surface that could.
 *  2. **The party ending under it** - the host ended it, or this member's membership is gone. The
 *     lobby closes itself, because there is nothing left for it to be the room of.
 *  3. **Going deeper** - the source list, the player, a title's details. These push *on top of* the
 *     lobby and never remove it, which is how the Phase 4 route-independent player keeps its home to
 *     come back to. Promotion from a running player has no lobby below it at all, and gets one on
 *     the way out of the player.
 *
 * An accidental dismissal is not on that list, and before 2026-09-15 Escape was one.
 */
enum class LobbyBackAction {
    /** A live party is held for this lobby: ask to leave or end it, exactly like the header arrow. */
    RequestDeparture,

    /** No live party is held for this lobby - it is still joining, or already over. Closing is safe. */
    Close,
}

fun decideLobbyBack(routePartyId: String?, held: WatchPartyState?): LobbyBackAction {
    if (held == null || held.status == WatchPartyStatus.ended) return LobbyBackAction.Close
    if (routePartyId != null && held.id != routePartyId) return LobbyBackAction.Close
    return LobbyBackAction.RequestDeparture
}

/**
 * The party whose lobby a player leaving must open, or null when none is owed.
 *
 * Case 3's "gets one on the way out of the player", which nothing implemented: a party started from
 * the player's own Watch Together panel has no lobby below it, so Escape went straight home and left
 * the party running with nothing on screen to leave or end it from.
 *
 * [lobbyPartyIdsOnStack] are the `partyId`s of the lobby routes already on the back stack, where an
 * invite-code lobby (no id yet) counts as any party's.
 */
fun lobbyOwedOnPlayerExit(
    held: WatchPartyState?,
    playerMatchesParty: Boolean,
    lobbyPartyIdsOnStack: List<String?>,
): String? {
    if (held == null || held.status == WatchPartyStatus.ended || !playerMatchesParty) return null
    if (lobbyPartyIdsOnStack.any { it == null || it == held.id }) return null
    return held.id
}

/** Why a lobby that was showing a live party must now close itself. */
enum class LobbyCloseReason {
    /** This member left, transferred or ended it themselves, or moved on to another party. Silent. */
    Departed,

    /** The party ended without this member leaving it - the host ended it. Worth a sentence. */
    PartyEnded,
}

/**
 * Whether the lobby bound to [boundPartyId] must close, given the party now held.
 *
 * [boundPartyId] is null until the lobby has seen its party live, and a lobby that has not is still
 * joining: an absent party then is the join in flight, never a reason to close.
 */
fun lobbyCloseReason(boundPartyId: String?, held: WatchPartyState?): LobbyCloseReason? {
    if (boundPartyId == null) return null
    if (held == null || held.id != boundPartyId) return LobbyCloseReason.Departed
    if (held.status == WatchPartyStatus.ended) return LobbyCloseReason.PartyEnded
    return null
}

/**
 * Whether a source-preparation route opened for [launchPartyId] is still working for a live party.
 *
 * A guest can be on the source route - matching, resolving, showing "resolving source" - when the
 * host ends the party. That route must stop and leave rather than finish realizing a source for a
 * party that no longer exists, and certainly never open a player for it.
 */
fun partyLaunchStillLive(launchPartyId: String, held: WatchPartyState?): Boolean =
    held != null && held.id == launchPartyId && held.status != WatchPartyStatus.ended
