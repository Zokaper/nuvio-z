package com.nuvio.app.features.setup

/**
 * This device's own setup revision (Phase 9) - see [SETUP_DEVICE_REVISION] and [setupWizardRun].
 *
 * **Device-local and never synced**, which is the whole point: the profile's revision syncs, so
 * without this a profile finished on one phone would never ask a second phone whether downloads may
 * use mobile data. Its own tiny store rather than a field on the download store, because the app
 * gate reads it before anything else has started and loading the download store starts the engine.
 *
 * It also carries two **per-profile** flags of this installation (setup + settings pass): whether a
 * profile imported from the other platform family still owes its Device Setup here, and whether a
 * profile has opened Advanced Setup (the "New" badge). Both are account data for signing out -
 * `LocalStoreRegistry` keeps only the revision.
 */
internal expect object DeviceSetupStorage {
    fun loadRevision(): Int?
    fun saveRevision(revision: Int)
    fun loadProfileFlag(flag: String, profileId: Int): Boolean
    fun saveProfileFlag(flag: String, profileId: Int, value: Boolean)
}

/** The per-profile flags [DeviceSetupStorage] carries. Names are storage keys: never rename one. */
internal object SetupProfileFlags {
    private const val ARRIVAL_PENDING = "setup_arrival_pending"
    private const val ADVANCED_SETUP_OPENED = "advanced_setup_opened"

    fun arrivalPending(profileId: Int): Boolean = DeviceSetupStorage.loadProfileFlag(ARRIVAL_PENDING, profileId)

    fun setArrivalPending(profileId: Int, pending: Boolean) =
        DeviceSetupStorage.saveProfileFlag(ARRIVAL_PENDING, profileId, pending)

    fun advancedSetupOpened(profileId: Int): Boolean =
        DeviceSetupStorage.loadProfileFlag(ADVANCED_SETUP_OPENED, profileId)

    fun markAdvancedSetupOpened(profileId: Int) =
        DeviceSetupStorage.saveProfileFlag(ADVANCED_SETUP_OPENED, profileId, true)
}
