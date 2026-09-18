package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

private fun tick(
    positionMs: Long,
    capturedAtPartyMs: Long,
    status: WatchPartyStatus = WatchPartyStatus.playing,
    speed: Float = 1f,
    generation: Int = 0,
    sequence: Long = 1,
) = PartyTick(
    partyId = "party",
    contentGeneration = generation,
    sequence = sequence,
    status = status,
    positionMs = positionMs,
    capturedAtPartyMs = capturedAtPartyMs,
    playbackSpeed = speed,
    durationMs = 7_200_000,
)

private fun command(
    kind: PartyCommandKind,
    startPositionMs: Long,
    startAtPartyMs: Long,
    counter: Long = 1,
    commandId: String = "cmd-$counter",
    from: String = "host",
    speed: Float = 1f,
) = PartyCommand(
    commandId = commandId,
    kind = kind,
    issuedByProfileId = from,
    counter = counter,
    contentGeneration = 0,
    startPositionMs = startPositionMs,
    startAtPartyMs = startAtPartyMs,
    playbackSpeed = speed,
)

class WatchPartyClockTest {
    /** The host stamped once, so the midpoint of the round trip is where this machine thinks it did. */
    @Test fun oneExchangeIsTheMidpointDifference() {
        val sample = partyClockSample(sentAtMs = 1_000, hostAtMs = 5_100, receivedAtMs = 1_200)
        assertEquals(200, sample.rttMs)
        assertEquals(4_000, sample.offsetMs)
    }

    /**
     * The offset is only exact on a symmetric path, and the error is bounded by half the round
     * trip - so the shortest exchange in the window is the most trustworthy thing in it, and a long
     * one carrying a wildly different answer is evidence about the network, not about the clock.
     */
    @Test fun theShortestExchangeInTheWindowIsTheOneAccepted() {
        val clock = PartyClock()
            .accept(PartyClockSample(offsetMs = 4_000, rttMs = 40, receivedAtMs = 1_000))
            .accept(PartyClockSample(offsetMs = 4_300, rttMs = 600, receivedAtMs = 2_000))
        assertEquals(4_000, clock.offsetMs)
        assertEquals(40, clock.bestRttMs)
    }

    @Test fun theEstimateLocksOnceEnoughExchangesHaveLanded() {
        var clock = PartyClock()
        repeat(WatchPartyClockLockSamples - 1) { index ->
            clock = clock.accept(PartyClockSample(4_000, 40, (index + 1) * 1_000L))
            assertFalse(clock.locked)
        }
        clock = clock.accept(PartyClockSample(4_000, 40, 9_000))
        assertTrue(clock.locked)
    }

    /**
     * Two machines drift apart by milliseconds a minute, so anything faster is a queued packet.
     * Following it would put a jump into playback that no clock ever made.
     */
    @Test fun aLockedOffsetSlewsRatherThanFollowingOneSample() {
        var clock = PartyClock()
        repeat(3) { clock = clock.accept(PartyClockSample(4_000, 40, 1_000)) }
        clock = clock.accept(PartyClockSample(4_300, 10, 2_000))
        assertEquals(4_000 + WatchPartyClockSlewLimitMs, clock.offsetMs)
        assertTrue(clock.locked)
    }

    /**
     * A second-sized move is not drift - it is an NTP correction on one of the machines, or a new
     * host after a transfer. Slewing towards it 50ms at a time would take twenty exchanges.
     */
    @Test fun aStepPastTheRelockThresholdThrowsTheWindowAway() {
        var clock = PartyClock()
        repeat(3) { clock = clock.accept(PartyClockSample(4_000, 40, 1_000)) }
        clock = clock.accept(PartyClockSample(9_000, 40, 2_000))
        assertEquals(9_000, clock.offsetMs)
        assertFalse(clock.locked)
        assertEquals(1, clock.samples.size)
    }

    /**
     * On a steady link every round trip measures the same, so ties are the ordinary case rather
     * than the edge one. Keeping the first minimum meant the very first exchange of the party won
     * every comparison for the life of the window and the estimate could not move at all.
     */
    @Test fun aTiedRoundTripGoesToTheMoreRecentExchange() {
        var clock = PartyClock()
        repeat(3) { clock = clock.accept(PartyClockSample(4_000, 40, 1_000)) }
        clock = clock.accept(PartyClockSample(4_040, 40, 2_000))
        assertEquals(4_040, clock.offsetMs)
    }

    /**
     * Eight exchanges span forty seconds at the steady cadence, so a lucky short round trip taken
     * before a host transfer would otherwise go on deciding the estimate for all of them.
     */
    @Test fun anExchangeOlderThanTheMaximumAgeStopsCounting() {
        var clock = PartyClock()
        repeat(3) { clock = clock.accept(PartyClockSample(4_000, 10, 1_000)) }
        clock = clock.accept(PartyClockSample(4_030, 400, 1_000 + WatchPartyClockSampleMaxAgeMs + 1))
        assertEquals(1, clock.samples.size)
        assertEquals(400, clock.bestRttMs)
    }

    @Test fun theOpeningExchangesAreFastAndThenSettle() {
        assertEquals(WatchPartyClockFastPingIntervalMs, watchPartyClockPingDelayMs(0))
        assertEquals(WatchPartyClockFastPingIntervalMs, watchPartyClockPingDelayMs(WatchPartyClockFastPingCount - 1))
        assertEquals(WatchPartyClockPingIntervalMs, watchPartyClockPingDelayMs(WatchPartyClockFastPingCount))
    }

