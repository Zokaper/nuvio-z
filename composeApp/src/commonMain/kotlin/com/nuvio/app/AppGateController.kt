package com.nuvio.app

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

class AppGateController {
    private val profileSelectionChannel = Channel<Unit>(Channel.BUFFERED)
    private val runSetupAgainChannel = Channel<Unit>(Channel.BUFFERED)
    private val whatsNewChannel = Channel<Unit>(Channel.BUFFERED)
    private val _mainContentReady = MutableStateFlow(false)
    private val _contentGeneration = MutableStateFlow(0)

    internal val profileSelectionRequests = profileSelectionChannel.receiveAsFlow()
    internal val runSetupAgainRequests = runSetupAgainChannel.receiveAsFlow()
    internal val whatsNewRequests = whatsNewChannel.receiveAsFlow()
    internal val mainContentReady = _mainContentReady.asStateFlow()
    internal val contentGeneration = _contentGeneration.asStateFlow()

    fun requestProfileSelection() {
        profileSelectionChannel.trySend(Unit)
    }

    fun requestRunSetupAgain() {
        runSetupAgainChannel.trySend(Unit)
    }

    fun requestWhatsNew() {
        whatsNewChannel.trySend(Unit)
    }

    /**
     * Advanced Setup, for a native host (iOS tabs) that has no Settings row of its own to press. The
     * same process-wide request the Settings row and What's New make - see `AdvancedSetupLauncher`.
     */
    fun requestAdvancedSetup() {
        com.nuvio.app.features.setup.AdvancedSetupLauncher.open()
    }

    internal fun beginContentReload() {
        _mainContentReady.value = false
        _contentGeneration.update { it + 1 }
    }

    internal fun reportMainContentReady(ready: Boolean) {
        _mainContentReady.value = ready
    }
}
