package com.nuvio.app.features.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioDesktopVerticalScrollbar
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.details.MetaEpisodeCardStyle
import com.nuvio.app.features.details.MetaScreenBackgroundMode
import com.nuvio.app.features.playback.PlaybackMode
import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle

/**
 * The wizard's steps 2-8 on a desktop window.
 *
 * ## Why this exists at all
 *
 * Every file under `features/setup/` except `SetupHomeStill.kt` was byte-identical to the mobile
 * repository, this screen included, and it showed. The mobile layout stacks a specimen band over
 * a panel and caps the panel's text at 620 dp - which on the 1280x820 default window
 * (`Main.kt`) is a band across the top and a narrow column with roughly 660 dp of empty surface
 * split between the gutters. Nothing was broken; it was simply a phone layout in a desktop
 * window.
 *
 * The app already knows how to do this and the wizard was the last screen not doing it.
 * `AuthScreen` - the *other* full-screen gate - splits into `AuthLargeLayout` at 900 dp,
 * `SettingsScreen` splits at 768 dp into a fixed rail plus a detail pane, `StreamsScreen` splits
 * 0.4/0.6, and `MetaDetailsScreen` swaps in `DesktopDetailHero` at `isDesktop && width >= 1000`.
 * This is that same move: the specimen takes the width, the controls keep a readable measure.
 *
 * ## Pure with respect to its parameters, and that is load-bearing
 *
 * This composable reads **no repository** - every appearance value arrives as an argument, exactly
 * as [SetupSpecimenBand] does and for the same reason. `SetupWizardScreen` holds the current step
 * in `rememberSaveable` state, so before this file existed there was no way to render a single
 * wizard step off-screen: the harness could only ever draw Welcome and the bands in isolation.
 * Taking [step] as a parameter is what lets `SetupWizardRenderHarness` draw all seven remaining
 * steps. ⚠ If a value is ever read internally here instead of passed in, that coverage is gone.
 *
 * ## The seam is vertical now, and the band needed no change for it
 *
 * [SetupSpecimenBand] paints itself with a `background -> surface` vertical gradient so that the
 * bottom of the band lands on the panel's colour instead of meeting it at a hard rule. That still
 * works here: the specimen pane is the full height of the window and the control pane beside it is
 * `colors.surface`, so the gradient reads as the left pane settling into the panel colour rather
 * than as a horizontal seam. **That is why `SetupSpecimen.kt` is untouched** and stays
 * byte-identical with the mobile repository - `diff -q` is still a real check on it.
 */
