package com.nuvio.app.features.playback

// No imports, and none may be added. This file is pure by design so it can be compiled and run
// outside Gradle (`AGENTS.md`, "Verifying without Gradle", item 2), which is the only way the
// rule below gets executed at all - it lives inside the player runtime, and no test in either
// repository can reach a composed player.

/**
 * Whether an automatically-picked source is still starting, or has to be given up on.
 *
 * **The defect this exists for.** The rule it replaces was one line: wait eight seconds, and if
 * `isPlaying` is still false and the position is still zero, call the source dead. Two things
 * were wrong with it, and they compound.
 *
 * It measured **the wrong thing**. "Has not started yet" is not "is not going to start": a debrid
 * link that has to be minted, a cold provider, or the first keyframe of a 60 GB remux can all be
 * perfectly healthy at eight seconds with a buffer visibly filling. Nothing in that check could
 * see the buffer, so a source doing exactly what it should was abandoned for it.
 *
 * And it applied **only to automatic picks**, because the watchdog is armed by
 * `onFatalPlaybackError`, which only Streamlined and Instant pass. The same file tapped by hand
 * in Classic had no deadline at all. So the two modes whose whole promise is "you do not have to
 * choose" were the only ones that threw good sources away - three in a row, one per candidate in
 * the failure chain - and then said *"No safe automatic source matched"* about a catalogue that
 * was fine. Reported from two devices on debug 19/20 as "it loads, then it tries again".
 *
 * What it measures now is **progress**, and only the absence of progress ends a play:
 *
 *  - Buffered or played milliseconds that keep climbing mean the source is working, however long
 *    it takes to show a frame. [STALL_DEADLINE_MS] runs from the last time that figure advanced,
 *    not from the start.
 *  - A source that has produced no bytes at all is given [NO_PROGRESS_DEADLINE_MS], which is much
 *    longer than the eight seconds it replaces, because it is now the only clock a slow-but-
 *    working start has to beat.
 *  - [MAX_STARTUP_MS] is the backstop for a source whose buffer creeps forever and never plays a
 *    frame. Without it, "measure progress instead" would trade a false positive for a hang, which
 *    is the worse of the two.
 *
 * None of this traps the user meanwhile: [shouldOfferManualEscape] puts the source list one tap
 * away after [MANUAL_ESCAPE_DELAY_MS], so a longer deadline costs a wait somebody can already
 * walk out of, where the old one cost the source itself.
 *
 * Pure and clock-free: the caller supplies the elapsed
 * wall-clock with each sample. **Wall-clock, never a sample count** - Android polls the player
 * every ~250 ms and desktop every 500 ms, so a count means two different things.
 */
object PlaybackStartupWatchdog {

    /**
     * How long a source that has produced nothing at all may hold the screen.
     *
     * Replaces a flat eight seconds. That figure was chosen when this check could only see
     * `isPlaying`, so it had to be short enough to catch a dead link quickly and was therefore
     * far too short to let a live one finish preparing. Now that a live one announces itself by
     * advancing [PlaybackStartupSample.progressMs], this clock only ever runs against a source
     * that has not moved a single millisecond, and it can afford to be patient.
     */
    const val NO_PROGRESS_DEADLINE_MS = 20_000L

    /**
     * How long a source that *was* progressing may sit without advancing.
     *
     * Shorter than [NO_PROGRESS_DEADLINE_MS] on purpose: a source that filled some buffer and
     * then stopped has already proved it can reach the host, so silence from it is evidence
     * rather than an absence of evidence.
     */
    const val STALL_DEADLINE_MS = 12_000L

    /**
     * The ceiling on the whole startup, however healthy each sample looks.
     *
     * For the source that trickles - a buffer advancing a few hundred milliseconds at a time over
     * a link far too slow to sustain the file. Every individual sample says "working", so
     * [STALL_DEADLINE_MS] never fires, and without this the play would never end. A minute is past
     * any start worth waiting for and well short of the point where the user has concluded the
     * app is broken.
     */
    const val MAX_STARTUP_MS = 60_000L

    /** How often the caller should sample. Fine enough that a verdict is never a poll late. */
    const val POLL_INTERVAL_MS = 1_000L

