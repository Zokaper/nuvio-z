package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun joinMember(
    profileId: String,
    readyState: SourceResolutionState,
    connected: Boolean = true,
    location: WatchPartyClientLocation = WatchPartyClientLocation.player,
) = WatchPartyParticipant(
    profileId = profileId,
    role = if (profileId == "host") "host" else "participant",
    readyState = readyState,
    connected = connected,
    clientLocation = location,
    joinedAt = "2026-09-17T00:00:00Z",
)

private fun joinParty(status: WatchPartyStatus, vararg members: WatchPartyParticipant) = WatchPartyState(
    id = "party",
    hostProfileId = "host",
    status = status,
    controlMode = WatchPartyControlMode.host_only,
    contentGeneration = 1,
    content = PartyContent("tt1", "movie", "tt1", "Movie"),
    positionMs = 503_628,
    durationMs = 7_094_186,
    playbackSpeed = 1f,
    sequence = 0,
    stateUpdatedAt = "2026-09-17T05:52:55Z",
    members = members.toList(),
)

/**
 * The host's side of a join, driven through the real pure rules the player effect calls.
 *
 * Not a second implementation of the barrier: every decision here is `partyPlaybackGate`,
 * `partyStartPlaybackRelease`, `partyStartReleaseResumes`, `GuestBufferingWatch` and
 * `StallHoldBudget`. The glue is the few lines `PlayerWatchPartyEffect` wraps around them - capture
 * the intent before the gate pauses, poll the release, hold only while playing and inside the budget,
 * resume when the hold empties - so a regression in any of those rules shows up as extra transitions.
 */
private class HostJoinSimulation(playingBeforeJoin: Boolean) {
    var playing = playingBeforeJoin
        private set

    /**
     * What the durable row says. A party built from a host's playback is created `paused` whatever the
     * host was doing, and only a party command moves it - never the host's local engine.
     */
    var durableStatus = WatchPartyStatus.paused
        private set
    val transitions = mutableListOf<String>()
    private var intentPlaying: Boolean? = null
    private var released = false
    private var durablyReadyAt: Long? = null
    private var watch = GuestBufferingWatch()
    private var budget = StallHoldBudget()
    private var autoPausedFor = emptyList<String>()

    fun peer(profileId: String, status: WatchPartyStatus, nowMs: Long) {
        watch = watch.observe(profileId, status, nowMs)
    }

    fun step(nowMs: Long, party: WatchPartyState, telemetry: Map<String, PartyPeerTelemetry>) {
        if (!released) {
            if (intentPlaying == null) intentPlaying = playing
            val gate = partyPlaybackGate(party, "host", hostStartReleased = false)
            if (!gate.allowPlayback) {
                durablyReadyAt = null
                pause("start-barrier")
            } else {
                val since = durablyReadyAt ?: nowMs.also { durablyReadyAt = it }
                val decision = partyStartPlaybackRelease(party.members, "host", telemetry, true, nowMs, since)
                if (decision.release) {
                    released = true
                    watch = watch.graceStartup(
                        party.members.map { it.profileId } - "host",
                        nowMs + WatchPartyStartupStallGraceMs,
                    )
                    if (partyStartReleaseResumes(intentPlaying)) play("gate")
                }
            }
        }
        watch = watch.retainOnly(partyMembersPresent(party))
            .graceStartup(partyMembersStartingUp(party, "host"), nowMs + WatchPartyStartupStallGraceMs)
            .advance(nowMs)
        val holding = watch.holdingProfiles
        if (holding.isNotEmpty() && autoPausedFor.isEmpty()) {
            if (playing && budget.mayHold(nowMs)) {
                budget = budget.record(nowMs)
                autoPausedFor = holding
                pause("stall-guard")
            }
        } else if (holding.isEmpty() && autoPausedFor.isNotEmpty()) {
            autoPausedFor = emptyList()
            play("stall-guard")
        }
    }

    private fun pause(source: String) {
        // The gate's pause is local; a stall hold is a party command.
        if (source != "start-barrier") durableStatus = WatchPartyStatus.paused
        if (!playing) return
        playing = false
        transitions += "pause:$source"
    }

    private fun play(source: String) {
        watch = watch.resetKeepingStartupGrace()
        durableStatus = WatchPartyStatus.playing
        if (playing) return
        playing = true
        transitions += "play:$source"
    }
}

/**
 * Post-release Bug 3: a member joining a playing party made everyone's film pause and resume three
 * times. See `WatchPartyStartupStallGraceMs` for the host log this replays.
 */
class WatchPartyJoinBarrierTest {

