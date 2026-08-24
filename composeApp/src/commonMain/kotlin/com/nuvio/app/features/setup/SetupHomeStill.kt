package com.nuvio.app.features.setup

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioNavigationBar
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.nuvioBlockPointerEvents
import com.nuvio.app.features.home.components.HomeCatalogRowSection
import com.nuvio.app.features.home.components.HomeContinueWatchingSection
import com.nuvio.app.features.home.components.HomeHeroSection
import com.nuvio.app.features.home.components.homeHeroLayout
import com.nuvio.app.features.home.components.homeSectionHorizontalPaddingForWidth
import com.nuvio.app.features.home.components.rememberContinueWatchingLayout
import com.nuvio.app.features.tracking.WatchProgressSource
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
 * Both repositories now pass the required `dataSourceKey: WatchProgressSource`; desktop's
 * `HomeHeroSection` also inserts an
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
 * ## It is a screenshot, and that is the whole design rule
 *
 * Everything is laid out at the app's real metrics inside the app's own scroll container, and the
 * wizard's sheet simply covers the bottom of it. **Nothing is ever resized, repositioned or
 * padded to fit the space the sheet leaves.** Revision 7 did all three - a hero shrunk to the
 * visible band, a nav bar lifted so it would clear the panel, a column padded to stop above it -
 * and the result read as messy precisely because none of it was where the app puts it.
 *
 * It is not interactive and it fetches nothing. ⚠ **With no network Coil draws nothing and the
 * hero is flat black**, because `HomeHeroSection`'s backdrop image carries no placeholder or
 * error painter. That is not a defect here - it is exactly what the real home screen looks like
 * with no network, and making the wizard nicer than the app is the failure this file exists to
 * avoid. It is still worth seeing in aeroplane mode.
 */
