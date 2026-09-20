package com.nuvio.app.features.playback

import com.nuvio.app.features.playback.PlaybackStartupWatchdog.PlaybackStartupSample
import com.nuvio.app.features.playback.PlaybackStartupWatchdog.Reason
import com.nuvio.app.features.playback.PlaybackStartupWatchdog.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The startup deadline for an automatically-picked source.
 *
 * Every case here fails against the rule this replaced - a flat eight seconds against
 * `isPlaying`, with no sight of the buffer - which is the point: that rule abandoned healthy
 * sources three at a time and then blamed the catalogue.
 */
class PlaybackStartupWatchdogTest {

    @Test
    fun `a buffer that keeps filling is never abandoned however long it takes`() {
        // The reported case. Eight seconds in, this source has 6s of buffer and no frame yet -
        // a debrid mint followed by a large remux seeking its first keyframe - and the old rule
        // killed it. Run it well past that, to the far side of the old deadline three times over.
        var state = PlaybackStartupWatchdog.initial()
        var elapsedMs = 0L
        while (elapsedMs < 25_000L) {
            elapsedMs += PlaybackStartupWatchdog.POLL_INTERVAL_MS
            state = PlaybackStartupWatchdog.observe(
                state,
                sample(elapsedMs = elapsedMs, bufferedPositionMs = elapsedMs * 3),
            )
        }
        assertEquals(Verdict.Waiting, state.verdict)
        assertNull(state.reason)
    }

    @Test
    fun `a position that advances counts as progress even with no buffer reported`() {
        // mpv and ExoPlayer disagree about which figure moves first, so either alone must do.
        var state = PlaybackStartupWatchdog.initial()
        var elapsedMs = 0L
        while (elapsedMs < 25_000L) {
            elapsedMs += PlaybackStartupWatchdog.POLL_INTERVAL_MS
            state = PlaybackStartupWatchdog.observe(
                state,
                sample(elapsedMs = elapsedMs, positionMs = elapsedMs / 2),
            )
        }
        assertEquals(Verdict.Waiting, state.verdict)
    }

    @Test
    fun `a source that answers nothing at all is abandoned on the patient deadline`() {
        var state = PlaybackStartupWatchdog.initial()

        state = PlaybackStartupWatchdog.observe(state, sample(elapsedMs = 8_000L))
        // Precisely where the old rule gave up, and the whole reason this exists.
        assertEquals(Verdict.Waiting, state.verdict)

        state = PlaybackStartupWatchdog.observe(
            state,
            sample(elapsedMs = PlaybackStartupWatchdog.NO_PROGRESS_DEADLINE_MS),
        )
        assertEquals(Verdict.Abandon, state.verdict)
        assertEquals(Reason.NeverStarted, state.reason)
    }

    @Test
    fun `a known duration alone does not shorten the patient deadline`() {
        // The header was read and the buffer is still empty: a big file seeking a keyframe. It
        // has said something, so it is not dead - but it has moved nothing, so it keeps the
        // longer clock rather than being held to the stall one.
        var state = PlaybackStartupWatchdog.initial()
        state = PlaybackStartupWatchdog.observe(
            state,
            sample(elapsedMs = 1_000L, durationMs = 7_200_000L),
        )
        state = PlaybackStartupWatchdog.observe(
            state,
            sample(
                elapsedMs = PlaybackStartupWatchdog.STALL_DEADLINE_MS + 1_000L,
                durationMs = 7_200_000L,
            ),
        )
        assertEquals(Verdict.Waiting, state.verdict)
    }

    @Test
    fun `a source that progresses and then stops is abandoned on the shorter deadline`() {
        var state = PlaybackStartupWatchdog.initial()
        state = PlaybackStartupWatchdog.observe(
            state,
            sample(elapsedMs = 2_000L, bufferedPositionMs = 4_000L),
        )
        state = PlaybackStartupWatchdog.observe(
            state,
            sample(elapsedMs = 2_000L + PlaybackStartupWatchdog.STALL_DEADLINE_MS - 1L, bufferedPositionMs = 4_000L),
        )
        assertEquals(Verdict.Waiting, state.verdict)

        state = PlaybackStartupWatchdog.observe(
            state,
            sample(elapsedMs = 2_000L + PlaybackStartupWatchdog.STALL_DEADLINE_MS, bufferedPositionMs = 4_000L),
        )
        assertEquals(Verdict.Abandon, state.verdict)
        assertEquals(Reason.Stalled, state.reason)
    }

    @Test
    fun `a buffer that creeps forever still ends`() {
        // Every sample says "working" - a few hundred milliseconds at a time over a line far too
        // slow for the file - so the stall clock never fires. Without the ceiling this play would
        // run until the user force-quit, which is the hang that "measure progress instead" would
        // otherwise have traded the false positive for.
        var state = PlaybackStartupWatchdog.initial()
        var elapsedMs = 0L
        while (state.verdict == Verdict.Waiting && elapsedMs < 300_000L) {
            elapsedMs += PlaybackStartupWatchdog.POLL_INTERVAL_MS
            state = PlaybackStartupWatchdog.observe(
                state,
                sample(elapsedMs = elapsedMs, bufferedPositionMs = elapsedMs / 4),
            )
        }
        assertEquals(Verdict.Abandon, state.verdict)
        assertEquals(Reason.TooSlow, state.reason)
        assertEquals(PlaybackStartupWatchdog.MAX_STARTUP_MS, elapsedMs)
    }

