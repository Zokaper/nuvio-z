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

    /**
     * Payloads written per profile before the device-wide store (Phase 9), keyed by profile.
     * Only desktop ever wrote those; Android and iOS always kept one device payload, and answer
     * with nothing. Read once, when there is no device payload yet, and never deleted.
     */
    fun loadLegacyProfilePayloads(): Map<Int, String>
}
