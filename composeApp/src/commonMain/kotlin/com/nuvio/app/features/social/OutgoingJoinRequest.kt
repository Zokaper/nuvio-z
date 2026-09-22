package com.nuvio.app.features.social

import com.nuvio.app.features.watchparty.WatchPartyState
import com.nuvio.app.features.watchparty.WatchPartyStatus

/**
 * The outgoing half of Watching Now's Join / Ask to join, as a pure reducer.
 *
 * It replaces an invisible `awaitJoinApproval` poll that lived on `MainAppContent`'s scope: no cancel,
 * no difference between declined and expired, and an accepted guest pulled into a lobby with no
 * warning. The process store that runs this (`OutgoingJoinRequestStore`) only executes the
 * [OutgoingJoinEffect]s it returns, so every decision - including every identity-boundary rule - is
 * here, where the pure suites can execute it.
 *
 * ⚠ **A request belongs to one profile's session and must never outlive it.** Every event that comes
 * back from asynchronous work names the [JoinRequestBinding] it was started under. An event whose
 * binding is not the current state's is stale - a send, status read, invalidation or countdown that
 * finished after a profile switch, a sign-out or Social being turned off - and it is dropped with a
 * log effect. It can never navigate, open a lobby, depart a party or show an outcome. The store bumps
 * the token at every boundary, so the same profile re-activated cannot resurrect a request either.
 */

/** How long Cancelled stays on screen before it clears. */
const val OutgoingJoinCancelledDisplayMs = 2_000L

/** How long Declined / Expired / TargetStopped stay on screen, with "Ask again". */
const val OutgoingJoinOutcomeDisplayMs = 6_000L

/** An accepted request joins by itself after this long, unless the viewer is in their own player. */
const val OutgoingJoinAcceptCountdownMs = 3_000L

/** Floor for status reads while pending; realtime invalidation reads sooner. */
const val OutgoingJoinPollMs = 3_000L

/** `watch_join_requests.expires_at` defaults to two minutes; used when the server did not say. */
const val OutgoingJoinDefaultLifetimeMs = 120_000L

/** Past `expires_at` with no answer, the request is treated as expired after this much grace. */
const val OutgoingJoinExpiryGraceMs = 5_000L

/** Server-side cleanup at a boundary is best effort and must not hold the boundary up for longer. */
const val OutgoingJoinBoundaryCleanupTimeoutMs = 5_000L

data class JoinRequestBinding(val ownerProfileId: String, val token: Long)

data class JoinRequestTarget(
    val profileId: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val avatarColorHex: String = "#1E88E5",
    val sessionId: String,
)

data class JoinRequestContent(
    val contentId: String,
    val videoId: String,
    val title: String,
    val season: Int? = null,
    val episode: Int? = null,
    val poster: String? = null,
    val background: String? = null,
)

fun WatchingNowItem.joinRequestTarget(): JoinRequestTarget = JoinRequestTarget(
    profileId = profile.profileId,
    displayName = profile.displayName.ifBlank { profile.handle },
    avatarUrl = profile.avatarUrl,
    avatarColorHex = profile.avatarColorHex,
    sessionId = sessionId,
)

fun WatchingNowItem.joinRequestContent(): JoinRequestContent = JoinRequestContent(
    contentId = contentId,
    videoId = videoId,
    title = title,
    season = season,
    episode = episode,
    poster = poster,
    background = background,
)

sealed interface OutgoingJoinRequestState {
    data object Idle : OutgoingJoinRequestState

    sealed interface Bound : OutgoingJoinRequestState {
        val binding: JoinRequestBinding
        val target: JoinRequestTarget
        val content: JoinRequestContent
    }

    data class Sending(
        override val binding: JoinRequestBinding,
        override val target: JoinRequestTarget,
        override val content: JoinRequestContent,
    ) : Bound

    data class Pending(
        override val binding: JoinRequestBinding,
        override val target: JoinRequestTarget,
        override val content: JoinRequestContent,
        val requestId: String,
        val expiresAtMs: Long,
    ) : Bound

    data class Cancelling(
        override val binding: JoinRequestBinding,
        override val target: JoinRequestTarget,
        override val content: JoinRequestContent,
        val requestId: String,
        val expiresAtMs: Long,
    ) : Bound

