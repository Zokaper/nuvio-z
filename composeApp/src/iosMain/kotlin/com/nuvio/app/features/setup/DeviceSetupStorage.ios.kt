package com.nuvio.app.features.setup

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults

internal actual object DeviceSetupStorage {
    private const val revisionKey = "nuvio_device_setup_revision"

    actual fun loadRevision(): Int? {
        val defaults = NSUserDefaults.standardUserDefaults
        if (defaults.objectForKey(revisionKey) == null) return null
        return defaults.integerForKey(revisionKey).toInt()
    }

    actual fun saveRevision(revision: Int) {
        NSUserDefaults.standardUserDefaults.setInteger(revision.toLong(), forKey = revisionKey)
    }

    // `nuvio_<flag>_<profileIndex>`: the profile-scoped shape the sign-out key rule removes.
    actual fun loadProfileFlag(flag: String, profileId: Int): Boolean =
        NSUserDefaults.standardUserDefaults.boolForKey(ProfileScopedKey.of("nuvio_$flag", profileId))

    actual fun saveProfileFlag(flag: String, profileId: Int, value: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(value, forKey = ProfileScopedKey.of("nuvio_$flag", profileId))
    }
}
