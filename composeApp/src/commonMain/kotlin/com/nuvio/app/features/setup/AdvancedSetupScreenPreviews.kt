package com.nuvio.app.features.setup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.i18n.localizedMediaTypeLabel
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioBlockPointerEvents
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.features.details.EpisodeRatingsVisibility
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaEpisodeCardStyle
import com.nuvio.app.features.details.MetaExternalRating
import com.nuvio.app.features.details.MetaScreenBackgroundMode
import com.nuvio.app.features.details.components.DetailActionButtons
import com.nuvio.app.features.details.components.DetailMetaInfo
import com.nuvio.app.features.details.components.DetailRatingsRow
import com.nuvio.app.features.details.components.DetailSecondaryAction
import com.nuvio.app.features.home.components.HomeCatalogRowSection
import com.nuvio.app.features.home.components.HomeContinueWatchingSection
import com.nuvio.app.features.home.components.HomeHeroSection
import com.nuvio.app.features.home.components.homeHeroLayout
import com.nuvio.app.features.home.components.homeSectionHorizontalPaddingForWidth
import com.nuvio.app.features.home.components.rememberContinueWatchingLayout
import com.nuvio.app.features.mdblist.MdbListMetadataService
import com.nuvio.app.features.tracking.WatchProgressSource
import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

// String keys by wildcard, as `SetupWizardScreen.kt` does.

/**
 * Advanced Setup's previews of whole screens - Home, the Detail page and Enhanced metadata - drawn as
 * **miniatures of the real screens** (setup polish, physical QA).
 *
 * The first versions were loose drawings sized to a phone band, and on a phone they cropped: the
 * Detail page lost everything below the episode row, and what was left of "More like this" read as
 * unrelated artwork under a trailers heading. These lay the screen out at a real phone's width (or a
 * desktop window's) with the app's own composables wherever they take plain data - the Home hero,
 * Continue Watching and catalog rows; the detail page's action buttons, meta line and ratings row -
 * and scale the whole screen to fit the band ([PreviewStage]), so nothing is cut and every section
 * sits where the app puts it.
 */

private val PhoneScreenWidth = 390.dp

// --- Home ---------------------------------------------------------------------------------

/**
 * A miniature Home screen from the real sections: the hero (or none), Continue Watching in the chosen
 * style with the chosen thumbnails, and catalog rows titled with or without their type.
 *
 * [focusContinueWatching] scrolls the screen the way `SetupHomeStill` does, so the Continue Watching
 * panel's row is in view even under a tall hero; the hero panel shows the top of the screen.
 */