    /**
     * The host let this member in. [countdownDeadlineMs] is null while the viewer is in their own
     * player: there it waits for an explicit Join / Not now instead of pulling them out of a film.
     */
    data class Accepted(
        override val binding: JoinRequestBinding,
        override val target: JoinRequestTarget,
        override val content: JoinRequestContent,
        val party: WatchPartyState,
        val countdownDeadlineMs: Long?,
    ) : Bound

    /** The lobby is being opened. Cleared when the store reports it opened. */
    data class Joining(
        override val binding: JoinRequestBinding,
        override val target: JoinRequestTarget,
        override val content: JoinRequestContent,
        val party: WatchPartyState,
    ) : Bound

    /** A short-lived outcome. [clearAtMs] is when it goes away by itself. */
    data class Outcome(
        override val binding: JoinRequestBinding,
        override val target: JoinRequestTarget,
        override val content: JoinRequestContent,
        val kind: OutgoingJoinOutcome,
        val clearAtMs: Long?,
        val message: String? = null,
    ) : Bound
}

enum class OutgoingJoinOutcome {
    Cancelled,
    Declined,
    Expired,
    TargetStopped,

    /** Sending or opening failed. Stays until dismissed or retried; carries its message. */
    Failed,
}

/** What `social_join_request_status` answers, as the client reads it. */
enum class JoinRequestServerStatus { pending, accepted, declined, expired, cancelled, not_found }

/** The initial send, already interpreted by `decideWatchingNowJoin`. */
sealed interface JoinSendAnswer {
    data class ApprovalRequired(val requestId: String?, val expiresAtMs: Long?) : JoinSendAnswer
    data class OpenParty(val party: WatchPartyState) : JoinSendAnswer
    data class Notice(val message: String) : JoinSendAnswer
}

sealed interface JoinCancelAnswer {
    data object Cancelled : JoinCancelAnswer

    /** The host won the race; this member is in [party] and has to leave it. */
    data class AlreadyAccepted(val party: WatchPartyState?) : JoinCancelAnswer

    /** Declined or expired by the time the cancel landed - nothing to undo. */
    data object AlreadyClosed : JoinCancelAnswer
    data class Failed(val message: String) : JoinCancelAnswer
}

sealed interface OutgoingJoinEvent {
    /** Present on every event that reports back from asynchronous work. */
    sealed interface Bound : OutgoingJoinEvent { val binding: JoinRequestBinding }

    /** A press on Join / Ask to join. The store has already minted [binding] with a fresh token. */
    data class Send(
        val binding: JoinRequestBinding,
        val target: JoinRequestTarget,
        val content: JoinRequestContent,
    ) : OutgoingJoinEvent

    data class SendAnswered(
        override val binding: JoinRequestBinding,
        val answer: JoinSendAnswer,
        val nowMs: Long,
        val inOwnPlayer: Boolean,
    ) : Bound

    data class SendFailed(override val binding: JoinRequestBinding, val message: String) : Bound

    data class StatusRead(
        override val binding: JoinRequestBinding,
        val requestId: String,
        val status: JoinRequestServerStatus,
        /** The party, when accepted and this member is still in it. */
        val party: WatchPartyState?,
        val expiresAtMs: Long?,
        val nowMs: Long,
        val inOwnPlayer: Boolean,
    ) : Bound

    /** The friend's presence row, as Watching Now currently lists it. */
    data class TargetPresence(
        override val binding: JoinRequestBinding,
        val stillWatching: Boolean,
        val nowMs: Long,
    ) : Bound

    /** The live party this member holds changed. */
    data class HeldPartyChanged(override val binding: JoinRequestBinding, val heldPartyId: String?) : Bound

    data class CancelPressed(override val binding: JoinRequestBinding) : Bound
    data class CancelAnswered(
        override val binding: JoinRequestBinding,
        val answer: JoinCancelAnswer,
        val nowMs: Long,
    ) : Bound

    data class PlayerPresenceChanged(
        override val binding: JoinRequestBinding,
        val inOwnPlayer: Boolean,
    ) : Bound

