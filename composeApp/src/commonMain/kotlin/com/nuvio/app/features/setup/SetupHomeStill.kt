package com.nuvio.app.features.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioNavigationBar
import com.nuvio.app.core.ui.PosterCardStyleRepository
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.home.components.HomeCatalogRowSection
import com.nuvio.app.features.home.components.HomeContinueWatchingSection
import com.nuvio.app.features.home.components.continueWatchingHeroViewportReserveHeight
import com.nuvio.app.features.home.components.rememberContinueWatchingLayout
import com.nuvio.app.features.home.components.HomeHeroSection
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_nav_downloads
import nuvio.composeapp.generated.resources.compose_nav_home
import nuvio.composeapp.generated.resources.compose_nav_library
import nuvio.composeapp.generated.resources.compose_nav_search
import nuvio.composeapp.generated.resources.setup_still_catalog_row
import nuvio.composeapp.generated.resources.sidebar_library
import nuvio.composeapp.generated.resources.sidebar_search
import org.jetbrains.compose.resources.stringResource

/**
 * A still of the home screen, drawn with the app's **real** composables.
 *
 * ## ⚠ This is the one file under `features/setup/` that is NOT byte-identical across the
 * ## repositories, and it must never be `cp`'d
 *
 * `NuvioZDesktop`'s `HomeContinueWatchingSection` takes a **required** `dataSourceKey:
 * WatchProgressSource` in third position that this repository's does not, and imports it from
 * `features.tracking` rather than `features.trakt`. Its `HomeHeroSection` also inserts an
 * optional `sectionPadding` mid-list, and `NuvioShelfSection` reorders `rowModifier`. **Named
 * arguments at every call below** are what stops a positional argument binding to the wrong slot
 * when this is ported. Port by hand; the two copies differ by three hunks.
 *
 * ## Why the real composables here, when every step specimen deliberately avoids them
 *
 * Revision 2 rendered a whole fake home and details screen from the shipped composables and was
 * pulled for three reasons - none of which apply to this screen:
 *
 * 1. Those composables read their settings repositories *internally*, so a choice the user made
 *    snapped instead of tweening. **The Welcome step has no controls.** Nothing changes while it
 *    is on screen, so there is nothing to tween.
 * 2. Most of a full screen had nothing to do with the one control being changed. **The Welcome
 *    step is not about a control** - it is answering "what is this?", and the whole screen is
 *    the answer.
 * 3. It could not be kept byte-identical. Still true, and accepted: fidelity is the entire point
 *    of this screen, and the divergence is quarantined to this one file.
 *
 * `SetupSpecimen.kt` keeps drawing from primitives for steps 2-8 and stays identical in both
 * repositories. That split is deliberate - do not merge them.
 *
 * ## What it is not
 *
 * Not scrollable, not interactive, and it fetches nothing. The nav bar is drawn because its
 * absence is most of what made the hand-drawn revision-5 miniature read as "not the app", but
 * its items go nowhere. With no network Coil draws nothing and what remains is the layout over
 * the gradient floor, which is the state to check in aeroplane mode.
 */
