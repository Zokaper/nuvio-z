package com.nuvio.app.core.debug

/**
 * Kotlin/Native knows whether it was compiled as a debug binary, so this needs no wiring.
 *
 * The diagnostic tooling itself is Android-only for now - the bandwidth throttle hooks
 * ExoPlayer's `DataSource`, which iOS does not have - but the flag is honest on every target
 * so nothing gated on it accidentally ships enabled.
 */
internal actual val isDebugBuild: Boolean
    get() = kotlin.native.Platform.isDebugBinary

internal actual object PlatformPlaybackDebugTools {
    actual val throttleOptionsMbps: List<Int> = emptyList()
    actual var throttleMbps: Int
        get() = 0
        set(@Suppress("UNUSED_PARAMETER") value) = Unit
}
