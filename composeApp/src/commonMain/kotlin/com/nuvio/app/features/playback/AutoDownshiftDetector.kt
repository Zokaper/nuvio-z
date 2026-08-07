package com.nuvio.app.features.playback

import com.nuvio.app.features.downloads.SourceFacts
import com.nuvio.app.features.streams.StreamItem

/**
 * Instant's automatic source downshift: the trigger half.
 *
 * Swapping source mid-playback is not ABR. It tears the stream down, opens a different
 * file and seeks back, so the user pays a visible 1-3s hiccup *in order to* avoid a stall.
 * That trade only pays off under sustained starvation, never for one rebuffer, which is
 * why everything here is about refusing to fire rather than about firing.
 *
 * **The run is measured in wall-clock time, not in snapshot counts.** Android polls the
 * player about every 250 ms and desktop every 500 ms
 * (`PlayerEngine.desktop.kt`), so "three consecutive snapshots" would mean 0.75 s on one
 * platform and 1.5 s on the other - and neither is "sustained". A duration threshold makes
 * the platforms agree without per-platform tuning; [MIN_SAMPLES_IN_RUN] then stops a
 * single stale sample spanning a long gap from standing in for a real run.
 *
 * Pure and clock-free by design: the caller supplies a monotonic timestamp with each
 * sample, exactly as `PlaybackModeRouter` takes gathered inputs rather than repositories.
 */
object AutoDownshiftDetector {

    /** Buffer-ahead at or below this is starvation. */
    const val STARVED_BUFFER_MS = 4_000L

    /** How long starvation must hold continuously before a swap is worth its own hiccup. */
    const val SUSTAINED_MS = 6_000L

    /** Guards against one sample across a long gap counting as a sustained run. */
    const val MIN_SAMPLES_IN_RUN = 3

    /**
     * Startup and post-seek buffering is normal, and on desktop it is also *misreported*:
     * `effectiveCachePositionSeconds()` in `player_bridge.cpp` clamps the cache position to
     * the resume point for the first seconds of a resumed play, so buffer-ahead is not
     * trustworthy until playback has settled.
     */
    const val SETTLE_GRACE_MS = 15_000L

    /** One swap per playback session. Past that, stop fiddling and leave the user alone. */
    const val MAX_SWAPS_PER_SESSION = 1

    /**
     * A position jump larger than this between samples is a seek, not playback, so the
     * run restarts and the settle grace applies again.
     */
    private const val SEEK_TOLERANCE_MS = 3_000L

    /** Carried by the caller across samples. Start from [initial] on every new source. */
    data class State(
        val settledAtMs: Long? = null,
        val runStartedAtMs: Long? = null,
        val samplesInRun: Int = 0,
        val lastSampleAtMs: Long? = null,
        val lastPositionMs: Long? = null,
        val swapsUsed: Int = 0,
    )

    data class Sample(
        /** Monotonic wall clock, e.g. `TimeSource`/`nanoTime` based - not epoch time. */
        val elapsedRealtimeMs: Long,
        val positionMs: Long,
        val bufferedPositionMs: Long,
        val isPlaying: Boolean,
        val isLoading: Boolean,
        val isEnded: Boolean,
    ) {
        val bufferedAheadMs: Long get() = (bufferedPositionMs - positionMs).coerceAtLeast(0L)

        /**
         * Whether the player is trying to advance. A stall reads as
         * `isPlaying == false && isLoading == true` (mpv's `paused-for-cache`), which is
         * the case we most want to count - so idleness is only a user pause when *neither*
         * flag is set.
         */
        val isActive: Boolean get() = !isEnded && (isPlaying || isLoading)

        /** A stall is starvation whatever the reported buffer says. */
        val isStarved: Boolean get() = isLoading || bufferedAheadMs <= STARVED_BUFFER_MS
    }

    fun initial(swapsUsed: Int = 0): State = State(swapsUsed = swapsUsed)

