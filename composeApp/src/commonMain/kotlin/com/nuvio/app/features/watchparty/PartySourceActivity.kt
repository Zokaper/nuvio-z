package com.nuvio.app.features.watchparty

/**
 * What this client is doing about a source, in the terms a person would use about it.
 *
 * Every one of these used to arrive on screen as "Matching source…" or "Loading source", and the
 * differences between them are exactly what somebody staring at a stopped film needs: *what
 * happened, whose source changed, and what is this client doing about it*. A guest whose host
 * switched release, a guest whose own stream died, a guest that cannot match the party at all and a
 * guest that is simply buffering are four different situations with four different answers - and
 * only one of them is anybody's fault.
 *
 * Pure and import-free beyond the party models, so the whole table can be executed by the pure
 * suites rather than inspected on a device. The copy lives here with the states, because a state
 * whose wording is decided somewhere else is a state that drifts from its own meaning.
 */
enum class PartySourceActivity {
    /** Nothing to say: not in a party, or in one and simply watching. */
    None,

    /** Joining: this client is matching the host's source for the first time. Nothing has failed. */
    InitialPartyMatch,

    /** The party's authoritative source changed and this client is looking for a match. */
    HostSourceChangedMatching,

    /** The party's authoritative source changed, a match was found, and it is being opened. */
    HostSourceChangedResolving,

    /** This client is the host, its source failed, and it is moving the party to a replacement. */
    HostSwitchingSource,

    /** This client's own source failed and it is trying another realization of the same release. */
    LocalSourceRetry,

    /** Playing an alternate that is timeline-compatible with the party's release. */
    LocalCompatibleFallback,

    /** Nothing available matches the party's timeline; the user has to choose. */
    PartySourceUnmatched,

    /** An automatic candidate failed and the chain is trying the next one. */
    FailedTryingNext,

    /** Parked while the party waits for everybody to be able to play - a start or a seek. */
    WaitingForPartyReadiness,

    /** This client can play and is waiting for the rest of the party. */
    ReadyWaitingForOthers,

    /** Ordinary buffering, and deliberately nothing else: no source wording on a full stop. */
    Buffering,
}

/**
 * The two lines a [PartySourceActivity] is worth on screen.
 *
 * A headline that says what happened and a detail that says what is being done about it, because
 * one line has to choose between them and both have been the missing one. [tone] is the existing
 * status vocabulary, so nothing downstream has to learn a second one.
 */
data class PartySourceMessage(
    val headline: String,
    val detail: String? = null,
    val tone: PartyStatusTone = PartyStatusTone.Waiting,
)

/**
 * Everything the activity is derived from, all of it already known to the player.
 *
 * Deliberately not a new signal on the wire: a member's own state already distinguishes these, and
 * the one fact that comes from elsewhere - that the party's source moved - is the source generation
 * the durable row has carried from the beginning.
 */
data class PartySourceActivityInputs(
    val inParty: Boolean = false,
    val isHost: Boolean = false,
    /**
     * This content generation has played at least once, which is what separates a join from a change.
     *
     * Without it every mid-film source change reads as "joining playback" and every join reads as
     * something having gone wrong - the two halves of the same missing fact.
     */
    val partyStarted: Boolean = false,
    val realization: PartyRealizationPhase = PartyRealizationPhase.None,
    /** The party's authoritative source moved and this client has not caught up with it yet. */
    val adoptingNewPartySource: Boolean = false,
    /** This client is the host and has just moved the party to a different release. */
    val hostAdvancingPartySource: Boolean = false,
    /** The local failure chain's attempt number: 1 is the first source, 2+ means one has failed. */
    val localAttempt: Int = 1,
    /** Nothing this client can reach carries the party's timeline. */
    val needsPartyMatch: Boolean = false,
    /** Playing something that is not the party's own release but is compatible with it. */
    val usingCompatibleAlternate: Boolean = false,
    /** A start or seek barrier is holding this client while the party gets ready. */
    val waitingForPartyReadiness: Boolean = false,
    /** The local engine has media open. */
    val mediaReady: Boolean = false,
    /** The local engine has run dry, in the ordinary mid-film way. */
    val buffering: Boolean = false,
)

/**
 * Which activity to show, most specific first.
 *
 * Order is the whole design here. "Buffering" is true of a client that is also matching a source it
 * has never opened, and saying so is how every one of these collapsed into the same sentence: a
 * client with no source yet is not buffering, it is finding something to buffer.
 */
