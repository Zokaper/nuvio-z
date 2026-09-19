package com.nuvio.app.features.watchparty

/**
 * Transport that happens at an instant everybody agrees on, instead of as fast as each machine can
 * manage.
 *
 * The jump on pause and resume was never a correction fault - it was the absence of a shared
 * instant. The host pressed play at position P and published it; the guest received it a
 * propagation delay later, computed `P + delay`, and jumped forward by exactly the delay it had
 * just spent waiting. On pause the same thing ran backwards: the guest played on until the pause
 * arrived and was then seeked back. Both are structural, and no tightening of the correction bands
 * removes either.
 *
 * A barrier is the fix. Play and seek carry the position **and the party instant at which to be
 * playing it**; every client, the host included, parks on that frame and starts when the party
 * clock says to. Nobody catches up, because nobody was behind.
 *
 * Pause is the deliberate exception and carries no lead: the host pauses under its own finger and
 * guests pause on receipt, then align while paused. A correction under a pause is one frame
 * changing rather than a jump, and it lands before the next resume - so the resume is clean.
 * Pausing 60ms apart is worth far more than pausing 300ms late together.
 *
 * Import-free, so `scripts/run-pure-suites.sh` runs this file rather than a copy of it.
 */

/**
 * Floor on the barrier lead, and in practice the lead itself.
 *
 * Was 250ms, and the 2026-09-10 two-client run says that number was measured against the wrong
 * path. The lead is sized from the worst round trip anyone has *reported*, and every round trip
 * this feature measures is a peer-plane broadcast between two clients. A command does not travel
 * that way: it goes through `party_submit_command_v2`, which locks the row and authors the
 * broadcast itself, so the hop is client to server to member with an RPC in the middle. The peer
 * plane never sees it, so `worstRtt / 2 + margin` came out under the floor every single time and
 * the floor was the whole answer - `leadMs=250` on all twenty-one commands of that run.
 *
 * What that cost is measurable in the same logs. All forty commands arrived *after* their own
 * barrier: median 180ms late, 652ms at the worst. A late barrier holds for nothing and carries its
 * overshoot into the target, so a resume that should have been "start on this frame" became a
 * forward seek on twenty-four of thirty-five, and a forward seek costs the decoder a flush and a
 * refill - 11:27:22 shows a 315ms corrective seek take the guest from 166ms out to 397ms out, then
 * three and a half seconds of nudging to get back inside the deadband. That excursion is the
 * "half a second apart after a resume" the physical run reported; steady state in the same logs
 * sits at 11-65ms.
 *
 * 550ms is where the measured distribution stops paying: it takes the commands needing no
 * corrective seek at all from 11 of 35 to 31 of 35, and the worst overshoot from 652ms to 352ms.
 * Past it the curve flattens and the only thing still growing is how long the host waits for its
 * own play button. Still well under [WatchPartyBarrierMaxLeadMs], which is the bound on exactly
 * that. Pause is unaffected - it carries no lead by design - so nothing about stopping got slower.
 *
 * This is a floor sized from one run on one pair of machines, not a measurement the feature takes.
 * The honest fix is for a receiver to report the lateness it actually saw so the sender can size
 * the next lead from it; that is a peer-plane protocol addition and deliberately not done here.
 */
const val WatchPartyBarrierMinLeadMs = 550L

/** Ceiling on the barrier lead. Past this the host feels their own button lag. */
const val WatchPartyBarrierMaxLeadMs = 800L

/** Headroom over the worst observed one-way latency, for the receiver's own scheduling jitter. */
const val WatchPartyBarrierLatencyMarginMs = 80L

/**
 * Position error tolerated before a barrier takes a seek to close it.
 *
 * A seek costs the stream its buffer, so anything the drift nudge can absorb after the start is
 * cheaper closed that way. Deliberately wider than [WatchPartyPausedAlignToleranceMs], because that
 * one is paid while paused.
 */
const val WatchPartyBarrierAlignToleranceMs = 120L

/** Position error tolerated on a pause. Closing it costs one frame, so the bar is lower. */
const val WatchPartyPausedAlignToleranceMs = 100L