    data class CountdownElapsed(override val binding: JoinRequestBinding, val nowMs: Long) : Bound
    data class JoinPressed(override val binding: JoinRequestBinding) : Bound
    data class NotNowPressed(override val binding: JoinRequestBinding) : Bound
    data class LobbyOpened(override val binding: JoinRequestBinding) : Bound
    data class LobbyFailed(override val binding: JoinRequestBinding, val message: String) : Bound
    data class Dismissed(override val binding: JoinRequestBinding) : Bound

    /** Clock tick: clears outcomes whose display time is over, expires an unanswered request. */
    data class Tick(val nowMs: Long) : OutgoingJoinEvent

    /**
     * Profile switch, sign-out, Social disabled, a wipe, or the Watch Party capability going away.
     * [serverCleanup] is false for a wipe, where no network is guaranteed.
     */
    data class IdentityBoundary(val previousProfileId: String?, val serverCleanup: Boolean) : OutgoingJoinEvent
}

sealed interface OutgoingJoinEffect {
    data class SendJoin(val binding: JoinRequestBinding, val target: JoinRequestTarget) : OutgoingJoinEffect

    /** Cancel [requestId] *as* [ownerProfileId], never as whoever is active when it runs. */
    data class CancelOnServer(
        val binding: JoinRequestBinding,
        val ownerProfileId: String,
        val requestId: String,
        /** A boundary cleanup: bounded by [OutgoingJoinBoundaryCleanupTimeoutMs], never reported back. */
        val boundary: Boolean,
    ) : OutgoingJoinEffect

    data class DepartParty(val ownerProfileId: String, val partyId: String) : OutgoingJoinEffect
    data class OpenLobby(val binding: JoinRequestBinding, val party: WatchPartyState) : OutgoingJoinEffect

    /** Remember an abandoned request so a later `restore` does not walk into the party it became. */
    data class RememberAbandoned(val ownerProfileId: String, val requestId: String) : OutgoingJoinEffect
    data object CancelJobs : OutgoingJoinEffect
    data class LogStale(val event: String) : OutgoingJoinEffect

    /** The send for [binding] was abandoned while still on the wire; its answer must be undone, not dropped. */
    data class AbandonSend(val binding: JoinRequestBinding) : OutgoingJoinEffect

    /**
     * A send answered after the request it belonged to was cancelled, replaced or crossed a boundary.
     *
     * The server acted on it regardless - a direct join is a membership, an Ask is a live request the
     * host can still accept - so it is released as its own owner rather than silently discarded.
     */
    data class ReleaseOrphanedSend(val ownerProfileId: String, val answer: JoinSendAnswer) : OutgoingJoinEffect
}

data class OutgoingJoinTransition(
    val state: OutgoingJoinRequestState,
    val effects: List<OutgoingJoinEffect> = emptyList(),
)

