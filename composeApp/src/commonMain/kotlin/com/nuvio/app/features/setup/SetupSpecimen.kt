package com.nuvio.app.features.setup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kmpalette.extensions.painter.rememberPainterDominantColorState
import com.nuvio.app.core.ui.NuvioProgressBar
import com.nuvio.app.core.ui.landscapePosterHeightForWidth
import com.nuvio.app.core.ui.landscapePosterWidth
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.details.MetaEpisodeCardStyle
import com.nuvio.app.features.details.MetaScreenBackgroundMode
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.playback.PlaybackMode
import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle
// Three keys, imported explicitly. ⚠ `SetupWizardScreen.kt` reads about sixty and uses a
// wildcard; that decision covers that file only, and this one had no resource imports at all
// before the details sections strip needed section titles. Check the host file's style.
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.meta_section_details_title
import nuvio.composeapp.generated.resources.settings_meta_cast
import nuvio.composeapp.generated.resources.settings_meta_trailers
import org.jetbrains.compose.resources.stringResource

/**
 * The band above the wizard's controls, and everything it can draw.
 *
 * ## Why these are not the real composables
 *
 * Revision 2 rendered the actual `HomeHeroSection`, `HomeContinueWatchingSection` and
 * `DetailHero` over a scrolling replica of the home and details screens, on the argument that a
 * preview built from the shipped composables can never drift from the app. It cannot, and it
 * was still the wrong thing:
 *
 * - Those composables read their settings repository **internally** and apply a change the
 *   instant it is written. There is no way to tween between two values you never hold, so every
 *   choice snapped.
 * - Most of what was on screen had nothing to do with the control being changed, and the
 *   per-step scroll anchoring that tried to fix that left rows half-clipped at the top of the
 *   band.
 * - ⚠ **They diverge between the two repositories.** `NuvioZDesktop`'s
 *   `HomeContinueWatchingSection` takes a *required* `dataSourceKey` this repository's does not,
 *   so the old stage could not be copied across and had to be hand-maintained in two places.
 *
 * Everything here is drawn from primitives instead, takes every setting as a **parameter**, and
 * reads no repository. That buys the tweening, the full-bleed framing, and a file that is
 * byte-identical in both repositories - so `diff -q` is a real check on it rather than a
 * formality.
 *
 * ⚠ **The cost is that these can drift from the real cards, and nothing will catch it.** What
 * each specimen mirrors:
 *
 * | Specimen | Mirrors | Pinned to |
 * | --- | --- | --- |
 * | [SpecimenCards] | `NuvioPosterCard` catalog cards | [landscapePosterWidth], [landscapePosterHeightForWidth], [PosterAspectRatio] |
 * | [SpecimenContinueWatching] | `HomeContinueWatchingSection`'s three styles | the 18 dp blur it uses |
 * | [SpecimenDetails] | `MetaDetailsScreen`'s three treatments | the **30 dp** blur, the 0.92 scrim, the 0.42 dominant blend, and that only DominantColor tints the hero |
 * | [SpecimenEpisodes] | `DetailSeriesContent`'s two card styles | the 18 dp blur, and the `thumbnail ?: background` fallback |
 * | [SpecimenDetailSections] | `TabbedSectionGroup`'s heading row | the `|` separator, and the 0.55 / 0.45 alphas |
 * | [SpecimenWelcome] | the home screen as a whole | nothing - it is the one specimen at its own scale |
 *
 * If one of those changes, change it here too. The blur radius has already drifted once - this
 * file blurred Cinematic at 18 dp against the real screen's 30 dp for a whole release.
 */
