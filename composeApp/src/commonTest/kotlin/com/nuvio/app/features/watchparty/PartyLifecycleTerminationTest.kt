package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hardware Bugs 1 and 5 (2026-09-15): a live party orphaned by Escape, and a host's End party that
 * never reached its guests. Both are lifecycle invariants, and both are pure here.
 */
class PartyLifecycleTerminationTest {
    private val generation = PartyGenerationKey("party", 1, 2, 3)
    private val playback = ActivePlaybackContext(
        attachmentId = "attachment", contentId = "tt1", videoId = "tt1",
        descriptor = PartySourceDescriptorV2(
            originKind = PartySourceOriginKind.embedded, originId = "nuvio",
            releaseFingerprint = partyReleaseFingerprint("Movie 2026 1080p"),
        ),
        positionMs = 10, durationMs = 100, playbackSpeed = 1f,
    )

    private fun party(
        id: String = "party",
        status: WatchPartyStatus = WatchPartyStatus.playing,
        host: String = "host",
    ) = WatchPartyState(
        id = id,
        hostProfileId = host,
        status = status,
        controlMode = WatchPartyControlMode.host_only,
        contentGeneration = 1,
        sourceGeneration = 2,
        content = PartyContent(contentId = "tt1", contentType = "movie", videoId = "tt1", title = "Movie"),
        sourceFingerprint = null,
        positionMs = 0,
        durationMs = 0,
        playbackSpeed = 1f,
        sequence = 1,
        stateUpdatedAt = "2026-09-15T00:00:00Z",
        authorityEpoch = 3,
        members = emptyList(),
    )

    private val membershipRefusal =
        "PostgrestRestException: party_membership_required (42501)"

    // Bug 5 - detecting the end a guest is never sent ------------------------------------------

    @Test fun aMembershipRefusalForTheHeldLivePartyIsConfirmedNotIgnored() {
        assertEquals(
            PartyRpcFailureVerdict.ConfirmMembership,
            classifyPartyRpcFailure(party(), failedPartyId = "party", errorMessage = membershipRefusal),
        )
        // A refresh through `call()` does not name the party; the held one is what it was about.
        assertEquals(
            PartyRpcFailureVerdict.ConfirmMembership,
            classifyPartyRpcFailure(party(), failedPartyId = null, errorMessage = membershipRefusal),
        )
    }

    @Test fun ordinaryFailuresNeverEndAParty() {
        // A network drop, a stale generation, a timeout: the poll is the retry, and the party stays.
        listOf(
            "Unable to resolve host",
            "stale_party_generation (40001)",
            "Timed out waiting for 5000 ms",
            null,
        ).forEach { message ->
            assertEquals(
                PartyRpcFailureVerdict.Transient,
                classifyPartyRpcFailure(party(), "party", message),
                "message=$message",
            )
        }
    }

    @Test fun onlyTheHeldLivePartyCanBeConcluded() {
        assertEquals(PartyRpcFailureVerdict.Transient, classifyPartyRpcFailure(null, "party", membershipRefusal))
        assertEquals(
            PartyRpcFailureVerdict.Transient,
            classifyPartyRpcFailure(party(status = WatchPartyStatus.ended), "party", membershipRefusal),
        )
        // A late refusal for a party this client has already moved on from says nothing about this one.
        assertEquals(
            PartyRpcFailureVerdict.Transient,
            classifyPartyRpcFailure(party(id = "new-party"), "old-party", membershipRefusal),
        )
    }

    @Test fun theProbeConcludesOnlyWhenThisMemberIsReallyOut() {
        assertTrue(membershipProbeConcludesParty("party", active = null), "no live party for this profile")
        assertTrue(membershipProbeConcludesParty("party", party(status = WatchPartyStatus.ended)))
        assertTrue(membershipProbeConcludesParty("party", party(id = "another")), "joined elsewhere")
        // Still a member: the refusal was not what it looked like, and nothing may be torn down.
        assertFalse(membershipProbeConcludesParty("party", party()))
    }

