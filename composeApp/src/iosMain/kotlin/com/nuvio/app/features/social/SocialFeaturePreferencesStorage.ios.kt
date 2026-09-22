package com.nuvio.app.features.social

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults

internal actual object SocialFeaturePreferencesStorage {
    private const val payloadKey = "social_features"

    actual fun loadPayload(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(
            payload,
            forKey = ProfileScopedKey.of(payloadKey),
        )
    }
}