/**
 * How long a corrective seek is given to reload before the guest resumes.
 *
 * Not an estimate of the reload cost that has to be right. The guest seeks to where the party will
 * be at the end of this hold and waits there, so a hold that is too long costs a slightly longer
 * pause and a hold that is too short costs the overrun - neither puts a standing error into the
 * position, which is what the old fixed 2.5s seek lead did whenever it guessed wrong.
 */
const val WatchPartySeekRecoveryLeadMs = 1_500L

/** Commands remembered for de-duplication. A two hour party issues a few hundred at most. */
const val WatchPartyCommandLogSize = 64

/**
 * How close a seek has to land before it counts as having happened.
 *
 * An exact seek lands within a frame, so this is slack for the position read rather than for the
 * seek: on desktop the position comes from mpv's `time-pos`, which does not advance while a seek is
 * in flight, and a playing party has moved on by a poll interval by the time it does.
 */
const val WatchPartySeekLandedToleranceMs = 250L

/** How long a seek is given to land before the correction gives up and lets the nudge have it. */
const val WatchPartySeekLandingTimeoutMs = 3_000L

/** How often the landing is checked. Short, because everything downstream is waiting on it. */
const val WatchPartySeekLandingPollMs = 50L

/**
 * How long a guest may be buffering before the host holds the party for it.
 *
 * Short enough that nobody watches on alone through someone else's stall, long enough that a
 * routine one-second rebuffer on a torrent source does not stop the film for everyone.
 *
 * **Strictly greater than the longest hold a guest takes on its own account**
 * ([WatchPartySeekRecoveryLeadMs] plus a landing), because those two being equal is what made the
 * host pause and resume the party on every corrective seek: the guest reported `buffering` for
 * exactly the grace, so the guard fired every time and cleared ~300ms later.
 */
const val WatchPartyGuestBufferingGraceMs = 2_500L

/**
 * How long a guest has to be *ready* again - not merely "no longer buffering" - before the host
 * starts the party back up.
 *
 * A guest that has finished aligning but is still starved reports `buffering`, and reading that as
 * recovery is what turned one stall into a pause, a resume, and another stall.
 */
const val WatchPartyStallRecoverySettleMs = 400L

/**
 * How often the host re-reads the stall watch when no message has arrived to move it.
 *
 * Both edges of a hold are elapsed time rather than a message, and the *release* edge has no
 * message behind it at all: the last thing a recovering guest sends is the status that starts the
 * settle, and by construction the settle has not run out yet when it lands. Driven only by
 * incoming telemetry, the release therefore waited for the guest's next liveness ping -
 * [WatchPartyClockPingIntervalMs] - which is more than ten times the settle and is what the
 * 2026-09-10 run reported as the party never starting again on its own. Well under
 * [WatchPartyStallRecoverySettleMs], so the settle is what decides the release rather than this.
 */
const val WatchPartyStallWatchPollMs = 150L

/** The least time between two automatic holds, so one flapping source cannot machine-gun the party. */
const val WatchPartyStallHoldCooldownMs = 5_000L

/** Holds inside [WatchPartyStallHoldWindowMs] after which the host stops holding for this content. */
const val WatchPartyStallHoldLimit = 3
const val WatchPartyStallHoldWindowMs = 30_000L

/**
 * The longest a single member may hold the party before the party goes on without them.
 *
 * A member that stops answering - a closed laptop, a socket that died without a close - is
 * indistinguishable from one that is buffering, and waiting for it forever is a dead party rather
 * than a considerate one.
 */
const val WatchPartyStallHoldMaxMs = 30_000L

enum class PartyCommandKind { play, pause, seek, speed }

/**
 * A transport action, addressed to an instant rather than to a moment of arrival.
 *
 * [counter] is per sender and strictly increasing, which is what lets a client drop a command that
 * overtook a newer one on the socket. [issuedByProfileId] is checked against the host named by the
 * durable snapshot before anything is applied - a payload cannot promote itself.
 */
