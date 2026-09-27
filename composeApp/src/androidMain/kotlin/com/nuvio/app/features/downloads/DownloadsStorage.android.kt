package com.nuvio.app.features.downloads

import android.content.Context
import android.content.SharedPreferences

internal actual object DownloadsStorage {
    private const val preferencesName = "nuvio_downloads"
    private const val payloadKey = "downloads_payload"
    private const val corruptPayloadKey = "downloads_payload_corrupt"
    private const val titleMetadataKey = "downloads_title_metadata"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadPayload(): String? =
        preferences?.getString(payloadKey, null)

    actual fun savePayload(payload: String) {
        preferences
            ?.edit()
            ?.putString(payloadKey, payload)
            ?.apply()
    }

    actual fun saveCorruptPayload(payload: String) {
        preferences
            ?.edit()
            ?.putString(corruptPayloadKey, payload)
            ?.apply()
    }

    // The payload here was always device-wide; there is nothing per-profile to merge.
    actual fun loadLegacyProfilePayloads(): Map<Int, String> = emptyMap()

    actual fun loadTitleMetadata(): String? =
        preferences?.getString(titleMetadataKey, null)

    actual fun saveTitleMetadata(payload: String) {
        preferences
            ?.edit()
            ?.putString(titleMetadataKey, payload)
            ?.apply()
    }
}