enum class SetupSpecimen(
    /**
     * How tall this specimen wants the band to be.
     *
     * Each is the drawn content plus a little breathing room, measured at the *largest* setting
     * the specimen can be asked to render - a 140 dp poster card is 207 dp tall, so [Cards]
     * asks for 280. The caller caps these against the window, so a value that is too generous
     * costs panel space on a short screen rather than clipping.
     */
    internal val preferredHeight: Dp,
) {
    /**
     * The whole home screen in miniature: banner, Continue Watching, a catalog row.
     *
     * ⚠ **The Welcome step used to draw the app's logo, and this replaced it.** A wordmark over
     * an accent wash read as a splash screen bolted onto a settings flow, and the asset has
     * "Nuvio" baked into it as pixels above copy that says "Nuvio Z". A still of the app answers
     * the only question the first screen is really being asked - *what is this?* - and it also
     * sets up the three steps that follow, which each take one row of it apart.
     *
     * ⚠ **The one specimen drawn at its own scale rather than at the app's real metrics**, and
     * the only one whose step changes nothing. Three real rows stacked at real sizes come to
     * roughly 470 dp - they would not fit any band a phone can give, and half a catalog row cut
     * off at the seam reads as a broken layout rather than as a screen continuing below the
     * fold. So the sizes here are fixed and chosen to show the whole composition at once. Card
     * shape and corner radius are still honoured, because they cost nothing and keep a re-run
     * from Settings consistent with the Cards step two screens later.
     */
    Welcome(preferredHeight = 344.dp),

    /** A catalog row, at the chosen shape, size, corner radius and title setting. */
    Cards(preferredHeight = 280.dp),

    /**
     * The home screen: the featured banner and the Continue Watching row, **together and
     * always**.
     *
     * Revision 3 split these and moved the band to whichever the user last touched. On a device
     * that read as jarring - the thing you were looking at kept being replaced - so both are
     * now permanently on screen and the controls change them in place. The banner toggle
     * expands and collapses inside a band whose own height does not move.
     */
    Home(preferredHeight = 330.dp),

    /**
     * The details screen: a small mock of it, with the chosen background treatment applied
     * behind the whole thing, the episode list in the chosen style, and the sections below it
     * either stacked or grouped into one tab row.
     *
     * Also one object rather than two, and for a second reason beyond the jarring switch: three
     * abstract swatches could not show what the background modes do, because the thing that
     * most distinguishes `DominantColor` is the tint reaching into the *hero*, and the swatches
     * had no hero.
     *
     * ⚠ **The tallest specimen, because it is the only one that has to reach past its own main
     * subject.** The tab toggle regroups the sections *below* the episode list, so a mock that
     * stopped at the episode list could not show it - which is exactly what revision 4 did, and
     * the toggle read as broken. Budget in [SpecimenDetails].
     */
    Details(preferredHeight = 380.dp),

    /** The accent colour applied to real controls. */
    Theme(preferredHeight = 190.dp),

    /**
     * The steps that change nothing visible. See `SetupDiagram.kt`.
     *
     * The smallest of the six on purpose: the playback-mode step is the tallest panel in the
     * flow - three `PlaybackModeCard`s - and in revision 2 it was cut off mid-card. 200 rather
     * than revision 4's 180 because the storyboard's release list is five rows tall; check the
     * playback-mode panel still fits without scrolling before raising it again.
     */
    Diagram(preferredHeight = 200.dp),
}

/**
 * Poster aspect ratio, width over height.
 *
 * ⚠ Mirrors `NuvioPosterShape.Poster.aspectRatio` in `core/ui/ShelfComponents.kt`, which is
 * private to that file. Landscape needs no constant here because
 * [landscapePosterHeightForWidth] is `internal` and can be called directly.
 */
private const val PosterAspectRatio = 0.675f

/** Long enough to read as a morph rather than a jump, short enough not to feel laggy. */
private const val ShapeTweenMillis = 340
private const val FadeTweenMillis = 220

/**
 * The full-bleed specimen band.
 *
 * Draws **only** the component the current step changes. Content deliberately overflows the
 * right edge the way a real catalog row does - the band is the app's own width, not a framed
 * illustration of it.
 *
 * Every appearance value arrives as a parameter rather than being read from its repository, so
 * this composable is pure with respect to its inputs. That is what makes the render harness in
 * `STATUS.md` able to draw every step deterministically, and it is the only check that catches
 * a layout defect without a device.
 */
@Composable
fun SetupSpecimenBand(
    specimen: SetupSpecimen,
    step: SetupStep,
    playbackMode: PlaybackMode,
    height: Dp,
    contentPaddingTop: Dp,
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
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio

    Box(
        modifier = modifier
            .fillMaxWidth()
            // The band is full-bleed, so its background runs under the status bar; only its
            // *content* is inset. Padding the whole thing instead would leave a bare strip of
            // window above it.
            .height(height + contentPaddingTop)
            // A gradient floor, so a band whose artwork has not loaded - or cannot, with no
            // network - reads as a dimmed screen rather than a broken one. It ends on the
            // panel's own colour rather than on `background`, which is what turns the seam
            // below into a soft landing instead of a hard rule across the screen.
            .background(
                Brush.verticalGradient(
                    0f to tokens.colors.background,
                    0.55f to tokens.colors.background,
                    1f to tokens.colors.surface,
                ),
            )
            .padding(top = contentPaddingTop),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = specimen,
            transitionSpec = {
                fadeIn(tween(FadeTweenMillis)) togetherWith fadeOut(tween(FadeTweenMillis / 2))
            },
            label = "setup_specimen",
        ) { current ->
            when (current) {
                SetupSpecimen.Welcome -> SpecimenWelcome(
                    landscapeCards = landscapeCards,
                    cornerRadiusDp = posterCornerRadiusDp,
                )

                SetupSpecimen.Cards -> SpecimenCards(
                    posterWidthDp = posterWidthDp,
                    cornerRadiusDp = posterCornerRadiusDp,
                    landscape = landscapeCards,
                    showTitles = showCardTitles,
                )

                SetupSpecimen.Home -> SpecimenHome(
                    heroEnabled = heroEnabled,
                    continueWatchingStyle = continueWatchingStyle,
                    useEpisodeThumbnails = useEpisodeThumbnails,
                    blurNextUp = blurNextUp,
                    cornerRadiusDp = posterCornerRadiusDp,
                    nextUpLabel = nextUpLabel,
                )

                SetupSpecimen.Details -> SpecimenDetails(
                    mode = backgroundMode,
                    episodeCardStyle = episodeCardStyle,
                    blurUnwatched = blurUnwatchedEpisodes,
                    tabLayout = tabLayout,
                    cornerRadiusDp = posterCornerRadiusDp,
                )

                SetupSpecimen.Theme -> SpecimenTheme(
                    cornerRadiusDp = posterCornerRadiusDp,
                )

                SetupSpecimen.Diagram -> SetupDiagram(
                    step = step,
                    playbackMode = playbackMode,
                )
            }
        }
    }
}

