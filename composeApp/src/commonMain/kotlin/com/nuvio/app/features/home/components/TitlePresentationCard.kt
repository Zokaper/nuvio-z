package com.nuvio.app.features.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioCardDepthSurface
import com.nuvio.app.core.ui.NuvioProgressBar
import com.nuvio.app.core.ui.PosterLandscapeAspectRatio
import com.nuvio.app.core.ui.desktopCatalogShelfPosterBaseWidthDp
import com.nuvio.app.core.ui.nuvioCardDepth
import com.nuvio.app.core.ui.rememberPosterCardStyleUiState
import com.nuvio.app.features.cloud.cloudLibraryDisplayArtworkUrl
import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle

/**
 * Presentation-only title card. Domain adornments are slots so social activity does not pretend
 * to be a ContinueWatchingItem and Continue Watching can retain its own watched/next-up behavior.
 */
@Composable
internal fun TitlePresentationCard(
    item: TitlePresentation,
    style: ContinueWatchingSectionStyle,
    useEpisodeThumbnails: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    layout: ContinueWatchingLayout? = null,
    cardMetrics: ContinueWatchingLandscapeCardMetrics? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val posterCardStyle = rememberPosterCardStyleUiState()
    val resolvedMetrics = cardMetrics ?: remember(posterCardStyle.widthDp, posterCardStyle.cornerRadiusDp) {
        val basePosterWidthDp = desktopCatalogShelfPosterBaseWidthDp(posterCardStyle.widthDp)
        continueWatchingLandscapeCardMetrics(
            basePosterWidthDp = basePosterWidthDp,
            cornerRadiusDp = posterCardStyle.cornerRadiusDp,
        )
    }
    val resolvedLayout = layout ?: rememberContinueWatchingLayout(1024f)
    val artwork = item.artwork(style, useEpisodeThumbnails)
    val episodeLine = if (item.episode != null) {
        "S${item.season ?: 1} E${item.episode}"
    } else {
        null
    }

    when (style) {
        ContinueWatchingSectionStyle.Card -> TitleCardLandscape(
            item = item,
            artworkUrl = artwork,
            episodeLine = episodeLine,
            cardMetrics = resolvedMetrics,
            onClick = onClick,
            modifier = modifier,
            leading = leading,
            trailing = trailing,
        )
        ContinueWatchingSectionStyle.Wide -> TitleCardWide(
            item = item,
            artworkUrl = artwork,
            episodeLine = episodeLine,
            layout = resolvedLayout,
            onClick = onClick,
            modifier = modifier,
            leading = leading,
            trailing = trailing,
        )
        ContinueWatchingSectionStyle.Poster -> TitleCardPoster(
            item = item,
            artworkUrl = artwork,
            episodeLine = episodeLine,
            layout = resolvedLayout,
            onClick = onClick,
            modifier = modifier,
            leading = leading,
            trailing = trailing,
        )
    }
}

