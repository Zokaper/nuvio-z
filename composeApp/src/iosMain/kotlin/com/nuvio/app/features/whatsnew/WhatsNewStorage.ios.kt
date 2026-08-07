package com.nuvio.app.features.whatsnew

import platform.Foundation.NSUserDefaults

internal actual object WhatsNewStorage {
    private const val lastSeenVersionKey = "nuvio_whats_new_last_seen_version"

    actual val isDesktop: Boolean = false

    actual fun loadLastSeenVersion(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(lastSeenVersionKey)

    actual fun saveLastSeenVersion(versionName: String) {
        NSUserDefaults.standardUserDefaults.setObject(versionName, forKey = lastSeenVersionKey)
    }
}
