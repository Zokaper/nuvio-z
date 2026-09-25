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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.VerticalAlignBottom
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.watchedItemKeys
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
    topChromePadding: Dp? = null,
    topSwitcher: (@Composable () -> Unit)? = null,
) {
    val uiState by remember {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.uiState
    }.collectAsStateWithLifecycle()
    val batches by DownloadsRepository.batches.collectAsStateWithLifecycle()

    var selectedShowId by rememberSaveable(initialShowId) { mutableStateOf(initialShowId) }
    var pendingTitleDeletion by remember { mutableStateOf<DownloadTitleGroup?>(null) }
    var downloadPendingDeletionId by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val openDownloadsDirectoryFailedText = stringResource(Res.string.downloads_open_directory_failed)
    val freeUpHintText = stringResource(Res.string.download_free_up_hint)
    val deviceItems by DownloadsRepository.deviceItems.collectAsStateWithLifecycle()
    val watchedUiState by remember {
        WatchedRepository.ensureLoaded()
        WatchedRepository.uiState
    }.collectAsStateWithLifecycle()
    val nowEpochMs = tickingNow(uiState.items.any { it.nextRetryAtEpochMs != null || it.status == DownloadStatus.Downloading })
    val attention = remember(uiState.items, batches, nowEpochMs) {
        AttentionGrouping.group(uiState.items, batches, nowEpochMs)
    }
    val queue = remember(uiState.items, batches, nowEpochMs) {
        val unfinished = uiState.items.filter {
            it.status != DownloadStatus.Completed &&
                DownloadPresenter.item(it, nowEpochMs).phase != DownloadUserPhase.NEEDS_YOU
        }
        DownloadQueueGrouping.group(unfinished, uiState.items, batches, nowEpochMs)
    }
    val storage = remember(deviceItems) {
        DownloadStorageSummary.of(deviceItems, DownloadsPlatformDownloader.freeStorageBytes())
    }
    val cleanup = remember(uiState.completedItems, watchedUiState.watchedKeys) {
        DownloadCleanup.watchedSuggestion(uiState.completedItems, isWatched = { item ->
            watchedItemKeys(item.parentMetaType, item.parentMetaId, item.seasonNumber, item.episodeNumber)
                .any(watchedUiState.watchedKeys::contains)
        })
    }
    var detailItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRemoval by remember { mutableStateOf<AttentionCard?>(null) }
    var pendingGroupCancel by remember { mutableStateOf<DownloadQueueGroup?>(null) }
    var pendingChoiceRemoval by remember { mutableStateOf<DownloadBatch?>(null) }
    val refreshingBatchIds by AssistedDiscovery.refreshing.collectAsStateWithLifecycle()
    var cleanupConfirm by remember { mutableStateOf(false) }
    var pendingSeasonDeletion by remember { mutableStateOf<Pair<String, Int>?>(null) }

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

    NuvioScreen(
        listState = listState,
        topPadding = if (topChromePadding != null) 0.dp else null,
    ) {
        stickyHeader {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                NuvioScreenHeader(
                    modifier = Modifier.downloadsContentWidth(),
                    title = if (selectedShowId == null) {
                        stringResource(Res.string.compose_settings_root_downloads_title)
                    } else {
                        selectedShowTitle ?: stringResource(Res.string.downloads_show_downloads)
                    },
                    topPadding = topChromePadding,
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
                if (selectedShowId == null && topSwitcher != null) {
                    Box(modifier = Modifier.downloadsContentWidth().padding(horizontal = 16.dp)) {
                        topSwitcher()
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
        }

        if (selectedShowId == null) {
            downloadsRootContent(
                uiState = uiState,
                batches = batches,
                storage = storage,
                attention = attention,
                queue = queue,
                cleanup = cleanup,
                nowEpochMs = nowEpochMs,
                onOpenDownload = onOpenDownload,
                onOpenShow = { showId, title ->
                    onNavigateToShow?.invoke(showId, title) ?: run { selectedShowId = showId }
                },
                onRequestTitleDeletion = { pendingTitleDeletion = it },
                onAttentionAction = { card, action ->
                    when (action) {
                        AttentionAction.REMOVE -> pendingRemoval = card
                        AttentionAction.FREE_UP_SPACE -> if (cleanup != null) {
                            cleanupConfirm = true
                        } else {
                            NuvioToastController.show(freeUpHintText)
                        }
                        else -> performAttentionAction(card, action)
                    }
                },
                onChooseMember = { member ->
                    when (member) {
                        is AttentionMember.Entry -> onChooseBatchEntryManually?.invoke(member.batch, member.entry)
                            ?: DownloadFlowController.chooseEntryManually(member.batch, member.entry)
                        is AttentionMember.Item -> DownloadFlowController.chooseItemManually(member.item)
                    }
                },
                onOpenDetail = { detailItemId = it.id },
                onReviewCleanup = { cleanupConfirm = true },
                onCancelGroup = { pendingGroupCancel = it },
                onRemoveChoiceBatch = { pendingChoiceRemoval = it },
                refreshingBatchIds = refreshingBatchIds,
            )
        } else {
            downloadsShowContent(
                episodes = showEpisodes,
                onOpenDownload = onOpenDownload,
                onDeleteDownload = { downloadPendingDeletionId = it },
                onDeleteSeason = { parentMetaId, season -> pendingSeasonDeletion = parentMetaId to season },
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

    detailItemId?.let { id ->
        val item = uiState.items.firstOrNull { it.id == id }
        if (item == null) {
            detailItemId = null
        } else {
            DownloadDetailSheet(
                item = item,
                nowEpochMs = nowEpochMs,
                onDismiss = { detailItemId = null },
                onDelete = {
                    detailItemId = null
                    downloadPendingDeletionId = item.id
                },
            )
        }
    }

    pendingRemoval?.let { card ->
        DownloadDeleteConfirmDialog(
            what = listOfNotNull(card.title, card.season?.let { "S$it" }).joinToString(" · "),
            onConfirm = {
                removeAttentionMembers(card)
                pendingRemoval = null
            },
            onDismiss = { pendingRemoval = null },
        )
    }

    pendingChoiceRemoval?.let { batch ->
        val season = AssistedChoiceRules.seasonOf(batch)
        NuvioStatusModal(
            title = stringResource(
                Res.string.download_choice_remove_title,
                season?.let { stringResource(Res.string.download_choice_season_label, batch.title, it) } ?: batch.title,
            ),
            message = stringResource(Res.string.download_choice_remove_body),
            isVisible = true,
            confirmText = stringResource(Res.string.download_action_remove),
            dismissText = stringResource(Res.string.action_cancel),
            onConfirm = {
                DownloadFlowController.removeChoiceBatch(batch.id)
                pendingChoiceRemoval = null
            },
            onDismiss = { pendingChoiceRemoval = null },
        )
    }

    pendingGroupCancel?.let { group ->
        DownloadDeleteConfirmDialog(
            what = listOfNotNull(group.title, group.season?.let { "S$it" }).joinToString(" · "),
            onConfirm = {
                DownloadsRepository.cancelDownloads(group.items.map { it.id })
                pendingGroupCancel = null
            },
            onDismiss = { pendingGroupCancel = null },
        )
    }

    pendingSeasonDeletion?.let { (parentMetaId, season) ->
        DownloadDeleteConfirmDialog(
            what = listOfNotNull(selectedShowTitle, "S$season").joinToString(" · "),
            onConfirm = {
                DownloadsRepository.deleteDownloadsForSeason(parentMetaId, season)
                pendingSeasonDeletion = null
            },
            onDismiss = { pendingSeasonDeletion = null },
        )
    }

    if (cleanupConfirm && cleanup != null) {
        NuvioStatusModal(
            title = stringResource(Res.string.download_cleanup_confirm_title),
            message = stringResource(
                Res.string.download_cleanup_confirm_body,
                cleanup.items.size,
                formatDownloadBytes(cleanup.bytes),
            ),
            isVisible = true,
            confirmText = stringResource(Res.string.action_delete),
            dismissText = stringResource(Res.string.action_cancel),
            onConfirm = {
                DownloadsRepository.cancelDownloads(cleanup.items.map { it.id })
                cleanupConfirm = false
            },
            onDismiss = { cleanupConfirm = false },
        )
    }

    val pendingDeletionId = downloadPendingDeletionId
    if (pendingDeletionId != null) {
        NuvioStatusModal(
            title = stringResource(Res.string.action_delete_confirm_title),
            message = stringResource(Res.string.action_delete_confirm_message),
            isVisible = true,
            confirmText = stringResource(Res.string.action_yes),
            dismissText = stringResource(Res.string.action_no),
            onConfirm = {
                DownloadsRepository.cancelDownload(pendingDeletionId)
                downloadPendingDeletionId = null
            },
            onDismiss = { downloadPendingDeletionId = null },
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

internal fun LazyListScope.downloadsRootContent(
    uiState: DownloadsUiState,
    batches: List<DownloadBatch>,
    storage: DownloadStorageSummary?,
    attention: List<AttentionCard>,
    queue: List<DownloadQueueGroup>,
    cleanup: WatchedCleanup?,
    nowEpochMs: Long,
    onOpenDownload: (DownloadItem) -> Unit,
    onOpenShow: (showId: String, title: String) -> Unit,
    onRequestTitleDeletion: (DownloadTitleGroup) -> Unit,
    onAttentionAction: (AttentionCard, AttentionAction) -> Unit,
    onChooseMember: (AttentionMember) -> Unit,
    onOpenDetail: (DownloadItem) -> Unit,
    onReviewCleanup: () -> Unit,
    onCancelGroup: (DownloadQueueGroup) -> Unit,
    /** Assisted "choose when ready": Remove on a batch still finding or waiting for its quality. */
    onRemoveChoiceBatch: (DownloadBatch) -> Unit = {},
    /** Batches finding their sources again after a process death. */
    refreshingBatchIds: Set<String> = emptySet(),
    /** The render harness opens every season; the app starts them closed. */
    initiallyExpandedGroups: Boolean = false,
) {
    // Phase 9 stage 7: storage, then what needs the user (one card per title/season and
    // reason), then the queue with a season as one row, then what is on the device.
    // Assisted "choose when ready" batches have their own row (finding, then ready to choose);
    // the rest of what is preparing is Automatic's, read-only.
    val choiceBatches = batches.filter { it.awaitsQualityChoice && (it.isPreparing || it.isAwaitingQualityChoice) }
    val preparingBatches = batches.filter { it.isPreparing && !it.awaitsQualityChoice }
    val completedGroups = uiState.completedItems.groupedByTitle()

    // Every row is capped at DownloadsContentMaxWidth and centred: a desktop window must not
    // stretch a row, and its actions, across the whole screen.
    val width = Modifier.downloadsContentWidth()
    if (storage != null && (uiState.items.isNotEmpty() || preparingBatches.isNotEmpty() || choiceBatches.isNotEmpty())) {
        item(key = "downloads-storage") { DownloadStorageBar(storage, width) }
    }

    // A suggestion about storage, so it sits with the storage bar rather than among the problems.
    if (cleanup != null) {
        item(key = "downloads-cleanup") { DownloadWatchedCleanupCard(cleanup, onReview = onReviewCleanup, modifier = width) }
    }

    if (attention.isNotEmpty()) {
        item(key = "downloads-attention") {
            DownloadAttentionSection(
                cards = attention,
                onAction = onAttentionAction,
                onChooseMember = onChooseMember,
                modifier = width,
            )
        }
    }

    if (choiceBatches.isNotEmpty() || preparingBatches.isNotEmpty() || queue.isNotEmpty()) {
        item(key = "downloads-active-title") {
            DownloadsSectionHeading(stringResource(Res.string.download_section_downloading), width)
        }
        items(choiceBatches, key = { "choice-${it.id}" }) { batch ->
            DownloadChoiceBatchRow(
                batch = batch,
                refreshing = batch.id in refreshingBatchIds,
                onChoose = { DownloadFlowController.chooseQuality(batch.id) },
                onRemove = { onRemoveChoiceBatch(batch) },
                modifier = width,
            )
        }
        items(preparingBatches, key = { "preparing-${it.id}" }) { batch ->
            PreparingBatchCard(batch = batch, modifier = width)
        }
        itemsIndexed(queue, key = { _, group -> "queue-${group.key}" }) { index, group ->
            if (group.isSeason) {
                DownloadQueueGroupRow(
                    group = group,
                    controls = DownloadGroupControls(
                        onPauseAll = { DownloadsRepository.pauseDownloads(group.items.map { it.id }) },
                        onResumeAll = { DownloadsRepository.resumeDownloads(group.items.map { it.id }) },
                        onCancelRemaining = { onCancelGroup(group) },
                        onMoveUp = if (index > 0) ({ DownloadsRepository.moveQueueGroup(group.key, up = true) }) else null,
                        onMoveDown = if (index < queue.lastIndex) ({ DownloadsRepository.moveQueueGroup(group.key, up = false) }) else null,
                    ),
                    onOpenItem = onOpenDetail,
                    onPauseItem = { DownloadsRepository.pauseDownload(it.id) },
                    onResumeItem = { DownloadsRepository.resumeDownload(it.id) },
                    initiallyExpanded = initiallyExpandedGroups,
                    modifier = width,
                )
            } else {
                val item = group.items.single()
                DownloadQueueItemRow(
                    item = item,
                    presentation = group.presentations.single(),
                    nowEpochMs = nowEpochMs,
                    onOpen = { onOpenDetail(item) },
                    onPause = { DownloadsRepository.pauseDownload(item.id) },
                    onResume = { DownloadsRepository.resumeDownload(item.id) },
                    modifier = width,
                )
            }
        }
    }

    if (completedGroups.isNotEmpty()) {
        item(key = "downloads-on-device-title") {
            DownloadsSectionHeading(stringResource(Res.string.downloads_section_on_device), width)
        }
        items(completedGroups, key = { "title-${it.parentMetaId}" }) { group ->
            DownloadTitleRow(
                group = group,
                modifier = width,
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

    if (uiState.items.isEmpty() && attention.isEmpty() && preparingBatches.isEmpty()) {
        item(key = "downloads-empty") {
            Column(
                modifier = Modifier
                    .downloadsContentWidth()
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
    onDeleteDownload: (String) -> Unit,
    onDeleteSeason: (parentMetaId: String, season: Int) -> Unit,
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
                    .downloadsContentWidth()
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
                    onClick = { onDeleteSeason(parentMetaId, seasonNumber) },
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
            Box(Modifier.downloadsContentWidth()) {
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
                    onDelete = { onDeleteDownload(item.id) },
                )
            }
        }
    }
}

/**
 * A batch that is still finding sources.
 *
 * Discovery runs in the background and can take minutes for a season, so without this
 * the tab shows nothing at all between the toast and the first queued episode. It is a
 * read-only view: the batch is persisted before discovery starts and each entry is
 * already updated as it resolves. Deliberately no cancel - the coordinator saves the
 * batch again when discovery finishes, so a removal here would silently come back.
 */
@Composable
private fun PreparingBatchCard(batch: DownloadBatch, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    val total = batch.entries.size
    val prepared = batch.preparedEntryCount

    Row(
        modifier = modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DownloadPoster(url = batch.poster ?: batch.background, title = batch.title, width = 48.dp)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = batch.title.trim().takeIf { it.isNotBlank() }
                    ?: stringResource(Res.string.downloads_section_preparing),
                style = MaterialTheme.typography.titleSmall,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(Res.string.downloads_preparing_progress, prepared, total),
                style = MaterialTheme.typography.labelMedium,
                color = tokens.colors.textMuted,
            )
            if (total > 0) {
                ThinProgress(prepared.toFloat() / total.toFloat(), modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

@Composable
private fun DownloadTitleRow(
    group: DownloadTitleGroup,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        DownloadPoster(url = group.poster, title = group.title, width = 48.dp)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = group.title,
                style = MaterialTheme.typography.titleSmall,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (group.isSeries) {
                    "${stringResource(Res.string.download_flow_episode_count, group.items.size)} · ${formatDownloadBytes(group.bytesOnDisk)}"
                } else {
                    formatDownloadBytes(group.bytesOnDisk)
                },
                style = MaterialTheme.typography.labelMedium,
                color = tokens.colors.textMuted,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Rounded.Delete,
                contentDescription = stringResource(Res.string.downloads_delete_title),
                tint = tokens.colors.textMuted,
            )
        }
        Icon(
            imageVector = if (group.isSeries) Icons.Rounded.ChevronRight else Icons.Rounded.PlayArrow,
            contentDescription = null,
            tint = tokens.colors.textMuted,
        )
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
    /** Null in the completed sections, where there is no queue position to change. */
    queueControls: QueueControls? = null,
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
                    if (queueControls != null) {
                        QueueMenu(controls = queueControls)
                    }
                    when (item.status) {
                        DownloadStatus.Queued,
                        DownloadStatus.Downloading,
                        -> {
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

            // Only a transfer that is actually running gets a bar. A queued item used to
            // spin an indeterminate one, which was indistinguishable from a live
            // download and made a waiting queue look like a stuck one.
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

            // Held back by the mobile-data rule, not broken: say so, and let the user spend
            // the data on this one item.
            if (item.activity == DownloadActivity.WAITING_FOR_WIFI) {
                TextButton(onClick = { DownloadsRepository.allowMobileData(listOf(item.id)) }) {
                    Text(stringResource(Res.string.downloads_download_now_anyway))
                }
            }
        }
    }
}

/**
 * What a row may do to its place in the queue.
 *
 * Boundary moves are disabled rather than hidden so the menu keeps a stable shape as
 * a row travels up and down the list.
 */
private data class QueueControls(
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    val onMoveToTop: () -> Unit,
    val onMoveUp: () -> Unit,
    val onMoveDown: () -> Unit,
    val onMoveToBottom: () -> Unit,
    val onChange: (() -> Unit)? = null,
)

/**
 * Reordering as a menu rather than drag handles.
 *
 * The downloads list is also driven with a TV remote, where dragging is not an
 * option, so every move is a discrete, focusable item.
 */
@Composable
private fun QueueMenu(controls: QueueControls) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = stringResource(Res.string.downloads_queue_actions),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            controls.onChange?.let { change ->
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.download_flow_change)) },
                    leadingIcon = {
                        Icon(Icons.Rounded.SwapHoriz, contentDescription = null)
                    },
                    onClick = {
                        expanded = false
                        change()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.downloads_queue_move_to_top)) },
                enabled = controls.canMoveUp,
                leadingIcon = {
                    Icon(Icons.Rounded.VerticalAlignTop, contentDescription = null)
                },
                onClick = {
                    expanded = false
                    controls.onMoveToTop()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.downloads_queue_move_up)) },
                enabled = controls.canMoveUp,
                leadingIcon = {
                    Icon(Icons.Rounded.ArrowUpward, contentDescription = null)
                },
                onClick = {
                    expanded = false
                    controls.onMoveUp()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.downloads_queue_move_down)) },
                enabled = controls.canMoveDown,
                leadingIcon = {
                    Icon(Icons.Rounded.ArrowDownward, contentDescription = null)
                },
                onClick = {
                    expanded = false
                    controls.onMoveDown()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.downloads_queue_move_to_bottom)) },
                enabled = controls.canMoveDown,
                leadingIcon = {
                    Icon(Icons.Rounded.VerticalAlignBottom, contentDescription = null)
                },
                onClick = {
                    expanded = false
                    controls.onMoveToBottom()
                },
            )
        }
    }
}

