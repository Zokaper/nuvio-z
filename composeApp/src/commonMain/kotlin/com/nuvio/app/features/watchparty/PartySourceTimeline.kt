package com.nuvio.app.features.watchparty

/**
 * Whether a source change moved this client off the party's timeline, and what that costs.
 *
 * **Different URLs are fine. Different timelines are not.** A party is a shared timestamp, so the
 * only question a source change has to answer is whether that timestamp still means the same frame
 * everywhere. A credential re-mint, a renewed debrid link, the same torrent file through another
 * provider: all of those are new URLs for bytes the party has already agreed on, and none of them is
 * anybody's business but the client that made them. A different release is the opposite - same
 * title, different frames at the same millisecond - and there is no local answer to it.
 *
 * Which way that falls depends entirely on **who** changed, because the host defines the timeline:
 *
 *  - The **host** cannot be wrong about the timeline; it *is* the timeline. So a host that has to
 *    leave its release takes the party with it: hold, advance the authoritative source, let everyone
 *    re-realize against the new descriptor, and resume through the readiness barrier.
 *  - A **guest** may not move the party at all. It may follow locally, but only against evidence
 *    that its new source still carries the host's timeline - and if it has none, it stays in the
 *    party and says so rather than reporting a readiness it cannot honour.
 *
 * Release identity is the evidence; duration is a contradiction check on top of it, never a proof on
 * its own. Two cuts of the same film can run to the same minute and still differ by a distributor
 * logo at the head, which is a permanent offset no correction policy can close.
 */

/**
 * The tiers that are the party's own release: a different URL for the same bytes.
 *
 * Narrower than [PartyExactMatchTiers] on purpose, and the difference is [PartySourceMatchTier.EquivalentMedia]:
 * that tier means *another release* whose media attributes look alike - same resolution, same codec,
 * a plausible substitute - which is exactly a timeline nobody has checked. It is a reasonable thing
 * for a guest to fall back to with a duration check behind it, and never a reason for the host to
 * keep a release change to itself.
 */
val PartySameReleaseTiers = setOf(
    PartySourceMatchTier.ExactTorrentFile,
    PartySourceMatchTier.ExactOriginRelease,
    PartySourceMatchTier.ExactRelease,
)

/** What a client owes the party after its own source changed underneath it. */
enum class PartySourceTimelineDecision {
    /**
     * Nothing. The timeline did not change, so neither does the party.
     *
     * A host re-minting a credential, and a guest that landed back on the party's own release.
     */
    KeepLocal,

    /**
     * The host is on a different release, so the party's authoritative source has to follow it.
     *
     * Only ever returned for the host. Everything downstream of it already exists: advancing the
     * source generation closes the start gate, guests re-realize against the new descriptor, and the
     * readiness barrier resumes everybody together.
     */
    AdvancePartySource,

    /**
     * A guest cannot show what the party is watching, and must not report itself ready.
     *
     * Not a failure of the party and not a reason to move it: this member stays in, says it needs a
     * source, and the host's existing escapes - "Don't wait", the start gate's ceiling - decide how
     * long the rest waits.
     */
    NeedsPartyMatch,
}

/**
 * What a client should do about its own source now that it is no longer the one the party recorded.
 *
 * [tier] is measured against the party's authoritative descriptor with [partySourceMatchTier], so
 * this function never sees a URL and cannot be fooled by one changing.
 *
 * [hostDurationMs] and [localDurationMs] are the contradiction check. A duration that disagrees is
 * evidence *against* a match that release identity claimed; a duration that agrees is no evidence
 * for one, which is why it can only ever subtract here. Either being unknown leaves the identity
 * verdict standing - see [arePartyDurationsCompatible], which answers `true` for an unknown side.
 */
fun partySourceTimelineDecision(
    isHost: Boolean,
    tier: PartySourceMatchTier,
    hostDurationMs: Long? = null,
    localDurationMs: Long? = null,
): PartySourceTimelineDecision {
    val durationsAgree = hostDurationMs == null || localDurationMs == null ||
        arePartyDurationsCompatible(hostDurationMs, localDurationMs)
    val sameRelease = tier in PartySameReleaseTiers
    if (isHost) {
        // The host's own duration is the party's duration, so a disagreement here is not about
        // matching anybody: it is the timeline itself having changed length, which the party has to
        // be told about even when the release identity says the file is the same one.
        return if (sameRelease && durationsAgree) {
            PartySourceTimelineDecision.KeepLocal
        } else {
            PartySourceTimelineDecision.AdvancePartySource
        }
    }
    if (!durationsAgree) return PartySourceTimelineDecision.NeedsPartyMatch
    return when {
        sameRelease -> PartySourceTimelineDecision.KeepLocal
        // Another release that looks like the party's, with nothing contradicting it. Weaker
        // evidence than an identity match and deliberately still allowed: a guest whose provider
        // cannot serve the host's exact file otherwise has no way into the party at all.
        tier == PartySourceMatchTier.EquivalentMedia -> PartySourceTimelineDecision.KeepLocal
        // `Fallback` is the tier for "something else that plays this title", and `None` is
        // contradicted identity. Neither is evidence of a shared timeline, and a member that reports
        // ready on one is a member watching a different film at the party's timestamps.
        else -> PartySourceTimelineDecision.NeedsPartyMatch
    }
}

/**
 * The readiness a member may report for a source at [tier], which is the decision in the party's own
 * vocabulary.
 *
 * [SourceResolutionState.choosing_fallback] rather than [SourceResolutionState.failed]: the member
 * has a player, it is in the party, and the thing it needs is a different source - which is what
 * that state has always meant, and what keeps it inside `PartyBlockingReadyStates` so the host's
 * gate waits for it exactly as it waits for anyone still resolving.
 */
fun partySourceReadyState(
    decision: PartySourceTimelineDecision,
): SourceResolutionState = when (decision) {
    PartySourceTimelineDecision.NeedsPartyMatch -> SourceResolutionState.choosing_fallback
    else -> SourceResolutionState.ready
}
