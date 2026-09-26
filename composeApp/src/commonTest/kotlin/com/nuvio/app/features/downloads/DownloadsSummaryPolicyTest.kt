package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadsSummaryPolicyTest {
    private fun item(
        id: String,
        status: DownloadStatus,
        season: Int? = 1,
        episode: Int? = id.filter(Char::isDigit).toIntOrNull(),
        parent: String = "tt1442437",
        activity: DownloadActivity? = null,
        downloaded: Long = 0L,
        total: Long? = null,
        rank: Long = episode?.toLong() ?: 0L,
    ) = DownloadItem(
        id = id,
        contentType = if (season == null) "movie" else "series",
        parentMetaId = parent,
        parentMetaType = if (season == null) "movie" else "series",
        videoId = "$parent:$season:$episode",
        title = "Modern Family",
        seasonNumber = season,
        episodeNumber = if (season == null) null else episode,
        streamTitle = "s",
        providerName = "p",
        fileName = "$id.mkv",
        status = status,
        activity = activity,
        downloadedBytes = downloaded,
        totalBytes = total,
        queuePosition = rank,
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
    )

    @Test
    fun nothingUnfinishedMeansNoNotification() {
        assertNull(DownloadsSummaryPolicy.summarize(listOf(item("e1", DownloadStatus.Completed)), emptyList()))
    }

    @Test
    fun theSummaryLeadsWithTheFirstTransferringItemAndCountsTheRest() {
        val summary = DownloadsSummaryPolicy.summarize(
            listOf(
                item("e3", DownloadStatus.Queued),
                item("e2", DownloadStatus.Downloading, activity = DownloadActivity.TRANSFERRING, downloaded = 50, total = 200),
                item("e1", DownloadStatus.Downloading, activity = DownloadActivity.RESOLVING_SOURCE),
            ),
            emptyList(),
        )!!
        assertEquals(2, summary.downloadingCount)
        assertEquals(1, summary.waitingCount)
        assertEquals("e2", summary.head?.id)
        // The whole queue, not the head's own 25%: three episodes, sizes unknown for two, so each
        // counts as one and e2 contributes its quarter - 8%.
        assertEquals(3, summary.progress?.memberCount)
        assertEquals(8, summary.progress?.percent)
        assertTrue(summary.singleSelection)
        assertNull(summary.waitingReason)
    }

    @Test
    fun anUnknownTotalGivesAnIndeterminateBarNotZero() {
        val summary = DownloadsSummaryPolicy.summarize(
            listOf(item("e1", DownloadStatus.Downloading, activity = DownloadActivity.TRANSFERRING, downloaded = 10)),
            emptyList(),
        )!!
        assertNull(summary.progress?.percent)
    }

    @Test
    fun nothingMovingSaysWhy() {
        val offline = DownloadsSummaryPolicy.summarize(
            listOf(
                item("e1", DownloadStatus.Queued, activity = DownloadActivity.WAITING_FOR_CONNECTION),
                item("e2", DownloadStatus.Queued, activity = DownloadActivity.QUEUED_FOR_SLOT),
            ),
            emptyList(),
        )!!
        assertEquals(DownloadWaitReason.CONNECTION, offline.waitingReason)
        val retrying = DownloadsSummaryPolicy.summarize(
            listOf(item("e1", DownloadStatus.Queued, activity = DownloadActivity.RETRY_BACKOFF)),
            emptyList(),
        )!!
        assertEquals(DownloadWaitReason.RETRYING_SHORTLY, retrying.waitingReason)
    }

    // `.54`: Pause all removed the notification, so four unfinished episodes left the shade.
    @Test
    fun aPausedQueueKeepsItsNotificationAndSaysSo() {
        val summary = DownloadsSummaryPolicy.summarize(
            listOf(
                item("e1", DownloadStatus.Completed, downloaded = 100, total = 100),
                item("e2", DownloadStatus.Paused, downloaded = 50, total = 100),
                item("e3", DownloadStatus.Paused, total = 100),
            ),
            emptyList(),
        )!!
        assertTrue(summary.isPausedOnly)
        assertEquals(2, summary.pausedCount)
        assertEquals(50, summary.progress?.percent)
        assertNull(summary.head)
    }

    @Test
    fun somethingStillMovingIsNotPausedOnly() {
        val summary = DownloadsSummaryPolicy.summarize(
            listOf(item("e1", DownloadStatus.Paused), item("e2", DownloadStatus.Queued)),
            emptyList(),
        )!!
        assertFalse(summary.isPausedOnly)
        assertEquals(1, summary.pausedCount)
    }

    @Test
    fun onlyFailedOrFinishedWorkStillRemovesTheNotification() {
        assertNull(
            DownloadsSummaryPolicy.summarize(
                listOf(item("e1", DownloadStatus.Completed), item("e2", DownloadStatus.Failed)),
                emptyList(),
            ),
        )
    }

    @Test
    fun aSeasonAnnouncesItselfOnlyWhenItsLastEpisodeFinishes() {
        val before = listOf(item("e1", DownloadStatus.Completed), item("e2", DownloadStatus.Downloading))
        val midway = listOf(item("e1", DownloadStatus.Completed), item("e2", DownloadStatus.Completed), item("e3", DownloadStatus.Queued))
        assertTrue(DownloadsSummaryPolicy.newlyCompletedGroups(before, midway).isEmpty())

        val done = listOf(item("e1", DownloadStatus.Completed), item("e2", DownloadStatus.Completed), item("e3", DownloadStatus.Completed))
        assertEquals(
            listOf(CompletedDownloadGroup("tt1442437", "Modern Family", 1)),
            DownloadsSummaryPolicy.newlyCompletedGroups(midway, done),
        )
    }

    @Test
    fun aFailedEpisodeKeepsTheSeasonFromAnnouncingCompletion() {
        val before = listOf(item("e1", DownloadStatus.Downloading), item("e2", DownloadStatus.Failed))
        val after = listOf(item("e1", DownloadStatus.Completed), item("e2", DownloadStatus.Failed))
        assertTrue(DownloadsSummaryPolicy.newlyCompletedGroups(before, after).isEmpty())
    }

    @Test
    fun alreadyCompletedItemsAreNeverAnnouncedAgain() {
        val done = listOf(item("e1", DownloadStatus.Completed))
        assertTrue(DownloadsSummaryPolicy.newlyCompletedGroups(done, done).isEmpty())
    }

    @Test
    fun aFilmAnnouncesItselfOnCompletion() {
        val before = listOf(item("m", DownloadStatus.Downloading, season = null, parent = "tt0111161"))
        val after = listOf(item("m", DownloadStatus.Completed, season = null, parent = "tt0111161"))
        assertEquals(
            listOf(CompletedDownloadGroup("tt0111161", "Modern Family", null)),
            DownloadsSummaryPolicy.newlyCompletedGroups(before, after),
        )
    }
}