// --- cards ---------------------------------------------------------------------------------

/**
 * A catalog row at the chosen card settings.
 *
 * Width, height and corner radius are all tweened independently, which is what makes the
 * poster/landscape flip read as the card changing shape rather than being replaced. The row
 * scrolls because six of the widest cards overflow a phone by design - see
 * `SetupSampleTitle.rowItems`, where that count is deliberate.
 */
@Composable
private fun SpecimenCards(
    posterWidthDp: Int,
    cornerRadiusDp: Int,
    landscape: Boolean,
    showTitles: Boolean,
) {
    val targetWidth = if (landscape) landscapePosterWidth(posterWidthDp) else posterWidthDp.dp
    val targetHeight = if (landscape) {
        landscapePosterHeightForWidth(targetWidth)
    } else {
        (posterWidthDp / PosterAspectRatio).dp
    }

    val width by animateDpAsState(
        targetValue = targetWidth,
        animationSpec = tween(ShapeTweenMillis, easing = LinearOutSlowInEasing),
        label = "specimen_card_width",
    )
    val cardHeight by animateDpAsState(
        targetValue = targetHeight,
        animationSpec = tween(ShapeTweenMillis, easing = LinearOutSlowInEasing),
        label = "specimen_card_height",
    )
    val radius by animateDpAsState(
        targetValue = cornerRadiusDp.dp,
        animationSpec = tween(FadeTweenMillis, easing = LinearOutSlowInEasing),
        label = "specimen_card_radius",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.width(16.dp))
        SetupSampleTitle.rowItems.forEach { item ->
            SpecimenCard(
                item = item,
                width = width,
                cardHeight = cardHeight,
                radius = radius,
                landscape = landscape,
                showTitle = showTitles,
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
    }
}

@Composable
private fun SpecimenCard(
    item: MetaPreview,
    width: Dp,
    cardHeight: Dp,
    radius: Dp,
    landscape: Boolean,
    showTitle: Boolean,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier.width(width),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .width(width)
                .height(cardHeight)
                .clip(RoundedCornerShape(radius))
                .background(tokens.colors.skeleton),
            contentAlignment = Alignment.Center,
        ) {
            // Behind the artwork, so it shows through only while the image is missing. With no
            // network this is what keeps every card distinguishable instead of six grey boxes.
            Text(
                text = item.name,
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.textMuted,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            AsyncImage(
                model = if (landscape) item.banner else item.poster,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (landscape) {
                // The logo overlay is the thing that makes a landscape card look different
                // rather than merely wider, so the specimen has to carry it.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)),
                            ),
                        ),
                )
                AsyncImage(
                    model = item.logo,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .widthIn(max = width * 0.7f)
                        .height((cardHeight * 0.26f).coerceAtLeast(16.dp)),
                )
            }
        }
        AnimatedVisibility(
            visible = showTitle,
            enter = fadeIn(tween(FadeTweenMillis)) + expandVertically(tween(FadeTweenMillis)),
            exit = fadeOut(tween(FadeTweenMillis / 2)) + shrinkVertically(tween(FadeTweenMillis)),
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.labelMedium,
                color = tokens.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// --- welcome ---------------------------------------------------------------------------------

/**
 * A still of the home screen: banner, Continue Watching, a catalog row.
 *
 * ⚠ **This replaced the app's wordmark, and the reason is not only that the logo looked out of
 * place.** The first screen of a setup flow is answering *what is this?*, and a logo answers it
 * with the one thing the user has already read on the panel below. A still of the app answers it
 * with the app - and it doubles as an establishing shot for the three steps that follow, each of
 * which takes one row of this picture apart.
 *
 * ⚠ **Fixed sizes, unlike every other specimen in this file**, because this one has to hold three
 * rows at once. At the app's real metrics they stack to roughly 470 dp and no phone can give a
 * band that tall - see [SetupSpecimen.Welcome]. Card shape and corner radius are still honoured.
 *
 * Nothing here animates and nothing on this step changes it. Top-aligned and clipped rather than
 * centred, so that a band capped short on a small phone cuts the bottom row off the way a screen
 * continues below the fold, instead of shaving both ends.
 */
@Composable
private fun SpecimenWelcome(
    landscapeCards: Boolean,
    cornerRadiusDp: Int,
) {
    val radius = cornerRadiusDp.dp
    val featured = SetupSampleTitle.rowItems.first()

    // Height budget (344 dp): 100 hero + 12 + 74 Continue Watching + 12 + 136 catalog ≈ 334.
    // The catalog row is the part that gives if any of these change - it is the one whose
    // bottom edge the band's floor is nearest.
    Column(
        modifier = Modifier.fillMaxSize().clipToBounds(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SpecimenHeroBanner(item = featured, height = 100.dp, logoHeight = 26.dp)

        // Two wide cards, the style the real section ships with, so this row reads as Continue
        // Watching rather than as a second catalog row.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.width(16.dp))
            SpecimenWelcomeContinueCard(
                item = featured,
                radius = radius,
                caption = SetupSampleTitle.continueWatchingCaption,
                progress = SetupSampleTitle.continueWatchingProgress,
            )
            SpecimenWelcomeContinueCard(
                item = SetupSampleTitle.rowItems[1],
                radius = radius,
                caption = SetupSampleTitle.rowItems[1].releaseInfo.orEmpty(),
                progress = 0.62f,
            )
            Spacer(modifier = Modifier.width(16.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(modifier = Modifier.width(16.dp))
            SetupSampleTitle.rowItems.forEach { item ->
                val cardWidth = if (landscapeCards) 150.dp else 92.dp
                val cardHeight = if (landscapeCards) 84.dp else (92 / PosterAspectRatio).dp
                Box(
                    modifier = Modifier
                        .width(cardWidth)
                        .height(cardHeight)
                        .clip(RoundedCornerShape(radius))
                        .background(MaterialTheme.nuvio.colors.skeleton),
                    contentAlignment = Alignment.Center,
                ) {
                    // Behind the artwork, so a card with no network is still a named card.
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.nuvio.colors.textMuted,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                    AsyncImage(
                        model = if (landscapeCards) item.banner else item.poster,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
        }
    }
}

@Composable
private fun SpecimenWelcomeContinueCard(
    item: MetaPreview,
    radius: Dp,
    caption: String,
    progress: Float,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier.width(252.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(120.dp)
                .height(68.dp)
                .clip(RoundedCornerShape(radius))
                .background(tokens.colors.skeleton),
        ) {
            AsyncImage(
                model = item.banner,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            NuvioProgressBar(
                progress = progress,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 6.dp, vertical = 6.dp),
            )
        }
        SpecimenCaption(title = item.name, caption = caption, modifier = Modifier.weight(1f))
    }
}

/**
 * The featured banner, shared by the Welcome still and the Home step.
 *
 * One composable rather than two because the two steps are showing the *same* component, and a
 * user who sees a different banner treatment on the welcome screen and on the step that toggles
 * it has been shown two different apps.
 */
@Composable
private fun SpecimenHeroBanner(
    item: MetaPreview,
    height: Dp,
    logoHeight: Dp,
) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(tokens.colors.skeleton),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AsyncImage(
            model = item.banner,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, tokens.colors.background),
                    ),
                ),
        )
        Column(
            modifier = Modifier.padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AsyncImage(
                model = item.logo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.height(logoHeight).widthIn(max = 190.dp),
            )
            Text(
                text = item.name,
                style = MaterialTheme.typography.labelMedium,
                color = tokens.colors.textSecondary,
                maxLines = 1,
            )
        }
    }
}

