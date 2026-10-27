package com.nuvio.app.features.social

import android.content.Context
import android.content.SharedPreferences

internal actual object PhoneSocialAlertsStorage {
    private const val preferencesName = "nuvio_social_alerts"
    private const val payloadKey = "phone_alerts"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun load(): String? = preferences?.getString(payloadKey, null)

    actual fun save(payload: String) {
        preferences?.edit()?.putString(payloadKey, payload)?.apply()
    }
}

internal actual val phoneSocialAlertsDescription: String =
    "Banners in the app and system notifications when it is closed"