data class PartyCommand(
    val commandId: String,
    val kind: PartyCommandKind,
    val issuedByProfileId: String,
    val counter: Long,
    val contentGeneration: Int,
    val startPositionMs: Long,
    /** The party instant at which to be at [startPositionMs]. Ignored for [PartyCommandKind.pause]. */
    val startAtPartyMs: Long,
    val playbackSpeed: Float,
    /**
     * Whether playback runs once the barrier is reached.
     *
     * Carried rather than assumed, and it is a [PartyCommandKind.seek] that needs it: scrubbing a
     * *paused* party is the ordinary way of finding a scene, and a seek that resumes everybody
     * turns it into a scrub-and-play. It reached the field - the host paused, dragged the bar, and
     * the film started again on every member. Defaults true so a command from a build that does not
     * send it behaves the way that build's own barrier did.
     */
    val playAfter: Boolean = true,
    val sourceGeneration: Int = 0,
    val authorityEpoch: Long = 0L,
)

/**
 * What this client does about a command, and when.
 *
 * [seekToMs] is null when the local position is already close enough to be left alone, which is the
 * ordinary case for a resume: everyone is parked where the pause left them.
 */
data class PartyBarrierPlan(
    val kind: PartyCommandKind,
    val seekToMs: Long?,
    val holdMs: Long,
    val playAfter: Boolean,
    val speed: Float,
)

/** The lead the host puts on a barrier, from the worst round trip it has measured. */
fun watchPartyBarrierLeadMs(maxOneWayLatencyMs: Long): Long =
    (maxOneWayLatencyMs + WatchPartyBarrierLatencyMarginMs)
        .coerceIn(WatchPartyBarrierMinLeadMs, WatchPartyBarrierMaxLeadMs)

/**
 * Turns a command into what this client should do about it.
 *
 * A command whose barrier has already passed - a slow socket, a client that was busy reloading - is
 * not dropped and is not applied at its stale position either. The overshoot is carried into the
 * target, so a late client lands where the party is *now* and starts immediately, which is the same
 * answer the drift policy would have reached one tick later and several hundred milliseconds worse.
 */
fun partyBarrierPlan(
    command: PartyCommand,
    localPositionMs: Long,
    partyNowMs: Long,
): PartyBarrierPlan {
    val speed = command.playbackSpeed
    if (command.kind == PartyCommandKind.pause) {
        val aligned = kotlin.math.abs(localPositionMs - command.startPositionMs) <= WatchPartyPausedAlignToleranceMs
        return PartyBarrierPlan(
            kind = PartyCommandKind.pause,
            seekToMs = if (aligned) null else command.startPositionMs,
            holdMs = 0L,
            playAfter = false,
            speed = speed,
        )
    }
    val hold = (command.startAtPartyMs - partyNowMs).coerceAtLeast(0L)
    val overshoot = (partyNowMs - command.startAtPartyMs).coerceAtLeast(0L)
    if (command.kind == PartyCommandKind.speed) {
        return PartyBarrierPlan(
            kind = PartyCommandKind.speed,
            seekToMs = null,
            holdMs = hold,
            playAfter = true,
            speed = speed,
        )
    }
    // A seek that is not going to resume is not late in any meaningful sense - nothing is running
    // for the overshoot to have advanced - so it lands exactly where it was aimed.
    val target = if (command.playAfter) {
        command.startPositionMs + (overshoot.toDouble() * speed.toDouble()).toLong()
    } else {
        command.startPositionMs
    }
    val aligned = kotlin.math.abs(localPositionMs - target) <= WatchPartyBarrierAlignToleranceMs
    return PartyBarrierPlan(
        kind = command.kind,
        seekToMs = if (aligned) null else target,
        holdMs = hold,
        playAfter = command.playAfter,
        speed = speed,
    )
}

/** A corrective seek, expressed the same way a host command is: a position and when to be at it. */
data class PartySeekPlan(val seekToMs: Long, val resumeAtPartyMs: Long)

/**
 * Where to seek so that resuming after the reload lands in sync rather than behind it.
 *
 * The caller clamps [PartySeekPlan.seekToMs] against its own duration - the tick carries the
 * host's, and a guest on a differently cut file must not be seeked past its own end.
 */
