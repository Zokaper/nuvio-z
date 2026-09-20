package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The readiness barrier for buffering the party asked for.
 *
 * A seek empties every member's buffer by construction, so the old flow - resume on a fixed lead,
 * find out afterwards - could only ever discover the cost once it had been paid. The 2026-09-20 run
 * measured it: the host released its seek barrier at 10:14:31.516 and the guest's `starved=true`
 * arrived at 10:14:31.954. Nothing was wrong with either client; the answer simply could not exist
 * before the question was asked, because a member holding for a barrier publishes nothing.
 *
 * So the seek parks the party (`playAfter = false`) and the resume waits for positive readiness from
 * the members it moved. Same rule as the start barrier, one argument different - see
 * [partyStartPlaybackRelease] - because it is the same question about the same members.
 */
class PartySeekReadinessBarrierTest {
    private val seekAtPartyMs = 400_000L

    private fun member(
        profileId: String,
        readyState: SourceResolutionState = SourceResolutionState.ready,
        connected: Boolean = true,
        location: WatchPartyClientLocation = WatchPartyClientLocation.player,
    ) = WatchPartyParticipant(
        profileId = profileId,
        role = if (profileId == "host") "host" else "participant",
        readyState = readyState,
        connected = connected,
        clientLocation = location,
        joinedAt = "2026-09-20T10:14:00Z",
    )

    private fun telemetry(
        status: WatchPartyStatus,
        starved: Boolean = false,
        reportedAtPartyMs: Long = seekAtPartyMs + 500L,
        receivedAtPartyMs: Long = seekAtPartyMs + 640L,
    ) = PartyPeerTelemetry(
        status = status,
        receivedAtPartyMs = receivedAtPartyMs,
        starved = starved,
        reportedAtPartyMs = reportedAtPartyMs,
    )

    private fun release(
        telemetry: Map<String, PartyPeerTelemetry>,
        members: List<WatchPartyParticipant> = listOf(member("host"), member("guest")),
        partyNowMs: Long = seekAtPartyMs + 1_000L,
    ) = partyStartPlaybackRelease(
        members = members,
        viewerProfileId = "host",
        peerTelemetry = telemetry,
        realtimeLive = true,
        partyNowMs = partyNowMs,
        durablyReadyAtPartyMs = seekAtPartyMs,
        freshSincePartyMs = seekAtPartyMs,
    )

    // 1. The finding.
    @Test
    fun aReportFromBeforeTheSeekCannotAnswerForAfterIt() {
        // The guest said "paused, not starved" a second before the scrub. It was true then, about a
        // position the party has since left. Resuming on it is the old behaviour in a new place.
        val stale = release(
            mapOf(
                "guest" to telemetry(
                    WatchPartyStatus.paused,
                    reportedAtPartyMs = seekAtPartyMs - 1_000L,
                    receivedAtPartyMs = seekAtPartyMs - 860L,
                ),
            ),
        )
        assertFalse(stale.release)
        assertEquals(listOf("guest"), stale.waitingOn)
    }

    @Test
    fun aReportThatCrossedWithTheCommandIsNotAnAnswerEither() {
        // Sent before the seek, received after it. The receipt clock would call this fresh, which is
        // why the sender's own stamp is what the freshness test reads.
        val crossed = release(
            mapOf(
                "guest" to telemetry(
                    WatchPartyStatus.paused,
                    reportedAtPartyMs = seekAtPartyMs - 60L,
                    receivedAtPartyMs = seekAtPartyMs + 80L,
                ),
            ),
        )
        assertFalse(crossed.release)
    }

    // 2. What the barrier is waiting to hear.
    @Test
    fun aMemberParkedOnTheFrameWithAFullEngineReleasesTheBarrier() {
        val ready = release(mapOf("guest" to telemetry(WatchPartyStatus.paused)))
        assertTrue(ready.release)
        assertFalse(ready.timedOut)
        assertEquals(emptyList(), ready.waitingOn)
    }