// --- home ----------------------------------------------------------------------------------

/**
 * The home screen: the featured banner and the Continue Watching row, together.
 *
 * ⚠ **Both are always drawn, and that is the fix.** Revision 3 showed whichever the last-touched
 * control affected and animated between them; on a device that read as the preview being yanked
 * away mid-thought. Everything stays put now and the controls change it in place.
 *
 * The banner expands and collapses *inside* a band whose height does not change, so toggling it
 * moves the Continue Watching row and nothing else. The row is the anchor: it has to stay on
 * screen in both states, or the banner toggle would look like it was clearing the whole band.
 */
@Composable
private fun SpecimenHome(
    heroEnabled: Boolean,
    continueWatchingStyle: ContinueWatchingSectionStyle,
    useEpisodeThumbnails: Boolean,
    blurNextUp: Boolean,
    cornerRadiusDp: Int,
    nextUpLabel: String,
) {
    val item = SetupSampleTitle.rowItems.first()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AnimatedVisibility(
            visible = heroEnabled,
            // Expand and shrink, not just fade. A fade alone would pop the row below it up by
            // the banner's full height in one frame, which is the opposite of what this step
            // is trying to show.
            enter = fadeIn(tween(FadeTweenMillis)) + expandVertically(tween(ShapeTweenMillis)),
            exit = fadeOut(tween(FadeTweenMillis / 2)) + shrinkVertically(tween(ShapeTweenMillis)),
        ) {
            SpecimenHeroBanner(item = item, height = 150.dp, logoHeight = 34.dp)
        }

        // ⚠ Height budget for `SetupSpecimen.Home` (330 dp): 150 banner + 14 gap + the tallest
        // Continue Watching style. That is Poster, at 112 wide → 166 tall, plus 8 and a
        // two-line caption ≈ 204 - which with the banner overflows, so the banner is the part
        // that gives: it is 150 here and the row is the anchor. Check both if either changes.
        SpecimenContinueWatching(
            style = continueWatchingStyle,
            useEpisodeThumbnails = useEpisodeThumbnails,
            blurNextUp = blurNextUp,
            cornerRadiusDp = cornerRadiusDp,
            nextUpLabel = nextUpLabel,
        )
    }
}