fun partySeekPlan(
    tick: PartyTick,
    partyNowMs: Long,
    recoveryLeadMs: Long = WatchPartySeekRecoveryLeadMs,
): PartySeekPlan {
    val resumeAt = partyNowMs + recoveryLeadMs
    return PartySeekPlan(seekToMs = tick.expectedPositionMs(resumeAt), resumeAtPartyMs = resumeAt)
}

/**
 * What has already been obeyed.
 *
 * Two guards, because they catch different things: the identifier catches the same command arriving
 * twice - the socket redelivering, or the durable snapshot confirming what the broadcast already
 * did - and the per-sender counter catches an older command arriving after a newer one.
 */
data class PartyCommandLog(
    val appliedIds: List<String> = emptyList(),
    val lastCounterByProfile: Map<String, Long> = emptyMap(),
) {
    fun accepts(command: PartyCommand): Boolean {
        if (command.commandId in appliedIds) return false
        val last = lastCounterByProfile[command.issuedByProfileId] ?: return true
        return command.counter > last
    }

    fun record(command: PartyCommand): PartyCommandLog = PartyCommandLog(
        appliedIds = (appliedIds + command.commandId).takeLast(WatchPartyCommandLogSize),
        lastCounterByProfile = lastCounterByProfile + (command.issuedByProfileId to command.counter),
    )
}

/**
 * A seek this client has issued and is waiting to see land.
 *
 * The position a correction is measured against comes from the engine, and on desktop that is mpv's
 * `time-pos`, which does not advance while a seek is in flight and can read *backwards* to a sample
 * from seconds ago. Evaluating drift against it is how one corrective seek became seven: each pass
 * measured the pre-seek position, called it a fresh gap, and seeked again to a target further ahead
 * than the last one. Nothing is measured or issued while one of these is outstanding.
 *
 * It expires on its own. The alternative - a flag some path has to remember to clear - is a wedged
 * party the first time an exception, a content change or a released player skips the clear.
 */
data class PendingPartySeek(
    val targetMs: Long,
    val issuedAtMs: Long,
    val deadlineAtMs: Long,
) {
    fun hasLanded(positionMs: Long): Boolean =
        kotlin.math.abs(positionMs - targetMs) <= WatchPartySeekLandedToleranceMs

    fun isOutstanding(nowMs: Long, positionMs: Long): Boolean =
        nowMs < deadlineAtMs && !hasLanded(positionMs)

    fun timedOut(nowMs: Long, positionMs: Long): Boolean =
        nowMs >= deadlineAtMs && !hasLanded(positionMs)
}

fun pendingPartySeek(
    targetMs: Long,
    nowMs: Long,
    timeoutMs: Long = WatchPartySeekLandingTimeoutMs,
): PendingPartySeek = PendingPartySeek(
    targetMs = targetMs,
    issuedAtMs = nowMs,
    deadlineAtMs = nowMs + timeoutMs,
)

/**
 * Who the host is waiting for, when the party waits for everyone.
 *
 * A guest that stalls used to be left behind and then dragged back by a seek, which on a torrent or
 * debrid source is most of what a party feels like. Holding for them instead costs the people who
 * are fine a pause they can see the reason for.
 *
 * The transition is in [advance] rather than in the query, because both edges are about elapsed time
 * rather than about a message: a stall becomes a hold when the grace runs out, and a hold ends when
 * the guest is *ready* again for the settle. Reading "no longer buffering" as recovery is what made
 * one stall produce a pause, a resume, and another stall a moment later - the guest was parked on
 * the right frame and not yet running, which is neither.
 *
 * "Ready" is `playing` for a member the party is not holding, and `playing` **or** a *full* `paused`
 * for one it is. That second case is not a loosening, it is the only reading that terminates:
 * holding a member pauses the party, the member obeys, and it then reports `paused` for as long as
 * the hold lasts. Demanding `playing` of a member the host has just stopped is a condition it cannot
 * meet, and the 2026-09-10 two-client run is what that costs - the guest finished buffering, said
 * so, and the party sat paused until somebody pressed play.
 *
 * ⚠ **"Full" is [PartyPeerStatusMessage.starved], and it has to be, because `paused` on its own is
 * not evidence of anything.** This doc used to claim a starving member says `buffering` instead,
 * since `partyStatusFor` tests `isLoading` before the transport. That is false, and the S25 run of
 * 2026-09-19 is the proof: `isLoading` is starvation measured *against an intent to play*, so the
 * moment the host's own stall hold pauses a starving guest, the guest stops reporting `buffering`
 * and reports `paused` - full or empty alike. The host then read its own command coming back as
 * recovery. It held for 1.27 s, resumed onto a guest still frozen at 12012 ms, spent the one hold
 * its budget allowed, and played on for another 26 s while the guest's watchdog gave up and threw
 * away the party's only source.
 *
 * So a hold may not end on a status the hold itself produced. `starved` is buffer occupancy, which
 * no command can change, and a `paused` member that is still empty stays counted as buffering here -
 * it is released by recovery or by [WatchPartyStallHoldMaxMs], never by obedience.
 */
