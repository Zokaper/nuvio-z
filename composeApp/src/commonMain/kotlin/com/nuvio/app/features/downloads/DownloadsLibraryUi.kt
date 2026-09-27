package com.nuvio.app.features.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioDesktopVerticalScrollbar
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioDesktopDragScroll
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.watchedItemKeys
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.buildWatchProgressKey
import com.nuvio.app.navigation.LocalNativeNavigationBarHidden
import com.nuvio.app.navigation.LocalUseNativeNavigation
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The downloaded library (Phase 9, library pass): "On this device" as backdrop cards with the
 * title's logo, and a show's own page as a title page - hero, one season at a time, compact
 * episode rows with their stills, overviews and watch state - so a 24-episode season reads as
 * a season rather than as 24 identical cards.
 *
 * Stateless: data in, callbacks out, so the render harness draws exactly what the app draws.
 * Everything shown offline comes from the download itself and its [DownloadTitleMetadata].
 */

// --- watch state -------------------------------------------------------------------------------

/** How far the active profile is through each download, from its watch progress and watched marks. */
@Composable
internal fun rememberDownloadWatchStates(): (DownloadItem) -> DownloadWatchState {
    val progress by remember {
        WatchProgressRepository.ensureLoaded()
        WatchProgressRepository.uiState
    }.collectAsStateWithLifecycle()
    val watched by remember {
        WatchedRepository.ensureLoaded()
        WatchedRepository.uiState
    }.collectAsStateWithLifecycle()
    return remember(progress, watched) {
        val byKey = progress.byProgressKey
        val watchedKeys = watched.watchedKeys
        val states: (DownloadItem) -> DownloadWatchState = { item ->
            val entry = byKey[buildWatchProgressKey(item.parentMetaId, item.seasonNumber, item.episodeNumber)]
            val marked = watchedItemKeys(item.parentMetaType, item.parentMetaId, item.seasonNumber, item.episodeNumber)
                .any(watchedKeys::contains)
            DownloadWatchState(
                fraction = entry?.progressFraction,
                watched = marked || entry?.isEffectivelyCompleted == true,
                updatedAtEpochMs = entry?.lastUpdatedEpochMs ?: 0L,
                remainingMs = entry?.takeIf { it.durationMs > 0L }?.let { (it.durationMs - it.lastPositionMs).coerceAtLeast(0L) },
            )
        }
        states
    }
}

// --- On this device ----------------------------------------------------------------------------

/**
 * "On this device": one backdrop card per title, [columns] to a row. The card opens a show's page
 * (a film plays); its round button plays what is next.
 */
