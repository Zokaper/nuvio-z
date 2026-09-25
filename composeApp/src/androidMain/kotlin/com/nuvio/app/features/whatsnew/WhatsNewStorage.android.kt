package com.nuvio.app.features.whatsnew

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.debug.isDebugBuild

internal actual object WhatsNewStorage {
    private const val preferencesName = "nuvio_whats_new"
    private const val lastSeenVersionKey = "last_seen_version"
    private const val ackSerialKey = "ack_serial"
    private const val ackDebugBuildKey = "ack_debug_build"
    private var preferences: SharedPreferences? = null

    actual val isDesktop: Boolean = false

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadLastSeenVersion(): String? =
        preferences?.getString(lastSeenVersionKey, null)

    actual fun saveLastSeenVersion(versionName: String) {
        preferences?.edit()?.putString(lastSeenVersionKey, versionName)?.apply()
    }

    actual val releaseIdentity: WhatsNewReleaseIdentity
        get() = WhatsNewReleaseIdentity(
            family = "mobile",
            serial = AppVersionConfig.RELEASE_SERIAL,
            versionName = AppVersionConfig.VERSION_NAME,
            debugBuild = AppVersionConfig.DEBUG_BUILD.takeIf { isDebugBuild },
            platform = ChangelogPlatform.ANDROID,
        )

    actual fun loadAck(): WhatsNewAck? {
        val prefs = preferences ?: return null
        if (!prefs.contains(ackSerialKey)) return null
        return WhatsNewAck(prefs.getInt(ackSerialKey, 0), prefs.getInt(ackDebugBuildKey, 0))
    }

    actual fun saveAck(ack: WhatsNewAck) {
        preferences?.edit()
            ?.putInt(ackSerialKey, ack.serial)
            ?.putInt(ackDebugBuildKey, ack.debugBuild)
            ?.apply()
    }
}
