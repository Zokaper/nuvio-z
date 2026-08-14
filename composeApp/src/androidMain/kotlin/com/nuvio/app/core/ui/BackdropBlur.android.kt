package com.nuvio.app.core.ui

import android.os.Build

/**
 * ⚠ **API 31.** `RenderEffect.createBlurEffect` arrives in Android 12; below it both Haze and
 * `Modifier.blur` are no-ops rather than approximations. `minSdk` is 24, so this is false on a
 * real share of installs and not a theoretical branch.
 */
internal actual fun isBackdropBlurSupported(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
