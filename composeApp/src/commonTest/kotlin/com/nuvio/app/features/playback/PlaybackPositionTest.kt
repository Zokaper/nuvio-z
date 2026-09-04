package com.nuvio.app.features.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The arithmetic behind "it plays, then it jumps to the end and sticks".
 *
 * Every case here is a value a real engine reports on a bad source, which is the argument for
 * covering it: the inputs that break this are free to produce in a test and nearly impossible
 * to produce on demand on a device.
 */
class PlaybackPositionTest {

    private val HOUR_MS = 3_600_000L

    @Test
    fun `a duration of zero is not a basis for anything`() {
        assertFalse(PlaybackPosition.isDurationUsable(0L))
        assertNull(
            PlaybackPosition.resolveStartPositionMs(
                initialPositionMs = 0L,
                progressFraction = 0.5f,
                durationMs = 0L,
            ),
        )
    }

    @Test
    fun `a negative duration is not a basis for anything`() {
        assertFalse(PlaybackPosition.isDurationUsable(-1L))
    }

    /**
     * The suspect the plan names: a progressive link through the Windows bridge reporting a
     * duration in the wrong unit. Seeking into it lands past the end of the file.
     */
    @Test
    fun `an implausible duration is refused rather than seeked into`() {
        val absurd = 1_000L * HOUR_MS
        assertFalse(PlaybackPosition.isDurationUsable(absurd))
        assertNull(
            PlaybackPosition.resolveStartPositionMs(
                initialPositionMs = 0L,
                progressFraction = 0.5f,
                durationMs = absurd,
            ),
        )
        assertEquals(
            "implausible_duration",
            PlaybackPosition.refusalReason(0L, 0.5f, absurd),
        )
    }

    @Test
    fun `a non-finite fraction is refused and named`() {
        assertNull(
            PlaybackPosition.resolveStartPositionMs(0L, Float.NaN, HOUR_MS),
        )
        assertEquals(
            "non_finite_fraction",
            PlaybackPosition.refusalReason(0L, Float.POSITIVE_INFINITY, HOUR_MS),
        )
    }

    /**
     * **The reported bug, in one assertion.** A fully-watched entry resumed on a fresh start
     * used to compute `duration × 1.0` and seek exactly to the end, where playback has nowhere
     * to go - "the position leaps to the end and sticks".
     */
    @Test
    fun `a fraction of one lands short of the end, never on it`() {
        val target = PlaybackPosition.resolveStartPositionMs(
            initialPositionMs = 0L,
            progressFraction = 1.0f,
            durationMs = HOUR_MS,
        )
        assertEquals(HOUR_MS - PlaybackPosition.END_GUARD_MS, target)
        assertTrue(target!! < HOUR_MS)
    }

    @Test
    fun `an explicit position past the duration is pulled back inside it`() {
        assertEquals(
            HOUR_MS - PlaybackPosition.END_GUARD_MS,
            PlaybackPosition.clampSeekTarget(HOUR_MS * 5, HOUR_MS),
        )
    }

    @Test
    fun `an explicit position wins over a fraction`() {
        assertEquals(
            90_000L,
            PlaybackPosition.resolveStartPositionMs(
                initialPositionMs = 90_000L,
                progressFraction = 0.9f,
                durationMs = HOUR_MS,
            ),
        )
    }

    /**
     * A caller holding a real position and an untrustworthy duration keeps its own number.
     * Inventing one from a duration we have just declined to believe would be worse.
     */
    @Test
    fun `an explicit position survives an unusable duration`() {
        assertEquals(90_000L, PlaybackPosition.clampSeekTarget(90_000L, 0L))
    }

    @Test
    fun `an ordinary resume is untouched`() {
        assertEquals(
            HOUR_MS / 2,
            PlaybackPosition.resolveStartPositionMs(
                initialPositionMs = 0L,
                progressFraction = 0.5f,
                durationMs = HOUR_MS,
            ),
        )
    }

    @Test
    fun `no resume information means no seek and no complaint`() {
        assertNull(PlaybackPosition.resolveStartPositionMs(0L, null, HOUR_MS))
        assertNull(PlaybackPosition.refusalReason(0L, null, HOUR_MS))
    }

    @Test
    fun `a negative position floors at zero`() {
        assertEquals(0L, PlaybackPosition.clampSeekTarget(-5_000L, HOUR_MS))
    }
}