data class GuestBufferingWatch(
    val bufferingSinceByProfile: Map<String, Long> = emptyMap(),
    val readySinceByProfile: Map<String, Long> = emptyMap(),
    val heldSinceByProfile: Map<String, Long> = emptyMap(),
    /**
     * Members who are starting up, and the party instant until which their buffering is start-up
     * rather than a stall. See [WatchPartyStartupStallGraceMs].
     */
    val startupGraceUntilByProfile: Map<String, Long> = emptyMap(),
) {
    /** Members the host is holding the party for right now. */
    val holdingProfiles: List<String> get() = heldSinceByProfile.keys.sorted()

    /**
     * Folds one member's report in.
     *
     * [starved] is the engine's own "nothing left to play", independent of [status] and of any
     * command in flight. A `paused` that is still starved is not a recovery and is not a person
     * pressing pause: it is a stall wearing the party's own pause, and it is counted as the stall
     * it is. Every other status means what it always meant; see the class doc for why this one
     * fact had to come over the wire rather than be inferred here.
     */
    fun observe(
        profileId: String,
        status: WatchPartyStatus,
        partyNowMs: Long,
        starved: Boolean = false,
    ): GuestBufferingWatch =
        // Normalised once, at the edge, so every branch below reads a status that means what it
        // says. Folding the test into the `paused` branch instead would leave a starved member out
        // of `bufferingSinceByProfile`, and a stall the guard can never see start is a stall it can
        // never hold for.
        when (if (status == WatchPartyStatus.paused && starved) WatchPartyStatus.buffering else status) {
            WatchPartyStatus.buffering -> copy(
                bufferingSinceByProfile = if (bufferingSinceByProfile.containsKey(profileId)) {
                    bufferingSinceByProfile
                } else {
                    bufferingSinceByProfile + (profileId to partyNowMs)
                },
                readySinceByProfile = readySinceByProfile - profileId,
            )
            WatchPartyStatus.playing -> copy(
                bufferingSinceByProfile = bufferingSinceByProfile - profileId,
                readySinceByProfile = markReady(profileId, partyNowMs),
            )
            // A `paused` that reached here is a *full* one - a starved one was read as the stall it
            // is above - and it is still two different facts depending on who caused it.
            //
            // From a member the party is holding, it is the party's own pause arriving back: the
            // host stopped everyone for this member, so demanding that it start playing again
            // before the hold may end is asking it to disobey the command that is holding it. It
            // has a buffer again, which is the fact the hold was waiting for, so the recovery clock
            // starts here and the hold ends on the ordinary settle.
            //
            // From a member nobody is holding, it is a person who pressed pause, and it is neither
            // a stall nor a recovery. Any hold that is standing stays standing on its own timer.
            WatchPartyStatus.paused -> copy(
                bufferingSinceByProfile = bufferingSinceByProfile - profileId,
                readySinceByProfile = if (heldSinceByProfile.containsKey(profileId)) {
                    markReady(profileId, partyNowMs)
                } else {
                    readySinceByProfile - profileId
                },
            )
            // A lobby, an ended party: not a stall, and not a recovery either. A member sitting
            // here keeps an existing hold alive until [WatchPartyStallHoldMaxMs] releases it,
            // rather than ending one it has not actually come back from.
            else -> copy(
                bufferingSinceByProfile = bufferingSinceByProfile - profileId,
                readySinceByProfile = readySinceByProfile - profileId,
            )
        }

    /** Starts the recovery clock, or leaves a running one alone so the settle is not restarted. */
    private fun markReady(profileId: String, partyNowMs: Long): Map<String, Long> =
        if (readySinceByProfile.containsKey(profileId)) {
            readySinceByProfile
        } else {
            readySinceByProfile + (profileId to partyNowMs)
        }

    /**
     * Promotes stalls that have outlasted the grace into holds, and releases holds that have
     * recovered - or that have outlasted [WatchPartyStallHoldMaxMs], whether the member went quiet
     * or simply never refilled. Past that ceiling, holding for them is just a dead party.
     */
    fun advance(partyNowMs: Long): GuestBufferingWatch {
        val next = heldSinceByProfile.toMutableMap()
        for ((profileId, since) in bufferingSinceByProfile) {
            // A member still starting up is not stalling. Once the start-up grace ends, a stall still
            // has to outlast the ordinary grace from *that* instant, so the end of the grace never
            // turns ten seconds of opening into an instant hold.
            val stallSince = maxOf(since, startupGraceUntilByProfile[profileId] ?: Long.MIN_VALUE)
            if (partyNowMs - stallSince < WatchPartyGuestBufferingGraceMs) continue
            if (!next.containsKey(profileId)) next[profileId] = partyNowMs
        }
        // ⚠ **The ceiling has to outrank "still stalling", or a member that never recovers holds
        // the party for the rest of the film.** It used to be reachable only by a member that had
        // stopped reporting a stall, which was survivable while a starving guest fell out of
        // `bufferingSinceByProfile` the moment the hold paused it - by the bug that made the hold
        // release itself. Now that a starved member correctly stays in that window, nothing else
        // would ever let go of it. The party is worth more than one client that cannot recover,
        // whether it is silent or merely empty.
        val abandoned = next.keys.filter { profileId ->
            partyNowMs - (next[profileId] ?: partyNowMs) >= WatchPartyStallHoldMaxMs
        }
        val released = abandoned + next.keys.filter { profileId ->
            if (profileId in abandoned) return@filter false
            if (bufferingSinceByProfile.containsKey(profileId)) return@filter false
            val readySince = readySinceByProfile[profileId]
            readySince != null && partyNowMs - readySince >= WatchPartyStallRecoverySettleMs
        }
        released.forEach { next.remove(it) }
        // A member given up on keeps no stall window, or the next `advance` would promote it
        // straight back into the hold it was just released from and the party would flap once per
        // poll. Starting its clock again is correct - a later stall is a new fact - and how often
        // a host may act on one is `StallHoldBudget`'s decision, not this one's.
        val stalls = if (abandoned.isEmpty()) {
            bufferingSinceByProfile
        } else {
            bufferingSinceByProfile - abandoned.toSet()
        }
        val grace = startupGraceUntilByProfile.filterValues { until ->
            // Kept one ordinary grace past its end, because `stallSince` above still reads it then.
            partyNowMs - until < WatchPartyGuestBufferingGraceMs
        }
        return if (
            next == heldSinceByProfile &&
            grace == startupGraceUntilByProfile &&
            stalls == bufferingSinceByProfile
        ) {
            this
        } else {
            copy(
                bufferingSinceByProfile = stalls,
                heldSinceByProfile = next,
                startupGraceUntilByProfile = grace,
            )
        }
    }

    fun forget(profileId: String): GuestBufferingWatch = copy(
        bufferingSinceByProfile = bufferingSinceByProfile - profileId,
        readySinceByProfile = readySinceByProfile - profileId,
        heldSinceByProfile = heldSinceByProfile - profileId,
        startupGraceUntilByProfile = startupGraceUntilByProfile - profileId,
    )

    /**
     * Forgets every member not in [present].
     *
     * A member who left or whose connection the server has given up on cannot be waited for, and a
     * hold standing for them would otherwise last until [WatchPartyStallHoldMaxMs] - thirty seconds
     * of a stopped party for somebody who is not coming back.
     */
    fun retainOnly(present: Set<String>): GuestBufferingWatch {
        val known = bufferingSinceByProfile.keys + readySinceByProfile.keys + heldSinceByProfile.keys +
            startupGraceUntilByProfile.keys
        val absent = known - present
        return if (absent.isEmpty()) this else absent.fold(this) { watch, profileId -> watch.forget(profileId) }
    }

    /** Extends (never shortens) the start-up grace of [profileIds] to [untilPartyMs]. */
    fun graceStartup(profileIds: Collection<String>, untilPartyMs: Long): GuestBufferingWatch {
        if (profileIds.isEmpty()) return this
        val next = startupGraceUntilByProfile.toMutableMap()
        profileIds.forEach { id -> next[id] = maxOf(next[id] ?: Long.MIN_VALUE, untilPartyMs) }
        return if (next == startupGraceUntilByProfile) this else copy(startupGraceUntilByProfile = next)
    }

    /**
     * What a party start keeps: nothing about who was buffering before it, but the start-up grace of
     * members who are still opening. A reset that dropped the grace would let the very first play -
     * or a user's pause and play a second later - hand a joiner's cold start straight to the guard.
     */
    fun resetKeepingStartupGrace(): GuestBufferingWatch = GuestBufferingWatch(
        startupGraceUntilByProfile = startupGraceUntilByProfile,
    )
}

