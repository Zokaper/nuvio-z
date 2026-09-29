package com.nuvio.app.features.watchparty

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid

/** Installed when the iOS controller is created, independently of the Compose display link. */
internal object IosPartyLifecycle {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val publicationMutex = Mutex()
    private val _facts = MutableStateFlow(
        PartyPlatformLifecycle(
            appForeground = UIApplication.sharedApplication.applicationState !=
                UIApplicationState.UIApplicationStateBackground,
        ),
    )
    val facts = _facts.asStateFlow()
    private var started = false
    private var revision = 0L
    private var awayTask: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid

    fun start() {
        if (started) return
        started = true
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, null) {
            revision += 1L
            val observedRevision = revision
            _facts.value = _facts.value.copy(appForeground = false)
            WatchPartySync.setLocalPresence(true)
            if (WatchPartyRepository.uiState.value.party == null) return@addObserverForName
            awayTask = UIApplication.sharedApplication.beginBackgroundTaskWithName("nuvio.party.away") {
                endAwayTask()
            }
            scope.launch {
                try {
                    publicationMutex.withLock {
                        if (observedRevision == revision) {
                            withTimeoutOrNull(8_000L) { WatchPartyRepository.setAway(true) }
                        }
                    }
                } finally {
                    endAwayTask()
                }
            }
        }
        center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, null) {
            revision += 1L
            _facts.value = _facts.value.copy(
                appForeground = true,
                resumeRevision = _facts.value.resumeRevision + 1L,
            )
            WatchPartySync.setLocalPresence(false)
            scope.launch {
                publicationMutex.withLock {
                    WatchPartyRepository.setAway(false)
                    if (WatchPartyRepository.uiState.value.party != null) WatchPartyRepository.refresh()
                }
            }
        }
    }

    private fun endAwayTask() {
        val task = awayTask
        if (task == UIBackgroundTaskInvalid) return
        awayTask = UIBackgroundTaskInvalid
        UIApplication.sharedApplication.endBackgroundTask(task)
    }
}

@Composable
internal actual fun rememberPartyPlatformLifecycle(): PartyPlatformLifecycle {
    val facts by IosPartyLifecycle.facts.collectAsState()
    return facts
}
