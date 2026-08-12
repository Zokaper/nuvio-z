package com.nuvio.app.features.setup

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.kmpalette.extensions.painter.rememberPainterDominantColorState
import com.kmpalette.rememberDominantColorState
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.details.MetaEpisodeCardStyle
import com.nuvio.app.features.details.MetaScreenBackgroundMode
import com.nuvio.app.features.details.MetaScreenSettingsRepository
import com.nuvio.app.features.details.components.DetailHero
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.components.HomeCatalogRowSection
import com.nuvio.app.features.home.components.HomeContinueWatchingSection
import com.nuvio.app.features.home.components.HomeHeroSection
import com.nuvio.app.features.home.components.rememberContinueWatchingLayout
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository

/**
 * What the stage is showing.
 *
 * Two live surfaces, not one per step. A step does not get its own bespoke preview: the stage
 * is a single object the user keeps looking at while it changes underneath them, which is the
 * only way "this is what your app will look like" reads as a promise rather than an
 * illustration.
 */
enum class SetupPreviewSurface {
    /** Hero banner, Continue Watching and a catalog row - everything the card and home steps set. */
    Home,

    /** The details screen, under whichever background treatment and episode style are current. */
    Details,
}

/**
 * The logical size the stage composes at, before scaling.
 *
 * ⚠ **This is the whole trick, and getting it wrong is how the preview starts lying.** Every
 * composable the stage renders sizes itself from *its container's* `maxWidth` -
 * `homeHeroLayout`, `rememberContinueWatchingLayout` and `homeSectionHorizontalPaddingForWidth`
 * all branch at 600/768/840/1024/1440 dp. Laid out at the stage's real width, a preview inside
 * a 1100 dp desktop wizard would pick *tablet* metrics, and one inside a 260 dp phone column
 * would pick phone metrics rendered at desktop scale. Neither is what the user's app will do.
 *
 * So the content always composes at one fixed logical width - a typical phone - and the whole
 * thing is scaled to fit afterwards. What the user sees is a scale model of a phone, which is
 * both honest and what a preview is expected to be.
 */
private val StageLogicalWidth: Dp = 390.dp
private val StageLogicalHeight: Dp = 620.dp

/**
 * The hint pair that fixes the hero's height inside the stage.
 *
 * These are the same two parameters `HomeScreen` passes down from its own `BoxWithConstraints`,
 * so the preview reaches `mobileHeroHeight` by the identical path the real screen does - it
 * just hands it the logical viewport instead of the device's. 620 less 240 leaves the hero
 * 380 dp and the Continue Watching row the rest, close to a real phone's split.
 */
private val StageBelowHeroHint: Dp = 240.dp

/** Matches `homeSectionHorizontalPaddingForWidth` below 768 dp, which is what 390 dp is. */
private val StageSectionPadding: Dp = 16.dp

/**
 * The live preview the wizard is built around.
 *
 * Everything here is the **real** composable reading the **real** settings repository. The
 * wizard writes each choice through the repository's own setter the moment it is tapped, so
 * there is no preview state, no override parameters and no second rendering path that could
 * drift from the app. If the stage is wrong, the app is wrong in the same way.
 *
 * ⚠ **Named arguments at every call site below, and it is not a style preference.** The four
 * composables this file calls have all diverged between the two repositories: desktop's
 * `HomeHeroSection` gained a `sectionPadding` parameter in the middle of its list and its
 * `DetailHero` gained a `viewportHeight`. Named arguments make both harmless; positional ones
 * would have bound silently to the wrong slot.
 *
 * ⚠ **This file is NOT byte-identical across the repositories, and must not be `cp`'d.**
 * `NuvioZDesktop`'s `HomeContinueWatchingSection` takes a *required* `dataSourceKey` that this
 * repository's copy does not have, so the desktop version carries exactly one extra argument
 * at the `HomeContinueWatchingSection` call below. Everything else must stay in step by hand.
 */
