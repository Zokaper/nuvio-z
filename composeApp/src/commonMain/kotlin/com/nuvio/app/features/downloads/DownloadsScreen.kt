package com.nuvio.app.features.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioToastController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Everything downloaded or downloading on this device. Rendered as a root tab, and as a
 * pushed destination when opening one show's episodes.
 */
@Composable
fun DownloadsScreen(
    onOpenDownload: (DownloadItem) -> Unit,
    onBack: (() -> Unit)? = null,
    initialShowId: String? = null,
    scrollToTopRequests: Flow<Unit> = emptyFlow(),
    onNavigateToShow: ((showId: String, title: String) -> Unit)? = null,
    onBackFromShow: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    onChooseBatchEntryManually: ((DownloadBatch, DownloadBatchEntry) -> Unit)? = null,
) {
    val uiState by remember {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.uiState
    }.collectAsStateWithLifecycle()
    val batches by DownloadsRepository.batches.collectAsStateWithLifecycle()

    var selectedShowId by rememberSaveable(initialShowId) { mutableStateOf(initialShowId) }
    var pendingTitleDeletion by remember { mutableStateOf<DownloadTitleGroup?>(null) }
    val listState = rememberLazyListState()
    val openDownloadsDirectoryFailedText = stringResource(Res.string.downloads_open_directory_failed)

    LaunchedEffect(scrollToTopRequests) {
        scrollToTopRequests.collect { listState.animateScrollToItem(0) }
    }

    val showEpisodes = remember(uiState.items, selectedShowId) {
        selectedShowId?.let { showId ->
            uiState.items
                .filter { it.isEpisode && it.parentMetaId == showId }
                .sortedForSeriesDownloads()
        }.orEmpty()
    }

    val selectedShowTitle = remember(showEpisodes) {
        showEpisodes.firstOrNull()?.title
    }

    NuvioScreen(listState = listState) {
        stickyHeader {
            NuvioScreenHeader(
                title = if (selectedShowId == null) {
                    stringResource(Res.string.compose_settings_root_downloads_title)
                } else {
                    selectedShowTitle ?: stringResource(Res.string.downloads_show_downloads)
                },
                onBack = if (selectedShowId != null) {
                    { onBackFromShow?.invoke() ?: run { selectedShowId = null } }
                } else {
                    onBack
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (!DownloadsPlatformDownloader.openDownloadsDirectory()) {
                                NuvioToastController.show(openDownloadsDirectoryFailedText)
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Folder,
                            contentDescription = stringResource(Res.string.downloads_open_directory),
                        )
                    }
                    if (selectedShowId == null && onOpenSettings != null) {
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = stringResource(Res.string.downloads_settings_title),
                            )
                        }
                    }
                },
            )
        }

        if (selectedShowId == null) {
            downloadsRootContent(
                uiState = uiState,
                batches = batches,
                onOpenDownload = onOpenDownload,
                onOpenShow = { showId, title ->
                    onNavigateToShow?.invoke(showId, title) ?: run { selectedShowId = showId }
                },
                onRequestTitleDeletion = { pendingTitleDeletion = it },
                onChooseBatchEntryManually = onChooseBatchEntryManually,
            )
        } else {
            downloadsShowContent(
                episodes = showEpisodes,
                onOpenDownload = onOpenDownload,
            )
        }
    }

    pendingTitleDeletion?.let { group ->
        DownloadDeleteConfirmation(
            group = group,
            onDismiss = { pendingTitleDeletion = null },
            onConfirm = {
                DownloadsRepository.deleteDownloadsForTitle(group.parentMetaId)
                pendingTitleDeletion = null
            },
        )
    }
}

/** One movie, or one show's worth of episodes, as shown in the "on this device" list. */
internal data class DownloadTitleGroup(
    val parentMetaId: String,
    val title: String,
    val poster: String?,
    val items: List<DownloadItem>,
) {
    val representative: DownloadItem = items.first()
    val isSeries: Boolean = representative.isEpisode
    val bytesOnDisk: Long = items.sumOf { it.totalBytes ?: it.downloadedBytes }
}

internal fun List<DownloadItem>.groupedByTitle(): List<DownloadTitleGroup> =
    groupBy { it.parentMetaId }
        .mapNotNull { (parentMetaId, items) ->
            val first = items.firstOrNull() ?: return@mapNotNull null
            DownloadTitleGroup(
                parentMetaId = parentMetaId,
                title = first.title,
                poster = items.firstNotNullOfOrNull { it.poster ?: it.background },
                items = items.sortedForSeriesDownloads(),
            )
        }
        .sortedBy { it.title.lowercase() }

