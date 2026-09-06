package com.nuvio.app.features.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The log format, pinned.
 *
 * Worth a test because the whole value of these lines is that a session is greppable as a
 * table: the moment one caller adds "just one more field" in its own shape, `grep` stops
 * lining up and the format silently becomes prose again.
 */
class PlaybackAttemptLogTest {

    @Test
    fun `an attempt line carries mode, budget, candidate, addon and outcome`() {
        assertEquals(
            "mode=instant attempt=2/3 candidate=4K_·_WEB-DL_·_TorBox addon=aiostreams " +
                "addonErrored=true cached=true outcome=resolve_failed",
            PlaybackAttemptLog.attempt(
                mode = "instant",
                attempt = 2,
                maxAttempts = 3,
                candidate = "4K · WEB-DL · TorBox",
                addonId = "aiostreams",
                addonErrored = true,
                cached = true,
                outcome = "resolve_failed",
            ),
        )
    }

    /**
     * `cached` is tri-state and must say so. Printing `false` for "we could not tell" is the
     * same untruth the `SourceFacts.isDebridReady` nullability exists to prevent, and a log
     * that asserts it is worse than one that admits ignorance.
     */
    @Test
    fun `an unknown cache state is named rather than defaulted`() {
        val line = PlaybackAttemptLog.attempt(
            mode = "streamlined",
            attempt = 1,
            maxAttempts = 3,
            candidate = "1080p",
            addonId = "torrentio",
            addonErrored = false,
            cached = null,
            outcome = "handed_off",
        )
        assertTrue(line.contains("cached=unknown"), line)
    }

    @Test
    fun `a missing candidate or addon reads as unknown, never as blank`() {
        val line = PlaybackAttemptLog.attempt(
            mode = "instant",
            attempt = 1,
            maxAttempts = 3,
            candidate = null,
            addonId = "   ",
            addonErrored = false,
            cached = false,
            outcome = "no_candidate",
        )
        assertTrue(line.contains("candidate=unknown"), line)
        assertTrue(line.contains("addon=unknown"), line)
    }

    @Test
    fun `the uncover reason is appended only when the list was handed back`() {
        val quiet = PlaybackAttemptLog.attempt(
            mode = "instant", attempt = 1, maxAttempts = 3, candidate = "c",
            addonId = "a", addonErrored = false, cached = true, outcome = "handed_off",
        )
        assertTrue(!quiet.contains("uncover="), quiet)

        val uncovered = PlaybackAttemptLog.attempt(
            mode = "instant", attempt = 3, maxAttempts = 3, candidate = "c",
            addonId = "a", addonErrored = false, cached = true, outcome = "gave_up",
            uncoverReason = "chain_spent",
        )
        assertTrue(uncovered.endsWith("uncover=chain_spent"), uncovered)
    }

    /**
     * The seek line exists so the jump-to-the-end bug names itself: a position equal to the
     * duration is instantly readable, where "it jumped to the end" could be a dozen things.
     */
    @Test
    fun `a seek line carries the numbers the position was derived from`() {
        assertEquals(
            "seek=resume pos=7143000ms duration=7143000ms fraction=1.0 accepted=true",
            PlaybackAttemptLog.seek(
                source = "resume",
                positionMs = 7_143_000L,
                durationMs = 7_143_000L,
                fraction = 1.0f,
                accepted = true,
            ),
        )
    }

    @Test
    fun `a refused seek says why, and an unknown duration says so`() {
        val line = PlaybackAttemptLog.seek(
            source = "resume",
            positionMs = 0L,
            durationMs = null,
            fraction = 0.5f,
            accepted = false,
            refusedReason = "implausible_duration",
        )
        assertTrue(line.contains("duration=unknown"), line)
        assertTrue(line.endsWith("refused=implausible_duration"), line)
    }
}