fun partySourceActivity(inputs: PartySourceActivityInputs): PartySourceActivity {
    if (!inputs.inParty) return PartySourceActivity.None
    // A member that cannot match outranks everything: it is the only state here that needs a person
    // to do something, and it must never be dressed up as progress.
    if (inputs.needsPartyMatch || inputs.realization == PartyRealizationPhase.FallbackRequired) {
        return PartySourceActivity.PartySourceUnmatched
    }
    if (inputs.hostAdvancingPartySource) return PartySourceActivity.HostSwitchingSource
    if (inputs.adoptingNewPartySource) {
        return when (inputs.realization) {
            PartyRealizationPhase.Resolving -> PartySourceActivity.HostSourceChangedResolving
            else -> PartySourceActivity.HostSourceChangedMatching
        }
    }
    if (inputs.realization == PartyRealizationPhase.Failed) return PartySourceActivity.FailedTryingNext
    if (inputs.realization == PartyRealizationPhase.Matching || inputs.realization == PartyRealizationPhase.Resolving) {
        // Realizing without the party having moved is the join. Saying "host source changed" here
        // would blame a change on somebody who has not made one.
        if (!inputs.partyStarted) return PartySourceActivity.InitialPartyMatch
        return PartySourceActivity.HostSourceChangedMatching
    }
    // A second attempt on this client's own chain, with the party's source unmoved: this member's
    // stream died and it is finding another way to the same release.
    if (inputs.localAttempt > 1 && !inputs.mediaReady) return PartySourceActivity.LocalSourceRetry
    if (inputs.waitingForPartyReadiness) return PartySourceActivity.WaitingForPartyReadiness
    if (inputs.usingCompatibleAlternate && !inputs.mediaReady) return PartySourceActivity.LocalCompatibleFallback
    if (inputs.buffering) return PartySourceActivity.Buffering
    return PartySourceActivity.None
}

/**
 * The copy for one activity.
 *
 * [hostName] is used only where the sentence is about somebody else's decision, and never to name a
 * technical tier: "Using a compatible source" is all a person needs to know about `EquivalentMedia`.
 */
fun partySourceMessage(
    activity: PartySourceActivity,
    isHost: Boolean = false,
    hostName: String? = null,
): PartySourceMessage? {
    val host = hostName?.takeIf { it.isNotBlank() }
    return when (activity) {
        PartySourceActivity.None -> null

        PartySourceActivity.InitialPartyMatch -> PartySourceMessage(
            headline = "Joining playback",
            detail = if (host != null) "Matching $host's source…" else "Matching the host's source…",
        )

        PartySourceActivity.HostSourceChangedMatching -> PartySourceMessage(
            headline = if (isHost) "Source changed" else "Host source changed",
            detail = "Finding a compatible source…",
        )

        PartySourceActivity.HostSourceChangedResolving -> PartySourceMessage(
            headline = if (isHost) "Source changed" else "Host source changed",
            detail = "Resolving the new source…",
        )

        PartySourceActivity.HostSwitchingSource -> PartySourceMessage(
            headline = "Switching source",
            detail = "The current source failed. Finding a replacement…",
            tone = PartyStatusTone.Warning,
        )

        PartySourceActivity.LocalSourceRetry -> if (isHost) {
            PartySourceMessage(
                headline = "Source failed",
                detail = "Trying another connection…",
                tone = PartyStatusTone.Warning,
            )
        } else {
            PartySourceMessage(
                headline = "Your source stopped working",
                detail = "Trying another compatible source…",
                tone = PartyStatusTone.Warning,
            )
        }

        PartySourceActivity.LocalCompatibleFallback -> PartySourceMessage(
            headline = "Using a compatible source",
            detail = "Preparing playback…",
        )

        PartySourceActivity.PartySourceUnmatched -> PartySourceMessage(
            headline = "Couldn't match the party source",
            detail = "Choose another source to continue with the group.",
            tone = PartyStatusTone.Warning,
        )

        PartySourceActivity.FailedTryingNext -> PartySourceMessage(
            headline = "Source didn't work",
            detail = "Trying the next option…",
            tone = PartyStatusTone.Warning,
        )

        PartySourceActivity.WaitingForPartyReadiness -> PartySourceMessage(
            headline = "Waiting for everyone…",
        )

        PartySourceActivity.ReadyWaitingForOthers -> PartySourceMessage(
            headline = "Ready — waiting for others",
        )

        // No source wording on an ordinary stall, which is the other half of not muddying these:
        // a film that stops for a second is not a source problem and must not be made to look like
        // one.
        PartySourceActivity.Buffering -> PartySourceMessage(headline = "Buffering…")
    }
}

/**
 * What the host sees beside a member that cannot match the party's source.
 *
 * The member list already says "Choosing alternate" for this state, which describes a mechanism
 * rather than a situation. A host deciding whether to wait needs the situation.
 */
fun partyMemberSourceLabel(readyState: SourceResolutionState): String? = when (readyState) {
    SourceResolutionState.choosing_fallback -> "Needs source"
    else -> null
}
