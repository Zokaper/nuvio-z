package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadTransferTest {

    @Test
    fun meaningfulProgressScalesWithTheFileAtBothEnds() {
        // A flat figure is wrong at both ends. On a 60 GB remux 16 MiB is 0.03%, so a source
        // could inch through the whole file and never be called stalled.
        assertEquals(600_000_000L, meaningfulProgressBytes(60_000_000_000L))
        // On a small episode the flat figure is most of the download, and a bar the transfer
        // cannot clear is the same stuck row in different clothes. This is the case that
        // matters: the harness serves 6 MiB files, and a 16 MiB floor would mean no retry
        // there could ever refresh its budget.
        val small = 6L * 1024L * 1024L
        assertEquals(small / 4L, meaningfulProgressBytes(small))
        assertTrue(meaningfulProgressBytes(small) < small)
        // In between, the flat figure stands.
        assertEquals(MEANINGFUL_PROGRESS_BYTES, meaningfulProgressBytes(400L * 1024L * 1024L))
        // Nothing to scale against.
        assertEquals(MEANINGFUL_PROGRESS_BYTES, meaningfulProgressBytes(null))
        assertEquals(MEANINGFUL_PROGRESS_BYTES, meaningfulProgressBytes(0L))
        // Never zero, or every callback would count as progress and the budget would never
        // be spent - the exact fault this was written to close.
        assertTrue(meaningfulProgressBytes(1L) >= 1L)
    }

    @Test
    fun contentRangeTotalIsReadFromBothSatisfiedAndUnsatisfiedForms() {
        assertEquals(1_000L, parseContentRangeTotal("bytes 100-199/1000"))
        // The form a 416 carries; recognising it is what lets an already complete
        // partial file be finalized instead of downloaded again from zero.
        assertEquals(1_000L, parseContentRangeTotal("bytes */1000"))
        assertNull(parseContentRangeTotal("bytes 100-199/*"))
        assertNull(parseContentRangeTotal("bytes 100-199"))
        assertNull(parseContentRangeTotal(""))
        assertNull(parseContentRangeTotal(null))
    }

    @Test
    fun contentRangeWinsOverContentLength() {
        val total = resolveTotalBytes(
            startingBytes = 400L,
            isPartialResume = true,
            contentRangeHeader = "bytes 400-999/1000",
            contentLength = 600L,
        )
        assertEquals(1_000L, total)
    }

    @Test
    fun contentLengthOnAResumeIsAddedToTheBytesAlreadyOnDisk() {
        // Content-Length describes only the bytes still to come, so treating it as the
        // whole file would make a resumed download look far smaller than it is.
        val total = resolveTotalBytes(
            startingBytes = 400L,
            isPartialResume = true,
            contentRangeHeader = null,
            contentLength = 600L,
        )
        assertEquals(1_000L, total)
    }

    @Test
    fun contentLengthFromByteZeroIsTheWholeFile() {
        val total = resolveTotalBytes(
            startingBytes = 0L,
            isPartialResume = false,
            contentRangeHeader = null,
            contentLength = 1_000L,
        )
        assertEquals(1_000L, total)
    }

    @Test
    fun unknownSizeStaysUnknown() {
        assertNull(
            resolveTotalBytes(
                startingBytes = 0L,
                isPartialResume = false,
                contentRangeHeader = null,
                contentLength = null,
            ),
        )
    }

    @Test
    fun aTruncatedTransferIsNotComplete() {
        // The bug this guards: the stream ending was treated as success, so a transfer
        // cut short was reported as a finished download at whatever it had reached.
        val completion = assertIs<DownloadCompletion.Short>(
            evaluateCompletion(downloadedBytes = 600L, totalBytes = 1_000L),
        )
        assertEquals(600L, completion.downloadedBytes)
        assertEquals(1_000L, completion.expectedBytes)
    }

    @Test
    fun anExactTransferIsComplete() {
        assertEquals(
            DownloadCompletion.Complete,
            evaluateCompletion(downloadedBytes = 1_000L, totalBytes = 1_000L),
        )
    }

    @Test
    fun anOverlongTransferPoisonsThePartialFile() {
        val completion = assertIs<DownloadCompletion.Overrun>(
            evaluateCompletion(downloadedBytes = 1_200L, totalBytes = 1_000L),
        )
        assertEquals(1_200L, completion.downloadedBytes)
        assertEquals(1_000L, completion.expectedBytes)
    }

    @Test
    fun withoutAKnownTotalTheTransferIsAccepted() {
        assertEquals(
            DownloadCompletion.Complete,
            evaluateCompletion(downloadedBytes = 600L, totalBytes = null),
        )
        assertEquals(
            DownloadCompletion.Complete,
            evaluateCompletion(downloadedBytes = 600L, totalBytes = 0L),
        )
    }

    @Test
    fun deadSourcesAreNotRetriedAndTransientOnesAre() {
        // A signed link that has expired, moved address or had its token rotated
        // answers with these, and the file behind it is still obtainable.
        assertEquals(DownloadFailureReason.SourceExpired, failureReasonForHttpStatus(401))
        assertEquals(DownloadFailureReason.SourceExpired, failureReasonForHttpStatus(403))
        assertEquals(DownloadFailureReason.SourceExpired, failureReasonForHttpStatus(404))
        assertEquals(DownloadFailureReason.SourceExpired, failureReasonForHttpStatus(410))
        assertEquals(DownloadFailureReason.Fatal, failureReasonForHttpStatus(400))
        assertEquals(DownloadFailureReason.Fatal, failureReasonForHttpStatus(451))
        assertEquals(DownloadFailureReason.Transient, failureReasonForHttpStatus(429))
        assertEquals(DownloadFailureReason.Transient, failureReasonForHttpStatus(500))
        assertEquals(DownloadFailureReason.Transient, failureReasonForHttpStatus(503))
    }

    @Test
    fun retriesStopAtTheBudgetAndNeverHappenForFatalFailures() {
        assertTrue(shouldRetry(DownloadFailureReason.Transient, attempt = 1))
        assertTrue(shouldRetry(DownloadFailureReason.Incomplete, attempt = MAX_DOWNLOAD_ATTEMPTS - 1))
        assertFalse(shouldRetry(DownloadFailureReason.Transient, attempt = MAX_DOWNLOAD_ATTEMPTS))
        assertFalse(shouldRetry(DownloadFailureReason.Fatal, attempt = 1))
    }

    @Test
    fun anExpiredLinkIsOnlyWorthRetryingWhenItCanBeMintedAgain() {
        // Without an origin every retry would replay the same dead URL, which is what
        // left debrid downloads failed with a retry button that could never work.
        assertFalse(
            shouldRetry(DownloadFailureReason.SourceExpired, attempt = 1, canReresolveSource = false),
        )
        assertTrue(
            shouldRetry(DownloadFailureReason.SourceExpired, attempt = 1, canReresolveSource = true),
        )
        assertTrue(
            shouldRetry(
                DownloadFailureReason.SourceExpired,
                attempt = MAX_SOURCE_RERESOLVE_ATTEMPTS,
                canReresolveSource = true,
            ),
        )
        // A link that cannot be re-minted this many times is a file that is gone.
        assertFalse(
            shouldRetry(
                DownloadFailureReason.SourceExpired,
                attempt = MAX_SOURCE_RERESOLVE_ATTEMPTS + 1,
                canReresolveSource = true,
            ),
        )
    }

    @Test
    fun aSourceStillPreparingTheFileGetsALongerBudget() {
        // A debrid queue outlasts the network-blip budget many times over.
        assertTrue(
            shouldRetry(DownloadFailureReason.SourceNotReady, attempt = MAX_DOWNLOAD_ATTEMPTS),
        )
        assertFalse(
            shouldRetry(DownloadFailureReason.SourceNotReady, attempt = MAX_SOURCE_NOT_READY_ATTEMPTS),
        )
        assertTrue(
            retryBackoffMs(1, DownloadFailureReason.SourceNotReady) >=
                retryBackoffMs(4, DownloadFailureReason.Transient),
            "waiting on someone else's queue should back off further than a network blip",
        )
    }

    @Test
    fun backoffGrowsAndThenLevelsOff() {
        val reasons = listOf(
            DownloadFailureReason.Transient,
            DownloadFailureReason.SourceNotReady,
            DownloadFailureReason.SourceExpired,
        )
        for (reason in reasons) {
            val delays = (1..6).map { retryBackoffMs(it, reason) }
            delays.zipWithNext { earlier, later ->
                assertTrue(later >= earlier, "backoff must not shrink for $reason: $delays")
            }
            assertTrue(delays.first() >= 1_000L)
            assertEquals(delays.last(), retryBackoffMs(99, reason))
        }
    }

    @Test
    fun aDebridPlaceholderIsNotAcceptedAsTheDownload() {
        // The reported case: a 23.4 KB "your download is queued" video standing in
        // for a 5.2 GB episode. It is a complete, valid, playable MP4, so only its
        // size against what the source advertised gives it away.
        assertTrue(isImplausiblySmallForMedia(finalBytes = 23_965L, expectedBytes = 5_200_000_000L))
        // Same placeholder, but nothing was advertised: the floor still catches it.
        assertTrue(isImplausiblySmallForMedia(finalBytes = 23_965L, expectedBytes = null))
        assertTrue(isImplausiblySmallForMedia(finalBytes = 23_965L, expectedBytes = 0L))
    }

    @Test
    fun realDownloadsAreNotMistakenForPlaceholders() {
        assertFalse(
            isImplausiblySmallForMedia(finalBytes = 5_200_000_000L, expectedBytes = 5_200_000_000L),
        )
        // Sources routinely advertise a size a little off from what they serve.
        assertFalse(
            isImplausiblySmallForMedia(finalBytes = 4_800_000_000L, expectedBytes = 5_200_000_000L),
        )
        assertFalse(isImplausiblySmallForMedia(finalBytes = 700_000_000L, expectedBytes = null))
        // Right at the floor with nothing advertised is accepted, not rejected.
        assertFalse(
            isImplausiblySmallForMedia(
                finalBytes = MIN_PLAUSIBLE_MEDIA_BYTES,
                expectedBytes = null,
            ),
        )
    }

    @Test
    fun etagIsPreferredOverLastModifiedAsTheResumeValidator() {
        assertEquals("\"abc\"", resumeValidator("\"abc\"", "Wed, 21 Oct 2015 07:28:00 GMT"))
        assertEquals(
            "Wed, 21 Oct 2015 07:28:00 GMT",
            resumeValidator(null, "Wed, 21 Oct 2015 07:28:00 GMT"),
        )
        assertEquals("\"abc\"", resumeValidator("  \"abc\"  ", null))
        assertNull(resumeValidator(null, null))
        assertNull(resumeValidator("   ", "  "))
    }

    @Test
    fun progressIsReportedOnEitherAByteOrATimeThreshold() {
        assertTrue(
            shouldReportProgress(
                downloadedBytes = PROGRESS_MIN_BYTE_DELTA,
                lastReportedBytes = 0L,
                nowEpochMs = 0L,
                lastReportedAtEpochMs = 0L,
            ),
        )
        assertTrue(
            shouldReportProgress(
                downloadedBytes = 1L,
                lastReportedBytes = 0L,
                nowEpochMs = PROGRESS_MIN_INTERVAL_MS,
                lastReportedAtEpochMs = 0L,
            ),
        )
        // A 16 KiB chunk arriving milliseconds after the last report used to rewrite the
        // entire downloads payload to disk.
        assertFalse(
            shouldReportProgress(
                downloadedBytes = 16L * 1024L,
                lastReportedBytes = 0L,
                nowEpochMs = 10L,
                lastReportedAtEpochMs = 0L,
            ),
        )
    }
}
