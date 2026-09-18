package com.nuvio.app.features.watchparty

import kotlin.math.abs

/**
 * Where the party is now, and what a guest should do about being somewhere else.
 *
 * The whole of the "a few seconds out of sync" report is one arithmetic fault, and it is in what
 * the old anchor paired. Party state carried `(position_ms, state_updated_at)`, where the position
 * was a host sample lifted from a 500ms Compose polling loop and the timestamp was the *server's*
 * `now()` when the RPC executed. Those are two different instants: the sample was already up to
 * 500ms old before it was serialized, and then it crossed the network. Every guest computed
 * `position + (serverNow - state_updated_at)` and so ran behind the host by that entire gap - a
 * constant, re-applied on every anchor, and invisible from either side because both machines
 * computed the same wrong number.
 *
 * A [PartyTick] carries the position and **the instant that position was read**, on one clock. The
 * propagation delay is then measured rather than absorbed, and the residual error is what is left
 * after that: clock estimate error, and the genuine difference between two machines' playback
 * rates. Both are tens of milliseconds.
 *
 * Import-free, so `scripts/run-pure-suites.sh` runs this file rather than a copy of it.
 */

/** Host cadence while the party is playing. Twice a second is what makes a nudge a nudge. */
const val WatchPartyTickIntervalMs = 500L

/** Host cadence while nothing is moving: enough to prove liveness, cheap enough to ignore. */
const val WatchPartyIdleTickIntervalMs = 2_000L

/**
 * How long a status has to hold before the host publishes it out of turn.
 *
 * A stall is published the instant it starts, because that is what tells every guest to hold, and
 * being late with it costs them a seek each. Coming *out* of one is debounced: a source that has
 * "recovered" for eighty milliseconds has not, and a host whose stream is flapping would otherwise
 * spend a message on every flap and start the party over and over.
 */
const val WatchPartyStatusSettleMs = 200L

/**
 * Past this a tick is not evidence about now.
 *
 * Four idle intervals' worth of silence, so a paused party at 2s ticks is never mistaken for a host
 * that has gone away, while a playing one is noticed within a couple of missed ticks.
 */
const val WatchPartyTickStaleMs = 4_000L

/**
 * Drift the guest settles at: below this, correcting is more visible than the error.
 *
 * Was 750ms, then 200ms, and the 200 was measured in the field doing exactly what a plain deadband
 * does - **a proportional nudge against a hard band settles at the band.** The two-client run of
 * 2026-09-02 shows `driftMs=197..199 action=NONE` held for seconds at a time: every pass concluded
 * the error was tolerable, and the error it was tolerating was the width of the tolerance.
 *
 * The fix is hysteresis rather than a smaller number: nudging starts at
 * [WatchPartyDriftNudgeEntryMs] and continues until the gap is back under this, so the steady state
 * is this value rather than the entry. See [partyDriftCorrection].
 */
const val WatchPartyDriftDeadbandMs = 60L

/**
 * The gap at which a guest that is *not* already correcting starts to.
 *
 * Wider than the exit band on purpose. Chattering a nudge on and off around one threshold is a
 * pitch-corrected speed change every half second for the length of a film, which is audible in a way
 * that being sixty milliseconds out is not.
 */
const val WatchPartyDriftNudgeEntryMs = 120L

/**
 * Above this a nudge cannot close the gap in reasonable time, so the guest takes the stall and
 * seeks.
 *
 * Was 4s, then 1.5s. At the ±10% cap a nudge closes a hundred milliseconds a second, so 1.5s of gap
 * took fifteen seconds to close and the guest was measurably out for all of them. A seek is now
 * scheduled *and exact* - see [PartySeekPlan] and `PlayerEngineController.seekToExact` - so it lands
 * where it was aimed rather than on the keyframe before it, which is what makes a seek cheap enough
 * to be the answer for anything a nudge would spend more than a few seconds on.
 */
const val WatchPartySeekThresholdMs = 1_000L

