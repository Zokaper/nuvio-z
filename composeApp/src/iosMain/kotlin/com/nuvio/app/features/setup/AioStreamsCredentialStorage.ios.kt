package com.nuvio.app.features.setup

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults

actual object AioStreamsCredentialStorage {
    private fun key(uuid: String) = ProfileScopedKey.of("aiostreams_credentials_$uuid")

    actual fun load(uuid: String): String? = NSUserDefaults.standardUserDefaults.stringForKey(key(uuid))

    actual fun save(uuid: String, value: String) {
        NSUserDefaults.standardUserDefaults.setObject(value, forKey = key(uuid))
    }

    actual fun remove(uuid: String) {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(key(uuid))
    }
}