/**
 * Continue Watching, in each of its three styles.
 *
 * Two entries, and the second one is the reason: `blurNextUp` only ever applies to an episode
 * the user has **not** started, so a specimen holding a single in-progress card could not show
 * that toggle doing anything.
 */
@Composable
private fun SpecimenContinueWatching(
    style: ContinueWatchingSectionStyle,
    useEpisodeThumbnails: Boolean,
    blurNextUp: Boolean,
    cornerRadiusDp: Int,
    nextUpLabel: String,
) {
    val inProgress = SetupSampleTitle.rowItems[0]
    val nextUp = SetupSampleTitle.rowItems[1]
    val radius by animateDpAsState(
        targetValue = cornerRadiusDp.dp,
        animationSpec = tween(FadeTweenMillis, easing = LinearOutSlowInEasing),
        label = "specimen_cw_radius",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.width(16.dp))
        SpecimenContinueWatchingCard(
            item = inProgress,
            style = style,
            useEpisodeThumbnail = useEpisodeThumbnails,
            blurred = false,
            radius = radius,
            progress = SetupSampleTitle.continueWatchingProgress,
            caption = SetupSampleTitle.continueWatchingCaption,
        )
        SpecimenContinueWatchingCard(
            item = nextUp,
            style = style,
            useEpisodeThumbnail = useEpisodeThumbnails,
            blurred = blurNextUp && useEpisodeThumbnails,
            radius = radius,
            progress = null,
            caption = nextUpLabel,
        )
        Spacer(modifier = Modifier.width(16.dp))
    }
}

