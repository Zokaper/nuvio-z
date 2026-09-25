package com.nuvio.app.features.whatsnew

import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.debug.isDebugBuild
import platform.Foundation.NSUserDefaults

internal actual object WhatsNewStorage {
    private const val lastSeenVersionKey = "nuvio_whats_new_last_seen_version"
    private const val ackSerialKey = "nuvio_whats_new_ack_serial"
    private const val ackDebugBuildKey = "nuvio_whats_new_ack_debug_build"

    actual val isDesktop: Boolean = false

    actual fun loadLastSeenVersion(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(lastSeenVersionKey)

    actual fun saveLastSeenVersion(versionName: String) {
        NSUserDefaults.standardUserDefaults.setObject(versionName, forKey = lastSeenVersionKey)
    }

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

    actual fun loadAck(): WhatsNewAck? {
        val defaults = NSUserDefaults.standardUserDefaults
        if (defaults.objectForKey(ackSerialKey) == null) return null
        return WhatsNewAck(
            serial = defaults.integerForKey(ackSerialKey).toInt(),
            debugBuild = defaults.integerForKey(ackDebugBuildKey).toInt(),
        )
    }

    actual fun saveAck(ack: WhatsNewAck) {
        val defaults = NSUserDefaults.standardUserDefaults
        defaults.setInteger(ack.serial.toLong(), forKey = ackSerialKey)
        defaults.setInteger(ack.debugBuild.toLong(), forKey = ackDebugBuildKey)
    }
}
