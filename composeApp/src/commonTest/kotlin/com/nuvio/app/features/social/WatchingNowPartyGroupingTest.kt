package com.nuvio.app.features.social

import kotlin.test.Test
import kotlin.test.assertEquals

class WatchingNowPartyGroupingTest {
    private fun item(
        who: String,
        session: String = "s-$who",
        party: String? = null,
        host: String? = null,
        count: Int? = null,
    ) = WatchingNowItem(
        profile = SocialProfileSummary(profileId = who, handle = who, displayName = who),
        contentId = "tt1", contentType = "movie", videoId = "tt1", title = "Burning",
        sessionId = session, positionMs = 0, durationMs = 0,
        state = SocialPlaybackState.playing, heartbeatAt = "2026-09-17T00:00:00Z",
        partyId = party, partyHostProfileId = host, partyMemberCount = count,
    )

    @Test fun onePartyIsOneEntryLedByTheHost() {
        // The live 2026-09-17 shape: two guests heartbeat before the host.
        val grouped = groupWatchingNowByParty(
            listOf(item("ben", party = "p", host = "ana", count = 3), item("ana", party = "p", host = "ana", count = 3),
                item("cy", party = "p", host = "ana", count = 3)),
        )
        assertEquals(1, grouped.size)
        assertEquals("ana", grouped.single().profile.profileId)
        assertEquals("s-ana", grouped.single().sessionId)
        assertEquals(listOf("ben", "cy"), grouped.single().partyCompanions.map { it.profileId })
        assertEquals("ana, ben & cy", watchingNowPeopleLabel(grouped.single()))
    }

    @Test fun separateSessionsOnTheSameTitleStaySeparate() {
        val items = listOf(item("ana"), item("ben"))
        assertEquals(items, groupWatchingNowByParty(items))
        assertEquals(2, orderWatchingNowForDisplay(items).size)
    }

    @Test fun twoPartiesAreTwoEntries() {
        val grouped = groupWatchingNowByParty(
            listOf(item("ana", party = "p1", host = "ana", count = 2), item("ben", party = "p2", host = "ben", count = 2),
                item("cy", party = "p1", host = "ana", count = 2)),
        )
        assertEquals(listOf("ana", "ben"), grouped.map { it.profile.profileId })
    }

    @Test fun aGuestWhoseHostIsNotListedOffersNoJoin() {
        val grouped = groupWatchingNowByParty(
            listOf(item("ben", party = "p", host = "stranger", count = 3), item("cy", party = "p", host = "stranger", count = 3)),
        ).single()
        assertEquals(WatchingNowJoinAffordance.None, watchingNowJoinAffordance(grouped, OutgoingJoinRequestState.Idle, null))
        assertEquals("ben & 2 others", watchingNowPeopleLabel(grouped))
    }

    @Test fun theHostEntryStillOffersItsJoin() {
        val host = groupWatchingNowByParty(
            listOf(item("ana", party = "p", host = "ana", count = 2), item("ben", party = "p", host = "ana", count = 2)),
        ).single()
        assertEquals(WatchingNowJoinAffordance.AskToJoin, watchingNowJoinAffordance(host, OutgoingJoinRequestState.Idle, null))
    }
}
