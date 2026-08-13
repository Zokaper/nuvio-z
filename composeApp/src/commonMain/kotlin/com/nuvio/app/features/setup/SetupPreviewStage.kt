package com.nuvio.app.features.setup

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
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
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository

/**
 * Which part of the app the preview is showing, and which part of it to bring into view.
 *
 * One value per thing the wizard can change, rather than one per screen, because the preview's
 * job is to have the *thing being changed* on screen. A Continue Watching step spent looking
 * at a hero banner is a preview in name only.
 */
enum class SetupPreviewFocus(internal val surface: Surface) {
    /** Home, resting at the top. Used by the steps that change nothing visible. */
    Home(Surface.Home),

    /** Home, hero banner in view. */
    HomeHero(Surface.Home),

    /** Home, Continue Watching row in view. */
    HomeContinueWatching(Surface.Home),

    /** Home, catalog row in view. */
    HomeCatalog(Surface.Home),

    /** Details, hero and background treatment in view. */
    DetailsHero(Surface.Details),

    /** Details, episode list in view. */
    DetailsEpisodes(Surface.Details),
    ;

    internal enum class Surface { Home, Details }
}

/**
 * The live preview the wizard is built around.
 *
 * Everything here is the **real** composable reading the **real** settings repository. The
 * wizard writes each choice through the repository's own setter the moment it is tapped, so
 * there is no preview state, no override parameters and no second rendering path that could
 * drift from the app. If the stage is wrong, the app is wrong in the same way.
 *
 * ⚠ **It composes at the size it is actually given, and that is the point.** An earlier version
 * pinned the content to a fixed 390x620 logical phone and scaled it, to stop a narrow
 * side-by-side column from making `homeHeroLayout` / `rememberContinueWatchingLayout` /
 * `homeSectionHorizontalPaddingForWidth` pick phone metrics inside a desktop window. With the
 * preview filling the window that cannot happen: the stage *is* roughly the real viewport, so
 * those helpers pick exactly the branch the real app picks. On a desktop window this previews
 * the **desktop** app, at desktop metrics - which is what it should have been doing all along.
 *
 * ⚠ **Named arguments at every call site below, and it is not a style preference.** The
 * composables this file calls have diverged between the two repositories: desktop's
 * `HomeHeroSection` gained a `sectionPadding` parameter in the middle of its list and its
 * `DetailHero` gained a `viewportHeight`. Named arguments make both harmless; positional ones
 * would bind silently to the wrong slot.
 *
 * ⚠ **This file is NOT byte-identical across the repositories and must not be `cp`'d.**
 * `NuvioZDesktop`'s `HomeContinueWatchingSection` takes a *required* `dataSourceKey` that this
 * repository's copy does not have, so the desktop version carries one extra argument at that
 * call. Everything else must stay in step by hand.
 *
 * @param bottomInset how much of the stage the wizard's sheet covers. Content scrolls above it
 *   rather than under it, so the last row is reachable instead of permanently hidden.
 */
@Composable
fun SetupPreviewStage(
    focus: SetupPreviewFocus,
    catalogRowTitle: String,
    continueWatchingTitle: String,
    episodesSectionTitle: String,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio

    Box(
        modifier = modifier
            .fillMaxSize()
            // A gradient floor, so a stage whose artwork has not loaded - or cannot, because
            // there is no network - reads as a dimmed screen rather than a broken one. The
            // wizard has to survive aeroplane mode intact.
            .background(
                Brush.verticalGradient(
                    listOf(tokens.colors.surfaceElevated, tokens.colors.background),
                ),
            ),
    ) {
        Crossfade(
            targetState = focus.surface,
            animationSpec = tween(durationMillis = 260),
            label = "setup_preview_surface",
        ) { surface ->
            when (surface) {
                SetupPreviewFocus.Surface.Home -> StageHome(
                    focus = focus,
                    catalogRowTitle = catalogRowTitle,
                    continueWatchingTitle = continueWatchingTitle,
                    bottomInset = bottomInset,
                )

                SetupPreviewFocus.Surface.Details -> StageDetails(
                    focus = focus,
                    episodesSectionTitle = episodesSectionTitle,
                    bottomInset = bottomInset,
                )
            }
        }
    }
}

/**
 * Scrolls [scrollState] so the section registered under [focus] sits at the top of the stage.
 *
 * Positions are collected by `onGloballyPositioned` rather than assumed, because the hero's
 * height comes from `homeHeroLayout` and changes with the window - and with whether the hero
 * is switched on at all.
 *
 * ⚠ **Both figures are root-relative and subtracted, rather than read as a position within the
 * parent.** The scrolling `Column` *is* the content, so it moves under the viewport as the user
 * scrolls; the difference between a section's root position and the column's own is the stable
 * content offset, while either one alone is not.
 */
@Composable
private fun AnchorEffect(
    focus: SetupPreviewFocus,
    offsets: Map<SetupPreviewFocus, Float>,
    contentTop: Float,
    scrollState: ScrollState,
) {
    val target = offsets[focus]
    LaunchedEffect(focus, target, contentTop) {
        val destination = target ?: return@LaunchedEffect
        scrollState.animateScrollTo((destination - contentTop).toInt().coerceAtLeast(0))
    }
}

