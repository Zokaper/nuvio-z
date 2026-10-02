package com.nuvio.app.features.whatsnew

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WhatsNewSelectionTest {

    private val desktop = ChangelogPlatform.DESKTOP
    private val android = ChangelogPlatform.ANDROID
    private val ios = ChangelogPlatform.IOS
    private val all = setOf(desktop, android, ios)
    private val phones = setOf(android, ios)

    private fun entry(title: String, platforms: Set<ChangelogPlatform> = all, category: ChangelogCategory = ChangelogCategory.FEATURE) =
        ChangelogEntry(category, platforms, title)

    private fun ship(version: String, serial: Int, date: String? = null) = ChangelogShip(version, serial, date)

    /*
     * A history shaped like the real one:
     *  1 - a desktop-only release (desktop 132, released);
     *  2 - the big release: mobile 127 released, desktop 133 not yet;
     *  3 - an iOS-only hotfix (iOS 128, released);
     *  4 - a planned desktop release (desktop 134, unreleased).
     */
    private val events = listOf(
        ChangelogEvent(4, mapOf(desktop to ship("0.1.26-alpha-z2", 134)), listOf(entry("Planned desktop", setOf(desktop)))),
        ChangelogEvent(
            3,
            mapOf(ios to ship("0.5.4-z2", 128, "2026-10-20")),
            listOf(entry("iOS sign-in fix", setOf(ios), ChangelogCategory.FIX)),
        ),
        ChangelogEvent(
            2,
            mapOf(
                desktop to ship("0.1.26-alpha-z1", 133),
                android to ship("0.5.4-z1", 127, "2026-10-10"),
                ios to ship("0.5.4-z1", 127, "2026-10-10"),
            ),
            listOf(
                entry("Everywhere"),
                entry("Phones only", phones, ChangelogCategory.IMPROVEMENT),
                entry("Desktop only", setOf(desktop), ChangelogCategory.FIX),
            ),
            summary = "Big one",
        ),
        ChangelogEvent(1, mapOf(desktop to ship("0.1.23-alpha-z7", 132, "2026-10-02")), listOf(entry("What's New, rebuilt", setOf(desktop)))),
    )

    private val notes = listOf(ChangelogDebugNote(80, "eighty"), ChangelogDebugNote(81, "eighty-one"), ChangelogDebugNote(82, "eighty-two"))

    private fun viewer(platform: ChangelogPlatform, serial: Int, debug: Int? = null) = ChangelogViewer(platform, serial, debug)

    private fun seqs(list: List<ChangelogEvent>) = list.map { it.seq }

    private fun acked(vararg seqs: Int) = StoredWhatsNew(acknowledged = SeenEvents.NONE.plus(seqs.toList(), events.map { it.seq }))

    // ---- Eligibility ----

    @Test
    fun anEventShippingOnThisPlatformWaitsForThisPlatformsSerial() {
        // Mobile has released event 2; desktop 132 carries it in its bundle and must not show it.
        val big = events.first { it.seq == 2 }
        assertFalse(big.isEligibleFor(viewer(desktop, 132)))
        assertTrue(big.isEligibleFor(viewer(desktop, 133)))
        assertEquals(listOf(3, 1), seqs(eligibleEvents(events, viewer(desktop, 132))))
    }

    @Test
    fun anEventForOtherPlatformsAppearsOnceItIsReleasedSomewhere() {
        // The iOS hotfix (released) reaches desktop and Android; the planned desktop event reaches nobody else.
        assertEquals(listOf(3, 2, 1), seqs(eligibleEvents(events, viewer(desktop, 133))))
        assertEquals(listOf(3, 2, 1), seqs(eligibleEvents(events, viewer(android, 127))))
    }

    @Test
    fun anUnreleasedEventIsHiddenOnStableAndPreviewedOnlyByThisPlatformsDebugBuild() {
        assertFalse(events.first { it.seq == 4 }.isEligibleFor(viewer(desktop, 133)))
        assertTrue(events.first { it.seq == 4 }.isEligibleFor(viewer(desktop, 133, debug = 90)))
        // A debug build of another platform does not see another platform's plan.
        assertFalse(events.first { it.seq == 4 }.isEligibleFor(viewer(android, 127, debug = 76)))
        // Released on mobile but not yet on desktop: a desktop debug build previews it, a stable one does not.
        assertTrue(events.first { it.seq == 2 }.isEligibleFor(viewer(desktop, 132, debug = 82)))
        assertFalse(events.first { it.seq == 2 }.isEligibleFor(viewer(desktop, 132)))
    }

    @Test
    fun aDebugBuildPreviewsTheUpcomingReleaseItCompilesTowards() {
        val upcoming = listOf(ChangelogEvent(1, mapOf(desktop to ship("0.1.26-alpha-z1", 132)), listOf(entry("Big", setOf(desktop)))))
        assertEquals(listOf(1), seqs(eligibleEvents(upcoming, viewer(desktop, 131, debug = 82))))
        assertTrue(eligibleEvents(upcoming, viewer(desktop, 131)).isEmpty())
    }

    // ---- Post-update screen ----

    @Test
    fun multipleMissedEventsShowNewestFirstWithNoFamilyFilter() {
        val decision = decideWhatsNew(events, emptyList(), viewer(desktop, 133), acked(1))
        assertEquals(listOf(3, 2), seqs(decision.events))
        assertTrue(decision.shouldShow)
    }

    @Test
    fun continuingAcknowledgesAndViewsEverythingEligible() {
        val decision = decideWhatsNew(events, emptyList(), viewer(desktop, 133), acked(1))
        val again = decideWhatsNew(events, emptyList(), viewer(desktop, 133), StoredWhatsNew(decision.stateAfterContinue.acknowledged, decision.stateAfterContinue.viewed, 0))
        assertFalse(again.shouldShow)
        assertFalse(again.badge)
        // The planned event 4 is not eligible, so the floor stops below it.
        assertEquals(SeenEvents(3), decision.stateAfterContinue.acknowledged)
    }

    @Test
    fun anEventThatBecomesEligibleAfterAHigherOneIsStillShown() {
        // Desktop 132 saw event 1 and, once released, the iOS hotfix 3 - but not 2, which waits for 133.
        val atZ7 = decideWhatsNew(events, emptyList(), viewer(desktop, 132), acked(1))
        assertEquals(listOf(3), seqs(atZ7.events))
        val after = atZ7.stateAfterContinue
        assertTrue(3 in after.acknowledged)
        assertFalse(2 in after.acknowledged)
        val at133 = decideWhatsNew(events, emptyList(), viewer(desktop, 133), StoredWhatsNew(after.acknowledged, after.viewed, after.debugBuild))
        assertEquals(listOf(2), seqs(at133.events))
    }

    @Test
    fun debugNotesAreShownOnlyOnDebugBuildsAndOnlyNewerOnes() {
        val stored = acked(1, 2, 3).copy(debugBuild = 80)
        assertEquals(listOf(82, 81), decideWhatsNew(events, notes, viewer(desktop, 133, debug = 82), stored).debugNotes.map { it.build })
        assertTrue(decideWhatsNew(events, notes, viewer(desktop, 133), stored).debugNotes.isEmpty())
        assertEquals(82, decideWhatsNew(events, notes, viewer(desktop, 133, debug = 82), stored).stateAfterContinue.debugBuild)
    }

    // ---- Migration ----

    @Test
    fun aZ6DesktopUserWithOnlyTheLegacyKeySeesTheEventsTheyMissed() {
        val legacy = StoredWhatsNew(legacyLastSeenVersion = "0.4.13-z1")
        // Straight from z6 to the big release: both desktop events, and the iOS fix released meanwhile.
        val direct = decideWhatsNew(events, emptyList(), viewer(desktop, 133), legacy)
        assertEquals(WhatsNewOrigin.MIGRATED_LEGACY_VERSION, direct.origin)
        assertEquals(listOf(3, 2, 1), seqs(direct.events))
        // Through the intermediate release first, then on: nothing is shown twice.
        val first = decideWhatsNew(events, emptyList(), viewer(desktop, 132), legacy)
        assertEquals(listOf(3, 1), seqs(first.events))
        val second = decideWhatsNew(events, emptyList(), viewer(desktop, 133), StoredWhatsNew(first.stateAfterContinue.acknowledged, first.stateAfterContinue.viewed, 0))
        assertEquals(WhatsNewOrigin.CURRENT, second.origin)
        assertEquals(listOf(2), seqs(second.events))
    }

    @Test
    fun anAndroidPreAlphaUserSeesTheFirstReleaseButNotDesktopsOlderHistory() {
        val decision = decideWhatsNew(events, emptyList(), viewer(android, 127), StoredWhatsNew(legacyLastSeenVersion = "0.5.0-beta"))
        assertEquals(listOf(3, 2), seqs(decision.events))
        assertTrue(1 in decision.state.acknowledged)
    }

    @Test
    fun theLegacyKeyIsTrustedOnlyForBeingThere() {
        // Desktop wrote a stale mobile name into it; the value never decides anything.
        listOf("0.4.13-z1", "0.1.26-alpha-z1", "garbage").forEach { value ->
            assertEquals(listOf(3, 2, 1), seqs(decideWhatsNew(events, emptyList(), viewer(desktop, 133), StoredWhatsNew(legacyLastSeenVersion = value)).events))
        }
        // A blank value is no value: a fresh install.
        assertEquals(WhatsNewOrigin.FRESH_INSTALL, decideWhatsNew(events, emptyList(), viewer(desktop, 133), StoredWhatsNew(legacyLastSeenVersion = " ")).origin)
    }

    @Test
    fun aSerialAckFromADebugBuildCountsOnlyEventsBelowItsSerialAsSeen() {
        // Debug builds wrote ack_serial for the upcoming release whose drafts they showed.
        val decision = decideWhatsNew(events, emptyList(), viewer(desktop, 133), StoredWhatsNew(legacySerial = 133, debugBuild = 80))
        assertEquals(WhatsNewOrigin.MIGRATED_SERIAL, decision.origin)
        assertEquals(listOf(3, 2), seqs(decision.events))
        assertEquals(80, decision.state.debugBuild)
        val mobile = decideWhatsNew(events, emptyList(), viewer(android, 127), StoredWhatsNew(legacySerial = 127))
        assertEquals(listOf(3, 2), seqs(mobile.events))
    }

    @Test
    fun aSerialAckBeyondEveryEventMeansEverythingIsSeenAndANegativeOneIsIgnored() {
        assertFalse(decideWhatsNew(events, emptyList(), viewer(desktop, 133), StoredWhatsNew(legacySerial = 500)).shouldShow)
        assertEquals(WhatsNewOrigin.FRESH_INSTALL, decideWhatsNew(events, emptyList(), viewer(desktop, 133), StoredWhatsNew(legacySerial = -3)).origin)
    }

    @Test
    fun anAlreadyMigratedStateIgnoresTheLegacyKeys() {
        val stored = acked(1, 2, 3).copy(legacySerial = 0, legacyLastSeenVersion = "0.4.13-z1")
        val decision = decideWhatsNew(events, emptyList(), viewer(desktop, 133), stored)
        assertEquals(WhatsNewOrigin.CURRENT, decision.origin)
        assertFalse(decision.shouldShow)
    }

    @Test
    fun aMissingViewedSetDefaultsToTheAcknowledgedOne() {
        val decision = decideWhatsNew(events, emptyList(), viewer(desktop, 133), acked(1, 2, 3))
        assertFalse(decision.badge)
    }

    // ---- Fresh install and the Settings badge ----

    @Test
    fun aFreshInstallShowsNothingButBadgesSettings() {
        val decision = decideWhatsNew(events, notes, viewer(ios, 128, debug = 82), StoredWhatsNew())
        assertEquals(WhatsNewOrigin.FRESH_INSTALL, decision.origin)
        assertFalse(decision.shouldShow)
        assertTrue(decision.badge)
        assertEquals(82, decision.state.debugBuild)
        // The next launch, with the saved state, still shows nothing and still badges.
        val next = decideWhatsNew(events, notes, viewer(ios, 128, debug = 82), StoredWhatsNew(decision.state.acknowledged, decision.state.viewed, decision.state.debugBuild))
        assertFalse(next.shouldShow)
        assertTrue(next.badge)
    }

    @Test
    fun openingWhatsNewFromSettingsClearsTheBadgeAndNeverReplaysThoseEvents() {
        val fresh = decideWhatsNew(events, emptyList(), viewer(android, 127), StoredWhatsNew()).state
        val viewed = markWhatsNewViewed(fresh, events, viewer(android, 127))
        assertFalse(whatsNewBadge(viewed, events, viewer(android, 127)))
        // An upgrade that makes a new event eligible badges again and shows only that event.
        val withNew = listOf(
            ChangelogEvent(5, mapOf(android to ship("0.5.4-z3", 129, "2026-11-01"), ios to ship("0.5.4-z3", 129, "2026-11-01")), listOf(entry("Later", phones))),
        ) + events
        val decision = decideWhatsNew(withNew, emptyList(), viewer(android, 129), StoredWhatsNew(viewed.acknowledged, viewed.viewed, 0))
        assertEquals(listOf(5), seqs(decision.events))
        assertTrue(decision.badge)
    }

    @Test
    fun noEventsAtAllIsQuiet() {
        val decision = decideWhatsNew(emptyList(), emptyList(), viewer(desktop, 133), StoredWhatsNew(legacyLastSeenVersion = "x"))
        assertFalse(decision.shouldShow)
        assertFalse(decision.badge)
    }

    // ---- How an event reads on one platform ----

    @Test
    fun anEventSplitsIntoThisPlatformsEntriesAndTheRestWithoutDroppingAny() {
        val big = events.first { it.seq == 2 }
        val onDesktop = big.viewFor(desktop)
        assertEquals(listOf(ChangelogCategory.FEATURE, ChangelogCategory.FIX), onDesktop.own.map { it.first })
        assertEquals(listOf("Everywhere", "Desktop only"), onDesktop.own.flatMap { it.second }.map { it.title })
        assertEquals(listOf("Phones only"), onDesktop.otherDevices.map { it.title })
        val onIos = big.viewFor(ios)
        assertEquals(listOf("Everywhere", "Phones only"), onIos.own.flatMap { it.second }.map { it.title })
        assertEquals(listOf("Desktop only"), onIos.otherDevices.map { it.title })
        assertEquals(big.entries.size, onIos.own.sumOf { it.second.size } + onIos.otherDevices.size)
    }

    @Test
    fun theVersionLineListsOnlyWhereTheEventShippedInDisplayOrder() {
        assertEquals(listOf(desktop, android, ios), events.first { it.seq == 2 }.viewFor(android).versions.map { it.first })
        assertEquals(listOf(ios), events.first { it.seq == 3 }.viewFor(desktop).versions.map { it.first })
    }

    @Test
    fun theDateIsThisPlatformsOwnElseTheFirstReleaseElsewhere() {
        val big = events.first { it.seq == 2 }
        assertEquals("2026-10-10", big.viewFor(android).date)
        // Desktop has not released it: the date shown is mobile's.
        assertEquals("2026-10-10", big.viewFor(desktop).date)
        assertNull(events.first { it.seq == 4 }.viewFor(desktop).date)
    }

    @Test
    fun theReleaseGuardFindsTheEventByPlatformSerial() {
        assertEquals(2, changelogEventFor(events, desktop, 133)?.seq)
        assertEquals(2, changelogEventFor(events, ios, 127)?.seq)
        assertEquals(3, changelogEventFor(events, ios, 128)?.seq)
        assertNull(changelogEventFor(events, android, 128))
    }

    // ---- Seen sets ----

    @Test
    fun aSeenSetKeepsAFloorAndTheExceptionsAboveIt() {
        val known = listOf(1, 2, 3, 4, 5)
        val seen = SeenEvents.NONE.plus(listOf(1, 3, 5), known)
        assertEquals(SeenEvents(1, setOf(3, 5)), seen)
        assertEquals(SeenEvents(3, setOf(5)), seen.plus(listOf(2), known))
        assertEquals("3,5", SeenEvents.encodeAbove(setOf(5, 3)))
        assertEquals(setOf(3, 5), SeenEvents.decodeAbove("5, 3,x,-2,,0"))
        assertEquals(emptySet(), SeenEvents.decodeAbove(null))
    }

    // ---- Validation ----

    @Test
    fun aSoundChangelogValidates() {
        assertEquals(emptyList(), validateChangelog(events))
    }

    @Test
    fun validationRejectsTheStructuralMistakes() {
        val dup = events + ChangelogEvent(1, mapOf(desktop to ship("x", 1)), listOf(entry("x", setOf(desktop))))
        assertTrue(validateChangelog(dup).any { "duplicate seq 1" in it })
        assertTrue(validateChangelog(events.reversed()).any { "newest" in it })
        val backwards = listOf(
            ChangelogEvent(2, mapOf(desktop to ship("b", 131)), listOf(entry("b", setOf(desktop)))),
            ChangelogEvent(1, mapOf(desktop to ship("a", 132)), listOf(entry("a", setOf(desktop)))),
        )
        assertTrue(validateChangelog(backwards).any { "serial does not increase" in it })
        val stray = listOf(ChangelogEvent(1, mapOf(ios to ship("a", 1)), listOf(entry("a", setOf(desktop)))))
        assertTrue(validateChangelog(stray).any { "does not ship on" in it })
        val split = listOf(ChangelogEvent(1, mapOf(android to ship("a", 1), ios to ship("a", 2)), listOf(entry("a", phones))))
        assertTrue(validateChangelog(split).any { "different versions or serials" in it })
        val badDate = listOf(ChangelogEvent(1, mapOf(ios to ship("a", 1, "20 Oct")), listOf(entry("a", setOf(ios)))))
        assertTrue(validateChangelog(badDate).any { "YYYY-MM-DD" in it })
        val releasedAfterPlan = listOf(
            ChangelogEvent(2, mapOf(desktop to ship("b", 2, "2026-10-02")), listOf(entry("b", setOf(desktop)))),
            ChangelogEvent(1, mapOf(desktop to ship("a", 1)), listOf(entry("a", setOf(desktop)))),
        )
        assertTrue(validateChangelog(releasedAfterPlan).any { "released after unreleased" in it })
    }
}
