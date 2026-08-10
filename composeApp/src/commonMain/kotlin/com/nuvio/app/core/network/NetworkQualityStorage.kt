package com.nuvio.app.core.network

/**
 * Where measured network estimates survive a process death.
 *
 * Without this every cold start threw away everything the app had learned and fell back to
 * [NetworkQualityRepository]'s connection-type presets, so the first play of every session
 * was decided by a guess no matter how much had been measured the day before.
 *
 * One opaque blob rather than a key per estimate: the set is keyed by network *and* provider
 * and is pruned by age and size on write, so it is only ever read and written whole.
 * Deliberately **not** wired into `LocalAccountDataCleaner` - how fast a Wi-Fi network is is a
 * property of the device, not of whoever is signed in.
 */
internal expect object NetworkQualityStorage {
    fun loadEstimatesJson(): String?
    fun saveEstimatesJson(json: String)
}
