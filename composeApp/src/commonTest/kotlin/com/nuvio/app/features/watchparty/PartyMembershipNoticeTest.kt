package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Guests arriving and leaving, as the members already watching read it. Hotfix 2026-09-17. */
class PartyMembershipNoticeTest {
    private fun member(
        id: String,
        displayName: String = "",
        handle: String? = null,
    ) = WatchPartyParticipant(
        profileId = id,
        role = if (id == "host") "host" else "member",
        readyState = SourceResolutionState.ready,
        connected = true,
        clientLocation = WatchPartyClientLocation.player,
        joinedAt = "2026-09-10T00:00:00Z",
        profile = PartyParticipantProfile(displayName = displayName, handle = handle),
    )

    private fun party(vararg members: WatchPartyParticipant) = WatchPartyState(
        id = "party",
        hostProfileId = "host",
        status = WatchPartyStatus.playing,
        controlMode = WatchPartyControlMode.collaborative,
        contentGeneration = 1,
        sourceGeneration = 1,
        content = PartyContent("tt1", "movie", "tt1", "Movie"),
        positionMs = 0,
        durationMs = 100_000,
        playbackSpeed = 1f,
        sequence = 1,
        stateUpdatedAt = "2026-09-10T00:00:00Z",
        authorityEpoch = 1,
        members = members.toList(),
    )

    private val host = member("host", "Rayo")
    private val ana = member("ana", "Ana")
    private val ben = member("ben", "Ben")

    @Test fun aGuestJoiningIsAnnounced() {
        assertEquals("Ana joined the party", partyMembershipNotice(party(host), party(host, ana), "host"))
    }

    @Test fun aGuestLeavingIsAnnounced() {
        assertEquals("Ana left the party", partyMembershipNotice(party(host, ana), party(host), "host"))
    }

    @Test fun aMemberMarkedLeftCountsAsGone() {
        val gone = ana.copy(readyState = SourceResolutionState.left, connected = false)
        assertEquals("Ana left the party", partyMembershipNotice(party(host, ana), party(host, gone), "host"))
    }

    @Test fun joinsAndLeavesInOneSnapshotShareOneNotice() {
        assertEquals(
            "Ben joined the party · Ana left the party",
            partyMembershipNotice(party(host, ana), party(host, ben), "host"),
        )
    }

    @Test fun twoArrivalsAreOneSentence() {
        assertEquals("Ana and Ben joined the party", partyMembershipNotice(party(host), party(host, ana, ben), "host"))
    }

    @Test fun theViewerIsNeverAnnouncedToItself() {
        assertNull(partyMembershipNotice(party(host), party(host, ana), "ana"))
    }

    @Test fun aConnectionBlipIsNotALeave() {
        assertNull(partyMembershipNotice(party(host, ana), party(host, ana.copy(connected = false)), "host"))
    }

    @Test fun enteringAPartySaysNothing() {
        assertNull(partyMembershipNotice(null, party(host, ana, ben), "ben"))
    }
}