    @Test
    fun `playing with something behind it is started`() {
        val state = PlaybackStartupWatchdog.observe(
            PlaybackStartupWatchdog.initial(),
            sample(elapsedMs = 3_000L, isPlaying = true, positionMs = 120L),
        )
        assertEquals(Verdict.Started, state.verdict)
    }

    @Test
    fun `an engine claiming to play from nowhere is not started`() {
        // The dead debrid link's shape: the engine reports itself playing while stuck at zero
        // with an empty buffer and no duration. `isPlaying` alone would have accepted it and the
        // watchdog would never have fired at all.
        var state = PlaybackStartupWatchdog.initial()
        state = PlaybackStartupWatchdog.observe(
            state,
            sample(elapsedMs = 3_000L, isPlaying = true),
        )
        assertEquals(Verdict.Waiting, state.verdict)

        state = PlaybackStartupWatchdog.observe(
            state,
            sample(
                elapsedMs = PlaybackStartupWatchdog.NO_PROGRESS_DEADLINE_MS,
                isPlaying = true,
            ),
        )
        assertEquals(Verdict.Abandon, state.verdict)
        assertEquals(Reason.NeverStarted, state.reason)
    }

    @Test
    fun `a terminal verdict is sticky`() {
        // The caller polls in a loop and acts on the verdict; a late sample must not un-decide a
        // play that has already been handed over or given up on.
        val abandoned = PlaybackStartupWatchdog.observe(
            PlaybackStartupWatchdog.initial(),
            sample(elapsedMs = PlaybackStartupWatchdog.NO_PROGRESS_DEADLINE_MS),
        )
        assertEquals(Verdict.Abandon, abandoned.verdict)
        val late = PlaybackStartupWatchdog.observe(
            abandoned,
            sample(elapsedMs = 21_000L, isPlaying = true, positionMs = 5_000L),
        )
        assertEquals(Verdict.Abandon, late.verdict)
        assertEquals(Reason.NeverStarted, late.reason)

        val started = PlaybackStartupWatchdog.observe(
            PlaybackStartupWatchdog.initial(),
            sample(elapsedMs = 2_000L, isPlaying = true, positionMs = 500L),
        )
        val stalledAfterwards = PlaybackStartupWatchdog.observe(
            started,
            sample(elapsedMs = 90_000L),
        )
        assertEquals(Verdict.Started, stalledAfterwards.verdict)
    }

    @Test
    fun `the deadlines are ordered the way the reasons claim`() {
        // Reading order matters here: a stall deadline above the no-progress one would mean a
        // source that buffered once was given *less* patience than one that answered nothing.
        val stall = PlaybackStartupWatchdog.STALL_DEADLINE_MS
        val noProgress = PlaybackStartupWatchdog.NO_PROGRESS_DEADLINE_MS
        val evidenceOfLife = PlaybackStartupWatchdog.EVIDENCE_OF_LIFE_DEADLINE_MS
        val ceiling = PlaybackStartupWatchdog.MAX_STARTUP_MS
        assertTrue(stall < noProgress, "stall deadline must be under the no-progress one")
        assertTrue(noProgress < evidenceOfLife, "no-progress deadline must be under the evidence-of-life one")
        assertTrue(evidenceOfLife < ceiling, "the ceiling must be past all startup deadlines")
        assertTrue(
            PlaybackStartupWatchdog.POLL_INTERVAL_MS < stall,
            "a verdict must never be a whole poll late",
        )
    }

    @Test
    fun `completely dead source is abandoned on the 20s deadline without evidence of life`() {
        var state = PlaybackStartupWatchdog.initial()
        state = PlaybackStartupWatchdog.observe(state, sample(elapsedMs = 19_000L))
        assertEquals(Verdict.Waiting, state.verdict)
        assertNull(state.reason)

        state = PlaybackStartupWatchdog.observe(
            state,
            sample(elapsedMs = PlaybackStartupWatchdog.NO_PROGRESS_DEADLINE_MS),
        )
        assertEquals(Verdict.Abandon, state.verdict)
        assertEquals(Reason.NeverStarted, state.reason)
    }

