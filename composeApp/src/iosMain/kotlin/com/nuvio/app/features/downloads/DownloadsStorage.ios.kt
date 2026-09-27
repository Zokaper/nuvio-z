package com.nuvio.app.features.downloads

import platform.Foundation.NSUserDefaults

internal actual object DownloadsStorage {
    private const val payloadKey = "downloads_payload"
    private const val corruptPayloadKey = "downloads_payload_corrupt"
    private const val titleMetadataKey = "downloads_title_metadata"

    actual fun loadPayload(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(payloadKey)

    actual fun savePayload(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = payloadKey)
    }

    actual fun saveCorruptPayload(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = corruptPayloadKey)
    }

    // The payload here was always device-wide; there is nothing per-profile to merge.
    actual fun loadLegacyProfilePayloads(): Map<Int, String> = emptyMap()

    actual fun loadTitleMetadata(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(titleMetadataKey)

    actual fun saveTitleMetadata(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = titleMetadataKey)
    }
}
