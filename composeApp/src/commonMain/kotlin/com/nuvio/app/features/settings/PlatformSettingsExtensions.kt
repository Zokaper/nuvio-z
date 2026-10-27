package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable

/**
 * Z: rows a platform adds to an upstream-owned Settings page, registered at startup like
 * [SocialSettingsExtensions], so the upstream page carries one call rather than the rows themselves.
 */
object PlatformSettingsExtensions {
    /**
     * Desktop (2026-10-22): closing the window and Start with Windows, under Advanced → Startup beside
     * "Remember last profile". They used to sit in Social settings; they are about the app, not friends.
     */
    var startupRows: (@Composable (isTablet: Boolean) -> Unit)? = null
}
