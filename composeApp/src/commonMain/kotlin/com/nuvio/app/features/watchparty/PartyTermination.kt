package com.nuvio.app.features.watchparty

/**
 * How a client learns that the party it is holding is over for it.
 *
 * ⚠⚠ **A guest is never told "ended" by the server, and this is the whole of hardware Bug 5
 * (2026-09-15).** `party_close_ended` - the body of the host's End party - marks the party `ended`
 * *and* stamps every member row `left_at`. `party_is_member` requires `left_at is null`, and it is
 * the first line of `party_snapshot`, `party_heartbeat` and every other member RPC. So from the
 * instant the host ends it, every call a guest makes raises `party_membership_required` (42501), and
 * the snapshot that says `status = ended` is one the guest is no longer allowed to read. The realtime
 * payload for the close bumps `authority_epoch`, which a guest correctly treats as "refresh", and the
 * refresh is refused the same way. The poll logged the failure once and kept going.
 *
 * Every client path already knew what to do with an ended party - the player's end-of-party choice,
 * `matchesPlayback`, the content and source handoffs. None of them ever saw one. Verified against
 * the deployed functions with `pg_get_functiondef` on 2026-09-15, which match the migrations.
 *
 * The fix is client-side and needs no backend change: a membership refusal for the held party is
 * evidence the party is over for this member, confirmed once through `party_get_active` - which is
 * scoped to the caller's own profile rather than to a membership, so it still answers - and then
 * concluded locally into the same ended state the server would have sent.
 */
enum class PartyRpcFailureVerdict {
    /** An ordinary failure. Keep the party; the poll is the retry. */
    Transient,

    /** This member's membership was refused. Confirm before concluding anything. */
    ConfirmMembership,
}

/** The label `party_is_member` raises. Matched as text because it is the server's chosen word. */
const val PartyMembershipRequiredLabel = "party_membership_required"

/**
 * Classifies a failed member RPC for the party this client holds.
 *
 * Only the *held, live* party can be concluded: a refusal for a party this client has already moved
 * on from, or one it already knows has ended, says nothing new.
 */
fun classifyPartyRpcFailure(
    held: WatchPartyState?,
    failedPartyId: String?,
    errorMessage: String?,
): PartyRpcFailureVerdict {
    if (held == null || held.status == WatchPartyStatus.ended) return PartyRpcFailureVerdict.Transient
    if (failedPartyId != null && failedPartyId != held.id) return PartyRpcFailureVerdict.Transient
    if (errorMessage?.contains(PartyMembershipRequiredLabel) != true) return PartyRpcFailureVerdict.Transient
    return PartyRpcFailureVerdict.ConfirmMembership
}

/**
 * Whether the membership probe proves the held party is over for this member.
 *
 * `party_get_active` answers the caller's current live party. The same party, still live, means the
 * refusal was not what it looked like and nothing may be torn down. No party, an ended one, or a
 * different one all mean this member is no longer in [heldPartyId].
 */
fun membershipProbeConcludesParty(heldPartyId: String, active: WatchPartyState?): Boolean =
    active == null || active.id != heldPartyId || active.status == WatchPartyStatus.ended

/**
 * The ended state a client installs for a party it can no longer read.
 *
 * Kept as the held party rather than dropped to null because `status = ended` is the representation
 * every consumer was already written against, and because a null party is indistinguishable from a
 * lobby still joining - the one state in which a lobby must *not* close.
 */
fun WatchPartyState.concludedLocally(): WatchPartyState = copy(status = WatchPartyStatus.ended)