/**
 * How long a speed nudge is given to close the gap it was chosen for.
 *
 * Was 5s against a ±10% cap, which is a contradiction: the cap alone means a gap of more than 500ms
 * cannot be closed inside the window, so the window was not describing the correction it sized. At
 * 2.5s the requested rate reaches the cap at 250ms and the whole band below the seek threshold
 * closes inside a few seconds.
 */
const val WatchPartyNudgeWindowMs = 2_500L

/**
 * Bound on the nudge, as a fraction of the party's shared speed. The player preserves pitch, so
 * +-10% is unobjectionable across a few seconds of dialogue; past that it is audible.
 */
const val WatchPartyMaxNudgeRate = 0.10f

/**
 * Consecutive ticks over [WatchPartySeekThresholdMs] before a seek is taken.
 *
 * At the tick rate this is half a second of agreement. One sample can be wrong - a position read
 * taken across a frame drop, a tick that queued behind a retransmit - and a seek taken on one bad
 * sample costs the stream its buffer to fix an error that was not there.
 */
const val WatchPartySeekStreakToCorrect = 2

/**
 * A host position sample paired with the instant it was read, in party time.
 *
 * [sequence] and [contentGeneration] are carried so a tick can be matched against the durable
 * snapshot: a tick for content this client has not been told about yet is not applied, it is a
 * reason to go and ask.
 */
data class PartyTick(
    val partyId: String,
    val contentGeneration: Int,
    val sequence: Long,
    val status: WatchPartyStatus,
    val positionMs: Long,
    /** When [positionMs] was read off the host's player, on the party clock. */
    val capturedAtPartyMs: Long,
    val playbackSpeed: Float,
    val durationMs: Long,
    val sourceGeneration: Int = 0,
    val authorityEpoch: Long = 0L,
    /**
     * Members a host stall-guard hold is waiting on, while one is in force.
     *
     * The hold itself goes out as an ordinary `pause`, so without this a guest can only read it as a
     * person pausing - "Seraph paused" for something nobody pressed. Peer plane only, optional on the
     * wire, and ignored by builds that predate it; the protocol version does not move.
     */
    val hold: List<String> = emptyList(),
) {
    /**
     * Where the party is at [partyNowMs].
     *
     * Only a playing party advances. Everything else holds at the position it stopped at, which is
     * why a host that stutters must publish `buffering` and not `paused`: both freeze here, but
     * only one of them tells a guest that the frozen position is stale by construction.
     */
    fun expectedPositionMs(partyNowMs: Long): Long {
        if (status != WatchPartyStatus.playing) return positionMs.coerceAtLeast(0L)
        val elapsed = (partyNowMs - capturedAtPartyMs).coerceAtLeast(0L)
        // Double, not Float: adding a Float promotes the Long position to Float, which starts
        // quantising millisecond values after roughly 4h39m of playback.
        return (positionMs.toDouble() + elapsed.toDouble() * playbackSpeed.toDouble())
            .toLong()
            .coerceAtLeast(0L)
    }

    fun isStale(partyNowMs: Long): Boolean = partyNowMs - capturedAtPartyMs > WatchPartyTickStaleMs

    /**
     * Whether this tick says anything the held one does not.
     *
     * Ordered on the capture instant rather than on [sequence], because `party_heartbeat` moves the
     * host's position without bumping a sequence and ticks do not touch the database at all. A
     * socket can deliver two ticks out of order; the older one is then simply dropped.
     */
    fun supersedes(held: PartyTick?): Boolean = when {
        held == null -> true
        held.partyId != partyId -> true
        contentGeneration != held.contentGeneration -> contentGeneration > held.contentGeneration
        else -> capturedAtPartyMs > held.capturedAtPartyMs
    }
}

/**
 * The database anchor, for the degraded ladder.
 *
 * Still biased by whatever the host's sample age was - that is what the tick exists to fix - but
 * this is what a client has when the socket is down or the other end is an older build, and it is
 * better than nothing by five seconds.
 */
