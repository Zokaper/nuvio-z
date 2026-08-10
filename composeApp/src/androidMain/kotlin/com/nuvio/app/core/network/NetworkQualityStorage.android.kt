package com.nuvio.app.core.network

import android.content.Context
import android.content.SharedPreferences

internal actual object NetworkQualityStorage {
    private const val preferencesName = "nuvio_network_quality"
    private const val estimatesKey = "estimates_json"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    // Not profile-scoped, unlike most storages here. The measured speed of a network belongs
    // to the device, and scoping it would make the first play on every profile a guess again.
    actual fun loadEstimatesJson(): String? = preferences?.getString(estimatesKey, null)

    actual fun saveEstimatesJson(json: String) {
        preferences?.edit()?.putString(estimatesKey, json)?.apply()
    }
}
