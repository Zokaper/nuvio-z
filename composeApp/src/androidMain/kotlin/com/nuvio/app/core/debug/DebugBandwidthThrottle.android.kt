package com.nuvio.app.core.debug

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * A debug-only ceiling on playback download speed, so "I walked downstairs" becomes a button.
 *
 * Without this, every judgement about buffering and automatic quality switching rests on
 * whether someone happened to have a bad connection at the right moment. With it, the exact
 * degradation can be applied mid-episode, repeated, and varied.
 *
 * Important: this only affects ExoPlayer. `AndroidPlaybackEngine.Auto` starts on ExoPlayer but
 * falls back to libmpv on failure, and libmpv does its own I/O well below this layer. A tester
 * who has silently fallen back will see the throttle do nothing - which is why the diagnostics
 * overlay reports which engine is live.
 */
object DebugBandwidthThrottle {
    /** Megabits per second, or 0 for no limit. */
    @Volatile
    var megabitsPerSecond: Int = 0

    val isActive: Boolean get() = megabitsPerSecond > 0

    /** The options offered in the overlay: off, then a slow-to-unusable ladder. */
    val OPTIONS = listOf(0, 20, 10, 5, 2)

    fun label(mbps: Int): String = if (mbps <= 0) "Off" else "$mbps Mbps"
}

/**
 * Wraps a factory so every source it produces obeys [DebugBandwidthThrottle].
 *
 * The rate is read per-read rather than captured, so changing it takes effect on the stream
 * already playing - no player rebuild, no interruption that would be confused with the swap
 * being measured.
 */
@UnstableApi
class ThrottledDataSourceFactory(
    private val upstream: DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = ThrottledDataSource(upstream.createDataSource())
}

@UnstableApi
private class ThrottledDataSource(
    private val upstream: DataSource,
) : DataSource {

    private var windowStartedAtMs: Long = 0L
    private var bytesInWindow: Long = 0L
    private var lastLimitMbps: Int = 0

    override fun open(dataSpec: DataSpec): Long {
        windowStartedAtMs = System.currentTimeMillis()
        bytesInWindow = 0L
        return upstream.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val mbps = DebugBandwidthThrottle.megabitsPerSecond
        if (mbps <= 0) {
            lastLimitMbps = 0
            return upstream.read(buffer, offset, length)
        }

        if (mbps != lastLimitMbps) {
            windowStartedAtMs = System.currentTimeMillis()
            bytesInWindow = 0L
            lastLimitMbps = mbps
        }

        // Cap each read so the sleep below stays short and the limit is felt smoothly rather
        // than as one long stall every few hundred kilobytes.
        val bytesPerSecond = mbps * 1_000_000L / 8L
        val maxChunk = (bytesPerSecond / 20L).coerceAtLeast(1_024L).toInt()
        val read = upstream.read(buffer, offset, minOf(length, maxChunk))
        if (read <= 0) return read

        bytesInWindow += read
        val elapsedMs = System.currentTimeMillis() - windowStartedAtMs
        val owedMs = bytesInWindow * 1_000L / bytesPerSecond
        val sleepMs = owedMs - elapsedMs
        if (sleepMs > 0) {
            try {
                Thread.sleep(sleepMs.coerceAtMost(500L))
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        // Roll the window so a long stretch at the limit cannot accumulate unbounded debt and
        // then release a burst the moment the throttle is switched off.
        if (elapsedMs > 2_000L) {
            windowStartedAtMs = System.currentTimeMillis()
            bytesInWindow = 0L
        }
        return read
    }

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()
}

internal actual object PlatformPlaybackDebugTools {
    actual val throttleOptionsMbps: List<Int>
        get() = DebugBandwidthThrottle.OPTIONS

    actual var throttleMbps: Int
        get() = DebugBandwidthThrottle.megabitsPerSecond
        set(value) {
            DebugBandwidthThrottle.megabitsPerSecond = value.coerceAtLeast(0)
        }
}