@Composable
fun SetupWizardDesktopLayout(
    step: SetupStep,
    plan: SetupWizardPlan,
    specimen: SetupSpecimen,
    dismissible: Boolean,
    onDismiss: () -> Unit,
    playbackMode: PlaybackMode,
    posterWidthDp: Int,
    posterCornerRadiusDp: Int,
    landscapeCards: Boolean,
    showCardTitles: Boolean,
    heroEnabled: Boolean,
    continueWatchingStyle: ContinueWatchingSectionStyle,
    useEpisodeThumbnails: Boolean,
    blurNextUp: Boolean,
    backgroundMode: MetaScreenBackgroundMode,
    episodeCardStyle: MetaEpisodeCardStyle,
    blurUnwatchedEpisodes: Boolean,
    tabLayout: Boolean,
    nextUpLabel: String,
    topInset: Dp,
    bottomInset: Dp,
    onBack: () -> Unit,
    onAdvance: () -> Unit,
    advance: SetupAdvance = SetupAdvance.Shown,
    modifier: Modifier = Modifier,
    body: @Composable () -> Unit,
) {
    val tokens = MaterialTheme.nuvio

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.colors.background),
        contentAlignment = Alignment.Center,
    ) {
        val paneHeight = maxHeight
        // ⚠ **The panes fill the window again.** An earlier attempt capped the whole thing at a
        // 1060 dp block to stop the specimens distorting, and that was the wrong lever: it did
        // not make them desktop-shaped, it just cropped a phone-shaped drawing and left it
        // marooned in the middle of a large screen. The specimens are adapted for a wide pane now
        // - see `scale` and `wide` below - so the pane can have the width it is given.
        val controlPaneWidth = desktopControlPaneWidth(maxWidth)

        Row(modifier = Modifier.fillMaxSize()) {
            // The specimen pane.
            //
            // ⚠ **The band gets `preferredHeight`, not the pane's height, and that is the whole
            // trick.** `SpecimenDetails` and `SpecimenHome` are `fillMaxSize()` internally and
            // top-anchor their content, so handing them an 820 dp pane does not centre them - it
            // stretches the mock and pushes its hero off the top edge. `preferredHeight` is the
            // height each specimen was actually budgeted against (`SetupSpecimen.kt`), so drawing
            // at exactly that height is what makes it render the way it was designed to; the pane
            // then centres the finished block. Capped against the pane so a short window clips
            // nothing.
            //
            // ⚠ **The two fillers are what make the pane look like one surface rather than a
            // band floating on one.** `SetupSpecimenBand` paints itself with a vertical gradient
            // running `background` (top) to `surface` (bottom). Centring it inside a
            // single-colour pane therefore puts a visible step at whichever edge does not match -
            // a hard line across the pane, which is what the first render of the playback-mode
            // step showed. Painting the space above it `background` and the space below it
            // `surface` continues the gradient's own endpoints exactly, so both edges vanish and
            // the pane reads as one continuous fade - the same thing a full-height band would
            // have drawn, but with the specimen still at the height it was designed for.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(tokens.colors.background),
                )
                SetupSpecimenBand(
                    specimen = specimen,
                    step = step,
                    playbackMode = playbackMode,
                    height = specimen.desktopHeight
                        .coerceAtMost((paneHeight - topInset).coerceAtLeast(0.dp)),
                    contentPaddingTop = 0.dp,
                    posterWidthDp = posterWidthDp,
                    posterCornerRadiusDp = posterCornerRadiusDp,
                    landscapeCards = landscapeCards,
                    showCardTitles = showCardTitles,
                    heroEnabled = heroEnabled,
                    continueWatchingStyle = continueWatchingStyle,
                    useEpisodeThumbnails = useEpisodeThumbnails,
                    blurNextUp = blurNextUp,
                    backgroundMode = backgroundMode,
                    episodeCardStyle = episodeCardStyle,
                    blurUnwatchedEpisodes = blurUnwatchedEpisodes,
                    tabLayout = tabLayout,
                    nextUpLabel = nextUpLabel,
                    modifier = Modifier.fillMaxWidth(),
                    scale = specimen.desktopScale,
                    wide = true,
                    downloadModeName = plan.downloadModeName,
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(tokens.colors.surface),
                )
            }

            // The same hairline the stacked layout draws between band and panel, stood on its end.
            Box(
                modifier = Modifier
                    .width(tokens.borders.hairline)
                    .fillMaxHeight()
                    .background(tokens.colors.borderSubtle.copy(alpha = 0.6f)),
            )

            SetupDesktopControlPane(
                step = step,
                plan = plan,
                playbackMode = playbackMode,
                dismissible = dismissible,
                onDismiss = onDismiss,
                topInset = topInset,
                bottomInset = bottomInset,
                onBack = onBack,
                onAdvance = onAdvance,
                advance = advance,
                modifier = Modifier
                    .width(controlPaneWidth)
                    .fillMaxHeight(),
                body = body,
            )
        }
    }
}

/**
 * How tall to draw a specimen when there is a whole desktop pane to put it in.
 *
 * ⚠ These are **not** `preferredHeight * DesktopSpecimenScale`, and they cannot be. The two heroes
 * are laid out by aspect ratio on a wide pane rather than by fixed height, so how tall a specimen
 * comes out depends on how wide the pane is - a 1200 dp pane gives the home banner 375 dp of
 * height on its own. These are budgets generous enough for the widest pane the layout allows, and
 * the band centres whatever is shorter. `coerceAtMost(paneHeight)` at the call site stops a short
 * window from being overrun.
 *
 * `preferredHeight` stays exactly as it is: it is the phone budget, and the phone layout is
 * unchanged.
 */
private val SetupSpecimen.desktopHeight: Dp
    get() = when (this) {
        // Banner (up to ~390 at the widest pane) + gap + the tallest Continue Watching style.
        SetupSpecimen.Home -> 660.dp
        // Hero + seam + episode row + two stacked sections with a rail under each.
        SetupSpecimen.Details -> 800.dp
        // A row of scaled poster cards plus their titles.
        SetupSpecimen.Cards -> 420.dp
        SetupSpecimen.Theme -> 300.dp
        SetupSpecimen.Diagram -> 420.dp
    }

/**
 * How much the specimen's own drawing is enlarged on a desktop pane.
 *
 * ⚠ **1.4 is not a taste value - it is `NuvioDesktopCatalogShelfPosterScale`.** `ShelfComponents.kt`
 * draws catalog posters 1.4x larger on desktop than the raw poster-width setting, so a specimen
 * that drew them at 1.0 would be showing the user a *smaller* card than the app is about to. Every
 * other fixed dimension in the specimens is scaled by the same factor so the mock stays internally
 * proportioned.
 *
 * This is separate from the theme's own UI scale, which has already been applied by the time this
 * multiplies anything: the theme decides how big a dp is, this decides how many dp the drawing is.
 */
