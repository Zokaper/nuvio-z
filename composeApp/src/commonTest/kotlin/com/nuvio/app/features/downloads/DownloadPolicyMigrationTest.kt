package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadPolicyMigrationTest {
    @Test
    fun migrationNeverAnswersTheModeForTheUser() {
        DownloadPreset.BuiltIns.forEach { preset ->
            assertNull(DownloadPolicyMigration.fromPresets(preset).mode, "${preset.name} set a mode")
        }
        assertNull(DownloadPolicyMigration.fromPresets(null).mode)
    }

    @Test
    fun eachBuiltInPresetLandsOnTheExpectedResolutionAndLevel() {
        val saver = DownloadPolicyMigration.fromPresets(DownloadPreset.Saver)
        assertEquals(DownloadResolutionPreference.P720, saver.preferredResolution)
        val balanced = DownloadPolicyMigration.fromPresets(DownloadPreset.Balanced)
        assertEquals(DownloadResolutionPreference.P1080, balanced.preferredResolution)
        // 1.5 GB/h sits exactly between Small (1) and Medium (2): a tie goes to the larger level,
        // so nobody's downloads get worse on upgrade.
        assertEquals(DownloadSizeLevel.MEDIUM, balanced.sizeLevel)
        val uhdLow = DownloadPolicyMigration.fromPresets(DownloadPreset.UltraHdLow)
        assertEquals(DownloadResolutionPreference.P2160, uhdLow.preferredResolution)
        assertEquals(DownloadSizeLevel.MEDIUM, uhdLow.sizeLevel)
        val uhdHigh = DownloadPolicyMigration.fromPresets(DownloadPreset.UltraHdHigh)
        assertEquals(DownloadSizeLevel.LARGE, uhdHigh.sizeLevel)
    }

    @Test
    fun noPresetHistoryMigratesAsBalanced() {
        assertEquals(DownloadPolicyMigration.fromPresets(DownloadPreset.Balanced), DownloadPolicyMigration.fromPresets(null))
    }

    @Test
    fun presetRangeAndSizeRuleCarryOver() {
        val preset = DownloadPreset.Balanced.copy(
            dynamicRangePolicy = DynamicRangePolicy.AVOID_HDR,
            sizePreference = SizePreference.SMALLEST,
        )
        val policy = DownloadPolicyMigration.fromPresets(preset)
        assertEquals(DownloadRange.AVOID_HDR, policy.range)
        assertEquals(DownloadPickRule.SMALLEST_THAT_FITS, policy.pickRule)
    }

    @Test
    fun theSyncPayloadRoundTrips() {
        val policy = DownloadPolicy(
            mode = DownloadMode.ASSISTED,
            preferredResolution = DownloadResolutionPreference.BEST_AVAILABLE,
            sizeLevel = DownloadSizeLevel.HUGE,
            pickRule = DownloadPickRule.BALANCED,
            range = DownloadRange.PREFER_HDR,
            resolutionFallback = DownloadResolutionFallback.LOWER,
        )
        assertEquals(policy, policy.toSyncPayload().toPolicy())
    }

    @Test
    fun aNullRemoteFieldLeavesTheLocalValueAlone() {
        val local = DownloadPolicy(mode = DownloadMode.MANUAL, sizeLevel = DownloadSizeLevel.SMALL)
        val fromOlderBuild = DownloadPolicySyncPayload(sizeLevel = "LARGE")
        val merged = fromOlderBuild.toPolicy(base = local)
        assertEquals(DownloadMode.MANUAL, merged.mode)
        assertEquals(DownloadSizeLevel.LARGE, merged.sizeLevel)
    }

    @Test
    fun anUnknownValueFromANewerBuildFallsBackInsteadOfFailing() {
        val merged = DownloadPolicySyncPayload(sizeLevel = "GIGANTIC", mode = "TELEPATHIC").toPolicy(base = DownloadPolicy())
        assertEquals(DownloadPolicy(), merged)
    }
}