fun reduceOutgoingJoinRequest(
    state: OutgoingJoinRequestState,
    event: OutgoingJoinEvent,
): OutgoingJoinTransition {
    val same = OutgoingJoinTransition(state)
    // A send answer for anything but the request now sending is an orphan with server-side effects.
    if (event is OutgoingJoinEvent.SendAnswered &&
        (state as? OutgoingJoinRequestState.Sending)?.binding != event.binding
    ) {
        return OutgoingJoinTransition(
            state,
            listOf(OutgoingJoinEffect.ReleaseOrphanedSend(event.binding.ownerProfileId, event.answer)),
        )
    }
    if (event is OutgoingJoinEvent.Bound) {
        val bound = state as? OutgoingJoinRequestState.Bound
        if (bound == null || bound.binding != event.binding) {
            return OutgoingJoinTransition(state, listOf(OutgoingJoinEffect.LogStale(event::class.simpleName ?: "event")))
        }
    }
    return when (event) {
        is OutgoingJoinEvent.Send -> {
            // One request at a time: a new press replaces the old one after cancelling it.
            val effects = mutableListOf<OutgoingJoinEffect>(OutgoingJoinEffect.CancelJobs)
            effects += releaseEffects(state, boundary = false)
            effects += OutgoingJoinEffect.SendJoin(event.binding, event.target)
            OutgoingJoinTransition(
                OutgoingJoinRequestState.Sending(event.binding, event.target, event.content),
                effects,
            )
        }

        is OutgoingJoinEvent.SendAnswered -> {
            val sending = state as? OutgoingJoinRequestState.Sending ?: return same
            when (val answer = event.answer) {
                is JoinSendAnswer.ApprovalRequired -> if (answer.requestId == null) {
                    failed(sending, "Couldn't send the request. Try again.")
                } else {
                    OutgoingJoinTransition(
                        OutgoingJoinRequestState.Pending(
                            binding = sending.binding,
                            target = sending.target,
                            content = sending.content,
                            requestId = answer.requestId,
                            expiresAtMs = answer.expiresAtMs ?: (event.nowMs + OutgoingJoinDefaultLifetimeMs),
                        ),
                    )
                }
                // A direct join: the viewer pressed Join, so there is no countdown and no question.
                is JoinSendAnswer.OpenParty -> OutgoingJoinTransition(
                    OutgoingJoinRequestState.Joining(sending.binding, sending.target, sending.content, answer.party),
                    listOf(OutgoingJoinEffect.OpenLobby(sending.binding, answer.party)),
                )
                is JoinSendAnswer.Notice -> failed(sending, answer.message)
            }
        }

        is OutgoingJoinEvent.SendFailed -> {
            val sending = state as? OutgoingJoinRequestState.Sending ?: return same
            failed(sending, event.message)
        }

        is OutgoingJoinEvent.StatusRead -> {
            val waiting = state.waitingRequest() ?: return same
            if (waiting.requestId != event.requestId) return same
            val bound = state as OutgoingJoinRequestState.Bound
            when (event.status) {
                JoinRequestServerStatus.pending -> if (state is OutgoingJoinRequestState.Pending && event.expiresAtMs != null) {
                    OutgoingJoinTransition(state.copy(expiresAtMs = event.expiresAtMs))
                } else {
                    same
                }
                JoinRequestServerStatus.accepted -> {
                    val party = event.party?.takeIf { it.status != WatchPartyStatus.ended }
                    when {
                        party == null -> outcome(bound, OutgoingJoinOutcome.Expired, event.nowMs)
                        // The host won a cancel race: the viewer asked to stop, so leave, don't open.
                        state is OutgoingJoinRequestState.Cancelling -> OutgoingJoinTransition(
                            OutgoingJoinRequestState.Idle,
                            listOf(
                                OutgoingJoinEffect.CancelJobs,
                                OutgoingJoinEffect.DepartParty(bound.binding.ownerProfileId, party.id),
                            ),
                        )
                        else -> accepted(bound, party, event.nowMs, event.inOwnPlayer)
                    }
                }
                JoinRequestServerStatus.declined -> outcome(bound, OutgoingJoinOutcome.Declined, event.nowMs)
                JoinRequestServerStatus.expired,
                JoinRequestServerStatus.not_found,
                -> outcome(bound, OutgoingJoinOutcome.Expired, event.nowMs)
                // Cancelled from another surface or device: nothing to say.
                JoinRequestServerStatus.cancelled -> if (state is OutgoingJoinRequestState.Cancelling) {
                    outcome(bound, OutgoingJoinOutcome.Cancelled, event.nowMs)
                } else {
                    OutgoingJoinTransition(OutgoingJoinRequestState.Idle, listOf(OutgoingJoinEffect.CancelJobs))
                }
            }
        }

        is OutgoingJoinEvent.TargetPresence -> {
            val pending = state as? OutgoingJoinRequestState.Pending ?: return same
            if (event.stillWatching) return same
            // The request dies with the presence it was aimed at; tidy the server row too.
            val t = outcome(pending, OutgoingJoinOutcome.TargetStopped, event.nowMs)
            t.copy(
                effects = t.effects + OutgoingJoinEffect.CancelOnServer(
                    binding = pending.binding,
                    ownerProfileId = pending.binding.ownerProfileId,
                    requestId = pending.requestId,
                    boundary = true,
                ),
            )
        }

        is OutgoingJoinEvent.HeldPartyChanged -> {
            val heldId = event.heldPartyId ?: return same
            when (state) {
                is OutgoingJoinRequestState.Accepted -> if (state.party.id == heldId) same else superseded(state)
                is OutgoingJoinRequestState.Joining -> same
                is OutgoingJoinRequestState.Sending,
                is OutgoingJoinRequestState.Pending,
                is OutgoingJoinRequestState.Cancelling,
                -> superseded(state as OutgoingJoinRequestState.Bound)
                else -> same
            }
        }

        is OutgoingJoinEvent.CancelPressed -> when (state) {
            is OutgoingJoinRequestState.Pending -> OutgoingJoinTransition(
                OutgoingJoinRequestState.Cancelling(
                    state.binding, state.target, state.content, state.requestId, state.expiresAtMs,
                ),
                listOf(
                    OutgoingJoinEffect.CancelOnServer(
                        binding = state.binding,
                        ownerProfileId = state.binding.ownerProfileId,
                        requestId = state.requestId,
                        boundary = false,
                    ),
                ),
            )
            // ⚠ **The send is still on the wire, and the server will act on it.** Idle at once for the
            // viewer, but the answer is marked abandoned so it is undone when it lands - a direct join
            // left, an Ask cancelled - rather than dropped as stale and left for the host to accept.
            is OutgoingJoinRequestState.Sending -> OutgoingJoinTransition(
                OutgoingJoinRequestState.Idle,
                listOf(OutgoingJoinEffect.CancelJobs, OutgoingJoinEffect.AbandonSend(state.binding)),
            )
            is OutgoingJoinRequestState.Accepted -> notNow(state)
            else -> same
        }

        is OutgoingJoinEvent.CancelAnswered -> {
            val cancelling = state as? OutgoingJoinRequestState.Cancelling ?: return same
            when (val answer = event.answer) {
                JoinCancelAnswer.Cancelled,
                JoinCancelAnswer.AlreadyClosed,
                -> outcome(cancelling, OutgoingJoinOutcome.Cancelled, event.nowMs)
                is JoinCancelAnswer.AlreadyAccepted -> {
                    val effects = mutableListOf<OutgoingJoinEffect>(OutgoingJoinEffect.CancelJobs)
                    answer.party?.let { effects += OutgoingJoinEffect.DepartParty(cancelling.binding.ownerProfileId, it.id) }
                    OutgoingJoinTransition(
                        OutgoingJoinRequestState.Outcome(
                            cancelling.binding, cancelling.target, cancelling.content,
                            OutgoingJoinOutcome.Cancelled, event.nowMs + OutgoingJoinCancelledDisplayMs,
                        ),
                        effects,
                    )
                }
                // The request is still out there; go back to waiting on it rather than pretend.
                is JoinCancelAnswer.Failed -> OutgoingJoinTransition(
                    OutgoingJoinRequestState.Pending(
                        cancelling.binding, cancelling.target, cancelling.content,
                        cancelling.requestId, cancelling.expiresAtMs,
                    ),
                )
            }
        }

        is OutgoingJoinEvent.PlayerPresenceChanged -> {
            val accepted = state as? OutgoingJoinRequestState.Accepted ?: return same
            // Entering the player stops the countdown. Leaving it does not restart one: the viewer
            // has already been asked, and a join that fires because they closed a film is a kidnapping.
            if (event.inOwnPlayer && accepted.countdownDeadlineMs != null) {
                OutgoingJoinTransition(accepted.copy(countdownDeadlineMs = null))
            } else {
                same
            }
        }

        is OutgoingJoinEvent.CountdownElapsed -> {
            val accepted = state as? OutgoingJoinRequestState.Accepted ?: return same
            val deadline = accepted.countdownDeadlineMs ?: return same
            if (event.nowMs < deadline) same else join(accepted)
        }

        is OutgoingJoinEvent.JoinPressed -> {
            val accepted = state as? OutgoingJoinRequestState.Accepted ?: return same
            join(accepted)
        }

        is OutgoingJoinEvent.NotNowPressed -> {
            val accepted = state as? OutgoingJoinRequestState.Accepted ?: return same
            notNow(accepted)
        }

        is OutgoingJoinEvent.LobbyOpened -> if (state is OutgoingJoinRequestState.Joining) {
            OutgoingJoinTransition(OutgoingJoinRequestState.Idle, listOf(OutgoingJoinEffect.CancelJobs))
        } else {
            same
        }

        is OutgoingJoinEvent.LobbyFailed -> if (state is OutgoingJoinRequestState.Joining) {
            failed(state, event.message)
        } else {
            same
        }

        is OutgoingJoinEvent.Dismissed -> when (state) {
            is OutgoingJoinRequestState.Outcome ->
                OutgoingJoinTransition(OutgoingJoinRequestState.Idle, listOf(OutgoingJoinEffect.CancelJobs))
            else -> same
        }

        is OutgoingJoinEvent.Tick -> when (state) {
            is OutgoingJoinRequestState.Outcome ->
                if (state.clearAtMs != null && event.nowMs >= state.clearAtMs) {
                    OutgoingJoinTransition(OutgoingJoinRequestState.Idle, listOf(OutgoingJoinEffect.CancelJobs))
                } else {
                    same
                }
            is OutgoingJoinRequestState.Pending ->
                if (event.nowMs >= state.expiresAtMs + OutgoingJoinExpiryGraceMs) {
                    outcome(state, OutgoingJoinOutcome.Expired, event.nowMs)
                } else {
                    same
                }
            is OutgoingJoinRequestState.Accepted -> {
                val deadline = state.countdownDeadlineMs
                if (deadline != null && event.nowMs >= deadline) join(state) else same
            }
            else -> same
        }

        is OutgoingJoinEvent.IdentityBoundary -> {
            val bound = state as? OutgoingJoinRequestState.Bound
            val effects = mutableListOf<OutgoingJoinEffect>(OutgoingJoinEffect.CancelJobs)
            if (bound != null && event.serverCleanup) {
                effects += releaseEffects(bound, boundary = true)
            } else if (bound != null) {
                // No network: remember what was abandoned, so restore can refuse the party it became.
                bound.abandonedRequestId()?.let {
                    effects += OutgoingJoinEffect.RememberAbandoned(bound.binding.ownerProfileId, it)
                }
            }
            // No outcome pill: a boundary is not something the next identity should be told about.
            OutgoingJoinTransition(OutgoingJoinRequestState.Idle, effects)
        }
    }
}

