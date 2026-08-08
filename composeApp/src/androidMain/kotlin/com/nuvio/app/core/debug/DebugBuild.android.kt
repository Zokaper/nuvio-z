package com.nuvio.app.core.debug

import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * Reads the manifest's debuggable flag rather than a generated `BuildConfig`.
 *
 * `composeApp` is a library module with `buildConfig` switched off, so `BuildConfig.DEBUG` is
 * not available here without changing the build. `FLAG_DEBUGGABLE` is set by AGP on exactly
 * the debug build type and cleared on release, which is the same signal without the build
 * change - and it is the flag the platform itself uses, so it cannot drift from reality.
 */
object AndroidDebugBuild {
    @Volatile
    private var debuggable: Boolean = false

    fun initialize(context: Context) {
        val flags = context.applicationContext.applicationInfo.flags
        debuggable = (flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    internal val value: Boolean
        get() = debuggable
}

internal actual val isDebugBuild: Boolean
    get() = AndroidDebugBuild.value
