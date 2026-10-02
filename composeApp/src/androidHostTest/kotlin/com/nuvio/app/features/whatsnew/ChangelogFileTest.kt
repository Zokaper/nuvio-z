package com.nuvio.app.features.whatsnew

import com.nuvio.app.core.build.AppVersionConfig
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The shipped global changelog parses whole, is structurally sound, and has an event for this build's
 * release serial on both phones - the same guard `scripts/check-changelog.py` applies in the release
 * workflow, run on every CI push so a bump without notes is red before anyone dispatches a release.
 * (Open `qa` markers are the release workflow's to refuse, not this test's: a debug line carries them.)
 */
class ChangelogFileTest {
    private val text = File("src/commonMain/composeResources/files/changelog.json").readText()
    private val events = ChangelogCatalog.parse(text)

    @Test
    fun theFileIsSoundAndNothingInItIsDroppedByTheLenientParser() {
        assertTrue(events.isNotEmpty())
        assertEquals(emptyList(), validateChangelog(events))
        // Leniency is for future files; the shipped one must parse whole.
        val rawEntries = Regex("\"category\"\\s*:").findAll(text).count()
        assertEquals(rawEntries, events.sumOf { it.entries.size })
        assertEquals(Regex("\"seq\"\\s*:").findAll(text).count(), events.size)
    }

    @Test
    fun thisBuildsReleaseSerialShipsAnEventOnBothPhones() {
        val android = changelogEventFor(events, ChangelogPlatform.ANDROID, AppVersionConfig.RELEASE_SERIAL)
        val ios = changelogEventFor(events, ChangelogPlatform.IOS, AppVersionConfig.RELEASE_SERIAL)
        assertNotNull(android, "changelog.json ships no Android event for RELEASE_SERIAL ${AppVersionConfig.RELEASE_SERIAL}")
        assertEquals(android, ios)
    }

    @Test
    fun theDebugNotesAreThisFamilysOwn() {
        val debug = File("src/commonMain/composeResources/files/changelog-debug.json").readText()
        val notes = ChangelogCatalog.parseDebug(debug, "mobile")
        assertTrue(notes.isNotEmpty())
        assertEquals(notes.size, notes.map { it.build }.toSet().size)
    }

    @Test
    fun theNextReleaseOffersAdvancedSetupOnEveryPlatform() {
        // Plan section 8: existing users meet Advanced Setup through the release's What's New card.
        val event = assertNotNull(changelogEventFor(events, ChangelogPlatform.ANDROID, AppVersionConfig.RELEASE_SERIAL))
        val card = event.entries.single { it.action == ChangelogAction.ADVANCED_SETUP }
        assertEquals(setOf(ChangelogPlatform.ANDROID, ChangelogPlatform.IOS, ChangelogPlatform.DESKTOP), card.platforms)
    }
}
