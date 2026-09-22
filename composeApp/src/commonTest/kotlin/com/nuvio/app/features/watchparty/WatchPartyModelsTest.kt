package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun member(
    profileId: String,
    readyState: SourceResolutionState,
    connected: Boolean = true,
) = WatchPartyParticipant(
    profileId = profileId,
    role = if (profileId == "host") "host" else "participant",
    readyState = readyState,
    connected = connected,
    joinedAt = "2026-09-01T00:00:00Z",
)

private fun party(
    hostProfileId: String,
    status: WatchPartyStatus,
    members: List<WatchPartyParticipant>,
) = WatchPartyState(
    id = "party",
    hostProfileId = hostProfileId,
    status = status,
    controlMode = WatchPartyControlMode.host_only,
    contentGeneration = 0,
    content = PartyContent(contentId = "tt1", contentType = "series", videoId = "tt1:1:2", title = "Title"),
    positionMs = 0,
    durationMs = 0,
    playbackSpeed = 1f,
    sequence = 1,
    stateUpdatedAt = "2026-09-01T00:00:00Z",
    members = members,
)

class WatchPartyModelsTest {
    @Test fun durationCompatibilityUsesLargerTolerance() {
        assertTrue(arePartyDurationsCompatible(7_200_000, 7_300_000))
        assertFalse(arePartyDurationsCompatible(3_600_000, 3_800_000))
    }

    @Test fun hostWaitsForConnectedMembersWithoutASource() {
        val party = party(
            hostProfileId = "host",
            status = WatchPartyStatus.buffering,
            members = listOf(
                member("host", SourceResolutionState.ready),
                member("guest", SourceResolutionState.resolving),
            ),
        )
        val gate = partyPlaybackGate(party, viewerProfileId = "host", hostStartReleased = false)
        assertFalse(gate.allowPlayback)
        assertEquals(PartyHoldReason.WAITING_FOR_PARTICIPANTS, gate.reason)
        assertEquals(1, gate.waitingOn)
    }

    @Test fun hostStartsOnceEveryConnectedMemberIsReady() {
        val party = party(
            hostProfileId = "host",
            status = WatchPartyStatus.buffering,
            members = listOf(
                member("host", SourceResolutionState.joined),
                member("guest", SourceResolutionState.ready),
            ),
        )
        assertTrue(partyPlaybackGate(party, viewerProfileId = "host", hostStartReleased = false).allowPlayback)
    }

    @Test fun membersWhoCannotBeWaitedForDoNotBlockTheStart() {
        val party = party(
            hostProfileId = "host",
            status = WatchPartyStatus.buffering,
            members = listOf(
                member("host", SourceResolutionState.ready),
                member("gone", SourceResolutionState.joined, connected = false),
                member("failed", SourceResolutionState.failed),
                member("left", SourceResolutionState.left),
            ),
        )
        assertTrue(partyPlaybackGate(party, viewerProfileId = "host", hostStartReleased = false).allowPlayback)
    }

    @Test fun aReleasedGateDoesNotRestartTheHostAfterAPause() {
        val party = party(
            hostProfileId = "host",
            status = WatchPartyStatus.paused,
            members = listOf(
                member("host", SourceResolutionState.ready),
                member("guest", SourceResolutionState.resolving),
            ),
        )
        assertFalse(partyPlaybackGate(party, "host", hostStartReleased = false).allowPlayback)
        assertTrue(partyPlaybackGate(party, "host", hostStartReleased = true).allowPlayback)
    }

    @Test fun guestsDistinguishHostBufferingFromWaitingToStartAndEventuallyRecover() {
        val members = listOf(member("host", SourceResolutionState.ready), member("guest", SourceResolutionState.ready))
        val waiting = party(hostProfileId = "host", status = WatchPartyStatus.buffering, members = members)
        assertEquals(PartyHoldReason.HOST_BUFFERING, partyPlaybackGate(waiting, "guest", false).reason)
        assertTrue(partyPlaybackGate(waiting, "guest", false, hostBufferingReleased = true).allowPlayback)
        assertEquals(
            PartyHoldReason.WAITING_FOR_HOST,
            partyPlaybackGate(waiting.copy(status = WatchPartyStatus.paused), "guest", false).reason,
        )
        val playing = waiting.copy(status = WatchPartyStatus.playing)
        assertTrue(partyPlaybackGate(playing, "guest", false).allowPlayback)
    }

