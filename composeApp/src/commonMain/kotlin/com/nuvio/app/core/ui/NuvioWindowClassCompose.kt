package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp

/**
 * The Compose half of [NuvioWindowClass].
 *
 * Separate from the classifier itself so that file can stay import-free and be run by group 2 of
 * `scripts/run-pure-suites.sh`. Nothing here decides anything; it only converts `Dp` to the plain
 * numbers the rules are written in.
 */

/**
 * Classify the window from the constraints this `BoxWithConstraints` was given.
 *
 * ⚠ **Read it from the `BoxWithConstraints`, not from the display.** `NuvioTheme` scales density on
 * desktop, so the window's platform size and the *composition* dp a layout divides are different
 * numbers. Deriving a breakpoint from anything but the constraints in hand is the same two-numbers
 * mistake `socialFeedMetrics` already carries a warning about.
 */
@Composable
fun BoxWithConstraintsScope.nuvioWindowClass(): NuvioWindowClass =
    NuvioWindowClass(widthDp = maxWidth.value, heightDp = maxHeight.value)

/** Build a class from a pair of [Dp] that were measured somewhere else. */
fun nuvioWindowClassOf(width: Dp, height: Dp): NuvioWindowClass =
    NuvioWindowClass(widthDp = width.value, heightDp = height.value)

/**
 * The window class of the whole app window, provided once by the shell.
 *
 * For screens that are not already inside a `BoxWithConstraints` and only need to know what kind of
 * device they are on. A screen that lays out against its own width should prefer
 * [nuvioWindowClass], because a pane is not the window.
 *
 * Defaults to a portrait phone rather than to a desktop: a wrong default that folds content is
 * recoverable on sight, and one that unfolds it hides the fault until somebody opens the app on a
 * phone.
 */
val LocalNuvioWindowClass = staticCompositionLocalOf {
    NuvioWindowClass(widthDp = 411f, heightDp = 891f)
}
