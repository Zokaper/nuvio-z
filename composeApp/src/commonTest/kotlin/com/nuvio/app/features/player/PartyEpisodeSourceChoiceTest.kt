package com.nuvio.app.features.player

import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.watchparty.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PartyEpisodeSourceChoiceTest {
    private val descriptor = PartySourceDescriptorV2(
        originKind = PartySourceOriginKind.addon, originId = "org.example",
        releaseFingerprint = partyReleaseFingerprint("Punisher.S01E09.host.mkv"),
    )
    private val party = WatchPartyState(
        id = "party", hostProfileId = "host", status = WatchPartyStatus.playing,
        controlMode = WatchPartyControlMode.host_only, contentGeneration = 2, sourceGeneration = 3,
        content = PartyContent("tt5675620", "series", "tt5675620:1:9", "The Punisher", season = 1, episode = 9),
        sourceFingerprint = descriptor, positionMs = 0, durationMs = 3_000_000,
        playbackSpeed = 1f, sequence = 1, stateUpdatedAt = "2026-10-28T00:00:00Z",
    )

    @Test fun manualRecoveryTargetsThePartysNewEpisodeAndKeepsItsCanonicalVideoId() {
        val known = MetaVideo(id = "local-catalogue-episode", title = "Episode Nine", season = 1, episode = 9)
        val episode = assertNotNull(partyEpisodeForSourceChoice(party, "tt5675620", "tt5675620:1:8", listOf(known)))
        assertEquals("tt5675620:1:9", episode.id)
        assertEquals("Episode Nine", episode.title)
        val alternate = descriptor.copy(releaseFingerprint = partyReleaseFingerprint("Punisher.S01E09.guest.mkv"))
        val choice = assertNotNull(partyLocalSourceChoiceForLaunch(party, "tt5675620", episode.id, alternate, true))
        assertTrue(choice.appliesTo(party, alternate))
        assertEquals(party.partySourceKey(), partyRealizationCompletedByLaunch(
            party, "tt5675620", episode.id, alternate, userSelectedSource = true,
        ))
        assertNull(partyRealizationCompletedByLaunch(
            party, "tt5675620", "tt5675620:1:8", alternate, userSelectedSource = true,
        ))
    }

    @Test fun currentEpisodeEndedPartyAndUnrelatedTitleDoNotRedirectTheSourcePanel() {
        assertNull(partyEpisodeForSourceChoice(party, "tt5675620", party.content.videoId, emptyList()))
        assertNull(partyEpisodeForSourceChoice(party.copy(status = WatchPartyStatus.ended), "tt5675620", "old", emptyList()))
        assertNull(partyEpisodeForSourceChoice(party, "another-title", "old", emptyList()))
    }
}