@Composable
fun SetupPreviewStage(
    surface: SetupPreviewSurface,
    catalogRowTitle: String,
    continueWatchingTitle: String,
    episodesSectionTitle: String,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val stageShape = RoundedCornerShape(18.dp)

    BoxWithConstraints(modifier = modifier) {
        val scale = minOf(
            maxWidth / StageLogicalWidth,
            maxHeight / StageLogicalHeight,
        ).coerceAtMost(1f)

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(StageLogicalWidth * scale, StageLogicalHeight * scale)
                .clip(stageShape)
                // A gradient floor, so a stage whose artwork has not loaded - or cannot,
                // because there is no network - reads as a dimmed screen rather than a broken
                // one. The wizard has to survive aeroplane mode intact.
                .background(
                    Brush.verticalGradient(
                        listOf(tokens.colors.surfaceElevated, tokens.colors.background),
                    ),
                )
                .border(
                    width = tokens.borders.hairline,
                    color = tokens.colors.borderSubtle,
                    shape = stageShape,
                ),
        ) {
            Box(
                modifier = Modifier
                    // requiredSize, not size: this deliberately escapes the parent's
                    // constraints so the content lays out at the logical phone size and is
                    // then scaled, rather than being squeezed into the stage's real box.
                    .requiredSize(StageLogicalWidth, StageLogicalHeight)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0f, 0f)
                    },
            ) {
                Crossfade(
                    targetState = surface,
                    animationSpec = tween(durationMillis = 260),
                    label = "setup_preview_surface",
                ) { current ->
                    when (current) {
                        SetupPreviewSurface.Home -> StageHome(
                            catalogRowTitle = catalogRowTitle,
                            continueWatchingTitle = continueWatchingTitle,
                        )

                        SetupPreviewSurface.Details -> StageDetails(
                            episodesSectionTitle = episodesSectionTitle,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Hero, Continue Watching and one catalog row - the three things the card and home steps move.
 *
 * The hero is present or absent exactly as `HomeScreen` decides it, from the same repository
 * flag, so turning it off in the wizard removes it here for the same reason it will remove it
 * from the home screen.
 */
@Composable
private fun StageHome(
    catalogRowTitle: String,
    continueWatchingTitle: String,
) {
    val homeSettings by remember {
        HomeCatalogSettingsRepository.ensureLoaded()
        HomeCatalogSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val continueWatching by remember {
        ContinueWatchingPreferencesRepository.ensureLoaded()
        ContinueWatchingPreferencesRepository.uiState
    }.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (homeSettings.heroEnabled) {
            HomeHeroSection(
                items = SetupSampleTitle.rowItems,
                viewportHeight = StageLogicalHeight,
                mobileBelowSectionHeightHint = StageBelowHeroHint,
            )
        }

        if (continueWatching.isVisible) {
            HomeContinueWatchingSection(
                items = SetupSampleTitle.continueWatching,
                style = continueWatching.style,
                useEpisodeThumbnails = continueWatching.useEpisodeThumbnails,
                blurNextUp = continueWatching.blurNextUp,
                title = continueWatchingTitle,
                sectionPadding = StageSectionPadding,
                layout = rememberContinueWatchingLayout(StageLogicalWidth.value),
            )
        }

        HomeCatalogRowSection(
            section = SetupSampleTitle.catalogSection(catalogRowTitle),
            sectionPadding = StageSectionPadding,
        )
    }
}

/**
 * The details screen: background treatment, the real hero, and the episode list beneath it.
 *
 * ⚠ **The background layer is reproduced here rather than reused, and it is the one thing in
 * this file that is not a shipped composable.** `MetaDetailsScreen` paints it as a sibling of
 * its `LazyColumn` inside a screen that owns a fetch, a scroll state, a nav controller and a
 * dominant-colour extractor - none of which a preview can supply - and that file is on
 * `AGENTS.md`'s "legitimately differs between the repositories" list, so reaching into it is
 * the one move here that could not be mirrored by copying.
 *
 * What *is* reproduced is reproduced exactly: the same `when` over the three modes, the same
 * 30 dp blur, the same 0.92 scrim, the same kmpalette extraction and the same 0.42 blend
 * towards the background. `DetailHero` itself - backdrop, gradient, logo with its text
 * fallback, genre line - is the shipped composable, and it is what the modes are judged
 * against. If the real screen's treatment changes, this has to change with it.
 */
@Composable
private fun StageDetails(episodesSectionTitle: String) {
    val colorScheme = MaterialTheme.colorScheme
    val metaSettings by remember {
        MetaScreenSettingsRepository.ensureLoaded()
        MetaScreenSettingsRepository.uiState
    }.collectAsStateWithLifecycle()

    val meta = SetupSampleTitle.featured
    val backdropUrl = meta.background ?: meta.poster
    val backgroundMode = metaSettings.backgroundMode
    val dominantColorEnabled = backgroundMode == MetaScreenBackgroundMode.DominantColor &&
        !backdropUrl.isNullOrBlank()

    var backdropPainter by remember(backdropUrl) { mutableStateOf<Painter?>(null) }
    var backdropImageBitmap by remember(backdropUrl) { mutableStateOf<ImageBitmap?>(null) }
    val imageBitmapColorState = rememberDominantColorState(
        defaultColor = colorScheme.background,
        defaultOnColor = colorScheme.onBackground,
    )
    val painterColorState = rememberPainterDominantColorState(
        defaultColor = colorScheme.background,
        defaultOnColor = colorScheme.onBackground,
    )
    LaunchedEffect(dominantColorEnabled, backdropImageBitmap, backdropPainter) {
        if (!dominantColorEnabled) return@LaunchedEffect
        val bitmap = backdropImageBitmap
        val painter = backdropPainter
        when {
            bitmap != null -> runCatching { imageBitmapColorState.updateFrom(bitmap) }
            painter != null -> runCatching { painterColorState.updateFrom(painter) }
        }
    }
    val extracted = if (backdropImageBitmap != null) {
        imageBitmapColorState.color
    } else {
        painterColorState.color
    }
    val targetBackdropColor = if (dominantColorEnabled) {
        colorScheme.background.blendTowards(extracted, fraction = 0.42f)
    } else {
        colorScheme.background
    }
    val backdropColor by animateColorAsState(
        targetValue = targetBackdropColor,
        animationSpec = tween(durationMillis = 320, easing = LinearOutSlowInEasing),
        label = "setup_preview_dominant_backdrop",
    )

    Box(modifier = Modifier.fillMaxSize()) {
        when (backgroundMode) {
            MetaScreenBackgroundMode.Normal -> Unit

            MetaScreenBackgroundMode.Cinematic -> if (backdropUrl != null) {
                AsyncImage(
                    model = backdropUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(30.dp),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colorScheme.background.copy(alpha = 0.92f)),
                )
            }

            MetaScreenBackgroundMode.DominantColor -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(backdropColor),
            )
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            DetailHero(
                meta = meta,
                isTablet = false,
                contentMaxWidth = StageLogicalWidth,
                heroGradientColor = backdropColor.takeIf { dominantColorEnabled },
                onBackdropLoaded = { painter, bitmap ->
                    backdropPainter = painter
                    backdropImageBitmap = bitmap
                },
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StageSectionHeader(
                    title = episodesSectionTitle,
                    tabbed = metaSettings.tabLayout,
                )
                SetupSampleTitle.episodes.forEach { episode ->
                    StageEpisodeCard(
                        episode = episode,
                        style = metaSettings.episodeCardStyle,
                    )
                }
            }
        }
    }
}

/**
 * The section header, stacked or as a tab strip.
 *
 * `TabbedSectionGroup` in `MetaDetailsScreen.kt` is what the real screen uses and it is
 * private to a file that diverges, so this shows the *shape* of the choice - one title, or a
 * row of them with the current one underlined - rather than reaching for it.
 */
@Composable
private fun StageSectionHeader(title: String, tabbed: Boolean) {
    val tokens = MaterialTheme.nuvio
    if (!tabbed) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        listOf(title to true, "Cast" to false, "More" to false).forEach { (label, selected) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (selected) tokens.colors.textPrimary else tokens.colors.textMuted,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .width(if (selected) 20.dp else 0.dp)
                        .height(2.dp)
                        .background(tokens.colors.accent, RoundedCornerShape(1.dp)),
                )
            }
        }
    }
}