    @Test fun aConcludedPartyIsTheEndedStateEveryConsumerAlreadyReads() {
        val concluded = party().concludedLocally()
        assertEquals(WatchPartyStatus.ended, concluded.status)
        assertFalse(concluded.matchesPlayback("tt1", "tt1"), "the player stops treating it as a party")
        assertEquals(PartyContentHandoff.None, decidePartyContentHandoff(concluded, "tt1", "tt0", null))
        assertTrue(ownsNextEpisodeChoice(concluded, "guest", "tt1", "tt1"), "local playback owns itself again")
    }

    // Bug 5 - the session reacts on whichever route the guest is on ---------------------------

    @Test fun aGuestWatchingWhenTheHostEndsGetsTheEndOfPartyChoice() {
        val active = reducePartySession(PartySessionState(), PartySessionEvent.PlayerAttached(playback, generation))
        val ended = observePartySnapshot(active, party(status = WatchPartyStatus.ended), selfProfileId = "guest")
        assertEquals(PartyClientPhase.Ended, ended.phase)
        assertTrue(ended.guestPostEndChoice)
        assertFalse(ended.membershipRetained)
        assertEquals(playback, ended.playback, "the guest's own playback is not destroyed")
    }

    @Test fun aGuestInTheLobbyOrOnTheSourceRouteIsReleasedWithNoChoiceToAnswer() {
        // ⚠ Before: an ended snapshot was read as an ordinary generation advance, so this stayed in
        // Lobby / MatchingHostSource for a party that no longer existed.
        val lobby = reducePartySession(PartySessionState(), PartySessionEvent.Restored(generation))
        val released = observePartySnapshot(lobby, party(status = WatchPartyStatus.ended), selfProfileId = "guest")
        assertEquals(PartyClientPhase.None, released.phase)
        assertFalse(released.membershipRetained)
        assertFalse(released.guestPostEndChoice)
        assertNull(released.generation)

        val matching = reducePartySession(lobby, PartySessionEvent.MatchStarted(generation))
        assertEquals(
            PartyClientPhase.None,
            observePartySnapshot(matching, party(status = WatchPartyStatus.ended), "guest").phase,
        )
    }

    @Test fun theHostEndingItsOwnPartyIsNeverOfferedAChoice() {
        val active = reducePartySession(PartySessionState(), PartySessionEvent.PlayerAttached(playback, generation))
        val ended = observePartySnapshot(active, party(status = WatchPartyStatus.ended), selfProfileId = "host")
        assertEquals(PartyClientPhase.None, ended.phase)
        assertFalse(ended.guestPostEndChoice)
        assertEquals(playback, ended.playback)
    }

    @Test fun theEndIsReducedOnceAndCannotResurfaceOverALaterPlayer() {
        val active = reducePartySession(PartySessionState(), PartySessionEvent.PlayerAttached(playback, generation))
        val ended = observePartySnapshot(active, party(status = WatchPartyStatus.ended), "guest")
        val continued = PartySessionState(playback = ended.playback)
        // The ended party is still held in the repository; seeing it again changes nothing.
        assertEquals(continued, observePartySnapshot(continued, party(status = WatchPartyStatus.ended), "guest"))

        // A different player opening while an old choice was never answered does not inherit it.
        val other = playback.copy(attachmentId = "later-player")
        val later = reducePartySession(ended, PartySessionEvent.PlayerAttached(other, null))
        assertFalse(later.guestPostEndChoice)
        // And the player the choice belonged to leaving takes the choice with it.
        assertFalse(reducePartySession(ended, PartySessionEvent.PlayerAttachmentLost("attachment")).guestPostEndChoice)
        // The same player re-registering keeps it: the question is still on its screen.
        assertTrue(reducePartySession(ended, PartySessionEvent.PlayerAttached(playback, null)).guestPostEndChoice)
    }

    @Test fun anEndedSnapshotForAPartyThisSessionNeverHeldIsIgnored() {
        val idle = PartySessionState()
        assertEquals(idle, observePartySnapshot(idle, party(status = WatchPartyStatus.ended), "guest"))
        val otherParty = reducePartySession(PartySessionState(), PartySessionEvent.Restored(generation.copy(partyId = "mine")))
        assertEquals(otherParty, observePartySnapshot(otherParty, party(status = WatchPartyStatus.ended), "guest"))
    }

