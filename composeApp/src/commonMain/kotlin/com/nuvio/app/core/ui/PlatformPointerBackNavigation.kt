package com.nuvio.app.core.ui

import androidx.compose.ui.Modifier

/**
 * Back issued by a **pointing device** -- the thumb button on a mouse.
 *
 * This is the pointer sibling of [PlatformBackHandler], which carries Back as the *system* gesture
 * or button. The two are not alternatives: a platform may have either, both or neither, and the
 * caller attaches both without knowing which one the device it is running on will use.
 *
 * It exists as a seam because the API that reads a mouse button, `PointerButton`, is JVM-only in
 * Compose. Shared UI referenced it directly, which compiled on desktop and does not exist on the
 * Android or iOS targets at all. Naming a mouse button from generic shared UI was the actual defect;
 * satisfying the compiler was not the point.
 *
 * On touch platforms the implementation is deliberately `this`, unchanged. That is not a stub
 * standing in for absent work: a phone has no pointer back button, and its Back already arrives
 * through [PlatformBackHandler]. Adding a synthetic gesture here would invent an affordance the
 * platform does not have.
 */
expect fun Modifier.platformPointerBackNavigation(onBack: () -> Unit): Modifier

/**
 * Swallow the pointing device's Back and Forward buttons over this area.
 *
 * The player uses it so a mouse thumb button cannot navigate out from under playback. It is a
 * suppression, not a handler: there is no callback, because the whole point is that nothing
 * happens. On touch platforms it is `this` for the same reason as above -- there are no such
 * buttons to suppress, and the system Back is handled deliberately elsewhere.
 */
expect fun Modifier.platformPointerNavigationGuard(): Modifier

/**
 * Dismiss on a scroll of the pointing device -- the wheel.
 *
 * Attached to hover previews, which open on hover and should get out of the way the moment the
 * reader scrolls. `onPointerEvent` is JVM-only in Compose, hence the seam.
 *
 * `this` on touch platforms: hover previews are a pointer affordance and never open there, so
 * there is nothing to dismiss.
 */
expect fun Modifier.platformPointerScrollDismiss(onScroll: () -> Unit): Modifier