/**
 * How long, once a member has a source, the stall guard leaves their buffering alone.
 *
 * ⚠ **Post-release Bug 3 (2026-09-17): a friend joining from Watching Now made the host's film go
 * pause, play, pause, play, pause, play.** The host log of that run names every transition: the
 * start gate paused the host at the join and released it when the guest reported `ready`, then the
 * stall guard paused and resumed the party twice more inside ten seconds, each time for the same
 * guest - whose freshly opened, cold stream rebuffered at 08:53:23, 29 and 34. Those were not stalls
 * in a film everybody was watching; they were one member's stream filling for the first time, and the
 * guard's 400 ms "recovered" settle released each hold into the next rebuffer. A member who is
 * starting up is caught up by its own drift correction instead - which moves only that member.
 */
const val WatchPartyStartupStallGraceMs = 10_000L

/**
 * The longest the start barrier waits, after every member has a source, for them to be able to play.
 *
 * `ready` means the member's file opened and has a duration - not that it can start. In the physical
 * run the gate released on exactly that and the guest's first real frame was still seconds away,
 * which is why the party's first act after the hold was another hold. Bounded, because a member whose
 * player never reports itself parked must not keep the party stopped: after this the barrier releases
 * on durable readiness alone, exactly as it did before, and the start-up grace absorbs the rest.
 */
