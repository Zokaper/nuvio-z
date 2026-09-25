package com.nuvio.app.features.downloads

/**
 * One user-facing vocabulary for a download (Phase 9, plan section 4.4). The engine keeps
 * `status + pauseReason + activity`; everything the user reads - the Downloads screen, the Android
 * notification, the iOS Live Activity - goes through [DownloadPresenter], so the three can never
 * describe the same download three ways.
 *
 * The plain line is plain words ("Waiting for connection", "Retrying shortly"). Attempts,
 * provider, HTTP and countdowns are the [DownloadDetail], shown on tap.
 */
enum class DownloadUserPhase { FINDING_SOURCE, QUEUED, DOWNLOADING, WAITING, PAUSED, NEEDS_YOU, COMPLETED }

enum class DownloadWaitReason {
    CONNECTION,
    WIFI,

    /** A retry is scheduled; the countdown and attempt count are detail. */
    RETRYING_SHORTLY,

    /** Has a slot, no bytes yet. */
    STARTING,

    /** Paused by the system (backgrounded, reclaimed); it comes back by itself. */
    RESUMING,
}

/** Exactly the four things the user is ever asked about (plan, "Attention, batches, queue"). */
enum class DownloadNeedsYouKind { NO_SOURCE_FITS, MANUAL_PICK, GAVE_UP, STORAGE }

data class DownloadDetail(
    val provider: String? = null,
    val source: String? = null,
    val attempts: Int = 0,
    val maxAttempts: Int = MAX_DOWNLOAD_ATTEMPTS,
    val lastError: String? = null,
    val nextRetryAtEpochMs: Long? = null,
    /** The engine's own words, for a bug report: "Queued · RETRY_BACKOFF". */
    val engineState: String = "",
)

