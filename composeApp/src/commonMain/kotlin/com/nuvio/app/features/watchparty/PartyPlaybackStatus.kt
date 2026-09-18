package com.nuvio.app.features.watchparty

/**
 * The one line the player shows about the party, and the rule for which one wins.
 *
 * Replaces `WatchPartyPlayerStatus.bannerText()`, which covered the start gate, host buffering and a
 * lost sync and nothing else. Several conditions a guest actually sat through said nothing at all: an
 * automatic stall hold (which instead arrived as a toast blaming a person for pausing), the guest's
 * own source or episode handoff, a barrier park, and a party that simply stayed paused a minute after
 * the 900ms attribution toast had gone.
 *
 * Pure and import-free beyond the party models: every name arrives already resolved as a
 * [PartyStatusPerson], so the priority table can be executed by the pure suites. Debouncing is a
 * separate reducer ([debouncePartyStatus]) run by the consumer, never folded in here.
 */

enum class PartyStatusKind {
    SourceNotFound,
    VersionTooShort,
    SwitchingEpisode,
    MatchingSource,
    HostChoosingSource,
    WaitingForSources,
    WaitingForBuffering,
    EveryoneWaitingOnYou,
    HostBuffering,
    WaitingForHostStart,
    CatchingUp,
    IncomingJoinRequest,
    Reconnecting,
    Offline,
    PausedBy,
    OutgoingJoinRequest,
}

enum class PartyStatusTone { Neutral, Waiting, Warning, Error }

enum class PartyStatusAction { ChooseSource, StartAnyway, DontWait, LetIn, Decline, CancelOutgoing }

data class PartyStatusPerson(
    val profileId: String,
    val name: String,
    val avatarUrl: String? = null,
    val avatarColorHex: String = "#1E88E5",
)

data class PartyStatusLine(
    val kind: PartyStatusKind,
    val text: String,
    val people: List<PartyStatusPerson> = emptyList(),
    val action: PartyStatusAction? = null,
    val secondaryAction: PartyStatusAction? = null,
    val tone: PartyStatusTone = PartyStatusTone.Neutral,
) {
    /** Two lines with the same identity update in place instead of re-running the debounce. */
    val identity: String get() = "${kind.name}:${people.firstOrNull()?.profileId.orEmpty()}"
}

/** The viewer's own party source work, as far as the status line needs it. */
enum class PartyRealizationPhase { None, Matching, Resolving, FallbackRequired, Failed }

/** A barrier park shorter than this is invisible; it is ordinary alignment. */
const val PartyStatusBarrierVisibleMs = 1_500L

/** A realtime blip shorter than this is invisible; the socket is usually back already. */
const val PartyStatusReconnectVisibleMs = 3_000L

data class PartyPlaybackStatusInputs(
    val inParty: Boolean,
    val isHost: Boolean,
    /** The host as named by the durable snapshot. Null outside a party. */
    val host: PartyStatusPerson? = null,
    val realization: PartyRealizationPhase = PartyRealizationPhase.None,
    /** True when the in-player handoff moved content (an episode), false for a source-only change. */
    val realizationChangesEpisode: Boolean = false,
    /** "S1E3", for the handoff failure sentence. Blank for a film. */
    val handoffEpisodeLabel: String = "",
    val positionUnreachable: Boolean = false,
    val waitingForHostSource: Boolean = false,
    val gate: PartyPlaybackGate = PartyPlaybackGate(allowPlayback = true, reason = PartyHoldReason.NONE),
    /**
     * This generation has already played. A guest's gate reads every non-playing party as
     * [PartyHoldReason.WAITING_FOR_HOST], so without this a mid-film pause - the guest's own included -
     * says the host has not started.
     */
    val partyStarted: Boolean = false,
    /** Members the host's start gate waits on, named. */
    val awaitingSource: List<PartyStatusPerson> = emptyList(),
    /** Other members a stall-guard hold is waiting on (host: `holdingProfiles`; guest: the tick's `hold`). */
    val stallHoldOthers: List<PartyStatusPerson> = emptyList(),
    /** This viewer is the one a stall hold is waiting on. */
    val selfHeld: Boolean = false,
    val hostBuffering: Boolean = false,
    val timelinePlaying: Boolean = false,
    /** How long a barrier park or pending seek has been holding this player, 0 when not. */
    val barrierHoldMs: Long = 0L,
    val incomingRequester: PartyStatusPerson? = null,
    val panelOpen: Boolean = false,
    /** How long realtime has been Connecting or Degraded, 0 when not. */
    val realtimeUnhealthyMs: Long = 0L,
    val capability: PartySyncCapability = PartySyncCapability.FullSync,
    /** The actor of the last accepted `pause` still in force, when it was not this viewer. */
    val pausedBy: PartyStatusPerson? = null,
    val outgoingRequestTarget: PartyStatusPerson? = null,
)

