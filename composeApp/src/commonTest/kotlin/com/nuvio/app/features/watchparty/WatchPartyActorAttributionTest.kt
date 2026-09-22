package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Who the party is told moved it.
 *
 * The 2026-09-10 two-client run is the whole reason these exist: a guest paused, every member
 * paused correctly, and nothing on any screen said whose doing it was. Host-ness must never be
 * consulted here - assuming it is exactly the bug.
 */
class WatchPartyActorAttributionTest {
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

    /** The reported case, exactly: a guest acts and the guest is named. */
    @Test fun aGuestWhoPausesIsNamedRatherThanTheHost() {
        assertEquals(
            "Big Z paused",
            partyActorNotice(
                kind = PartyCommandKind.pause,
                actorProfileId = "guest",
                viewerProfileId = "host",
                actorName = party(member("host", "Rayo"), member("guest", "Big Z"))
                    .actorDisplayName("guest"),
            ),
        )
    }

    @Test fun everyTransportKindHasAVerbAndASeekHasTwo() {
        fun notice(kind: PartyCommandKind, backwards: Boolean = false) = partyActorNotice(
            kind = kind,
            actorProfileId = "guest",
            viewerProfileId = "host",
            actorName = "Big Z",
            seekingBackwards = backwards,
        )
        assertEquals("Big Z resumed", notice(PartyCommandKind.play))
        assertEquals("Big Z paused", notice(PartyCommandKind.pause))
        assertEquals("Big Z skipped ahead", notice(PartyCommandKind.seek))
        assertEquals("Big Z skipped back", notice(PartyCommandKind.seek, backwards = true))
        assertEquals("Big Z changed the speed", notice(PartyCommandKind.speed))
    }

    /** The person who pressed the button does not need telling, on any of their presses. */
    @Test fun theViewersOwnCommandIsNeverAnnouncedBackToThem() {
        PartyCommandKind.entries.forEach { kind ->
            assertNull(
                partyActorNotice(
                    kind = kind,
                    actorProfileId = "guest",
                    viewerProfileId = "guest",
                    actorName = "Big Z",
                ),
            )
        }
    }

    /** Better silent than announcing a profile id at somebody. */
    @Test fun anActorTheSnapshotCannotNameSaysNothing() {
        val party = party(member("host", "Rayo"), member("guest", "Big Z"))
        assertEquals("", party.actorDisplayName("departed"))
        assertNull(
            partyActorNotice(
                kind = PartyCommandKind.pause,
                actorProfileId = "departed",
                viewerProfileId = "host",
                actorName = party.actorDisplayName("departed"),
            ),
        )
    }

    /** A member with no display name is still a person, and their handle is a name. */
    @Test fun aHandleStandsInForAMissingDisplayName() {
        val party = party(member("host", "Rayo"), member("guest", handle = "bigz"))
        assertEquals("@bigz", party.actorDisplayName("guest"))
    }

    /**
     * The name is the one *other* members read, so it is never "You".
     *
     * [WatchPartyParticipant.displayName] answers "You" for the viewer, which is right on a member
     * list and wrong in a sentence about somebody else - and this function's caller only ever
     * builds sentences about somebody else.
     */
    @Test fun theActorNameIsNeverTheViewersOwnPronoun() {
        val party = party(member("host", "Rayo"), member("guest", "Big Z"))
        assertEquals("Rayo", party.actorDisplayName("host"))
        assertEquals("Big Z", party.actorDisplayName("guest"))
    }

    /** Host-ness decides nothing here: the host is announced by name like anybody else. */
    @Test fun theHostIsAttributedByNameAndNotByRole() {
        assertEquals(
            "Rayo skipped back",
            partyActorNotice(
                kind = PartyCommandKind.seek,
                actorProfileId = "host",
                viewerProfileId = "guest",
                actorName = party(member("host", "Rayo"), member("guest", "Big Z"))
                    .actorDisplayName("host"),
                seekingBackwards = true,
            ),
        )
    }
}
