package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Moving finished downloads into the organized layout, and migrating flat libraries, against a
 * fake disk (Phase 9 closeout). The desktop E2E suite runs the same steps on real files.
 */
class DownloadFileOrganizerTest {

    /** A downloads folder: relative path -> file. URIs are "file:///dl/<relative>". */
    private class FakeDisk(files: Set<String>) : DownloadFileOrganizer.FileOps {
        val files = files.toMutableSet()
        val failingMoves = mutableSetOf<String>()
        val moves = mutableListOf<Pair<String, String>>()

        override fun relativePathOf(localFileUri: String?): String? =
            localFileUri?.takeIf { it.startsWith(ROOT) }?.removePrefix(ROOT)

        override fun exists(relativePath: String): Boolean =
            relativePath in files || files.any { it.startsWith("$relativePath/") }

        override fun fileUriFor(relativePath: String): String = ROOT + relativePath

        override fun move(localFileUri: String, relativePath: String): Boolean {
            val from = relativePathOf(localFileUri) ?: return false
            if (relativePath in failingMoves || from !in files || relativePath in files) return false
            files -= from
            files += relativePath
            moves += from to relativePath
            return true
        }
    }

    /** The store's items, updated the way [DownloadFileOrganizer.organizeLocked] updates them. */
    private class FakeStore(var items: List<DownloadItem>) {
        val writes = mutableListOf<List<DownloadItem>>()

        fun persist(changes: Map<String, Pair<String, String>>) {
            items = items.map { item ->
                val (expected, next) = changes[item.id] ?: return@map item
                if (item.localFileUri == expected) item.copy(localFileUri = next) else item
            }
            writes += items
        }
    }

    private fun item(
        episode: Int,
        status: DownloadStatus = DownloadStatus.Completed,
        flatName: String = "Modern Family S01E0${episode}_k3x.mkv",
        show: String = "tt-mf",
    ) = DownloadItem(
        id = "$show-1-$episode",
        contentType = "series",
        parentMetaId = show,
        parentMetaType = "series",
        videoId = "$show:1:$episode",
        title = "Modern Family",
        seasonNumber = 1,
        episodeNumber = episode,
        episodeTitle = "Episode $episode",
        streamTitle = "s",
        providerName = "p",
        fileName = flatName,
        localFileUri = if (status == DownloadStatus.Completed) ROOT + flatName else null,
        status = status,
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
    )

    private fun organize(store: FakeStore, disk: FakeDisk, ids: Collection<String>? = null): List<DownloadFileOrganizer.Move> {
        val moves = DownloadFileOrganizer.plan(store.items, ids, { null }, disk)
        return DownloadFileOrganizer.apply(moves, disk, store::persist)
    }

    @Test
    fun aFlatLibraryMigratesAndStaysPlayable() {
        val items = (1..3).map { item(it) }
        val disk = FakeDisk(items.map { it.fileName }.toSet())
        val store = FakeStore(items)

        val failed = organize(store, disk)

        assertTrue(failed.isEmpty())
        store.items.forEach { item ->
            val relative = disk.relativePathOf(item.localFileUri)!!
            assertEquals("Modern Family/Season 01/S01E0${item.episodeNumber} - Episode ${item.episodeNumber}.mkv", relative)
            assertTrue(relative in disk.files, "$relative is recorded but not on disk")
        }
        assertTrue(disk.files.none { '/' !in it }, "a flat file was left behind: ${disk.files}")
    }

    @Test
    fun theNewPathIsPersistedBeforeAnyFileMoves() {
        val items = listOf(item(1))
        val disk = FakeDisk(setOf(items[0].fileName))
        val store = FakeStore(items)
        val movesAtFirstWrite = mutableListOf<Int>()
        DownloadFileOrganizer.apply(DownloadFileOrganizer.plan(store.items, null, { null }, disk), disk) { changes ->
            movesAtFirstWrite += disk.moves.size
            store.persist(changes)
        }
        assertEquals(0, movesAtFirstWrite.first(), "a file moved before its new path was persisted")
    }

