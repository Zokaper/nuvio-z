package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PartyAwayRecoveryTest {
    private val guest = WatchPartyParticipant("guest", "member", SourceResolutionState.ready,
        connected = true, joinedAt = "2026-10-01T00:00:00Z")
    private fun peer(at: Long, starved: Boolean = false, away: Boolean = false) =
        PartyPeerTelemetry(WatchPartyStatus.paused, at, starved, at, away)
    private fun PartyAwayRecovery.step(at: Long, absent: Boolean, p: PartyPeerTelemetry? = null,
        member: WatchPartyParticipant = guest, enabled: Boolean = true) =
        advance(if (absent) listOf("guest") else emptyList(), listOf(member),
            p?.let { mapOf("guest" to it) }.orEmpty(), at, enabled)

    @Test fun homeAndLockKeepTheSameLeaseThroughAllNonterminalReadinessStates() {
        val now = kotlin.time.Instant.parse("2026-10-01T05:21:30Z").toEpochMilliseconds()
        for (state in SourceResolutionState.entries.filter { it !in listOf(SourceResolutionState.failed, SourceResolutionState.left) }) {
            for (connected in listOf(true, false)) {
                val member = guest.copy(connected = connected, readyState = state, awaySince = "2026-10-01T05:21:29Z")
                val party = WatchPartyState(id = "party", hostProfileId = "host", status = WatchPartyStatus.playing,
                    controlMode = WatchPartyControlMode.host_only, contentGeneration = 1, positionMs = 0,
                    durationMs = 100_000, playbackSpeed = 1f, sequence = 1, stateUpdatedAt = "2026-10-01T05:21:29Z",
                    content = PartyContent(contentType = "movie", contentId = "id", videoId = "id", title = "Test"), members = listOf(member))
                assertEquals(listOf("guest"), partyAwayHoldMembers(party, emptySet(), "host", true, now), "$state/$connected")
            }
        }
    }

    @Test fun skippedPauseRetriesTheSameAwayWhenPlaybackBecomesPossible() {
        assertEquals(PartyAutoHoldAction.Retry, partyAutoHoldAction(listOf("guest"), emptyList(), false, false))
        assertEquals(PartyAutoHoldAction.Hold, partyAutoHoldAction(listOf("guest"), emptyList(), true, false))
    }

    @Test fun lockDuringStallKeepsAwayHoldAfterStallRecovers() {
        assertEquals(PartyAutoHoldAction.Hold, partyAutoHoldAction(listOf("guest"), emptyList(), false, true))
        // This is the stall guard clearing while the Away guard still owns the pause.
        assertEquals(PartyAutoHoldAction.Clear, partyAutoHoldAction(emptyList(), listOf("guest"), false, true))
        assertEquals(PartyAutoHoldAction.Resume, partyAutoHoldAction(emptyList(), listOf("guest"), false, false))
    }

    @Test fun clearingAwayRequiresFreshNonstarvedPlaybackThenTheExistingSettle() {
        var watch = PartyAwayRecovery().step(1_000, true, peer(1_000, away = true))
        watch = watch.step(2_000, false, peer(999)) // A delayed pre-Away report cannot answer.
        assertEquals(listOf("guest"), watch.holding)
        watch = watch.step(2_100, false, peer(2_100, starved = true))
        assertEquals(listOf("guest"), watch.holding)
        watch = watch.step(3_000, false, peer(3_000))
        assertEquals(listOf("guest"), watch.holding)
        assertEquals(listOf("guest"), watch.step(3_399, false, peer(3_000)).holding)
        assertTrue(watch.step(3_400, false, peer(3_000)).holding.isEmpty())
    }

    @Test fun readinessFlapRestartsSettleAndAnotherAbsenceCancelsReturn() {
        var watch = PartyAwayRecovery().step(1_000, true).step(2_000, false, peer(2_000))
        watch = watch.step(2_300, false, peer(2_300, starved = true))
        watch = watch.step(2_400, false, peer(2_400))
        assertEquals(listOf("guest"), watch.step(2_700, false, peer(2_400)).holding)
        watch = watch.step(2_800, true, peer(2_800, away = true))
        assertTrue(watch.returningAt.isEmpty())
        assertEquals(listOf("guest"), watch.step(5_000, true).holding)
    }

    @Test fun readinessArrivingBeforeDurableClearStillAnswersForThisAbsence() {
        val watch = PartyAwayRecovery().step(1_000, true)
            .step(2_000, true, peer(1_900)) // RPC clear arrives after this ready peer packet.
            .step(2_100, false, peer(1_900))
        assertEquals(2_100L, watch.readySince["guest"])
        val released = watch.step(2_500, false, peer(1_900))
        assertTrue(released.holding.isEmpty())
        assertEquals("all-ready", released.releasedBy["guest"])
    }

    @Test fun staleOrAwayReportsNeverEstablishReadiness() {
        assertFalse(partyPeerPlaybackReady(peer(0), WatchPartyClockStaleMs + 1, 0))
        assertFalse(partyPeerPlaybackReady(peer(1_000, away = true), 1_000, 0))
        assertFalse(partyPeerPlaybackReady(peer(1_000, starved = true), 1_000, 0))
    }

    @Test fun disconnectedReturnWaitsForEvidenceButCannotHangPastExistingCeiling() {
        val disconnected = guest.copy(connected = false, readyState = SourceResolutionState.disconnected)
        var watch = PartyAwayRecovery().step(1_000, true, member = disconnected)
        watch = watch.step(2_000, false, member = disconnected)
        assertEquals(listOf("guest"), watch.step(13_999, false, member = disconnected).holding)
        assertTrue(watch.step(14_000, false, member = disconnected).holding.isEmpty())
    }

    @Test fun disablingWaitingLeavingOrFailingClearsRecovery() {
        val watch = PartyAwayRecovery().step(1_000, true).step(2_000, false)
        assertTrue(watch.step(2_001, false, enabled = false).holding.isEmpty())
        for (state in listOf(SourceResolutionState.left, SourceResolutionState.failed)) {
            assertTrue(watch.step(2_001, false, member = guest.copy(readyState = state)).holding.isEmpty())
        }
        assertTrue(watch.advance(emptyList(), emptyList(), emptyMap(), 2_001, true).holding.isEmpty())
    }
}