@Composable
fun SetupHomeStill(modifier: Modifier = Modifier) {
    val continueWatching by remember {
        ContinueWatchingPreferencesRepository.ensureLoaded()
        ContinueWatchingPreferencesRepository.uiState
    }.collectAsStateWithLifecycle()

    val catalogRowTitle = stringResource(Res.string.setup_still_catalog_row)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            // ⚠ A real `LazyColumn` is draggable. A screenshot that scrolls out from under your
            // thumb while you read the panel is a new kind of mess, so the whole still is inert.
            // `Initial` pass, so the list never sees the gesture at all.
            .nuvioBlockPointerEvents(),
    ) {
        val density = LocalDensity.current

        // ⚠ **The full window, which is what `HomeScreen` passes.** Revision 7 passed the height
        // visible above the panel and capped it again with the Continue Watching reserve, which
        // made the hero a size the app never draws - the still stopped being a screenshot and
        // became a layout fitted to a hole. The sheet crops this; nothing reflows for it.
        val heroLayout = homeHeroLayout(
            maxWidthDp = maxWidth.value,
            viewportHeightDp = maxHeight.value,
        )

        // How far down the screenshot is taken. Derived from the hero the section will actually
        // draw rather than guessed, so it holds on every window: scroll until only the hero's
        // content block - logo, metadata line, button, dots - is left above the fold, and
        // Continue Watching follows it.
        val scrollPx = with(density) {
            (heroLayout.heroHeight - HeroTailKept).coerceAtLeast(0.dp).roundToPx()
        }

        // ⚠ **Seeded state, not `Modifier.offset`.** `HomeHeroSection` reads
        // `listState.firstVisibleItemScrollOffset` for its parallax and background scale, so an
        // offset on a plain Column leaves the backdrop sitting where it would be *unscrolled* -
        // which was part of why revision 7 read as not-the-app. Giving the list the position and
        // handing the section the same state gets the app's own behaviour for free.
        val listState = remember(scrollPx) { LazyListState(0, scrollPx) }

        // Both measured off the window width, exactly as `HomeScreen` measures them, and both
        // passed down. ⚠ `HomeContinueWatchingSection` only honours `sectionPadding` when
        // `layout` comes with it - pass one without the other and it silently drops into its
        // own `BoxWithConstraints` and re-derives both, so the argument reads as load-bearing
        // while doing nothing.
        val sectionPadding = homeSectionHorizontalPaddingForWidth(maxWidth.value)
        val continueWatchingLayout = rememberContinueWatchingLayout(maxWidth.value)

        NuvioScreen(
            // Exactly how `HomeScreen` calls it when the hero is on: full-bleed, and the hero
            // starts at y = 0 under the status bar.
            horizontalPadding = 0.dp,
            topPadding = 0.dp,
            listState = listState,
        ) {
            item {
                // ⚠ **One item, not the whole row.** The hero is a pager that rotates, and a
                // still that rotates is not a still - it also meant the screenshot landed on
                // whichever title's backdrop happened to be missing from the artwork host, which
                // is what produced a hero that was simply black. The cost is the pager dots,
                // which `HomeHeroSection` only draws for more than one item.
                HomeHeroSection(
                    items = SetupSampleTitle.rowItems.take(1),
                    viewportHeight = maxHeight,
                    listState = listState,
                )
            }
            item {
                HomeContinueWatchingSection(
                    items = SetupSampleTitle.continueWatching,
                    style = continueWatching.style,
                    dataSourceKey = WatchProgressSource.NUVIO_SYNC,
                    useEpisodeThumbnails = continueWatching.useEpisodeThumbnails,
                    blurNextUp = continueWatching.blurNextUp,
                    sectionPadding = sectionPadding,
                    layout = continueWatchingLayout,
                    // ⚠ 12 dp per item on top of the container's 12 dp `listGap` - the real home
                    // screen's rows are 24 dp apart, and revision 7's 16 dp was neither.
                    modifier = Modifier.padding(bottom = HomeRowBottomPadding),
                    // No `title`: the section falls back to the same string resource the real
                    // home screen shows it under, so this cannot drift from it.
                )
            }
            item {
                HomeCatalogRowSection(
                    section = SetupSampleTitle.catalogSection(catalogRowTitle),
                    sectionPadding = sectionPadding,
                    modifier = Modifier.padding(bottom = HomeRowBottomPadding),
                )
            }
            item {
                // A second row, so what the panel crops is a screen that carries on rather than
                // the end of a short list. Its own key - `NuvioShelfSection` dedupes by key.
                HomeCatalogRowSection(
                    section = SetupSampleTitle.secondCatalogSection(catalogRowTitle),
                    sectionPadding = sectionPadding,
                    modifier = Modifier.padding(bottom = HomeRowBottomPadding),
                )
            }
        }

        // ⚠ Pinned to the window's bottom edge, which is where `App.kt` draws it - an overlay
        // over the rows, not a thing laid out below them. Revision 7 padded it up so it would
        // clear the panel and it ended up floating across the middle of the Continue Watching
        // row. The sheet covers it now, and that is correct: a screenshot of a scrolled home
        // screen with a sheet over it does not show the tab bar either.
        SetupStillNavigationBar(modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * How much of the hero is left above the fold.
 *
 * The content block - logo, metadata line, action button, dots - measures about 240 dp on a phone
 * (`HomeHeroSection`'s `bottomFadeHeight` is 220 dp and the block slightly overruns it), so this
 * frames on the part of the hero that carries the title's identity and hands the rest of the band
 * to Continue Watching.
 */
private val HeroTailKept = 250.dp

/**
 * ⚠ Mirrors the per-row bottom padding `HomeScreen` applies to every section - literal `12.dp` on
 * the catalog rows and `HomeContinueWatchingSectionBottomPadding` (also `12.dp`) on Continue
 * Watching. Kept local rather than imported so the desktop port of this file needs no extra
 * import, and because the two real values are separate constants that happen to agree.
 */
private val HomeRowBottomPadding = 12.dp

/**
 * The nav bar, drawn for looks only.
 *
 * No `hazeState`: the bar would then blur the still behind it, which is correct in the app but
 * competes with the wizard's own frosted panel. `NuvioNavigationBar` already deepens its own tint
 * to `0.82f` when no haze state is supplied, so this is the shape the bar is designed to take
 * without one rather than a special case.
 *
 * ⚠ Home is drawn selected because that is the tab a first launch lands on. The Settings tab in
 * the real bar is a `ProfileSwitcherTab`, which reads `ProfileRepository`; it is left out rather
 * than faked, and four items still read as the app's nav bar.
 *
 * `NavItem` is a member of `NuvioNavigationBar`'s content receiver, so it needs no import.
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
