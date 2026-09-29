package com.nuvio.app.core.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalStoreRegistryTest {
    @Test
    fun everyStoreIsListedOnce() {
        val names = LocalStoreRegistry.stores.map { it.name }
        assertEquals(names.toSet().size, names.size)
    }

    @Test
    fun devicePreferencesSurviveAndEverythingElseIsWiped() {
        listOf("nuvio_whats_new", "nuvio_downloads", "server_configuration", "nuvio_window_state", "nuvio_desktop_renderer")
            .forEach { assertFalse(LocalStoreRegistry.isWiped(it), it) }
        listOf("nuvio_tmdb_settings", "nuvio_aiostreams_credentials", "nuvio_player_settings", "nuvio_social_features", "nuvio_official_session")
            .forEach { assertTrue(LocalStoreRegistry.isWiped(it), it) }
    }

    @Test
    fun anUnclassifiedStoreIsWipedRatherThanLeftForTheNextAccount() {
        assertTrue(LocalStoreRegistry.isWiped("nuvio_some_future_store"))
    }

    @Test
    fun deviceKeysInsideAccountStoresArePreserved() {
        assertEquals(setOf("selected_app_language"), LocalStoreRegistry.find("nuvio_theme_settings")?.preservedKeys)
        assertEquals(setOf("device_setup_revision"), LocalStoreRegistry.find("nuvio_device_setup")?.preservedKeys)
    }

    @Test
    fun iosProfileScopedKeysAreRemovedForEveryProfileIndex() {
        listOf("tmdb_api_key_1", "selected_theme_5", "nav_bar_style_3", "library_payload_2", "social_features_enabled_4")
            .forEach { assertTrue(isAccountScopedDefaultsKey(it, maxProfiles = 5), it) }
    }

    @Test
    fun iosIndexesOutsideTheProfileRangeAndNonSnakeKeysAreLeftAlone() {
        assertFalse(isAccountScopedDefaultsKey("tmdb_api_key_6", maxProfiles = 5))
        assertFalse(isAccountScopedDefaultsKey("tmdb_api_key_0", maxProfiles = 5))
        assertFalse(isAccountScopedDefaultsKey("AppleLanguages_1", maxProfiles = 5))
        assertFalse(isAccountScopedDefaultsKey("com.apple.something", maxProfiles = 5))
        assertFalse(isAccountScopedDefaultsKey("_1", maxProfiles = 5))
        assertFalse(isAccountScopedDefaultsKey("key_", maxProfiles = 5))
    }

    @Test
    fun iosDeviceKeysSurviveEvenInTheirLegacyProfileScopedShape() {
        assertFalse(isAccountScopedDefaultsKey("nuvio_device_setup_revision", maxProfiles = 5))
        assertFalse(isAccountScopedDefaultsKey("selected_app_language", maxProfiles = 5))
        assertFalse(isAccountScopedDefaultsKey("downloads_payload_2", maxProfiles = 5))
        assertFalse(isAccountScopedDefaultsKey("server_backend_url", maxProfiles = 5))
    }

    @Test
    fun iosUnscopedAccountDataIsRemoved() {
        listOf("profile_payload", "anonymous_user_id", "social_state_abc-123", "stream_link_xyz", "profile_pin_cache_2")
            .forEach { assertTrue(isAccountScopedDefaultsKey(it, maxProfiles = 5), it) }
    }
}
