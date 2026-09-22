package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PartyContentSwitchTest {

    private val descriptor = PartySourceDescriptorV2(
        originKind = PartySourceOriginKind.addon,
        originId = "org.example",
        releaseFingerprint = "sha256:" + "0123456789abcdef".repeat(4),
    )

    private fun party(
        videoId: String = "tt2:2:9",
        contentGeneration: Int = 4,
        sourceGeneration: Int = 7,
        status: WatchPartyStatus = WatchPartyStatus.playing,
        controlMode: WatchPartyControlMode = WatchPartyControlMode.collaborative,
        fingerprint: PartySourceDescriptorV2? = descriptor,
    ) = WatchPartyState(
        id = "party",
        hostProfileId = "host",
        status = status,
        controlMode = controlMode,
        contentGeneration = contentGeneration,
        sourceGeneration = sourceGeneration,
        content = PartyContent(
            contentId = "tt2",
            contentType = "series",
            videoId = videoId,
            title = "A Show",
            season = 2,
            episode = 9,
        ),
        sourceFingerprint = fingerprint,
        positionMs = 0,
        durationMs = 0,
        playbackSpeed = 1f,
        sequence = 1,
        stateUpdatedAt = "2026-09-11T00:00:00Z",
        authorityEpoch = 3,
        members = emptyList(),
    )

    // decidePartyContentHandoff --------------------------------------------------------------

    @Test fun thePartyMovingToANewEpisodeIsAdoptedWithBothGenerations() {
        val handoff = decidePartyContentHandoff(
            party = party(),
            localContentId = "tt2",
            localVideoId = "tt2:2:8",
            handledContentGeneration = 3,
        )
        val adopt = assertIs<PartyContentHandoff.Adopt>(handoff)
        assertEquals("tt2:2:9", adopt.content.videoId)
        assertEquals(4, adopt.contentGeneration)
        // ⚠ Both counters move on a content change, and the source handoff reads the other one.
        assertEquals(7, adopt.sourceGeneration)
        assertEquals(descriptor, adopt.target)
    }

    @Test fun aPlayerAlreadyOnThePartysEpisodeOwesNothing() {
        assertEquals(
            PartyContentHandoff.None,
            decidePartyContentHandoff(party(), "tt2", "tt2:2:9", handledContentGeneration = null),
        )
    }

    @Test fun oneAdvanceIsAdoptedOnce() {
        assertEquals(
            PartyContentHandoff.None,
            decidePartyContentHandoff(party(), "tt2", "tt2:2:8", handledContentGeneration = 4),
        )
    }

    @Test fun aMemberWatchingSomethingElseIsNotDraggedIntoTheParty() {
        // They walked out of the party's title and started another one. Adopting here would be
        // hijacking a playback they deliberately chose.
        assertEquals(
            PartyContentHandoff.None,
            decidePartyContentHandoff(party(), localContentId = "tt99", localVideoId = "tt99", handledContentGeneration = null),
        )
    }

    @Test fun anEndedPartyMovesNobody() {
        assertEquals(
            PartyContentHandoff.None,
            decidePartyContentHandoff(
                party(status = WatchPartyStatus.ended), "tt2", "tt2:2:8", handledContentGeneration = null,
            ),
        )
        assertEquals(
            PartyContentHandoff.None,
            decidePartyContentHandoff(null, "tt2", "tt2:2:8", handledContentGeneration = null),
        )
    }

    @Test fun aContentChangeWithNoSourceYetIsStillAnAdoption() {
        // `party_change_content_v2` accepts a null descriptor and parks the party in
        // `waiting_for_host_source`. The member has still moved episode and must say so.
        val adopt = assertIs<PartyContentHandoff.Adopt>(
            decidePartyContentHandoff(
                party(fingerprint = null), "tt2", "tt2:2:8", handledContentGeneration = null,
            ),
        )
        assertEquals(null, adopt.target)
    }

    // shouldPublishPartyContentChange --------------------------------------------------------

    @Test fun onlyTheHostMovesThePartysEpisode() {
        assertTrue(
            shouldPublishPartyContentChange(party(), "host", "tt2:2:10", publishedContentGeneration = null),
        )
        // ⚠ Collaborative widens who may change the *source*; it does not widen this. The server
        // says the same thing with `host_required`, so answering otherwise only buys a refusal.
        assertFalse(
            shouldPublishPartyContentChange(party(), "guest", "tt2:2:10", publishedContentGeneration = null),
        )
        assertFalse(
            shouldPublishPartyContentChange(
                party(controlMode = WatchPartyControlMode.host_only), "guest", "tt2:2:10", null,
            ),
        )
    }

    @Test fun thePartyIsNeverMovedToWhereItAlreadyIs() {
        // Republishing the current content would burn a generation for a change nobody made and
        // reset every member to `fetching` for it.
        assertFalse(
            shouldPublishPartyContentChange(party(), "host", "tt2:2:9", publishedContentGeneration = null),
        )
    }

    @Test fun oneAdvanceIsPublishedOnce() {
        assertFalse(
            shouldPublishPartyContentChange(party(), "host", "tt2:2:10", publishedContentGeneration = 4),
        )
        // A later generation re-arms it: the party has moved on since this host last published.
        assertTrue(
            shouldPublishPartyContentChange(
                party(contentGeneration = 5), "host", "tt2:2:10", publishedContentGeneration = 4,
            ),
        )
    }

    @Test fun aGuestPromotedToHostCanStillAdvanceTheEpisodeItAdopted() {
        // ⚠ Regression. The adopt path briefly latched `publishedContentGeneration` to the
        // generation it was adopting - which looks careful (this client did not publish that
        // content) and is wrong: the latch then equals `party.contentGeneration` for as long as the
        // party stays on the episode, and this reads it as "already published". A guest promoted to
        // host could never move the party again.
        //
        // Nothing needs to latch. Re-announcing the episode the party is already on is refused by
        // the videoId test, which is the check that actually means it.
        assertFalse(
            shouldPublishPartyContentChange(party(), "host", "tt2:2:9", publishedContentGeneration = null),
            "re-announcing the current episode is refused on its own merits",
        )
        assertTrue(
            shouldPublishPartyContentChange(party(), "host", "tt2:2:10", publishedContentGeneration = null),
            "and the newly promoted host can still advance",
        )
    }

    @Test fun nothingIsPublishedWithoutATargetOrAParty() {
        assertFalse(shouldPublishPartyContentChange(party(), "host", null, null))
        assertFalse(shouldPublishPartyContentChange(party(), "host", "", null))
        assertFalse(shouldPublishPartyContentChange(null, "host", "tt2:2:10", null))
        assertFalse(
            shouldPublishPartyContentChange(party(status = WatchPartyStatus.ended), "host", "tt2:2:10", null),
        )
    }

    // ownsNextEpisodeChoice ------------------------------------------------------------------

    @Test fun theHostKeepsItsCountdownAndAGuestDoesNot() {
        assertTrue(ownsNextEpisodeChoice(party(), "host", "tt2", "tt2:2:9"))
        assertFalse(ownsNextEpisodeChoice(party(), "guest", "tt2", "tt2:2:9"))
        // Collaborative does not hand the episode choice to guests either.
        assertFalse(
            ownsNextEpisodeChoice(
                party(controlMode = WatchPartyControlMode.collaborative), "guest", "tt2", "tt2:2:9",
            ),
        )
    }

    @Test fun ordinaryPlaybackAlwaysOwnsItsOwnNextEpisode() {
        assertTrue(ownsNextEpisodeChoice(null, "guest", "tt2", "tt2:2:9"))
        assertTrue(ownsNextEpisodeChoice(party(status = WatchPartyStatus.ended), "guest", "tt2", "tt2:2:9"))
        // A member watching something else with a party open in the background is having an
        // ordinary evening, and taking their Next episode button away would be wrong.
        assertTrue(ownsNextEpisodeChoice(party(), "guest", "tt99", "tt99"))
    }

    @Test fun aGuestBehindTheHostMidTransitionDoesNotRunItsOwnNextEpisode() {
        // ⚠ Regression, hardware Bug 2 (2026-09-15). This used to assert the *opposite*: that a
        // guest one episode behind the party owns its next episode, because `matchesPlayback` is
        // false for it. That window is exactly where the guest's own countdown ran - its previous
        // episode ending while the handoff was still matching - so a guest made its own content
        // decision and raced the host. A guest the handoff is carrying is not choosing.
        assertFalse(ownsNextEpisodeChoice(party(), "guest", "tt2", "tt2:2:8"))
        // And it is the same rule the handoff uses to decide the guest is being moved at all.
        assertIs<PartyContentHandoff.Adopt>(decidePartyContentHandoff(party(), "tt2", "tt2:2:8", null))
        // The host is never waiting on anybody for its own episode choice.
        assertTrue(ownsNextEpisodeChoice(party(), "host", "tt2", "tt2:2:8"))
    }

    @Test fun theHostIsNotDraggedBackWhileItsOwnAdvanceIsInFlight() {
        // The host switched to 2x10 locally and published from generation 4; the server has not
        // answered, so the party still says 2x9. Without the latch this read "go back to 2x9".
        assertEquals(
            PartyContentHandoff.None,
            decidePartyContentHandoff(
                party(), "tt2", "tt2:2:10",
                handledContentGeneration = null,
                pendingPublishedContentGeneration = 4,
            ),
        )
        // A refusal releases the latch, and the party is authoritative again.
        assertIs<PartyContentHandoff.Adopt>(
            decidePartyContentHandoff(party(), "tt2", "tt2:2:10", handledContentGeneration = null),
        )
        // A latch from an older generation says nothing about this one: somebody else moved it.
        assertIs<PartyContentHandoff.Adopt>(
            decidePartyContentHandoff(
                party(contentGeneration = 5), "tt2", "tt2:2:10",
                handledContentGeneration = null,
                pendingPublishedContentGeneration = 4,
            ),
        )
    }

    @Test fun oneHostAdvanceProducesExactlyOneContentGeneration() {
        // The whole Stage 7 cycle on the host, driven through the three decisions in order.
        var published: Int? = null
        var held = party(videoId = "tt2:2:9", contentGeneration = 4)

        // Next episode applies locally and publishes once.
        assertTrue(shouldPublishPartyContentChange(held, "host", "tt2:2:10", published))
        published = held.contentGeneration
        // Re-entry of the same apply (a debrid re-resolution) must not publish again.
        assertFalse(shouldPublishPartyContentChange(held, "host", "tt2:2:10", published))
        // Nor may the in-flight window pull the host back.
        assertEquals(
            PartyContentHandoff.None,
            decidePartyContentHandoff(held, "tt2", "tt2:2:10", null, pendingPublishedContentGeneration = published),
        )

        // The server accepts: one generation later, on the new episode.
        held = party(videoId = "tt2:2:10", contentGeneration = 5, sourceGeneration = 8)
        assertFalse(shouldPublishPartyContentChange(held, "host", "tt2:2:10", published))
        assertEquals(PartyContentHandoff.None, decidePartyContentHandoff(held, "tt2", "tt2:2:10", null, published))

        // And the guest, still on 2x9, is moved exactly once to generation 5.
        val adopt = assertIs<PartyContentHandoff.Adopt>(decidePartyContentHandoff(held, "tt2", "tt2:2:9", null))
        assertEquals(5, adopt.contentGeneration)
        assertEquals(PartyContentHandoff.None, decidePartyContentHandoff(held, "tt2", "tt2:2:9", handledContentGeneration = 5))
        assertFalse(ownsNextEpisodeChoice(held, "guest", "tt2", "tt2:2:9"))
    }
}