    @Test
    fun `alive-but-slow source with duration or probe evidence is not abandoned at 20s`() {
        var stateWithDuration = PlaybackStartupWatchdog.initial()
        stateWithDuration = PlaybackStartupWatchdog.observe(
            stateWithDuration,
            sample(elapsedMs = 5_000L, durationMs = 7_200_000L),
        )
        stateWithDuration = PlaybackStartupWatchdog.observe(
            stateWithDuration,
            sample(elapsedMs = PlaybackStartupWatchdog.NO_PROGRESS_DEADLINE_MS, durationMs = 7_200_000L),
        )
        assertEquals(Verdict.Waiting, stateWithDuration.verdict)
        assertNull(stateWithDuration.reason)

        var stateWithProbe = PlaybackStartupWatchdog.initial()
        stateWithProbe = PlaybackStartupWatchdog.observe(
            stateWithProbe,
            sample(elapsedMs = 5_000L, hasExternalEvidenceOfLife = true),
        )
        stateWithProbe = PlaybackStartupWatchdog.observe(
            stateWithProbe,
            sample(
                elapsedMs = PlaybackStartupWatchdog.NO_PROGRESS_DEADLINE_MS,
                hasExternalEvidenceOfLife = true,
            ),
        )
        assertEquals(Verdict.Waiting, stateWithProbe.verdict)
        assertNull(stateWithProbe.reason)
    }

    @Test
    fun `alive-but-slow source starting at 24s succeeds`() {
        val resumeMs = 359_818L
        var state = PlaybackStartupWatchdog.initial()
        // Container duration read early
        state = PlaybackStartupWatchdog.observe(
            state,
            sample(elapsedMs = 5_000L, durationMs = 7_094_176L, baselineMs = resumeMs),
        )
        assertEquals(Verdict.Waiting, state.verdict)

        // Still waiting past the dead-source 20s deadline
        state = PlaybackStartupWatchdog.observe(
            state,
            sample(elapsedMs = 20_000L, durationMs = 7_094_176L, baselineMs = resumeMs),
        )
        assertEquals(Verdict.Waiting, state.verdict)

        // At 24s, keyframes finish buffering and playback advances past resume point
        state = PlaybackStartupWatchdog.observe(
            state,
            sample(
                elapsedMs = 24_000L,
                isPlaying = true,
                positionMs = resumeMs + 100L,
                bufferedPositionMs = resumeMs + 2_000L,
                durationMs = 7_094_176L,
                baselineMs = resumeMs,
            ),
        )
        assertEquals(Verdict.Started, state.verdict)
        assertNull(state.reason)
    }

    @Test
    fun `alive source still not starting by 35s is abandoned`() {
        var state = PlaybackStartupWatchdog.initial()
        state = PlaybackStartupWatchdog.observe(
            state,
            sample(elapsedMs = 2_000L, durationMs = 7_200_000L),
        )
        state = PlaybackStartupWatchdog.observe(
            state,
            sample(elapsedMs = PlaybackStartupWatchdog.EVIDENCE_OF_LIFE_DEADLINE_MS - 1L, durationMs = 7_200_000L),
        )
        assertEquals(Verdict.Waiting, state.verdict)

        state = PlaybackStartupWatchdog.observe(
            state,
            sample(
                elapsedMs = PlaybackStartupWatchdog.EVIDENCE_OF_LIFE_DEADLINE_MS,
                durationMs = 7_200_000L,
            ),
        )
        assertEquals(Verdict.Abandon, state.verdict)
        assertEquals(Reason.NeverStarted, state.reason)
    }

    @Test
    fun `actual progress followed by stall abandons on existing 12s stall deadline even with evidence of life`() {
        var state = PlaybackStartupWatchdog.initial()
        // Starts with progress and duration
        state = PlaybackStartupWatchdog.observe(
            state,
            sample(elapsedMs = 2_000L, bufferedPositionMs = 4_000L, durationMs = 7_200_000L),
        )
        assertEquals(Verdict.Waiting, state.verdict)

        // 11.9s later, still waiting
        state = PlaybackStartupWatchdog.observe(
            state,
            sample(
                elapsedMs = 2_000L + PlaybackStartupWatchdog.STALL_DEADLINE_MS - 1L,
                bufferedPositionMs = 4_000L,
                durationMs = 7_200_000L,
            ),
        )
        assertEquals(Verdict.Waiting, state.verdict)

        // Exactly at stall deadline, abandons with Reason.Stalled
        state = PlaybackStartupWatchdog.observe(
            state,
            sample(
                elapsedMs = 2_000L + PlaybackStartupWatchdog.STALL_DEADLINE_MS,
                bufferedPositionMs = 4_000L,
                durationMs = 7_200_000L,
            ),
        )
        assertEquals(Verdict.Abandon, state.verdict)
        assertEquals(Reason.Stalled, state.reason)
    }

    @Test
    fun `candidate handoff or url existence alone does not count as evidence of life`() {
        val sampleWithoutEvidence = sample(elapsedMs = 1_000L)
        assertEquals(false, sampleWithoutEvidence.hasEvidenceOfLife)
    }

