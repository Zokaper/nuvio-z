package com.nuvio.app.features.autosync

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoSyncAccountResetTest {
    @Test
    fun wipingAnAccountReloadsPreferencesEvenWhenTheProfileIndexStaysTheSame() {
        var saved: Boolean? = true
        AutoSyncPreferencesRepository.installPersistence(load = { saved }, save = { saved = it })
        try {
            AutoSyncPreferencesRepository.ensureLoaded()
            assertTrue(AutoSyncPreferencesRepository.preferredSubtitleAutoSyncOnStart.value)
            saved = null // Platform storage has been wiped.
            AutoSyncPreferencesRepository.clearLocalState()
            AutoSyncPreferencesRepository.ensureLoaded()
            assertFalse(AutoSyncPreferencesRepository.preferredSubtitleAutoSyncOnStart.value)
        } finally {
            AutoSyncPreferencesRepository.installPersistence(load = { null }, save = {})
            AutoSyncPreferencesRepository.clearLocalState()
        }
    }
}
