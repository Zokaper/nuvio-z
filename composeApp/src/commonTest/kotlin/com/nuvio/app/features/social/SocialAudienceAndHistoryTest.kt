package com.nuvio.app.features.social

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SocialAudienceAndHistoryTest {
    private val everyone = SocialAudience()

    @Test fun everyoneLetsEveryFriendIn() = assertTrue(everyone.allows("b"))

    @Test fun bannerRequiresHttpsAndAllowsTrimming() {
        assertTrue(isValidSocialBannerUrl(" https://example.test/banner.jpg "))
        assertFalse(isValidSocialBannerUrl("http://example.test/banner.jpg"))
        assertFalse(isValidSocialBannerUrl("https://"))
        assertFalse(isValidSocialBannerUrl("https://example.test/a b.jpg"))
        assertFalse(isValidSocialBannerUrl("https://example.test/" + "x".repeat(2048)))
    }

    @Test fun exceptKeepsItsListOut() {
        val audience = SocialAudience(SocialAudienceMode.Except, except = listOf("b"))
        assertFalse(audience.allows("b"))
        assertTrue(audience.allows("c"))
    }

    @Test fun onlyLetsItsListIn() {
        val audience = SocialAudience(SocialAudienceMode.Only, only = listOf("c"))
        assertTrue(audience.allows("c"))
        assertFalse(audience.allows("b"))
    }

    @Test fun keepingOneFriendOutOfEveryoneIsExceptJustThem() {
        // An older, inactive except list is not revived (mirrors social_set_audience_member).
        val audience = SocialAudience(except = listOf("old")).withMember("b", allowed = false)
        assertEquals(SocialAudience(SocialAudienceMode.Except, except = listOf("b")), audience)
    }

    @Test fun lettingOneFriendIntoNobodyIsOnlyThem() {
        val audience = SocialAudience(SocialAudienceMode.Nobody, only = listOf("old")).withMember("c", allowed = true)
        assertEquals(SocialAudience(SocialAudienceMode.Only, only = listOf("c")), audience)
    }

    @Test fun emptiedListsSayWhatTheyMean() {
        assertEquals(SocialAudienceMode.Everyone,
            SocialAudience(SocialAudienceMode.Except, except = listOf("b")).withMember("b", allowed = true).mode)
        assertEquals(SocialAudienceMode.Nobody,
            SocialAudience(SocialAudienceMode.Only, only = listOf("c")).withMember("c", allowed = false).mode)
        assertEquals(SocialAudienceMode.Nobody, everyone.withMode(SocialAudienceMode.Only, emptyList()).mode)
    }

    @Test fun eachModeRemembersItsOwnList() {
        val both = SocialAudience(SocialAudienceMode.Except, except = listOf("b"), only = listOf("c"))
        assertEquals(listOf("c"), both.withMode(SocialAudienceMode.Only).members)
        assertEquals(listOf("b"), both.withMode(SocialAudienceMode.Only).withMode(SocialAudienceMode.Except).members)
    }

    private fun run(id: String, content: String, last: String, season: Int? = null, episode: Int? = null) = RecentActivityRun(
        runId = id,
        profile = SocialProfileSummary(profileId = "f", handle = "faisal", displayName = "faisal"),
        contentId = content,
        contentType = if (season != null) "series" else "movie",
        title = content,
        season = season,
        episode = episode,
        firstEventTime = last,
        lastEventTime = last,
    )

    @Test fun historyIsOneShelfPerMonthNewestFirst() {
        val months = friendHistoryMonths(
            listOf(
                run("1", "sp", "2026-10-23T20:00:00Z", 2, 6),
                run("2", "burning", "2026-10-21T20:00:00Z"),
                run("3", "sp", "2026-10-20T20:00:00Z", 2, 5),
                run("4", "void", "2026-09-28T20:00:00Z"),
                run("5", "sp", "2026-09-01T20:00:00Z", 1, 1),
            ),
            utcOffsetMs = 0L,
        )
        assertEquals(listOf(2026 to 10, 2026 to 9), months.map { it.year to it.month })
        // A binge within a month is one tile; the same show in another month is that month's tile.
        assertEquals(listOf("sp", "burning"), months[0].entries.map { it.group.contentId })
        assertEquals(2, months[0].entries[0].group.runs.size)
        assertEquals(listOf("void", "sp"), months[1].entries.map { it.group.contentId })
        assertEquals(23, months[0].entries[0].day)
    }

    @Test fun theMonthIsTheViewersMonth() {
        // 23:30 UTC on 31 October is November in UTC+1.
        val months = friendHistoryMonths(listOf(run("1", "m", "2026-10-31T23:30:00Z")), utcOffsetMs = 3_600_000L)
        assertEquals(2026 to 11, months.single().year to months.single().month)
        assertEquals(1, months.single().entries.single().day)
    }

    @Test fun unreadableTimesGoLast() {
        val months = friendHistoryMonths(listOf(run("1", "x", "not a time"), run("2", "y", "2026-01-02T00:00:00Z")), 0L)
        assertEquals(listOf(2026 to 1, 0 to 0), months.map { it.year to it.month })
    }

    @Test fun civilDateCrossesLeapDays() {
        assertEquals(Triple(2024, 2, 29), civilDate(1_709_164_800_000L))
        assertEquals(Triple(1969, 12, 31), civilDate(-1L))
    }
}
