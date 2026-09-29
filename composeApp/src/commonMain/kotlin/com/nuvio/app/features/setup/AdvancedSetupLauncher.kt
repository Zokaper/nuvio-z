package com.nuvio.app.features.setup

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The one way into Advanced Setup, from anywhere: Settings, the Done page, What's New.
 *
 * ⚠ **A process-wide request rather than a callback threaded through the shell.** The Settings row
 * lives several upstream-owned composables below `AppGate` (`MainAppContent` → `SettingsScreen` →
 * the root page), and on iOS the settings content runs in a *different* `AppGate` from the one that
 * draws overlays (`bypassAppGate` in the native-tab host, `AppGateOverlay` above it). "Run setup
 * again" is threaded through every one of those and iOS has lost it once on the way. A request the
 * overlay gate collects needs none of that plumbing, and it cannot be lost by a host that forgot to
 * pass a lambda. `AppGateController.requestAdvancedSetup()` is the same request for native hosts.
 *
 * The gate decides whether it can open now: the hub never covers a gating wizard or the profile
 * picker, it waits for them (`AppGate`).
 */
object AdvancedSetupLauncher {
    private val requestChannel = Channel<Unit>(Channel.CONFLATED)

    internal val requests: Flow<Unit> = requestChannel.receiveAsFlow()

    fun open() {
        requestChannel.trySend(Unit)
    }
}

/**
 * Whether the active profile has opened Advanced Setup on this device: the Settings row's "New"
 * badge (plan §8). Per profile and device-local, stored in [DeviceSetupStorage].
 *
 * A flow because the badge is on screen when the hub opens over it and must clear without a
 * restart; the value itself stays in storage, which remains the one source of truth.
 */
internal object AdvancedSetupBadge {
    private val _opened = MutableStateFlow(true)
    val opened: StateFlow<Boolean> = _opened.asStateFlow()

    /** Re-read for [profileId]; called when Settings shows the row and on profile switches. */
    fun refresh(profileId: Int) {
        _opened.value = runCatching { SetupProfileFlags.advancedSetupOpened(profileId) }.getOrDefault(true)
    }

    fun markOpened(profileId: Int) {
        runCatching { SetupProfileFlags.markAdvancedSetupOpened(profileId) }
        _opened.value = true
    }
}