    @Test
    fun `a dead source resumed at a position is abandoned, not declared started`() {
        // Continuing episode 3 at 22 minutes on a debrid link that no longer resolves. The
        // engine answers the pending seek immediately - `currentPosition` is the seek target
        // the instant `seekTo` is called - and reports itself playing, with nothing buffered
        // and no duration. Measured against zero this was 22 minutes of progress and the
        // watchdog said Started on the very first sample, so the failure chain never ran and
        // the player sat on the startup overlay for good.
        val resumeMs = 22L * 60L * 1_000L
        var state = PlaybackStartupWatchdog.initial()
        var elapsedMs = 0L
        while (elapsedMs < PlaybackStartupWatchdog.NO_PROGRESS_DEADLINE_MS) {
            elapsedMs += PlaybackStartupWatchdog.POLL_INTERVAL_MS
            state = PlaybackStartupWatchdog.observe(
                state,
                sample(
                    elapsedMs = elapsedMs,
                    isPlaying = true,
                    positionMs = resumeMs,
                    baselineMs = resumeMs,
                ),
            )
        }

        assertEquals(Verdict.Abandon, state.verdict)
        assertEquals(Reason.NeverStarted, state.reason)
    }

    @Test
    fun `a healthy source resumed at a position still starts`() {
        // The other half of the same rule: past the resume point is real progress, and this
        // must not have become harder to start than a play from zero.
        val resumeMs = 22L * 60L * 1_000L
        val state = PlaybackStartupWatchdog.observe(
            PlaybackStartupWatchdog.initial(),
            sample(
                elapsedMs = 2_000L,
                isPlaying = true,
                positionMs = resumeMs,
                bufferedPositionMs = resumeMs + 4_000L,
                baselineMs = resumeMs,
            ),
        )

        assertEquals(Verdict.Started, state.verdict)
    }

    @Test
    fun `a resumed source that fills its buffer and then stops is stalled, not started`() {
        // The stall clock was equally unreachable on a resumed play, because the first sample
        // ended the watchdog before any of it could run.
        val resumeMs = 22L * 60L * 1_000L
        var state = PlaybackStartupWatchdog.observe(
            PlaybackStartupWatchdog.initial(),
            sample(
                elapsedMs = 2_000L,
                positionMs = resumeMs,
                bufferedPositionMs = resumeMs + 4_000L,
                baselineMs = resumeMs,
            ),
        )
        assertEquals(Verdict.Waiting, state.verdict)

        state = PlaybackStartupWatchdog.observe(
            state,
            sample(
                elapsedMs = 2_000L + PlaybackStartupWatchdog.STALL_DEADLINE_MS,
                positionMs = resumeMs,
                bufferedPositionMs = resumeMs + 4_000L,
                baselineMs = resumeMs,
            ),
        )

        assertEquals(Verdict.Abandon, state.verdict)
        assertEquals(Reason.Stalled, state.reason)
    }

    // ---------------------------------------------------------------- Watch Together holds
    //
    // Physically reproduced on two clients, and the whole reason `isHeld` exists: a guest's source
    // loads slowly, the watchdog arms, Watch Together holds the guest at the readiness gate while
    // it waits for the party, the host starts and then pauses again before the guest's first frame
    // has settled. The guest sits deliberately paused at 7257ms, its buffer stops advancing because
    // a paused player has nothing to advance for, and twelve seconds later `Reason.Stalled` fired,
    // `onFatalPlaybackError` ran, and the guest was failed over and popped back to the source list -
    // out of a party that was working.

    // ---------------------------------------------------------------- A hold is not immortality
    //
    // The other half of the same mechanism, reproduced on an Android host on 2026-09-20 and the
    // reason `hasProvenViability` exists. The party pinned one candidate; its URL served a 21 KB
    // placeholder claiming 59.4 GB; the host's own start gate held it at WAITING_FOR_PARTICIPANTS
    // while it waited for the guest, and the guest waited for the host. Because every deadline was
    // frozen by the hold, nothing in this file could ever end it: the source could not fail, so the
    // chain could not run, so the party could not move. Seven minutes, and it was still going when
    // the run was stopped by hand.

    @Test
    fun `a start held at the party gate with nothing behind it still reaches its deadline`() {
        // Nothing has arrived and the party is holding - the exact shape of the placeholder above.
        // The hold does not stop a clock this source has not earned the right to stop.
        var state = PlaybackStartupWatchdog.initial()
        var elapsedMs = 0L
        while (state.verdict == Verdict.Waiting && elapsedMs < MAX_STARTUP_CEILING_PROBE_MS) {
            elapsedMs += PlaybackStartupWatchdog.POLL_INTERVAL_MS
            state = PlaybackStartupWatchdog.observe(state, sample(elapsedMs = elapsedMs, isHeld = true))
        }

        assertEquals(Verdict.Abandon, state.verdict)
        assertEquals(Reason.NeverStarted, state.reason)
        assertEquals(
            PlaybackStartupWatchdog.NO_PROGRESS_DEADLINE_MS,
            elapsedMs,
            "the ordinary deadline, not a longer one: a party is no reason to wait longer for nothing",
        )
        assertEquals(0L, state.holdMs, "an unproven source is charged the time, not excused it")
    }

