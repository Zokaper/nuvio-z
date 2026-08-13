package com.nuvio.app.features.setup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle

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
 * | [SpecimenDetailsBackground] | `MetaDetailsScreen`'s three treatments | the 0.92 scrim and 0.42 dominant blend |
 * | [SpecimenEpisodes] | `DetailSeriesContent`'s two card styles | the 18 dp blur it uses |
 *
 * If one of those changes, change it here too.
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
    /** A catalog row, at the chosen shape, size, corner radius and title setting. */
    Cards(preferredHeight = 280.dp),

    /** The featured banner, appearing and disappearing above a row. */
    HomeHero(preferredHeight = 300.dp),

    /** Continue Watching cards, in the chosen style. */
    HomeContinueWatching(preferredHeight = 240.dp),

    /** The three details-screen background treatments, side by side. */
    DetailsBackground(preferredHeight = 210.dp),

    /** Episode cards, in the chosen style. */
    DetailsEpisodes(preferredHeight = 220.dp),

    /** The accent colour applied to real controls. */
    Theme(preferredHeight = 190.dp),

    /**
     * The steps that change nothing visible. See `SetupDiagram.kt`.
     *
     * The smallest of the seven on purpose: the playback-mode step is the tallest panel in the
     * flow - three `PlaybackModeCard`s - and in revision 2 it was cut off mid-card.
     */
    Diagram(preferredHeight = 180.dp),
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
            // A gradient floor so a band whose artwork has not loaded - or cannot, with no
            // network - reads as a dimmed screen rather than a broken one.
            .background(
                Brush.verticalGradient(
                    listOf(tokens.colors.surface, tokens.colors.background),
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
                SetupSpecimen.Cards -> SpecimenCards(
                    posterWidthDp = posterWidthDp,
                    cornerRadiusDp = posterCornerRadiusDp,
                    landscape = landscapeCards,
                    showTitles = showCardTitles,
                )

                SetupSpecimen.HomeHero -> SpecimenHomeHero(
                    heroEnabled = heroEnabled,
                    posterWidthDp = posterWidthDp,
                    cornerRadiusDp = posterCornerRadiusDp,
                    landscape = landscapeCards,
                )

                SetupSpecimen.HomeContinueWatching -> SpecimenContinueWatching(
                    style = continueWatchingStyle,
                    useEpisodeThumbnails = useEpisodeThumbnails,
                    blurNextUp = blurNextUp,
                    cornerRadiusDp = posterCornerRadiusDp,
                    nextUpLabel = nextUpLabel,
                )

                SetupSpecimen.DetailsBackground -> SpecimenDetailsBackground(
                    mode = backgroundMode,
                )

                SetupSpecimen.DetailsEpisodes -> SpecimenEpisodes(
                    style = episodeCardStyle,
                    blurUnwatched = blurUnwatchedEpisodes,
                    cornerRadiusDp = posterCornerRadiusDp,
                )

                SetupSpecimen.Theme -> SpecimenTheme(
                    cornerRadiusDp = posterCornerRadiusDp,
                )

                SetupSpecimen.Diagram -> SetupDiagram(step = step)
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

// --- home ----------------------------------------------------------------------------------

/**
 * The featured banner, with a row beneath it.
 *
 * The row is present in both states on purpose: the choice is not "banner or nothing", it is
 * what the top of the home screen is, so the thing the banner displaces has to be visible.
 */
@Composable
private fun SpecimenHomeHero(
    heroEnabled: Boolean,
    posterWidthDp: Int,
    cornerRadiusDp: Int,
    landscape: Boolean,
) {
    val tokens = MaterialTheme.nuvio
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
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
                    modifier = Modifier.padding(bottom = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AsyncImage(
                        model = item.logo,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.height(34.dp).widthIn(max = 190.dp),
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

        // A compact row, so the band shows what the banner sits above without becoming a
        // second copy of the Cards step. ⚠ 0.62 is set against the band height: at the largest
        // card size this row is 128 dp, which with the 150 dp banner and the 14 dp gap comes to
        // 292 - inside `HomeHero`'s 300 dp. Raising either without raising that clips.
        SpecimenCards(
            posterWidthDp = (posterWidthDp * 0.62f).toInt(),
            cornerRadiusDp = cornerRadiusDp,
            landscape = landscape,
            showTitles = false,
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
 * The three details-screen background treatments, side by side.
 *
 * All three are drawn at once rather than only the chosen one, because the choice is a
 * comparison - "blurred art" and "matched colour" mean very little named and a great deal seen
 * next to each other. The selected one is lifted and outlined; selection itself stays with the
 * chips in the panel, so there is exactly one control for it.
 */
@Composable
private fun SpecimenDetailsBackground(mode: MetaScreenBackgroundMode) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.width(16.dp))
        MetaScreenBackgroundMode.entries.forEach { entry ->
            SpecimenBackgroundSwatch(mode = entry, selected = entry == mode)
        }
        Spacer(modifier = Modifier.width(16.dp))
    }
}

@Composable
private fun SpecimenBackgroundSwatch(
    mode: MetaScreenBackgroundMode,
    selected: Boolean,
) {
    val tokens = MaterialTheme.nuvio
    val backdropUrl = SetupSampleTitle.backgroundUrl(SetupSampleTitle.featuredImdbId)
    val posterUrl = SetupSampleTitle.posterUrl(SetupSampleTitle.featuredImdbId)

    val borderWidth by animateDpAsState(
        targetValue = if (selected) 2.dp else tokens.borders.hairline,
        animationSpec = tween(FadeTweenMillis),
        label = "specimen_swatch_border",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) tokens.colors.accent else tokens.colors.borderSubtle,
        animationSpec = tween(FadeTweenMillis),
        label = "specimen_swatch_border_color",
    )
    val contentAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0.62f,
        animationSpec = tween(FadeTweenMillis),
        label = "specimen_swatch_alpha",
    )

    var backdropPainter by remember(backdropUrl) { mutableStateOf<Painter?>(null) }
    val dominant = rememberDominantBackdropColor(
        painter = backdropPainter,
        enabled = mode == MetaScreenBackgroundMode.DominantColor,
    )

    Box(
        modifier = Modifier
            .width(124.dp)
            .height(168.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(tokens.colors.background)
            .border(borderWidth, borderColor, RoundedCornerShape(14.dp)),
    ) {
        // The background treatment itself. ⚠ The blur radius and the scrim alpha mirror
        // `MetaDetailsScreen`'s Cinematic mode, and the 0.42 blend mirrors its DominantColor
        // mode. Changing them there means changing them here.
        when (mode) {
            MetaScreenBackgroundMode.Normal -> Box(
                modifier = Modifier.fillMaxSize().background(tokens.colors.background),
            )

            MetaScreenBackgroundMode.Cinematic -> {
                AsyncImage(
                    model = backdropUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().blur(18.dp),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(tokens.colors.background.copy(alpha = 0.92f)),
                )
            }

            MetaScreenBackgroundMode.DominantColor -> {
                // Drawn, then covered. The extractor needs a painter and the only way to get
                // one is to let Coil actually load the image, so the backdrop is laid out at
                // full size underneath an opaque fill. `dominant` starts *at* the background
                // colour and animates towards the extracted one, so the image is never visible.
                AsyncImage(
                    model = backdropUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onSuccess = { backdropPainter = it.painter },
                    modifier = Modifier.fillMaxSize(),
                )
                Box(modifier = Modifier.fillMaxSize().background(dominant))
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .width(58.dp)
                    .height((58 / PosterAspectRatio).dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(tokens.colors.skeleton),
            ) {
                AsyncImage(
                    model = posterUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            SpecimenTextLine(width = 72.dp, color = tokens.colors.textSecondary, alpha = contentAlpha)
            SpecimenTextLine(width = 52.dp, color = tokens.colors.textMuted, alpha = contentAlpha)
            SpecimenTextLine(width = 62.dp, color = tokens.colors.textMuted, alpha = contentAlpha)
        }
    }
}

/** A stand-in for a line of text. Deliberately abstract - the swatch is about the backdrop. */
@Composable
private fun SpecimenTextLine(width: Dp, color: Color, alpha: Float) {
    Box(
        modifier = Modifier
            .width(width)
            .height(5.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(color.copy(alpha = 0.55f * alpha)),
    )
}

/**
 * The details screen's matched-colour backdrop, extracted for real.
 *
 * Guessing a colour here would make the swatch lie about the option it is illustrating, so this
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

/** Episode cards in each of the two styles, with the unwatched blur applied. */
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
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.width(16.dp))
            SetupSampleTitle.episodes.forEach { episode ->
                Column(
                    modifier = Modifier.width(212.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SpecimenEpisodeStill(
                        blurred = blurUnwatched,
                        radius = radius,
                        modifier = Modifier.fillMaxWidth().height(120.dp),
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SetupSampleTitle.episodes.forEach { episode ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SpecimenEpisodeStill(
                        blurred = blurUnwatched,
                        radius = radius,
                        modifier = Modifier.width(112.dp).height(64.dp),
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

@Composable
private fun SpecimenEpisodeStill(
    blurred: Boolean,
    radius: Dp,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(radius))
            .background(tokens.colors.skeleton),
    ) {
        AsyncImage(
            model = SetupSampleTitle.backgroundUrl(SetupSampleTitle.featuredImdbId),
            contentDescription = null,
            contentScale = ContentScale.Crop,
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