    @Test fun liveSnapshotsAreObservedExactlyAsBefore() {
        val restored = observePartySnapshot(PartySessionState(), party(), "guest")
        assertEquals(PartyClientPhase.Lobby, restored.phase)
        val active = reducePartySession(PartySessionState(), PartySessionEvent.PlayerAttached(playback, generation))
        assertEquals(PartyClientPhase.ActivePlayer, observePartySnapshot(active, party(), "guest").phase)
        assertEquals(PartyClientPhase.None, observePartySnapshot(restored, null, "guest").phase)
    }

    // Bug 1 - route exits --------------------------------------------------------------------

    @Test fun aSystemBackOverALivePartyAsksToLeaveItInsteadOfDismissingTheLobby() {
        assertEquals(LobbyBackAction.RequestDeparture, decideLobbyBack("party", party()))
        // An invite-code lobby is bound to whatever it joined.
        assertEquals(LobbyBackAction.RequestDeparture, decideLobbyBack(null, party()))
    }

    @Test fun aLobbyWithNoLivePartyToOrphanClosesNormally() {
        assertEquals(LobbyBackAction.Close, decideLobbyBack("party", null), "still joining")
        assertEquals(LobbyBackAction.Close, decideLobbyBack("party", party(status = WatchPartyStatus.ended)))
        assertEquals(LobbyBackAction.Close, decideLobbyBack("party", party(id = "another")))
    }

    @Test fun theLobbyStaysExactlyAsLongAsItsParty() {
        // Not yet bound: an absent party is the join in flight, never a reason to close.
        assertNull(lobbyCloseReason(boundPartyId = null, held = null))
        assertNull(lobbyCloseReason(boundPartyId = null, held = party(status = WatchPartyStatus.ended)))
        // Bound and live: stay.
        assertNull(lobbyCloseReason("party", party()))
        // This member left, transferred or ended it: close quietly.
        assertEquals(LobbyCloseReason.Departed, lobbyCloseReason("party", null))
        assertEquals(LobbyCloseReason.Departed, lobbyCloseReason("party", party(id = "another")))
        // Somebody else ended it: close, and say so.
        assertEquals(LobbyCloseReason.PartyEnded, lobbyCloseReason("party", party(status = WatchPartyStatus.ended)))
    }

    @Test fun aSourceRouteStopsTheMomentItsPartyIsNoLongerLive() {
        assertTrue(partyLaunchStillLive("party", party()))
        assertFalse(partyLaunchStillLive("party", party(status = WatchPartyStatus.ended)))
        assertFalse(partyLaunchStillLive("party", null))
        assertFalse(partyLaunchStillLive("party", party(id = "another")))
    }

    @Test fun aPartyStartedInThePlayerGetsItsLobbyOnTheWayOut() {
        // Hardware (2026-09-15): the host promoted from the player panel, pressed Escape, landed home.
        assertEquals("party", lobbyOwedOnPlayerExit(party(), playerMatchesParty = true, lobbyPartyIdsOnStack = emptyList()))
        // A lobby already below the player is where back lands; a second one is never pushed.
        assertNull(lobbyOwedOnPlayerExit(party(), true, listOf("party")))
        assertNull(lobbyOwedOnPlayerExit(party(), true, listOf(null)), "an invite-code lobby")
        assertEquals("party", lobbyOwedOnPlayerExit(party(), true, listOf("another")))
        // Nothing live, or a player showing something else: nothing is owed.
        assertNull(lobbyOwedOnPlayerExit(null, false, emptyList()))
        assertNull(lobbyOwedOnPlayerExit(party(status = WatchPartyStatus.ended), true, emptyList()))
        assertNull(lobbyOwedOnPlayerExit(party(), playerMatchesParty = false, lobbyPartyIdsOnStack = emptyList()))
    }
}