internal const val DesktopSpecimenScale = 1.4f

/**
 * How much the specimen is enlarged, per specimen.
 *
 * ⚠ The diagram gets substantially more, and it is the one case where matching the app is not the
 * goal. The mocks are previews of real screens, so 1.4x is bounded by what those screens actually
 * do. `SetupDiagram` is an *illustration* - a TV, an arrow and a play button - and it is the only
 * thing in its pane, with no rails or artwork beside it to give it scale. At 1.4x on a large
 * monitor it read as a detail lost in the middle of an empty half of the window.
 */
private val SetupSpecimen.desktopScale: Float
    get() = when (this) {
        SetupSpecimen.Diagram -> DesktopSpecimenScale * DesktopDiagramExtraScale
        else -> DesktopSpecimenScale
    }

private const val DesktopDiagramExtraScale = 1.8f

/**
 * The controls, in a column of their own.
 *
 * Proportional with hard clamps, following `detailTabletContentMaxWidth` in `MetaDetailsScreen`:
 * a fraction alone would let a very wide window stretch the body copy back out, and a fixed width
 * alone would crowd the specimen at 1000 dp.
 */
internal fun desktopControlPaneWidth(windowWidth: Dp): Dp =
    (windowWidth * DesktopControlPaneFraction)
        .coerceIn(DesktopControlPaneMinWidth, DesktopControlPaneMaxWidth)

private const val DesktopControlPaneFraction = 0.32f
private val DesktopControlPaneMinWidth = 440.dp
private val DesktopControlPaneMaxWidth = 620.dp

/**
 * Horizontal padding inside the control pane.
 *
 * 32 dp is what `MetaDetailsScreen` uses for `contentHorizontalPadding` on a wide window, not the
 * 22 dp the stacked mobile panel uses. The pane is furniture at this size and needs the gutter.
 */
private val DesktopControlPanePadding = 32.dp

@Composable
private fun SetupDesktopControlPane(
    step: SetupStep,
    plan: SetupWizardPlan,
    playbackMode: PlaybackMode,
    dismissible: Boolean,
    onDismiss: () -> Unit,
    topInset: Dp,
    bottomInset: Dp,
    onBack: () -> Unit,
    onAdvance: () -> Unit,
    advance: SetupAdvance = SetupAdvance.Shown,
    modifier: Modifier = Modifier,
    body: @Composable () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val scrollState = rememberScrollState()

    Box(modifier = modifier.background(tokens.colors.surface)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = DesktopControlPanePadding,
                    end = DesktopControlPanePadding,
                    top = 32.dp + topInset,
                    bottom = 28.dp + bottomInset,
                ),
        ) {
            SetupPanelHeader(
                step = step,
                plan = plan,
                playbackMode = playbackMode,
                dismissible = dismissible,
                onDismiss = onDismiss,
            )
            Spacer(modifier = Modifier.height(24.dp))
            // Scrolls for a long step (Cards and Details both carry four controls) or a large
            // font scale. The desktop scrollbar is drawn rather than left implicit, because a
            // pane that scrolls with no visible affordance is a desktop bug in its own right -
            // `NuvioScreen` wires it exactly this way.
            Box(modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState),
                ) {
                    body()
                }
                NuvioDesktopVerticalScrollbar(
                    state = scrollState,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight(),
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            SetupDesktopFooter(
                step = step,
                plan = plan,
                onBack = onBack,
                onAdvance = onAdvance,
                advance = advance,
            )
        }
    }
}

/**
 * Back and Next.
 *
 * ⚠ Deliberately **not** `fillMaxWidth()` buttons. The stacked mobile layout stretches its
 * primary actions because a thumb needs the target; a 500 dp wide "Next" under a mouse pointer
 * just looks like a mistake. Same two actions, same order, same strings.
 */
@Composable
private fun SetupDesktopFooter(
    step: SetupStep,
    plan: SetupWizardPlan,
    onBack: () -> Unit,
    onAdvance: () -> Unit,
    advance: SetupAdvance = SetupAdvance.Shown,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SetupBackButton(step = step, plan = plan, onBack = onBack)
        Spacer(modifier = Modifier.weight(1f))
        SetupAdvanceButton(step = step, plan = plan, onAdvance = onAdvance, advance = advance)
    }
}
