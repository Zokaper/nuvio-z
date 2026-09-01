package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchPartyModelsTest {
    @Test fun expectedPositionUsesServerTimeAndSpeed() {
        assertEquals(3_000, expectedPartyPositionMs(1_000, 10_000, 11_000, WatchPartyStatus.playing, 2f))
        assertEquals(1_000, expectedPartyPositionMs(1_000, 10_000, 11_000, WatchPartyStatus.paused, 2f))
    }

    @Test fun driftPolicyUsesDeadbandSpeedAndSeek() {
        assertEquals(DriftCorrectionKind.NONE, partyDriftCorrection(1_000, 1_700, 1f).kind)
        assertEquals(1.03f, partyDriftCorrection(1_000, 2_000, 1f).temporarySpeed)
        assertEquals(DriftCorrectionKind.SEEK, partyDriftCorrection(1_000, 4_000, 1f).kind)
    }

    @Test fun durationCompatibilityUsesLargerTolerance() {
        assertTrue(arePartyDurationsCompatible(7_200_000, 7_300_000))
        assertFalse(arePartyDurationsCompatible(3_600_000, 3_800_000))
    }

    @Test fun infoHashMatchWins() {
        val host = SourceFingerprint(infoHash="ABC",fileIndex=1,releaseFingerprint="x")
        val same = SourceFingerprint(infoHash="abc",fileIndex=1,releaseFingerprint="different")
        assertEquals(10_000, sourceFingerprintMatchScore(host,same))
    }
}
