package com.nuvio.app.features.whatsnew

import android.content.Context
import android.content.SharedPreferences

internal actual object WhatsNewStorage {
    private const val preferencesName = "nuvio_whats_new"
    private const val lastSeenVersionKey = "last_seen_version"
    private var preferences: SharedPreferences? = null

    actual val isDesktop: Boolean = false

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadLastSeenVersion(): String? =
        preferences?.getString(lastSeenVersionKey, null)

    actual fun saveLastSeenVersion(versionName: String) {
        preferences?.edit()?.putString(lastSeenVersionKey, versionName)?.apply()
    }
}
