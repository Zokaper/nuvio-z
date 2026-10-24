package com.nuvio.app.features.setup

import androidx.compose.runtime.Composable

/**
 * Advanced Setup content only a platform can draw, registered at startup like
 * `SocialSettingsExtensions`: the shared screen knows the area exists, the desktop app knows the
 * tray and the Windows `Run` key. Null where the area does not apply, which drops it from the hub.
 */
object AdvancedSetupExtensions {
    /** Desktop: This computer (Startup & tray, 2026-10-22). */
    var startupAndTray: AdvancedSetupPlatformArea? = null

    /**
     * The platform's Social alerts (2026-10-24): the phone's alert switches (`PhoneSocialAlerts`), the
     * desktop's pop-ups. Both belong to the installation, not the profile.
     */
    var alerts: AdvancedSetupPlatformArea? = null
}

class AdvancedSetupPlatformArea(
    /** The hub card's one-line summary of the current answers. */
    val summary: @Composable () -> String,
    /** The panel's controls. */
    val controls: @Composable () -> Unit,
    /** The panel's preview; `desktop` is the side-by-side pane rather than a phone band. */
    val preview: @Composable (desktop: Boolean) -> Unit,
)