@Composable
internal fun SpecimenHomeScreen(
    heroEnabled: Boolean,
    showCatalogType: Boolean,
    continueWatchingVisible: Boolean,
    continueWatchingStyle: ContinueWatchingSectionStyle,
    useEpisodeThumbnails: Boolean,
    blurNextUp: Boolean,
    focusContinueWatching: Boolean,
    desktop: Boolean,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val stageW = if (desktop) 1280.dp else PhoneScreenWidth
    val stageH = if (desktop) 760.dp else 700.dp
    val catalogName = stringResource(Res.string.advanced_preview_catalog_name)
    val secondName = stringResource(Res.string.advanced_preview_catalog_name_second)
    val typeLabel = localizedMediaTypeLabel("series")
    val firstTitle = if (showCatalogType) stringResource(Res.string.home_catalog_default_title, catalogName, typeLabel) else catalogName
    val secondTitle = if (showCatalogType) stringResource(Res.string.home_catalog_default_title, secondName, typeLabel) else secondName

    PreviewStage(
        logicalWidth = stageW,
        logicalHeight = stageH,
        modifier = modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
        cornerRadius = if (desktop) 10.dp else 18.dp,
        background = tokens.colors.background,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().nuvioBlockPointerEvents()) {
            val density = LocalDensity.current
            val heroLayout = homeHeroLayout(maxWidthDp = maxWidth.value, viewportHeightDp = maxHeight.value)
            val scrollPx = when {
                !heroEnabled -> 0
                focusContinueWatching -> with(density) { (heroLayout.heroHeight - 250.dp).coerceAtLeast(0.dp).roundToPx() }
                else -> with(density) { (heroLayout.heroHeight - 360.dp).coerceAtLeast(0.dp).roundToPx() }
            }
            val listState = remember(heroEnabled, focusContinueWatching, scrollPx) { LazyListState(0, scrollPx) }
            val sectionPadding = homeSectionHorizontalPaddingForWidth(maxWidth.value)
            val continueWatchingLayout = rememberContinueWatchingLayout(maxWidth.value)
            NuvioScreen(
                horizontalPadding = 0.dp,
                topPadding = if (heroEnabled) 0.dp else if (desktop) 112.dp else 28.dp,
                listState = listState,
            ) {
                if (heroEnabled) {
                    item(key = "hero") {
                        HomeHeroSection(
                            items = SetupSampleTitle.rowItems.take(1),
                            viewportHeight = maxHeight,
                            listState = listState,
                        )
                    }
                }
                if (continueWatchingVisible) {
                    item(key = "cw") {
                        HomeContinueWatchingSection(
                            items = SetupSampleTitle.continueWatching,
                            style = continueWatchingStyle,
                            dataSourceKey = WatchProgressSource.NUVIO_SYNC,
                            useEpisodeThumbnails = useEpisodeThumbnails,
                            blurNextUp = blurNextUp,
                            sectionPadding = sectionPadding,
                            layout = continueWatchingLayout,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                }
                item(key = "row1") {
                    HomeCatalogRowSection(
                        section = SetupSampleTitle.catalogSection(firstTitle),
                        sectionPadding = sectionPadding,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                item(key = "row2") {
                    HomeCatalogRowSection(
                        section = SetupSampleTitle.secondCatalogSection(secondTitle).copy(title = secondTitle),
                        sectionPadding = sectionPadding,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            }
            if (!desktop) {
                SetupStillNavigationBar(modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

// --- Detail page -----------------------------------------------------------------------------

/** Which part of the detail page a panel is about: the page itself, or its episodes. */
internal enum class DetailPreviewFocus { Layout, Episodes }

/** A sample series detail, fed to the real `DetailMetaInfo` and ratings row. */
private fun sampleDetailMeta(description: String): MetaDetails {
    val item = SetupSampleTitle.rowItems.first()
    return MetaDetails(
        id = item.id,
        type = "series",
        name = item.name,
        imdbId = item.id,
        poster = item.poster,
        background = item.banner,
        logo = item.logo,
        description = description,
        releaseInfo = "2008-2013",
        runtime = "47 min",
        ageRating = "TV-MA",
        imdbRating = "9.5",
        externalRatings = listOf(
            MetaExternalRating(MdbListMetadataService.PROVIDER_IMDB, 9.5),
            MetaExternalRating(MdbListMetadataService.PROVIDER_TMDB, 89.0),
            MetaExternalRating(MdbListMetadataService.PROVIDER_TOMATOES, 96.0),
            MetaExternalRating(MdbListMetadataService.PROVIDER_METACRITIC, 87.0),
            MetaExternalRating(MdbListMetadataService.PROVIDER_TRAKT, 92.0),
        ),
        genres = item.genres,
    )
}

/**
 * The detail page, at a phone's width, in the chosen background mode.
 *
 * - [DetailPreviewFocus.Layout]: the hero, the real action buttons, the real meta line with the
 *   ratings a fresh install actually shows - the **IMDb** rating the catalog addon supplies, inline -
 *   or, with MDBList configured, the real multi-source ratings row; then the sections below, stacked
 *   or as one tab row (Cast | Trailers | More like this), each labelled and each holding what it
 *   really holds (the trailers are this title's, "More like this" is other titles).
 * - [DetailPreviewFocus.Episodes]: the season's episodes in the chosen card style, the first one
 *   watched and the rest unwatched - so blurring unwatched episodes and hiding their ratings visibly
 *   do something - under the real action buttons, which gain Shuffle when Random Episode is on.
 */
@Composable
internal fun SpecimenDetailPage(
    focus: DetailPreviewFocus,
    mode: MetaScreenBackgroundMode,
    tabLayout: Boolean,
    showOverallRatings: Boolean,
    mdbListActive: Boolean,
    episodeCardStyle: MetaEpisodeCardStyle,
    blurUnwatched: Boolean,
    episodeRatings: EpisodeRatingsVisibility,
    randomEpisodeAvailable: Boolean,
    cornerRadiusDp: Int,
    desktop: Boolean,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val stageW = if (desktop) 900.dp else PhoneScreenWidth
    val stageH = when {
        focus == DetailPreviewFocus.Layout && desktop -> 640.dp
        focus == DetailPreviewFocus.Layout -> 700.dp
        desktop -> 520.dp
        else -> 560.dp
    }
    val backdropUrl = SetupSampleTitle.backgroundUrl(SetupSampleTitle.featuredImdbId)
    var backdropPainter by remember(backdropUrl) { mutableStateOf<Painter?>(null) }
    val dominantEnabled = mode == MetaScreenBackgroundMode.DominantColor
    val dominant = rememberDominantBackdropColor(painter = backdropPainter, enabled = dominantEnabled)
    val seamColor = if (dominantEnabled) dominant else tokens.colors.background
    val description = stringResource(Res.string.advanced_preview_detail_description)
    val meta = remember(description) { sampleDetailMeta(description) }

    PreviewStage(
        logicalWidth = stageW,
        logicalHeight = stageH,
        modifier = modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
        cornerRadius = if (desktop) 10.dp else 18.dp,
        background = tokens.colors.background,
    ) {
        // The background layer - the same `when` the real screen runs under its content.
        when (mode) {
            MetaScreenBackgroundMode.Normal -> Box(Modifier.fillMaxSize().background(tokens.colors.background))
            MetaScreenBackgroundMode.Cinematic -> {
                AsyncImage(
                    model = backdropUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().blur(30.dp),
                )
                Box(Modifier.fillMaxSize().background(tokens.colors.background.copy(alpha = 0.92f)))
            }
            MetaScreenBackgroundMode.DominantColor -> Box(Modifier.fillMaxSize().background(dominant))
        }
        // Scrolls like the real page: the sections below the fold are laid out, not squeezed.
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            val heroHeight = when {
                focus == DetailPreviewFocus.Episodes -> if (desktop) 140.dp else 130.dp
                desktop -> 190.dp
                else -> 230.dp
            }
            Box(
                modifier = Modifier.fillMaxWidth().height(heroHeight).background(tokens.colors.surfaceCard),
                contentAlignment = if (desktop) Alignment.BottomStart else Alignment.BottomCenter,
            ) {
                AsyncImage(
                    model = backdropUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onSuccess = { backdropPainter = it.painter },
                    modifier = Modifier.fillMaxSize(),
                )
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, seamColor))))
                AsyncImage(
                    model = SetupSampleTitle.logoUrl(SetupSampleTitle.featuredImdbId),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .padding(start = if (desktop) 32.dp else 0.dp, bottom = 12.dp)
                        .height(if (focus == DetailPreviewFocus.Episodes) 40.dp else 60.dp)
                        .widthIn(max = 240.dp),
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DetailActionButtons(
                    playLabel = stringResource(Res.string.advanced_preview_detail_resume),
                    secondaryActions = buildList {
                        add(DetailSecondaryAction(label = stringResource(Res.string.hero_add_to_library), icon = Icons.Rounded.Add))
                        add(DetailSecondaryAction(label = stringResource(Res.string.hero_mark_watched), icon = Icons.Rounded.CheckCircle))
                        if (randomEpisodeAvailable) {
                            add(DetailSecondaryAction(label = stringResource(Res.string.advanced_preview_shuffle), icon = Icons.Default.Shuffle))
                        }
                    },
                )
                if (focus == DetailPreviewFocus.Layout) {
                    DetailMetaInfo(meta = meta, showOverallRatings = showOverallRatings, isMdbListActive = mdbListActive)
                    if (showOverallRatings && !mdbListActive) {
                        // What MDBList would add, labelled as such and dimmed - it is not on this page yet.
                        Column(Modifier.alpha(0.5f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(Res.string.advanced_preview_ratings_with_mdblist),
                                style = MaterialTheme.typography.labelSmall,
                                color = tokens.colors.textMuted,
                            )
                            DetailRatingsRow(ratings = meta.externalRatings)
                        }
                    }
                    DetailSectionsPreview(tabLayout = tabLayout, cornerRadiusDp = cornerRadiusDp)
                } else {
                    DetailEpisodesPreview(
                        style = episodeCardStyle,
                        blurUnwatched = blurUnwatched,
                        ratings = episodeRatings,
                        cornerRadiusDp = cornerRadiusDp,
                    )
                }
            }
        }
    }
}

/** Cast | Trailers | More like this, stacked or as one tab row (`TabbedSectionGroup`'s treatment). */
@Composable
private fun DetailSectionsPreview(tabLayout: Boolean, cornerRadiusDp: Int) {
    val tokens = MaterialTheme.nuvio
    val titles = listOf(
        stringResource(Res.string.settings_meta_cast),
        stringResource(Res.string.settings_meta_trailers),
        stringResource(Res.string.details_more_like_this),
    )
    var selected by remember { mutableStateOf(0) }
    AnimatedContent(
        targetState = tabLayout,
        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
        label = "detail_sections",
    ) { tabbed ->
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (tabbed) {
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
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
                            color = if (index == selected) tokens.colors.textPrimary else tokens.colors.textPrimary.copy(alpha = 0.55f),
                            maxLines = 1,
                            modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { selected = index },
                        )
                    }
                }
                when (selected) {
                    1 -> TrailerRail(cornerRadiusDp)
                    2 -> MoreLikeThisRail(cornerRadiusDp)
                    else -> CastRail()
                }
            } else {
                SectionHeading(titles[0])
                CastRail()
                SectionHeading(titles[1])
                TrailerRail(cornerRadiusDp)
                SectionHeading(titles[2])
                MoreLikeThisRail(cornerRadiusDp)
            }
        }
    }
}

@Composable
private fun SectionHeading(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.nuvio.colors.textPrimary,
        maxLines = 1,
    )
}

/** `DetailCastSection`'s no-photo state: initials in a `surfaceVariant` circle, the name below. */
@Composable
private fun CastRail() {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SetupSampleTitle.castNames.take(5).forEach { name ->
            Column(Modifier.width(56.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        SetupSampleTitle.initials(name),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.nuvio.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** This title's own trailers: 16:9 frames with the play mark and the video's name, as `TrailerCard` draws. */
@Composable
private fun TrailerRail(cornerRadiusDp: Int) {
    val labels = listOf(
        stringResource(Res.string.advanced_preview_trailer_official),
        stringResource(Res.string.advanced_preview_trailer_teaser),
        stringResource(Res.string.advanced_preview_trailer_final_season),
    )
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        labels.forEachIndexed { index, label ->
            Column(Modifier.width(150.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(cornerRadiusDp.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    val still = SetupSampleTitle.episodes.getOrNull(index)
                    AsyncImage(
                        model = still?.fallbackStillUrl ?: SetupSampleTitle.backgroundUrl(SetupSampleTitle.featuredImdbId),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = when (index) {
                            0 -> Alignment.Center
                            1 -> Alignment.CenterStart
                            else -> Alignment.CenterEnd
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.2f)))
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                }
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.nuvio.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Other titles, as posters with their names - recommendations, not this title's videos. */
@Composable
private fun MoreLikeThisRail(cornerRadiusDp: Int) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SetupSampleTitle.rowItems.drop(1).take(4).forEach { item ->
            Column(Modifier.width(78.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AsyncImage(
                    model = item.poster,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(0.675f).clip(RoundedCornerShape(cornerRadiusDp.dp))
                        .background(MaterialTheme.nuvio.colors.surfaceCard),
                )
                Text(item.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.nuvio.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** One season: episode 14 watched, 15 and 16 not - so blur and rating visibility have something to act on. */
@Composable
private fun DetailEpisodesPreview(
    style: MetaEpisodeCardStyle,
    blurUnwatched: Boolean,
    ratings: EpisodeRatingsVisibility,
    cornerRadiusDp: Int,
) {
    val tokens = MaterialTheme.nuvio
    val episodeRatings = listOf("10.0", "9.2", "9.9")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(Res.string.advanced_preview_detail_season, 5),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = tokens.colors.textPrimary,
        )
        val rows = SetupSampleTitle.episodes.mapIndexed { index, episode ->
            val watched = index == 0
            val showRating = when (ratings) {
                EpisodeRatingsVisibility.SHOW_ALL -> true
                EpisodeRatingsVisibility.HIDE_UNWATCHED_EPISODES -> watched
                EpisodeRatingsVisibility.HIDE_EPISODES -> false
            }
            EpisodeRow(episode, watched, blurUnwatched && !watched, episodeRatings[index].takeIf { showRating })
        }
        when (style) {
            MetaEpisodeCardStyle.Horizontal -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.forEach { row ->
                    Column(Modifier.width(220.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        EpisodeStill(row, cornerRadiusDp, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                        EpisodeCaption(row, lines = 2)
                    }
                }
            }
            MetaEpisodeCardStyle.List -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        EpisodeStill(row, cornerRadiusDp, Modifier.width(132.dp).aspectRatio(16f / 9f))
                        Box(Modifier.weight(1f)) { EpisodeCaption(row, lines = 2) }
                    }
                }
            }
        }
    }
}

private data class EpisodeRow(
    val episode: SetupSampleTitle.SampleEpisode,
    val watched: Boolean,
    val blurred: Boolean,
    val rating: String?,
)

@Composable
private fun EpisodeStill(row: EpisodeRow, cornerRadiusDp: Int, modifier: Modifier) {
    var failed by remember(row.episode.stillUrl) { mutableStateOf(false) }
    Box(modifier.clip(RoundedCornerShape(cornerRadiusDp.dp)).background(MaterialTheme.nuvio.colors.surfaceCard)) {
        AsyncImage(
            model = if (failed) row.episode.fallbackStillUrl else row.episode.stillUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onError = { failed = true },
            // 18 dp, matching `DetailSeriesContent`.
            modifier = Modifier.fillMaxSize().then(if (row.blurred) Modifier.blur(18.dp) else Modifier),
        )
        if (row.watched) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp).background(MaterialTheme.nuvio.colors.accent))
        }
    }
}

@Composable
private fun EpisodeCaption(row: EpisodeRow, lines: Int) {
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "${row.episode.episodeNumber}. ${row.episode.title}",
                style = MaterialTheme.typography.labelLarge,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            row.rating?.let { rating ->
                Text(
                    text = "IMDb $rating",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFF5C518),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
        Text(
            text = row.episode.overview,
            style = MaterialTheme.typography.labelSmall,
            color = tokens.colors.textSecondary,
            maxLines = lines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// --- Enhanced metadata ----------------------------------------------------------------------

/**
 * The top of a detail page with and without enhanced metadata (TMDB), so the difference is the
 * picture: off, the page has what the catalog addon sends - its backdrop, the title as text, the
 * plot and cast as a line of names; on, it gains the title logo, cast faces, this title's trailers,
 * full episode details and "More like this". Sections enrichment adds carry an accent marker, and
 * the ones it would add are shown as empty, labelled slots while it is off.
 *
 * MDBList is not here on purpose: it enriches *ratings*, and is explained on its own row.
 */
@Composable
internal fun SpecimenMetadata(enriched: Boolean, desktop: Boolean, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    val item = SetupSampleTitle.rowItems.first()
    val stageW = if (desktop) 720.dp else 420.dp
    val stageH = if (desktop) 540.dp else 600.dp
    PreviewStage(
        logicalWidth = stageW,
        logicalHeight = stageH,
        modifier = modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
        cornerRadius = 16.dp,
        background = tokens.colors.background,
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().height(190.dp).background(tokens.colors.surfaceCard), contentAlignment = Alignment.BottomStart) {
                AsyncImage(
                    model = item.banner,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, tokens.colors.background))))
                AnimatedContent(targetState = enriched, label = "meta_title") { on ->
                    if (on) {
                        AsyncImage(
                            model = item.logo,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.BottomStart,
                            modifier = Modifier.padding(16.dp).height(56.dp).widthIn(max = 230.dp),
                        )
                    } else {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = tokens.colors.textPrimary,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = item.description.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                EnrichedSection(stringResource(Res.string.settings_meta_cast), enriched) {
                    if (enriched) {
                        CastRail()
                    } else {
                        Text(
                            text = SetupSampleTitle.castNames.take(4).joinToString(", "),
                            style = MaterialTheme.typography.labelMedium,
                            color = tokens.colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                EnrichedSection(stringResource(Res.string.settings_meta_trailers), enriched, missingWhenOff = true) {
                    TrailerRail(cornerRadiusDp = 10)
                }
                EnrichedSection(stringResource(Res.string.details_more_like_this), enriched, missingWhenOff = true) {
                    MoreLikeThisRail(cornerRadiusDp = 8)
                }
            }
        }
        Text(
            text = stringResource(if (enriched) Res.string.advanced_preview_meta_enhanced else Res.string.advanced_preview_meta_basic),
            style = MaterialTheme.typography.labelMedium,
            color = if (enriched) tokens.colors.accent else tokens.colors.textMuted,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * One section of the metadata preview. With enrichment its heading carries the accent marker; without
 * it, a section enrichment would supply is drawn as an empty, labelled slot.
 */
@Composable
private fun EnrichedSection(
    title: String,
    enriched: Boolean,
    missingWhenOff: Boolean = false,
    content: @Composable () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier.width(3.dp).height(14.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (enriched) tokens.colors.accent else Color.Transparent),
            )
            SectionHeading(title)
        }
        if (enriched || !missingWhenOff) {
            content()
        } else {
            Box(
                Modifier.fillMaxWidth().height(56.dp)
                    .border(1.dp, tokens.colors.borderSubtle, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.advanced_preview_meta_missing),
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.colors.textMuted,
                )
            }
        }
    }
}
