package com.nuvio.app.features.playback

// No imports, and none may be added. Pure for the same reason as PlaybackStartupWatchdog: it is
// compiled and run outside Gradle by group 2 of scripts/run-pure-suites.sh, and replayed against
// buffer traces recorded on each engine.

/**
 * Whether a source that has **already started** is starving, and whose fault that is.
 *
 * [PlaybackStartupWatchdog] stops at its first `Started` verdict, so before this existed nothing
 * watched a play once the first frame was up: a stream that buffered every few seconds spun for as
 * long as the viewer could bear it. This is the missing half. It decides nothing about *what to
 * do* - that is `StallRecoveryPolicy` - it only answers "is this source failing the viewer?" and
 * refuses to answer yes unless it is sure.
 *
 * **Biased against yes on purpose.** The deleted P7 detector is the cautionary tale: a swap costs
 * the buffer, the track selection and sometimes a debrid re-mint, so a false "starved" is worse
 * than a late one. Hence:
 *
 *  - **A single rebuffer is never a verdict.** Only [Reason.Chronic] (several stalls close
 *    together), [Reason.Sustained] (one stall that will not end) or [Reason.Dead] (a buffer that has
 *    stopped growing) are.
 *  - **The device being offline is never the source's fault.** A stall seen while the link is down
 *    or the internet probe says offline reads [Verdict.DeviceOffline], wipes the stall history,
 *    and re-graces the source when connectivity returns: the stalls just before a drop are the
 *    drop, not the source.
 *  - **Unconfirmed connectivity is not confirmed.** A play that would be starved while
 *    [Internet.Unknown] reads [Verdict.AwaitingConnectivity] and asks for a probe
 *    ([State.wantsConnectivityProbe]). It becomes [Verdict.SourceStarved] only once the caller
 *    reports [Internet.Online].
 *  - **A seek is not a stall.** The buffering after a seek - detected by [Sample.seekGeneration]
 *    or by the playhead jumping further than wall-clock allows - reads [Verdict.SeekBuffering] for
 *    [SEEK_GRACE_MS] and counts toward nothing.
 *  - **Pause freezes every clock**, and a Watch Together hold freezes the two that measure the
 *    viewer's experience ([Reason.Chronic], [Reason.Sustained]) but not [Reason.Dead]: a held
 *    member whose buffer has stopped growing is the reason the party is held, and a hold must not
 *    make a dead source immortal (the same lesson as `PlaybackStartupWatchdog.State.hasProvenViability`).
 *
 * Like the startup watchdog it is clock-free and **wall-clock, never sample-count**: Android
 * polls every ~250 ms and desktop every 500 ms.
 */
object PlaybackStallMonitor {

    /** How long the playhead must sit still, while the viewer wants it moving, to be a stall. */
    const val STALL_FROZEN_MS = 1_500L

    /** Less than this ahead of the playhead, while the engine says it is loading, is starving. */
    const val LOW_BUFFER_AHEAD_MS = 4_000L

    /** A stall is over once the playhead has moved at roughly real time for this long. */
    const val RECOVERY_PROGRESS_MS = 3_000L

    /** [Reason.Chronic]: this many stalls ... */
    const val CHRONIC_EPISODES = 3

    /** ... starting within this window. */
    const val CHRONIC_WINDOW_MS = 120_000L

    /** [Reason.Sustained]: one stall that has held the picture this long. */
    const val SUSTAINED_STALL_MS = 15_000L

    /**
     * [Reason.Dead]: a starving buffer that has not grown at all for this long. The same figure as
     * [PlaybackStartupWatchdog.STALL_DEADLINE_MS], for the same reason: a source that has delivered
     * media and then gone silent has proved it can reach the host, so its silence is evidence.
     */
    const val DEAD_BUFFER_MS = PlaybackStartupWatchdog.STALL_DEADLINE_MS

    /** Nothing counts this soon after the first frame: engines refill aggressively at start. */
    const val FIRST_FRAME_GRACE_MS = 12_000L

    /** Nothing counts this soon after a source swap, which is a fresh start on a warm pipeline. */
    const val SWAP_GRACE_MS = 20_000L

    /** How long the buffering after a seek is the seek's, not the source's. */
    const val SEEK_GRACE_MS = 15_000L