@Composable
private fun StageHome(
    focus: SetupPreviewFocus,
    catalogRowTitle: String,
    continueWatchingTitle: String,
    bottomInset: Dp,
) {
    val homeSettings by remember {
        HomeCatalogSettingsRepository.ensureLoaded()
        HomeCatalogSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val continueWatching by remember {
        ContinueWatchingPreferencesRepository.ensureLoaded()
        ContinueWatchingPreferencesRepository.uiState
    }.collectAsStateWithLifecycle()

    val scrollState = rememberScrollState()
    val offsets = remember { mutableStateMapOf<SetupPreviewFocus, Float>() }
    var contentTop by remember { mutableStateOf(0f) }
    AnchorEffect(
        focus = focus,
        offsets = offsets,
        contentTop = contentTop,
        scrollState = scrollState,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .onGloballyPositioned { contentTop = it.localToRoot(Offset.Zero).y },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (homeSettings.heroEnabled) {
            Box(modifier = Modifier.anchor(SetupPreviewFocus.HomeHero, offsets)) {
                HomeHeroSection(items = SetupSampleTitle.rowItems)
            }
        }

        if (continueWatching.isVisible) {
            Box(modifier = Modifier.anchor(SetupPreviewFocus.HomeContinueWatching, offsets)) {
                HomeContinueWatchingSection(
                    items = SetupSampleTitle.continueWatching,
                    style = continueWatching.style,
                    useEpisodeThumbnails = continueWatching.useEpisodeThumbnails,
                    blurNextUp = continueWatching.blurNextUp,
                    title = continueWatchingTitle,
                )
            }
        }

        Box(modifier = Modifier.anchor(SetupPreviewFocus.HomeCatalog, offsets)) {
            HomeCatalogRowSection(section = SetupSampleTitle.catalogSection(catalogRowTitle))
        }

        // A second row, so the catalog step has something below the one it is changing and the
        // card size choice reads as a layout rather than as one isolated shelf.
        HomeCatalogRowSection(section = SetupSampleTitle.secondCatalogSection(catalogRowTitle))

        Spacer(modifier = Modifier.height(bottomInset))
    }
}

/**
 * The details screen: background treatment, the real hero, and the episode list beneath it.
 *
 * ⚠ **The background layer is reproduced here rather than reused, and it is the one thing in
 * this file that is not a shipped composable.** `MetaDetailsScreen` paints it as a sibling of
 * its `LazyColumn` inside a screen that owns a fetch, a scroll state, a nav controller and a
 * dominant-colour extractor - none of which a preview can supply - and that file is on
 * `AGENTS.md`'s "legitimately differs between the repositories" list.
 *
 * What *is* reproduced is reproduced exactly: the same `when` over the three modes, the same
 * 30 dp blur, the same 0.92 scrim, the same kmpalette extraction and the same 0.42 blend
 * towards the background. `DetailHero` itself is the shipped composable. If the real screen's
 * treatment changes, this has to change with it.
 */
@Composable
private fun StageDetails(
    focus: SetupPreviewFocus,
    episodesSectionTitle: String,
    bottomInset: Dp,
) {
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

    val scrollState = rememberScrollState()
    val offsets = remember { mutableStateMapOf<SetupPreviewFocus, Float>() }
    var contentTop by remember { mutableStateOf(0f) }
    AnchorEffect(
        focus = focus,
        offsets = offsets,
        contentTop = contentTop,
        scrollState = scrollState,
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .onGloballyPositioned { contentTop = it.localToRoot(Offset.Zero).y },
        ) {
            Box(modifier = Modifier.anchor(SetupPreviewFocus.DetailsHero, offsets)) {
                DetailHero(
                    meta = meta,
                    isTablet = false,
                    heroGradientColor = backdropColor.takeIf { dominantColorEnabled },
                    onBackdropLoaded = { painter, bitmap ->
                        backdropPainter = painter
                        backdropImageBitmap = bitmap
                    },
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .anchor(SetupPreviewFocus.DetailsEpisodes, offsets)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StageSectionHeader(
                    title = episodesSectionTitle,
                    tabbed = metaSettings.tabLayout,
                )
                SetupSampleTitle.episodes.forEach { episode ->
                    StageEpisodeCard(
                        episode = episode,
                        style = metaSettings.episodeCardStyle,
                        blurred = metaSettings.blurUnwatchedEpisodes,
                    )
                }
            }

            Spacer(modifier = Modifier.height(bottomInset))
        }
    }
}

/**
 * Records this element's root-relative y position for [AnchorEffect].
 *
 * `localToRoot(Offset.Zero)` rather than a position-in-parent helper: it is the primitive every
 * other positioning API is built on, and the anchor only needs a figure it can subtract a
 * sibling's from.
 */
private fun Modifier.anchor(
    focus: SetupPreviewFocus,
    offsets: MutableMap<SetupPreviewFocus, Float>,
): Modifier = this.onGloballyPositioned { coordinates ->
    offsets[focus] = coordinates.localToRoot(Offset.Zero).y
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
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
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
 *
 * [blurred] is `blurUnwatchedEpisodes`. Every sample episode is unwatched, so the setting
 * applies to all of them - which is the honest demonstration of what it does to a season you
 * have not started.
 */
@Composable
private fun StageEpisodeCard(
    episode: SetupSampleTitle.SampleEpisode,
    style: MetaEpisodeCardStyle,
    blurred: Boolean,
) {
    val tokens = MaterialTheme.nuvio
    val label = "S${episode.seasonNumber} E${episode.episodeNumber} · ${episode.title}"
    val shape = RoundedCornerShape(10.dp)
    val stillBlur = if (blurred) 14.dp else 0.dp

    when (style) {
        MetaEpisodeCardStyle.Horizontal -> Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AsyncImage(
                model = episode.stillUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(shape)
                    .background(tokens.colors.skeleton)
                    .blur(stillBlur),
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
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = episode.stillUrl,
                contentDescription = null,
                modifier = Modifier
                    .width(132.dp)
                    .height(74.dp)
                    .clip(shape)
                    .background(tokens.colors.skeleton)
                    .blur(stillBlur),
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
