package com.nuvio.app.features.social

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FriendActivityGroupingTest {
    private fun friend(id: String, name: String = id) =
        SocialProfileSummary(profileId = id, handle = id, displayName = name)

    private fun run(
        id: String,
        who: String,
        content: String = "tt-dd",
        title: String = "Daredevil",
        type: String = "series",
        season: Int? = 2,
        episode: Int? = 4,
        events: Int = 1,
        last: String = "2026-09-15T10:00:00Z",
        poster: String? = "p",
    ) = RecentActivityRun(
        runId = id,
        profile = friend(who, who.replaceFirstChar { it.uppercase() }),
        contentId = content,
        contentType = type,
        title = title,
        poster = poster,
        season = season,
        episode = episode,
        eventCount = events,
        firstEventTime = last,
        lastEventTime = last,
    )

    @Test fun oneFriendOneMovieIsOneRowReadingMovie() {
        val groups = groupFriendActivity(listOf(run("r1", "seraph", content = "tt-m", title = "Mayday", type = "movie", season = null, episode = null)))
        assertEquals(1, groups.size)
        assertEquals("Seraph", groups[0].friendNamesLabel())
        assertEquals("Movie", groups[0].contextLabel())
    }

    @Test fun aBingeSplitAcrossRunsIsOneRowWithASpan() {
        // The screenshot: Seraph's Daredevil arrived as two cards.
        val groups = groupFriendActivity(
            listOf(
                run("r2", "seraph", episode = 9, events = 3, last = "2026-09-15T12:00:00Z"),
                run("r1", "seraph", episode = 4, events = 3, last = "2026-09-14T20:00:00Z"),
            ),
        )
        assertEquals(1, groups.size)
        val g = groups[0]
        assertEquals(listOf("r2", "r1"), g.runs.map { it.runId })
        assertEquals(6, g.totalEvents)
        assertEquals("S2 E4–E9 · 6 episodes", g.contextLabel())
        assertEquals("Seraph", g.friendNamesLabel())
        assertEquals(9, g.latestRun.episode)
    }

    @Test fun threeFriendsOnOneTitleGroupMostRecentFirst() {
        val groups = groupFriendActivity(
            listOf(
                run("a", "debug", last = "2026-09-15T09:00:00Z"),
                run("b", "seraph", last = "2026-09-15T11:00:00Z"),
                run("c", "ahmed", last = "2026-09-15T10:00:00Z"),
                run("d", "seraph", last = "2026-09-15T08:00:00Z"),
            ),
        )
        val g = groups.single()
        assertEquals(listOf("seraph", "ahmed", "debug"), g.friends.map { it.profileId })
        assertEquals("Seraph + 2", g.friendNamesLabel())
        assertEquals(3, g.stackedFriends.size)
        assertEquals(0, g.avatarOverflow)
    }

    @Test fun twoFriendsReadAsAPairAndOverflowCounts() {
        val two = groupFriendActivity(listOf(run("a", "seraph"), run("b", "debug"))).single()
        assertEquals("Seraph & Debug", two.friendNamesLabel())
        val five = groupFriendActivity((1..5).map { run("r$it", "f$it") }).single()
        assertEquals(2, five.avatarOverflow)
    }

    @Test fun groupOrderFollowsServerOrderAndKeysAreStableAcrossPages() {
        val page1 = listOf(run("1", "seraph", content = "a"), run("2", "debug", content = "b"))
        val page2 = listOf(run("3", "ahmed", content = "a", last = "2026-09-10T00:00:00Z"), run("4", "x", content = "c"))
        val first = groupFriendActivity(page1).map { it.contentId }
        val both = groupFriendActivity(page1 + page2).map { it.contentId }
        assertEquals(listOf("a", "b"), first)
        assertEquals(listOf("a", "b", "c"), both)
    }

    @Test fun artworkFallsBackAcrossRunsAndBlankIsMissing() {
        val g = groupFriendActivity(listOf(run("1", "seraph", poster = " "), run("2", "debug", poster = "real", last = "2026-09-01T00:00:00Z"))).single()
        assertEquals("real", g.poster)
        val none = groupFriendActivity(listOf(run("1", "seraph", poster = null))).single()
        assertNull(none.poster)
    }

    @Test fun singleEpisodeHasNoCountAndCrossSeasonLeadsWithLatest() {
        assertEquals("S1 E5", groupFriendActivity(listOf(run("1", "s", season = 1, episode = 5))).single().contextLabel())
        val cross = groupFriendActivity(
            listOf(run("1", "s", season = 2, episode = 1, last = "2026-09-15T00:00:00Z"), run("2", "s", season = 1, episode = 8, last = "2026-09-14T00:00:00Z")),
        ).single()
        assertEquals("S2 E1 · 2 episodes", cross.contextLabel())
    }

    @Test fun parsesPostgrestTimestamps() {
        assertEquals(0L, parseSocialTimestampMs("1970-01-01T00:00:00Z"))
        assertEquals(1_789_459_200_000L, parseSocialTimestampMs("2026-09-15T08:00:00Z"))
        assertEquals(1_789_459_200_123L, parseSocialTimestampMs("2026-09-15T08:00:00.123456+00:00"))
        assertEquals(1_789_459_200_000L, parseSocialTimestampMs("2026-09-15T10:00:00+02:00"))
        assertEquals(1_789_459_200_000L, parseSocialTimestampMs("2026-09-15 08:00:00"))
        assertNull(parseSocialTimestampMs("yesterday"))
        assertNull(parseSocialTimestampMs("2026-13-15T08:00:00Z"))
    }

    @Test fun relativeTimeLabels() {
        val now = 1_000_000_000_000L
        assertEquals("Just now", relativeTimeLabel(now - 30_000, now))
        assertEquals("Just now", relativeTimeLabel(now + 60_000, now))
        assertEquals("5m", relativeTimeLabel(now - 5 * 60_000, now))
        assertEquals("2h", relativeTimeLabel(now - 2 * 3_600_000, now))
        assertEquals("3d", relativeTimeLabel(now - 3 * 86_400_000L, now))
        assertEquals("2w", relativeTimeLabel(now - 15 * 86_400_000L, now))
        assertEquals("2mo", relativeTimeLabel(now - 65 * 86_400_000L, now))
        assertEquals("", relativeTimeLabel(null, now))
    }

    @Test fun bucketsUseLocalCalendarDays() {
        val now = parseSocialTimestampMs("2026-09-15T08:00:00Z")!!
        fun at(s: String) = parseSocialTimestampMs(s)
        assertEquals(FriendActivityBucket.Today, friendActivityBucket(at("2026-09-15T00:30:00Z"), now))
        assertEquals(FriendActivityBucket.Yesterday, friendActivityBucket(at("2026-09-14T23:59:00Z"), now))
        // UTC+2: 23:30Z on the 14th is already 01:30 on the 15th locally.
        assertEquals(FriendActivityBucket.Today, friendActivityBucket(at("2026-09-14T23:30:00Z"), now, utcOffsetMs = 7_200_000L))
        // UTC-10: now is 22:00 on the 14th locally, and 00:30Z is 14:30 the same local day.
        assertEquals(FriendActivityBucket.Today, friendActivityBucket(at("2026-09-15T00:30:00Z"), now, utcOffsetMs = -36_000_000L))
        assertEquals(FriendActivityBucket.ThisWeek, friendActivityBucket(at("2026-09-10T12:00:00Z"), now))
        assertEquals(FriendActivityBucket.Earlier, friendActivityBucket(at("2026-09-08T12:00:00Z"), now))
        assertEquals(FriendActivityBucket.Earlier, friendActivityBucket(null, now))
    }

    @Test fun bucketingKeepsOrderAndDropsEmptyBuckets() {
        val now = parseSocialTimestampMs("2026-09-15T20:00:00Z")!!
        val groups = groupFriendActivity(
            listOf(
                run("1", "a", content = "x", last = "2026-09-15T19:00:00Z"),
                run("2", "b", content = "y", last = "2026-09-15T18:00:00Z"),
                run("3", "c", content = "z", last = "2026-09-01T18:00:00Z"),
            ),
        )
        val buckets = bucketFriendActivity(groups, now)
        assertEquals(listOf(FriendActivityBucket.Today, FriendActivityBucket.Earlier), buckets.map { it.first })
        assertEquals(listOf("x", "y"), buckets[0].second.map { it.contentId })
    }
}