    /** How long a source is given once connectivity returns from [Verdict.DeviceOffline]. */
    const val OFFLINE_REGRACE_MS = 10_000L

    /** A playhead move further than wall-clock allows by more than this is a seek. */
    const val SEEK_JUMP_TOLERANCE_MS = 2_500L

    /** Playhead movement smaller than this is jitter, not progress. */
    const val PROGRESS_EPSILON_MS = 100L

    /** Once a stall has run this long, ask the caller to confirm connectivity. */
    const val PROBE_AFTER_STALL_MS = 2_000L

    enum class Link { Up, Down, Unknown }

    enum class Internet { Online, Offline, Unknown }

    data class Sample(
        /** Wall-clock on any monotonic clock, as long as the caller never changes clocks. */
        val elapsedMs: Long,
        val positionMs: Long,
        val bufferedPositionMs: Long,
        /** True while the viewer (or the party) wants the picture moving: not user-paused, not ended. */
        val wantsToPlay: Boolean,
        /** `isLoading`, or an engine readiness of Buffering - either is the engine admitting it is short. */
        val engineSaysBuffering: Boolean,
        /** Bumped by the caller on every seek it issues. A change starts [SEEK_GRACE_MS]. */
        val seekGeneration: Long = 0L,
        val playbackSpeed: Double = 1.0,
        /** HLS/DASH: the engine adapts bitrate itself, so a few short stalls are its business. */
        val isManifest: Boolean = false,
        /** A downloaded file. It cannot be starved by a server, so nothing here ever blames it. */
        val isLocal: Boolean = false,
        /** Watch Together is holding this player still on purpose. */
        val isHeld: Boolean = false,
        val link: Link = Link.Unknown,
        val internet: Internet = Internet.Unknown,
    ) {
        val aheadMs: Long get() = (bufferedPositionMs - positionMs).coerceAtLeast(0L)
    }

    enum class Verdict {
        Healthy,
        /** Stalled now, but nothing about it is yet a reason to act. */
        Rebuffering,
        SeekBuffering,
        DeviceOffline,
        /** Would be [SourceStarved], but connectivity is unconfirmed. Probe, then sample again. */
        AwaitingConnectivity,
        /** Latched. The caller acts (or, in shadow mode, logs) and then calls [rearm]. */
        SourceStarved,
    }

    enum class Reason { Chronic, Sustained, Dead }

    data class State(
        val verdict: Verdict = Verdict.Healthy,
        val reason: Reason? = null,
        val lastSampleMs: Long = 0L,
        val lastPositionMs: Long = 0L,
        val lastSeekGeneration: Long = 0L,
        /** Wall-clock of the last real playhead advance, or of the last thing that reset it. */
        val lastAdvanceMs: Long = 0L,
        val lastBufferedMs: Long = 0L,
        val lastBufferGrowthMs: Long = 0L,
        /** Nothing counts before this. Moves forward on first frame, swap, seek and reconnect. */
        val graceUntilMs: Long = 0L,
        val seekGraceUntilMs: Long = 0L,
        /** Start of the current stall, or null while playing. */
        val stallStartedMs: Long? = null,
        /** Picture-frozen time inside the current stall, held time excluded. */
        val stallFrozenMs: Long = 0L,
        /** Whether the current stall has been entered into [episodeStartsMs]. */
        val stallCounted: Boolean = false,
        /** Wall-clock the playhead began moving again inside a stall that has not ended yet. */
        val recoveringSinceMs: Long? = null,
        /** Starts of counted stalls, oldest first, trimmed to [CHRONIC_WINDOW_MS]. */
        val episodeStartsMs: List<Long> = emptyList(),
        val wasOffline: Boolean = false,
        val wantsConnectivityProbe: Boolean = false,
    )

    /** A fresh monitor for a source whose first frame (or swap) happened at [nowMs]. */
    fun initial(nowMs: Long, positionMs: Long, graceMs: Long = FIRST_FRAME_GRACE_MS): State = State(
        lastSampleMs = nowMs,
        lastPositionMs = positionMs,
        lastAdvanceMs = nowMs,
        lastBufferedMs = positionMs,
        lastBufferGrowthMs = nowMs,
        graceUntilMs = nowMs + graceMs,
    )

