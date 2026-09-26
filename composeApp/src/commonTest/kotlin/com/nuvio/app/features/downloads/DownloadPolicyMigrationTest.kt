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
        // The smallest level admitting the preset's limit, so nothing gets worse on upgrade:
        // Saver 0.75 at 720p -> Standard (Small is 0.6).
        assertEquals(DownloadSizeLevel.MEDIUM, saver.sizeLevel)
        val balanced = DownloadPolicyMigration.fromPresets(DownloadPreset.Balanced)
        assertEquals(DownloadResolutionPreference.P1080, balanced.preferredResolution)
        // 1.5 GB/h at 1080p is above Small (1.2): Standard, the recommended level.
        assertEquals(DownloadSizeLevel.MEDIUM, balanced.sizeLevel)
        val uhdLow = DownloadPolicyMigration.fromPresets(DownloadPreset.UltraHdLow)
        assertEquals(DownloadResolutionPreference.P2160, uhdLow.preferredResolution)
        assertEquals(DownloadSizeLevel.MEDIUM, uhdLow.sizeLevel)
        // 15 GB/h is above 4K Large (14): Huge (18), still below REMUX.
        val uhdHigh = DownloadPolicyMigration.fromPresets(DownloadPreset.UltraHdHigh)
        assertEquals(DownloadSizeLevel.HUGE, uhdHigh.sizeLevel)
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