    @Test
    fun `a held source with a header but no media still reaches the evidence deadline`() {
        // What the dead candidate really looked like from here: a duration parsed out of the
        // container and a probe that answered, and not one millisecond of media behind either. Both
        // are evidence of life, so it gets the longer deadline - and it does get to the end of it.
        var state = PlaybackStartupWatchdog.initial()
        var elapsedMs = 0L
        while (state.verdict == Verdict.Waiting && elapsedMs < MAX_STARTUP_CEILING_PROBE_MS) {
            elapsedMs += PlaybackStartupWatchdog.POLL_INTERVAL_MS
            state = PlaybackStartupWatchdog.observe(
                state,
                sample(
                    elapsedMs = elapsedMs,
                    durationMs = 7_500_000L,
                    hasExternalEvidenceOfLife = true,
                    isHeld = true,
                ),
            )
        }

        assertEquals(Verdict.Abandon, state.verdict)
        assertEquals(Reason.NeverStarted, state.reason)
        assertEquals(PlaybackStartupWatchdog.EVIDENCE_OF_LIFE_DEADLINE_MS, elapsedMs)
    }

    @Test
    fun `a party seek under a hold cannot buy an unproven source more time`() {
        // The two mechanisms together: the party parks the client *and* keeps aligning it, which is
        // every few seconds a fresh baseline. The rebase makes each jump measure zero progress, so
        // the source stays unproven and the deadline stays running.
        var state = PlaybackStartupWatchdog.initial()
        var elapsedMs = 0L
        var commandedMs = 0L
        while (state.verdict == Verdict.Waiting && elapsedMs < MAX_STARTUP_CEILING_PROBE_MS) {
            elapsedMs += PlaybackStartupWatchdog.POLL_INTERVAL_MS
            commandedMs += 30_000L
            state = PlaybackStartupWatchdog.observe(
                state,
                sample(
                    elapsedMs = elapsedMs,
                    positionMs = commandedMs,
                    baselineMs = commandedMs,
                    isHeld = true,
                ),
            )
        }

        assertEquals(Verdict.Abandon, state.verdict)
        assertEquals(Reason.NeverStarted, state.reason)
        assertEquals(PlaybackStartupWatchdog.NO_PROGRESS_DEADLINE_MS, elapsedMs)
    }

    @Test
    fun `one millisecond of real media buys the hold protection back`() {
        // The line between the two halves. A source that delivers anything at all is proven from
        // then on, and from then on the party may hold it for as long as it likes - which is the
        // guest case above, and must keep working exactly as it did.
        var state = PlaybackStartupWatchdog.initial()
        var elapsedMs = PlaybackStartupWatchdog.POLL_INTERVAL_MS
        state = PlaybackStartupWatchdog.observe(
            state,
            sample(elapsedMs = elapsedMs, bufferedPositionMs = 1L, isHeld = true),
        )
        assertTrue(state.hasProvenViability, "a millisecond of buffer is media")

        val heldUntilMs = elapsedMs + PlaybackStartupWatchdog.MAX_STARTUP_MS * 3
        while (elapsedMs < heldUntilMs) {
            elapsedMs += PlaybackStartupWatchdog.POLL_INTERVAL_MS
            state = PlaybackStartupWatchdog.observe(
                state,
                sample(elapsedMs = elapsedMs, bufferedPositionMs = 1L, isHeld = true),
            )
        }

        assertEquals(Verdict.Waiting, state.verdict)
        assertNull(state.reason)
        assertEquals(
            0L,
            state.effectiveElapsedMs,
            "the proving sample was itself held, so even its own interval was time nobody waited in",
        )
    }

    @Test
    fun `proof earned after the hold began protects the source from then on`() {
        // Ordering, because the flag is sticky rather than a property of the current sample: held
        // from the first poll, nothing for a while, then the first bytes arrive *while still held*.
        // The time before the proof stays charged; the time after it does not.
        var state = PlaybackStartupWatchdog.initial()
        var elapsedMs = 0L
        repeat(5) {
            elapsedMs += PlaybackStartupWatchdog.POLL_INTERVAL_MS
            state = PlaybackStartupWatchdog.observe(state, sample(elapsedMs = elapsedMs, isHeld = true))
        }
        val chargedBeforeProofMs = state.effectiveElapsedMs
        assertEquals(elapsedMs, chargedBeforeProofMs, "nothing had been proved, so nothing was excused")

        elapsedMs += PlaybackStartupWatchdog.POLL_INTERVAL_MS
        state = PlaybackStartupWatchdog.observe(
            state,
            sample(elapsedMs = elapsedMs, bufferedPositionMs = 2_400L, isHeld = true),
        )
        val chargedAtProofMs = state.effectiveElapsedMs

        repeat(120) {
            elapsedMs += PlaybackStartupWatchdog.POLL_INTERVAL_MS
            state = PlaybackStartupWatchdog.observe(
                state,
                sample(elapsedMs = elapsedMs, bufferedPositionMs = 2_400L, isHeld = true),
            )
        }

        assertEquals(Verdict.Waiting, state.verdict)
        assertEquals(chargedAtProofMs, state.effectiveElapsedMs, "held time after the proof is free")
    }

