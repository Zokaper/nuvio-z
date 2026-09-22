package com.nuvio.app.features.watchparty

import com.nuvio.app.features.downloads.SourceRanking
import com.nuvio.app.features.playback.ContentIdentityGuard
import com.nuvio.app.features.playback.LanguageStrictness
import com.nuvio.app.features.playback.PlaybackSelectionContext
import com.nuvio.app.features.playback.PlaybackSourceCandidate
import com.nuvio.app.features.playback.PlaybackSourceSelector

/**
 * Strict party matching over a loaded source catalogue, as a pure function.
 *
 * This used to be a `remember` block inside `StreamDestination`, which meant the rule that decides
 * whether a member may play the host's release could only be read - and could only be tested - by
 * standing up a composition. It depends on nothing but its arguments, so it lives here with the
 * tier algorithm it feeds, and the route supplies the catalogue and the settings it already reads.
 *
 * [normalOrder] is the ordinary ranked order for the same candidates. It contributes only a
 * tie-break *within* a tier; it can never lift a fallback above an exact match.
 */
fun tierPartyPlaybackSources(
    host: PartySourceDescriptorV2,
    candidates: List<PlaybackSourceCandidate>,
    normalOrder: List<PlaybackSourceCandidate>,
    selection: PlaybackSelectionContext,
): TieredPartySources<PlaybackSourceCandidate> = tierPartySourceCandidates(
    host = host,
    candidates = candidates.mapNotNull { candidate ->
        val descriptor = candidate.stream.toPartySourceDescriptor(candidate.facts)
            ?: return@mapNotNull null
        val identity = selection.identity
        val contentMatches = identity == null || ContentIdentityGuard.evaluate(
            releaseName = candidate.facts.filename ?: candidate.stream.name,
            requestedSeason = identity.season,
            requestedEpisode = identity.episode,
            requestedYear = identity.year,
        ) == null
        val normalIndex = normalOrder.indexOfFirst { it.stream === candidate.stream }
        PartySourceCandidate(
            value = candidate,
            descriptor = descriptor,
            normalRank = if (normalIndex >= 0) normalOrder.size - normalIndex else 0,
            contentMatches = contentMatches,
            protocolSafe = PlaybackSourceSelector.isPlaybackProtocolEligible(
                candidate,
                selection.allowTorrentSources,
            ),
            // Stream groups are built only from the currently enabled addon/plugin set.
            addonAllowed = true,
            languageWatchable = selection.languageStrictness != LanguageStrictness.REQUIRE ||
                selection.preferredAudioLanguage.isNullOrBlank() ||
                SourceRanking.isLanguageWatchable(candidate.facts, selection.rankingPreferences),
        )
    },
)

/** What the realizer should do with a catalogue for the currently authoritative party source. */
sealed interface PartyRealizationDecision {
    /** The catalogue has not settled. Nothing is decided and no state is terminal yet. */
    data object Wait : PartyRealizationDecision

    /**
     * Nothing in the catalogue is the host's release.
     *
     * A fallback is only ever an offer: the member picks an alternate by hand, or the host changes
     * the source. This decision never carries a candidate to open.
     */
    data object FallbackRequired : PartyRealizationDecision

    /** The strict matcher found the host's release; these candidates may be resolved, in order. */
    data class Resolve(val candidates: List<PlaybackSourceCandidate>) : PartyRealizationDecision
}

/**
 * The one place that turns "the catalogue is loaded" into a party realization outcome.
 *
 * Kept separate from [tierPartyPlaybackSources] so the settle gate and the match rule can be
 * exercised independently: a route that decides too early is the failure that produces a spurious
 * "host source unavailable" while addons are still answering.
 */
fun decidePartyRealization(
    catalogueSettled: Boolean,
    tiered: TieredPartySources<PlaybackSourceCandidate>?,
): PartyRealizationDecision = when {
    !catalogueSettled -> PartyRealizationDecision.Wait
    tiered == null ||
        tiered.tier == PartySourceMatchTier.None ||
        tiered.candidates.isEmpty() -> PartyRealizationDecision.FallbackRequired
    else -> PartyRealizationDecision.Resolve(tiered.candidates.map { it.value })
}

/**
 * The readiness a member is genuinely in, derived from the work the realizer is actually doing.
 *
 * Readiness used to be published only from the player: a member matching or resolving the host's
 * release showed the party whatever they last were - usually `joined` - for the whole preparation,
 * which is exactly the window the host's wait gate is looking at. Null means "this state says
 * nothing about readiness"; the source generation travels with it so the backend can reject a
 * report that belongs to a generation the party has already left.
 */
/** What this member last told the party about its readiness, for [realizerReadinessMayPublish]. */
data class PublishedPartyReadiness(
    val partyId: String,
    val state: SourceResolutionState,
    val sourceGeneration: Int?,
)

/**
 * Whether a report derived from the realizer may go out over what the member last published.
 *
 * ⚠ **A realization becoming Ready must never walk a playing member back to `source_ready`.** The
 * realizer learns of the launch from the player route, and the player publishes `ready` itself once
 * its media is loaded - two writers, one row, and no order between them. The physical run's host log
 * has the regression on the record: `ready`, then `source_ready`, then `ready` again inside half a
 * second. `source_ready` is still a waiting state for the host's start gate, so on a joining guest
 * the same flap closes a gate that had just opened - one more pause and play for everyone watching.
 * Every other realizer report stays allowed: a member who goes back to the source list really is
 * matching again.
 */
fun realizerReadinessMayPublish(
    report: Pair<SourceResolutionState, Int>,
    partyId: String,
    lastPublished: PublishedPartyReadiness?,
): Boolean {
    if (report.first != SourceResolutionState.source_ready) return true
    val last = lastPublished ?: return true
    if (last.partyId != partyId || last.sourceGeneration != report.second) return true
    return last.state != SourceResolutionState.ready && last.state != SourceResolutionState.buffering
}

fun partyReadinessReport(state: PartySourceRealizationState): Pair<SourceResolutionState, Int>? =
    when (state) {
        PartySourceRealizationState.Unresolved -> null
        is PartySourceRealizationState.Matching ->
            SourceResolutionState.fetching to state.key.sourceGeneration
        is PartySourceRealizationState.Resolving ->
            SourceResolutionState.resolving to state.key.sourceGeneration
        is PartySourceRealizationState.FallbackRequired ->
            SourceResolutionState.choosing_fallback to state.key.sourceGeneration
        is PartySourceRealizationState.Failed ->
            SourceResolutionState.failed to state.key.sourceGeneration
        // The player owns `ready`, with the duration and match evidence only it has.
        is PartySourceRealizationState.Ready ->
            SourceResolutionState.source_ready to state.key.sourceGeneration
    }
