package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PartyReadinessReconcileTest {
    @Test fun loadedPlayerRepublishesAfterBackgroundHeartbeatExpiry() {
        // Before lock: player opened, server ready. During suspension: heartbeat expires and the
        // server reopens the fetching gate. Foreground sees the same loaded media as before.
        val mediaLoadedBeforeLock = true
        assertFalse(partyReadinessNeedsReconcile(mediaLoadedBeforeLock, SourceResolutionState.ready))
        assertTrue(partyReadinessNeedsReconcile(mediaLoadedBeforeLock, SourceResolutionState.disconnected))
        assertTrue(partyReadinessNeedsReconcile(mediaLoadedBeforeLock, SourceResolutionState.fetching))
        assertFalse(partyReadinessNeedsReconcile(mediaLoadedBeforeLock, SourceResolutionState.ready))
    }

    @Test fun unresolvedMediaIsNeverPromotedByRecovery() {
        assertFalse(partyReadinessNeedsReconcile(false, SourceResolutionState.fetching))
        assertFalse(partyReadinessNeedsReconcile(false, SourceResolutionState.disconnected))
    }
}
