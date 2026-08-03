package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadTransferTest {

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
        assertEquals(DownloadFailureReason.Fatal, failureReasonForHttpStatus(404))
        assertEquals(DownloadFailureReason.Fatal, failureReasonForHttpStatus(403))
        assertEquals(DownloadFailureReason.Fatal, failureReasonForHttpStatus(410))
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
    fun backoffGrowsAndThenLevelsOff() {
        val delays = (1..6).map(::retryBackoffMs)
        delays.zipWithNext { earlier, later ->
            assertTrue(later >= earlier, "backoff must not shrink: $delays")
        }
        assertTrue(delays.first() >= 1_000L)
        assertEquals(delays.last(), retryBackoffMs(99))
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
