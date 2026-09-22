package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The last place the duplicate heuristic outranked a decision somebody had actually made.
 *
 * `partyHostTimelineContradicted` covers the host whose *own chain* moved it, and that path carries
 * a verdict past the guard. A host reaching into the sources panel and choosing another release had
 * no such verdict: the pick went through the ordinary duplicate test, `EquivalentMedia` is inside
 * `PartyExactMatchTiers`, and a deliberately chosen look-alike release was therefore refused as a
 * republication of the source the party was already on. The host's player changed; the party's did
 * not; and the UI reported a host source change that never happened.
 *
 * A host picking a source is not a heuristic's business. The party's timeline is whatever the host
 * is watching, so for an explicit host pick the duplicate test narrows to
 * [PartySameReleaseTiers] - the party's own release, a different URL for the same bytes - and
 * `EquivalentMedia` falls outside it.
 *
 * What deliberately did **not** change is covered here too, because the whole value of this is that
 * it is narrow: re-picking the party's own release still publishes nothing, a guest's pick still
 * faces the full test whatever flag it arrives with, the automatic paths are untouched, and the
 * one-shot generation guard sits outside all of it.
 */
class PartyHostManualSourcePickTest {
    private val torrentA = "0123456789abcdef0123456789abcdef01234567"
    private val media = PartySourceMedia(resolution = "2160p", releaseQuality = "bluray", codec = "hevc")

    private fun descriptor(
        originId: String = "org.example",
        infoHash: String? = torrentA,
        fileIndex: Int? = 0,
        release: String = "Film.Name.2026.2160p.BluRay.REMUX",
        media: PartySourceMedia = this.media,
    ) = PartySourceDescriptorV2(
        originKind = PartySourceOriginKind.addon,
        originId = originId,
        infoHash = infoHash,
        fileIndex = fileIndex,
        releaseFingerprint = partyReleaseFingerprint(release),
        media = media,
    )

    /** What the party is on. */
    private val partySource = descriptor()

    /** The same release the party is on, served by a different provider. A different URL, same bytes. */
    private val sameReleaseElsewhere = descriptor(originId = "org.other", infoHash = null, fileIndex = null)

    /** The same provider and the same release, re-resolved. */
    private val sameOriginRelease = descriptor(infoHash = null, fileIndex = null)

    /** Another release that looks alike: same resolution, same quality, same codec, unchecked timeline. */
    private val lookalike = descriptor(
        infoHash = null,
        fileIndex = null,
        release = "Film.Name.2026.2160p.BluRay.REMUX.Other",
    )

    /** A release nothing about it matches. */
    private val plainlyDifferent = descriptor(
        infoHash = null,
        fileIndex = null,
        release = "Film.Name.2026.1080p.WEB-DL",
        media = PartySourceMedia(resolution = "1080p", releaseQuality = "web", codec = "h264"),
    )

    private fun party(
        sourceGeneration: Int = 3,
        controlMode: WatchPartyControlMode = WatchPartyControlMode.host_only,
    ) = WatchPartyState(
        id = "party-1",
        hostProfileId = "host",
        status = WatchPartyStatus.playing,
        controlMode = controlMode,
        contentGeneration = 1,
        content = PartyContent("tt1", "movie", "tt1", "Film"),
        sourceFingerprint = partySource,
        sourceGeneration = sourceGeneration,
        positionMs = 0,
        durationMs = 7_094_186L,
        playbackSpeed = 1f,
        sequence = 1,
        stateUpdatedAt = "2026-09-20T10:00:00Z",
    )

    /** The sources panel: a person picked this, and only this call site says so. */
    private fun picks(
        picked: PartySourceDescriptorV2,
        profileId: String,
        partyState: WatchPartyState = party(),
        publishedSourceGeneration: Int? = null,
    ) = shouldPublishPartySourceChange(
        party = partyState,
        profileId = profileId,
        picked = picked,
        publishedSourceGeneration = publishedSourceGeneration,
        explicitHostSelection = true,
    )

    // ---- the fixtures are the tiers they claim to be -------------------------------------------

