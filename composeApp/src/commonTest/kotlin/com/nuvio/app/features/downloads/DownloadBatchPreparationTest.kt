package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Downloads tab's Preparing section and the Android preparing notification are both
 * driven entirely by these two, so a state that is silently counted as finished shows
 * the user a batch that never stops preparing, or one that vanishes mid-discovery.
 */
class DownloadBatchPreparationTest {
    @Test
    fun onlyDiscoveringAndResolvingCountAsPreparing() {
        val preparingStates = DownloadBatchEntryState.entries.filter { it.isPreparing }

        assertEquals(
            listOf(DownloadBatchEntryState.DISCOVERING, DownloadBatchEntryState.RESOLVING),
            preparingStates,
        )
    }

    @Test
    fun aBatchPreparesUntilItsLastEntryResolves() {
        val batch = batch(
            entry(DownloadBatchEntryState.READY),
            entry(DownloadBatchEntryState.SKIPPED),
            entry(DownloadBatchEntryState.RESOLVING),
        )

        assertTrue(batch.isPreparing)
        assertEquals(2, batch.preparedEntryCount)
    }

    @Test
    fun aFailedEntryStillCountsAsPrepared() {
        // Failures are an outcome, not an unfinished step. Counting them as pending
        // would leave "3 of 4" on screen with nothing left to do.
        val batch = batch(
            entry(DownloadBatchEntryState.FAILED),
            entry(DownloadBatchEntryState.APPROVAL_NEEDED),
        )

        assertFalse(batch.isPreparing)
        assertEquals(2, batch.preparedEntryCount)
    }

    @Test
    fun anEmptyBatchIsNotPreparing() {
        val batch = batch()

        assertFalse(batch.isPreparing)
        assertEquals(0, batch.preparedEntryCount)
    }

    private fun entry(state: DownloadBatchEntryState): DownloadBatchEntry =
        DownloadBatchEntry(
            id = "entry-${state.name}",
            videoId = "video-${state.name}",
            title = state.name,
            season = 1,
            episode = state.ordinal + 1,
            state = state,
        )

    private fun batch(vararg entries: DownloadBatchEntry): DownloadBatch = DownloadBatch(
        id = "batch-1",
        scope = DownloadScope.Season(1),
        contentType = "series",
        parentMetaId = "tt0903747",
        parentMetaType = "series",
        title = "Show",
        presetSnapshot = DownloadPreset.Balanced,
        sourcePolicySnapshot = DownloadSourcePolicy(),
        entries = entries.toList(),
        createdAtEpochMs = 0L,
    )
}
