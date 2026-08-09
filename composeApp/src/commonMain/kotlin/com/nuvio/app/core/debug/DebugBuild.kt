package com.nuvio.app.core.debug

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * True only in a developer build, false in anything shipped.
 *
 * Everything gated on this is diagnostic scaffolding - the playback HUD, the bandwidth
 * throttle, the forced source-swap actions. It exists so that mid-playback quality switching
 * can be *measured* on a real device before its thresholds are tuned or it is turned on by
 * default, rather than guessed at from a passing test suite.
 *
 * Read it at the point of use rather than caching it: on Android it is only meaningful after
 * [com.nuvio.app.core.debug.AndroidDebugBuild.initialize] has run in `MainActivity.onCreate`.
 */
internal expect val isDebugBuild: Boolean

/** Session-only controls for the playback diagnostics surface. */
internal object PlaybackDebugSettings {
    var hudEnabled by mutableStateOf(false)
}

/** Platform hook for the Android ExoPlayer bandwidth limiter. */
internal expect object PlatformPlaybackDebugTools {
    val throttleOptionsMbps: List<Int>
    var throttleMbps: Int
}
