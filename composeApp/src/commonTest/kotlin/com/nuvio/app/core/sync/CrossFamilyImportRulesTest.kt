package com.nuvio.app.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrossFamilyImportRulesTest {
    @Test
    fun neverImportedPlayerKeysAreNeverOnTheAllowlist() {
        val leaked = CrossFamilyImportRules.PLAYER_KEYS intersect CrossFamilyImportRules.NEVER_IMPORTED_PLAYER_KEYS
        assertTrue(leaked.isEmpty(), "imported although device-specific: $leaked")
    }

    @Test
    fun qualityHdrAndTheSetupRevisionAreNeverCopiedAsPlayerKeys() {
        listOf(
            "playback_quality_ceiling_mbps",
            "playback_dynamic_range_policy",
            "playback_metered_cap_height",
            "setup_wizard_completed_revision",
        ).forEach { assertTrue(it !in CrossFamilyImportRules.PLAYER_KEYS, it) }
    }

    @Test
    fun navigationIsNeverImported() {
        val leaked = CrossFamilyImportRules.THEME_KEYS intersect CrossFamilyImportRules.NEVER_IMPORTED_THEME_KEYS
        assertTrue(leaked.isEmpty(), "$leaked")
    }

    @Test
    fun theProfileWidePreferencesDoCome() {
        listOf("playback_mode", "preferred_audio_language", "preferred_subtitle_language", "playback_language_strictness")
            .forEach { assertTrue(it in CrossFamilyImportRules.PLAYER_KEYS, it) }
        assertEquals(CrossFamilyImportMode.Whole, CrossFamilyImportRules.rule("social_features").mode)
        assertEquals(CrossFamilyImportMode.Whole, CrossFamilyImportRules.rule("tmdb_settings").mode)
        assertEquals(CrossFamilyImportMode.Whole, CrossFamilyImportRules.rule("download_policy").mode)
    }

    @Test
    fun tokenDependentAndPlatformOnlyFeaturesStayBehind() {
        assertEquals(CrossFamilyImportMode.Skip, CrossFamilyImportRules.rule("trakt_settings_payload").mode)
        assertEquals(CrossFamilyImportMode.Skip, CrossFamilyImportRules.rule("notifications_settings").mode)
        assertEquals(setOf("home_presentation"), CrossFamilyImportRules.rule("z_features").keys)
        assertEquals(CrossFamilyImportMode.AllButKeys, CrossFamilyImportRules.rule("meta_screen_settings_payload").mode)
        assertTrue("hoverPreviewEnabled" !in CrossFamilyImportRules.POSTER_KEYS)
    }

    @Test
    fun anUnknownFieldIsSkipped() {
        assertEquals(CrossFamilyImportMode.Skip, CrossFamilyImportRules.rule("something_new").mode)
    }

    @Test
    fun everyFieldHasOneRule() {
        val fields = CrossFamilyImportRules.rules.map { it.field }
        assertEquals(fields.toSet().size, fields.size)
    }

    @Test
    fun theImportedRevisionIsCappedAndNeedsAFinishedSetup() {
        assertEquals(10, CrossFamilyImportRules.importedRevision(10, currentRevision = 10))
        assertEquals(9, CrossFamilyImportRules.importedRevision(9, currentRevision = 10))
        assertEquals(10, CrossFamilyImportRules.importedRevision(12, currentRevision = 10))
        assertNull(CrossFamilyImportRules.importedRevision(7, currentRevision = 10))
        assertNull(CrossFamilyImportRules.importedRevision(null, currentRevision = 10))
    }

    @Test
    fun theFamiliesAreEachOthersOther() {
        assertEquals("desktop", CrossFamilyImportRules.otherFamily("mobile"))
        assertEquals("mobile", CrossFamilyImportRules.otherFamily("desktop"))
        assertNull(CrossFamilyImportRules.otherFamily("tv"))
    }
}