@Composable
fun SetupHomeStill(
    visibleHeightBelowTop: Dp,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val posterStyle by remember {
        PosterCardStyleRepository.ensureLoaded()
        PosterCardStyleRepository.uiState
    }.collectAsStateWithLifecycle()
    val continueWatching by remember {
        ContinueWatchingPreferencesRepository.ensureLoaded()
        ContinueWatchingPreferencesRepository.uiState
    }.collectAsStateWithLifecycle()

    val catalogRowTitle = stringResource(Res.string.setup_still_catalog_row)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            // The still runs under the status bar the way the real home screen does; only the
            // nav bar reads an inset, and it reads its own.
            .clipToBounds()
            .background(
                // A floor, so a still whose artwork has not loaded - or cannot, with no network -
                // reads as a dimmed screen rather than a broken one.
                Brush.verticalGradient(
                    listOf(tokens.colors.surfaceElevated, tokens.colors.background),
                ),
            ),
    ) {
        // ⚠ **The visible viewport, not the window.** The panel covers the bottom of this screen,
        // and `mobileHeroHeight` takes 82% of whatever `viewportHeight` it is given: passing the
        // window height sized a hero for space the user cannot see, and the banner filled the
        // entire visible band. Revision 6 did exactly that.
        val visibleViewport = (maxHeight - visibleHeightBelowTop).coerceAtLeast(MinVisibleViewport)

        // ⚠ **The app's own arithmetic for "leave room for the row underneath".** `HomeScreen`
        // computes its hint the same way and passes it to the same parameter; without it the
        // hero has nothing to cap against. Reusing the helper rather than picking a number is
        // what keeps the still honest when the Continue Watching style changes its height.
        val continueWatchingReserve = continueWatchingHeroViewportReserveHeight(
            style = continueWatching.style,
            layout = rememberContinueWatchingLayout(maxWidth.value),
            basePosterWidthDp = posterStyle.widthDp,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                // Laid out in the band above the panel. The Box behind this stays full-bleed so
                // the panel's blur still has something to sample.
                .padding(bottom = visibleHeightBelowTop)
                // The "scroll down a bit" this needed: with the hero capped, a nudge upward puts
                // the catalog row under Continue Watching into view rather than leaving it at
                // the seam. Small, because the hero's logo and buttons are the part that says
                // "this is the app" and cropping them defeats the point.
                .offset(y = -StillScrollOffset),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HomeHeroSection(
                items = SetupSampleTitle.rowItems,
                viewportHeight = visibleViewport,
                mobileBelowSectionHeightHint = continueWatchingReserve,
            )

            HomeContinueWatchingSection(
                items = SetupSampleTitle.continueWatching,
                style = continueWatching.style,
                useEpisodeThumbnails = continueWatching.useEpisodeThumbnails,
                blurNextUp = continueWatching.blurNextUp,
                // No `title`: the section falls back to the same string resource the real home
                // screen shows it under, so this cannot drift from it.
            )

            HomeCatalogRowSection(
                section = SetupSampleTitle.catalogSection(catalogRowTitle),
            )

            // A second row so the still reads as a screen that continues below the fold rather
            // than as two isolated shelves. Its own key - `NuvioShelfSection` dedupes by key.
            HomeCatalogRowSection(
                section = SetupSampleTitle.secondCatalogSection(catalogRowTitle),
            )

            Spacer(
                modifier = Modifier.height(
                    WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding(),
                ),
            )
        }

        SetupStillNavigationBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // Sits just above the panel rather than at the window's bottom edge, where it
                // would be hidden behind it entirely.
                .padding(bottom = visibleHeightBelowTop),
        )
    }
}

/**
 * How far the still is nudged up so the row under Continue Watching comes into view.
 *
 * ⚠ Deliberately small. The hero's logo is the part that makes this read as the app, and cropping
 * it to show one more row trades the thing being shown for the thing being listed.
 */
private val StillScrollOffset = 28.dp

/** A floor, so a mis-measured panel can never collapse the still to nothing. */
private val MinVisibleViewport = 260.dp

/**
 * The floating nav pill, drawn for looks only.
 *
 * No `hazeState`: the pill would then blur the still behind it, which is correct in the app but
 * competes with the wizard's own frosted panel sitting a few dp below it. `NuvioNavigationBar`
 * already deepens its own tint to `0.82f` when no haze state is supplied, so this is the shape
 * the bar is designed to take without one rather than a special case.
 *
 * ⚠ Home is drawn selected because that is the tab a first launch lands on. The Settings tab in
 * the real bar is a `ProfileSwitcherTab`, which reads `ProfileRepository`; it is left out rather
 * than faked, and four items still read as the app's nav bar.
 */
@Composable
private fun SetupStillNavigationBar(modifier: Modifier = Modifier) {
    NuvioNavigationBar(modifier = modifier.fillMaxWidth()) {
        NavItem(
            selected = true,
            onClick = {},
            icon = Icons.Filled.Home,
            contentDescription = stringResource(Res.string.compose_nav_home),
            label = stringResource(Res.string.compose_nav_home),
        )
        NavItem(
            selected = false,
            onClick = {},
            icon = Res.drawable.sidebar_search,
            contentDescription = stringResource(Res.string.compose_nav_search),
            label = stringResource(Res.string.compose_nav_search),
        )
        NavItem(
            selected = false,
            onClick = {},
            icon = Res.drawable.sidebar_library,
            contentDescription = stringResource(Res.string.compose_nav_library),
            label = stringResource(Res.string.compose_nav_library),
        )
        NavItem(
            selected = false,
            onClick = {},
            icon = Icons.Filled.Download,
            contentDescription = stringResource(Res.string.compose_nav_downloads),
            label = stringResource(Res.string.compose_nav_downloads),
        )
    }
}
