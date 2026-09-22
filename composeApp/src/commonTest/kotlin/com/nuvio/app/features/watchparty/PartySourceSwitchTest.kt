package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Active source switching, decided without a player.
 *
 * The two halves are deliberately separate functions: one member deciding to move the party, and
 * every member deciding what that move obliges them to do. A test that could only exercise them
 * together would need two desktops.
 */
class PartySourceSwitchTest {
    private val current = descriptor("Stage.Six.2026.1080p.WEB-DL.mkv")
    private val replacement = descriptor("Stage.Six.2026.2160p.REMUX.mkv")

    @Test fun aNewSourceGenerationIsAdoptedInPlace() {
        val handoff = decidePartySourceHandoff(
            party = party(source = replacement, sourceGeneration = 5),
            localDescriptor = current,
            handledSourceGeneration = 4,
        )
        val adopt = assertIs<PartySourceHandoff.Adopt>(handoff)
        assertEquals(replacement, adopt.target)
        assertEquals(5, adopt.sourceGeneration)
    }

    @Test fun aPlayerAlreadyOnThePartySourceHasNothingToAdopt() {
        assertEquals(
            PartySourceHandoff.None,
            decidePartySourceHandoff(
                party = party(source = current, sourceGeneration = 5),
                localDescriptor = current,
                handledSourceGeneration = null,
            ),
        )
    }

    /** Failure is remembered, so a source this client cannot realize is not retried forever. */
    @Test fun aGenerationThatHasAlreadyBeenActedOnIsNotAdoptedAgain() {
        assertEquals(
            PartySourceHandoff.None,
            decidePartySourceHandoff(
                party = party(source = replacement, sourceGeneration = 5),
                localDescriptor = current,
                handledSourceGeneration = 5,
            ),
        )
    }

    @Test fun aPartyWithNoAuthoritativeSourceObligesNothing() {
        assertEquals(
            PartySourceHandoff.None,
            decidePartySourceHandoff(
                party = party(source = null, sourceGeneration = 0),
                localDescriptor = current,
                handledSourceGeneration = null,
            ),
        )
        assertEquals(
            PartySourceHandoff.None,
            decidePartySourceHandoff(party = null, localDescriptor = current, handledSourceGeneration = null),
        )
    }

    @Test fun theHostMayMoveThePartyToADifferentSource() {
        assertTrue(
            shouldPublishPartySourceChange(
                party = party(source = current, sourceGeneration = 5),
                profileId = "host",
                picked = replacement,
                publishedSourceGeneration = null,
            ),
        )
    }

    @Test fun aGuestMayOnlyMoveACollaborativeParty() {
        assertTrue(
            shouldPublishPartySourceChange(
                party = party(source = current, sourceGeneration = 5),
                profileId = "guest",
                picked = replacement,
                publishedSourceGeneration = null,
            ),
        )
        assertFalse(
            shouldPublishPartySourceChange(
                party = party(source = current, sourceGeneration = 5, controlMode = WatchPartyControlMode.host_only),
                profileId = "guest",
                picked = replacement,
                publishedSourceGeneration = null,
            ),
        )
    }

    /** Republishing the source the party is already on would advance a generation for no change. */
    @Test fun pickingTheSourceThePartyIsAlreadyOnPublishesNothing() {
        assertFalse(
            shouldPublishPartySourceChange(
                party = party(source = current, sourceGeneration = 5),
                profileId = "host",
                picked = current,
                publishedSourceGeneration = null,
            ),
        )
    }

    /** One pick is one transition: a retry of the same pick must not advance the generation twice. */
    @Test fun aPickIsPublishedOnlyOncePerGeneration() {
        assertFalse(
            shouldPublishPartySourceChange(
                party = party(source = current, sourceGeneration = 5),
                profileId = "host",
                picked = replacement,
                publishedSourceGeneration = 5,
            ),
        )
    }

    @Test fun somebodyWhoIsNotInThePartyChangesNothingButTheirOwnPlayback() {
        assertFalse(
            shouldPublishPartySourceChange(
                party = party(source = current, sourceGeneration = 5),
                profileId = null,
                picked = replacement,
                publishedSourceGeneration = null,
            ),
        )
    }

    private fun descriptor(release: String) = PartySourceDescriptorV2(
        originKind = PartySourceOriginKind.addon,
        originId = "org.example",
        releaseFingerprint = partyReleaseFingerprint(release),
    )

    private fun party(
        source: PartySourceDescriptorV2?,
        sourceGeneration: Int,
        controlMode: WatchPartyControlMode = WatchPartyControlMode.collaborative,
    ) = WatchPartyState(
        id = "party",
        hostProfileId = "host",
        status = WatchPartyStatus.playing,
        controlMode = controlMode,
        contentGeneration = 1,
        sourceGeneration = sourceGeneration,
        stage = WatchPartyStage.playing,
        content = PartyContent("tt6", "movie", "tt6", "Stage Six"),
        sourceFingerprint = source,
        positionMs = 0,
        durationMs = 7_200_000,
        playbackSpeed = 1f,
        sequence = 1,
        stateUpdatedAt = "2026-09-09T00:00:00Z",
    )
}