/** The attention actions that need no confirmation. REMOVE and FREE_UP_SPACE are the screen's. */
internal fun performAttentionAction(card: AttentionCard, action: AttentionAction) {
    val entries = card.members.filterIsInstance<AttentionMember.Entry>()
    val items = card.members.filterIsInstance<AttentionMember.Item>().map { it.item }
    when (action) {
        AttentionAction.ALLOW, AttentionAction.USE_NEAREST -> {
            items.filter { it.sizeApprovalRequired }.forEach { DownloadsRepository.approveUnexpectedSize(it.id) }
            entries.groupBy { it.batch.id }.forEach { (batchId, members) ->
                DownloadsRepository.queueBatch(
                    batchId,
                    approveUnknownSizes = true,
                    onlyEntryIds = members.mapTo(mutableSetOf()) { it.entry.id },
                )
            }
        }
        AttentionAction.CHECK_AGAIN -> {
            entries.forEach { DownloadFlowController.checkAgain(it.batch, it.entry) }
            items.forEach(DownloadFlowController::recheck)
        }
        AttentionAction.RETRY -> items.forEach { DownloadsRepository.retryDownload(it.id) }
        AttentionAction.CHOOSE_SOURCES -> card.batch?.let { DownloadFlowController.openChooseSources(it.id) }
        AttentionAction.PICK_THE_REST -> card.batch?.let(DownloadFlowController::pickTheRest)
        AttentionAction.REMOVE -> removeAttentionMembers(card)
        AttentionAction.FREE_UP_SPACE -> Unit
    }
}

internal fun removeAttentionMembers(card: AttentionCard) {
    card.members.filterIsInstance<AttentionMember.Item>().forEach { DownloadsRepository.cancelDownload(it.item.id) }
    card.members.filterIsInstance<AttentionMember.Entry>().groupBy { it.batch.id }.forEach { (batchId, members) ->
        DownloadsRepository.removeBatchEntries(batchId, members.mapTo(mutableSetOf()) { it.entry.id })
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DownloadDetailSheet(
    item: DownloadItem,
    nowEpochMs: Long,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    com.nuvio.app.core.ui.NuvioModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        DownloadDetailContent(
            item = item,
            presentation = DownloadPresenter.item(item, nowEpochMs),
            nowEpochMs = nowEpochMs,
            onPause = { DownloadsRepository.pauseDownload(item.id) },
            onResume = { DownloadsRepository.resumeDownload(item.id) },
            onChange = {
                onDismiss()
                DownloadFlowController.change(item)
            },
            onDownloadNext = {
                DownloadsRepository.moveDownloadToTop(item.id)
                onDismiss()
            },
            onDelete = onDelete,
        )
    }
}
