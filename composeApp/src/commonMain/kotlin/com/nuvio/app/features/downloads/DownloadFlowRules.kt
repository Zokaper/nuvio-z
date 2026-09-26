package com.nuvio.app.features.downloads

/**
 * The decisions behind the download flows (Phase 9, stage 6), import-free so the pure suites run
 * them: what a download that waits for the user is waiting *for*, when "Choose manually" may be
 * offered, whether a batch fits in free space, the season chooser's quick selections, and which
 * resolution the Assisted sheet pre-selects.
 */

/**
 * Why a batch entry waits for the user. Stored on the entry so the Downloads screen can say the
 * true reason and offer only actions that can work.
 */
enum class DownloadEntryDecisionKind {
    /** Every usable source is above the size level. Allow takes the smallest. */
    OVER_LIMIT,

    /** The chosen resolution has no source. Use nearest takes the closest resolution. */
    RESOLUTION_MISSING,

    /** Sources exist, none is usable (not cached on the debrid service). Check again / Remove. */
    NOTHING_CACHED,

    /** No source at all that downloads may use. Check again / Remove. */
    NO_SOURCES,

    /** Manual: the user has not picked this episode's source yet. */
    MANUAL_PICK,
}

object DownloadFlowRules {

    /** Downloads still waiting for or holding a transfer: what counts against iOS's submission window. */
    fun pendingTransferCount(items: List<DownloadItem>): Int =
        items.count { it.status == DownloadStatus.Queued || it.status == DownloadStatus.Downloading }

    /**
     * True when the queue has just grown past [window] - the moment to explain that iOS only keeps
     * that many moving while Nuvio is suspended. A null [previous] is the first look after the store
     * loaded (app start, a queue already that long) and is never a crossing.
     */
    fun crossesHandoverWindow(previous: Int?, current: Int, window: Int): Boolean =
        previous != null && previous <= window && current > window

    /**
     * "Choose manually" is offered only when the item's source list holds at least one source that
     * could become a download. Nothing cached and no sources mean there is nothing to choose, and
     * offering the list anyway is the Phase 8 dead end (a list of rows that all fail).
     */
    fun offersChooseManually(kind: DownloadEntryDecisionKind?, hasUsableSources: Boolean?): Boolean = when (kind) {
        DownloadEntryDecisionKind.NOTHING_CACHED,
        DownloadEntryDecisionKind.NO_SOURCES,
        -> false
        DownloadEntryDecisionKind.OVER_LIMIT,
        DownloadEntryDecisionKind.RESOLUTION_MISSING,
        DownloadEntryDecisionKind.MANUAL_PICK,
        -> true
        // Entries from before stage 6 carry no kind: keep their old behaviour unless the
        // discovery recorded that nothing usable exists.
        null -> hasUsableSources != false
    }

    /** "Check again" re-runs discovery; it is only honest where the answer can change by itself. */
    fun offersCheckAgain(kind: DownloadEntryDecisionKind?): Boolean =
        kind == DownloadEntryDecisionKind.NOTHING_CACHED || kind == DownloadEntryDecisionKind.NO_SOURCES

    sealed interface FreeSpaceVerdict {
        data object Fits : FreeSpaceVerdict

        /**
         * The batch is larger than the free space. [fitCount] items, taken in queue order, fit;
         * "Download what fits" queues exactly those.
         */
        data class TooBig(val neededBytes: Long, val freeBytes: Long, val fitCount: Int) : FreeSpaceVerdict
    }

    /**
     * Checked before a batch starts. An unknown free space (0 or less: the platform could not say)
     * never blocks, and an unknown size counts as nothing - the transfer's own storage check still
     * stops a file that does not fit.
     */
    fun freeSpace(sizesInQueueOrder: List<Long?>, freeBytes: Long): FreeSpaceVerdict {
        if (freeBytes <= 0L) return FreeSpaceVerdict.Fits
        val needed = sizesInQueueOrder.sumOf { it?.coerceAtLeast(0L) ?: 0L }
        if (needed <= freeBytes) return FreeSpaceVerdict.Fits
        var used = 0L
        var fit = 0
        for (size in sizesInQueueOrder) {
            val next = used + (size?.coerceAtLeast(0L) ?: 0L)
            if (next > freeBytes) break
            used = next
            fit += 1
        }
        return FreeSpaceVerdict.TooBig(neededBytes = needed, freeBytes = freeBytes, fitCount = fit)
    }

    /** One season in the whole-show chooser. Season 0 is Specials. */
    data class SeasonChoice(val season: Int, val episodeCount: Int, val unwatchedCount: Int)

    /** "All": every numbered season. Specials only when tapped on their own. */
    fun allSeasons(seasons: List<SeasonChoice>): Set<Int> =
        seasons.filter { it.season != 0 && it.episodeCount > 0 }.mapTo(linkedSetOf()) { it.season }

    /** "Unwatched": the numbered seasons with something left to watch. */
    fun unwatchedSeasons(seasons: List<SeasonChoice>): Set<Int> =
        seasons.filter { it.season != 0 && it.unwatchedCount > 0 }.mapTo(linkedSetOf()) { it.season }

    /**
     * The chooser has one selection model: the ticked seasons, and whether "episodes" means the
     * unwatched ones or all of them. A season with nothing left to watch adds nothing under
     * Unwatched, so it cannot be ticked there.
     */
    fun isSelectable(choice: SeasonChoice, unwatchedOnly: Boolean): Boolean =
        if (unwatchedOnly) choice.unwatchedCount > 0 else choice.episodeCount > 0