    /**
     * Folds one snapshot into [state].
     *
     * Returns the new state and whether a downshift should happen now. Firing always ends
     * the current run, so a caller that cannot act on the signal is asked again only after
     * another full sustained window rather than on every subsequent snapshot.
     *
     * **The swap budget is not spent here.** Only [consumeSwap] spends it, because whether
     * a swap is possible depends on the candidate list, which this function cannot see -
     * charging the budget for a swap that never happened would silently disable the feature
     * for the rest of the session.
     */
    fun observe(state: State, sample: Sample, enabled: Boolean): Outcome {
        if (!enabled) return Outcome(initial(state.swapsUsed), shouldDownshift = false)

        val seeked = state.lastPositionMs?.let { previous ->
            val expected = state.lastSampleAtMs?.let { sample.elapsedRealtimeMs - it } ?: 0L
            val moved = sample.positionMs - previous
            moved < -SEEK_TOLERANCE_MS || moved > expected + SEEK_TOLERANCE_MS
        } ?: false

        // Paused, ended or seeking: no run survives, and playback has to settle again.
        if (!sample.isActive || seeked) {
            return Outcome(
                state.copy(
                    settledAtMs = null,
                    runStartedAtMs = null,
                    samplesInRun = 0,
                    lastSampleAtMs = sample.elapsedRealtimeMs,
                    lastPositionMs = sample.positionMs,
                ),
                shouldDownshift = false,
            )
        }

        val settledAtMs = state.settledAtMs ?: sample.elapsedRealtimeMs
        val settled = sample.elapsedRealtimeMs - settledAtMs >= SETTLE_GRACE_MS

        if (!sample.isStarved) {
            return Outcome(
                state.copy(
                    settledAtMs = settledAtMs,
                    runStartedAtMs = null,
                    samplesInRun = 0,
                    lastSampleAtMs = sample.elapsedRealtimeMs,
                    lastPositionMs = sample.positionMs,
                ),
                shouldDownshift = false,
            )
        }

        // The run may only start once playback has settled. Counting starved samples during
        // the grace and then firing the moment it lifts would build the decision entirely
        // out of the samples the grace exists to distrust - on desktop those are literally
        // clamped to the resume point rather than measured.
        if (!settled) {
            return Outcome(
                state.copy(
                    settledAtMs = settledAtMs,
                    runStartedAtMs = null,
                    samplesInRun = 0,
                    lastSampleAtMs = sample.elapsedRealtimeMs,
                    lastPositionMs = sample.positionMs,
                ),
                shouldDownshift = false,
            )
        }

        val runStartedAtMs = state.runStartedAtMs ?: sample.elapsedRealtimeMs
        val samplesInRun = state.samplesInRun + 1
        val advanced = state.copy(
            settledAtMs = settledAtMs,
            runStartedAtMs = runStartedAtMs,
            samplesInRun = samplesInRun,
            lastSampleAtMs = sample.elapsedRealtimeMs,
            lastPositionMs = sample.positionMs,
        )

        val sustained = sample.elapsedRealtimeMs - runStartedAtMs >= SUSTAINED_MS &&
            samplesInRun >= MIN_SAMPLES_IN_RUN
        val budgetLeft = state.swapsUsed < MAX_SWAPS_PER_SESSION

        if (!sustained || !budgetLeft) {
            return Outcome(advanced, shouldDownshift = false)
        }

        // Whatever happens next needs its own settle window before it could be judged.
        return Outcome(
            advanced.copy(
                settledAtMs = null,
                runStartedAtMs = null,
                samplesInRun = 0,
            ),
            shouldDownshift = true,
        )
    }

    /** Records that a swap actually happened. Call this only when one did. */
    fun consumeSwap(state: State): State = state.copy(swapsUsed = state.swapsUsed + 1)

    data class Outcome(val state: State, val shouldDownshift: Boolean)
}

/**
 * Instant's automatic source downshift: the candidate half.
 *
 * A timestamp only carries across releases that are actually the same cut of the same
 * file, so a swap is confined to the current release group. Everything else - different
 * intros, different edits, different framerates - lands the user seconds to minutes away
 * from where they were, which is worse than the stall being avoided.
 */
object AutoDownshiftCandidates {

    /**
     * The best strictly-lower-quality candidate from the same release group, or null when
     * no swap is safe.
     *
     * Null is the common and correct answer. It is returned when the current source has no
     * known release group or resolution, when nothing in the group is lower, and when the
     * current source is a manifest - HLS and DASH adapt inside themselves, so tearing one
     * down would discard a working ladder to solve a problem it is already solving.
     */
    fun select(
        current: PlaybackSourceCandidate,
        candidates: List<PlaybackSourceCandidate>,
    ): PlaybackSourceCandidate? {
        if (isManifest(current.stream)) return null
        val group = current.facts.releaseGroup?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: return null
        val currentHeight = current.facts.resolution?.height ?: return null

        return candidates.asSequence()
            .filterNot { it.stream.playableDirectUrl == current.stream.playableDirectUrl }
            .filterNot { isManifest(it.stream) }
            // Never swap up: the connection just told us it cannot sustain what is playing.
            .filter { (it.facts.resolution?.height ?: Int.MAX_VALUE) < currentHeight }
            .filter { sameReleaseGroup(it.facts, group) }
            // Never trade a working stream for one that has to be cached first.
            .filterNot { it.facts.isDebridReady == false }
            // The highest quality still below the current one - one step down, not a plunge.
            .maxByOrNull { it.facts.resolution?.height ?: 0 }
    }

    private fun sameReleaseGroup(facts: SourceFacts, group: String): Boolean =
        facts.releaseGroup?.trim()?.lowercase() == group

    private fun isManifest(stream: StreamItem): Boolean {
        val url = stream.playableDirectUrl?.trim()?.lowercase() ?: return false
        return ".m3u8" in url || ".mpd" in url
    }
}
