package com.nuvio.app.core.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PlatformLinkState { Up, Down, Unknown }

/**
 * Whether the operating system says this device has a network path at all, pushed the moment it
 * changes.
 *
 * [NetworkStatusRepository] only learns about connectivity when something asks it to probe, and
 * before this only Android asked on a real change (from the downloads lifecycle). iOS and desktop
 * found out on the next foreground or the next manual retry, so a viewer who lost Wi-Fi mid-film
 * watched a spinner that blamed nobody. Every platform now reports here, and every change triggers
 * a forced probe - with no route the probe fails within milliseconds, so "offline" is known almost
 * as soon as the OS knows it.
 *
 * [state] is the raw OS view and is deliberately not debounced: a Wi-Fi-to-cellular hand-off can
 * report Down for an instant, and the only consumer that acts on Down - the stall monitor - acts by
 * *not* blaming a source, which is the safe direction to err in. Anything user-facing reads
 * [NetworkStatusRepository], which the probe confirms.
 */
object PlatformLink {
    private val _state = MutableStateFlow(PlatformLinkState.Unknown)
    val state: StateFlow<PlatformLinkState> = _state.asStateFlow()

    private var started = false

    /**
     * Arms the platform's own monitor where one is lazily created (iOS's path monitor lives in
     * [NetworkQualityPlatform] and starts on first use). Desktop starts its monitor from `main`.
     */
    fun ensureStarted() {
        if (started) return
        started = true
        NetworkQualityPlatform.current()
    }

    /** Called by the platform monitor on every change it sees; repeats are ignored. */
    fun report(up: Boolean) {
        val next = if (up) PlatformLinkState.Up else PlatformLinkState.Down
        if (_state.value == next) return
        _state.value = next
        NetworkStatusRepository.requestRefresh(force = true)
    }
}