fun expectedPartyPositionMs(
    statePositionMs: Long,
    stateUpdatedAtEpochMs: Long,
    serverNowEpochMs: Long,
    status: WatchPartyStatus,
    playbackSpeed: Float,
): Long {
    if (status != WatchPartyStatus.playing) return statePositionMs.coerceAtLeast(0L)
    val elapsed = (serverNowEpochMs - stateUpdatedAtEpochMs).coerceAtLeast(0L)
    return (statePositionMs.toDouble() + elapsed.toDouble() * playbackSpeed.toDouble())
        .toLong()
        .coerceAtLeast(0L)
}

/**
 * The bands used against the database anchor, which is the wider, older, biased one.
 *
 * Two policies for two qualities of evidence, and the difference between them is not a preference.
 * The tick knows when it was taken, so a 200ms gap measured against it is a 200ms gap. The database
 * row does not: its position and its timestamp were taken at different instants, and the difference
 * between them - a host sample age plus an uplink - is baked into every reading. Correcting a
 * measurement that is systematically wrong by several hundred milliseconds to a tolerance of two
 * hundred is how a guest ends up seeking at every poll. These are the values the feature shipped
 * with, kept for the path they were right for.
 */
const val WatchPartyFallbackDeadbandMs = 750L
const val WatchPartyFallbackSeekThresholdMs = 4_000L

/**
 * How far ahead of the party a corrective seek aims **on the fallback path only**.
 *
 * Seeking to where the party is now lands the guest where the party was by the time the reload
 * finishes: the shared clock runs on through it. On the tick path this is solved by scheduling the
 * resume instead of guessing; here there is nothing accurate enough to schedule against, so the old
 * estimate stands.
 */
const val WatchPartyFallbackSeekLeadMs = 2_500L

enum class DriftCorrectionKind { NONE, TEMPORARY_SPEED, SEEK }

data class DriftCorrection(
    val kind: DriftCorrectionKind,
    val targetPositionMs: Long,
    val temporarySpeed: Float? = null,
    val restoreSpeed: Float,
)

/**
 * Chooses how a guest closes the gap between where it is and where the party says it should be.
 *
 * The [DriftCorrectionKind.SEEK] target is the expected position **exactly**, with no lead. The old
 * policy added a fixed 2.5s because seeking to where the party is now lands the guest where the
 * party was by the time the reload finishes - a real problem, solved here by not guessing: a
 * corrective seek is scheduled through [partySeekPlan], which holds the guest paused until the
 * party actually reaches the position it seeked to. Being wrong about how long a reload takes then
 * costs a slightly longer or shorter pause, never a position error.
 */
fun partyDriftCorrection(
    localPositionMs: Long,
    expectedPositionMs: Long,
    sharedSpeed: Float,
    /**
     * Whether this guest is already nudging. The band it has to fall back under is narrower than the
     * one that started it, which is what stops the correction settling at its own threshold.
     */
    alreadyNudging: Boolean = false,
): DriftCorrection {
    val drift = expectedPositionMs - localPositionMs
    val magnitude = abs(drift)
    val releaseBand = if (alreadyNudging) WatchPartyDriftDeadbandMs else WatchPartyDriftNudgeEntryMs
    return when {
        magnitude <= releaseBand ->
            DriftCorrection(DriftCorrectionKind.NONE, expectedPositionMs, restoreSpeed = sharedSpeed)
        magnitude <= WatchPartySeekThresholdMs -> DriftCorrection(
            kind = DriftCorrectionKind.TEMPORARY_SPEED,
            targetPositionMs = expectedPositionMs,
            temporarySpeed = partyNudgeSpeed(drift, sharedSpeed),
            restoreSpeed = sharedSpeed,
        )
        else -> DriftCorrection(
            kind = DriftCorrectionKind.SEEK,
            targetPositionMs = expectedPositionMs,
            restoreSpeed = sharedSpeed,
        )
    }
}

/**
 * The playback rate that closes [driftMs] over [WatchPartyNudgeWindowMs] while the party goes on
 * advancing at [sharedSpeed] - so the guest has to cover the party's advance *and* the gap.
 *
 * Clamped as a fraction of the shared speed rather than in absolute terms, so a party watching at
 * 1.5x is nudged by the same proportion a party at 1x is.
 */