@Composable
private fun TitleCardLandscape(
    item: TitlePresentation,
    artworkUrl: String?,
    episodeLine: String?,
    cardMetrics: ContinueWatchingLandscapeCardMetrics,
    onClick: () -> Unit,
    modifier: Modifier,
    leading: @Composable (() -> Unit)?,
    trailing: @Composable (() -> Unit)?,
) {
    val backgroundColor = MaterialTheme.colorScheme.background
    Box(
        modifier = modifier
            .width(cardMetrics.width)
            .aspectRatio(PosterLandscapeAspectRatio)
            .clip(RoundedCornerShape(cardMetrics.cornerRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .nuvioCardDepth(
                shape = RoundedCornerShape(cardMetrics.cornerRadius),
                surface = NuvioCardDepthSurface.ContinueWatching,
            )
            .clickable(onClick = onClick),
    ) {
        if (artworkUrl != null) {
            NuvioAsyncImage(
                model = cloudLibraryDisplayArtworkUrl(artworkUrl),
                contentDescription = item.title,
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        drawContent()
                        val startY = size.height * 0.40f
                        val gradient = Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to Color.Transparent,
                                0.55f to backgroundColor.copy(alpha = 0.75f),
                                1.0f to backgroundColor.copy(alpha = 0.95f),
                            ),
                            startY = startY,
                            endY = size.height,
                        )
                        drawRect(
                            brush = gradient,
                            topLeft = Offset(-2f, startY),
                            size = Size(size.width + 4f, (size.height - startY) + 4f),
                        )
                    },
                contentScale = ContentScale.Crop,
            )
        }

        if (trailing != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(cardMetrics.badgeInset)
                    .clip(RoundedCornerShape(4.dp))
                    .background(backgroundColor.copy(alpha = 0.80f))
                    .padding(
                        horizontal = cardMetrics.badgeHorizontalPadding,
                        vertical = cardMetrics.badgeVerticalPadding,
                    ),
            ) {
                trailing()
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(cardMetrics.contentPadding),
            verticalArrangement = Arrangement.spacedBy(cardMetrics.textGap),
        ) {
            leading?.invoke()

            if (episodeLine != null) {
                Text(
                    text = episodeLine,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = cardMetrics.metaTextSize,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontSize = cardMetrics.titleTextSize,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.episodeTitle?.isNotBlank() == true) {
                Text(
                    text = item.episodeTitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = cardMetrics.metaTextSize,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        item.progress?.takeIf { it > 0f }?.let { progress ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        horizontal = cardMetrics.progressHorizontalPadding,
                        vertical = cardMetrics.progressBottomPadding,
                    )
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(999.dp))
                    .height(cardMetrics.progressHeight)
                    .background(Color.Black.copy(alpha = 0.30f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

@Composable
private fun TitleCardWide(
    item: TitlePresentation,
    artworkUrl: String?,
    episodeLine: String?,
    layout: ContinueWatchingLayout,
    onClick: () -> Unit,
    modifier: Modifier,
    leading: @Composable (() -> Unit)?,
    trailing: @Composable (() -> Unit)?,
) {
    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .width(layout.wideCardWidth)
            .height(layout.wideCardHeight)
            .clip(RoundedCornerShape(layout.cardRadius))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .border(
                width = 1.5.dp,
                color = Color.White.copy(alpha = 0.15f),
                shape = RoundedCornerShape(layout.cardRadius),
            ),
    ) {
        ArtworkPanel(
            imageUrl = artworkUrl,
            width = layout.widePosterStripWidth,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxHeight(),
        )
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f)
                .padding(layout.wideContentPadding),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = item.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = layout.wideTitleSize,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    trailing?.let {
                        Box(modifier = Modifier.padding(start = 6.dp)) {
                            it()
                        }
                    }
                }
                leading?.invoke()
                if (episodeLine != null) {
                    Text(
                        text = episodeLine,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = layout.wideMetaSize,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (item.episodeTitle?.isNotBlank() == true) {
                    Text(
                        text = item.episodeTitle,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = layout.wideMetaSize,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            item.progress?.takeIf { it > 0f }?.let { progress ->
                NuvioProgressBar(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth(),
                    height = layout.progressHeight,
                    trackColor = Color.White.copy(alpha = 0.10f),
                )
            }
        }
    }
}

@Composable
private fun TitleCardPoster(
    item: TitlePresentation,
    artworkUrl: String?,
    episodeLine: String?,
    layout: ContinueWatchingLayout,
    onClick: () -> Unit,
    modifier: Modifier,
    leading: @Composable (() -> Unit)?,
    trailing: @Composable (() -> Unit)?,
) {
    Column(
        modifier = modifier
            .width(layout.posterCardWidth),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(layout.posterCardHeight)
                .clip(RoundedCornerShape(layout.cardRadius))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .nuvioCardDepth(
                    shape = RoundedCornerShape(layout.cardRadius),
                    surface = NuvioCardDepthSurface.ContinueWatching,
                )
                .clickable(onClick = onClick),
        ) {
            if (artworkUrl != null) {
                NuvioAsyncImage(
                    model = cloudLibraryDisplayArtworkUrl(artworkUrl),
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            trailing?.let {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.80f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    it()
                }
            }
            item.progress?.takeIf { it > 0f }?.let { progress ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 10.dp, vertical = 10.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                ) {
                    NuvioProgressBar(
                        progress = progress,
                        modifier = Modifier.width(layout.posterCardWidth - 32.dp),
                        height = layout.progressHeight,
                        trackColor = MaterialTheme.colorScheme.surfaceTint.copy(alpha = 0.16f),
                    )
                }
            }
        }

        leading?.invoke()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(layout.posterTitleBlockHeight),
            ) {
                Text(
                    text = item.title,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = layout.posterTitleSize,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 18.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (episodeLine != null) {
                Text(
                    text = episodeLine,
                    modifier = Modifier.padding(start = 6.dp),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = layout.posterMetaSize,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
