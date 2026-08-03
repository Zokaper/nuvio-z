package com.nuvio.app.features.downloads

internal expect object DownloadsStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)

    /**
     * Sets aside a payload that could not be parsed.
     *
     * Decoding falls back to an empty payload, which the next save then overwrites -
     * so without this a single unreadable field would quietly destroy every download,
     * batch and preset with no way to get them back.
     */
    fun saveCorruptPayload(payload: String)
}
