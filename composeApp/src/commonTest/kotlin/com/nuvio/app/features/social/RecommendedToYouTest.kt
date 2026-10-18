package com.nuvio.app.features.social

import kotlin.test.Test
import kotlin.test.assertEquals

class RecommendedToYouTest {
    private fun person(name: String) = SocialProfileSummary(profileId = "p-$name", handle = name, displayName = name)

    private fun rec(id: String, who: String, content: String, at: String, note: String? = null, kind: String = SocialInboxEvent.Recommendation) =
        SocialInboxEvent(
            id = id,
            kind = kind,
            actor = person(who),
            createdAt = at,
            payload = SocialInboxPayload(contentId = content, contentType = "series", title = content.uppercase(), note = note),
        )

    @Test fun oneCardPerTitleWithEveryFriendNewestFirst() {
        val shelf = recommendedToYou(
            listOf(
                rec("1", "ben", "aot", "2026-10-18T10:00:00Z"),
                rec("2", "seraph", "aot", "2026-10-18T12:00:00Z", note = "If you watched BB u gotta watch ts"),
                rec("3", "faye", "budapest", "2026-10-18T11:00:00Z"),
            ),
        )
        assertEquals(listOf("aot", "budapest"), shelf.map { it.contentId })
        assertEquals(listOf("seraph", "ben"), shelf.first().people.map { it.displayName })
        assertEquals("If you watched BB u gotta watch ts", shelf.first().from.first().note)
        assertEquals(setOf("2", "1"), shelf.first().eventIds)
    }

    @Test fun onlyRecommendationsAndOnlyWithATitle() {
        val shelf = recommendedToYou(
            listOf(
                rec("1", "ben", "aot", "2026-10-18T10:00:00Z", kind = SocialInboxEvent.FriendWatching),
                rec("2", "ben", "aot", "2026-10-18T10:00:00Z", kind = SocialInboxEvent.RecommendationStarted),
                SocialInboxEvent(id = "3", kind = SocialInboxEvent.Recommendation, actor = person("ben"), createdAt = "2026-10-18T10:00:00Z"),
            ),
        )
        assertEquals(emptyList(), shelf)
    }

    @Test fun theSameFriendTwiceIsOneFace() {
        val shelf = recommendedToYou(
            listOf(
                rec("1", "ben", "aot", "2026-10-18T10:00:00Z", note = "older"),
                rec("2", "ben", "aot", "2026-10-18T12:00:00Z", note = "newer"),
            ),
        )
        assertEquals(1, shelf.single().from.size)
        assertEquals("newer", shelf.single().from.single().note)
    }
}
