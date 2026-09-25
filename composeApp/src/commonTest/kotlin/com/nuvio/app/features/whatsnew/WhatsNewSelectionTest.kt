package com.nuvio.app.features.whatsnew

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WhatsNewSelectionTest {

    private val all = setOf(ChangelogPlatform.ANDROID, ChangelogPlatform.IOS, ChangelogPlatform.DESKTOP)
    private val phones = setOf(ChangelogPlatform.ANDROID, ChangelogPlatform.IOS)

    private fun entry(category: ChangelogCategory, title: String, platforms: Set<ChangelogPlatform> = all) =
        ChangelogEntry(category, platforms, title)

    private val releases = listOf(
        ChangelogRelease(
            "mobile", "0.4.13-z1", 127, "2026-09-01",
            listOf(entry(ChangelogCategory.FEATURE, "Instant is back", phones), entry(ChangelogCategory.FIX, "No loop", phones)),
        ),
        ChangelogRelease(
            "mobile", "0.4.14-z1", 128, "2026-10-01",
            listOf(
                entry(ChangelogCategory.FEATURE, "Download modes"),
                entry(ChangelogCategory.FIX, "Screen-off downloads", setOf(ChangelogPlatform.ANDROID)),
                entry(ChangelogCategory.IMPROVEMENT, "Clearer queue"),
            ),
            debug = listOf(ChangelogDebugNote(50, "Engine split"), ChangelogDebugNote(51, "Stage 7 polish")),
        ),
        ChangelogRelease("mobile", "0.4.15-z1", 129, "2026-11-01", listOf(entry(ChangelogCategory.FEATURE, "Later"))),
        ChangelogRelease("desktop", "0.1.23-alpha-z7", 132, "2026-10-01", listOf(entry(ChangelogCategory.FEATURE, "Desktop only"))),
    )

    private fun decide(
        serial: Int = 128,
        version: String = "0.4.14-z1",
        debug: Int? = null,
        ack: WhatsNewAck? = null,
        legacy: String? = null,
        platform: ChangelogPlatform = ChangelogPlatform.ANDROID,
    ) = decideWhatsNew(releases, "mobile", platform, serial, version, debug, ack, legacy)

    private fun WhatsNewDecision.titles() = sections.flatMap { s -> s.entries.map { it.entry.title } }

    @Test
    fun aFreshInstallShowsNothingAndAcknowledgesTheCurrentBuild() {
        val decision = decide(debug = 51)
        assertFalse(decision.shouldShow)
        assertEquals(WhatsNewAck(128, 51), decision.ackToWrite)
    }

    @Test
    fun anUpgradeFromTheOldKeyShowsOnlyTheCurrentRelease() {
        val decision = decide(legacy = "0.4.13-z1")
        assertEquals(listOf("Download modes", "Clearer queue", "Screen-off downloads"), decision.titles())
    }

    @Test
    fun theOldKeyNamingThisVersionShowsNothing() {
        assertFalse(decide(legacy = "0.4.14-z1").shouldShow)
    }

    @Test
    fun skippingReleasesMergesEverythingMissedByCategoryNewestFirst() {
        val decision = decide(serial = 129, version = "0.4.15-z1", ack = WhatsNewAck(127, 0))
        assertEquals(ChangelogCategoryOrder, decision.sections.map { it.category })
        val features = decision.sections.first { it.category == ChangelogCategory.FEATURE }.entries
        assertEquals(listOf("0.4.15-z1" to "Later", "0.4.14-z1" to "Download modes"), features.map { it.version to it.entry.title })
    }

    @Test
    fun entriesAreFilteredToThisPlatform() {
        val ios = decide(ack = WhatsNewAck(127, 0), platform = ChangelogPlatform.IOS)
        assertFalse("Screen-off downloads" in ios.titles())
        assertTrue("Screen-off downloads" in decide(ack = WhatsNewAck(127, 0)).titles())
    }

    @Test
    fun nothingNewShowsNothingAndAReleaseAheadOfTheBuildIsNeverShown() {
        assertFalse(decide(ack = WhatsNewAck(128, 0)).shouldShow)
        assertFalse("Later" in decide(ack = WhatsNewAck(127, 0)).titles())
    }

    @Test
    fun anotherFamilysReleasesAreNeverShown() {
        assertFalse("Desktop only" in decide(serial = 200, ack = WhatsNewAck(0, 0)).titles())
    }

    @Test
    fun aDebugBuildAddsOnlyItsUnseenDebugNotes() {
        val decision = decide(serial = 127, version = "0.4.13-z1", debug = 51, ack = WhatsNewAck(127, 50))
        assertEquals(listOf("Stage 7 polish"), decision.debugNotes.map { it.text })
        assertTrue(decision.sections.isEmpty())
        assertEquals(WhatsNewAck(127, 51), decision.ackToWrite)
    }

    @Test
    fun aStableBuildNeverShowsDebugNotes() {
        assertTrue(decide(ack = WhatsNewAck(127, 0)).debugNotes.isEmpty())
    }

    @Test
    fun aDowngradeNeverLowersTheAck() {
        val decision = decide(serial = 127, version = "0.4.13-z1", ack = WhatsNewAck(129, 60))
        assertFalse(decision.shouldShow)
        assertEquals(WhatsNewAck(129, 60), decision.ackToWrite)
    }

    @Test
    fun historyIsEveryShippedReleaseNewestFirst() {
        val history = changelogHistory(releases, "mobile", ChangelogPlatform.ANDROID, currentSerial = 128)
        assertEquals(listOf("0.4.14-z1", "0.4.13-z1"), history.map { it.first.version })
    }

    @Test
    fun theReleaseGuardWantsNotesForTheSerial() {
        assertTrue(changelogHasRelease(releases, "mobile", 128))
        assertFalse(changelogHasRelease(releases, "mobile", 130))
        assertFalse(changelogHasRelease(releases, "desktop", 128))
    }
}
