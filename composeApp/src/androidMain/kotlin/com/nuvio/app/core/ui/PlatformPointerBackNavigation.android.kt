package com.nuvio.app.core.ui

import androidx.compose.ui.Modifier

/**
 * No pointer back button exists on a touch device, so this is the identity modifier.
 *
 * Back on this platform arrives through `PlatformBackHandler` as the system gesture or button,
 * and is already handled there. Synthesising a gesture here would invent an affordance the
 * platform does not have.
 */
actual fun Modifier.platformPointerBackNavigation(onBack: () -> Unit): Modifier = this

/** Identity: a touch device has no pointer Back/Forward buttons to suppress. */
actual fun Modifier.platformPointerNavigationGuard(): Modifier = this

/** Identity: hover previews are a pointer affordance and never open on a touch device. */
actual fun Modifier.platformPointerScrollDismiss(onScroll: () -> Unit): Modifier = this