    /** One guest's join, shaped like the 2026-09-17 host log, played against the rules. */
    private fun replayJoin(
        sim: HostJoinSimulation,
        guestReadyAtMs: Long = 20_300,
        guestParkedAtMs: Long? = 23_000,
        rebuffers: List<LongRange> = listOf(26_000L..27_000L, 31_000L..33_500L, 36_000L..37_000L),
        disconnectAtMs: Long? = null,
        endMs: Long = 60_000,
        duplicateEvents: Boolean = false,
    ) {
        var lastStatus: WatchPartyStatus? = null
        val telemetry = mutableMapOf<String, PartyPeerTelemetry>()
        var t = 0L
        while (t <= endMs) {
            val connected = disconnectAtMs == null || t < disconnectAtMs
            val readiness = when {
                t < 5_000 -> SourceResolutionState.fetching
                t < guestReadyAtMs -> SourceResolutionState.resolving
                else -> SourceResolutionState.ready
            }
            val status = when {
                t < 11_000 -> null
                rebuffers.any { t in it } -> WatchPartyStatus.buffering
                guestParkedAtMs == null || t < guestParkedAtMs -> WatchPartyStatus.buffering
                sim.playing -> WatchPartyStatus.playing
                else -> WatchPartyStatus.paused
            }
            if (connected && status != null) {
                if (duplicateEvents || status != lastStatus) sim.peer("guest", status, t)
                lastStatus = status
                telemetry["guest"] = PartyPeerTelemetry(status, receivedAtPartyMs = t)
            }
            val party = joinParty(
                sim.durableStatus,
                joinMember("host", SourceResolutionState.ready),
                joinMember(
                    "guest",
                    readiness,
                    connected = connected,
                    location = if (t < 11_000) WatchPartyClientLocation.matching else WatchPartyClientLocation.player,
                ),
            )
            sim.step(t, party, telemetry)
            if (duplicateEvents) sim.step(t, party, telemetry)
            t += 150
        }
    }

    @Test fun aPlayingPartyPausesOnceForAJoinAndResumesOnce() {
        val sim = HostJoinSimulation(playingBeforeJoin = true)
        replayJoin(sim)
        assertEquals(listOf("pause:start-barrier", "play:gate"), sim.transitions)
        assertTrue(sim.playing)
    }

    /** `ready` means the file opened, not that it can play; the barrier waits for the parked frame. */
    @Test fun noResumeBeforeTheJoinerCanActuallyPlay() {
        val sim = HostJoinSimulation(playingBeforeJoin = true)
        replayJoin(sim, endMs = 22_950)
        assertEquals(listOf("pause:start-barrier"), sim.transitions)
        assertFalse(sim.playing)
    }

    @Test fun anAlreadyPausedPartyStaysPausedAfterTheJoinerIsReady() {
        val sim = HostJoinSimulation(playingBeforeJoin = false)
        replayJoin(sim)
        assertEquals(emptyList(), sim.transitions)
        assertFalse(sim.playing)
    }

    @Test fun aJoinerThatDisconnectsBeforeReadyNoLongerBlocksTheBarrier() {
        val sim = HostJoinSimulation(playingBeforeJoin = true)
        replayJoin(sim, guestReadyAtMs = 50_000, guestParkedAtMs = null, disconnectAtMs = 8_000, endMs = 9_000)
        assertEquals(listOf("pause:start-barrier", "play:gate"), sim.transitions)
    }

    /** A joiner whose player never says it is parked cannot keep the party stopped. */
    @Test fun theBarrierReleasesOnceWhenTheJoinerNeverReportsPlayable() {
        val sim = HostJoinSimulation(playingBeforeJoin = true)
        replayJoin(
            sim,
            guestParkedAtMs = null,
            rebuffers = emptyList(),
            endMs = 20_300 + WatchPartyStartPlaybackReadyMaxWaitMs + 300,
        )
        assertEquals(listOf("pause:start-barrier", "play:gate"), sim.transitions)
    }

    /** The same status and the same snapshot arriving again and again changes nothing. */
    @Test fun duplicateReadinessAndStatusEventsCauseNoExtraCycles() {
        val sim = HostJoinSimulation(playingBeforeJoin = true)
        replayJoin(sim, duplicateEvents = true)
        assertEquals(listOf("pause:start-barrier", "play:gate"), sim.transitions)
    }

    /**
     * A joiner whose matcher found nothing chooses an alternate by hand. The barrier waits through
     * the choice - the host has Start anyway - and still resumes exactly once.
     */
    @Test fun aSourceFallbackDuringTheJoinDoesNotOscillate() {
        val sim = HostJoinSimulation(playingBeforeJoin = true)
        val telemetry = mutableMapOf<String, PartyPeerTelemetry>()
        var t = 0L
        while (t <= 40_000) {
            val readiness = when {
                t < 6_000 -> SourceResolutionState.fetching
                t < 15_000 -> SourceResolutionState.choosing_fallback
                t < 18_000 -> SourceResolutionState.resolving
                else -> SourceResolutionState.ready
            }
            if (t >= 16_000) {
                val status = when {
                    t < 19_000 -> WatchPartyStatus.buffering
                    sim.playing -> WatchPartyStatus.playing
                    else -> WatchPartyStatus.paused
                }
                sim.peer("guest", status, t)
                telemetry["guest"] = PartyPeerTelemetry(status, t)
            }
            sim.step(
                t,
                joinParty(
                    sim.durableStatus,
                    joinMember("host", SourceResolutionState.ready),
                    joinMember("guest", readiness),
                ),
                telemetry,
            )
            t += 150
        }
        assertEquals(listOf("pause:start-barrier", "play:gate"), sim.transitions)
    }