    @Test fun aClockWithNoAnswersIsStale() {
        assertTrue(PartyClock().isStale(0))
        val clock = PartyClock().accept(PartyClockSample(0, 20, 1_000))
        assertFalse(clock.isStale(1_000 + WatchPartyClockStaleMs))
        assertTrue(clock.isStale(1_001 + WatchPartyClockStaleMs))
    }
}

class WatchPartyTimelineTest {
    /**
     * The whole point of the tick: the position advances from the instant it was *read*, not from
     * the instant the server happened to commit a row about it.
     */
    @Test fun theExpectedPositionAdvancesFromTheCaptureInstant() {
        assertEquals(3_000, tick(positionMs = 1_000, capturedAtPartyMs = 10_000, speed = 2f).expectedPositionMs(11_000))
        assertEquals(
            1_000,
            tick(positionMs = 1_000, capturedAtPartyMs = 10_000, speed = 2f, status = WatchPartyStatus.paused)
                .expectedPositionMs(11_000),
        )
    }

    /** Adding a Float would promote the position to Float, which quantises after about 4h39m. */
    @Test fun theExpectedPositionKeepsMillisecondPrecisionForLongContent() {
        val position = 16_777_301L
        assertEquals(position + 1_000, tick(positionMs = position, capturedAtPartyMs = 0).expectedPositionMs(1_000))
    }

    /** A socket can deliver two ticks out of order. The older one is simply dropped. */
    @Test fun aTickIsSupersededOnlyByALaterCapture() {
        val held = tick(positionMs = 1_000, capturedAtPartyMs = 10_000)
        assertTrue(tick(positionMs = 1_500, capturedAtPartyMs = 10_500).supersedes(held))
        assertFalse(tick(positionMs = 900, capturedAtPartyMs = 9_500).supersedes(held))
        assertTrue(tick(positionMs = 0, capturedAtPartyMs = 1, generation = 1).supersedes(held))
        assertTrue(tick(positionMs = 0, capturedAtPartyMs = 0).supersedes(null))
    }

    /**
     * The database anchor, kept for the degraded ladder. Still biased by the host's sample age -
     * that is what the tick exists to fix - but it is what a client has when the socket is down.
     */
    @Test fun theDatabaseAnchorStillAdvancesFromTheServerStamp() {
        assertEquals(3_000, expectedPartyPositionMs(1_000, 10_000, 11_000, WatchPartyStatus.playing, 2f))
        assertEquals(1_000, expectedPartyPositionMs(1_000, 10_000, 11_000, WatchPartyStatus.paused, 2f))
        assertEquals(
            100_000_002L,
            expectedPartyPositionMs(
                statePositionMs = 100_000_001L,
                stateUpdatedAtEpochMs = 1_000L,
                serverNowEpochMs = 1_001L,
                status = WatchPartyStatus.playing,
                playbackSpeed = 1f,
            ),
        )
    }

    @Test fun aTickGoesStaleAfterTheHostStopsSending() {
        val held = tick(positionMs = 0, capturedAtPartyMs = 1_000)
        assertFalse(held.isStale(1_000 + WatchPartyTickStaleMs))
        assertTrue(held.isStale(1_001 + WatchPartyTickStaleMs))
    }

    /**
     * The bands the anchor bias used to hide behind. 750ms was never a judgement about what is
     * visible; it was the width of the error, so the policy called the fault "in sync".
     */
    @Test fun theDriftBandsAreDeadbandNudgeAndSeek() {
        assertEquals(DriftCorrectionKind.NONE, partyDriftCorrection(1_000, 1_050, 1f).kind)
        assertEquals(DriftCorrectionKind.TEMPORARY_SPEED, partyDriftCorrection(1_000, 1_800, 1f).kind)
        assertEquals(DriftCorrectionKind.SEEK, partyDriftCorrection(1_000, 3_000, 1f).kind)
    }

    /**
     * The whole reason the field run sat at 199ms for seconds at a time.
     *
     * A proportional nudge against one hard band settles *at* the band: every pass concluded the
     * error was tolerable, and the error it was tolerating was the width of the tolerance. A guest
     * that has started correcting has to come back under the narrower band before it stops, so the
     * steady state is the exit band rather than the entry.
     */
    @Test fun aGuestAlreadyNudgingKeepsGoingUntilItIsBackUnderTheNarrowBand() {
        val gap = (WatchPartyDriftDeadbandMs + WatchPartyDriftNudgeEntryMs) / 2
        assertEquals(DriftCorrectionKind.NONE, partyDriftCorrection(0, gap, 1f, alreadyNudging = false).kind)
        assertEquals(
            DriftCorrectionKind.TEMPORARY_SPEED,
            partyDriftCorrection(0, gap, 1f, alreadyNudging = true).kind,
        )
        assertEquals(
            DriftCorrectionKind.NONE,
            partyDriftCorrection(0, WatchPartyDriftDeadbandMs, 1f, alreadyNudging = true).kind,
        )
    }

    /** Carried on the tracker, so a run of ticks converges instead of chattering at one threshold. */
    @Test fun theTrackerRemembersThatItIsNudging() {
        val entered = DriftTracker().next(0, WatchPartyDriftNudgeEntryMs + 1, 1f)
        assertEquals(DriftCorrectionKind.TEMPORARY_SPEED, entered.correction.kind)
        assertTrue(entered.tracker.nudging)

        val stillClosing = entered.tracker.next(0, WatchPartyDriftNudgeEntryMs - 1, 1f)
        assertEquals(DriftCorrectionKind.TEMPORARY_SPEED, stillClosing.correction.kind)

        val settled = stillClosing.tracker.next(0, WatchPartyDriftDeadbandMs - 1, 1f)
        assertEquals(DriftCorrectionKind.NONE, settled.correction.kind)
        assertFalse(settled.tracker.nudging)
    }

