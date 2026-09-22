package com.nuvio.app.features.social

import co.touchlab.kermit.Logger
import com.nuvio.app.features.watchparty.PartyClientPhase
import com.nuvio.app.features.watchparty.WatchPartyRepository
import com.nuvio.app.features.watchparty.WatchPartySessionCoordinator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private val log = Logger.withTag("SocialFeatureShutdown")

/**
 * How long to wait for a party departure to land before giving up and clearing local state anyway.
 *
 * The departure RPC is the thing that tells the server, and on a dead network it will not
 * complete. Blocking a settings toggle on an unreachable server is worse than clearing locally
 * and letting the reaper collect the membership - `party_transfer_stale_host` and the stale reap
 * exist precisely for a client that vanished. Ten seconds is longer than the 60-second abandon
 * window needs and short enough that a person does not think the switch is broken.
 */
private const val DepartureTimeoutMs = 10_000L

/**
 * Whether a party is live enough that turning social off has to leave it first.
 *
 * `None` and `Ended` are the two phases with nothing to depart. Everything else - including
 * `Lobby`, `Reconnecting` and `Detached` - is a membership the server still believes in.
 */
val PartyClientPhase.holdsLiveParty: Boolean
    get() = this != PartyClientPhase.None && this != PartyClientPhase.Ended

/**
 * Take the social layer down for a profile before its surfaces disappear.
 *
 * ⚠ **The order is the whole function.** A party must be departed *through the coordinator* -
 * which is what runs `party_leave` and, for a host, hands the party to somebody else - before the
 * repository is told to forget which party it was in. Clearing state first would leave the server
 * holding a live membership nobody reaps on time and, for a host, a party with no host until the
 * 15-second grace and the stale-host transfer notice. Presence and the Realtime channel close
 * last, because they are how the departure is announced.
 *
 * ⚠ **Lifecycle only.** Nothing here touches party authority, the sequence, source matching or
 * the sync protocol. Every call is an existing Phase 4 entry point, used exactly as a manual
 * "leave party" uses it; Phase 4 is closed and this does not reopen it.
 *
 * Idempotent: safe to call when nothing is running, which is the common case (a first-run wizard
 * has no party to leave) and is why the callers need no separate no-party path.
 */
suspend fun shutdownSocialLayer() {
    val phase = WatchPartySessionCoordinator.state.value.phase
    if (phase.holdsLiveParty) {
        log.i { "leaving party before social shutdown, phase=$phase" }
        WatchPartySessionCoordinator.leave()
        // The coordinator serialises its intents, so the departure is not done when `leave()`
        // returns - it is done when the phase says so. Waiting on the phase rather than sleeping
        // is what makes the ordering real instead of hopeful.
        val settled = withTimeoutOrNull(DepartureTimeoutMs) {
            WatchPartySessionCoordinator.state.first { !it.phase.holdsLiveParty }
        }
        if (settled == null) {
            log.w { "party departure did not settle in ${DepartureTimeoutMs}ms; clearing locally" }
        }
    }

    // An outgoing join request is settled while the party identity still exists: its cancel goes out
    // as this profile, and an accepted-race answer departs the party it produced. Awaited, so the
    // departure is not racing `setActiveProfile(null)` below.
    OutgoingJoinRequestStore.onIdentityBoundary(
        SocialRepository.uiState.value.activeProfileId,
        serverCleanup = true,
    )

    // Forget the profile. Clears `PartySourceRealizer`, drops the sync authority, resets the
    // party UI state - and calls `leave()` itself if the step above somehow left a party held.
    WatchPartyRepository.setActiveProfile(null)

    // Clear presence on every device this profile published from, close the `social:` channel and
    // reset the social state. The existing shutdown path, unchanged.
    SocialRepository.activate(null)
}
