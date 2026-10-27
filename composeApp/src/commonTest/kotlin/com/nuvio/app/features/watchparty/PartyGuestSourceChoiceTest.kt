package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PartyGuestSourceChoiceTest {
    private val host = descriptor("Punisher.S01E08.2160p.HEVC-GROUP.mkv")
    private val local = descriptor("Punisher.S01E08.1080p.AVC-OTHER.mkv")
    private val party = WatchPartyState(
        id = "party", hostProfileId = "host", status = WatchPartyStatus.playing,
        controlMode = WatchPartyControlMode.collaborative, contentGeneration = 2, sourceGeneration = 3,
        content = PartyContent("tt5675620", "series", "tt5675620:1:8", "The Punisher", season = 1, episode = 8),
        sourceFingerprint = host, positionMs = 40_000L, durationMs = 3_000_000L,
        playbackSpeed = 1f, sequence = 1, stateUpdatedAt = "2026-10-28T00:00:00Z",
    )

    @Test fun explicitChoiceCompletesEvenIfTheMatcherIsStillResolving() {
        val key = party.partySourceKey()!!
        assertEquals(key, partyRealizationCompletedByLaunch(
            party, party.content.contentId, party.content.videoId, local,
            userSelectedSource = true,
        ))
        assertNull(partyRealizationCompletedByLaunch(
            party, party.content.contentId, party.content.videoId, local,
        ))
    }

    @Test fun explicitAlternateIsReadyEvenWithDifferentTimingAndNeverMovesTheParty() {
        val decision = partySourceTimelineDecision(
            isHost = false, tier = PartySourceMatchTier.Fallback,
            hostDurationMs = 3_000_000, localDurationMs = 2_500_000, userSelectedSource = true,
        )
        assertEquals(PartySourceTimelineDecision.KeepLocal, decision)
        assertEquals(SourceResolutionState.ready, partySourceReadyState(decision))
        // Automatic fallback still requires evidence. The host still defines the shared timeline.
        assertEquals(PartySourceTimelineDecision.NeedsPartyMatch,
            partySourceTimelineDecision(false, PartySourceMatchTier.Fallback))
        assertEquals(PartySourceTimelineDecision.AdvancePartySource,
            partySourceTimelineDecision(true, PartySourceMatchTier.Fallback, userSelectedSource = true))
    }

    @Test fun choiceSurvivesPollsButIsRetiredByAnotherEpisodeSourcePartyOrLocalSource() {
        val choice = partyLocalSourceChoiceForLaunch(party, party.content.contentId, party.content.videoId, local, true)!!
        assertTrue(choice.appliesTo(party.copy(sequence = 50), local))
        assertEquals(PartySourceHandoff.None, decidePartySourceHandoff(party, local, null, choice))
        for (changed in listOf(
            party.copy(contentGeneration = 3), party.copy(sourceGeneration = 4), party.copy(id = "other"),
        )) {
            assertFalse(choice.appliesTo(changed, local))
            assertIs<PartySourceHandoff.Adopt>(decidePartySourceHandoff(changed, local, null, choice))
        }
        assertFalse(choice.appliesTo(party, host))
        assertNull(partyLocalSourceChoiceForLaunch(party, "other", "other", local, true))
        assertNull(partyLocalSourceChoiceForLaunch(party, party.content.contentId, party.content.videoId, local, false))
    }

    @Test fun resolvingTheChosenLinkKeepsTheChoiceButLateUnrelatedResolvesCannotClaimIt() {
        val choice = partyLocalSourceChoiceForLaunch(party, party.content.contentId, party.content.videoId, local, true)!!
        val enriched = local.copy(media = PartySourceMedia(sizeBytes = 1_234_567))
        assertTrue(choice.resolvedFor(party, local, enriched).appliesTo(party, enriched))
        assertEquals(choice, choice.resolvedFor(party.copy(sourceGeneration = 4), local, enriched))
        assertEquals(choice, choice.resolvedFor(party, host, enriched))
    }

    private fun descriptor(filename: String) = PartySourceDescriptorV2(
        originKind = PartySourceOriginKind.addon, originId = "org.example",
        releaseFingerprint = partyReleaseFingerprint(filename),
    )
}