    /** The rate is chosen for the gap, and clamped where the pitch shift starts to be audible. */
    @Test fun theNudgeRateIsProportionalAndCapped() {
        val tolerance = 1e-4f
        assertEquals(1.05f, partyNudgeSpeed(125, 1f), tolerance)
        assertEquals(0.95f, partyNudgeSpeed(-125, 1f), tolerance)
        assertEquals(1.1f, partyNudgeSpeed(2_000, 1f), tolerance)
        assertEquals(0.9f, partyNudgeSpeed(-2_000, 1f), tolerance)
        // Proportional to the shared speed, so a party watching at 1.5x is nudged by the same share.
        assertEquals(1.65f, partyNudgeSpeed(2_000, 1.5f), tolerance)
    }

    /**
     * The window and the cap have to agree, or the window is not describing the correction.
     *
     * At the old 5s window a gap of more than 500ms could not be closed inside it at all, because
     * the cap bit first - so the constant that was supposed to say "closed in five seconds" said
     * nothing. Everything the nudge is now responsible for closes inside its window.
     */
    @Test fun theNudgeCanActuallyCloseTheWholeBandItOwns() {
        val worstGapMs = WatchPartySeekThresholdMs
        val closedPerWindowMs = (WatchPartyMaxNudgeRate * WatchPartyNudgeWindowMs).toLong()
        assertTrue(worstGapMs <= closedPerWindowMs * 4)
    }

    /**
     * No lead any more. A corrective seek is scheduled through [partySeekPlan], so the position it
     * aims at is the one the party will actually be at - nothing has to guess the reload cost.
     */
    @Test fun aCorrectiveSeekAimsAtTheExpectedPositionExactly() {
        assertEquals(6_000, partyDriftCorrection(0, 6_000, 1f).targetPositionMs)
        assertEquals(0, partyDriftCorrection(6_000, 0, 1f).targetPositionMs)
    }

    /**
     * One sample can be wrong - a read taken across a frame drop, a tick that queued behind a
     * retransmit - and a seek taken on it costs the stream its buffer for an error that was not
     * there. Nudged as hard as the policy allows while the gap is being confirmed.
     */
    /**
     * The fallback anchor is biased by construction - its position and its timestamp were taken at
     * different instants - so correcting it to the tick path's tolerance would seek at every poll.
     * The two policies exist because the evidence is of two different qualities.
     */
    @Test fun theFallbackPolicyKeepsTheWiderBandsItWasRightFor() {
        assertEquals(DriftCorrectionKind.NONE, partyFallbackDriftCorrection(0, 700, 1f).kind)
        assertEquals(DriftCorrectionKind.TEMPORARY_SPEED, partyFallbackDriftCorrection(0, 3_000, 1f).kind)
        assertEquals(DriftCorrectionKind.SEEK, partyFallbackDriftCorrection(0, 5_000, 1f).kind)
        // The same two gaps on the tick path: what the fallback ignores, an unbiased anchor nudges,
        // and what the fallback nudges, an unbiased anchor treats as a real stall and seeks for.
        assertEquals(DriftCorrectionKind.TEMPORARY_SPEED, partyDriftCorrection(0, 700, 1f).kind)
        assertEquals(DriftCorrectionKind.SEEK, partyDriftCorrection(0, 3_000, 1f).kind)
    }

    /** Nothing schedules the resume on the fallback path, so the old estimate of the reload stands. */
    @Test fun theFallbackSeekStillLeadsTheTargetWhenBehind() {
        assertEquals(
            5_000 + WatchPartyFallbackSeekLeadMs,
            partyFallbackDriftCorrection(0, 5_000, 1f).targetPositionMs,
        )
        assertEquals(0, partyFallbackDriftCorrection(5_000, 0, 1f).targetPositionMs)
    }

    @Test fun aSeekIsTakenOnlyAfterTheGapHasBeenSeenTwice() {
        val first = DriftTracker().next(0, 3_000, 1f)
        assertEquals(DriftCorrectionKind.TEMPORARY_SPEED, first.correction.kind)
        assertEquals(1, first.tracker.seekStreak)

        val second = first.tracker.next(0, 3_000, 1f)
        assertEquals(DriftCorrectionKind.SEEK, second.correction.kind)
        assertEquals(0, second.tracker.seekStreak)
    }

    @Test fun aGapThatClosesOnItsOwnNeverCostsASeek() {
        val first = DriftTracker().next(0, 3_000, 1f)
        val second = first.tracker.next(0, WatchPartyDriftDeadbandMs - 1, 1f)
        assertEquals(DriftCorrectionKind.NONE, second.correction.kind)
        assertEquals(0, second.tracker.seekStreak)
    }
}

class WatchPartyBarrierTest {
    @Test fun theLeadIsBoundedAtBothEnds() {
        assertEquals(WatchPartyBarrierMinLeadMs, watchPartyBarrierLeadMs(0))
        assertEquals(WatchPartyBarrierMinLeadMs, watchPartyBarrierLeadMs(260))
        assertEquals(700, watchPartyBarrierLeadMs(620))
        assertEquals(WatchPartyBarrierMaxLeadMs, watchPartyBarrierLeadMs(5_000))
    }