    /** One reading of a source that has been handed to the engine and has not started yet. */
    data class PlaybackStartupSample(
        /** Wall-clock since this source was handed to the engine. */
        val elapsedMs: Long,
        val isPlaying: Boolean,
        val positionMs: Long,
        val bufferedPositionMs: Long,
        val durationMs: Long,
        /**
         * Where in the file this play *began* - the resume point, or 0 for a play from the start.
         *
         * ⚠ **Without it every deadline here was dead on a resumed play, which is the most
         * common way anybody starts a video at all.** [progressMs] was the absolute furthest
         * point reached, so continuing an episode at 22 minutes made the very first sample read
         * 1_320_000 ms of "progress" before a single byte had arrived: [hasEvidenceOfLife] was
         * true immediately, `isPlaying` came back true off the pending seek - ExoPlayer returns
         * the seek target from `currentPosition` the instant `seekTo` is called, which the note
         * on [progressMs] already said - and the watchdog announced [Verdict.Started] over a
         * dead debrid link. The failure chain never ran and the player sat on the startup
         * overlay indefinitely. The `bestProgressMs <= 0L` branch below was unreachable for the
         * same reason.
         *
         * Defaults to 0, which is both the play-from-the-start case and what every caller that
         * has no resume point to declare should leave it at.
         */
        val baselineMs: Long = 0L,
    ) {
        /**
         * How far this play has moved **from where it started**.
         *
         * The **maximum** of the two, not the buffer alone: engines disagree about which moves
         * first. mpv reports a cache position before a play position; some ExoPlayer sources do
         * the opposite when the play begins with a seek to a resume point. Taking the larger
         * means either one alone counts as progress, which is the whole point.
         *
         * Measured against [baselineMs] rather than against zero, because a resume point is a
         * position the engine reports before it has fetched anything - see that field.
         */
        val progressMs: Long
            get() {
                val furthest = if (bufferedPositionMs > positionMs) bufferedPositionMs else positionMs
                val advanced = furthest - baselineMs
                return if (advanced > 0L) advanced else 0L
            }

        /**
         * Whether anything at all has come back from the host.
         *
         * A known duration counts, and it is the one signal here that is not a *quantity*: it
         * means the container header was read, so bytes arrived and were parsed. Deliberately
         * **not** used to shorten any deadline - a source stuck with a header and an empty buffer
         * is exactly a big file seeking out its first keyframe, so it keeps the patient clock.
         * It is here for the log line, which is the only thing that can tell "nothing answered"
         * apart from "it answered and then stopped" from outside a device.
         */
        val hasEvidenceOfLife: Boolean get() = progressMs > 0L || durationMs > 0L

        /**
         * Whether this play has actually moved.
         *
         * ⚠ **Only this may end the watchdog.** [hasEvidenceOfLife] used to be the sole gate on
         * [Verdict.Started], which contradicted its own documentation: a known duration is not
         * a quantity of progress, it is proof a header was parsed. A source that reads its
         * container header and then delivers nothing - while the engine reports `isPlaying` at
         * position zero with an empty buffer - was therefore declared Started on the first
         * poll. That is precisely the dead-link shape this watchdog exists to catch, and only
         * `progressMs` distinguishes it.
         *
         * Costs at most one extra sampling interval on a healthy start, because a source that
         * is really playing advances between polls by definition.
         */
        val hasAdvanced: Boolean get() = progressMs > 0L
    }

    /** What the watchdog has concluded so far. */
    enum class Verdict {
        /** Still starting. Keep sampling. */
        Waiting,

        /** Playback began. Terminal - this watchdog's job is startup and nothing else. */
        Started,

        /** Nothing is coming. Terminal - the caller advances its failure chain. */
        Abandon,
    }

    /** Why [Verdict.Abandon] was reached. One per deadline, so a log line can tell them apart. */
    enum class Reason {
        /** [NO_PROGRESS_DEADLINE_MS] passed without a single millisecond of position or buffer. */
        NeverStarted,

        /** [STALL_DEADLINE_MS] passed since the last advance, after the source had progressed. */
        Stalled,

        /** [MAX_STARTUP_MS] passed while still advancing, without ever playing a frame. */
        TooSlow,
    }

    /**
     * Carried by the caller across samples. Start from [initial] on every new source.
     *
     * [reason] is set with [Verdict.Abandon] and is the *shape* of the failure, not a message:
     * the caller turns it into a localized string, because this file may not reach the resource
     * bundle. It exists because the abandonment used to be entirely silent - no log line, and a
     * null reason into `noteSourceFailure`, so the progress overlay named a source and said
     * nothing whatever about why it had given up on it. A failure nobody can see is a failure
     * nobody can report, and this one survived three releases on exactly that.
     */
    data class State(
        val bestProgressMs: Long = 0L,
        val lastAdvanceMs: Long = 0L,
        val verdict: Verdict = Verdict.Waiting,
        val reason: Reason? = null,
    )

    fun initial(): State = State()

    /**
     * Folds one reading into [State].
     *
     * Terminal verdicts are sticky: a caller that keeps sampling past [Verdict.Started] or
     * [Verdict.Abandon] gets the same answer back, so acting on a verdict is idempotent and
     * there is no window in which a late sample un-decides a play that has already been handed
     * over or given up on.
     */
    fun observe(state: State, sample: PlaybackStartupSample): State {
        if (state.verdict != Verdict.Waiting) return state
        // Started, and *only* this. `isPlaying` on its own is true for an engine that reports
        // itself playing while stuck at zero with an empty buffer, which is precisely the shape
        // of the dead debrid link this watchdog exists for.
        if (sample.isPlaying && sample.hasAdvanced) {
            return state.copy(verdict = Verdict.Started)
        }

        val advanced = sample.progressMs > state.bestProgressMs
        val bestProgressMs = if (advanced) sample.progressMs else state.bestProgressMs
        val lastAdvanceMs = if (advanced) sample.elapsedMs else state.lastAdvanceMs

        fun abandon(reason: Reason) = State(
            bestProgressMs = bestProgressMs,
            lastAdvanceMs = lastAdvanceMs,
            verdict = Verdict.Abandon,
            reason = reason,
        )

        // Ordered dearest-first: a transfer that has run past the ceiling is [Reason.TooSlow]
        // whatever else is also true of it, and that is the one a log reader most needs told
        // apart from the other two - it is the only verdict that is about the *line* rather
        // than about the source.
        if (sample.elapsedMs >= MAX_STARTUP_MS) return abandon(Reason.TooSlow)
        if (bestProgressMs <= 0L) {
            return if (sample.elapsedMs >= NO_PROGRESS_DEADLINE_MS) {
                abandon(Reason.NeverStarted)
            } else {
                State(bestProgressMs = 0L, lastAdvanceMs = lastAdvanceMs)
            }
        }
        if (sample.elapsedMs - lastAdvanceMs >= STALL_DEADLINE_MS) return abandon(Reason.Stalled)
        return State(bestProgressMs = bestProgressMs, lastAdvanceMs = lastAdvanceMs)
    }
}
