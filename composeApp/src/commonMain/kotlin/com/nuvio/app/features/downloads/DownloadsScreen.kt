package com.nuvio.app.features.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.isDesktop
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
    var pendingTitleDeletion by remember { mutableStateOf<DownloadLibraryTitle?>(null) }
    var selectedSeason by rememberSaveable(selectedShowId) { mutableStateOf<Int?>(null) }
    var pendingWatchedDeletion by remember { mutableStateOf<Pair<Int, List<DownloadItem>>?>(null) }
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

    val library = remember(uiState.items) { DownloadLibrary.titles(uiState.items) }
    val metadata by remember {
        DownloadTitleMetadataStore.ensureLoaded()
        DownloadTitleMetadataStore.titles
    }.collectAsStateWithLifecycle()
    val watch = rememberDownloadWatchStates()
    // Snapshot what the library shows while online, so it still looks like itself offline. Every
    // profile's downloads keep theirs: the snapshots are the device's, like the files.
    LaunchedEffect(library.map { it.parentMetaId to it.seasons }) {
        DownloadTitleMetadataStore.refresh(library)
    }
    LaunchedEffect(deviceItems.map { it.parentMetaId }.toSet()) {
        if (deviceItems.isNotEmpty()) DownloadTitleMetadataStore.prune(deviceItems.mapTo(mutableSetOf()) { it.parentMetaId })
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

    // [headerWidth]: the single column's capped width, or the two panes' full width.
    val header: @Composable (Modifier) -> Unit = { headerWidth ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background),
        ) {
            NuvioScreenHeader(
                modifier = headerWidth,
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
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (selectedShowId == null && onOpenSettings != null) {
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = stringResource(Res.string.downloads_settings_title),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
            if (selectedShowId == null && topSwitcher != null) {
                // NuvioScreen already pads this screen 16dp. Library pads its own header and switcher
                // by the same 16dp on an unpadded screen; another 16 here put the chips 16dp to the
                // right of Library's, so they jumped sideways on every switch between the two tabs.
                Box(modifier = Modifier.downloadsContentWidth()) {
                    topSwitcher()
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // One column is capped at DownloadsContentMaxWidth; two cards to a row once it is wide
        // enough for two backdrops to keep their logos legible.
        val libraryColumns = if (minOf(maxWidth - 32.dp, DownloadsContentMaxWidth) >= 600.dp) 2 else 1
        val rootContent: LazyListScope.(DownloadsPart) -> Unit = { part ->
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
                library = library,
                metadata = metadata,
                watch = watch,
                libraryColumns = libraryColumns,
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
                part = part,
            )
        }

        val showId = selectedShowId
        if (showId != null) {
            // A show's own page: a title page of what is on the device, one season at a time.
            DownloadedShowPage(
                episodes = showEpisodes,
                metadata = metadata[showId],
                watch = watch,
                nowEpochMs = nowEpochMs,
                selectedSeason = selectedSeason,
                onSelectSeason = { selectedSeason = it },
                onBack = { onBackFromShow?.invoke() ?: run { selectedShowId = null } },
                onPlay = onOpenDownload,
                onOpenDetail = { detailItemId = it.id },
                onDeleteTitle = { pendingTitleDeletion = library.firstOrNull { it.parentMetaId == showId } },
                onDeleteSeason = { season -> pendingSeasonDeletion = showId to season },
                onDeleteWatched = { season, items -> pendingWatchedDeletion = season to items },
                onDeleteEpisode = { downloadPendingDeletionId = it.id },
                listState = listState,
            )
        } else if (isDesktop && topSwitcher == null && downloadsUsesWideLayout(maxWidth)) {
            // Desktop's own Downloads destination, in a window wide enough: two panes. A phone and a
            // narrow window keep the one column.
            DownloadsWideLayout(header = header, content = rootContent, mainListState = listState)
        } else {
            NuvioScreen(
                listState = listState,
                topPadding = if (topChromePadding != null) 0.dp else null,
            ) {
                stickyHeader { header(Modifier.downloadsContentWidth()) }
                rootContent(DownloadsPart.All)
            }
        }
    }

    pendingTitleDeletion?.let { title ->
        DownloadDeleteConfirmation(
            title = title,
            onDismiss = { pendingTitleDeletion = null },
            onConfirm = {
                DownloadsRepository.deleteDownloadsForTitle(title.parentMetaId)
                pendingTitleDeletion = null
                // Deleted from its own page: nothing is left to show there.
                if (selectedShowId == title.parentMetaId) {
                    onBackFromShow?.invoke() ?: run { selectedShowId = null }
                }
            },
        )
    }

    pendingWatchedDeletion?.let { (season, items) ->
        NuvioStatusModal(
            title = stringResource(Res.string.download_library_delete_watched_title),
            message = stringResource(
                Res.string.download_library_delete_watched_body,
                items.size,
                listOfNotNull(selectedShowTitle, "S$season").joinToString(" · "),
                formatDownloadBytes(items.sumOf { it.totalBytes ?: it.downloadedBytes }),
            ),
            isVisible = true,
            confirmText = stringResource(Res.string.action_delete),
            dismissText = stringResource(Res.string.action_cancel),
            onConfirm = {
                DownloadsRepository.cancelDownloads(items.map { it.id })
                pendingWatchedDeletion = null
            },
            onDismiss = { pendingWatchedDeletion = null },
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
    onRequestTitleDeletion: (DownloadLibraryTitle) -> Unit,
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
    /** Every section in one column, or one pane's share of them (desktop, wide window). */
    part: DownloadsPart = DownloadsPart.All,
    /** What is finished on the device, by title ([DownloadLibrary.titles]). */
    library: List<DownloadLibraryTitle> = DownloadLibrary.titles(uiState.items),
    metadata: Map<String, DownloadTitleMetadata> = emptyMap(),
    watch: (DownloadItem) -> DownloadWatchState = { DownloadWatchState.Unwatched },
    /** Library cards to a row in one column; the desktop rail is always one. */
    libraryColumns: Int = 1,
) {
    // Phase 9 stage 7: storage, then what needs the user (one card per title/season and
    // reason), then the queue with a season as one row, then what is on the device.
    // Assisted "choose when ready" batches have their own row (finding, then ready to choose, or
    // after "Choose now" checking what was found); the rest of what is preparing is Automatic's,
    // read-only.
    val choiceBatches = batches.filter { it.showsAsChoiceRow }
    val preparingBatches = batches.filter { it.isPreparing && !it.showsAsChoiceRow }

    // Every row is capped at DownloadsContentMaxWidth and centred: a desktop window must not
    // stretch a row, and its actions, across the whole screen.
    // In a pane, the pane is the width.
    val width = if (part == DownloadsPart.All) Modifier.downloadsContentWidth() else Modifier.fillMaxWidth()
    if (part.showsRail && storage != null && (uiState.items.isNotEmpty() || preparingBatches.isNotEmpty() || choiceBatches.isNotEmpty())) {
        item(key = "downloads-storage") { DownloadStorageBar(storage, width) }
    }

    // A suggestion about storage, so it sits with the storage bar rather than among the problems.
    if (part.showsRail && cleanup != null) {
        item(key = "downloads-cleanup") { DownloadWatchedCleanupCard(cleanup, onReview = onReviewCleanup, modifier = width) }
    }

    if (part.showsMain && attention.isNotEmpty()) {
        item(key = "downloads-attention") {
            DownloadAttentionSection(
                cards = attention,
                onAction = onAttentionAction,
                onChooseMember = onChooseMember,
                modifier = width,
            )
        }
    }

    if (part.showsMain && (choiceBatches.isNotEmpty() || preparingBatches.isNotEmpty() || queue.isNotEmpty())) {
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

    // Two panes, everything finished: the main pane says so rather than standing empty.
    if (part == DownloadsPart.Main && uiState.items.isNotEmpty() && attention.isEmpty() &&
        choiceBatches.isEmpty() && preparingBatches.isEmpty() && queue.isEmpty()
    ) {
        item(key = "downloads-nothing-active") {
            Text(
                text = stringResource(Res.string.downloads_nothing_active),
                modifier = width.padding(horizontal = 4.dp, vertical = 24.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (part.showsRail && library.isNotEmpty()) {
        item(key = "downloads-on-device-title") {
            DownloadsSectionHeading(stringResource(Res.string.downloads_section_on_device), width)
        }
        downloadLibrarySection(
            titles = library,
            metadata = metadata,
            watch = watch,
            columns = if (part == DownloadsPart.Rail) 1 else libraryColumns,
            width = width,
            onOpenShow = { onOpenShow(it.parentMetaId, it.title) },
            onPlay = onOpenDownload,
            onDelete = onRequestTitleDeletion,
        )
    }

    if (part.showsMain && uiState.items.isEmpty() && attention.isEmpty() && preparingBatches.isEmpty() && choiceBatches.isEmpty()) {
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
private fun DownloadDeleteConfirmation(
    title: DownloadLibraryTitle,
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
                    title.title,
                    formatDownloadBytes(title.bytesOnDisk),
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