    @Test
    fun aFailedMoveKeepsTheOldPathUsable() {
        val items = (1..2).map { item(it) }
        val disk = FakeDisk(items.map { it.fileName }.toSet())
        disk.failingMoves += "Modern Family/Season 01/S01E02 - Episode 2.mkv"
        val store = FakeStore(items)

        val failed = organize(store, disk)

        assertEquals(listOf("tt-mf-1-2"), failed.map { it.itemId })
        val second = store.items.single { it.episodeNumber == 2 }
        assertEquals(ROOT + items[1].fileName, second.localFileUri, "a failed move must leave the old path")
        assertTrue(items[1].fileName in disk.files)
        // The next run (the next launch) tries again and succeeds.
        disk.failingMoves.clear()
        assertTrue(organize(store, disk).isEmpty())
        assertEquals("Modern Family/Season 01/S01E02 - Episode 2.mkv", disk.relativePathOf(store.items.single { it.episodeNumber == 2 }.localFileUri))
    }

    @Test
    fun organizingIsIdempotent() {
        val items = (1..2).map { item(it) }
        val disk = FakeDisk(items.map { it.fileName }.toSet())
        val store = FakeStore(items)
        organize(store, disk)
        val after = store.items
        val writes = store.writes.size

        assertTrue(DownloadFileOrganizer.plan(store.items, null, { null }, disk).isEmpty())
        organize(store, disk)
        assertEquals(after, store.items)
        assertEquals(writes, store.writes.size, "a second run wrote the store")
    }

    @Test
    fun anInterruptedMoveIsFinishedOnTheNextRun() {
        // Crash after the store was written, before the rename: the store points at the new path,
        // the file is still flat. The platform's fallback to <downloads>/<fileName> resolves it on
        // load (DownloadStore.normalizeCompletedLocalFileUri), which puts the flat URI back...
        val original = item(1)
        val disk = FakeDisk(setOf(original.fileName))
        val reloaded = FakeStore(listOf(original.copy(localFileUri = ROOT + original.fileName)))
        // ...and the load-time run moves it.
        assertTrue(organize(reloaded, disk).isEmpty())
        assertEquals(setOf("Modern Family/Season 01/S01E01 - Episode 1.mkv"), disk.files)
    }

    @Test
    fun activeAndUnfinishedDownloadsAreNeverTouched() {
        val completed = item(1)
        val statuses = listOf(DownloadStatus.Downloading, DownloadStatus.Queued, DownloadStatus.Paused, DownloadStatus.Failed)
        val others = statuses.mapIndexed { index, status -> item(index + 2, status = status) }
        // Their partial files sit flat too - and a stale URI on a failed row must not move it either.
        val disk = FakeDisk(setOf(completed.fileName) + others.map { "${it.fileName}.part" })
        val store = FakeStore(listOf(completed) + others)

        val moves = DownloadFileOrganizer.plan(store.items, null, { null }, disk)

        assertEquals(listOf(completed.id), moves.map { it.itemId })
        organize(store, disk)
        others.forEach { assertTrue("${it.fileName}.part" in disk.files) }
        assertEquals(others, store.items.drop(1))
    }

    @Test
    fun aMissingFileIsLeftForTheMissingFileMessage() {
        val item = item(1)
        val store = FakeStore(listOf(item))
        assertTrue(DownloadFileOrganizer.plan(store.items, null, { null }, FakeDisk(emptySet())).isEmpty())
    }

    @Test
    fun onlyTheCompletedDownloadIsMovedWhenOneFinishes() {
        val items = (1..3).map { item(it) }
        val disk = FakeDisk(items.map { it.fileName }.toSet())
        val store = FakeStore(items)
        organize(store, disk, ids = listOf("tt-mf-1-2"))
        assertEquals(1, disk.moves.size)
        assertEquals(items[0].localFileUri, store.items[0].localFileUri)
    }

    @Test
    fun twoProfilesWithTheSameEpisodeGetTwoFiles() {
        val first = item(1)
        val second = item(1, flatName = "Modern Family S01E01_k4y.mkv").copy(id = "profile-2", ownerProfileId = 2)
        val disk = FakeDisk(setOf(first.fileName, second.fileName))
        val store = FakeStore(listOf(first, second))
        organize(store, disk)
        assertEquals(
            setOf("Modern Family/Season 01/S01E01 - Episode 1.mkv", "Modern Family/Season 01/S01E01 - Episode 1 (2).mkv"),
            disk.files,
        )
        assertEquals(2, store.items.map { it.localFileUri }.toSet().size)
    }

    @Test
    fun aRowChangedMeanwhileIsNotOverwritten() {
        val store = FakeStore(listOf(item(1)))
        store.persist(mapOf("tt-mf-1-1" to ("file:///elsewhere" to ROOT + "x")))
        assertEquals(ROOT + item(1).fileName, store.items.single().localFileUri)
    }

    private companion object {
        const val ROOT = "file:///dl/"
    }
}