    @Test
    fun `a host pause before a guest first frame does not abandon the source`() {
        // The exact chain. Seven seconds of real startup with a little buffer, then the party pauses
        // the guest at 7257ms and the buffer stops. Held for four times the stall deadline.
        var state = PlaybackStartupWatchdog.initial()
        var elapsedMs = 0L
        while (elapsedMs < 7_000L) {
            elapsedMs += PlaybackStartupWatchdog.POLL_INTERVAL_MS
            state = PlaybackStartupWatchdog.observe(
                state,
                sample(elapsedMs = elapsedMs, bufferedPositionMs = elapsedMs),
            )
        }
        assertEquals(Verdict.Waiting, state.verdict)

        val heldFrom = elapsedMs
        val frozenBuffer = elapsedMs
        while (elapsedMs < heldFrom + PlaybackStartupWatchdog.STALL_DEADLINE_MS * 4) {
            elapsedMs += PlaybackStartupWatchdog.POLL_INTERVAL_MS
            state = PlaybackStartupWatchdog.observe(
                state,
                sample(elapsedMs = elapsedMs, bufferedPositionMs = frozenBuffer, positionMs = 7_257L, isHeld = true),
            )
        }

        assertEquals(Verdict.Waiting, state.verdict, "the party paused it; it did not stall")
        assertNull(state.reason)
    }

    @Test
    fun `a prolonged party pause accumulates no stall time`() {
        var state = PlaybackStartupWatchdog.initial()
        var elapsedMs = 0L
        repeat(4) {
            elapsedMs += PlaybackStartupWatchdog.POLL_INTERVAL_MS
            state = PlaybackStartupWatchdog.observe(
                state,
                sample(elapsedMs = elapsedMs, bufferedPositionMs = elapsedMs),
            )
        }
        val effectiveBeforeHold = state.effectiveElapsedMs

        val holdMs = 10 * 60_000L
        val frozenBuffer = elapsedMs
        while (elapsedMs < 4 * PlaybackStartupWatchdog.POLL_INTERVAL_MS + holdMs) {
            elapsedMs += PlaybackStartupWatchdog.POLL_INTERVAL_MS
            state = PlaybackStartupWatchdog.observe(
                state,
                sample(elapsedMs = elapsedMs, bufferedPositionMs = frozenBuffer, isHeld = true),
            )
        }

        assertEquals(Verdict.Waiting, state.verdict)
        assertEquals(
            effectiveBeforeHold,
            state.effectiveElapsedMs,
            "ten minutes of deliberate pause is ten minutes the source was never asked to fill",
        )
        assertTrue(state.holdMs >= holdMs, "the hold is measured, not merely ignored")
    }

    @Test
    fun `a source that is genuinely dead after the party releases it is still abandoned`() {
        // The other half, and the one that would make this a bug rather than a fix if it failed:
        // released, asked to play, and it never moves again.
        var state = PlaybackStartupWatchdog.initial()
        var elapsedMs = 0L
        repeat(4) {
            elapsedMs += PlaybackStartupWatchdog.POLL_INTERVAL_MS
            state = PlaybackStartupWatchdog.observe(
                state,
                sample(elapsedMs = elapsedMs, bufferedPositionMs = elapsedMs),
            )
        }
        val frozenBuffer = elapsedMs
        repeat(30) {
            elapsedMs += PlaybackStartupWatchdog.POLL_INTERVAL_MS
            state = PlaybackStartupWatchdog.observe(
                state,
                sample(elapsedMs = elapsedMs, bufferedPositionMs = frozenBuffer, isHeld = true),
            )
        }
        assertEquals(Verdict.Waiting, state.verdict)

        // Released. It gets the whole stall deadline from here - and no more than it.
        val releasedAt = elapsedMs
        while (state.verdict == Verdict.Waiting &&
            elapsedMs < releasedAt + PlaybackStartupWatchdog.STALL_DEADLINE_MS * 3
        ) {
            elapsedMs += PlaybackStartupWatchdog.POLL_INTERVAL_MS
            state = PlaybackStartupWatchdog.observe(
                state,
                sample(elapsedMs = elapsedMs, bufferedPositionMs = frozenBuffer),
            )
        }

        assertEquals(Verdict.Abandon, state.verdict)
        assertEquals(Reason.Stalled, state.reason)
        assertTrue(
            elapsedMs - releasedAt >= PlaybackStartupWatchdog.STALL_DEADLINE_MS,
            "a released player refills an empty pipeline; it gets the full deadline, not the remains of one",
        )
    }

