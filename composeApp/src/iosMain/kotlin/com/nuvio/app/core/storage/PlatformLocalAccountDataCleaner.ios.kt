@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nuvio.app.core.storage

import platform.Foundation.NSUserDefaults
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import com.nuvio.app.features.profiles.MAX_PROFILES

/**
 * Signing out on iOS. Every store lives in one NSUserDefaults domain, so the rule is a key rule -
 * [isAccountScopedDefaultsKey]: every profile-scoped key (`<base>_<profileIndex>`), the named
 * unscoped account keys and prefixes, and never the device keys. The hand-written base-key list this
 * replaced was missing most player, theme and TMDB keys.
 */
internal actual object PlatformLocalAccountDataCleaner {
    actual fun wipe() {
        val defaults = NSUserDefaults.standardUserDefaults

        for (key in defaults.dictionaryRepresentation().keys) {
            val keyString = key as? String ?: continue
            if (isAccountScopedDefaultsKey(keyString, MAX_PROFILES)) {
                defaults.removeObjectForKey(keyString)
            }
        }

        val scraperCodePath = "${NSHomeDirectory()}/Library/Application Support/nuvio_plugin_scrapers"
        if (NSFileManager.defaultManager.fileExistsAtPath(scraperCodePath)) {
            NSFileManager.defaultManager.removeItemAtPath(scraperCodePath, null)
        }
        val membershipPath = "${NSHomeDirectory()}/Library/Application Support/NuvioMembership"
        if (NSFileManager.defaultManager.fileExistsAtPath(membershipPath)) {
            NSFileManager.defaultManager.removeItemAtPath(membershipPath, null)
        }
    }
}