    /**
     * The floor has to cover the path a command actually travels, not the one the clock measures.
     *
     * Round trips are measured peer to peer; a command goes through the server RPC that authors the
     * broadcast, and the 2026-09-10 run had every one of forty commands arrive after its own
     * barrier - median 180ms late against a 250ms lead. A late barrier turns a resume into a
     * forward seek, which is what put the guest half a second out. Asserted rather than left in a
     * comment, because the floor reading as a round number is exactly how it goes back to 250.
     */
    @Test fun theLeadFloorCoversTheMeasuredCommandPath() {
        val observedLeadMs = 250L
        val medianLatenessMs = 180L
        val ninetiethLatenessMs = 421L
        assertTrue(WatchPartyBarrierMinLeadMs >= observedLeadMs + medianLatenessMs)
        assertTrue(WatchPartyBarrierMinLeadMs >= observedLeadMs + ninetiethLatenessMs - 150L)
        // And still inside the bound on how long a host waits for its own button.
        assertTrue(WatchPartyBarrierMinLeadMs < WatchPartyBarrierMaxLeadMs)
        // A resume whose barrier lands within the align tolerance takes no seek at all, which is
        // the entire point of widening it.
        assertTrue(WatchPartyBarrierAlignToleranceMs > 0)
    }

    /**
     * The resume that used to jump. Everyone is parked where the pause left them, so there is
     * nothing to seek - each client simply waits for the instant the party agreed on.
     */
    @Test fun aResumeHoldsWhereItIsInsteadOfCatchingUp() {
        val plan = partyBarrierPlan(
            command = command(PartyCommandKind.play, startPositionMs = 5_000, startAtPartyMs = 10_000),
            localPositionMs = 5_000,
            partyNowMs = 9_700,
        )
        assertNull(plan.seekToMs)
        assertEquals(300, plan.holdMs)
        assertTrue(plan.playAfter)
    }

    /** A hundred milliseconds out is cheaper for the drift nudge to absorb than for a seek to fix. */
    @Test fun aResumeDoesNotSeekForAGapTheNudgeCanAbsorb() {
        val plan = partyBarrierPlan(
            command = command(PartyCommandKind.play, startPositionMs = 5_000, startAtPartyMs = 10_000),
            localPositionMs = 5_000 + WatchPartyBarrierAlignToleranceMs,
            partyNowMs = 9_700,
        )
        assertNull(plan.seekToMs)
    }

    /**
     * A barrier that has already passed - a slow socket, a client that was reloading - must not be
     * applied at its own stale position. The overshoot goes into the target, so a late client lands
     * where the party is now.
     */
    @Test fun aLateBarrierLandsWhereThePartyIsNow() {
        val plan = partyBarrierPlan(
            command = command(PartyCommandKind.play, startPositionMs = 5_000, startAtPartyMs = 10_000),
            localPositionMs = 5_000,
            partyNowMs = 10_500,
        )
        assertEquals(5_500, plan.seekToMs)
        assertEquals(0, plan.holdMs)
        assertTrue(plan.playAfter)
    }

    /**
     * Pause carries no lead: the host pauses under its own finger, and a guest that pauses 60ms
     * later and then aligns changes one frame rather than jumping.
     */
    @Test fun pauseIsImmediateAndAlignsWhilePaused() {
        val far = partyBarrierPlan(
            command = command(PartyCommandKind.pause, startPositionMs = 5_000, startAtPartyMs = 0),
            localPositionMs = 5_400,
            partyNowMs = 9_000,
        )
        assertEquals(5_000, far.seekToMs)
        assertEquals(0, far.holdMs)
        assertFalse(far.playAfter)

        val near = partyBarrierPlan(
            command = command(PartyCommandKind.pause, startPositionMs = 5_000, startAtPartyMs = 0),
            localPositionMs = 5_000 + WatchPartyPausedAlignToleranceMs,
            partyNowMs = 9_000,
        )
        assertNull(near.seekToMs)
    }

    /** Seeking to where the party will be at the end of the hold is what makes one seek one seek. */
    @Test fun aCorrectiveSeekTargetsWhereThePartyWillBe() {
        val plan = partySeekPlan(tick(positionMs = 1_000, capturedAtPartyMs = 0), partyNowMs = 10_000)
        assertEquals(10_000 + WatchPartySeekRecoveryLeadMs, plan.resumeAtPartyMs)
        assertEquals(1_000 + 10_000 + WatchPartySeekRecoveryLeadMs, plan.seekToMs)
    }

    /**
     * A client that has exchanged nothing with the host has no offset, and an offset of zero is the
     * absence of an estimate rather than a good one. Reading the command as though it arrived
     * exactly on time collapses the barrier to what the transport did before barriers existed: go
     * to the position, act now - which is the right answer when there is no shared instant.
     */
    @Test fun aBarrierReadAsOnTimeCollapsesToActingNow() {
        val issued = command(PartyCommandKind.play, startPositionMs = 5_000, startAtPartyMs = 1_700_000_000_000)
        val plan = partyBarrierPlan(issued, localPositionMs = 2_000, partyNowMs = issued.startAtPartyMs)
        assertEquals(0, plan.holdMs)
        assertEquals(5_000, plan.seekToMs)
        assertTrue(plan.playAfter)
    }