    @Test
    fun `a release rebases the stall deadline rather than resuming a spent one`() {
        var state = PlaybackStartupWatchdog.initial()
        var elapsedMs = PlaybackStartupWatchdog.POLL_INTERVAL_MS
        state = PlaybackStartupWatchdog.observe(state, sample(elapsedMs = elapsedMs, bufferedPositionMs = 4_000L))

        // Almost the whole stall deadline spent before the party takes the player.
        elapsedMs += PlaybackStartupWatchdog.STALL_DEADLINE_MS - PlaybackStartupWatchdog.POLL_INTERVAL_MS
        state = PlaybackStartupWatchdog.observe(state, sample(elapsedMs = elapsedMs, bufferedPositionMs = 4_000L))
        assertEquals(Verdict.Waiting, state.verdict)

        elapsedMs += PlaybackStartupWatchdog.POLL_INTERVAL_MS
        state = PlaybackStartupWatchdog.observe(
            state,
            sample(elapsedMs = elapsedMs, bufferedPositionMs = 4_000L, isHeld = true),
        )
        elapsedMs += 30_000L
        state = PlaybackStartupWatchdog.observe(
            state,
            sample(elapsedMs = elapsedMs, bufferedPositionMs = 4_000L, isHeld = true),
        )

        // First unheld sample after the release. The pre-hold remainder was one poll; a resumed
        // clock would abandon here.
        elapsedMs += PlaybackStartupWatchdog.POLL_INTERVAL_MS
        state = PlaybackStartupWatchdog.observe(state, sample(elapsedMs = elapsedMs, bufferedPositionMs = 4_000L))
        assertEquals(Verdict.Waiting, state.verdict, "the release restarts the stall clock")

        elapsedMs += PlaybackStartupWatchdog.STALL_DEADLINE_MS
        state = PlaybackStartupWatchdog.observe(state, sample(elapsedMs = elapsedMs, bufferedPositionMs = 4_000L))
        assertEquals(Verdict.Abandon, state.verdict, "and restarts it once, not on every poll")
        assertEquals(Reason.Stalled, state.reason)
    }

    @Test
    fun `a play that is never held behaves exactly as it did before holds existed`() {
        // The regression that matters most to everybody not in a party: `isHeld` defaults to false,
        // and with it false every deadline is the wall-clock it always was.
        var state = PlaybackStartupWatchdog.initial()
        var elapsedMs = 0L
        while (state.verdict == Verdict.Waiting && elapsedMs < 120_000L) {
            elapsedMs += PlaybackStartupWatchdog.POLL_INTERVAL_MS
            state = PlaybackStartupWatchdog.observe(state, sample(elapsedMs = elapsedMs))
        }

        assertEquals(Verdict.Abandon, state.verdict)
        assertEquals(Reason.NeverStarted, state.reason)
        assertEquals(PlaybackStartupWatchdog.NO_PROGRESS_DEADLINE_MS, elapsedMs)
        assertEquals(0L, state.holdMs)
        assertEquals(elapsedMs, state.effectiveElapsedMs)
    }

    // ---------------------------------------------------------------------------------------
    // A playhead moved by Watch Together is not progress this source made.
    //
    // The S25 run of 2026-09-19, reproduced below: a guest starving on a 4K remux was aligned by
    // the host from 4339ms to 12012ms, the engine reported the target immediately, and the jump
    // read as 12012ms of progress. That moved the play off the patient no-progress deadline and
    // onto STALL_DEADLINE_MS, which then ran out against a position only the host could move. The
    // party's only candidate was abandoned as Stalled.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a party seek to a later position counts as no progress at all`() {
        var state = PlaybackStartupWatchdog.initial()
        // Real progress first: the guest genuinely reached 4339ms on its own.
        state = PlaybackStartupWatchdog.observe(
            state,
            sample(elapsedMs = 2_000L, positionMs = 4_339L, bufferedPositionMs = 4_339L),
        )
        assertEquals(4_339L, state.bestProgressMs)

