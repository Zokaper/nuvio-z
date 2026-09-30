package com.nuvio.app.features.watchparty

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
import platform.UIKit.UIApplicationWillResignActiveNotification
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
        WatchPartyProbeLog.enable()
        val center = NSNotificationCenter.defaultCenter
        fun publishAway(event: String) {
            WatchPartyDiagnostics.transport("ios-lifecycle", WatchPartyRepository.uiState.value.party?.id,
                realtime = "lifecycle", detail = "event=$event away=true")
            if (!_facts.value.appForeground) return
            revision += 1L
            val observedRevision = revision
            _facts.value = _facts.value.copy(appForeground = false)
            WatchPartySync.setLocalPresence(true)
            if (WatchPartyRepository.uiState.value.party == null) return
            endAwayTask(awayTask)
            var task: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid
            task = UIApplication.sharedApplication.beginBackgroundTaskWithName("nuvio.party.away") {
                endAwayTask(task)
            }
            awayTask = task
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    publicationMutex.withLock {
                        if (observedRevision == revision) {
                            withTimeoutOrNull(8_000L) { WatchPartyRepository.setAway(true) }
                        }
                    }
                } finally {
                    endAwayTask(task)
                }
            }
        }
        // The app switcher/lock transition starts here, while the socket can still run. Waiting
        // for DidEnterBackground left the peer send racing suspension after the engine paused.
        center.addObserverForName(UIApplicationWillResignActiveNotification, null, null) {
            publishAway("will-resign-active")
        }
        center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, null) {
            publishAway("did-enter-background")
        }
        center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, null) {
            WatchPartyDiagnostics.transport("ios-lifecycle", WatchPartyRepository.uiState.value.party?.id,
                realtime = "lifecycle", detail = "event=did-become-active away=false")
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

    private fun endAwayTask(task: UIBackgroundTaskIdentifier) {
        if (task == UIBackgroundTaskInvalid) return
        // An older publication finishing must not end a newer background task.
        if (awayTask != task) return
        awayTask = UIBackgroundTaskInvalid
        UIApplication.sharedApplication.endBackgroundTask(task)
    }
}

@Composable
internal actual fun rememberPartyPlatformLifecycle(): PartyPlatformLifecycle {
    val facts by IosPartyLifecycle.facts.collectAsState()
    return facts
}
