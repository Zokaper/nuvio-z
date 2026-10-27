package com.nuvio.app.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Device Setup's settings stay this device's own once it is set up (redesign, 2026-10-24). */
class DeviceOwnedSettingsTest {
    private val player = buildJsonObject {
        put("playback_quality_ceiling_mbps", 25)
        put("playback_dynamic_range_policy", "SDR_ONLY")
        put("use_legacy_player_layout", true)
        put("skip_intro_enabled", true)
        put("preferred_audio_language", "en")
    }

    @Test
    fun aDeviceNotSetUpYetFollowsTheProfile() {
        // The blob is what seeds a new device's questions, so nothing is dropped before setup.
        assertEquals(player, withoutDeviceOwnedKeys(player, DeviceOwnedSettings.playerKeys, deviceOwned = false))
    }

    @Test
    fun aSetUpDeviceKeepsItsOwnPictureAndTouchButFollowsEverythingElse() {
        val pulled = withoutDeviceOwnedKeys(player, DeviceOwnedSettings.playerKeys, deviceOwned = true)
        assertFalse("playback_quality_ceiling_mbps" in pulled)
        assertFalse("playback_dynamic_range_policy" in pulled)
        assertFalse("use_legacy_player_layout" in pulled)
        // Profile-wide settings still sync.
        assertEquals(JsonPrimitive(true), pulled["skip_intro_enabled"])
        assertEquals(JsonPrimitive("en"), pulled["preferred_audio_language"])
    }

    @Test
    fun aMissingKeyIsNeverClearedLocally() {
        // `replaceFromSyncPayload` clears only what the payload carries, which is what makes dropping
        // a key leave the local value alone.
        val pulled = withoutDeviceOwnedKeys(player, DeviceOwnedSettings.playerKeys, deviceOwned = true)
        val keys = DeviceOwnedSettings.playerKeys.toList() + "skip_intro_enabled"
        assertEquals(listOf("skip_intro_enabled"), syncKeysToClear(keys, pulled))
    }

    @Test
    fun navigationIsDeviceOwnedButThemeIsNot() {
        assertTrue("nav_bar_style" in DeviceOwnedSettings.themeKeys)
        assertTrue("desktop_navigation_layout" in DeviceOwnedSettings.themeKeys)
        assertFalse("selected_theme" in DeviceOwnedSettings.themeKeys)
        assertFalse("amoled_enabled" in DeviceOwnedSettings.themeKeys)
    }
}