    // 3. The whole reason `starved` had to come over the wire, in its second form.
    @Test
    fun aMemberParkedOnAnEmptyEngineDoesNotReleaseIt() {
        // `paused` is what a member reports while the party holds it, full or empty alike. Reading
        // that as readiness is the 2026-09-19 failure; here it would resume the party onto a guest
        // that cannot play a frame of what it was seeked to.
        val empty = release(mapOf("guest" to telemetry(WatchPartyStatus.paused, starved = true)))
        assertFalse(empty.release)
        assertEquals(listOf("guest"), empty.waitingOn)
    }

    @Test
    fun aMemberStillBufferingDoesNotReleaseIt() {
        assertFalse(release(mapOf("guest" to telemetry(WatchPartyStatus.buffering))).release)
    }

    // 4. It cannot become an infinite barrier.
    @Test
    fun aMemberThatNeverAnswersIsGivenUpOnAtTheCeiling() {
        val stillWaiting = release(
            telemetry = mapOf("guest" to telemetry(WatchPartyStatus.buffering)),
            partyNowMs = seekAtPartyMs + WatchPartyStartPlaybackReadyMaxWaitMs - 1L,
        )
        assertFalse(stillWaiting.release)

        val timedOut = release(
            telemetry = mapOf("guest" to telemetry(WatchPartyStatus.buffering)),
            partyNowMs = seekAtPartyMs + WatchPartyStartPlaybackReadyMaxWaitMs,
        )
        assertTrue(timedOut.release, "the party goes on without a member that never came back")
        assertTrue(timedOut.timedOut)
        assertEquals(listOf("guest"), timedOut.waitingOn, "and says who it left behind")
    }

    // 5. Members who cannot be waited for at all.
    @Test
    fun aMemberWhoHasGoneOrFailedIsNotWaitedFor() {
        for (state in listOf(
            SourceResolutionState.left,
            SourceResolutionState.failed,
            SourceResolutionState.disconnected,
        )) {
            val gone = release(
                telemetry = mapOf("guest" to telemetry(WatchPartyStatus.buffering)),
                members = listOf(member("host"), member("guest", readyState = state)),
            )
            assertTrue(gone.release, "$state must not hold the party")
        }
        val disconnected = release(
            telemetry = mapOf("guest" to telemetry(WatchPartyStatus.buffering)),
            members = listOf(member("host"), member("guest", connected = false)),
        )
        assertTrue(disconnected.release)
    }

    @Test
    fun aMemberWatchingFromTheLobbyIsNotWaitedFor() {
        // Nothing to seek, so nothing to be ready for. The durable states are the whole answer about
        // somebody who is not in a player.
        val lobby = release(
            telemetry = emptyMap(),
            members = listOf(member("host"), member("guest", location = WatchPartyClientLocation.lobby)),
        )
        assertTrue(lobby.release)
    }

    @Test
    fun aPartyWithoutALivePlaneResumesOnTheSeekAsItAlwaysDid() {
        // No peer plane, no readiness to wait for: the barrier would be waiting for a report that
        // cannot arrive, and a party on the durable path would never resume from a scrub.
        val degraded = partyStartPlaybackRelease(
            members = listOf(member("host"), member("guest")),
            viewerProfileId = "host",
            peerTelemetry = emptyMap(),
            realtimeLive = false,
            partyNowMs = seekAtPartyMs + 1_000L,
            durablyReadyAtPartyMs = seekAtPartyMs,
            freshSincePartyMs = seekAtPartyMs,
        )
        assertTrue(degraded.release)
    }

    // 6. The start barrier's own call must be unchanged by the argument it does not pass.
    @Test
    fun theStartBarrierStillAcceptsAnyRecentReport() {
        val atStart = partyStartPlaybackRelease(
            members = listOf(member("host"), member("guest")),
            viewerProfileId = "host",
            peerTelemetry = mapOf(
                "guest" to PartyPeerTelemetry(
                    status = WatchPartyStatus.paused,
                    receivedAtPartyMs = 1_000L,
                ),
            ),
            realtimeLive = true,
            partyNowMs = 1_200L,
            durablyReadyAtPartyMs = 900L,
        )
        assertTrue(atStart.release, "a report with no stamp at all is still an answer at the start")
    }
}