const val WatchPartyStartPlaybackReadyMaxWaitMs = 12_000L

/** Whether the start barrier may release, and who it is still waiting on when it may not. */
data class PartyStartRelease(
    val release: Boolean,
    val waitingOn: List<String>,
    val timedOut: Boolean = false,
)

/**
 * The start barrier's second condition: every member it waited on can actually play.
 *
 * Called only once the durable gate has opened, so every connected member already has a source.
 * A member is playback-ready when its fresh peer status is `paused` or `playing`: `partyStatusFor`
 * reports `buffering` for as long as the engine is loading or has no first frame, so `paused` from a
 * member the party is holding means parked on a decoded frame. Members the barrier does not wait on:
 * the viewer, anyone disconnected, left or failed, and anyone who has never been in a player and says
 * nothing on the live plane - durable readiness is all there is to know about them. Without a live
 * plane there is no playback evidence to wait for, so the durable gate is the whole answer.
 */
fun partyStartPlaybackRelease(
    members: List<WatchPartyParticipant>,
    viewerProfileId: String?,
    peerTelemetry: Map<String, PartyPeerTelemetry>,
    realtimeLive: Boolean,
    partyNowMs: Long,
    durablyReadyAtPartyMs: Long,
): PartyStartRelease {
    if (!realtimeLive) return PartyStartRelease(release = true, waitingOn = emptyList())
    val waiting = members.filter { member ->
        if (member.profileId == viewerProfileId || !member.connected) return@filter false
        if (member.readyState == SourceResolutionState.left ||
            member.readyState == SourceResolutionState.failed ||
            member.readyState == SourceResolutionState.disconnected
        ) return@filter false
        val status = peerTelemetry[member.profileId]
            ?.takeIf { partyNowMs - it.receivedAtPartyMs <= WatchPartyClockStaleMs }
            ?.status
        when (status) {
            WatchPartyStatus.paused, WatchPartyStatus.playing -> false
            WatchPartyStatus.buffering -> true
            else -> member.clientLocation == WatchPartyClientLocation.player
        }
    }.map { it.profileId }.sorted()
    if (waiting.isEmpty()) return PartyStartRelease(release = true, waitingOn = emptyList())
    val timedOut = partyNowMs - durablyReadyAtPartyMs >= WatchPartyStartPlaybackReadyMaxWaitMs
    return PartyStartRelease(release = timedOut, waitingOn = waiting, timedOut = timedOut)
}

