package com.nuvio.app.features.social

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A place inside the Social tab that something outside it can send the user to. */
sealed interface SocialNavigationTarget {
    /** Friend requests: the Friends view on a phone, the rail (which leads with them) on wide layouts. */
    data object FriendRequests : SocialNavigationTarget

    /** The Inbox page: party invites and requests to join your playback wait there with their actions. */
    data object Inbox : SocialNavigationTarget

    /** One friend's session: its join sheet, which closes itself if the session has ended. */
    data class WatchingSession(val sessionKey: String) : SocialNavigationTarget
}

/**
 * "Open Nuvio on the relevant place" for a Social notification (desktop pop-ups now, Android push next).
 *
 * A held value rather than a channel: a desktop pop-up clicked while Nuvio sits in the tray asks first and
 * the window is created after, so the request has to wait for a Social tab that does not exist yet.
 * `MainAppContent` switches to the tab; [SocialFeed] opens the place and [consume]s it.
 */
object SocialNavigation {
    private val _pending = MutableStateFlow<SocialNavigationTarget?>(null)
    val pending: StateFlow<SocialNavigationTarget?> = _pending.asStateFlow()

    fun open(target: SocialNavigationTarget) {
        _pending.value = target
    }

    /** Clears [target] if it is still the pending one; a newer request is left for the next reader. */
    fun consume(target: SocialNavigationTarget) {
        _pending.compareAndSet(target, null)
    }
}

/** The session key [SocialNavigationTarget.WatchingSession] carries; the same key the Social tab uses. */
fun WatchingNowItem.socialNavigationKey(): String = socialSessionKey()