        // The host aligns everyone to 12012ms. The engine reports the target at once, with no
        // buffer beyond it, and the baseline moves with the command.
        state = PlaybackStartupWatchdog.observe(
            state,
            sample(
                elapsedMs = 3_000L,
                positionMs = 12_012L,
                bufferedPositionMs = 12_012L,
                baselineMs = 12_012L,
            ),
        )
        assertEquals(Verdict.Waiting, state.verdict)
        assertEquals(0L, state.bestProgressMs)
    }

    @Test
    fun `a party seek does not hand the source a fresh startup`() {
        // The deadline that matters after a rebase is the no-progress one, and it is absolute in
        // effective elapsed time. Seeking at the last moment must not buy a single millisecond.
        var state = PlaybackStartupWatchdog.initial()
        state = PlaybackStartupWatchdog.observe(
            state,
            sample(elapsedMs = 2_000L, positionMs = 4_339L, bufferedPositionMs = 4_339L),
        )
        state = PlaybackStartupWatchdog.observe(
            state,
            sample(
                elapsedMs = PlaybackStartupWatchdog.EVIDENCE_OF_LIFE_DEADLINE_MS - 1L,
                positionMs = 12_012L,
                bufferedPositionMs = 12_012L,
                baselineMs = 12_012L,
            ),
        )
        assertEquals(Verdict.Waiting, state.verdict)

        state = PlaybackStartupWatchdog.observe(
            state,
            sample(
                elapsedMs = PlaybackStartupWatchdog.EVIDENCE_OF_LIFE_DEADLINE_MS,
                positionMs = 12_012L,
                bufferedPositionMs = 12_012L,
                baselineMs = 12_012L,
            ),
        )
        assertEquals(Verdict.Abandon, state.verdict)
        assertEquals(Reason.NeverStarted, state.reason)
    }

    @Test
    fun `repeated party seeks cannot extend the watchdog indefinitely`() {
        // A party correcting a guest every second must not be able to keep it alive forever. The
        // ceiling is absolute, so it ends the play whatever the corrections do.
        var state = PlaybackStartupWatchdog.initial()
        var elapsedMs = 0L
        var targetMs = 0L
        while (state.verdict == Verdict.Waiting && elapsedMs < MAX_STARTUP_CEILING_PROBE_MS) {
            elapsedMs += PlaybackStartupWatchdog.POLL_INTERVAL_MS
            targetMs += 5_000L
            state = PlaybackStartupWatchdog.observe(
                state,
                sample(
                    elapsedMs = elapsedMs,
                    positionMs = targetMs,
                    bufferedPositionMs = targetMs,
                    baselineMs = targetMs,
                ),
            )
        }
        assertEquals(Verdict.Abandon, state.verdict)
        assertTrue(
            elapsedMs <= PlaybackStartupWatchdog.MAX_STARTUP_MS,
            "a seek every poll kept the play alive to ${elapsedMs}ms",
        )
    }

    @Test
    fun `real advancement past a party seek target still counts`() {
        var state = PlaybackStartupWatchdog.initial()
        state = PlaybackStartupWatchdog.observe(
            state,
            sample(elapsedMs = 2_000L, positionMs = 4_339L, bufferedPositionMs = 4_339L),
        )
        state = PlaybackStartupWatchdog.observe(
            state,
            sample(
                elapsedMs = 3_000L,
                positionMs = 12_012L,
                bufferedPositionMs = 12_012L,
                baselineMs = 12_012L,
            ),
        )
        assertEquals(0L, state.bestProgressMs)

        // The source refills past the commanded target: ordinary progress, measured from it.
        state = PlaybackStartupWatchdog.observe(
            state,
            sample(
                elapsedMs = 4_000L,
                positionMs = 12_012L,
                bufferedPositionMs = 15_000L,
                baselineMs = 12_012L,
            ),
        )
        assertEquals(Verdict.Waiting, state.verdict)
        assertEquals(15_000L - 12_012L, state.bestProgressMs)

        // And that advance restarts the stall clock, so the guest is not abandoned for the
        // seconds it spent frozen before the party moved it.
        state = PlaybackStartupWatchdog.observe(
            state,
            sample(
                elapsedMs = 4_000L + PlaybackStartupWatchdog.STALL_DEADLINE_MS - 1L,
                positionMs = 12_012L,
                bufferedPositionMs = 15_000L,
                baselineMs = 12_012L,
            ),
        )
        assertEquals(Verdict.Waiting, state.verdict)
    }

    @Test
    fun `a party seek leaves held time excluded exactly as before`() {
        // The rebase must not disturb the hold accounting, which is a separate mechanism that a
        // party exercises at the same moments.
        var state = PlaybackStartupWatchdog.initial()
        state = PlaybackStartupWatchdog.observe(
            state,
            sample(elapsedMs = 1_000L, positionMs = 4_339L, bufferedPositionMs = 4_339L),
        )
        // Held for ten seconds across the alignment, exactly as a stall hold does it.
        state = PlaybackStartupWatchdog.observe(state, sample(elapsedMs = 2_000L, isHeld = true))
        state = PlaybackStartupWatchdog.observe(
            state,
            sample(
                elapsedMs = 12_000L,
                positionMs = 12_012L,
                bufferedPositionMs = 12_012L,
                baselineMs = 12_012L,
                isHeld = true,
            ),
        )
        // 11s charged to the hold, not 10: the interval a hold *begins* in is charged too, because
        // the buffer had already stopped for part of it. That is the pre-existing rule and the
        // rebase must not have touched it.
        assertEquals(11_000L, state.holdMs)
        assertEquals(1_000L, state.effectiveElapsedMs)
        assertEquals(Verdict.Waiting, state.verdict)
        assertEquals(0L, state.bestProgressMs)
    }

    /** Comfortably past [PlaybackStartupWatchdog.MAX_STARTUP_MS], so the loop above terminates. */
    private val MAX_STARTUP_CEILING_PROBE_MS = PlaybackStartupWatchdog.MAX_STARTUP_MS * 3

    private fun sample(
        elapsedMs: Long,
        isPlaying: Boolean = false,
        positionMs: Long = 0L,
        bufferedPositionMs: Long = 0L,
        durationMs: Long = 0L,
        baselineMs: Long = 0L,
        hasExternalEvidenceOfLife: Boolean = false,
        isHeld: Boolean = false,
    ) = PlaybackStartupSample(
        elapsedMs = elapsedMs,
        isPlaying = isPlaying,
        positionMs = positionMs,
        bufferedPositionMs = bufferedPositionMs,
        durationMs = durationMs,
        baselineMs = baselineMs,
        hasExternalEvidenceOfLife = hasExternalEvidenceOfLife,
        isHeld = isHeld,
    )
}