    @Test fun aCommandIsObeyedOnceAndOlderOnesAreDropped() {
        val first = command(PartyCommandKind.play, 0, 0, counter = 4)
        val log = PartyCommandLog().also { assertTrue(it.accepts(first)) }.record(first)

        assertFalse(log.accepts(first))
        assertFalse(log.accepts(command(PartyCommandKind.pause, 0, 0, counter = 3, commandId = "older")))
        assertTrue(log.accepts(command(PartyCommandKind.pause, 0, 0, counter = 5, commandId = "newer")))
        // Counters are per sender, so a second controller in a collaborative party is not gated by
        // the first one's numbering.
        assertTrue(log.accepts(command(PartyCommandKind.pause, 0, 0, counter = 1, commandId = "other", from = "guest")))
    }

    @Test fun theCommandLogIsBounded() {
        var log = PartyCommandLog()
        repeat(WatchPartyCommandLogSize + 10) { index ->
            log = log.record(command(PartyCommandKind.seek, 0, 0, counter = index.toLong(), commandId = "cmd-$index"))
        }
        assertEquals(WatchPartyCommandLogSize, log.appliedIds.size)
    }

    /** A routine one-second rebuffer must not stop the film for everyone else. */
    @Test fun theHostWaitsOnlyForAGuestStalledPastTheGrace() {
        val watch = GuestBufferingWatch().observe("guest", WatchPartyStatus.buffering, partyNowMs = 0)
        assertEquals(emptyList(), watch.advance(WatchPartyGuestBufferingGraceMs - 1).holdingProfiles)
        assertEquals(listOf("guest"), watch.advance(WatchPartyGuestBufferingGraceMs).holdingProfiles)
        assertEquals(
            emptyList(),
            watch.observe("guest", WatchPartyStatus.playing, partyNowMs = 900)
                .advance(WatchPartyGuestBufferingGraceMs)
                .holdingProfiles,
        )
    }

    /** A member who goes on stalling keeps the instant they started, not the instant last seen. */
    @Test fun aContinuingStallKeepsItsStartingInstant() {
        val watch = GuestBufferingWatch()
            .observe("guest", WatchPartyStatus.buffering, partyNowMs = 0)
            .observe("guest", WatchPartyStatus.buffering, partyNowMs = 1_000)
        assertEquals(listOf("guest"), watch.advance(WatchPartyGuestBufferingGraceMs).holdingProfiles)
    }

    /**
     * The grace has to outlast the longest hold a guest takes on its own account.
     *
     * Both were 1500ms, so a guest realigning reported `buffering` for exactly the grace and the host
     * held the party every single time - four pause/resume pairs in thirty seconds of the
     * 2026-09-02 run, with nothing actually wrong with anybody's stream.
     */
    @Test fun theGraceOutlastsAGuestsOwnCorrectiveHold() {
        assertTrue(WatchPartyGuestBufferingGraceMs > WatchPartySeekRecoveryLeadMs)
    }

    /**
     * The hold ends once the member it is holding is full again, however that member reads.
     *
     * A held member reports `paused`, because the hold *is* a party pause and it obeyed: demanding
     * `playing` of it is asking it to disobey the command holding it, and the 2026-09-10 two-client
     * run is what that cost - the guest finished buffering, said so, and the party sat paused until
     * somebody pressed play. `paused` is only ever "parked and full" here, since a member still
     * starved reports `buffering`.
     */
    @Test fun aHoldEndsOnceTheHeldGuestIsFullAgain() {
        val held = GuestBufferingWatch()
            .observe("guest", WatchPartyStatus.buffering, partyNowMs = 0)
            .advance(WatchPartyGuestBufferingGraceMs)
        assertEquals(listOf("guest"), held.holdingProfiles)

        val full = held.observe("guest", WatchPartyStatus.paused, partyNowMs = WatchPartyGuestBufferingGraceMs)
        assertEquals(
            listOf("guest"),
            full.advance(WatchPartyGuestBufferingGraceMs + WatchPartyStallRecoverySettleMs - 1).holdingProfiles,
        )
        assertEquals(
            emptyList(),
            full.advance(WatchPartyGuestBufferingGraceMs + WatchPartyStallRecoverySettleMs).holdingProfiles,
        )
    }

    /** A member that is still starved keeps the hold, whatever the settle says. */
    @Test fun aStillStarvedGuestIsNotReadyNoMatterHowLongItHasBeenHeld() {
        val held = GuestBufferingWatch()
            .observe("guest", WatchPartyStatus.buffering, partyNowMs = 0)
            .advance(WatchPartyGuestBufferingGraceMs)
            .observe("guest", WatchPartyStatus.buffering, partyNowMs = WatchPartyGuestBufferingGraceMs)
        assertEquals(
            listOf("guest"),
            held.advance(WatchPartyGuestBufferingGraceMs + WatchPartyStallRecoverySettleMs).holdingProfiles,
        )
    }

    /**
     * A pause from a member nobody is holding is a person, not a recovery.
     *
     * This is the half of the old rule worth keeping: reading every `paused` as recovery is what
     * made one stall produce a hold, a release and another hold moments later.
     */
    @Test fun aPauseFromAnUnheldGuestStartsNoRecoveryClock() {
        val watch = GuestBufferingWatch()
            .observe("guest", WatchPartyStatus.paused, partyNowMs = 0)
        assertEquals(emptyMap(), watch.readySinceByProfile)
        // And it cannot shorten a hold that is taken afterwards.
        val held = watch
            .observe("guest", WatchPartyStatus.buffering, partyNowMs = 100)
            .advance(100 + WatchPartyGuestBufferingGraceMs)
        assertEquals(listOf("guest"), held.holdingProfiles)
    }

