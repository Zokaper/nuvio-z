package com.nuvio.app.features.watchparty

import com.nuvio.app.features.downloads.SourceFacts
import com.nuvio.app.features.playback.PlaybackSelectionContext
import com.nuvio.app.features.playback.PlaybackSourceCandidate
import com.nuvio.app.features.playback.RequestedContent
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The strict party match, exercised without a composition.
 *
 * These rules used to live in a `remember` block inside the source route, which meant they could
 * only be checked by launching the app with two clients. The decision they feed is the difference
 * between a member joining the host's release and a member being told the source is unavailable.
 */
class PartySourceRealizationTest {
    private val hostRelease = "Stage.14.2026.1080p.WEB-DL.mkv"

    @Test fun anUnsettledCatalogueDecidesNothing() {
        assertEquals(
            PartyRealizationDecision.Wait,
            decidePartyRealization(catalogueSettled = false, tiered = null),
        )
        // Even with a match in hand: a catalogue that is still filling can produce a better one.
        assertEquals(
            PartyRealizationDecision.Wait,
            decidePartyRealization(
                catalogueSettled = false,
                tiered = tierPartyPlaybackSources(
                    host = descriptor(hostRelease),
                    candidates = listOf(candidate(hostRelease)),
                    normalOrder = emptyList(),
                    selection = selection(),
                ),
            ),
        )
    }

    @Test fun aSettledCatalogueWithTheHostReleaseResolvesIt() {
        val match = candidate(hostRelease)
        val decision = decidePartyRealization(
            catalogueSettled = true,
            tiered = tierPartyPlaybackSources(
                host = descriptor(hostRelease),
                candidates = listOf(candidate("Something.Else.2026.720p.mkv"), match),
                normalOrder = emptyList(),
                selection = selection(),
            ),
        )
        val resolve = assertIs<PartyRealizationDecision.Resolve>(decision)
        assertTrue(resolve.candidates.all { it.stream.name == match.stream.name })
    }

    @Test fun aSettledCatalogueWithoutTheHostReleaseRequiresFallbackRatherThanOpeningOne() {
        val decision = decidePartyRealization(
            catalogueSettled = true,
            tiered = tierPartyPlaybackSources(
                host = descriptor(hostRelease),
                candidates = listOf(candidate("Something.Else.2026.720p.mkv")),
                normalOrder = emptyList(),
                selection = selection(),
            ),
        )
        assertEquals(PartyRealizationDecision.FallbackRequired, decision)
    }

    @Test fun anEmptyCatalogueRequiresFallback() {
        assertEquals(
            PartyRealizationDecision.FallbackRequired,
            decidePartyRealization(
                catalogueSettled = true,
                tiered = tierPartyPlaybackSources(
                    host = descriptor(hostRelease),
                    candidates = emptyList(),
                    normalOrder = emptyList(),
                    selection = selection(),
                ),
            ),
        )
    }

    /** The identity guard is the reason a party never opens the wrong episode of the right show. */
    @Test fun aCandidateTheContentGuardRejectsIsNeverPartyMatched() {
        val wrongEpisode = "Stage.S01E09.2026.1080p.WEB-DL.mkv"
        val tiered = tierPartyPlaybackSources(
            host = descriptor(wrongEpisode),
            candidates = listOf(candidate(wrongEpisode)),
            normalOrder = emptyList(),
            selection = selection(
                identity = RequestedContent(season = 1, episode = 4, year = null),
            ),
        )
        assertEquals(
            PartyRealizationDecision.FallbackRequired,
            decidePartyRealization(catalogueSettled = true, tiered = tiered),
        )
    }

    @Test fun readinessIsReportedFromTheWorkAndCarriesItsSourceGeneration() {
        val key = com.nuvio.app.features.player.PartyPlayerLaunchKey(
            partyId = "party",
            contentGeneration = 1,
            sourceGeneration = 6,
            descriptor = descriptor(hostRelease),
        )
        assertEquals(null, partyReadinessReport(PartySourceRealizationState.Unresolved))
        assertEquals(
            SourceResolutionState.fetching to 6,
            partyReadinessReport(PartySourceRealizationState.Matching(key)),
        )
        assertEquals(
            SourceResolutionState.resolving to 6,
            partyReadinessReport(PartySourceRealizationState.Resolving(key)),
        )
        assertEquals(
            SourceResolutionState.choosing_fallback to 6,
            partyReadinessReport(PartySourceRealizationState.FallbackRequired(key)),
        )
        assertEquals(
            SourceResolutionState.failed to 6,
            partyReadinessReport(PartySourceRealizationState.Failed(key, "debrid_resolve_failed")),
        )
        // Not `ready`: only the player has the duration and match evidence that answer carries.
        assertEquals(
            SourceResolutionState.source_ready to 6,
            partyReadinessReport(PartySourceRealizationState.Ready(key, realizationId = 1)),
        )
    }

    private fun selection(identity: RequestedContent? = null) = PlaybackSelectionContext(
        isEpisode = identity != null,
        allowTorrentSources = true,
        identity = identity,
    )

    private fun descriptor(release: String) = PartySourceDescriptorV2(
        originKind = PartySourceOriginKind.addon,
        originId = "org.example",
        releaseFingerprint = partyReleaseFingerprint(release),
    )

    private fun candidate(release: String) = PlaybackSourceCandidate(
        stream = StreamItem(
            name = release,
            url = "https://local.invalid/${release}",
            addonName = "Example",
            addonId = "org.example",
            partyOriginKind = "addon",
            partyOriginId = "org.example",
        ),
        facts = SourceFacts(filename = release),
    )
}
