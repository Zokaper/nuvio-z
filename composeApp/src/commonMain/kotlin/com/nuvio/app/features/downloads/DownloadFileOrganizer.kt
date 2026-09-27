package com.nuvio.app.features.downloads

/**
 * Moves finished downloads from the flat downloads folder into [DownloadFileLayout]'s
 * title / season hierarchy (Phase 9 closeout). The same step runs when a transfer completes and,
 * as the migration of existing libraries, every time the store loads.
 *
 * **Safety rules:**
 * - Only [DownloadStatus.Completed] items whose file sits directly in the downloads folder are
 *   touched. A running, queued, paused or failed download - and its `.part` file - never is, so
 *   nothing moves under an active transfer or an iOS background task.
 * - The new `localFileUri` is **persisted before the rename**. A crash between the two leaves the
 *   store pointing at a path that does not exist yet, and the platform's existing fallback to
 *   `<downloads>/<fileName>` finds the unmoved file on the next load; the next run moves it again.
 * - A rename that fails puts the old URI back. Nothing is reported as moved that was not.
 * - Rename only, inside one folder tree: never a copy, never an overwrite.
 * - Idempotent: an organized file is not flat any more, so it is never picked again.
 */
internal object DownloadFileOrganizer {

    /** The file operations the organizer needs; the platform downloader in production. */
    interface FileOps {
        /** [localFileUri] as a '/'-separated path under the downloads folder, or null when outside it. */
        fun relativePathOf(localFileUri: String?): String?
        fun exists(relativePath: String): Boolean
        fun fileUriFor(relativePath: String): String?
        /** Renames the file at [localFileUri] to [relativePath]; false (and nothing moved) on failure. */
        fun move(localFileUri: String, relativePath: String): Boolean
    }

    /** One planned move: [item] goes from [fromUri] to [relativePath] ([toUri]). */
    data class Move(val itemId: String, val fromUri: String, val relativePath: String, val toUri: String)

    internal var fileOps: FileOps = PlatformFileOps

    /** Whether [item]'s finished file still sits flat in the downloads folder. */
    fun needsOrganizing(item: DownloadItem, ops: FileOps): Boolean {
        if (item.status != DownloadStatus.Completed) return false
        val uri = item.localFileUri?.takeIf { it.isNotBlank() } ?: return false
        val relative = ops.relativePathOf(uri) ?: return false
        return '/' !in relative && ops.exists(relative)
    }

    /**
     * The moves that would organize [items] (every download on the device), limited to [ids] when
     * given. Pure apart from [FileOps] reads. Each planned target counts as taken for the next.
     */
    fun plan(
        items: List<DownloadItem>,
        ids: Collection<String>?,
        yearOf: (DownloadItem) -> Int?,
        ops: FileOps,
    ): List<Move> {
        val placed = items.mapNotNull { item ->
            ops.relativePathOf(item.localFileUri)
                ?.takeIf { '/' in it }
                ?.let { DownloadFileLayout.Placed(item.parentMetaId, it) }
        }.toMutableList()
        return items
            .filter { ids == null || it.id in ids }
            .filter { needsOrganizing(it, ops) }
            .mapNotNull { item ->
                val relative = DownloadFileLayout.target(item, yearOf(item), placed, ops::exists)
                val toUri = ops.fileUriFor(relative) ?: return@mapNotNull null
                placed += DownloadFileLayout.Placed(item.parentMetaId, relative)
                Move(item.id, item.localFileUri!!, relative, toUri)
            }
    }

    /**
     * Carries out [moves]: [persist] records every new URI first (one write), then each file is
     * renamed, and [persist] runs again with the old URI for every rename that failed. Returns the
     * moves that failed.
     */
    fun apply(
        moves: List<Move>,
        ops: FileOps,
        persist: (Map<String, Pair<String, String>>) -> Unit,
    ): List<Move> {
        if (moves.isEmpty()) return emptyList()
        persist(moves.associate { it.itemId to (it.fromUri to it.toUri) })
        val failed = moves.filterNot { ops.move(it.fromUri, it.relativePath) }
        if (failed.isNotEmpty()) {
            persist(failed.associate { it.itemId to (it.toUri to it.fromUri) })
        }
        return failed
    }

    /**
     * Organizes the finished downloads among [ids] (every one when null). Call with
     * [DownloadStore.lock] held: the store and the disk change together.
     */
    fun organizeLocked(ids: Collection<String>? = null) {
        val ops = fileOps
        val items = DownloadStore.allItems
        if (items.none { (ids == null || it.id in ids) && it.status == DownloadStatus.Completed }) return
        // Only for a film's year. The metadata store never takes the download lock, so no inversion.
        val titles = runCatching {
            DownloadTitleMetadataStore.ensureLoaded()
            DownloadTitleMetadataStore.titles.value
        }.getOrDefault(emptyMap())
        val moves = runCatching {
            plan(items, ids, { DownloadFileLayout.yearOf(titles[it.parentMetaId]?.releaseInfo) }, ops)
        }.getOrElse { error ->
            DownloadDiagnostics.note("file_organize_skipped", "error=${error::class.simpleName}")
            return
        }
        val failed = apply(moves, ops) { changes ->
            // Swap only a URI that is still the one this step expects; anything else changed it since.
            val updated = DownloadStore.allItems.map { item ->
                val (expected, next) = changes[item.id] ?: return@map item
                if (item.localFileUri == expected) item.copy(localFileUri = next) else item
            }
            if (updated != DownloadStore.allItems) DownloadStore.publishLocked(updated, immediate = true)
        }
        if (moves.isNotEmpty()) {
            DownloadDiagnostics.note("file_organized", "moved=${moves.size - failed.size} failed=${failed.size}")
        }
    }

    private object PlatformFileOps : FileOps {
        override fun relativePathOf(localFileUri: String?): String? =
            DownloadsPlatformDownloader.relativePathOf(localFileUri)

        override fun exists(relativePath: String): Boolean =
            DownloadsPlatformDownloader.existsInDownloads(relativePath)

        override fun fileUriFor(relativePath: String): String? =
            DownloadsPlatformDownloader.fileUriFor(relativePath)

        override fun move(localFileUri: String, relativePath: String): Boolean =
            DownloadsPlatformDownloader.moveCompletedFile(localFileUri, relativePath)
    }
}
