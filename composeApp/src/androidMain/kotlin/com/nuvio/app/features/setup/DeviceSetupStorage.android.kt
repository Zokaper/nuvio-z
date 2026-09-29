package com.nuvio.app.features.setup

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object DeviceSetupStorage {
    private const val preferencesName = "nuvio_device_setup"
    private const val revisionKey = "device_setup_revision"
    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadRevision(): Int? =
        preferences?.takeIf { it.contains(revisionKey) }?.getInt(revisionKey, 0)

    actual fun saveRevision(revision: Int) {
        preferences?.edit()?.putInt(revisionKey, revision)?.apply()
    }

    actual fun loadProfileFlag(flag: String, profileId: Int): Boolean =
        preferences?.getBoolean(ProfileScopedKey.of(flag, profileId), false) ?: false

    actual fun saveProfileFlag(flag: String, profileId: Int, value: Boolean) {
        preferences?.edit()?.putBoolean(ProfileScopedKey.of(flag, profileId), value)?.apply()
    }
}