private data class WaitingRequest(val requestId: String)

private fun OutgoingJoinRequestState.waitingRequest(): WaitingRequest? = when (this) {
    is OutgoingJoinRequestState.Pending -> WaitingRequest(requestId)
    is OutgoingJoinRequestState.Cancelling -> WaitingRequest(requestId)
    else -> null
}

private fun OutgoingJoinRequestState.Bound.abandonedRequestId(): String? = when (this) {
    is OutgoingJoinRequestState.Pending -> requestId
    is OutgoingJoinRequestState.Cancelling -> requestId
    else -> null
}

/** What leaving [state] behind needs on the server, as its own owner. */
private fun releaseEffects(state: OutgoingJoinRequestState, boundary: Boolean): List<OutgoingJoinEffect> {
    val bound = state as? OutgoingJoinRequestState.Bound ?: return emptyList()
    val owner = bound.binding.ownerProfileId
    return when (bound) {
        is OutgoingJoinRequestState.Pending -> listOfNotNull(
            OutgoingJoinEffect.CancelOnServer(bound.binding, owner, bound.requestId, boundary = true),
            if (boundary) OutgoingJoinEffect.RememberAbandoned(owner, bound.requestId) else null,
        )
        is OutgoingJoinRequestState.Cancelling -> listOfNotNull(
            OutgoingJoinEffect.CancelOnServer(bound.binding, owner, bound.requestId, boundary = true),
            if (boundary) OutgoingJoinEffect.RememberAbandoned(owner, bound.requestId) else null,
        )
        // Accepted but never opened: the membership exists server-side and nobody is going to use it.
        is OutgoingJoinRequestState.Accepted -> listOf(OutgoingJoinEffect.DepartParty(owner, bound.party.id))
        // A lobby is already opening; at a boundary it is torn down by the party layer's own reset.
        is OutgoingJoinRequestState.Joining ->
            if (boundary) listOf(OutgoingJoinEffect.DepartParty(owner, bound.party.id)) else emptyList()
        is OutgoingJoinRequestState.Sending,
        is OutgoingJoinRequestState.Outcome,
        -> emptyList()
    }
}