private fun LazyListScope.downloadsRootContent(
    uiState: DownloadsUiState,
    batches: List<DownloadBatch>,
    onOpenDownload: (DownloadItem) -> Unit,
    onOpenShow: (showId: String, title: String) -> Unit,
    onRequestTitleDeletion: (DownloadTitleGroup) -> Unit,
    onChooseBatchEntryManually: ((DownloadBatch, DownloadBatchEntry) -> Unit)?,
) {
    val reviewBatches = batches.filter { batch ->
        batch.entries.any {
            it.state == DownloadBatchEntryState.APPROVAL_NEEDED ||
                it.state == DownloadBatchEntryState.SKIPPED ||
                it.state == DownloadBatchEntryState.FAILED
        }
    }
    val attentionItems = uiState.items.filter {
        it.sizeApprovalRequired || it.status == DownloadStatus.Failed
    }
    val activeItems = uiState.activeItems.filterNot { it in attentionItems }
    val completedGroups = uiState.completedItems.groupedByTitle()

    if (reviewBatches.isNotEmpty() || attentionItems.isNotEmpty()) {
        item(key = "downloads-attention-title") {
            DownloadSectionTitle(stringResource(Res.string.downloads_section_attention))
        }
        items(reviewBatches, key = { "batch-${it.id}" }) { batch ->
            ReviewBatchCard(
                batch = batch,
                onChooseBatchEntryManually = onChooseBatchEntryManually,
            )
        }
        items(attentionItems, key = { "attention-${it.id}" }) { item ->
            DownloadRow(
                item = item,
                onOpen = { onOpenDownload(item) },
                onPause = { DownloadsRepository.pauseDownload(item.id) },
                onResume = {
                    if (item.sizeApprovalRequired) {
                        DownloadsRepository.approveUnexpectedSize(item.id)
                    } else {
                        DownloadsRepository.resumeDownload(item.id)
                    }
                },
                onRetry = { DownloadsRepository.retryDownload(item.id) },
                onDelete = { DownloadsRepository.cancelDownload(item.id) },
            )
        }
    }

    if (activeItems.isNotEmpty()) {
        item(key = "downloads-active-title") {
            DownloadSectionTitle(stringResource(Res.string.downloads_section_downloading))
        }
        items(activeItems, key = { "active-${it.id}" }) { item ->
            DownloadRow(
                item = item,
                onOpen = { onOpenDownload(item) },
                onPause = { DownloadsRepository.pauseDownload(item.id) },
                onResume = { DownloadsRepository.resumeDownload(item.id) },
                onRetry = { DownloadsRepository.retryDownload(item.id) },
                onDelete = { DownloadsRepository.cancelDownload(item.id) },
            )
        }
    }

    if (completedGroups.isNotEmpty()) {
        item(key = "downloads-on-device-title") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DownloadSectionTitle(
                    title = stringResource(Res.string.downloads_section_on_device),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(
                        Res.string.downloads_storage_used,
                        formatDownloadBytes(uiState.bytesOnDisk),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(completedGroups, key = { "title-${it.parentMetaId}" }) { group ->
            DownloadTitleRow(
                group = group,
                onClick = {
                    if (group.isSeries) {
                        onOpenShow(group.parentMetaId, group.title)
                    } else {
                        onOpenDownload(group.representative)
                    }
                },
                onDelete = { onRequestTitleDeletion(group) },
            )
        }
    }

    if (uiState.items.isEmpty() && reviewBatches.isEmpty()) {
        item(key = "downloads-empty") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(Res.string.downloads_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(Res.string.downloads_empty_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun LazyListScope.downloadsShowContent(
    episodes: List<DownloadItem>,
    onOpenDownload: (DownloadItem) -> Unit,
) {
    if (episodes.isEmpty()) {
        item(key = "downloads-show-empty") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.downloads_empty_episodes),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    val seasons = episodes
        .groupBy { it.seasonNumber ?: 0 }
        .toList()
        .sortedWith(
            compareBy<Pair<Int, List<DownloadItem>>> { (season, _) ->
                if (season == 0) 0 else 1
            }.thenBy { (season, _) -> if (season == 0) 0 else season },
        )

    seasons.forEach { (seasonNumber, entries) ->
        item(key = "downloads-season-$seasonNumber") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DownloadSectionTitle(
                    title = if (seasonNumber == 0) {
                        stringResource(Res.string.episodes_specials)
                    } else {
                        stringResource(Res.string.episodes_season, seasonNumber)
                    },
                    modifier = Modifier.weight(1f),
                )
                val parentMetaId = entries.first().parentMetaId
                IconButton(
                    onClick = {
                        DownloadsRepository.deleteDownloadsForSeason(parentMetaId, seasonNumber)
                    },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = stringResource(Res.string.downloads_delete_season),
                    )
                }
            }
        }

        items(
            items = entries.sortedForSeriesDownloads(),
            key = { it.id },
        ) { item ->
            DownloadRow(
                item = item,
                onOpen = { onOpenDownload(item) },
                onPause = { DownloadsRepository.pauseDownload(item.id) },
                onResume = {
                    if (item.sizeApprovalRequired) {
                        DownloadsRepository.approveUnexpectedSize(item.id)
                    } else {
                        DownloadsRepository.resumeDownload(item.id)
                    }
                },
                onRetry = { DownloadsRepository.retryDownload(item.id) },
                onDelete = { DownloadsRepository.cancelDownload(item.id) },
            )
        }
    }
}

@Composable
private fun ReviewBatchCard(
    batch: DownloadBatch,
    onChooseBatchEntryManually: ((DownloadBatch, DownloadBatchEntry) -> Unit)?,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(batch.title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${batch.presetSnapshot.name} • ${batch.entries.count { it.state == DownloadBatchEntryState.APPROVAL_NEEDED }} approval needed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (batch.entries.any { it.state == DownloadBatchEntryState.APPROVAL_NEEDED }) {
                    IconButton(onClick = { DownloadsRepository.queueBatch(batch.id, approveUnknownSizes = true) }) {
                        Icon(
                            Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(Res.string.download_batch_approve_unknown),
                        )
                    }
                }
                IconButton(onClick = { DownloadsRepository.removeBatch(batch.id) }) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = stringResource(Res.string.action_delete),
                    )
                }
            }
            if (onChooseBatchEntryManually != null) {
                batch.entries
                    .filter {
                        it.state == DownloadBatchEntryState.SKIPPED ||
                            it.state == DownloadBatchEntryState.FAILED
                    }
                    .forEach { entry ->
                        TextButton(
                            onClick = { onChooseBatchEntryManually(batch, entry) },
                        ) {
                            Text("${entry.title}: ${stringResource(Res.string.download_choose_manual)}")
                        }
                    }
            }
        }
    }
}