    @Test fun startupGraceKeepsAJoinersFirstRebuffersFromHoldingTheParty() {
        val watch = GuestBufferingWatch()
            .graceStartup(listOf("guest"), untilPartyMs = WatchPartyStartupStallGraceMs)
            .observe("guest", WatchPartyStatus.buffering, partyNowMs = 1_000)
        assertEquals(emptyList(), watch.advance(1_000 + WatchPartyGuestBufferingGraceMs).holdingProfiles)
        assertEquals(emptyList(), watch.advance(WatchPartyStartupStallGraceMs).holdingProfiles)
        // After the grace a stall that continues must still outlast the ordinary grace from its end.
        assertEquals(
            emptyList(),
            watch.advance(WatchPartyStartupStallGraceMs + WatchPartyGuestBufferingGraceMs - 1).holdingProfiles,
        )
        assertEquals(
            listOf("guest"),
            watch.advance(WatchPartyStartupStallGraceMs + WatchPartyGuestBufferingGraceMs).holdingProfiles,
        )
    }

    @Test fun aPartyStartKeepsTheStartupGraceButForgetsOldStalls() {
        val reset = GuestBufferingWatch()
            .graceStartup(listOf("guest"), untilPartyMs = 9_000)
            .observe("other", WatchPartyStatus.buffering, partyNowMs = 0)
            .resetKeepingStartupGrace()
        assertEquals(mapOf("guest" to 9_000L), reset.startupGraceUntilByProfile)
        assertEquals(emptyMap(), reset.bufferingSinceByProfile)
    }

    @Test fun aHeldMemberWhoLeavesReleasesTheHold() {
        val held = GuestBufferingWatch()
            .observe("guest", WatchPartyStatus.buffering, partyNowMs = 0)
            .advance(WatchPartyGuestBufferingGraceMs)
        assertEquals(listOf("guest"), held.holdingProfiles)
        assertEquals(
            emptyList(),
            held.retainOnly(setOf("host")).advance(WatchPartyGuestBufferingGraceMs + 1).holdingProfiles,
        )
    }

    @Test fun aMidFilmStallIsNotStartUp() {
        val party = joinParty(
            WatchPartyStatus.playing,
            joinMember("host", SourceResolutionState.ready),
            joinMember("stalled", SourceResolutionState.buffering),
            joinMember("opening", SourceResolutionState.resolving),
            joinMember("gone", SourceResolutionState.fetching, connected = false),
        )
        assertEquals(listOf("opening"), partyMembersStartingUp(party, "host"))
        assertEquals(setOf("host", "stalled", "opening"), partyMembersPresent(party))
    }

    @Test fun startReleaseWaitsOnlyForMembersWithSomethingToPlay() {
        val members = listOf(
            joinMember("host", SourceResolutionState.ready),
            joinMember("lobbyGuest", SourceResolutionState.ready, location = WatchPartyClientLocation.lobby),
            joinMember("gone", SourceResolutionState.ready, connected = false),
            joinMember("failed", SourceResolutionState.failed),
            joinMember("silentPlayer", SourceResolutionState.ready),
        )
        val waiting = partyStartPlaybackRelease(members, "host", emptyMap(), true, 1_000, 0)
        assertEquals(listOf("silentPlayer"), waiting.waitingOn)
        assertFalse(waiting.release)

        val parked = partyStartPlaybackRelease(
            members,
            "host",
            mapOf("silentPlayer" to PartyPeerTelemetry(WatchPartyStatus.paused, receivedAtPartyMs = 900)),
            true,
            1_000,
            0,
        )
        assertTrue(parked.release)

        // Stale evidence is no evidence.
        val stale = partyStartPlaybackRelease(
            members,
            "host",
            mapOf("silentPlayer" to PartyPeerTelemetry(WatchPartyStatus.paused, receivedAtPartyMs = 0)),
            true,
            WatchPartyClockStaleMs + 1,
            WatchPartyClockStaleMs,
        )
        assertFalse(stale.release)

        // Without a live plane the durable gate is the whole answer.
        assertTrue(partyStartPlaybackRelease(members, "host", emptyMap(), false, 1_000, 0).release)
    }

    /** The intent is what the player held when the barrier closed; a paused host is never auto-resumed. */
    @Test fun theBarrierResumesOnlyIntoTheIntentItCaptured() {
        assertTrue(partyStartReleaseResumes(true))
        assertFalse(partyStartReleaseResumes(false))
        // A barrier with no captured intent is a lobby start, which has always resumed.
        assertTrue(partyStartReleaseResumes(null))
    }
}
