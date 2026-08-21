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
        val ceiling = PlaybackStartupWatchdog.MAX_STARTUP_MS
        assertTrue(stall < noProgress, "stall deadline must be under the no-progress one")
        assertTrue(noProgress < ceiling, "the ceiling must be past both deadlines")
        assertTrue(
            PlaybackStartupWatchdog.POLL_INTERVAL_MS < stall,
            "a verdict must never be a whole poll late",
        )
    }

    private fun sample(
        elapsedMs: Long,
        isPlaying: Boolean = false,
        positionMs: Long = 0L,
        bufferedPositionMs: Long = 0L,
        durationMs: Long = 0L,
    ) = PlaybackStartupSample(
        elapsedMs = elapsedMs,
        isPlaying = isPlaying,
        positionMs = positionMs,
        bufferedPositionMs = bufferedPositionMs,
        durationMs = durationMs,
    )
}
