package com.nuvio.app.features.playback

import com.nuvio.app.features.downloads.SourceFacts
import com.nuvio.app.features.downloads.VideoResolution
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoDownshiftDetectorTest {

    @Test
    fun sustainedStarvationFiresOnceThePlaybackHasSettled() {
        val result = run(intervalMs = 250L, bufferedAheadMs = 500L)
        assertTrue(result.fired, "sustained starvation past the settle grace should downshift")
    }

    @Test
    fun theSameRunAtDesktopPollingRateFiresToo() {
        // Desktop polls every 500 ms. A snapshot-count threshold would make the two
        // platforms disagree by a factor of two; a duration threshold must not.
        val fast = run(intervalMs = 250L, bufferedAheadMs = 500L)
        val slow = run(intervalMs = 500L, bufferedAheadMs = 500L)
        assertTrue(fast.fired && slow.fired)
        assertEquals(fast.firedAtMs!! / 1000L, slow.firedAtMs!! / 1000L)
    }

    @Test
    fun theRunOnlyStartsAfterTheGraceLifts() {
        // Starved from the very first sample. The grace exists because early buffer figures
        // are untrustworthy - on desktop they are clamped to the resume point - so those
        // samples must not count toward the run. Firing at exactly the grace boundary would
        // mean the decision was built entirely from them.
        val result = run(intervalMs = 250L, bufferedAheadMs = 0L, durationMs = 60_000L)
        assertTrue(result.fired)
        assertTrue(
            result.firedAtMs!! >= AutoDownshiftDetector.SETTLE_GRACE_MS +
                AutoDownshiftDetector.SUSTAINED_MS,
            "fired at ${result.firedAtMs} ms, before grace + sustained had elapsed",
        )
    }

    @Test
    fun aHealthyBufferNeverFires() {
        assertFalse(run(intervalMs = 250L, bufferedAheadMs = 30_000L).fired)
    }

    @Test
    fun starvationDuringTheSettleGraceIsIgnored() {
        // Starved from the very first sample, but stopped before the grace expires.
        val result = run(intervalMs = 250L, bufferedAheadMs = 0L, durationMs = 10_000L)
        assertFalse(result.fired, "startup buffering must not count")
    }

    @Test
    fun aBriefDipDoesNotFire() {
        var state = AutoDownshiftDetector.initial()
        var now = 0L
        var fired = false
        // Settle, then alternate two starved seconds with a healthy sample, repeatedly.
        repeat(200) { index ->
            val starved = (index / 4) % 2 == 0 && index > 80
            val outcome = AutoDownshiftDetector.observe(
                state,
                sample(now, positionMs = now, bufferedAheadMs = if (starved) 0L else 30_000L),
                enabled = true,
            )
            state = outcome.state
            if (outcome.shouldDownshift) fired = true
            now += 500L
        }
        assertFalse(fired, "a run that keeps recovering is not sustained starvation")
    }

    @Test
    fun onlyOneSwapPerSession() {
        val result = run(intervalMs = 250L, bufferedAheadMs = 0L, durationMs = 180_000L)
        assertEquals(1, result.fireCount, "the session budget is one swap")
    }

    @Test
    fun aFiringThatCannotBeActedOnDoesNotSpendTheBudget() {
        // The caller only learns whether a swap is possible after the detector fires - the
        // candidate list might have nothing in the same release group. If firing spent the
        // budget by itself, one unusable trigger would disable the feature for the episode.
        var state = AutoDownshiftDetector.initial()
        var now = 0L
        var fires = 0
        while (now <= 180_000L) {
            val outcome = AutoDownshiftDetector.observe(
                state,
                sample(now, positionMs = now, bufferedAheadMs = 0L),
                enabled = true,
            )
            state = outcome.state
            if (outcome.shouldDownshift) fires++ // deliberately never consumeSwap
            now += 250L
        }
        assertTrue(fires > 1, "an unacted trigger must be offered again, not swallowed")
        assertEquals(0, state.swapsUsed)
    }

    @Test
    fun aStallCountsEvenWhenTheReportedBufferLooksFine() {
        // paused-for-cache: isPlaying false, isLoading true. The buffer figure is stale.
        var state = AutoDownshiftDetector.initial()
        var now = 0L
        var fired = false
        repeat(200) {
            val settled = now >= AutoDownshiftDetector.SETTLE_GRACE_MS
            val outcome = AutoDownshiftDetector.observe(
                state,
                sample(
                    now,
                    positionMs = now,
                    bufferedAheadMs = 30_000L,
                    isPlaying = !settled,
                    isLoading = settled,
                ),
                enabled = true,
            )
            state = outcome.state
            if (outcome.shouldDownshift) fired = true
            now += 250L
        }
        assertTrue(fired, "a persistent stall is starvation whatever the buffer reports")
    }

    @Test
    fun pausingClearsTheRun() {
        var state = AutoDownshiftDetector.initial()
        var now = 0L
        var fired = false
        repeat(400) { index ->
            // Idle with neither flag set is a user pause, every fifth sample.
            val paused = index % 5 == 0
            val outcome = AutoDownshiftDetector.observe(
                state,
                sample(
                    now,
                    positionMs = now,
                    bufferedAheadMs = 0L,
                    isPlaying = !paused,
                    isLoading = false,
                ),
                enabled = true,
            )
            state = outcome.state
            if (outcome.shouldDownshift) fired = true
            now += 500L
        }
        assertFalse(fired, "a paused player is not starving")
    }

    @Test
    fun seekingRestartsTheSettleGrace() {
        var state = AutoDownshiftDetector.initial()
        var now = 0L
        var position = 0L
        var fired = false
        repeat(200) { index ->
            // Jump forward ten minutes every 10 s - inside the settle grace, so a user who
            // keeps skipping around never accumulates a run however starved each hop is.
            if (index > 0 && index % 20 == 0) position += 600_000L
            val outcome = AutoDownshiftDetector.observe(
                state,
                sample(now, positionMs = position, bufferedAheadMs = 0L),
                enabled = true,
            )
            state = outcome.state
            if (outcome.shouldDownshift) fired = true
            now += 500L
            position += 500L
        }
        assertFalse(fired, "post-seek buffering must not trigger a swap")
    }

    @Test
    fun disabledNeverFires() {
        var state = AutoDownshiftDetector.initial()
        var now = 0L
        repeat(400) {
            val outcome = AutoDownshiftDetector.observe(
                state,
                sample(now, positionMs = now, bufferedAheadMs = 0L),
                enabled = false,
            )
            assertFalse(outcome.shouldDownshift)
            state = outcome.state
            now += 250L
        }
    }

    @Test
    fun candidateIsTheNextStepDownInTheSameReleaseGroup() {
        val current = candidate("https://cdn/2160.mkv", VideoResolution.UHD_2160, "NUVIO")
        val sameGroup1080 = candidate("https://cdn/1080.mkv", VideoResolution.FULL_HD_1080, "NUVIO")
        val sameGroup720 = candidate("https://cdn/720.mkv", VideoResolution.HD_720, "NUVIO")
        val otherGroup = candidate("https://cdn/other.mkv", VideoResolution.FULL_HD_1080, "OTHER")

        val picked = AutoDownshiftCandidates.select(
            current,
            listOf(otherGroup, sameGroup720, sameGroup1080),
        )
        assertEquals(sameGroup1080.stream, picked?.stream, "one step down, and only within the group")
    }

    @Test
    fun neverSwapsUpAndNeverAcrossGroups() {
        val current = candidate("https://cdn/720.mkv", VideoResolution.HD_720, "NUVIO")
        val higherSameGroup = candidate("https://cdn/1080.mkv", VideoResolution.FULL_HD_1080, "NUVIO")
        val lowerOtherGroup = candidate("https://cdn/480.mkv", VideoResolution.SD, "OTHER")

        assertNull(AutoDownshiftCandidates.select(current, listOf(higherSameGroup, lowerOtherGroup)))
    }

    @Test
    fun noReleaseGroupMeansNoSwap() {
        val current = candidate("https://cdn/1080.mkv", VideoResolution.FULL_HD_1080, group = null)
        val lower = candidate("https://cdn/720.mkv", VideoResolution.HD_720, group = null)
        assertNull(AutoDownshiftCandidates.select(current, listOf(lower)))
    }

    @Test
    fun manifestSourcesAreExemptBecauseTheyAdaptThemselves() {
        val current = candidate("https://cdn/master.m3u8", VideoResolution.UHD_2160, "NUVIO")
        val lower = candidate("https://cdn/720.mkv", VideoResolution.HD_720, "NUVIO")
        assertNull(AutoDownshiftCandidates.select(current, listOf(lower)))
    }

    @Test
    fun anUncachedDebridCandidateIsNeverSwappedTo() {
        val current = candidate("https://cdn/1080.mkv", VideoResolution.FULL_HD_1080, "NUVIO")
        val uncached = candidate(
            "https://cdn/720.mkv",
            VideoResolution.HD_720,
            "NUVIO",
            isDebridReady = false,
        )
        assertNull(AutoDownshiftCandidates.select(current, listOf(uncached)))
    }

    private data class RunResult(val fireCount: Int, val firedAtMs: Long?) {
        val fired: Boolean get() = fireCount > 0
    }

    /** Plays continuously at [intervalMs], starved at [bufferedAheadMs], for [durationMs]. */
    private fun run(
        intervalMs: Long,
        bufferedAheadMs: Long,
        durationMs: Long = 60_000L,
    ): RunResult {
        var state = AutoDownshiftDetector.initial()
        var now = 0L
        var fireCount = 0
        var firedAtMs: Long? = null
        while (now <= durationMs) {
            val outcome = AutoDownshiftDetector.observe(
                state,
                sample(now, positionMs = now, bufferedAheadMs = bufferedAheadMs),
                enabled = true,
            )
            state = outcome.state
            if (outcome.shouldDownshift) {
                // Stand in for a caller that found a candidate and swapped.
                state = AutoDownshiftDetector.consumeSwap(state)
                fireCount++
                if (firedAtMs == null) firedAtMs = now
            }
            now += intervalMs
        }
        return RunResult(fireCount, firedAtMs)
    }

    private fun sample(
        nowMs: Long,
        positionMs: Long,
        bufferedAheadMs: Long,
        isPlaying: Boolean = true,
        isLoading: Boolean = false,
    ) = AutoDownshiftDetector.Sample(
        elapsedRealtimeMs = nowMs,
        positionMs = positionMs,
        bufferedPositionMs = positionMs + bufferedAheadMs,
        isPlaying = isPlaying,
        isLoading = isLoading,
        isEnded = false,
    )

    private fun candidate(
        url: String,
        resolution: VideoResolution,
        group: String?,
        isDebridReady: Boolean? = null,
    ) = PlaybackSourceCandidate(
        stream = StreamItem(
            name = url,
            url = url,
            addonName = "Addon",
            addonId = "addon",
        ),
        facts = SourceFacts(
            resolution = resolution,
            releaseGroup = group,
            isDebridReady = isDebridReady,
        ),
    )
}