    /** Whether Unwatched / All episodes is a real choice: only once the show has been started. */
    fun offersUnwatchedMode(seasons: List<SeasonChoice>): Boolean =
        seasons.any { it.season != 0 && it.unwatchedCount < it.episodeCount } && unwatchedSeasons(seasons).isNotEmpty()

    /**
     * Switching between Unwatched and All episodes keeps the ticked seasons, except that
     * Unwatched drops those with nothing left to watch; if that leaves none, it ticks every
     * season that has something unwatched.
     */
    fun selectionForMode(seasons: List<SeasonChoice>, selected: Set<Int>, unwatchedOnly: Boolean): Set<Int> {
        if (!unwatchedOnly) return selected
        val kept = selected.filterTo(linkedSetOf()) { season ->
            seasons.firstOrNull { it.season == season }?.let { isSelectable(it, unwatchedOnly = true) } == true
        }
        return kept.ifEmpty { unwatchedSeasons(seasons) }
    }

    /** "Select all" in the current mode. Specials still only when tapped on their own. */
    fun selectAll(seasons: List<SeasonChoice>, unwatchedOnly: Boolean): Set<Int> =
        if (unwatchedOnly) unwatchedSeasons(seasons) else allSeasons(seasons)

    /**
     * What the whole-show chooser opens with: someone part-way through a show wants what they
     * have not seen ("Unwatched"); someone who has not started it wants all of it.
     */
    fun defaultSeasonSelection(seasons: List<SeasonChoice>): Pair<Set<Int>, Boolean> {
        val started = seasons.any { it.season != 0 && it.unwatchedCount < it.episodeCount }
        val unwatched = unwatchedSeasons(seasons)
        return if (started && unwatched.isNotEmpty()) unwatched to true else allSeasons(seasons) to false
    }

    /** How many episodes a selection downloads, before anything already downloaded is removed. */
    fun episodeCount(seasons: List<SeasonChoice>, selected: Set<Int>, unwatchedOnly: Boolean): Int =
        seasons.filter { it.season in selected }
            .sumOf { if (unwatchedOnly) it.unwatchedCount else it.episodeCount }

    /**
     * The Assisted sheet pre-selects the preferred resolution; Best available pre-selects the
     * highest. When the preferred one is missing, the nearest below it, else the nearest above.
     */
    fun preselectedHeight(available: List<Int>, preferred: DownloadResolutionPreference): Int? {
        if (available.isEmpty()) return null
        if (preferred == DownloadResolutionPreference.BEST_AVAILABLE) return available.max()
        val target = preferred.height
        if (target in available) return target
        return available.filter { it < target }.maxOrNull() ?: available.filter { it > target }.minOrNull()
    }

    /**
     * Assisted "Choose now" - a quality before any source is found: the resolutions a preference
     * can name, highest first. Which of them exist is unknown until discovery ends; one that is
     * missing then goes through the user's resolution fallback like Automatic's.
     */
    val earlyChoiceHeights: List<Int> = listOf(2160, 1080, 720)

    /** The early sheet pre-selects the preferred resolution; Best available is the highest. */
    fun earlyPreselectedHeight(preferred: DownloadResolutionPreference): Int =
        if (preferred == DownloadResolutionPreference.BEST_AVAILABLE) earlyChoiceHeights.first() else preferred.height

    /** The preference an early choice of [height] stands for when discovery ends. */
    fun preferenceForHeight(height: Int): DownloadResolutionPreference = when {
        height >= 2160 -> DownloadResolutionPreference.P2160
        height >= 1080 -> DownloadResolutionPreference.P1080
        else -> DownloadResolutionPreference.P720
    }

    /** "~16–33 GB": an estimate, in whole gigabytes from 10 GB, never more precise than it is. */
    fun sizeRangeLabel(range: LongRange): String {
        val low = range.first / 1_000_000_000.0
        val high = range.last / 1_000_000_000.0
        fun gb(value: Double): String = if (high >= 10.0) {
            kotlin.math.round(value).toLong().toString()
        } else {
            (kotlin.math.round(value * 10.0) / 10.0).toString().removeSuffix(".0")
        }
        if (high < 1.0) {
            val lowMb = kotlin.math.round(low * 1000.0).toLong().coerceAtLeast(1L)
            val highMb = kotlin.math.round(high * 1000.0).toLong().coerceAtLeast(1L)
            return if (lowMb == highMb) "~$highMb MB" else "~$lowMb–$highMb MB"
        }
        val lowText = gb(low)
        val highText = gb(high)
        return if (lowText == highText) "~$highText GB" else "~$lowText–$highText GB"
    }

    /** "4K", "1080p" - the resolution's name as the sheet and the toast say it. */
    fun resolutionLabel(height: Int): String = when {
        height >= 4320 -> "8K"
        height >= 2160 -> "4K"
        height >= 1440 -> "1440p"
        else -> "${height}p"
    }

    /** "2.1 GB", "850 MB". Decimal units, like the size levels. */
    fun sizeLabel(bytes: Long): String {
        val gb = bytes / 1_000_000_000.0
        if (gb >= 1.0) {
            val tenths = kotlin.math.round(gb * 10.0) / 10.0
            val text = if (tenths >= 100.0) kotlin.math.round(gb).toLong().toString() else tenths.toString()
            return "${text.removeSuffix(".0")} GB"
        }
        val mb = kotlin.math.round(bytes / 1_000_000.0).toLong().coerceAtLeast(1L)
        return "$mb MB"
    }
}