/** First match wins. See the table in `PLAN-social-watch-together-ux-pass.md` §5. */
fun projectPartyPlaybackStatus(inputs: PartyPlaybackStatusInputs): PartyStatusLine? = with(inputs) {
    val hostName = host?.name?.takeIf(String::isNotBlank) ?: "the host"
    val hostPeople = listOfNotNull(host)
    val guest = inParty && !isHost

    // 1. The viewer's own handoff has no source to follow the party with.
    if (inParty && realization in setOf(PartyRealizationPhase.FallbackRequired, PartyRealizationPhase.Failed)) {
        val what = if (handoffEpisodeLabel.isBlank()) "version" else "version of $handoffEpisodeLabel"
        return PartyStatusLine(
            PartyStatusKind.SourceNotFound,
            "Couldn't find ${possessive(hostName)} $what",
            hostPeople,
            action = PartyStatusAction.ChooseSource,
            tone = PartyStatusTone.Error,
        )
    }
    // 2.
    if (inParty && positionUnreachable) {
        return PartyStatusLine(
            PartyStatusKind.VersionTooShort,
            "Your version is shorter than ${possessive(hostName)}",
            hostPeople,
            action = PartyStatusAction.ChooseSource,
            tone = PartyStatusTone.Warning,
        )
    }
    // 3.
    if (inParty && realization in setOf(PartyRealizationPhase.Matching, PartyRealizationPhase.Resolving)) {
        return if (realizationChangesEpisode) {
            PartyStatusLine(PartyStatusKind.SwitchingEpisode, "Switching to ${possessive(hostName)} episode…", hostPeople, tone = PartyStatusTone.Waiting)
        } else {
            PartyStatusLine(PartyStatusKind.MatchingSource, "Matching ${possessive(hostName)} source…", hostPeople, tone = PartyStatusTone.Waiting)
        }
    }
    // 4. Only the others: the host choosing knows they are.
    if (guest && waitingForHostSource) {
        return PartyStatusLine(PartyStatusKind.HostChoosingSource, "$hostName is choosing a source", hostPeople, tone = PartyStatusTone.Waiting)
    }
    // 5.
    if (inParty && isHost && gate.reason == PartyHoldReason.WAITING_FOR_PARTICIPANTS) {
        val text = if (awaitingSource.size == 1) {
            "Waiting for ${awaitingSource[0].name} to find a source"
        } else if (awaitingSource.isEmpty()) {
            val n = gate.waitingOn.coerceAtLeast(1)
            if (n == 1) "Waiting for 1 person to find a source" else "Waiting for $n people to find a source"
        } else {
            "Waiting for ${peopleList(awaitingSource)}"
        }
        return PartyStatusLine(
            PartyStatusKind.WaitingForSources, text, awaitingSource,
            action = PartyStatusAction.StartAnyway, tone = PartyStatusTone.Waiting,
        )
    }
    // 6b before 6: the held member reads about themselves, even if someone else is held too.
    if (inParty && selfHeld) {
        return PartyStatusLine(PartyStatusKind.EveryoneWaitingOnYou, "Everyone's waiting while you buffer", tone = PartyStatusTone.Waiting)
    }
    // 6.
    if (inParty && stallHoldOthers.isNotEmpty()) {
        return PartyStatusLine(
            PartyStatusKind.WaitingForBuffering,
            "Waiting for ${peopleList(stallHoldOthers)} to buffer",
            stallHoldOthers,
            action = if (isHost) PartyStatusAction.DontWait else null,
            tone = PartyStatusTone.Waiting,
        )
    }
    // 7.
    if (guest && (hostBuffering || gate.reason == PartyHoldReason.HOST_BUFFERING)) {
        return PartyStatusLine(PartyStatusKind.HostBuffering, "$hostName is buffering", hostPeople, tone = PartyStatusTone.Waiting)
    }
    // 8. The durable row lags the timeline; a playing timeline already answered this. Once the party
    // has started, a paused party is a pause (row 13), not a start that has not happened.
    if (guest && gate.reason == PartyHoldReason.WAITING_FOR_HOST && !timelinePlaying && !partyStarted) {
        return PartyStatusLine(PartyStatusKind.WaitingForHostStart, "Waiting for $hostName to start", hostPeople, tone = PartyStatusTone.Waiting)
    }
    // 9.
    if (inParty && barrierHoldMs > PartyStatusBarrierVisibleMs) {
        return PartyStatusLine(PartyStatusKind.CatchingUp, "Catching up with the party…", tone = PartyStatusTone.Waiting)
    }
    // 10. Party or not: a host sharing presence under Ask can be asked before any party exists.
    if (incomingRequester != null && !panelOpen) {
        return PartyStatusLine(
            PartyStatusKind.IncomingJoinRequest,
            "${incomingRequester.name} wants to join",
            listOf(incomingRequester),
            action = PartyStatusAction.LetIn,
            secondaryAction = PartyStatusAction.Decline,
        )
    }
    // 12 outranks 11 when both are true: offline is the stronger and more useful statement.
    if (inParty && capability == PartySyncCapability.OfflineLocalPlayback) {
        return PartyStatusLine(PartyStatusKind.Offline, "Connection lost · playing on your own", tone = PartyStatusTone.Error)
    }
    // 11.
    if (inParty && realtimeUnhealthyMs > PartyStatusReconnectVisibleMs) {
        return PartyStatusLine(PartyStatusKind.Reconnecting, "Reconnecting to the party…", tone = PartyStatusTone.Warning)
    }
    // 13.
    if (inParty && pausedBy != null) {
        return PartyStatusLine(PartyStatusKind.PausedBy, "Paused by ${pausedBy.name}", listOf(pausedBy))
    }
    // 14.
    if (outgoingRequestTarget != null) {
        return PartyStatusLine(
            PartyStatusKind.OutgoingJoinRequest,
            "Asking ${outgoingRequestTarget.name} to join…",
            listOf(outgoingRequestTarget),
            action = PartyStatusAction.CancelOutgoing,
            tone = PartyStatusTone.Waiting,
        )
    }
    // 15. DurableFallback steady state: deliberately no pill.
    null
}

