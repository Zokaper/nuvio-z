package com.nuvio.app.features.social

import co.touchlab.kermit.Logger
import com.nuvio.app.features.watchparty.WatchPartyRepository
import com.nuvio.app.features.watchparty.WatchPartyState
import com.nuvio.app.features.watchparty.WatchPartyStatus
import kotlinx.coroutines.sync.Mutex

/**
 * Joining a friend from Watching Now, end to end.
 *
 * ⚠ **Hardware Bug 6 (2026-09-15): Ask to join and Join both did nothing.** Traced against the
 * deployed backend (`pg_get_functiondef` on `social_join_watching`, `social_notification_action`,
 * `party_promote_presence_internal`, `social_get_state_v2`, and the trigger list), the flow was
 * dropped in four separate places, any one of which was enough:
 *
 *  1. **Home's button was not wired.** `homeSocialSections` defaulted `onStartParty` to `{}` and Home
 *     never passed one, so the button no RPC ever left. Production held exactly one
 *     `watch_join_requests` row, from 2026-09-12 - the hardware run created none.
 *  2. **Every failure was silent.** The Social tab's handler had `onSuccess` and no `onFailure`, so a
 *     refused call (`friendship_required`, a missing capability, a decode failure) looked exactly
 *     like a button that does nothing.
 *  3. **A guest was never told it had been accepted.** `social_notification_action` adds the
 *     requester to the party server-side and returns the snapshot to the *host*. Nothing reaches the
 *     requester: there is no notification kind for it, and `watch_join_requests` has no invalidation
 *     trigger. The guest sat on "Join request sent" while already a member of a party it did not know
 *     it was in.
 *  4. **A host was never told a direct join promoted its playback.** `social_join_watching` builds
 *     the party from the host's presence row and adds the guest, and returns the party only to the
 *     guest. `party_promote_presence_internal` writes no social table, so no invalidation reaches the
 *     host either: the guest went to a lobby for a party whose host kept watching alone.
 *
 * None of these needs a backend change. Delivery to the host rides the presence heartbeat the host
 * is already sending; discovery on both sides is `party_get_active`, which is scoped to the caller's
 * own profile and so answers for a membership the client did not create itself.
 */
sealed interface WatchingNowJoinStep {
    /** The server made this member part of a party: open its lobby, which starts playback. */
    data class OpenParty(val party: WatchPartyState) : WatchingNowJoinStep

    /**
     * A request is waiting on the host. [requestId] and [expiresAtMs] are what the outgoing request
     * store follows; either can be missing from an older backend.
     */
    data class AwaitApproval(val requestId: String?, val expiresAtMs: Long?) : WatchingNowJoinStep

    /** Nothing more will happen; say why. */
    data class Notice(val message: String) : WatchingNowJoinStep

    /**
     * The server answered with a party that is not the target's - a membership this client does not
     * hold and did not know about. Leave it, then ask again. Never opened.
     */
    data class ReleaseStrayMembership(val party: WatchPartyState) : WatchingNowJoinStep
}

/**
 * ⚠ **A join aimed at a friend may only ever open that friend's party.**
 *
 * `social_join_watching` checks whether the requester is already a member of *any* live party
 * before it looks at the target, and answers `already_joined` with that party. Opened unchecked, a
 * requester still holding a stray membership - a party a friend's direct join built from their own
 * earlier playback before their client adopted it, or one whose departure never reached the server -
 * would go to that party's lobby, as its host, instead of joining the friend they pressed Join on.
 * The only party this may open is one [targetProfileId] is actually in.
 */
fun decideWatchingNowJoin(
    result: Result<SocialActionResult>,
    targetProfileId: String,
    heldLivePartyId: String? = null,
): WatchingNowJoinStep {
    val action = result.getOrElse { failure ->
        return WatchingNowJoinStep.Notice(
            failure.message?.let(::joinFailureMessage) ?: "Couldn't join. Check your connection and try again.",
        )
    }
    val party = action.party?.takeIf { it.status != WatchPartyStatus.ended }
    return when (action.outcome) {
        "joined", "already_joined", "accepted" -> when {
            party == null -> WatchingNowJoinStep.Notice("Couldn't open the party. Try again.")
            party.includesProfile(targetProfileId) -> WatchingNowJoinStep.OpenParty(party)
            // A party this client is really in is the user's to leave, not this button's.
            party.id == heldLivePartyId ->
                WatchingNowJoinStep.Notice("You're already in a Watch Together party. Leave it to join this one.")
            else -> WatchingNowJoinStep.ReleaseStrayMembership(party)
        }
        "approval_required" -> WatchingNowJoinStep.AwaitApproval(
            requestId = action.requestId,
            expiresAtMs = action.expiresAt?.let(::parseSocialTimestampMs),
        )
        "disabled" -> WatchingNowJoinStep.Notice("This playback is not open to joining")
        "stale" -> WatchingNowJoinStep.Notice("This playback is no longer available")
        "full" -> WatchingNowJoinStep.Notice("This party is full")
        "unsupported_contract" -> WatchingNowJoinStep.Notice("Watch Together needs an update to join this playback")
        else -> WatchingNowJoinStep.Notice("Couldn't join. Try again.")
    }
}