/**
 * One episode, in whichever of the two shapes is selected.
 *
 * ⚠ **Not the shipped card.** `EpisodeHorizontalCard` and `EpisodeListCard` are private inside
 * `DetailSeriesContent.kt`, a 64 KB file that also diverges between the repositories, and they
 * take a `MetaVideo` plus watch progress, download state, IMDb ratings and skip segments that a
 * preview has none of. This draws the same two silhouettes - 16:9 still above the text, or a
 * thumbnail beside it - with the real artwork and the real tokens, which is what the choice is
 * actually asking the user to compare.
 */
@Composable
private fun StageEpisodeCard(
    episode: SetupSampleTitle.SampleEpisode,
    style: MetaEpisodeCardStyle,
) {
    val tokens = MaterialTheme.nuvio
    val label = "S${episode.seasonNumber} E${episode.episodeNumber} · ${episode.title}"
    val shape = RoundedCornerShape(10.dp)

    when (style) {
        MetaEpisodeCardStyle.Horizontal -> Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AsyncImage(
                model = episode.stillUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .clip(shape)
                    .background(tokens.colors.skeleton),
                contentScale = ContentScale.Crop,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = episode.runtime,
                style = MaterialTheme.typography.labelMedium,
                color = tokens.colors.textMuted,
            )
        }

        MetaEpisodeCardStyle.List -> Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = episode.stillUrl,
                contentDescription = null,
                modifier = Modifier
                    .width(104.dp)
                    .height(59.dp)
                    .clip(shape)
                    .background(tokens.colors.skeleton),
                contentScale = ContentScale.Crop,
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = episode.overview,
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.colors.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The blend `MetaDetailsScreen.dominantBackdropBlendColor` performs, at the same fraction.
 *
 * Copied rather than shared because the original is private to a file that differs between the
 * two repositories; four lines of arithmetic is a smaller liability than making a member of
 * that file internal and having to hand-port the change twice.
 */
private fun Color.blendTowards(target: Color, fraction: Float): Color {
    val clamped = fraction.coerceIn(0f, 1f)
    return Color(
        red = red + (target.red - red) * clamped,
        green = green + (target.green - green) * clamped,
        blue = blue + (target.blue - blue) * clamped,
        alpha = alpha + (target.alpha - alpha) * clamped,
    )
}