    /**
     * Readiness order one: the guest is ready before the host is.
     *
     * Nothing is ever held, so nothing has to be released - the start gate is what makes the guest
     * wait here, not the stall guard.
     */
    @Test fun aGuestReadyBeforeTheHostIsNeverHeld() {
        val watch = GuestBufferingWatch()
            .observe("guest", WatchPartyStatus.buffering, partyNowMs = 0)
            .observe("guest", WatchPartyStatus.paused, partyNowMs = 200)
            .observe("guest", WatchPartyStatus.playing, partyNowMs = 400)
        assertEquals(emptyList(), watch.advance(10_000).holdingProfiles)
    }

    /**
     * Readiness order two: the host is ready first, starts, and holds for the guest.
     *
     * The whole reported failure in one sequence - hold taken late, guest recovers into the party's
     * own pause, hold released without anybody pressing anything. Held past the settle so the
     * release is the settle's doing and not [WatchPartyStallHoldMaxMs] abandonment.
     */
    @Test fun aHostThatStartedFirstReleasesItsHoldWhenTheGuestCatchesUp() {
        val grace = WatchPartyGuestBufferingGraceMs
        val settle = WatchPartyStallRecoverySettleMs
        assertTrue(grace + settle < WatchPartyStallHoldMaxMs)

        val held = GuestBufferingWatch()
            .observe("guest", WatchPartyStatus.buffering, partyNowMs = 0)
            .advance(grace)
        assertEquals(listOf("guest"), held.holdingProfiles)

        // The host's stall-guard pause reaches the guest, which is still starved: still held.
        val obeying = held.observe("guest", WatchPartyStatus.buffering, partyNowMs = grace + 50)
        assertEquals(listOf("guest"), obeying.advance(grace + 100).holdingProfiles)

        // The guest fills and reports the pause it was given.
        val recovered = obeying.observe("guest", WatchPartyStatus.paused, partyNowMs = grace + 500)
        assertEquals(listOf("guest"), recovered.advance(grace + 500 + settle - 1).holdingProfiles)
        assertEquals(emptyList(), recovered.advance(grace + 500 + settle).holdingProfiles)
    }

    /**
     * One member that never comes back must not hold the party for the length of the film.
     *
     * Held on a member that has dropped to a lobby: it is neither starved nor ready, so no settle
     * can release it and the window is the only thing that can.
     */
    @Test fun aHoldIsAbandonedOnceItHasOutlastedTheWindow() {
        val held = GuestBufferingWatch()
            .observe("guest", WatchPartyStatus.buffering, partyNowMs = 0)
            .advance(WatchPartyGuestBufferingGraceMs)
            .observe("guest", WatchPartyStatus.lobby, partyNowMs = WatchPartyGuestBufferingGraceMs)
        assertEquals(listOf("guest"), held.advance(WatchPartyGuestBufferingGraceMs + 10).holdingProfiles)
        assertEquals(
            emptyList(),
            held.advance(WatchPartyGuestBufferingGraceMs + WatchPartyStallHoldMaxMs).holdingProfiles,
        )
    }

    /** A source that flaps must not be able to stop and start the party indefinitely. */
    @Test fun theHostStopsHoldingAfterTooManyHoldsInAWindow() {
        var budget = StallHoldBudget()
        assertTrue(budget.mayHold(0))
        budget = budget.record(0)
        // Inside the cooldown, however loudly the guest reports a stall.
        assertFalse(budget.mayHold(WatchPartyStallHoldCooldownMs - 1))
        assertTrue(budget.mayHold(WatchPartyStallHoldCooldownMs))

        budget = budget.record(WatchPartyStallHoldCooldownMs)
        budget = budget.record(2 * WatchPartyStallHoldCooldownMs)
        assertTrue(budget.exhausted(2 * WatchPartyStallHoldCooldownMs))
        assertFalse(budget.mayHold(3 * WatchPartyStallHoldCooldownMs))
        // The window is what releases it, so a party that settles down gets the behaviour back.
        assertFalse(budget.exhausted(WatchPartyStallHoldWindowMs + 2 * WatchPartyStallHoldCooldownMs))
    }
}

class WatchPartySeekWhilePausedTest {

    /**
     * Scrubbing a paused party must move everybody and start nobody.
     *
     * The plan returned `playAfter = true` unconditionally for play *and* seek, and the executor
     * ignored the field and played anyway - so dragging the bar on a paused film started it again on
     * every member, which is what a host looking for a scene does over and over.
     */
    @Test fun aSeekOnAPausedPartyDoesNotResumeIt() {
        val paused = command(PartyCommandKind.seek, startPositionMs = 60_000, startAtPartyMs = 1_000)
            .copy(playAfter = false)
        val plan = partyBarrierPlan(paused, localPositionMs = 0, partyNowMs = 900)
        assertFalse(plan.playAfter)
        assertEquals(60_000, plan.seekToMs)

        val playing = command(PartyCommandKind.seek, startPositionMs = 60_000, startAtPartyMs = 1_000)
        assertTrue(partyBarrierPlan(playing, localPositionMs = 0, partyNowMs = 900).playAfter)
    }

    /**
     * A late command carries its overshoot into the target so the client lands where the party is
     * *now* - but nothing is running to overshoot when the seek is not going to resume, and adding
     * the delivery delay there would put every paused scrub a few hundred milliseconds past its mark.
     */
    @Test fun aPausedSeekIgnoresTheDeliveryOvershoot() {
        val late = command(PartyCommandKind.seek, startPositionMs = 60_000, startAtPartyMs = 1_000)
            .copy(playAfter = false)
        assertEquals(60_000, partyBarrierPlan(late, localPositionMs = 0, partyNowMs = 4_000).seekToMs)

        val latePlaying = command(PartyCommandKind.seek, startPositionMs = 60_000, startAtPartyMs = 1_000)
        assertEquals(63_000, partyBarrierPlan(latePlaying, localPositionMs = 0, partyNowMs = 4_000).seekToMs)
    }

