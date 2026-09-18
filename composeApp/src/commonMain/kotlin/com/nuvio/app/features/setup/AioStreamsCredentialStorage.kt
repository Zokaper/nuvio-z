package com.nuvio.app.features.setup

/**
 * The AIOStreams uuid and password for each install the recommended setup made, keyed by uuid and
 * scoped to the profile.
 *
 * Kept so a later template change (a rename, a dropped preset) can be applied to existing installs
 * through AIOStreams' own API instead of asking everyone to re-run setup. Installs made before
 * 2026-09-17 were never recorded and cannot be updated this way.
 */
internal expect object AioStreamsCredentialStorage {
    fun load(uuid: String): String?
    fun save(uuid: String, value: String)
    fun remove(uuid: String)
}