@Composable
private fun SpecimenContinueWatchingCard(
    item: MetaPreview,
    style: ContinueWatchingSectionStyle,
    useEpisodeThumbnail: Boolean,
    blurred: Boolean,
    radius: Dp,
    progress: Float?,
    caption: String,
) {
    val tokens = MaterialTheme.nuvio
    // The episode-thumbnail toggle picks the still over the show's own artwork, and in the
    // Poster style there is no still to pick - which is exactly what the real section does.
    val artwork = when {
        style == ContinueWatchingSectionStyle.Poster -> item.poster
        useEpisodeThumbnail -> item.banner
        else -> item.poster
    }

    @Composable
    fun Artwork(modifier: Modifier) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(radius))
                .background(tokens.colors.skeleton),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.textMuted,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            AsyncImage(
                model = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // 18 dp, matching `HomeContinueWatchingSection`. ⚠ `Modifier.blur` is a no-op
                // below Android API 31; there the toggle is simply invisible here, which is
                // also true of the real section, so the specimen is not lying.
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (blurred) Modifier.blur(18.dp) else Modifier),
            )
            if (progress != null) {
                NuvioProgressBar(
                    progress = progress,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                )
            }
        }
    }

    when (style) {
        ContinueWatchingSectionStyle.Card -> Column(
            modifier = Modifier.width(206.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Artwork(Modifier.fillMaxWidth().height(116.dp))
            SpecimenCaption(title = item.name, caption = caption)
        }

        ContinueWatchingSectionStyle.Wide -> Row(
            modifier = Modifier.width(268.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(Modifier.width(124.dp).height(70.dp))
            SpecimenCaption(title = item.name, caption = caption, modifier = Modifier.weight(1f))
        }

        ContinueWatchingSectionStyle.Poster -> Column(
            modifier = Modifier.width(112.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Artwork(Modifier.fillMaxWidth().height((112 / PosterAspectRatio).dp))
            SpecimenCaption(title = item.name, caption = caption)
        }
    }
}

@Composable
private fun SpecimenCaption(
    title: String,
    caption: String,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = caption,
            style = MaterialTheme.typography.labelSmall,
            color = tokens.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// --- details -------------------------------------------------------------------------------

/**
 * A small details screen, with the chosen background treatment applied behind all of it.
 *
 * ⚠ **This replaced three abstract swatches, and the reason is worth keeping.** The maintainer
 * looked at those and said "I am not sure what is even going on here", which was fair: the one
 * thing that most separates `DominantColor` from `Normal` is that its tint reaches into the
 * *hero's* bottom fade - `MetaDetailsScreen` passes `heroGradientColor` for that mode and null
 * for the other two - and the swatches had no hero for it to reach into.
 *
 * ⚠ **Cinematic is genuinely subtle, and that is not a defect here.** The real screen blurs the
 * backdrop at 30 dp and then covers it with a `background @ 0.92` scrim, so roughly 8% of the
 * artwork survives: a faint haze, not a visible picture. The first version of this file blurred
 * at 18 dp and overstated it badly. If Cinematic looks close to Normal, it looks like the app.
 */
@Composable
private fun SpecimenDetails(
    mode: MetaScreenBackgroundMode,
    episodeCardStyle: MetaEpisodeCardStyle,
    blurUnwatched: Boolean,
    tabLayout: Boolean,
    cornerRadiusDp: Int,
) {
    val tokens = MaterialTheme.nuvio
    val backdropUrl = SetupSampleTitle.backgroundUrl(SetupSampleTitle.featuredImdbId)

    var backdropPainter by remember(backdropUrl) { mutableStateOf<Painter?>(null) }
    val dominantEnabled = mode == MetaScreenBackgroundMode.DominantColor
    val dominant = rememberDominantBackdropColor(
        painter = backdropPainter,
        enabled = dominantEnabled,
    )

    // Mirrors `heroGradientColor = dominantBackdropColor.takeIf { dominantColorEnabled }`: the
    // hero fade and the seam take the tint only in DominantColor.
    val seamColor = if (dominantEnabled) dominant else tokens.colors.background

    Box(modifier = Modifier.fillMaxSize()) {
        // The background layer, behind everything - the same `when` the real screen runs below
        // its scrolling content.
        when (mode) {
            MetaScreenBackgroundMode.Normal -> Box(
                modifier = Modifier.fillMaxSize().background(tokens.colors.background),
            )

            MetaScreenBackgroundMode.Cinematic -> {
                AsyncImage(
                    model = backdropUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().blur(30.dp),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(tokens.colors.background.copy(alpha = 0.92f)),
                )
            }

            MetaScreenBackgroundMode.DominantColor -> Box(
                modifier = Modifier.fillMaxSize().background(dominant),
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // The hero. Its `onSuccess` is also what feeds the dominant-colour extractor, so
            // this image does two jobs and has to stay laid out in all three modes.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .background(tokens.colors.skeleton),
                contentAlignment = Alignment.BottomCenter,
            ) {
                AsyncImage(
                    model = backdropUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onSuccess = { backdropPainter = it.painter },
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color.Transparent, seamColor)),
                    ),
                )
                AsyncImage(
                    model = SetupSampleTitle.logoUrl(SetupSampleTitle.featuredImdbId),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .padding(bottom = 10.dp)
                        .height(28.dp)
                        .widthIn(max = 164.dp),
                )
            }

            // The seam that bridges hero into page. ⚠ The real screen uses a 132 dp band under a
            // 420-760 dp hero; scaled to this 110 dp hero that would swallow the mock, so it is
            // 18 dp here. Same colour rule and the same `usesBackdropBackground` gate.
            if (mode.usesBackdropBackground) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(18.dp).background(
                        Brush.verticalGradient(
                            listOf(
                                seamColor.copy(alpha = 0.98f),
                                seamColor.copy(alpha = 0.52f),
                                Color.Transparent,
                            ),
                        ),
                    ),
                )
            } else {
                Spacer(modifier = Modifier.height(10.dp))
            }

            SpecimenEpisodes(
                style = episodeCardStyle,
                blurUnwatched = blurUnwatched,
                cornerRadiusDp = cornerRadiusDp,
            )

            Spacer(modifier = Modifier.height(14.dp))

            SpecimenDetailSections(tabLayout = tabLayout, cornerRadiusDp = cornerRadiusDp)
        }
    }
}