data class DownloadPresentation(
    val phase: DownloadUserPhase,
    val waitReason: DownloadWaitReason? = null,
    val needsYou: DownloadNeedsYouKind? = null,
    /** For [DownloadNeedsYouKind.NO_SOURCE_FITS]: which rule could not be met. */
    val noSourceReason: DownloadEntryDecisionKind? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val detail: DownloadDetail = DownloadDetail(),
) {
    val progressPercent: Int?
        get() = totalBytes?.takeIf { it > 0L }?.let { total ->
            ((downloadedBytes.coerceAtLeast(0L).toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        }

    val isUnfinished: Boolean get() = phase != DownloadUserPhase.COMPLETED && phase != DownloadUserPhase.NEEDS_YOU
}

object DownloadPresenter {

    fun item(item: DownloadItem, nowEpochMs: Long): DownloadPresentation {
        val detail = DownloadDetail(
            provider = item.providerName.takeIf { it.isNotBlank() },
            source = item.streamTitle.takeIf { it.isNotBlank() },
            attempts = item.attemptCount,
            lastError = item.errorMessage?.takeIf { it.isNotBlank() },
            nextRetryAtEpochMs = item.nextRetryAtEpochMs?.takeIf { it > nowEpochMs },
            engineState = listOfNotNull(item.status.name, item.activity?.name, item.pauseReason?.name).joinToString(" · "),
        )
        fun of(
            phase: DownloadUserPhase,
            wait: DownloadWaitReason? = null,
            needs: DownloadNeedsYouKind? = null,
            reason: DownloadEntryDecisionKind? = null,
        ) = DownloadPresentation(phase, wait, needs, reason, item.downloadedBytes, item.totalBytes, detail)

        return when (item.status) {
            DownloadStatus.Completed -> of(DownloadUserPhase.COMPLETED)
            DownloadStatus.Failed -> when (item.failureKind) {
                DownloadFailureKind.STORAGE -> of(DownloadUserPhase.NEEDS_YOU, needs = DownloadNeedsYouKind.STORAGE)
                DownloadFailureKind.NOT_CACHED -> of(
                    DownloadUserPhase.NEEDS_YOU,
                    needs = DownloadNeedsYouKind.NO_SOURCE_FITS,
                    reason = DownloadEntryDecisionKind.NOTHING_CACHED,
                )
                null -> of(DownloadUserPhase.NEEDS_YOU, needs = DownloadNeedsYouKind.GAVE_UP)
            }
            DownloadStatus.Paused -> when {
                item.sizeApprovalRequired -> of(
                    DownloadUserPhase.NEEDS_YOU,
                    needs = DownloadNeedsYouKind.NO_SOURCE_FITS,
                    reason = DownloadEntryDecisionKind.OVER_LIMIT,
                )
                item.pauseReason == DownloadPauseReason.User || item.activity == DownloadActivity.USER_PAUSED ->
                    of(DownloadUserPhase.PAUSED)
                else -> of(DownloadUserPhase.WAITING, wait = DownloadWaitReason.RESUMING)
            }
            DownloadStatus.Downloading -> when {
                item.activity == DownloadActivity.RESOLVING_SOURCE -> of(DownloadUserPhase.FINDING_SOURCE)
                item.downloadedBytes <= 0L && item.totalBytes == null -> of(DownloadUserPhase.WAITING, wait = DownloadWaitReason.STARTING)
                else -> of(DownloadUserPhase.DOWNLOADING)
            }
            DownloadStatus.Queued -> when {
                item.activity == DownloadActivity.WAITING_FOR_CONNECTION ->
                    of(DownloadUserPhase.WAITING, wait = DownloadWaitReason.CONNECTION)
                item.activity == DownloadActivity.WAITING_FOR_WIFI ->
                    of(DownloadUserPhase.WAITING, wait = DownloadWaitReason.WIFI)
                item.activity == DownloadActivity.RETRY_BACKOFF ||
                    item.activity == DownloadActivity.WAITING_FOR_PROVIDER ||
                    item.isWaitingForRetry(nowEpochMs) ->
                    of(DownloadUserPhase.WAITING, wait = DownloadWaitReason.RETRYING_SHORTLY)
                item.activity == DownloadActivity.SYSTEM_PAUSED ->
                    of(DownloadUserPhase.WAITING, wait = DownloadWaitReason.RESUMING)
                else -> of(DownloadUserPhase.QUEUED)
            }
        }
    }

    /**
     * A batch entry with no download behind it yet. Null once an item backs it - the item is
     * what is presented then, so nothing is shown twice.
     */
    fun entry(entry: DownloadBatchEntry): DownloadPresentation? {
        val detail = DownloadDetail(
            provider = entry.providerName,
            source = entry.streamTitle,
            lastError = entry.failureMessage,
            engineState = listOfNotNull(entry.state.name, entry.decision?.name).joinToString(" · "),
        )
        return when (entry.state) {
            DownloadBatchEntryState.DISCOVERING,
            DownloadBatchEntryState.RESOLVING,
            DownloadBatchEntryState.READY,
            -> DownloadPresentation(DownloadUserPhase.FINDING_SOURCE, detail = detail)
            DownloadBatchEntryState.APPROVAL_NEEDED -> when {
                entry.selectsUncachedDebrid -> DownloadPresentation(
                    DownloadUserPhase.NEEDS_YOU,
                    needsYou = DownloadNeedsYouKind.NO_SOURCE_FITS,
                    noSourceReason = DownloadEntryDecisionKind.NOTHING_CACHED,
                    detail = detail,
                )
                else -> DownloadPresentation(
                    DownloadUserPhase.NEEDS_YOU,
                    needsYou = DownloadNeedsYouKind.NO_SOURCE_FITS,
                    // Entries from before stage 6 carry no reason; their approval was a size one.
                    noSourceReason = entry.decision ?: DownloadEntryDecisionKind.OVER_LIMIT,
                    detail = detail,
                )
            }
            DownloadBatchEntryState.SKIPPED -> when (entry.decision) {
                DownloadEntryDecisionKind.MANUAL_PICK -> DownloadPresentation(
                    DownloadUserPhase.NEEDS_YOU,
                    needsYou = DownloadNeedsYouKind.MANUAL_PICK,
                    detail = detail,
                )
                null -> DownloadPresentation(
                    DownloadUserPhase.NEEDS_YOU,
                    needsYou = DownloadNeedsYouKind.NO_SOURCE_FITS,
                    noSourceReason = DownloadEntryDecisionKind.NO_SOURCES,
                    detail = detail,
                )
                else -> DownloadPresentation(
                    DownloadUserPhase.NEEDS_YOU,
                    needsYou = DownloadNeedsYouKind.NO_SOURCE_FITS,
                    noSourceReason = entry.decision,
                    detail = detail,
                )
            }
            // Discovery itself failed (no item was ever made): the user can only try again.
            DownloadBatchEntryState.FAILED -> DownloadPresentation(
                DownloadUserPhase.NEEDS_YOU,
                needsYou = DownloadNeedsYouKind.GAVE_UP,
                detail = detail,
            )
            DownloadBatchEntryState.QUEUED,
            DownloadBatchEntryState.DOWNLOADING,
            DownloadBatchEntryState.PAUSED,
            DownloadBatchEntryState.COMPLETED,
            DownloadBatchEntryState.CANCELLED,
            -> null
        }
    }
}

// --- the queue, grouped ------------------------------------------------------------------------

/**
 * One row of the queue: a film, one episode on its own, or a season's worth of episodes as one
 * expandable row with combined progress (plan: "Season in the queue: one grouped expandable row").
 */
data class DownloadQueueGroup(
    val key: String,
    val parentMetaId: String,
    val title: String,
    val season: Int?,
    /** In queue order. */
    val items: List<DownloadItem>,
    val presentations: List<DownloadPresentation>,
    /** Episodes of this season already downloaded, for "3 of 10 done". */
    val completedCount: Int,
) {
    val isSeason: Boolean get() = season != null && items.size > 1
    val downloadedBytes: Long get() = presentations.sumOf { it.downloadedBytes.coerceAtLeast(0L) }
    val knownTotalBytes: Long get() = presentations.sumOf { it.totalBytes ?: 0L }
    val progressPercent: Int?
        get() {
            val total = knownTotalBytes.takeIf { it > 0L } ?: return null
            return ((downloadedBytes.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        }

    /** What the collapsed row says: the most active member's phase. */
    val lead: DownloadPresentation
        get() = presentations.minBy { leadRank(it) }

    val allPaused: Boolean get() = presentations.all { it.phase == DownloadUserPhase.PAUSED }
    val anyPaused: Boolean get() = presentations.any { it.phase == DownloadUserPhase.PAUSED }

    /** The title's poster, for the row's artwork. */
    val poster: String? get() = items.firstNotNullOfOrNull { it.poster ?: it.background }

    private fun leadRank(p: DownloadPresentation): Int = when (p.phase) {
        DownloadUserPhase.DOWNLOADING -> 0
        DownloadUserPhase.FINDING_SOURCE -> 1
        DownloadUserPhase.WAITING -> 2
        DownloadUserPhase.QUEUED -> 3
        DownloadUserPhase.PAUSED -> 4
        DownloadUserPhase.NEEDS_YOU -> 5
        DownloadUserPhase.COMPLETED -> 6
    }
}

object DownloadQueueGrouping {
    /** A season is one group; a film or a lone episode is its own. */
    fun keyOf(item: DownloadItem): String =
        if (item.isEpisode) "${item.parentMetaId}|${item.seasonNumber}" else "${item.parentMetaId}|${item.id}"

    /**
     * [unfinished] must already exclude items that need the user (those are attention cards).
     * Groups keep queue order: a group sits where its first member is queued.
     */
    fun group(
        unfinished: List<DownloadItem>,
        completed: List<DownloadItem>,
        nowEpochMs: Long,
    ): List<DownloadQueueGroup> {
        val ordered = unfinished.sortedWith(downloadQueueComparator)
        val groups = linkedMapOf<String, MutableList<DownloadItem>>()
        ordered.forEach { item -> groups.getOrPut(keyOf(item)) { mutableListOf() } += item }
        return groups.map { (key, members) ->
            val first = members.first()
            val season = first.seasonNumber.takeIf { first.isEpisode }
            DownloadQueueGroup(
                key = key,
                parentMetaId = first.parentMetaId,
                title = first.title,
                season = season,
                items = members,
                presentations = members.map { DownloadPresenter.item(it, nowEpochMs) },
                completedCount = if (season == null) {
                    0
                } else {
                    completed.count { it.parentMetaId == first.parentMetaId && it.seasonNumber == season }
                },
            )
        }
    }
}

// --- attention ---------------------------------------------------------------------------------

enum class AttentionAction {
    /** Over the size level: take the smallest anyway. */
    ALLOW,

    /** The chosen resolution is missing: take the nearest one. */
    USE_NEAREST,

    /** Manual, several episodes: open Choose sources. */
    CHOOSE_SOURCES,

    /** Manual: let the Assisted rules pick what is left. */
    PICK_THE_REST,

    /** Nothing cached / no sources: discovery again. */
    CHECK_AGAIN,
    RETRY,
    FREE_UP_SPACE,
    REMOVE,
}

sealed interface AttentionMember {
    val season: Int?
    val episode: Int?
    val title: String

    /** Whether a manual pick could help this one member ("Choose manually" per row). */
    val offersChooseManually: Boolean

    data class Item(val item: DownloadItem, val presentation: DownloadPresentation) : AttentionMember {
        override val season get() = item.seasonNumber
        override val episode get() = item.episodeNumber
        override val title get() = item.displayTitleForAttention()
        // A different source cannot fix a full disk, and nothing cached has nothing to pick.
        override val offersChooseManually: Boolean
            get() = presentation.needsYou != DownloadNeedsYouKind.STORAGE &&
                presentation.noSourceReason != DownloadEntryDecisionKind.NOTHING_CACHED &&
                presentation.noSourceReason != DownloadEntryDecisionKind.NO_SOURCES
    }

    data class Entry(val batch: DownloadBatch, val entry: DownloadBatchEntry, val presentation: DownloadPresentation) : AttentionMember {
        override val season get() = entry.season
        override val episode get() = entry.episode
        override val title get() = entry.title
        // Manual-pick entries have Choose sources instead; nothing-cached / no-sources have nothing to pick.
        override val offersChooseManually: Boolean
            get() = entry.decision != DownloadEntryDecisionKind.MANUAL_PICK &&
                DownloadFlowRules.offersChooseManually(entry.decision, entry.hasUsableSources)
    }
}

/** One card per title/season and reason: "2 episodes have no 1080p source · Use nearest". */
data class AttentionCard(
    val key: String,
    val parentMetaId: String,
    val title: String,
    val season: Int?,
    val kind: DownloadNeedsYouKind,
    val noSourceReason: DownloadEntryDecisionKind?,
    val members: List<AttentionMember>,
    val actions: List<AttentionAction>,
) {
    /** The batch behind Manual cards (Choose sources / pick the rest). */
    val batch: DownloadBatch? get() = members.firstNotNullOfOrNull { (it as? AttentionMember.Entry)?.batch }

    /** The title's poster, for the card's artwork. */
    val poster: String?
        get() = members.firstNotNullOfOrNull { member ->
            when (member) {
                is AttentionMember.Entry -> member.batch.poster ?: member.batch.background
                is AttentionMember.Item -> member.item.poster ?: member.item.background
            }
        }

    /** Over the limit: the largest of the smallest files, so "Allow" says what it may cost. */
    val allowBytes: Long?
        get() = members.mapNotNull { member ->
            when (member) {
                is AttentionMember.Entry -> (member.entry.selection as? SourceSelectionResult.ApprovalNeeded)?.facts?.sizeBytes
                is AttentionMember.Item -> member.item.totalBytes ?: member.item.expectedSizeBytes
            }
        }.takeIf { it.isNotEmpty() }?.sum()
}

object AttentionGrouping {
    fun group(items: List<DownloadItem>, batches: List<DownloadBatch>, nowEpochMs: Long): List<AttentionCard> {
        val members = mutableListOf<Triple<String, String, AttentionMember>>() // groupKey, title, member
        items.forEach { item ->
            val p = DownloadPresenter.item(item, nowEpochMs)
            if (p.phase == DownloadUserPhase.NEEDS_YOU) {
                members += Triple(item.parentMetaId, item.title, AttentionMember.Item(item, p))
            }
        }
        batches.filter { !it.isPreparing }.forEach { batch ->
            batch.entries.forEach { entry ->
                val p = DownloadPresenter.entry(entry) ?: return@forEach
                if (p.phase != DownloadUserPhase.NEEDS_YOU) return@forEach
                // A failed entry whose download exists is that download's card, not a second one.
                val backed = items.any {
                    it.parentMetaId == batch.parentMetaId && it.videoId == entry.videoId &&
                        it.seasonNumber == entry.season && it.episodeNumber == entry.episode
                }
                if (backed) return@forEach
                members += Triple(batch.parentMetaId, batch.title, AttentionMember.Entry(batch, entry, p))
            }
        }
        return members
            .groupBy { (parent, _, member) ->
                val p = member.presentationOf()
                listOf(parent, member.season, p.needsYou, p.noSourceReason)
            }
            .map { (_, grouped) ->
                val (parent, title, firstMember) = grouped.first()
                val p = firstMember.presentationOf()
                val kind = p.needsYou!!
                val cardMembers = grouped.map { it.third }.sortedWith(compareBy({ it.season ?: -1 }, { it.episode ?: -1 }))
                AttentionCard(
                    key = listOf(parent, firstMember.season, kind, p.noSourceReason).joinToString("|"),
                    parentMetaId = parent,
                    title = title,
                    season = firstMember.season,
                    kind = kind,
                    noSourceReason = p.noSourceReason,
                    members = cardMembers,
                    actions = actionsFor(kind, p.noSourceReason, cardMembers),
                )
            }
            .sortedWith(compareBy({ kindRank(it.kind) }, { it.title }, { it.season ?: -1 }))
    }

    fun actionsFor(
        kind: DownloadNeedsYouKind,
        reason: DownloadEntryDecisionKind?,
        members: List<AttentionMember>,
    ): List<AttentionAction> = when (kind) {
        DownloadNeedsYouKind.NO_SOURCE_FITS -> when (reason) {
            DownloadEntryDecisionKind.RESOLUTION_MISSING -> listOf(AttentionAction.USE_NEAREST, AttentionAction.REMOVE)
            DownloadEntryDecisionKind.NOTHING_CACHED,
            DownloadEntryDecisionKind.NO_SOURCES,
            -> listOf(AttentionAction.CHECK_AGAIN, AttentionAction.REMOVE)
            else -> listOf(AttentionAction.ALLOW, AttentionAction.REMOVE)
        }
        DownloadNeedsYouKind.MANUAL_PICK -> listOf(
            AttentionAction.CHOOSE_SOURCES,
            AttentionAction.PICK_THE_REST,
            AttentionAction.REMOVE,
        )
        DownloadNeedsYouKind.GAVE_UP ->
            // A failed discovery has no download to retry; checking again is the retry.
            if (members.all { it is AttentionMember.Entry }) {
                listOf(AttentionAction.CHECK_AGAIN, AttentionAction.REMOVE)
            } else {
                listOf(AttentionAction.RETRY, AttentionAction.REMOVE)
            }
        DownloadNeedsYouKind.STORAGE -> listOf(AttentionAction.FREE_UP_SPACE, AttentionAction.RETRY, AttentionAction.REMOVE)
    }

    private fun kindRank(kind: DownloadNeedsYouKind): Int = when (kind) {
        DownloadNeedsYouKind.STORAGE -> 0
        DownloadNeedsYouKind.NO_SOURCE_FITS -> 1
        DownloadNeedsYouKind.MANUAL_PICK -> 2
        DownloadNeedsYouKind.GAVE_UP -> 3
    }

    private fun AttentionMember.presentationOf(): DownloadPresentation = when (this) {
        is AttentionMember.Item -> presentation
        is AttentionMember.Entry -> presentation
    }
}

private fun DownloadItem.displayTitleForAttention(): String =
    if (isEpisode) episodeTitle?.trim()?.takeIf { it.isNotBlank() } ?: title else title

// --- storage and cleanup -----------------------------------------------------------------------

/** The storage bar: what downloads use (every profile's - the disk is shared) and what is free. */
data class DownloadStorageSummary(val usedBytes: Long, val freeBytes: Long) {
    /** Share of (used + free) that downloads take, or null when free space is unknown. */
    val usedFraction: Float?
        get() {
            if (freeBytes <= 0L) return null
            val whole = (usedBytes + freeBytes).takeIf { it > 0L } ?: return null
            return (usedBytes.toDouble() / whole.toDouble()).toFloat().coerceIn(0f, 1f)
        }

    companion object {
        fun of(deviceItems: List<DownloadItem>, freeBytes: Long) = DownloadStorageSummary(
            usedBytes = deviceItems.sumOf { item ->
                if (item.status == DownloadStatus.Completed) item.totalBytes ?: item.downloadedBytes else item.downloadedBytes
            }.coerceAtLeast(0L),
            freeBytes = freeBytes,
        )
    }
}

/** "Free up 12 GB of watched episodes": suggested, never automatic. */
data class WatchedCleanup(val items: List<DownloadItem>, val bytes: Long)

object DownloadCleanup {
    /** Worth suggesting from a gigabyte: less than that is not worth a card. */
    const val MIN_SUGGESTED_BYTES: Long = 1_000_000_000L

    fun watchedSuggestion(
        completed: List<DownloadItem>,
        isWatched: (DownloadItem) -> Boolean,
        minBytes: Long = MIN_SUGGESTED_BYTES,
    ): WatchedCleanup? {
        val watched = completed.filter { it.status == DownloadStatus.Completed && it.isEpisode && isWatched(it) }
        val bytes = watched.sumOf { it.totalBytes ?: it.downloadedBytes }
        if (watched.isEmpty() || bytes < minBytes) return null
        return WatchedCleanup(watched, bytes)
    }
}


/** The Live Activity's state for a presented download (the iOS actual's mapping, testable here). */
internal fun liveActivityStateOf(presentation: DownloadPresentation): DownloadsLiveStatusPolicy.State = when (presentation.phase) {
    DownloadUserPhase.COMPLETED -> DownloadsLiveStatusPolicy.State.COMPLETED
    DownloadUserPhase.NEEDS_YOU -> DownloadsLiveStatusPolicy.State.FAILED
    DownloadUserPhase.PAUSED -> DownloadsLiveStatusPolicy.State.PAUSED
    DownloadUserPhase.DOWNLOADING -> DownloadsLiveStatusPolicy.State.DOWNLOADING
    DownloadUserPhase.FINDING_SOURCE -> DownloadsLiveStatusPolicy.State.PREPARING
    DownloadUserPhase.QUEUED -> DownloadsLiveStatusPolicy.State.WAITING
    DownloadUserPhase.WAITING -> when (presentation.waitReason) {
        DownloadWaitReason.RETRYING_SHORTLY -> DownloadsLiveStatusPolicy.State.RETRYING
        DownloadWaitReason.STARTING -> DownloadsLiveStatusPolicy.State.STARTING
        DownloadWaitReason.RESUMING -> DownloadsLiveStatusPolicy.State.PAUSED
        DownloadWaitReason.CONNECTION, DownloadWaitReason.WIFI, null -> DownloadsLiveStatusPolicy.State.WAITING
    }
}