internal fun LazyListScope.downloadLibrarySection(
    titles: List<DownloadLibraryTitle>,
    metadata: Map<String, DownloadTitleMetadata>,
    watch: (DownloadItem) -> DownloadWatchState,
    columns: Int,
    width: Modifier,
    onOpenShow: (DownloadLibraryTitle) -> Unit,
    onPlay: (DownloadItem) -> Unit,
    onDelete: (DownloadLibraryTitle) -> Unit,
) {
    titles.chunked(columns.coerceAtLeast(1)).forEach { row ->
        item(key = "library-${row.first().parentMetaId}") {
            Row(width, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { title ->
                    val nextUp = DownloadLibrary.nextUp(title.completed, watch)
                    DownloadLibraryCard(
                        title = title,
                        metadata = metadata[title.parentMetaId],
                        nextUp = nextUp,
                        onOpen = {
                            if (title.isSeries) onOpenShow(title) else onPlay(nextUp?.item ?: title.representative)
                        },
                        onPlay = { onPlay(nextUp?.item ?: title.representative) },
                        onDelete = { onDelete(title) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
internal fun DownloadLibraryCard(
    title: DownloadLibraryTitle,
    metadata: DownloadTitleMetadata?,
    nextUp: DownloadNextUp?,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(shape)
            .background(tokens.colors.surfaceElevated)
            .clickable(onClick = onOpen),
    ) {
        val art = title.background ?: metadata?.background ?: title.poster
        if (art != null) {
            AsyncImage(model = art, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.10f),
                        0.45f to Color.Black.copy(alpha = 0.15f),
                        1f to Color.Black.copy(alpha = 0.88f),
                    ),
                ),
        )

        LibraryOverflow(
            onDelete = onDelete,
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
        )

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 14.dp, end = 12.dp, bottom = if (nextUp?.kind == DownloadNextUpKind.RESUME) 16.dp else 12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TitleLogo(
                    logo = title.logo ?: metadata?.logo,
                    title = title.title,
                    maxHeight = 38.dp,
                    maxWidthFraction = 0.78f,
                    textStyleLarge = false,
                )
                Text(
                    text = libraryCardLine(title, metadata),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (nextUp != null && title.isSeries && nextUp.kind != DownloadNextUpKind.START) {
                    Text(
                        text = nextUpLabel(nextUp),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            PlayDisc(onClick = onPlay, size = 44.dp)
        }

        if (nextUp?.kind == DownloadNextUpKind.RESUME && nextUp.fraction != null) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color.White.copy(alpha = 0.22f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(nextUp.fraction.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(tokens.colors.accent),
                )
            }
        }
    }
}

@Composable
private fun libraryCardLine(title: DownloadLibraryTitle, metadata: DownloadTitleMetadata?): String {
    val size = formatDownloadBytes(title.bytesOnDisk)
    return if (title.isSeries) {
        val seasons = title.seasons.size
        listOfNotNull(
            if (seasons > 1) pluralStringResource(Res.plurals.download_flow_seasons_selected_seasons, seasons, seasons) else null,
            pluralStringResource(Res.plurals.download_library_episodes, title.completed.size, title.completed.size),
            size,
        ).joinToString(" · ")
    } else {
        listOfNotNull(metadata?.releaseInfo?.take(4), metadata?.runtime, size).joinToString(" · ")
    }
}

@Composable
private fun nextUpLabel(nextUp: DownloadNextUp): String {
    val code = episodeCode(nextUp.item) ?: return stringResource(
        if (nextUp.kind == DownloadNextUpKind.RESUME) Res.string.action_resume else Res.string.action_play,
    )
    return when (nextUp.kind) {
        DownloadNextUpKind.RESUME -> stringResource(Res.string.download_library_resume, code)
        else -> stringResource(Res.string.download_library_play, code)
    }
}

@Composable
private fun episodeCode(item: DownloadItem): String? {
    val season = item.seasonNumber ?: return null
    val episode = item.episodeNumber ?: return null
    return stringResource(Res.string.download_library_episode_code, season, episode)
}

/** The title's logo when it has one, else its name - never both. */
@Composable
private fun TitleLogo(
    logo: String?,
    title: String,
    maxHeight: Dp,
    maxWidthFraction: Float,
    textStyleLarge: Boolean,
    modifier: Modifier = Modifier,
) {
    var failed by remember(logo) { mutableStateOf(false) }
    if (!logo.isNullOrBlank() && !failed) {
        AsyncImage(
            model = logo,
            contentDescription = title,
            modifier = modifier.fillMaxWidth(maxWidthFraction).heightIn(max = maxHeight),
            contentScale = ContentScale.Fit,
            alignment = Alignment.BottomStart,
            onError = { failed = true },
        )
    } else {
        Text(
            text = title,
            modifier = modifier,
            style = if (textStyleLarge) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PlayDisc(onClick: () -> Unit, size: Dp) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.White,
        contentColor = Color.Black,
        modifier = Modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(Res.string.action_play), modifier = Modifier.size(size * 0.58f))
        }
    }
}

@Composable
private fun LibraryOverflow(onDelete: () -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.38f))
                .clickable { expanded = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.MoreVert,
                contentDescription = stringResource(Res.string.download_library_more_options),
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.downloads_delete_title)) },
                leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}

// --- a show's page -----------------------------------------------------------------------------

/** Past this the show page's content stops growing and centres. */
private val ShowContentMaxWidth: Dp = 1180.dp

/**
 * One downloaded show: a hero (backdrop, logo, year · rating · genres, what is on the device, the
 * synopsis, Resume/Play), then its seasons as tabs and the selected season's episodes. From
 * [WideFrom] the episodes run two to a row.
 */
