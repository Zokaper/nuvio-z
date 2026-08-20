package com.nuvio.app.core.debug

import com.nuvio.app.navigation.AppRoute

/**
 * The seam between the debug self-test harness and the running app.
 *
 * The harness itself lives entirely in `desktopMain`, because a suite that drives a native mpv
 * handle and screen-grabs an AWT window has nothing to say to Android or iOS. What it cannot do
 * from there is reach the navigator: the back stack is built inside `App.kt` in `commonMain` and
 * is not a singleton, so it has to be published outwards.
 *
 * ⚠ **This file is deliberately plain.** No `expect`, no `actual`, no platform types beyond
 * [AppRoute]. `commonMain` is byte-identical across `nuvio-z` and `NuvioZDesktop` and every line
 * here is paid for twice; worse, an `expect` added here would need an `actual` on **four** targets
 * and a missing desktop one has broken that build twice already. Nullable properties set by
 * whichever platform cares is the whole mechanism.
 *
 * On Android and iOS every field stays null, which is what hides the settings row - see
 * `AdvancedSettingsPage`. Adding a mobile harness later means populating these, not changing them.
 */
internal object SelfTestHooks {

    /**
     * Starts a self-test run. Non-null only on desktop, and only in a debug build.
     *
     * Doubles as the availability flag: the settings row checks this for null rather than asking
     * a platform question, which is what keeps this file free of `expect`.
     */
    var launch: (() -> Unit)? = null

    /** Navigates the real back stack, so the harness exercises the routes a user would. */
    var navigate: ((AppRoute) -> Unit)? = null

    /** Pops the real back stack. Used to assert that one Back leaves the player. */
    var popBackStack: (() -> Unit)? = null

    /**
     * The route currently on top.
     *
     * This is what makes the pointer-input check mechanical rather than a matter of looking: click
     * a point that should be inert, read this, and assert it did not change. That fault - a
     * full-screen overlay without `nuvioConsumePointerEvents()` leaving the surface underneath
     * fully tappable - has now shipped twice, and both times it took someone on a device to find.
     */
    var currentRoute: (() -> AppRoute?)? = null
}