@Composable
private fun DownloadTitleRow(
    group: DownloadTitleGroup,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DownloadArtwork(
                imageUrl = group.poster,
                contentDescription = group.title,
                modifier = Modifier.width(52.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = group.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (group.isSeries) {
                        "${stringResource(Res.string.downloads_episode_count, group.items.size)} • ${formatDownloadBytes(group.bytesOnDisk)}"
                    } else {
                        formatDownloadBytes(group.bytesOnDisk)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = stringResource(Res.string.downloads_delete_title),
                )
            }
            Icon(
                imageVector = if (group.isSeries) Icons.Rounded.ChevronRight else Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DownloadArtwork(
    imageUrl: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = modifier
            .aspectRatio(2f / 3f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun DownloadDeleteConfirmation(
    group: DownloadTitleGroup,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.downloads_delete_title)) },
        text = {
            Text(
                stringResource(
                    Res.string.downloads_delete_title_confirmation,
                    group.title,
                    formatDownloadBytes(group.bytesOnDisk),
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(Res.string.action_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}

@Composable
private fun DownloadRow(
    item: DownloadItem,
    onOpen: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val displayTitle = item.displayTitle()
    val displaySubtitle = downloadDisplaySubtitle(
        item = item,
        displayTitle = displayTitle,
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(enabled = item.isPlayable, onClick = onOpen),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                DownloadArtwork(
                    imageUrl = item.episodeThumbnail ?: item.poster ?: item.background,
                    contentDescription = displayTitle,
                    modifier = Modifier.width(44.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = displayTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = displaySubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = downloadStatusText(item),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (item.status) {
                        DownloadStatus.Downloading -> {
                            IconButton(onClick = onPause) {
                                Icon(
                                    imageVector = Icons.Rounded.Pause,
                                    contentDescription = stringResource(Res.string.compose_action_pause),
                                )
                            }
                        }
                        DownloadStatus.Paused -> {
                            IconButton(onClick = onResume) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = if (item.sizeApprovalRequired) {
                                        stringResource(Res.string.download_approve_size)
                                    } else {
                                        stringResource(Res.string.action_resume)
                                    },
                                )
                            }
                        }
                        DownloadStatus.Failed -> {
                            IconButton(onClick = onRetry) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = stringResource(Res.string.action_retry),
                                )
                            }
                        }
                        DownloadStatus.Completed -> {
                            IconButton(onClick = onOpen) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = stringResource(Res.string.action_play),
                                )
                            }
                        }
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = stringResource(Res.string.action_delete),
                        )
                    }
                }
            }

            if (item.status == DownloadStatus.Downloading) {
                if (item.totalBytes != null && item.totalBytes > 0L) {
                    LinearProgressIndicator(
                        progress = item.progressFraction,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
