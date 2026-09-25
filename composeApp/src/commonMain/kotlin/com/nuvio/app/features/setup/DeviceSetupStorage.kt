package com.nuvio.app.features.setup

/**
 * This device's own setup revision (Phase 9) - see [SETUP_DEVICE_REVISION] and [setupWizardRun].
 *
 * **Device-local and never synced**, which is the whole point: the profile's revision syncs, so
 * without this a profile finished on one phone would never ask a second phone whether downloads may
 * use mobile data. Its own tiny store rather than a field on the download store, because the app
 * gate reads it before anything else has started and loading the download store starts the engine.
 */
internal expect object DeviceSetupStorage {
    fun loadRevision(): Int?
    fun saveRevision(revision: Int)
}