    /** Clears a latched verdict and the history behind it, as after the caller has acted on it. */
    fun rearm(state: State, nowMs: Long, graceMs: Long = SWAP_GRACE_MS): State =
        initial(nowMs, state.lastPositionMs, graceMs).copy(lastSeekGeneration = state.lastSeekGeneration)

    fun observe(state: State, sample: Sample): State {
        if (state.verdict == Verdict.SourceStarved) return state
        val now = sample.elapsedMs
        val intervalMs = (now - state.lastSampleMs).coerceAtLeast(0L)

        // --- Seeks: a new generation, or a playhead that moved further than time allows. --------
        val expectedMoveMs = if (sample.wantsToPlay) (intervalMs * sample.playbackSpeed).toLong() else 0L
        // Forward past what time allows, or backward at all. A playhead that did *not* move is
        // never a seek, however long the gap between samples - that is a stall seen through a
        // slow poll, and treating it as a seek would reset the very stall being measured.
        val movedMs = sample.positionMs - state.lastPositionMs
        val seeked = sample.seekGeneration != state.lastSeekGeneration ||
            movedMs > expectedMoveMs + SEEK_JUMP_TOLERANCE_MS ||
            movedMs < -SEEK_JUMP_TOLERANCE_MS
        if (seeked) {
            // A seek abandons the stall in progress without counting it: whatever the viewer was
            // waiting for, they are now waiting for something else.
            return state.copy(
                verdict = if (sample.wantsToPlay && sample.engineSaysBuffering) Verdict.SeekBuffering else Verdict.Healthy,
                reason = null,
                lastSampleMs = now,
                lastPositionMs = sample.positionMs,
                lastSeekGeneration = sample.seekGeneration,
                lastAdvanceMs = now,
                lastBufferedMs = sample.bufferedPositionMs,
                lastBufferGrowthMs = now,
                seekGraceUntilMs = now + SEEK_GRACE_MS,
                stallStartedMs = null,
                stallFrozenMs = 0L,
                stallCounted = false,
                recoveringSinceMs = null,
                wantsConnectivityProbe = false,
            )
        }

        val advanced = sample.positionMs - state.lastPositionMs > PROGRESS_EPSILON_MS
        // Any rise since the last sample is growth: a trickle of a hundred milliseconds a poll is
        // still a source that is alive. Measured sample-to-sample rather than against a high-water
        // mark, because a buffer legitimately falls - consumed, discarded by the engine, re-based
        // by a seek - and a trickle climbing back from there would never pass an old peak. Jitter
        // can fake a rise; that only ever delays [Reason.Dead], which is the safe direction.
        val bufferGrew = sample.bufferedPositionMs > state.lastBufferedMs
        val lastBufferGrowthMs = if (bufferGrew || !(sample.wantsToPlay || sample.isHeld)) now else state.lastBufferGrowthMs

        // --- Device offline: never the source's fault, and it wipes what came before it. --------
        val offline = sample.link == Link.Down || sample.internet == Internet.Offline
        if (offline) {
            return State(
                verdict = if (sample.wantsToPlay && !advanced) Verdict.DeviceOffline else Verdict.Healthy,
                lastSampleMs = now,
                lastPositionMs = sample.positionMs,
                lastSeekGeneration = sample.seekGeneration,
                lastAdvanceMs = now,
                lastBufferedMs = sample.bufferedPositionMs,
                lastBufferGrowthMs = now,
                graceUntilMs = now + OFFLINE_REGRACE_MS,
                seekGraceUntilMs = state.seekGraceUntilMs,
                wasOffline = true,
            )
        }
        val graceUntilMs = if (state.wasOffline) now + OFFLINE_REGRACE_MS else state.graceUntilMs

        // --- Pause: every clock stops. A stall the viewer paused through simply ends. ----------
        if (!sample.wantsToPlay && !sample.isHeld) {
            return state.copy(
                verdict = Verdict.Healthy,
                reason = null,
                lastSampleMs = now,
                lastPositionMs = sample.positionMs,
                lastAdvanceMs = now,
                lastBufferedMs = sample.bufferedPositionMs,
                lastBufferGrowthMs = now,
                graceUntilMs = graceUntilMs,
                stallStartedMs = null,
                stallFrozenMs = 0L,
                stallCounted = false,
                recoveringSinceMs = null,
                wasOffline = false,
                wantsConnectivityProbe = false,
            )
        }

        val lastAdvanceMs = if (advanced) now else state.lastAdvanceMs
        val inGrace = now < graceUntilMs
        val inSeekGrace = now < state.seekGraceUntilMs
        val frozenForMs = now - lastAdvanceMs
        val starvingNow = !sample.isHeld &&
            frozenForMs >= STALL_FROZEN_MS &&
            (sample.engineSaysBuffering || sample.aheadMs < LOW_BUFFER_AHEAD_MS)

        // --- The current stall: when it began, how long it has frozen the picture, when it ends.
        var stallStartedMs = state.stallStartedMs
        var stallFrozenMs = state.stallFrozenMs
        var stallCounted = state.stallCounted
        var recoveringSinceMs = state.recoveringSinceMs
        // Frozen time is only charged outside every grace, so a seek that takes thirty seconds
        // to buffer is fifteen seconds of [Reason.Sustained], not an instant verdict the moment
        // [SEEK_GRACE_MS] ends.
        val charging = !inGrace && !inSeekGrace
        if (starvingNow) {
            if (stallStartedMs == null) {
                stallStartedMs = lastAdvanceMs
                stallFrozenMs = if (charging) frozenForMs else 0L
            } else if (charging) {
                stallFrozenMs += intervalMs
            }
            recoveringSinceMs = null
        } else if (stallStartedMs != null && !sample.isHeld) {
            if (advanced) {
                val since = recoveringSinceMs ?: now
                recoveringSinceMs = since
                if (now - since >= RECOVERY_PROGRESS_MS) {
                    stallStartedMs = null
                    stallFrozenMs = 0L
                    stallCounted = false
                    recoveringSinceMs = null
                }
            }
        }

        // A stall is counted only once it is outside every grace (first frame, swap, reconnect,
        // seek), and never under a hold: the hold is the party's choice, not the viewer's experience.
        var episodes = state.episodeStartsMs.filter { now - it <= CHRONIC_WINDOW_MS }
        val countable = stallStartedMs != null && !inGrace && !inSeekGrace && !sample.isHeld && !sample.isLocal
        if (countable && !stallCounted) {
            episodes = episodes + stallStartedMs!!
            stallCounted = true
        }

        val reason: Reason? = when {
            sample.isLocal -> null
            // Dead runs even under a hold, and even in grace: a buffer that has not grown for
            // twelve seconds while short is not going to be rescued by waiting longer.
            (sample.wantsToPlay || sample.isHeld) &&
                sample.aheadMs < LOW_BUFFER_AHEAD_MS &&
                now - lastBufferGrowthMs >= DEAD_BUFFER_MS &&
                !inSeekGrace -> Reason.Dead
            countable && stallFrozenMs >= SUSTAINED_STALL_MS -> Reason.Sustained
            !sample.isManifest && episodes.size >= CHRONIC_EPISODES -> Reason.Chronic
            else -> null
        }

        val verdict = when {
            reason != null && sample.internet == Internet.Online -> Verdict.SourceStarved
            reason != null -> Verdict.AwaitingConnectivity
            stallStartedMs != null && inSeekGrace -> Verdict.SeekBuffering
            stallStartedMs != null && starvingNow -> Verdict.Rebuffering
            else -> Verdict.Healthy
        }
        val wantsProbe = verdict == Verdict.AwaitingConnectivity ||
            (starvingNow && stallFrozenMs >= PROBE_AFTER_STALL_MS && sample.internet != Internet.Online)

        return State(
            verdict = verdict,
            reason = if (verdict == Verdict.SourceStarved || verdict == Verdict.AwaitingConnectivity) reason else null,
            lastSampleMs = now,
            lastPositionMs = sample.positionMs,
            lastSeekGeneration = sample.seekGeneration,
            lastAdvanceMs = lastAdvanceMs,
            lastBufferedMs = sample.bufferedPositionMs,
            lastBufferGrowthMs = lastBufferGrowthMs,
            graceUntilMs = graceUntilMs,
            seekGraceUntilMs = state.seekGraceUntilMs,
            stallStartedMs = stallStartedMs,
            stallFrozenMs = stallFrozenMs,
            stallCounted = stallCounted,
            recoveringSinceMs = recoveringSinceMs,
            episodeStartsMs = episodes,
            wasOffline = false,
            wantsConnectivityProbe = wantsProbe,
        )
    }
}