private fun WatchPartyState.includesProfile(profileId: String): Boolean =
    hostProfileId == profileId || members.any { it.profileId == profileId }

private val joinInFlight = Mutex()

/**
 * The whole of a Join press: the RPC, the ownership check, and at most one recovery.
 *
 * Returns null for a press that arrived while another was still running - a double click must not
 * send two joins and push two lobbies. A stray membership is departed once and the join asked again;
 * a second stray answer, or a departure the server refused, fails cleanly instead of looping. Nothing
 * here ever creates a party: the only writes are the join itself and leaving a party the target is
 * not in.
 */
suspend fun joinWatchingNow(
    item: WatchingNowItem,
    heldLivePartyId: () -> String?,
    join: suspend (WatchingNowItem) -> Result<SocialActionResult> = SocialRepository::joinWatching,
    departStray: suspend (partyId: String) -> Result<Unit> = WatchPartyRepository::departStrayMembership,
): WatchingNowJoinStep? {
    if (!joinInFlight.tryLock()) {
        joinLog.i { "join ignored - another join is still in flight" }
        return null
    }
    try {
        val target = item.profile.profileId
        var released = false
        while (true) {
            val result = join(item)
            result.exceptionOrNull()?.let { failure -> joinLog.w(failure) { "join watching failed" } }
            val step = decideWatchingNowJoin(result, target, heldLivePartyId())
            if (step !is WatchingNowJoinStep.ReleaseStrayMembership) return step
            if (released) {
                joinLog.w { "join answered with a non-target party again party=${step.party.id.take(8)} - giving up" }
                return WatchingNowJoinStep.Notice("Couldn't join. Try again.")
            }
            joinLog.i { "join answered with a stray membership party=${step.party.id.take(8)} - leaving it and retrying once" }
            released = true
            departStray(step.party.id).onFailure { failure ->
                joinLog.w(failure) { "could not leave stray party=${step.party.id.take(8)}" }
                return WatchingNowJoinStep.Notice("Couldn't join. Try again.")
            }
        }
    } finally {
        joinInFlight.unlock()
    }
}

private fun joinFailureMessage(raw: String): String? = when {
    "friendship_required" in raw -> "You can only join friends' playback"
    "Watch Together update required" in raw -> "Watch Together needs an update to join this playback"
    else -> null
}

/**
 * How long a guest watches for acceptance. `social_join_watching` inserts the request with the
 * table's two-minute `expires_at`; a few seconds past it covers a host who accepts at the last moment.
 */
const val WatchingNowApprovalWatchMs = 125_000L

/** How often a waiting guest asks whether it has been added. One cheap, profile-scoped RPC. */
const val WatchingNowApprovalPollMs = 3_000L

sealed interface JoinApprovalPoll {
    data object Continue : JoinApprovalPoll
    data class Joined(val party: WatchPartyState) : JoinApprovalPoll

    /** This member is already in a party some other way. Stop watching, and change nothing. */
    data object Superseded : JoinApprovalPoll

    /** The request expired unanswered or was declined - the server does not say which. */
    data object GaveUp : JoinApprovalPoll
}

/**
 * One tick of the guest's wait for approval.
 *
 * A probe that failed is not an answer - the network blinked - so it continues until the deadline.
 * A party the member was already holding before the probe is left alone: whatever put them there,
 * it was not this request, and dragging them to another lobby would corrupt that party's state.
 */
fun decideJoinApprovalPoll(
    elapsedMs: Long,
    heldLivePartyId: String?,
    probe: Result<WatchPartyState?>,
    timeoutMs: Long = WatchingNowApprovalWatchMs,
): JoinApprovalPoll {
    if (heldLivePartyId != null) return JoinApprovalPoll.Superseded
    val active = probe.getOrNull()?.takeIf { it.status != WatchPartyStatus.ended }
    if (active != null) return JoinApprovalPoll.Joined(active)
    return if (elapsedMs >= timeoutMs) JoinApprovalPoll.GaveUp else JoinApprovalPoll.Continue
}

private val joinLog = Logger.withTag("SocialJoin")

/**
 * Whether a playing host should look for a party it did not create itself.
 *
 * Only while it is publishing joinable presence and holds no live party: that is exactly the state a
 * direct join (or an approval accepted on another surface) turns into "hosting", without telling it.
 */
fun shouldDiscoverPromotedParty(policy: WatchJoinPolicy, heldParty: WatchPartyState?): Boolean =
    policy != WatchJoinPolicy.disabled && (heldParty == null || heldParty.status == WatchPartyStatus.ended)

/**
 * Whether a playing host should refresh its social state on this heartbeat, so a join request reaches
 * it. Requests only exist under approval, and nothing on the backend invalidates the host when one is
 * inserted; the heartbeat is the delivery. Held party or not: a second friend can ask to join a party
 * the first request already created.
 */
fun shouldPollForJoinRequests(policy: WatchJoinPolicy): Boolean = policy == WatchJoinPolicy.approval