private fun failed(state: OutgoingJoinRequestState.Bound, message: String) = OutgoingJoinTransition(
    OutgoingJoinRequestState.Outcome(
        state.binding, state.target, state.content, OutgoingJoinOutcome.Failed, clearAtMs = null, message = message,
    ),
    listOf(OutgoingJoinEffect.CancelJobs),
)

private fun outcome(state: OutgoingJoinRequestState.Bound, kind: OutgoingJoinOutcome, nowMs: Long) = OutgoingJoinTransition(
    OutgoingJoinRequestState.Outcome(
        binding = state.binding,
        target = state.target,
        content = state.content,
        kind = kind,
        clearAtMs = nowMs + if (kind == OutgoingJoinOutcome.Cancelled) OutgoingJoinCancelledDisplayMs else OutgoingJoinOutcomeDisplayMs,
    ),
    listOf(OutgoingJoinEffect.CancelJobs),
)

private fun accepted(
    state: OutgoingJoinRequestState.Bound,
    party: WatchPartyState,
    nowMs: Long,
    inOwnPlayer: Boolean,
) = OutgoingJoinTransition(
    OutgoingJoinRequestState.Accepted(
        binding = state.binding,
        target = state.target,
        content = state.content,
        party = party,
        countdownDeadlineMs = if (inOwnPlayer) null else nowMs + OutgoingJoinAcceptCountdownMs,
    ),
)