private fun possessive(name: String): String =
    if (name == "the host") "the host's" else if (name.endsWith("s")) "$name'" else "$name's"

/** "Ahmed", "Ahmed and Seraph", "Ahmed and 2 others". */
private fun peopleList(people: List<PartyStatusPerson>): String = when (people.size) {
    0 -> "someone"
    1 -> people[0].name
    2 -> "${people[0].name} and ${people[1].name}"
    else -> "${people[0].name} and ${people.size - 1} others"
}

/** A condition must hold this long before it shows. */
const val PartyStatusAppearDelayMs = 700L

/** Once shown, a line stays at least this long. */
const val PartyStatusMinVisibleMs = 1_200L

data class PartyStatusDebounceState(
    val shown: PartyStatusLine? = null,
    val shownAtMs: Long = 0L,
    val candidate: PartyStatusLine? = null,
    val candidateSinceMs: Long = 0L,
)

/**
 * Stops barrier parks and one-second rebuffers from flickering the pill.
 *
 * - A new condition shows only after it has held for [PartyStatusAppearDelayMs].
 * - A shown line stays for at least [PartyStatusMinVisibleMs], even if its condition clears.
 * - A line with the shown line's [PartyStatusLine.identity] replaces it immediately (a count or name
 *   changing), without restarting either clock.
 *
 * The consumer calls this on every projection change and on a timer while something is pending.
 */
fun debouncePartyStatus(
    state: PartyStatusDebounceState,
    projected: PartyStatusLine?,
    nowMs: Long,
): PartyStatusDebounceState {
    val shown = state.shown
    if (shown != null && projected != null && projected.identity == shown.identity) {
        return state.copy(shown = projected, candidate = null)
    }
    // Track how long the projected value has been the candidate.
    val candidateUnchanged = when {
        projected == null -> state.candidate == null
        else -> state.candidate?.identity == projected.identity
    }
    val candidateSince = if (candidateUnchanged) state.candidateSinceMs else nowMs
    val next = state.copy(candidate = projected, candidateSinceMs = candidateSince)
    val minVisibleOver = shown == null || nowMs - state.shownAtMs >= PartyStatusMinVisibleMs
    if (!minVisibleOver) return next
    return if (projected == null) {
        next.copy(shown = null)
    } else if (nowMs - candidateSince >= PartyStatusAppearDelayMs) {
        next.copy(shown = projected, shownAtMs = nowMs, candidate = null)
    } else {
        next
    }
}
