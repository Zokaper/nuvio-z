package com.nuvio.app.features.downloads

import platform.Foundation.NSUserDefaults

internal actual object DownloadsStorage {
    private const val payloadKey = "downloads_payload"
    private const val corruptPayloadKey = "downloads_payload_corrupt"

    actual fun loadPayload(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(payloadKey)

    actual fun savePayload(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = payloadKey)
    }

    actual fun saveCorruptPayload(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = corruptPayloadKey)
    }
}
