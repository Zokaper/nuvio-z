package com.nuvio.app.features.setup

import android.content.Context
import android.content.SharedPreferences

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
}
