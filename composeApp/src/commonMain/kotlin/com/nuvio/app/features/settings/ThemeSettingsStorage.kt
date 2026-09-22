package com.nuvio.app.features.settings

import kotlinx.serialization.json.JsonObject

internal expect object ThemeSettingsStorage {
    fun loadSelectedTheme(): String?
    fun saveSelectedTheme(themeName: String)
    fun loadCustomThemeColors(): String?
    fun saveCustomThemeColors(colors: String)
    fun loadAmoledEnabled(): Boolean?
    fun saveAmoledEnabled(enabled: Boolean)
    fun loadLiquidGlassNativeTabBarEnabled(): Boolean?
    fun saveLiquidGlassNativeTabBarEnabled(enabled: Boolean)
    fun loadDesktopNavigationLayout(): String?
    fun saveDesktopNavigationLayout(layoutName: String)

    /**
     * Interface zoom, as a percentage of the automatic scale. Desktop-only and **device-local**.
     *
     * ⚠ Deliberately absent from [exportToSyncPayload] and [replaceFromSyncPayload], unlike every
     * other setting on this object. The right zoom is a property of the *display*, not of the
     * profile: syncing it would push a 4K desktop's 100% onto a laptop, or a laptop's onto the
     * desktop, and one of the two would be wrong every time. `selectedAppLanguage` is the existing
     * precedent for a key that stays on the device.
     *
     * The Android and iOS actuals are stubs. They exist only because this is a common `expect`
     * object, which is already the shape `loadDesktopNavigationLayout` takes.
     */
    fun loadDesktopUiZoomPercent(): Int?
    fun saveDesktopUiZoomPercent(percent: Int)
    fun loadSelectedAppLanguage(): String?
    fun saveSelectedAppLanguage(languageCode: String)
    fun applySelectedAppLanguage(languageCode: String)
    fun loadNavBarStyle(): String?
    fun saveNavBarStyle(styleKey: String)
    fun exportToSyncPayload(): JsonObject
    fun replaceFromSyncPayload(payload: JsonObject)
}
