package com.nuvio.app.features.social

import android.content.Context
import android.content.SharedPreferences

actual object SocialStorage {
    private var preferences: SharedPreferences? = null
    fun initialize(context: Context) {
        preferences = context.getSharedPreferences("nuvio_social", Context.MODE_PRIVATE)
    }
    actual fun loadPayload(profileId: String): String? = preferences?.getString("state_$profileId", null)
    actual fun savePayload(profileId: String, payload: String) { preferences?.edit()?.putString("state_$profileId", payload)?.apply() }
    actual fun loadOutbox(profileId: String): String? = preferences?.getString("outbox_$profileId", null)
    actual fun saveOutbox(profileId: String, payload: String) { preferences?.edit()?.putString("outbox_$profileId", payload)?.apply() }
}