    @Test fun theFixturesCoverTheTiersThisTurnsOn() {
        assertEquals(PartySourceMatchTier.ExactTorrentFile, partySourceMatchTier(partySource, partySource))
        assertEquals(PartySourceMatchTier.ExactOriginRelease, partySourceMatchTier(partySource, sameOriginRelease))
        assertEquals(PartySourceMatchTier.ExactRelease, partySourceMatchTier(partySource, sameReleaseElsewhere))
        assertEquals(PartySourceMatchTier.EquivalentMedia, partySourceMatchTier(partySource, lookalike))
        assertEquals(PartySourceMatchTier.Fallback, partySourceMatchTier(partySource, plainlyDifferent))
    }

    // ---- what changed --------------------------------------------------------------------------

    /** The defect. */
    @Test fun aHostPickingALookalikeReleaseMovesTheParty() {
        assertFalse(
            shouldPublishPartySourceChange(
                party = party(),
                profileId = "host",
                picked = lookalike,
                publishedSourceGeneration = null,
            ),
            "without the pick's own verdict the duplicate heuristic still calls it a duplicate",
        )
        assertTrue(picks(lookalike, profileId = "host"), "the host chose another release, so the party did too")
    }

    /** Nothing about this depends on the control mode; the host is the host in both. */
    @Test fun aHostPickingALookalikeMovesThePartyInCollaborativeModeToo() {
        assertTrue(picks(lookalike, profileId = "host", partyState = party(controlMode = WatchPartyControlMode.collaborative)))
    }

    // ---- what deliberately did not ------------------------------------------------------------

    @Test fun aHostRepickingThePartysOwnSourcePublishesNothing() {
        assertFalse(picks(partySource, profileId = "host"), "re-picking what is playing is a change nobody made")
    }

    @Test fun aHostRepickingTheSameReleaseFromAnotherProviderPublishesNothing() {
        // A different URL for the same bytes. The party's timeline did not move, so neither does the
        // generation - this is the case `PartySameReleaseTiers` exists to name.
        assertFalse(picks(sameReleaseElsewhere, profileId = "host"))
    }

    @Test fun aHostRepickingTheSameOriginReleasePublishesNothing() {
        assertFalse(picks(sameOriginRelease, profileId = "host"))
    }

    /**
     * The flag is not authority; the host is.
     *
     * A guest in collaborative mode is permitted to change the party source, so it reaches the
     * duplicate test - and it must reach the *whole* test. A guest promoting a look-alike would be
     * asserting a timeline it does not define, which is the entire failure mode the invariant is
     * against, and it must not become reachable by way of the call site's flag.
     */
    @Test fun aGuestPickingALookalikePublishesNothingEvenWithTheFlagSet() {
        assertFalse(
            picks(
                lookalike,
                profileId = "guest",
                partyState = party(controlMode = WatchPartyControlMode.collaborative),
            ),
        )
    }

    /** The guest's ordinary authority is unchanged: a plainly different release still publishes. */
    @Test fun aGuestPickingAPlainlyDifferentReleaseStillPublishes() {
        assertTrue(
            picks(
                plainlyDifferent,
                profileId = "guest",
                partyState = party(controlMode = WatchPartyControlMode.collaborative),
            ),
        )
    }

    /** And a guest with no permission at all is refused before any of this is reached. */
    @Test fun aGuestUnderHostOnlyControlPublishesNothing() {
        assertFalse(picks(plainlyDifferent, profileId = "guest"))
    }

    /**
     * Automatic duplicate protection is not weakened.
     *
     * Only the sources panel passes `explicitHostSelection`. Every automatic path - a chain step, a
     * debrid re-resolution, a credential re-mint - keeps `PartyExactMatchTiers`, so a realization
     * that flaps onto a look-alike is still a duplicate and still says nothing to the party.
     */
    @Test fun anAutomaticHostPathKeepsTheFullDuplicateTest() {
        assertFalse(
            shouldPublishPartySourceChange(
                party = party(),
                profileId = "host",
                picked = lookalike,
                publishedSourceGeneration = null,
            ),
        )
    }

    /**
     * Exactly once, whichever way the publish was justified.
     *
     * The one-shot guard is above both doors, so a recomposition or a retry of the same host pick
     * cannot advance the generation a second time.
     */
    @Test fun theOneShotGuardOutranksTheHostsPick() {
        assertTrue(picks(lookalike, profileId = "host", publishedSourceGeneration = 2))
        assertFalse(
            picks(lookalike, profileId = "host", publishedSourceGeneration = 3),
            "this client has already advanced generation 3",
        )
    }
}
