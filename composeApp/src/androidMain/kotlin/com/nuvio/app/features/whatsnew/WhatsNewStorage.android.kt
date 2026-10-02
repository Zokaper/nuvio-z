package com.nuvio.app.features.whatsnew

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.debug.isDebugBuild

internal actual object WhatsNewStorage {
    private const val preferencesName = "nuvio_whats_new"

    // Written by earlier builds; read for migration only, never rewritten.
    private const val lastSeenVersionKey = "last_seen_version"
    private const val ackSerialKey = "ack_serial"

    private const val ackSeqKey = "ack_seq"
    private const val ackSeenKey = "ack_seen"
    private const val viewedSeqKey = "viewed_seq"
    private const val viewedSeenKey = "viewed_seen"
    private const val ackDebugBuildKey = "ack_debug_build"
    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual val releaseIdentity: WhatsNewReleaseIdentity
        get() = WhatsNewReleaseIdentity(
            family = "mobile",
            serial = AppVersionConfig.RELEASE_SERIAL,
            versionName = AppVersionConfig.VERSION_NAME,
            debugBuild = AppVersionConfig.DEBUG_BUILD.takeIf { isDebugBuild },
            platform = ChangelogPlatform.ANDROID,
        )

    actual fun load(): StoredWhatsNew {
        val prefs = preferences ?: return StoredWhatsNew()
        fun int(key: String): Int? = if (prefs.contains(key)) runCatching { prefs.getInt(key, 0) }.getOrNull() else null
        fun seen(seqKey: String, seenKey: String): SeenEvents? =
            int(seqKey)?.let { SeenEvents(it.coerceAtLeast(0), SeenEvents.decodeAbove(prefs.getString(seenKey, null))) }
        return StoredWhatsNew(
            acknowledged = seen(ackSeqKey, ackSeenKey),
            viewed = seen(viewedSeqKey, viewedSeenKey),
            debugBuild = int(ackDebugBuildKey),
            legacySerial = int(ackSerialKey),
            legacyLastSeenVersion = runCatching { prefs.getString(lastSeenVersionKey, null) }.getOrNull(),
        )
    }

    actual fun save(state: WhatsNewState) {
        preferences?.edit()
            ?.putInt(ackSeqKey, state.acknowledged.floor)
            ?.putString(ackSeenKey, SeenEvents.encodeAbove(state.acknowledged.above))
            ?.putInt(viewedSeqKey, state.viewed.floor)
            ?.putString(viewedSeenKey, SeenEvents.encodeAbove(state.viewed.above))
            ?.putInt(ackDebugBuildKey, state.debugBuild)
            ?.apply()
    }
}