@Composable
internal fun DownloadedShowPage(
    episodes: List<DownloadItem>,
    metadata: DownloadTitleMetadata?,
    watch: (DownloadItem) -> DownloadWatchState,
    nowEpochMs: Long,
    selectedSeason: Int?,
    onSelectSeason: (Int) -> Unit,
    onBack: (() -> Unit)?,
    onPlay: (DownloadItem) -> Unit,
    onOpenDetail: (DownloadItem) -> Unit,
    onDeleteTitle: () -> Unit,
    onDeleteSeason: (Int) -> Unit,
    onDeleteWatched: (season: Int, List<DownloadItem>) -> Unit,
    onDeleteEpisode: (DownloadItem) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val tokens = MaterialTheme.nuvio
    val first = episodes.firstOrNull()
    val completed = remember(episodes) { episodes.filter { it.status == DownloadStatus.Completed } }
    val seasons = remember(episodes) { episodes.mapNotNull { it.seasonNumber }.distinct().sortedWith(SeasonOrder) }
    val nextUp = remember(completed, watch) { DownloadLibrary.nextUp(completed, watch) }
    val season = selectedSeason?.takeIf { it in seasons } ?: nextUp?.item?.seasonNumber ?: seasons.firstOrNull()
    val seasonEpisodes = remember(episodes, season) {
        with(DownloadLibrary) { episodes.filter { it.seasonNumber == season }.sortedForLibrary() }
    }
    val summary = remember(seasonEpisodes, watch) { DownloadLibrary.seasonSummary(seasonEpisodes, watch) }
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val nativeBack = LocalUseNativeNavigation.current && !LocalNativeNavigationBarHidden.current

    BoxWithConstraints(modifier.fillMaxSize().background(tokens.colors.background)) {
        val wide = maxWidth >= WideFrom
        val gutter = if (wide) 40.dp else tokens.spacing.screenHorizontal
        // Centred and capped, the gutter inside the cap so every row starts on the same edge.
        val contentWidth = Modifier
            .fillMaxWidth()
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = ShowContentMaxWidth + gutter * 2)
            .fillMaxWidth()
            .padding(horizontal = gutter)
        val columns = if (maxWidth >= 1000.dp) 2 else 1

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = nuvioSafeBottomPadding(tokens.spacing.screenBottom)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (first == null) {
                item(key = "show-empty") {
                    Text(
                        text = stringResource(Res.string.downloads_empty_episodes),
                        modifier = Modifier.padding(top = statusBarTop + 96.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = tokens.colors.textMuted,
                    )
                }
                return@LazyColumn
            }

            item(key = "show-hero") {
                ShowHero(
                    first = first,
                    completed = completed,
                    unfinishedCount = episodes.size - completed.size,
                    seasonCount = seasons.size,
                    metadata = metadata,
                    nextUp = nextUp,
                    wide = wide,
                    availableWidth = maxWidth,
                    contentWidth = contentWidth,
                    onPlay = onPlay,
                    onDeleteTitle = onDeleteTitle,
                )
            }

            if (seasons.size > 1) {
                item(key = "show-seasons") {
                    SeasonTabs(
                        seasons = seasons,
                        selected = season,
                        onSelect = onSelectSeason,
                        gutter = gutter,
                        modifier = Modifier.padding(top = 28.dp),
                    )
                }
            }

            if (season != null) {
                item(key = "show-season-header-$season") {
                    SeasonHeader(
                        season = season,
                        showSeasonName = seasons.size <= 1,
                        summary = summary,
                        onDeleteSeason = { onDeleteSeason(season) },
                        onDeleteWatched = { onDeleteWatched(season, summary.watched) },
                        modifier = contentWidth.padding(top = if (seasons.size > 1) 14.dp else 28.dp, bottom = 6.dp),
                    )
                }
            }

            seasonEpisodes.chunked(columns).forEach { row ->
                item(key = "show-ep-${row.first().id}") {
                    Row(contentWidth, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        row.forEach { item ->
                            DownloadedEpisodeRow(
                                item = item,
                                metadata = metadata?.episode(item.seasonNumber, item.episodeNumber),
                                watch = watch(item),
                                nowEpochMs = nowEpochMs,
                                wide = wide,
                                onPlay = { onPlay(item) },
                                onOpenDetail = { onOpenDetail(item) },
                                onDelete = { onDeleteEpisode(item) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            // The end of a long season: the next one is one tap away, not a scroll back to the tabs.
            val nextSeason = seasons.getOrNull(seasons.indexOf(season) + 1)
            if (nextSeason != null) {
                item(key = "show-next-season") {
                    Box(contentWidth.padding(top = 18.dp)) {
                        DownloadsTonalButton(
                            text = stringResource(Res.string.download_library_next_season, seasonName(nextSeason)),
                            onClick = { onSelectSeason(nextSeason) },
                        )
                    }
                }
            }
        }

        NuvioDesktopVerticalScrollbar(
            state = listState,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 8.dp, horizontal = 4.dp),
        )

        if (first != null) {
            ShowTopBar(
                title = first.title,
                listState = listState,
                onBack = onBack.takeUnless { nativeBack },
                statusBarTop = statusBarTop,
            )
        }
    }
}

private val WideFrom: Dp = 720.dp

@Composable
private fun ShowHero(
    first: DownloadItem,
    completed: List<DownloadItem>,
    unfinishedCount: Int,
    seasonCount: Int,
    metadata: DownloadTitleMetadata?,
    nextUp: DownloadNextUp?,
    wide: Boolean,
    availableWidth: Dp,
    contentWidth: Modifier,
    onPlay: (DownloadItem) -> Unit,
    onDeleteTitle: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val background = tokens.colors.background
    val imageHeight = if (wide) (availableWidth * 0.40f).coerceIn(320.dp, 540.dp) else availableWidth * 0.62f
    // Where the text starts: over the lower part of the backdrop on a wide window, under it on a phone.
    val textTop = if (wide) imageHeight * 0.42f else imageHeight - 64.dp
    val art = first.background ?: metadata?.background ?: first.poster

    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(imageHeight)) {
            if (art != null) {
                AsyncImage(
                    model = art,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                )
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to background.copy(alpha = 0.35f),
                        0.22f to Color.Transparent,
                        0.55f to background.copy(alpha = 0.25f),
                        1f to background,
                    ),
                ),
            )
            if (wide) {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(
                            0f to background.copy(alpha = 0.92f),
                            0.45f to background.copy(alpha = 0.45f),
                            0.75f to Color.Transparent,
                        ),
                    ),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = textTop),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(contentWidth, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TitleLogo(
                    logo = first.logo ?: metadata?.logo,
                    title = first.title,
                    maxHeight = if (wide) 96.dp else 64.dp,
                    maxWidthFraction = if (wide) 0.42f else 0.72f,
                    textStyleLarge = true,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                ShowFactsLine(metadata)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Rounded.DownloadDone, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(16.dp))
                    Text(
                        text = listOfNotNull(
                            if (seasonCount > 1) pluralStringResource(Res.plurals.download_flow_seasons_selected_seasons, seasonCount, seasonCount) else null,
                            pluralStringResource(Res.plurals.download_library_episodes, completed.size, completed.size),
                            stringResource(Res.string.download_library_on_device, formatDownloadBytes(completed.sumOf { it.totalBytes ?: it.downloadedBytes })),
                            if (unfinishedCount > 0) pluralStringResource(Res.plurals.download_library_more_coming, unfinishedCount, unfinishedCount) else null,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelLarge,
                        color = tokens.colors.textMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                metadata?.description?.let { description ->
                    Text(
                        text = description,
                        modifier = Modifier.widthIn(max = 620.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (nextUp != null) {
                        HeroPlayButton(
                            nextUp = nextUp,
                            onClick = { onPlay(nextUp.item) },
                            modifier = if (wide) Modifier.widthIn(min = 220.dp) else Modifier.weight(1f),
                        )
                    }
                    Surface(
                        onClick = onDeleteTitle,
                        shape = CircleShape,
                        color = tokens.colors.surfaceElevated,
                        contentColor = tokens.colors.textPrimary,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Delete, contentDescription = stringResource(Res.string.downloads_delete_title), modifier = Modifier.size(22.dp))
                        }
                    }
                }
                if (nextUp?.kind == DownloadNextUpKind.RESUME) {
                    ResumeLine(nextUp, wide)
                }
            }
        }
    }
}

/** "2009 · TV-PG · ★ 8.5 · Comedy, Family", whichever of those the snapshot has. */
@Composable
private fun ShowFactsLine(metadata: DownloadTitleMetadata?) {
    val tokens = MaterialTheme.nuvio
    metadata ?: return
    val year = metadata.releaseInfo
    val genres = metadata.genres.takeIf { it.isNotEmpty() }?.joinToString(", ")
    if (year == null && metadata.ageRating == null && metadata.imdbRating == null && genres == null) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val style = MaterialTheme.typography.bodyMedium
        year?.let { Text(it, style = style, color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold) }
        metadata.ageRating?.let { rating ->
            Text(
                text = rating,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(tokens.colors.surfaceElevated)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
                style = MaterialTheme.typography.labelMedium,
                color = tokens.colors.textSecondary,
            )
        }
        metadata.imdbRating?.let { rating ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Icon(Icons.Rounded.Star, contentDescription = null, tint = Color(0xFFF5C518), modifier = Modifier.size(15.dp))
                Text(rating, style = style, color = tokens.colors.textPrimary)
            }
        }
        genres?.let {
            Text(it, style = style, color = tokens.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HeroPlayButton(nextUp: DownloadNextUp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(40.dp),
        color = MaterialTheme.colorScheme.onBackground,
        contentColor = MaterialTheme.colorScheme.background,
        modifier = modifier.height(48.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 22.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = nextUpLabel(nextUp),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

/** Under Resume: how far in, and what is left. */
@Composable
private fun ResumeLine(nextUp: DownloadNextUp, wide: Boolean) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = if (wide) Modifier.width(280.dp) else Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.weight(1f)) { ThinProgress(nextUp.fraction ?: 0f) }
        nextUp.remainingMs?.let { remaining ->
            Text(
                text = stringResource(Res.string.download_library_minutes_left, ((remaining + 59_999L) / 60_000L).toInt()),
                style = MaterialTheme.typography.labelMedium,
                color = tokens.colors.textMuted,
            )
        }
    }
}

@Composable
private fun seasonName(season: Int): String =
    if (season == 0) stringResource(Res.string.episodes_specials) else stringResource(Res.string.episodes_season, season)

@Composable
private fun SeasonTabs(
    seasons: List<Int>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    gutter: Dp,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val state = rememberLazyListState(initialFirstVisibleItemIndex = seasons.indexOf(selected).coerceAtLeast(0))
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Starts on the content column's edge, and scrolls out to the screen's.
        val inset = gutter + ((maxWidth - ShowContentMaxWidth - gutter * 2) / 2).coerceAtLeast(0.dp)
        LazyRow(
            state = state,
            modifier = Modifier.fillMaxWidth().nuvioDesktopDragScroll(state),
            contentPadding = PaddingValues(horizontal = inset),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(seasons, key = { it }) { season ->
                val isSelected = season == selected
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (isSelected) tokens.colors.textPrimary else tokens.colors.surfaceElevated)
                        .clickable { onSelect(season) }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = seasonName(season),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isSelected) tokens.colors.background else tokens.colors.textSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SeasonHeader(
    season: Int,
    showSeasonName: Boolean,
    summary: DownloadSeasonSummary,
    onDeleteSeason: () -> Unit,
    onDeleteWatched: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    var expanded by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (showSeasonName) {
                Text(
                    text = seasonName(season),
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = listOfNotNull(
                    pluralStringResource(Res.plurals.download_library_episodes, summary.episodeCount, summary.episodeCount),
                    formatDownloadBytes(summary.bytes),
                    summary.watched.size.takeIf { it > 0 }?.let { stringResource(Res.string.download_library_watched_count, it) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelLarge,
                color = tokens.colors.textMuted,
            )
        }
        Box {
            IconButton(onClick = { expanded = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(Res.string.download_library_more_options), tint = tokens.colors.textSecondary)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (summary.watched.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.download_library_delete_watched, summary.watched.size)) },
                        leadingIcon = { Icon(Icons.Rounded.Check, contentDescription = null) },
                        onClick = {
                            expanded = false
                            onDeleteWatched()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.download_library_delete_season)) },
                    leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onDeleteSeason()
                    },
                )
            }
        }
    }
}