    @Test fun aPartyForAnotherVideoIsNotThisPlayback() {
        val party = party(hostProfileId = "host", status = WatchPartyStatus.playing, members = emptyList())
        assertTrue(party.matchesPlayback("tt1", "tt1:1:2"))
        assertFalse(party.matchesPlayback("tt1", "tt1:1:3"))
        assertFalse(party.copy(status = WatchPartyStatus.ended).matchesPlayback("tt1", "tt1:1:2"))
    }

    @Test fun infoHashMatchWins() {
        val host = SourceFingerprint(infoHash="ABC",fileIndex=1,releaseFingerprint="x")
        val same = SourceFingerprint(infoHash="abc",fileIndex=1,releaseFingerprint="different")
        assertEquals(10_000, sourceFingerprintMatchScore(host,same))
    }

    // ------------------------------------------------------------------- Watch Together holds
    //
    // The physically reproduced abandonment: a guest held at the readiness gate, then paused by the
    // host before its first frame settled, was declared stalled twelve seconds later and dumped
    // back to the source list. The startup watchdog could not tell "no progress" from "no progress
    // asked for", and this is the fact it was missing.

    @Test
    fun `a play outside a party is never held`() {
        val hold = resolvePartyStartupHold(
            inMatchingParty = false,
            gate = PartyPlaybackGate(allowPlayback = false, reason = PartyHoldReason.WAITING_FOR_HOST),
            holdingForBarrier = true,
            partyWantsPlayback = false,
        )
        assertEquals(PartyStartupHold.none, hold)
    }

    @Test
    fun `the readiness gate is a hold, and it says which gate`() {
        val hold = resolvePartyStartupHold(
            inMatchingParty = true,
            gate = PartyPlaybackGate(
                allowPlayback = false,
                reason = PartyHoldReason.WAITING_FOR_PARTICIPANTS,
                waitingOn = 2,
            ),
            holdingForBarrier = false,
            partyWantsPlayback = false,
        )
        assertEquals(true, hold.isHeld)
        assertEquals(PartyStartupHoldReason.GATE, hold.reason)
        assertEquals(PartyHoldReason.WAITING_FOR_PARTICIPANTS, hold.gateReason)
    }

    @Test
    fun `a barrier park is a hold`() {
        val hold = resolvePartyStartupHold(
            inMatchingParty = true,
            gate = PartyPlaybackGate(allowPlayback = true, reason = PartyHoldReason.NONE),
            holdingForBarrier = true,
            partyWantsPlayback = false,
        )
        assertEquals(true, hold.isHeld)
        assertEquals(PartyStartupHoldReason.BARRIER, hold.reason)
    }

    @Test
    fun `a party that has paused this player is holding it`() {
        // The 7257ms case. The gate has released, no barrier is running, and the player is still
        // deliberately motionless because the host pressed pause.
        val hold = resolvePartyStartupHold(
            inMatchingParty = true,
            gate = PartyPlaybackGate(allowPlayback = true, reason = PartyHoldReason.NONE),
            holdingForBarrier = false,
            partyWantsPlayback = false,
        )
        assertEquals(true, hold.isHeld)
        assertEquals(PartyStartupHoldReason.PAUSED, hold.reason)
    }

    @Test
    fun `a party that has asked for playback is not holding anything`() {
        // The invariant that keeps the watchdog useful: told to play and not playing is exactly the
        // source it exists to catch, and being in a party must not excuse it.
        val hold = resolvePartyStartupHold(
            inMatchingParty = true,
            gate = PartyPlaybackGate(allowPlayback = true, reason = PartyHoldReason.NONE),
            holdingForBarrier = false,
            partyWantsPlayback = true,
        )
        assertEquals(PartyStartupHold.none, hold)
    }

    @Test
    fun `the two live planes are distinct topics`() {
        assertEquals("party:abc", watchPartyAuthorityTopic("abc"))
        assertEquals("party_peer:abc", watchPartyPeerTopic("abc"))
        assertNotEquals(watchPartyAuthorityTopic("abc"), watchPartyPeerTopic("abc"))
    }
}