/**
 * Members whose party start is still in progress: they are working towards a source for the current
 * generation. Deliberately not `buffering` (a mid-film stall) or `disconnected`.
 */
fun partyMembersStartingUp(party: WatchPartyState, viewerProfileId: String?): List<String> =
    party.members.filter { member ->
        member.profileId != viewerProfileId && member.connected && member.readyState in PartyStartingUpStates
    }.map { it.profileId }

/** Members the stall watch may still hold for: present and connected. */
fun partyMembersPresent(party: WatchPartyState): Set<String> =
    party.members.filter {
        it.connected && it.readyState != SourceResolutionState.left && it.readyState != SourceResolutionState.disconnected
    }.map { it.profileId }.toSet()

private val PartyStartingUpStates = setOf(
    SourceResolutionState.joined,
    SourceResolutionState.waiting_for_host,
    SourceResolutionState.fetching,
    SourceResolutionState.resolving,
    SourceResolutionState.choosing_fallback,
    SourceResolutionState.source_ready,
)

/**
 * What the start barrier resumes into.
 *
 * ⚠ **The intent is the explicit one the player held when the barrier closed, never a callback from
 * inside it.** A party built from a host's playback starts `paused` on the server whatever the host
 * was doing, and the gate then pauses the host itself - so by release time every signal says "paused"
 * and only the captured intent still knows the film was running. The same capture is what keeps a
 * host who had paused before a friend joined paused afterwards: releasing used to mean `play`,
 * unconditionally.
 */
fun partyStartReleaseResumes(intentPlaying: Boolean?): Boolean = intentPlaying ?: true

/**
 * How often a host has held the party for a stalled guest, and whether it should keep doing it.
 *
 * Off is already a legitimate answer to "wait for everyone", and this is what turns it off on the
 * host's behalf when the answer stops helping: a source that flaps produces a hold, a resume, and
 * another hold, and a party spent stopping and starting is worse than one member being a second
 * behind. Deliberately per content generation - a new episode is a new stream and deserves the
 * benefit of the doubt again.
 */
data class StallHoldBudget(val holdsAtMs: List<Long> = emptyList()) {
    fun mayHold(partyNowMs: Long): Boolean {
        val recent = holdsAtMs.filter { partyNowMs - it < WatchPartyStallHoldWindowMs }
        if (recent.size >= WatchPartyStallHoldLimit) return false
        val last = recent.maxOrNull() ?: return true
        return partyNowMs - last >= WatchPartyStallHoldCooldownMs
    }

    fun exhausted(partyNowMs: Long): Boolean =
        holdsAtMs.count { partyNowMs - it < WatchPartyStallHoldWindowMs } >= WatchPartyStallHoldLimit

    fun record(partyNowMs: Long): StallHoldBudget = StallHoldBudget(
        holdsAtMs = (holdsAtMs + partyNowMs).filter { partyNowMs - it < WatchPartyStallHoldWindowMs },
    )
}