/**
 * One episode: its still (watched tick, or how far in), "7. Phil on Wire", runtime and size, and
 * the overview. Tapping plays it; one that is still downloading opens its detail instead.
 */
@Composable
internal fun DownloadedEpisodeRow(
    item: DownloadItem,
    metadata: DownloadEpisodeMetadata?,
    watch: DownloadWatchState,
    nowEpochMs: Long,
    wide: Boolean,
    onPlay: () -> Unit,
    onOpenDetail: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val finished = item.status == DownloadStatus.Completed
    val presentation = if (finished) null else DownloadPresenter.item(item, nowEpochMs)
    val name = item.episodeTitle?.trim()?.takeIf { it.isNotBlank() } ?: metadata?.title
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = if (finished) onPlay else onOpenDetail)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        EpisodeStill(
            url = metadata?.thumbnail ?: item.episodeThumbnail ?: item.background ?: item.poster,
            title = name ?: item.title,
            width = if (wide) 176.dp else 116.dp,
            watch = watch,
            downloadingPercent = presentation?.progressPercent,
            finished = finished,
        )
        Column(Modifier.weight(1f).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = when {
                    item.episodeNumber != null && name != null -> stringResource(Res.string.download_library_episode_title, item.episodeNumber, name)
                    item.episodeNumber != null -> stringResource(Res.string.download_library_episode_number, item.episodeNumber)
                    else -> name ?: item.title
                },
                style = MaterialTheme.typography.titleSmall,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (presentation != null) {
                    presentation.plainText()
                } else {
                    listOfNotNull(
                        when {
                            watch.isInProgress && watch.remainingMs != null ->
                                stringResource(Res.string.download_library_minutes_left, ((watch.remainingMs + 59_999L) / 60_000L).toInt())
                            metadata?.runtimeMinutes != null -> stringResource(Res.string.download_library_runtime, metadata.runtimeMinutes)
                            else -> null
                        },
                        formatDownloadBytes(item.totalBytes ?: item.downloadedBytes),
                    ).joinToString(" · ")
                },
                style = MaterialTheme.typography.labelMedium,
                color = tokens.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            metadata?.overview?.let { overview ->
                Text(
                    text = overview,
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textSecondary,
                    maxLines = if (wide) 3 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (finished) {
            EpisodeOverflow(onDelete = onDelete)
        }
    }
}

@Composable
private fun EpisodeStill(
    url: String?,
    title: String,
    width: Dp,
    watch: DownloadWatchState,
    downloadingPercent: Int?,
    finished: Boolean,
) {
    val tokens = MaterialTheme.nuvio
    Box(Modifier.width(width).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
        DownloadStill(url = url, title = title, width = width, cornerRadius = 0.dp)
        when {
            !finished -> {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                    Text(
                        text = downloadingPercent?.let { "$it%" } ?: "…",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            watch.watched -> {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.62f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = stringResource(Res.string.download_flow_season_watched), tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
            watch.isInProgress -> {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.White.copy(alpha = 0.25f)),
                ) {
                    Box(Modifier.fillMaxWidth(watch.fraction ?: 0f).fillMaxHeight().background(tokens.colors.accent))
                }
            }
        }
    }
}

@Composable
private fun EpisodeOverflow(onDelete: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(Res.string.download_library_more_options), tint = tokens.colors.textMuted, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.download_library_delete_episode)) },
                leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}

/**
 * The floating back control, and once the hero has scrolled away a solid bar with the show's
 * name - the title page's pattern, so the page never loses its way back.
 */
@Composable
private fun ShowTopBar(
    title: String,
    listState: LazyListState,
    onBack: (() -> Unit)?,
    statusBarTop: Dp,
) {
    val tokens = MaterialTheme.nuvio
    val collapsed by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    Box(
        Modifier
            .fillMaxWidth()
            .background(if (collapsed) tokens.colors.background else Color.Transparent)
            .padding(top = statusBarTop + 6.dp, bottom = 6.dp, start = 8.dp, end = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onBack != null) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (collapsed) Color.Transparent else Color.Black.copy(alpha = 0.42f))
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(Res.string.action_back),
                        tint = Color.White,
                    )
                }
            }
            if (collapsed) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
