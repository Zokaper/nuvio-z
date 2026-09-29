package com.nuvio.app.features.settings

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * "Set up in Settings" from outside Settings: Advanced Setup's links to Integrations, MDBList and
 * Tracking open the real page rather than a copy of it (plan §12 - the personal keys and the
 * connect flows stay in Settings).
 *
 * `MainAppContent` collects these and navigates exactly as its own "connect cloud" shortcut does -
 * a `SettingsPageRoute` on a phone with native navigation, the in-screen page on a tablet or
 * desktop. A request, not a callback, for the reason `AdvancedSetupLauncher` gives: the asker is an
 * overlay drawn by `AppGate`, several upstream composables away from the navigation controller.
 */
object ZSettingsNavigation {
    private val requestChannel = Channel<String>(Channel.CONFLATED)

    internal val requests: Flow<String> = requestChannel.receiveAsFlow()

    /** [page] by constant name - the same name `SettingsPageRoute` and saved state use. */
    internal fun open(page: SettingsPage) {
        requestChannel.trySend(page.name)
    }
}