/**
 * The sections under the episode list, stacked or grouped into one tab row.
 *
 * ⚠ **This exists because the toggle above it did nothing at all.** Revision 4's mock stopped at
 * the episode list, and the file said so - "the one control in the wizard whose effect the band
 * does not show". That turned out to understate it: every section's `tabGroup` defaults to null,
 * and `ConfiguredMetaSections` draws a `TabbedSectionGroup` only for a group with more than one
 * member, so `tabLayout = true` rendered identically to false **in the real details screen too**.
 * `MetaScreenSettingsRepository.setTabLayout` now seeds a default grouping, and this draws it.
 *
 * ⚠ Mirrors `TabbedSectionGroup` in `MetaDetailsScreen.kt`: the headings sit in one row
 * separated by `|`, the active one at full opacity, the inactive ones at **0.55**, the separator
 * at **0.45**. Off, each heading is its own `DetailSectionTitle` over its own rail. If that
 * treatment changes, change it here.
 *
 * The rail is deliberately abstract - blocks, not cast portraits. The three sections it stands for
 * hold different things (faces, trailer stills, a table of text), so any one of them drawn
 * literally would misdescribe the other two, and the point being made is about *grouping*.
 */
@Composable
private fun SpecimenDetailSections(tabLayout: Boolean, cornerRadiusDp: Int) {
    val tokens = MaterialTheme.nuvio
    val radius by animateDpAsState(
        targetValue = cornerRadiusDp.dp,
        animationSpec = tween(FadeTweenMillis, easing = LinearOutSlowInEasing),
        label = "specimen_sections_radius",
    )
    val titles = listOf(
        stringResource(Res.string.settings_meta_cast),
        stringResource(Res.string.settings_meta_trailers),
        stringResource(Res.string.meta_section_details_title),
    )

    @Composable
    fun Rail() {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Spacer(modifier = Modifier.width(16.dp))
            repeat(5) {
                Box(
                    modifier = Modifier
                        .width(46.dp)
                        .height(30.dp)
                        .clip(RoundedCornerShape(radius))
                        .background(tokens.colors.skeleton),
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
        }
    }

    AnimatedContent(
        targetState = tabLayout,
        transitionSpec = {
            fadeIn(tween(FadeTweenMillis)) togetherWith fadeOut(tween(FadeTweenMillis / 2))
        },
        label = "specimen_detail_sections",
    ) { tabbed ->
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (tabbed) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    titles.forEachIndexed { index, title ->
                        if (index > 0) {
                            Text(
                                text = "|",
                                style = MaterialTheme.typography.titleSmall,
                                color = tokens.colors.textPrimary.copy(alpha = 0.45f),
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        }
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (index == 0) {
                                tokens.colors.textPrimary
                            } else {
                                tokens.colors.textPrimary.copy(alpha = 0.55f)
                            },
                            maxLines = 1,
                        )
                    }
                }
                Rail()
            } else {
                // Only two of the three, and only one rail: stacked, they run down the page well
                // past the bottom of the band, and showing two headings is enough to read as
                // "these are separate sections". The tabbed state is the one that has to be
                // complete, because it is the one making the claim.
                titles.take(2).forEachIndexed { index, title ->
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.colors.textPrimary,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    if (index == 0) Rail()
                }
            }
        }
    }
}

/**
 * The details screen's matched-colour backdrop, extracted for real.
 *
 * Guessing a colour here would make the mock lie about the option it is illustrating, so this
 * runs the same kmpalette extraction and the same 0.42 blend towards the background that
 * `MetaDetailsScreen` runs. With no artwork it falls back to the plain background, which is
 * also what the real screen does.
 */
@Composable
private fun rememberDominantBackdropColor(painter: Painter?, enabled: Boolean): Color {
    val colorScheme = MaterialTheme.colorScheme
    val painterColorState = rememberPainterDominantColorState(
        defaultColor = colorScheme.background,
        defaultOnColor = colorScheme.onBackground,
    )

    LaunchedEffect(enabled, painter) {
        if (!enabled) return@LaunchedEffect
        val current = painter ?: return@LaunchedEffect
        runCatching { painterColorState.updateFrom(current) }
    }

    val target = if (enabled) {
        colorScheme.background.blendTowards(painterColorState.color, fraction = 0.42f)
    } else {
        colorScheme.background
    }
    val animated by animateColorAsState(
        targetValue = target,
        animationSpec = tween(320, easing = LinearOutSlowInEasing),
        label = "specimen_dominant_backdrop",
    )
    return animated
}

