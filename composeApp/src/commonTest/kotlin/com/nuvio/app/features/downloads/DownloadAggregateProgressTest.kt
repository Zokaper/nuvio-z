package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A season's progress is the whole selection's (`.52`: "2 GB of 4 GB · 50%" for two running
 * episodes out of six, and a denominator that moved whenever one started or finished).
 */
class DownloadAggregateProgressTest {
    private val gb = 1_000_000_000L

    private fun ep(
        n: Int,
        status: DownloadStatus = DownloadStatus.Queued,
        downloaded: Long = 0L,
        total: Long? = null,
        expected: Long? = 2 * gb,
        created: Long = 100L,
        failure: DownloadFailureKind? = null,
    ) = DownloadItem(
        id = "e$n",
        ownerProfileId = 1,
        contentType = "series",
        parentMetaId = "tt1",
        parentMetaType = "series",
        videoId = "tt1:1:$n",
        title = "Lanterns",
        seasonNumber = 1,
        episodeNumber = n,
        streamTitle = "s",
        providerName = "p",
        fileName = "e$n.mkv",
        status = status,
        failureKind = failure,
        downloadedBytes = downloaded,
        totalBytes = total,
        expectedSizeBytes = expected,
        queuePosition = n.toLong(),
        createdAtEpochMs = created,
        updatedAtEpochMs = created,
    )

    private fun running(n: Int, downloaded: Long) =
        ep(n, DownloadStatus.Downloading, downloaded = downloaded, total = 2 * gb)

    private fun done(n: Int, created: Long = 100L) =
        ep(n, DownloadStatus.Completed, downloaded = 2 * gb, total = 2 * gb, created = created)

    private fun season(items: List<DownloadItem>, batches: List<DownloadBatch> = emptyList()) =
        assertNotNull(DownloadAggregate.forSeason("tt1", 1, items, batches))

    @Test
    fun twoRunningOutOfSixIsMeasuredAgainstAllSix() {
        val progress = season(listOf(running(1, 1 * gb), running(2, 1 * gb), ep(3), ep(4), ep(5), ep(6)))
        assertEquals(12 * gb, progress.expectedBytes)
        assertEquals(2 * gb, progress.downloadedBytes)
        assertEquals(16, progress.percent)
        assertEquals(0, progress.doneCount)
        assertEquals(6, progress.memberCount)
    }

    @Test
    fun theDenominatorDoesNotMoveAsEpisodesStartAndFinish() {
        val before = season(listOf(running(1, 1 * gb), running(2, 1 * gb), ep(3), ep(4), ep(5), ep(6)))
        val nextStarted = season(listOf(done(1), running(2, 1 * gb), running(3, 0L), ep(4), ep(5), ep(6)))
        assertEquals(before.expectedBytes, nextStarted.expectedBytes)
        assertEquals(1, nextStarted.doneCount)
        assertEquals(25, nextStarted.percent)
    }

    @Test
    fun anEpisodeThatNeedsTheUserStaysInTheDenominator() {
        val failed = ep(4, DownloadStatus.Failed, downloaded = 0L, failure = null)
        val progress = season(listOf(done(1), done(2), done(3), failed, done(5), done(6)))
        assertEquals(12 * gb, progress.expectedBytes)
        assertEquals(83, progress.percent)
        assertEquals(5, progress.doneCount)
    }

    @Test
    fun anEpisodeFinishedLongBeforeThisRunIsNotProgressTowardIt() {
        val lastMonth = done(1, created = 1L)
        val progress = season(listOf(lastMonth, running(2, 1 * gb), ep(3)))
        assertEquals(2, progress.memberCount)
        assertEquals(4 * gb, progress.expectedBytes)
    }

    @Test
    fun aBatchNamesItsFinishedEpisodesEvenIfTheyWereCreatedEarlier() {
        val batch = batch(listOf(entry(1, DownloadBatchEntryState.COMPLETED), entry(2, DownloadBatchEntryState.DOWNLOADING)))
        val progress = season(listOf(done(1, created = 1L), running(2, 1 * gb)), listOf(batch))
        assertEquals(2, progress.memberCount)
        assertEquals(75, progress.percent)
    }

    @Test
    fun entriesStillBeingPreparedCountAtTheirSourcesSize() {
        val batch = batch(
            listOf(
                entry(1, DownloadBatchEntryState.DOWNLOADING),
                entry(2, DownloadBatchEntryState.READY, size = 2 * gb),
            ),
        )
        val progress = season(listOf(running(1, 1 * gb)), listOf(batch))
        assertEquals(2, progress.memberCount)
        assertEquals(4 * gb, progress.expectedBytes)
        assertEquals(25, progress.percent)
    }

    @Test
    fun anUnknownSizeFallsBackToCountingEpisodes() {
        val progress = season(listOf(done(1), running(2, 1 * gb), ep(3, expected = null)))
        assertNull(progress.expectedBytes)
        // (1 + 0.5 + 0) of three.
        assertEquals(50, progress.percent)
    }

    @Test
    fun nothingMeasurableIsIndeterminateRatherThanZero() {
        val progress = season(listOf(ep(1, expected = null), ep(2, expected = null)))
        assertNull(progress.percent)
    }

    @Test
    fun anEstimateBelowWhatHasArrivedCannotPassAHundred() {
        val progress = season(listOf(ep(1, DownloadStatus.Downloading, downloaded = 3 * gb, expected = 2 * gb), ep(2)))
        assertEquals(5 * gb, progress.expectedBytes)
    }

    @Test
    fun theQueueAggregateSpansEverySelectionAndFilms() {
        val film = ep(0).copy(id = "m", parentMetaId = "tt9", videoId = "tt9", seasonNumber = null, episodeNumber = null, contentType = "movie")
        val progress = assertNotNull(DownloadAggregate.forQueue(listOf(running(1, 1 * gb), ep(2), done(3), film), emptyList()))
        assertEquals(4, progress.memberCount)
        assertEquals(8 * gb, progress.expectedBytes)
        assertEquals(3 * gb, progress.downloadedBytes)
    }

    @Test
    fun aLoneEpisodeHasNoAggregate() {
        assertNull(DownloadAggregate.forItem(running(1, 1 * gb), listOf(running(1, 1 * gb)), emptyList()))
    }

    private fun batch(entries: List<DownloadBatchEntry>) = DownloadBatch(
        id = "b",
        ownerProfileId = 1,
        scope = DownloadScope.Season(1),
        contentType = "series",
        parentMetaId = "tt1",
        parentMetaType = "series",
        title = "Lanterns",
        sourcePolicySnapshot = DownloadSourcePolicy(),
        entries = entries,
        createdAtEpochMs = 0L,
    )

    private fun entry(n: Int, state: DownloadBatchEntryState, size: Long? = null) = DownloadBatchEntry(
        id = "tt1:1:$n|1|$n",
        videoId = "tt1:1:$n",
        title = "E$n",
        season = 1,
        episode = n,
        state = state,
        selection = size?.let {
            SourceSelectionResult.Selected(
                streamUrl = "https://a/$n",
                facts = SourceFacts(sizeBytes = it),
                addonKey = AddonSourceKey("a", "https://a/manifest.json"),
                calculatedCapBytes = 10 * gb,
            )
        },
    )
}
