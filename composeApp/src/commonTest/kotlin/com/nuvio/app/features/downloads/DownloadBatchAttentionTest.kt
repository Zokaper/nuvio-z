package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamDebridCacheStatus
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a batch entry in "Needs your attention" actually needs.
 *
 * `.46` offered one bulk approve for every entry awaiting approval, including sources the
 * debrid service had not cached. The resolver answers "not cached" for those from the
 * addon's snapshot without asking the service again, so an approved one sat at "Waiting
 * for provider" until it failed. Those need a different source, not an approval.
 */
class DownloadBatchAttentionTest {
    private val addon = AddonSourceKey("a", "https://a/manifest.json")

    @Test fun uncachedDebridApprovalNeedsASourceNotAnApproval() {
        val entry = entry(approval(SourceFacts(isDebridReady = false)))
        assertTrue(entry.selectsUncachedDebrid)
        assertTrue(entry.needsManualSource)
        assertFalse(entry.canBeApproved)
    }

    @Test fun originReportedNotCachedAlsoNeedsASource() {
        val entry = entry(approval(SourceFacts(), origin = origin(StreamDebridCacheState.NOT_CACHED)))
        assertTrue(entry.needsManualSource)
        assertFalse(entry.canBeApproved)
    }

    @Test fun unknownSizeApprovalCanStillBeApproved() {
        val entry = entry(approval(SourceFacts(isDebridReady = true)))
        assertTrue(entry.canBeApproved)
        assertFalse(entry.needsManualSource)
    }

    @Test fun cachedOriginIsNotTreatedAsUncached() {
        val entry = entry(approval(SourceFacts(), origin = origin(StreamDebridCacheState.CACHED)))
        assertTrue(entry.canBeApproved)
        assertFalse(origin(StreamDebridCacheState.CACHED).isKnownUncached())
    }

    @Test fun skippedAndFailedEntriesNeedASource() {
        listOf(DownloadBatchEntryState.SKIPPED, DownloadBatchEntryState.FAILED).forEach { state ->
            val entry = DownloadBatchEntry(id = "e", videoId = "v", title = "E", state = state)
            assertTrue(entry.needsManualSource, "$state")
            assertFalse(entry.canBeApproved, "$state")
        }
    }

    @Test fun queuedEntriesNeedNothing() {
        val entry = entry(approval(SourceFacts(isDebridReady = false))).copy(state = DownloadBatchEntryState.QUEUED)
        assertFalse(entry.needsManualSource)
        assertFalse(entry.canBeApproved)
    }

    @Test fun missingOriginIsNotKnownUncached() {
        assertFalse((null as DownloadSourceOrigin?).isKnownUncached())
    }

    private fun entry(selection: SourceSelectionResult) = DownloadBatchEntry(
        id = "e1",
        videoId = "tt1:1:5",
        title = "Episode 5",
        state = DownloadBatchEntryState.APPROVAL_NEEDED,
        selection = selection,
    )

    private fun approval(facts: SourceFacts, origin: DownloadSourceOrigin? = null) =
        SourceSelectionResult.ApprovalNeeded(
            facts = facts,
            addonKey = addon,
            calculatedCapBytes = 1L,
            reason = "test",
            sourceOrigin = origin,
        )

    private fun origin(state: StreamDebridCacheState) = DownloadSourceOrigin(
        stream = StreamItem(
            addonName = "a",
            addonId = "a",
            debridCacheStatus = StreamDebridCacheStatus(providerId = "rd", providerName = "RD", state = state),
        ),
    )
}