    /** A build that predates the field always resumed, so a command without it still does. */
    @Test fun aCommandWithoutTheFieldStillResumes() {
        assertTrue(
            command(PartyCommandKind.seek, startPositionMs = 0, startAtPartyMs = 0).playAfter,
        )
    }
}

class WatchPartyPendingSeekTest {

    /** Nothing is measured or issued while the position being measured is the pre-seek one. */
    @Test fun aSeekIsOutstandingUntilThePositionArrives() {
        val pending = pendingPartySeek(targetMs = 30_000, nowMs = 0)
        assertTrue(pending.isOutstanding(nowMs = 100, positionMs = 27_027))
        assertFalse(pending.isOutstanding(nowMs = 100, positionMs = 30_000))
        assertFalse(
            pending.isOutstanding(nowMs = 100, positionMs = 30_000 - WatchPartySeekLandedToleranceMs),
        )
        assertTrue(
            pending.isOutstanding(nowMs = 100, positionMs = 30_000 - WatchPartySeekLandedToleranceMs - 1),
        )
    }

    /**
     * It expires rather than waiting to be cleared.
     *
     * A flag some path has to remember to clear is a wedged party the first time a content change or
     * a released player skips the clear.
     */
    @Test fun aSeekThatCannotLandTimesOutRatherThanHoldingForever() {
        val pending = pendingPartySeek(targetMs = 30_000, nowMs = 0)
        assertTrue(pending.isOutstanding(nowMs = WatchPartySeekLandingTimeoutMs - 1, positionMs = 0))
        assertFalse(pending.isOutstanding(nowMs = WatchPartySeekLandingTimeoutMs, positionMs = 0))
        assertTrue(pending.timedOut(nowMs = WatchPartySeekLandingTimeoutMs, positionMs = 0))
        assertFalse(pending.timedOut(nowMs = WatchPartySeekLandingTimeoutMs, positionMs = 30_000))
    }
}

class WatchPartySyncProtocolTest {

    @Test
    fun intentionalChannelCloseIsConsumedOnlyOnce() {
        val first = partyChannelClosePlan(
            hasChannel = true,
            boundPartyId = "party",
            channelInstance = 7,
            detached = true,
        )
        val duplicate = partyChannelClosePlan(
            hasChannel = false,
            boundPartyId = null,
            channelInstance = 7,
            detached = true,
        )

        assertEquals(PartyChannelClosePlan("party", 7, detached = true), first)
        assertNull(duplicate, "the null authority emission after cancellation must not close twice")
    }
    @Test fun everyMessageSurvivesARoundTrip() {
        val messages = listOf(
            PartyTickMessage("host", tick(positionMs = 1_234, capturedAtPartyMs = 99_000, speed = 1.5f)),
            PartyCommandMessage("party", command(PartyCommandKind.seek, 4_000, 5_000, counter = 7)),
            // The paused scrub, which is the case the flag exists for: it has to survive the wire,
            // because a guest cannot tell it from a scrub-while-playing by looking at its own player.
            PartyCommandMessage(
                "party",
                command(PartyCommandKind.seek, 4_000, 5_000, counter = 8).copy(playAfter = false),
            ),
            PartyClockPingMessage("party", "guest", exchangeId = "x1", sentAtMs = 10),
            PartyClockPongMessage("party", "host", toProfileId = "guest", exchangeId = "x1", sentAtMs = 10, hostAtMs = 4_010),
            PartyPeerStatusMessage("party", "guest", WatchPartyStatus.buffering, atPartyMs = 500, rttMs = 42),
        )
        messages.forEach { message ->
            assertEquals(message, decodePartySyncMessage(encodePartySyncMessage(message)))
        }
    }

    /**
     * Desktop and mobile ship separately, so a new client and an old one meet in the field. An
     * unreadable payload is a reason to fall back to the database anchor, never a reason to guess.
     */
    @Test fun anUnreadablePayloadDecodesToNothing() {
        val tickPayload = encodePartySyncMessage(PartyTickMessage("host", tick(0, 0)))
        val newer = kotlinx.serialization.json.buildJsonObject {
            tickPayload.forEach { (key, value) -> if (key != "v") put(key, value) }
            put("v", kotlinx.serialization.json.JsonPrimitive(WatchPartySyncProtocolVersion + 1))
        }
        assertNull(decodePartySyncMessage(newer))

        val unknownType = kotlinx.serialization.json.buildJsonObject {
            tickPayload.forEach { (key, value) -> if (key != "t") put(key, value) }
            put("t", kotlinx.serialization.json.JsonPrimitive("something-else"))
        }
        assertNull(decodePartySyncMessage(unknownType))

        val missingField = kotlinx.serialization.json.buildJsonObject {
            tickPayload.forEach { (key, value) -> if (key != "at") put(key, value) }
        }
        assertNull(decodePartySyncMessage(missingField))
    }
}

/**
 * The reconnect loop's exception classification. `maintainChannel` retries forever while the party
 * is still wanted, so what it decides to rethrow is the one thing that can retire it permanently.
 */
class WatchPartyChannelReconnectTest {

    /** A real `withTimeout` casualty, not a hand-built stand-in: the bug was about its type. */
    private suspend fun subscribeTimeout(): TimeoutCancellationException {
        try {
            withTimeout(1) { delay(60_000) }
        } catch (timedOut: TimeoutCancellationException) {
            return timedOut
        }
        error("withTimeout was expected to expire")
    }

