package com.nuvio.app.core.sync

import kotlinx.serialization.json.JsonObject

/**
 * The settings Device Setup asks (redesign, 2026-10-24), kept **per profile and per device**: a phone
 * and a tablet, or two desktops, each keep their own quality limit, navigation and player touch.
 *
 * They are still pushed - the family blob is what seeds a device the profile has not set up yet, so
 * its questions start on the profile's latest answers - but once `SetupProfileFlags.deviceSetupDone`
 * holds for the profile here, a pull no longer applies them. Dropping a key from the payload is
 * enough: `replaceFromSyncPayload` clears only the keys a payload carries (`syncKeysToClear`), so a
 * missing key leaves the local value alone.
 *
 * An older client still pushes and pulls these keys; that is harmless - it only means that client
 * keeps following the newest answer, as it always did.
 *
 * Episode alerts are the third device-owned setting: `ProfileSettingsSync` skips applying them.
 */
internal object DeviceOwnedSettings {
    /** In `theme_settings`: the navigation style and its glow, iOS 26's Liquid Glass, the desktop layout. */
    val themeKeys: Set<String> = setOf(
        "nav_bar_style",
        "nav_bar_glow_enabled",
        "liquid_glass_native_tab_bar_enabled",
        "desktop_navigation_layout",
    )

    /** In `player_settings`: the picture (quality limit, HDR, metered cap) and the mobile player's touch. */
    val playerKeys: Set<String> = setOf(
        "playback_quality_ceiling_mbps",
        "playback_dynamic_range_policy",
        "playback_metered_cap_height",
        "use_legacy_player_layout",
        "touch_gestures_enabled",
        "hold_to_speed_enabled",
        "hold_to_speed_value",
    )
}

/** [payload] without [keys] when the device owns them, unchanged otherwise. */
internal fun withoutDeviceOwnedKeys(payload: JsonObject, keys: Set<String>, deviceOwned: Boolean): JsonObject =
    if (deviceOwned) JsonObject(payload.filterKeys { it !in keys }) else payload