/** ⚠ Mirrors the private `blendTowards` in `features/details/MetaDetailsScreen.kt`. */
private fun Color.blendTowards(target: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = red + (target.red - red) * f,
        green = green + (target.green - green) * f,
        blue = blue + (target.blue - blue) * f,
        alpha = alpha,
    )
}

/**
 * Episode cards in each of the two styles.
 *
 * ⚠ Height budget inside `SetupSpecimen.Details` (380 dp): 110 hero + 18 seam + 14 gap + about
 * 76 for [SpecimenDetailSections] leaves ~160. Horizontal runs to about 124, List to about 118
 * at two rows. List takes **two** episodes and Horizontal **three** for that reason - a third
 * List row would overflow. The hero and the seam both shrank when the sections strip was added;
 * they are the parts that give, because the strip and the episode list are what the step's
 * controls act on.
 */
@Composable
private fun SpecimenEpisodes(
    style: MetaEpisodeCardStyle,
    blurUnwatched: Boolean,
    cornerRadiusDp: Int,
) {
    val radius by animateDpAsState(
        targetValue = cornerRadiusDp.dp,
        animationSpec = tween(FadeTweenMillis, easing = LinearOutSlowInEasing),
        label = "specimen_episode_radius",
    )

    when (style) {
        MetaEpisodeCardStyle.Horizontal -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(modifier = Modifier.width(16.dp))
            SetupSampleTitle.episodes.forEach { episode ->
                Column(
                    modifier = Modifier.width(176.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SpecimenEpisodeStill(
                        episode = episode,
                        blurred = blurUnwatched,
                        radius = radius,
                        modifier = Modifier.fillMaxWidth().height(84.dp),
                    )
                    SpecimenCaption(
                        title = "${episode.episodeNumber}. ${episode.title}",
                        caption = episode.runtime,
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
        }

        MetaEpisodeCardStyle.List -> Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SetupSampleTitle.episodes.take(2).forEach { episode ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SpecimenEpisodeStill(
                        episode = episode,
                        blurred = blurUnwatched,
                        radius = radius,
                        modifier = Modifier.width(96.dp).height(54.dp),
                    )
                    SpecimenCaption(
                        title = "${episode.episodeNumber}. ${episode.title}",
                        caption = episode.runtime,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * One episode still, with the app's own fallback chain.
 *
 * `episodes.metahub.space` is a different host from the show-artwork one and **has never been
 * reached from the sandbox**, so this cannot assume it answers. On failure it swaps to the
 * show's backdrop, which is exactly what `DetailSeriesContent` does
 * (`video.thumbnail ?: meta.background ?: meta.poster`) - so a dead host degrades this to the
 * repeated-backdrop look the real app has for a series with no episode artwork, rather than to
 * an empty box.
 */
@Composable
private fun SpecimenEpisodeStill(
    episode: SetupSampleTitle.SampleEpisode,
    blurred: Boolean,
    radius: Dp,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    var stillFailed by remember(episode.stillUrl) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(radius))
            .background(tokens.colors.skeleton),
    ) {
        AsyncImage(
            model = if (stillFailed) episode.fallbackStillUrl else episode.stillUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onError = { stillFailed = true },
            // 18 dp, matching `DetailSeriesContent`.
            modifier = Modifier
                .fillMaxSize()
                .then(if (blurred) Modifier.blur(18.dp) else Modifier),
        )
    }
}

// --- theme ---------------------------------------------------------------------------------

/**
 * The accent applied to the controls it actually colours.
 *
 * The palette swatches themselves live in the panel, so repeating them here would be a second
 * copy of the control. What the band shows instead is the consequence: a filled button, a
 * progress fill and a selected chip, all of which take the accent, next to artwork that does
 * not - which is the honest picture of how much of the app a palette changes.
 */
@Composable
private fun SpecimenTheme(cornerRadiusDp: Int) {
    val tokens = MaterialTheme.nuvio
    val radius by animateDpAsState(
        targetValue = cornerRadiusDp.dp,
        animationSpec = tween(FadeTweenMillis, easing = LinearOutSlowInEasing),
        label = "specimen_theme_radius",
    )
    val item = SetupSampleTitle.rowItems.first()

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(96.dp)
                .height((96 / PosterAspectRatio).dp)
                .clip(RoundedCornerShape(radius))
                .background(tokens.colors.skeleton),
        ) {
            AsyncImage(
                model = item.poster,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(tokens.colors.accent),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = tokens.colors.onAccent,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            NuvioProgressBar(
                progress = 0.42f,
                fillColor = tokens.colors.accent,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item.genres.take(2).forEach { genre ->
                    Text(
                        text = genre,
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.colors.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(tokens.colors.accent.copy(alpha = 0.16f))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}