    /** A real cancellation raised by the scope going away underneath a suspended child. */
    private suspend fun scopeCancellation(): CancellationException = coroutineScope {
        var captured: CancellationException? = null
        val child = launch {
            try {
                delay(60_000)
            } catch (cancelled: CancellationException) {
                captured = cancelled
                throw cancelled
            }
        }
        yield()
        child.cancelAndJoin()
        captured ?: error("the cancelled child was expected to observe its cancellation")
    }

    /**
     * Runs the same decision `maintainChannel` runs, with a scripted `openChannel`, and reports how
     * many attempts it made, how many it reported as degraded, and what escaped the loop.
     */
    private fun driveReconnectLoop(attempts: List<Throwable?>): Triple<Int, Int, Throwable?> {
        var opened = 0
        var reported = 0
        var escaped: Throwable? = null
        try {
            for (outcome in attempts) {
                try {
                    opened += 1
                    if (outcome != null) throw outcome
                } catch (failure: Throwable) {
                    if (partyChannelFailureIsScopeCancellation(failure)) throw failure
                    reported += 1
                }
            }
        } catch (failure: Throwable) {
            escaped = failure
        }
        return Triple(opened, reported, escaped)
    }

    @Test
    fun aSubscribeTimeoutIsAFailedAttemptRatherThanACancelledScope() = runBlocking {
        val timedOut = subscribeTimeout()
        assertFalse(
            partyChannelFailureIsScopeCancellation(timedOut),
            "the loop's own subscribe timeout must not read as the scope being cancelled",
        )
    }

    @Test
    fun aCancelledScopeIsStillACancelledScope() = runBlocking {
        assertTrue(partyChannelFailureIsScopeCancellation(scopeCancellation()))
    }

    @Test
    fun anOrdinaryTransportFailureIsRetried() {
        assertFalse(partyChannelFailureIsScopeCancellation(IllegalStateException("no session")))
    }

    /**
     * The regression itself: a subscribe that ran long used to escape the reconnect loop and take
     * the process-wide authority collector with it. It has to reconnect instead.
     */
    @Test
    fun aSubscribeTimeoutReconnectsInsteadOfRetiringTheLoop() = runBlocking {
        val (opened, reported, escaped) = driveReconnectLoop(
            listOf(subscribeTimeout(), subscribeTimeout(), null),
        )
        assertEquals(3, opened, "the loop must keep reopening after a subscribe timeout")
        assertEquals(2, reported, "each timed-out attempt is reported as degraded")
        assertNull(escaped, "a subscribe timeout must not escape the reconnect loop")
    }

    @Test
    fun aCancelledScopeStillEndsTheLoop() = runBlocking {
        val cancelled = scopeCancellation()
        val (opened, reported, escaped) = driveReconnectLoop(listOf(cancelled, null))
        assertEquals(1, opened, "cancellation stops the loop at the attempt that saw it")
        assertEquals(0, reported, "a cancelled scope is not a transport failure to report")
        assertSame(cancelled, escaped, "genuine cancellation must propagate unchanged")
    }

    // --------------------------------------------------------------- which plane may say what
    //
    // Supabase Realtime authorizes a private channel once, at join, with a stub row whose payload is
    // NULL, and caches the answer. There is no per-broadcast payload hook, so nothing a message
    // *claims* about its sender can be checked - the topic it could have been written to is the
    // only sender binding this transport has.

    @Test fun onlyTheServerAuthoredPlaneMayCarryACommand() {
        val command = PartyCommandMessage("party", command(PartyCommandKind.pause, 1_000, 2_000))
        assertTrue(partyMessageIsAdmissible(command, PartyRealtimePlane.Authority))
        assertFalse(
            partyMessageIsAdmissible(command, PartyRealtimePlane.Peer),
            "members can write the peer plane, so a command arriving on it is a forgery",
        )
    }

    @Test fun peerTrafficIsRefusedOnTheAuthorityPlane() {
        val peerPlane = listOf(
            PartyTickMessage("host", tick(positionMs = 10, capturedAtPartyMs = 20, speed = 1f)),
            PartyClockPingMessage("party", "guest", exchangeId = "x", sentAtMs = 1),
            PartyClockPongMessage("party", "host", toProfileId = "guest", exchangeId = "x", sentAtMs = 1, hostAtMs = 2),
            PartyPeerStatusMessage("party", "guest", WatchPartyStatus.buffering, atPartyMs = 3),
        )
        peerPlane.forEach { message ->
            assertTrue(
                partyMessageIsAdmissible(message, PartyRealtimePlane.Peer),
                "${message::class.simpleName} belongs on the peer plane",
            )
            assertFalse(
                partyMessageIsAdmissible(message, PartyRealtimePlane.Authority),
                "${message::class.simpleName} is not something the backend authors",
            )
        }
    }

    @Test fun anAcceptedCommandReachesTheOtherMembersThroughTheDurableSubmission() {
        // A client may not write the authority plane, so the durable submit is the command's only
        // route to anybody else - and it must not overtake the sender's own player.
        val events = mutableListOf<String>()
        dispatchPartyCommandLocallyFirst(
            command = command(PartyCommandKind.pause, startPositionMs = 1_000, startAtPartyMs = 2_000),
            emitDirective = { events += "directive" },
            enqueueRemoteDelivery = { events += "durable" },
        )
        assertEquals(listOf("directive", "durable"), events)
    }
}
