package com.nuvio.app.features.details.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.i18n.localizedSeasonEpisodeCode
import com.nuvio.app.core.ui.nuvioHorizontalScrollBleed
import com.nuvio.app.core.ui.posterCardClickable
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.TitleDownloadState
import com.nuvio.app.features.downloads.sortedForSeriesDownloads
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * What this title has on the device, shown on its own entry so downloads are visible where the
 * content lives rather than only in the Downloads tab.
 */
@Composable
fun DetailDownloadsSection(
    downloadState: TitleDownloadState,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
    horizontalScrollPadding: Dp = 0.dp,
    onItemClick: (DownloadItem) -> Unit,
    onItemManage: (DownloadItem) -> Unit,
) {
    val items = remember(downloadState) {
        downloadState.completedItems.sortedForSeriesDownloads()
    }
    if (items.isEmpty()) return

    DetailSection(
        title = stringResource(Res.string.meta_section_downloads_title),
        modifier = modifier,
        showHeader = showHeader,
    ) {
        LazyRow(
            modifier = Modifier
                .nuvioHorizontalScrollBleed(horizontalScrollPadding)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = horizontalScrollPadding),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(items, key = { it.id }) { item ->
                DownloadedItemCard(
                    item = item,
                    onClick = { onItemClick(item) },
                    onManage = { onItemManage(item) },
                )
            }
        }
    }
}

@Composable
private fun DownloadedItemCard(
    item: DownloadItem,
    onClick: () -> Unit,
    onManage: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val imageUrl = item.episodeThumbnail ?: item.background ?: item.poster
    val label = if (item.isEpisode) {
        item.episodeTitle?.trim()?.takeIf { it.isNotBlank() } ?: item.title
    } else {
        item.title
    }
    val caption = localizedSeasonEpisodeCode(
        seasonNumber = item.seasonNumber,
        episodeNumber = item.episodeNumber,
    )

    Column(
        modifier = Modifier.width(168.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .posterCardClickable(
                    onClick = onClick,
                    onLongClick = onManage,
                    zoomImageUrl = null,
                    zoomCornerRadius = 10.dp,
                ),
        ) {
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = label,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = stringResource(Res.string.downloads_play_offline),
                tint = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!caption.isNullOrBlank()) {
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            IconButton(
                onClick = onManage,
                modifier = Modifier.padding(start = 2.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(Res.string.downloads_manage_title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
