package com.nuvio.app.core.ui

import com.nuvio.app.features.social.SocialRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSUserDefaults

private const val nativeSocialBadgeKey = "NuvioNativeSocialBadge"
private const val nativeTabChromeDidChangeNotification = "NuvioNativeTabChromeDidChange"

/**
 * The unread count on the native iOS tab bar's Social item (2026-10-18). The Compose shells draw the same
 * number as a dot (`socialUnreadCount`); the native bar is SwiftUI, so the count crosses the way the tab
 * titles and the profile icon do: a `UserDefaults` value and the chrome-changed notification, which
 * `NativeTabIconStore` already observes.
 *
 * Process-wide and started once: every tab has its own Compose controller, and the badge must not follow
 * whichever one happens to be alive. The repository is empty while Social is off, so the badge clears with it.
 */
internal object NativeSocialBadge {
    private var started = false

    fun start() {
        if (started) return
        started = true
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            SocialRepository.uiState.map { it.unreadCount }.distinctUntilChanged().collect { count ->
                NSUserDefaults.standardUserDefaults.setInteger(count.toLong(), forKey = nativeSocialBadgeKey)
                NSNotificationCenter.defaultCenter.postNotificationName(nativeTabChromeDidChangeNotification, null)
            }
        }
    }
}
