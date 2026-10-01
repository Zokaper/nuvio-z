package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.rememberLifecycleOwner

/**
 * Gives a root-tab pane the activity of its tab: while [active] is false the pane stays composed (its
 * scroll position and remembered state survive) but is told it cannot be seen.
 *
 * - [LocalScreenActive] is false, so every [ScreenActivityEffect] stops: the Home hero stops paging,
 *   shimmers and loading indicators stop animating, scroll-to-top requests are not collected.
 * - The pane's lifecycle is capped at CREATED, so every `collectAsStateWithLifecycle` inside it stops
 *   collecting until the tab is shown again, when each one resumes with the flow's current value.
 *
 * This is the gating upstream's `RootTabHost`/`RootTabPane` gave every tab. Z's always-mounted Home
 * (`AppTabHost`) lost it in the 0.5.4 merge, which left `LocalScreenActive` with no provider at all -
 * so a hidden Home kept rotating its hero and kept 16 collectors live behind every other tab
 * (Docs/PERFORMANCE-AUDIT-2026-10.md §3.2).
 */
@Composable
internal fun TabPaneActivity(
    active: Boolean,
    content: @Composable () -> Unit,
) {
    val lifecycleOwner = rememberLifecycleOwner(
        maxLifecycle = if (active) Lifecycle.State.RESUMED else Lifecycle.State.CREATED,
    )
    CompositionLocalProvider(
        LocalLifecycleOwner provides lifecycleOwner,
        LocalScreenActive provides (active && LocalScreenActive.current),
        content = content,
    )
}
