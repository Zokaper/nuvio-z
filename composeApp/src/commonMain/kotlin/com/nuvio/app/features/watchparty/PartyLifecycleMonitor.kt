package com.nuvio.app.features.watchparty

import androidx.compose.runtime.Composable

/**
 * The platform's half of [PartyLifecycleFacts]: whether this app is in front of the user.
 *
 * Deliberately **not** `core/sync/AppForegroundMonitor`, although the Android actual watches the
 * same `ProcessLifecycleOwner`. That monitor exists to pause a background sync poll, and its
 * desktop actual answers `Background` the instant the window loses focus - which is correct for a
 * poll and would be a disaster here: alt-tabbing out of the desktop player would tell the party
 * you had stepped away from a film that never stopped playing. Away is a statement about a person,
 * so it gets a signal chosen for that question on each platform rather than a borrowed one.
 *
 * Picture-in-picture and "is the video actually still running" are not here, because this cannot
 * know them - the player does, and it supplies them in `PlayerWatchPartyEffect`. Everything here is
 * a fact; every decision made from one is in `PartyPresence.kt`.
 */
internal data class PartyPlatformLifecycle(
    val appForeground: Boolean,
    /**
     * The device is locked, or its screen is off.
     *
     * Separate from [appForeground] even though Android usually stops the activity as well: the
     * two are genuinely different reasons and a run that flapped between them is far easier to read
     * when the log says which. Platforms that cannot answer it say false, which leaves the case to
     * [PartyAwayReason.Background] - the same answer, one word less specific.
     */
    val screenLocked: Boolean = false,
)

/**
 * A Composable rather than a flow, for the same reason `rememberIsInPictureInPicture` is one: the
 * Android actual needs `LocalContext`, and every consumer of this is inside the player's
 * composition anyway. It follows that convention rather than introducing a second one beside it.
 */
@Composable
internal expect fun rememberPartyPlatformLifecycle(): PartyPlatformLifecycle
