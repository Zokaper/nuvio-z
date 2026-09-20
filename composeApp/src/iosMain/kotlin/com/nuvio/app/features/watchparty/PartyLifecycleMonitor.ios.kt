package com.nuvio.app.features.watchparty

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationState

/**
 * The shared Away semantics, with the one iOS signal that means the same thing they do.
 *
 * `didEnterBackground` rather than `willResignActive`, which is what `AppForegroundMonitor.ios`
 * uses: resigning active is also a notification shade, a control centre, an incoming-call banner
 * and the app switcher being scrubbed through, and none of those is somebody walking away from a
 * film. Entering the background is. This is the same argument the desktop actual makes about window
 * focus, and it is why Away has its own monitor rather than borrowing that one.
 *
 * [PartyPlatformLifecycle.screenLocked] stays false. iOS has `protectedDataWillBecomeUnavailable`,
 * but it fires only on a device with a passcode and not at all for a simple lock, so it would be a
 * signal that is right sometimes and silently absent the rest of the time - worse than the
 * background notification that already covers the case as [PartyAwayReason.Background]. Nothing
 * here is verified on a device; see the iOS caveat in `STATUS.md`.
 */
@Composable
internal actual fun rememberPartyPlatformLifecycle(): PartyPlatformLifecycle {
    var facts by remember {
        mutableStateOf(
            PartyPlatformLifecycle(
                appForeground = UIApplication.sharedApplication.applicationState !=
                    UIApplicationState.UIApplicationStateBackground,
            ),
        )
    }
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val foreground = center.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = null,
        ) { _ -> facts = facts.copy(appForeground = true) }
        val background = center.addObserverForName(
            name = UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = null,
        ) { _ -> facts = facts.copy(appForeground = false) }
        onDispose {
            center.removeObserver(foreground)
            center.removeObserver(background)
        }
    }
    return facts
}