private fun join(state: OutgoingJoinRequestState.Accepted) = OutgoingJoinTransition(
    OutgoingJoinRequestState.Joining(state.binding, state.target, state.content, state.party),
    listOf(OutgoingJoinEffect.OpenLobby(state.binding, state.party)),
)

private fun notNow(state: OutgoingJoinRequestState.Accepted) = OutgoingJoinTransition(
    OutgoingJoinRequestState.Idle,
    listOf(
        OutgoingJoinEffect.CancelJobs,
        OutgoingJoinEffect.DepartParty(state.binding.ownerProfileId, state.party.id),
    ),
)

/** Joined some other way while waiting: stop, change nothing, say nothing. */
private fun superseded(state: OutgoingJoinRequestState.Bound): OutgoingJoinTransition {
    val effects = mutableListOf<OutgoingJoinEffect>(OutgoingJoinEffect.CancelJobs)
    state.abandonedRequestId()?.let {
        effects += OutgoingJoinEffect.CancelOnServer(state.binding, state.binding.ownerProfileId, it, boundary = true)
    }
    return OutgoingJoinTransition(OutgoingJoinRequestState.Idle, effects)
}

sealed interface JoinRequestPollDecision {
    /** Nothing to poll for. */
    data object Stop : JoinRequestPollDecision
    data object ReadNow : JoinRequestPollDecision
    data class WaitUntil(val atMs: Long) : JoinRequestPollDecision
}

/**
 * When the store should next read the request's status.
 *
 * Only a request that is waiting on the host is polled. A realtime invalidation reads at once; without
 * one, reads are [OutgoingJoinPollMs] apart - the floor the old approval poll kept - and stop being
 * worth anything past expiry plus grace, where the [OutgoingJoinEvent.Tick] expiry takes over.
 */
fun decideJoinRequestPoll(
    state: OutgoingJoinRequestState,
    nowMs: Long,
    lastReadAtMs: Long?,
    invalidated: Boolean,
): JoinRequestPollDecision {
    val expiresAtMs = when (state) {
        is OutgoingJoinRequestState.Pending -> state.expiresAtMs
        is OutgoingJoinRequestState.Cancelling -> state.expiresAtMs
        else -> return JoinRequestPollDecision.Stop
    }
    if (nowMs >= expiresAtMs + OutgoingJoinExpiryGraceMs) return JoinRequestPollDecision.Stop
    if (invalidated || lastReadAtMs == null) return JoinRequestPollDecision.ReadNow
    val next = lastReadAtMs + OutgoingJoinPollMs
    return if (nowMs >= next) JoinRequestPollDecision.ReadNow else JoinRequestPollDecision.WaitUntil(next)
}

/**
 * What a boundary cleanup's cancel answer still requires. A boundary cancel is never reported back to
 * the reducer - the state is already Idle and belongs to nobody - so the one follow-up it can need,
 * leaving a party the host accepted into just before the boundary, is decided here.
 */
fun boundaryCancelFollowUp(ownerProfileId: String, answer: JoinCancelAnswer): OutgoingJoinEffect? =
    (answer as? JoinCancelAnswer.AlreadyAccepted)?.party?.let { OutgoingJoinEffect.DepartParty(ownerProfileId, it.id) }
