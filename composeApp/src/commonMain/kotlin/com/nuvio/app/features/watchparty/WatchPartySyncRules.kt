package com.nuvio.app.features.watchparty

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/**
 * The decisions `WatchPartySync` makes that do not need a socket to be wrong.
 *
 * Split out of `WatchPartySyncTransport.kt` so `scripts/run-pure-suites.sh` can compile and execute
 * them: that file reaches the Supabase Realtime client and cannot be built outside Gradle, and the
 * consequence was a Watch Together suite that reported nine passing tests while the whole group had
 * silently stopped compiling. Every rule here has already cost a release once - a teardown that ran
 * twice, a subscribe timeout that retired the transport for the life of the process, and a command
 * that reached the network before it reached the player that issued it.
 */

internal data class PartyChannelClosePlan(
    val partyId: String?,
    val channelInstance: Long,
    val detached: Boolean,
)

/** Returns null after the binding has already been consumed, making teardown idempotent. */
internal fun partyChannelClosePlan(
    hasChannel: Boolean,
    boundPartyId: String?,
    channelInstance: Long,
    detached: Boolean,
): PartyChannelClosePlan? = if (!hasChannel && boundPartyId == null) {
    null
} else {
    PartyChannelClosePlan(boundPartyId, channelInstance, detached)
}

/**
 * Whether a throwable out of a channel open is the scope going away, rather than a failure the
 * reconnect loop is supposed to absorb.
 *
 * The subscribe is wrapped in `withTimeout`, and the [TimeoutCancellationException] it throws *is*
 * a [CancellationException] - so a bare `catch (c: CancellationException) { throw c }` sent it out
 * through the `collectLatest` on the desired-authority flow that drives `WatchPartySync` for the
 * whole process. One subscribe that ran long took the authority collector with it: every party
 * after that one was left on the poll floor, with nothing left in the process to reopen a channel.
 * A cancellation the loop caused itself is a failed attempt; only one it did not cause belongs to
 * the scope.
 */
internal fun partyChannelFailureIsScopeCancellation(failure: Throwable): Boolean =
    failure is CancellationException && failure !is TimeoutCancellationException

/**
 * The summary of the timing plane that the UI and the debug overlay want.
 *
 * Written only when something in it changes, so a tick twice a second does not recompose anything.
 */
data class WatchPartySyncState(
    val clockLocked: Boolean = false,
    val clockOffsetMs: Long = 0L,
    val bestRttMs: Long = -1L,
    val tickStatus: WatchPartyStatus? = null,
    val tickCapturedAtPartyMs: Long? = null,
    /** The newest host tick's stall-hold list: how a guest learns a pause was automatic. */
    val tickHold: List<String> = emptyList(),
    val holdingProfiles: List<String> = emptyList(),
    val peerTelemetry: Map<String, PartyPeerTelemetry> = emptyMap(),
    /**
     * Every member this client believes is away right now, the local viewer included.
     *
     * On the host it is built from the guests' peer reports plus its own presence, and it is what
     * the host publishes on its tick. On a guest it is the host's roster from that tick, plus the
     * guest's own presence - which is authoritative about itself and arrives a round trip before
     * the host could echo it back.
     */
    val awayProfileIds: Set<String> = emptySet(),
)

data class PartyPeerTelemetry(
    val status: WatchPartyStatus,
    val receivedAtPartyMs: Long,
    /**
     * The engine's own "nothing left to play", carried beside the status because the status cannot
     * express it: a member the party has paused reports `paused` whether it is full or empty. Comes
     * over the wire already - see `PartyPeerStatusMessage.starved` - and is what stops a readiness
     * barrier releasing onto a member parked on an empty engine.
     */
    val starved: Boolean = false,
    /**
     * The party instant the *sender* stamped on this report, which is the only clock that can say
     * whether it answers a question the party asked after it. [receivedAtPartyMs] cannot: a report
     * that crossed with the command on the wire is received after it and describes the member
     * before it.
     */
    val reportedAtPartyMs: Long = 0L,
    /**
     * This member is in the party and deliberately not watching. See `PartyPresence.kt`.
     *
     * Beside [starved] for the same reason [starved] is beside [status]: a backgrounded member and
     * a member who pressed pause both report `paused`, and the party has to be able to tell a
     * person who has stepped away from a person who is sitting there watching a still frame.
     */
    val away: Boolean = false,
)

/**
 * The order the two halves of an accepted command must happen in.
 *
 * The local directive first, always: the sender's own player must not wait on a network round trip
 * to obey a button its user just pressed. [enqueueRemoteDelivery] is what carries the same command
 * to everybody else, and since a client may not write the authority plane, that is the durable
 * submission - the backend authors the broadcast the other members act on.
 */
internal inline fun dispatchPartyCommandLocallyFirst(
    command: PartyCommand,
    emitDirective: (PartyCommand) -> Unit,
    enqueueRemoteDelivery: (PartyCommand) -> Unit,
) {
    emitDirective(command)
    enqueueRemoteDelivery(command)
}
