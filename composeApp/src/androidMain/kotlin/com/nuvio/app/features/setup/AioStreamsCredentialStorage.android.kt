package com.nuvio.app.features.setup

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

actual object AioStreamsCredentialStorage {
    private const val preferencesName = "nuvio_aiostreams_credentials"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun load(uuid: String): String? = preferences?.getString(ProfileScopedKey.of(uuid), null)

    actual fun save(uuid: String, value: String) {
        preferences?.edit()?.putString(ProfileScopedKey.of(uuid), value)?.apply()
    }

    actual fun remove(uuid: String) {
        preferences?.edit()?.remove(ProfileScopedKey.of(uuid))?.apply()
    }
}
