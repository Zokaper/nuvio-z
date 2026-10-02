package com.nuvio.app.features.whatsnew

import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.debug.isDebugBuild
import platform.Foundation.NSUserDefaults

internal actual object WhatsNewStorage {
    // Written by earlier builds; read for migration only, never rewritten.
    private const val lastSeenVersionKey = "nuvio_whats_new_last_seen_version"
    private const val ackSerialKey = "nuvio_whats_new_ack_serial"

    private const val ackSeqKey = "nuvio_whats_new_ack_seq"
    private const val ackSeenKey = "nuvio_whats_new_ack_seen"
    private const val viewedSeqKey = "nuvio_whats_new_viewed_seq"
    private const val viewedSeenKey = "nuvio_whats_new_viewed_seen"
    private const val ackDebugBuildKey = "nuvio_whats_new_ack_debug_build"

    /**
     * ⚠ `debugBuild` rides on `Platform.isDebugBinary`. If the debug IPA is ever compiled as a
     * release binary, the "This debug build" section simply does not appear on iPhone.
     */
    actual val releaseIdentity: WhatsNewReleaseIdentity
        get() = WhatsNewReleaseIdentity(
            family = "mobile",
            serial = AppVersionConfig.RELEASE_SERIAL,
            versionName = AppVersionConfig.VERSION_NAME,
            debugBuild = AppVersionConfig.DEBUG_BUILD.takeIf { isDebugBuild },
            platform = ChangelogPlatform.IOS,
        )

    actual fun load(): StoredWhatsNew {
        val defaults = NSUserDefaults.standardUserDefaults
        fun int(key: String): Int? = if (defaults.objectForKey(key) == null) null else defaults.integerForKey(key).toInt()
        fun seen(seqKey: String, seenKey: String): SeenEvents? =
            int(seqKey)?.let { SeenEvents(it.coerceAtLeast(0), SeenEvents.decodeAbove(defaults.stringForKey(seenKey))) }
        return StoredWhatsNew(
            acknowledged = seen(ackSeqKey, ackSeenKey),
            viewed = seen(viewedSeqKey, viewedSeenKey),
            debugBuild = int(ackDebugBuildKey),
            legacySerial = int(ackSerialKey),
            legacyLastSeenVersion = defaults.stringForKey(lastSeenVersionKey),
        )
    }

    actual fun save(state: WhatsNewState) {
        val defaults = NSUserDefaults.standardUserDefaults
        defaults.setInteger(state.acknowledged.floor.toLong(), forKey = ackSeqKey)
        defaults.setObject(SeenEvents.encodeAbove(state.acknowledged.above), forKey = ackSeenKey)
        defaults.setInteger(state.viewed.floor.toLong(), forKey = viewedSeqKey)
        defaults.setObject(SeenEvents.encodeAbove(state.viewed.above), forKey = viewedSeenKey)
        defaults.setInteger(state.debugBuild.toLong(), forKey = ackDebugBuildKey)
    }
}