internal fun partyNudgeSpeed(driftMs: Long, sharedSpeed: Float): Float {
    val rate = (driftMs.toFloat() / WatchPartyNudgeWindowMs)
        .coerceIn(-WatchPartyMaxNudgeRate, WatchPartyMaxNudgeRate)
    return (sharedSpeed * (1f + rate)).coerceIn(0.25f, 4f)
}

/**
 * Carries the one thing a correction decision cannot read off a single sample: whether the gap is
 * still there.
 */
data class DriftTracker(val seekStreak: Int = 0, val nudging: Boolean = false) {
    /**
     * The correction for this tick, with the seek held back until the gap has been seen twice.
     *
     * A gap wide enough to seek for that closes on its own by the next tick was a measurement, not
     * a drift, and seeking for it costs the stream its buffer for nothing.
     *
     * A member rather than an extension, so a caller cannot reach it without the receiver and
     * cannot resolve `next` to something else entirely - which is exactly what a missing import
     * made the compiler do the first time this was written.
     */
    fun next(localPositionMs: Long, expectedPositionMs: Long, sharedSpeed: Float): DriftOutcome {
        val correction = partyDriftCorrection(
            localPositionMs = localPositionMs,
            expectedPositionMs = expectedPositionMs,
            sharedSpeed = sharedSpeed,
            alreadyNudging = nudging,
        )
        if (correction.kind != DriftCorrectionKind.SEEK) {
            return DriftOutcome(
                correction = correction,
                tracker = DriftTracker(
                    seekStreak = 0,
                    nudging = correction.kind == DriftCorrectionKind.TEMPORARY_SPEED,
                ),
            )
        }
        val streak = seekStreak + 1
        if (streak < WatchPartySeekStreakToCorrect) {
            // Nudge as hard as the policy allows meanwhile, so the half second spent confirming the
            // gap is not also spent ignoring it.
            return DriftOutcome(
                correction = DriftCorrection(
                    kind = DriftCorrectionKind.TEMPORARY_SPEED,
                    targetPositionMs = expectedPositionMs,
                    temporarySpeed = partyNudgeSpeed(expectedPositionMs - localPositionMs, sharedSpeed),
                    restoreSpeed = sharedSpeed,
                ),
                tracker = DriftTracker(seekStreak = streak, nudging = true),
            )
        }
        // The seek restores the shared speed on the way through, so the next pass starts from a
        // player that is not nudging - saying otherwise would hand it the narrow release band for a
        // correction it is not making.
        return DriftOutcome(correction, DriftTracker(seekStreak = 0, nudging = false))
    }
}

data class DriftOutcome(val correction: DriftCorrection, val tracker: DriftTracker)

/**
 * The correction taken against the database anchor when no timeline is arriving.
 *
 * Reached when the socket is down, or when the other end is an older build that publishes no ticks.
 * A party in this state is following along a few seconds at a time rather than not following at
 * all, and the bands say so.
 */
fun partyFallbackDriftCorrection(
    localPositionMs: Long,
    expectedPositionMs: Long,
    sharedSpeed: Float,
): DriftCorrection {
    val drift = expectedPositionMs - localPositionMs
    val magnitude = abs(drift)
    return when {
        magnitude <= WatchPartyFallbackDeadbandMs ->
            DriftCorrection(DriftCorrectionKind.NONE, expectedPositionMs, restoreSpeed = sharedSpeed)
        magnitude <= WatchPartyFallbackSeekThresholdMs -> DriftCorrection(
            kind = DriftCorrectionKind.TEMPORARY_SPEED,
            targetPositionMs = expectedPositionMs,
            temporarySpeed = partyNudgeSpeed(drift, sharedSpeed),
            restoreSpeed = sharedSpeed,
        )
        // Only lead when behind. A guest that is ahead is about to lose time to the stall anyway,
        // so leading it further would overshoot in the direction it is already going.
        else -> DriftCorrection(
            kind = DriftCorrectionKind.SEEK,
            targetPositionMs = expectedPositionMs + if (drift > 0) WatchPartyFallbackSeekLeadMs else 0L,
            restoreSpeed = sharedSpeed,
        )
    }
}
