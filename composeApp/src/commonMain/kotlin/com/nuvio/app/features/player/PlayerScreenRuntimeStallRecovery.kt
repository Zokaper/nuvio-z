package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import co.touchlab.kermit.Logger
import com.nuvio.app.core.debug.isDebugBuild
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.network.PlatformLink
import com.nuvio.app.core.network.PlatformLinkState
import com.nuvio.app.features.downloads.LocalPlaybackPolicy
import com.nuvio.app.features.playback.PlaybackStallMonitor
import kotlin.time.TimeSource
import kotlinx.coroutines.delay

/**
 * `adb logcat -s PlaybackStall` / the desktop debug log. Every verdict transition, and on request
 * the whole sample trace as CSV - the evidence S0 of `PLAN-unhappy-paths.md` is gathered from.
 */
private val stallLog = Logger.withTag("PlaybackStall")

private const val STALL_SAMPLE_INTERVAL_MS = 500L

/** Ten minutes at [STALL_SAMPLE_INTERVAL_MS]: enough to hold a reported loop and what preceded it. */
private const val STALL_TRACE_CAPACITY = 1_200

/**
 * The live stall verdict and its recent samples, for the diagnostics HUD and for trace export.
 *
 * Process-wide rather than a field on [PlayerScreenRuntime] because there is only ever one player,
 * and a field would widen the `PlayerScreenRuntime*` patch surface for a debug instrument.
 */
internal object PlaybackStallTrace {
    var latest by mutableStateOf<PlaybackStallMonitor.State?>(null)
        internal set
    var starvedVerdicts by mutableStateOf(0)
        internal set

    private val samples = ArrayDeque<String>()

    internal fun reset() {
        latest = null
        starvedVerdicts = 0
        samples.clear()
    }

    internal fun record(sample: PlaybackStallMonitor.Sample, state: PlaybackStallMonitor.State) {
        latest = state
        if (samples.size >= STALL_TRACE_CAPACITY) samples.removeFirst()
        samples.addLast(
            listOf(
                sample.elapsedMs,
                sample.positionMs,
                sample.bufferedPositionMs,
                sample.wantsToPlay,
                sample.engineSaysBuffering,
                sample.seekGeneration,
                sample.isManifest,
                sample.isLocal,
                sample.isHeld,
                sample.link,
                sample.internet,
                state.verdict,
                state.reason ?: "",
            ).joinToString(","),
        )
    }

    /** Writes the trace to the log as CSV, in chunks a log line can carry. */
    fun dumpToLog(engineName: String) {
        stallLog.i { "trace begin engine=$engineName samples=${samples.size}" }
        stallLog.i {
            "csv:elapsedMs,positionMs,bufferedMs,wantsToPlay,engineBuffering,seekGen," +
                "manifest,local,held,link,internet,verdict,reason"
        }
        samples.chunked(40).forEach { chunk ->
            stallLog.i { chunk.joinToString(separator = "\n") { "csv:$it" } }
        }
        stallLog.i { "trace end" }
    }
}

/**
 * Shadow mode (S0): the stall monitor runs over every play once its first frame is up and reports
 * what it *would* decide - to the HUD and the log - without acting. Nothing here changes playback.
 * The monitor is trusted to act only after its verdicts have been checked against real engines.
 */
@Composable
internal fun PlayerScreenRuntime.BindStallMonitorShadow() {
    LaunchedEffect(activeSourceUrl, firstFrameReached) {
        PlaybackStallTrace.reset()
        if (!isDebugBuild || !firstFrameReached) return@LaunchedEffect
        val clock = TimeSource.Monotonic.markNow()
        val sourceUrl = activeSourceUrl
        val isLocal = LocalPlaybackPolicy.isLocalSource(sourceUrl)
        val normalizedUrl = sourceUrl.lowercase()
        val isManifest = ".m3u8" in normalizedUrl || ".mpd" in normalizedUrl
        var state = PlaybackStallMonitor.initial(
            nowMs = 0L,
            positionMs = playbackSnapshot.positionMs,
        )
        var lastVerdict = state.verdict
        var probeRequested = false
        stallLog.i { "monitor armed: source=$activeStreamTitle local=$isLocal manifest=$isManifest" }
        while (true) {
            delay(STALL_SAMPLE_INTERVAL_MS)
            val snapshot = playbackSnapshot
            val condition = NetworkStatusRepository.uiState.value.condition
            val sample = PlaybackStallMonitor.Sample(
                elapsedMs = clock.elapsedNow().inWholeMilliseconds,
                positionMs = snapshot.positionMs,
                bufferedPositionMs = snapshot.bufferedPositionMs,
                wantsToPlay = shouldPlay && !snapshot.isEnded,
                engineSaysBuffering = snapshot.isLoading ||
                    snapshot.engineReadiness == PlayerEngineReadiness.Buffering,
                isManifest = isManifest,
                isLocal = isLocal,
                link = when (PlatformLink.state.value) {
                    PlatformLinkState.Up -> PlaybackStallMonitor.Link.Up
                    PlatformLinkState.Down -> PlaybackStallMonitor.Link.Down
                    PlatformLinkState.Unknown -> PlaybackStallMonitor.Link.Unknown
                },
                internet = when (condition) {
                    NetworkCondition.Online -> PlaybackStallMonitor.Internet.Online
                    NetworkCondition.NoInternet -> PlaybackStallMonitor.Internet.Offline
                    else -> PlaybackStallMonitor.Internet.Unknown
                },
            )
            state = PlaybackStallMonitor.observe(state, sample)
            PlaybackStallTrace.record(sample, state)
            if (state.wantsConnectivityProbe && !probeRequested) {
                NetworkStatusRepository.requestRefresh(force = true)
            }
            probeRequested = state.wantsConnectivityProbe
            if (state.verdict != lastVerdict) {
                stallLog.i {
                    "verdict ${lastVerdict.name} -> ${state.verdict.name} reason=${state.reason} " +
                        "elapsed=${sample.elapsedMs}ms pos=${sample.positionMs}ms ahead=${sample.aheadMs}ms " +
                        "episodes=${state.episodeStartsMs.size} frozen=${state.stallFrozenMs}ms " +
                        "network=$condition engine=${snapshot.engineName} source=$activeStreamTitle"
                }
                lastVerdict = state.verdict
            }
            if (state.verdict == PlaybackStallMonitor.Verdict.SourceStarved) {
                PlaybackStallTrace.starvedVerdicts += 1
                stallLog.w {
                    "shadow: would act on ${state.reason} for $activeStreamTitle " +
                        "(not acting - S0 shadow mode)"
                }
                state = PlaybackStallMonitor.rearm(state, sample.elapsedMs)
                lastVerdict = state.verdict
            }
        }
    }
}
