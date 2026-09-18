package com.nuvio.app

import android.os.Build

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
}

actual fun getPlatform(): Platform = AndroidPlatform()

internal actual val isIos: Boolean = false
internal actual val isDesktop: Boolean = false
internal actual val isWindows: Boolean = false
actual fun platformDisplayMaxHeight(): Int? = runCatching {
    android.content.res.Resources.getSystem().displayMetrics.let {
        minOf(it.widthPixels, it.heightPixels)
    }
}.getOrNull()
