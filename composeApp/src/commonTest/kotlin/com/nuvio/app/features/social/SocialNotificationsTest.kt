package com.nuvio.app.features.social

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SocialNotificationsTest {
    private val actor=SocialProfileSummary("p","friend","Friend")
    private fun item(id:String,created:String="2026-09-07T00:00:00Z")=SocialNotification(
        id=id,kind=SocialNotificationKind.WatchingNowJoinRequest,actor=actor,createdAt=created,
        state="pending",availableActions=setOf(SocialNotificationAction.Accept,SocialNotificationAction.Decline),
    )

    @Test fun refreshDeduplicatesStableDomainIdentity() {
        val state=reduceSocialNotifications(SocialNotificationState(),SocialNotificationEvent.Refreshed(listOf(item("join_request:1"),item("join_request:1"))))
        assertEquals(1,state.items.size)
        assertEquals(1,state.unreadCount)
    }

    @Test fun bannerDoesNotMarkReadButOpeningDoes() {
        val state=SocialNotificationState(listOf(item("join_request:1")))
        assertEquals(1,state.unreadCount)
        val read=reduceSocialNotifications(state,SocialNotificationEvent.MarkedRead(setOf("join_request:1"),"now"))
        assertEquals(0,read.unreadCount)
    }

    @Test fun staleActionConsumesActionsAndShowsCanonicalMessage() {
        val state=reduceSocialNotifications(SocialNotificationState(listOf(item("join_request:1"))),SocialNotificationEvent.ActionStale("join_request:1"))
        assertEquals(emptySet(),state.items.single().availableActions)
        assertEquals("This request is no longer available.",state.staleMessage)
    }

    private val now = parseSocialTimestampMs("2026-10-06T12:00:00Z")!!
    private fun invite(state: String, created: String, actions: Set<SocialNotificationAction> = emptySet()) = SocialNotification(
        id = "party_invite:$state", kind = SocialNotificationKind.PartyInvitation, actor = actor, createdAt = created,
        state = state, availableActions = actions,
    )

    @Test fun anInviteFromAPartyThatEndedDaysAgoLeavesTheInbox() {
        // Production 2026-10-06: every invite's party had ended; 31 were "accepted", 4 "expired".
        assertTrue(invite("accepted", "2026-10-03T20:00:00Z").isRetired(now))
        assertTrue(invite("expired", "2026-10-03T20:00:00Z").isRetired(now))
        assertTrue(invite("pending", "2026-10-03T20:00:00Z").isRetired(now))
    }

    @Test fun lastNightsFinishedInviteStaysAsHistory() {
        val recent = invite("accepted", "2026-10-06T01:00:00Z")
        assertFalse(recent.isRetired(now))
        assertEquals(SocialPartyNoticeOutcome.Accepted, recent.partyNoticeOutcome())
    }

    @Test fun anInviteThatCanStillBeJoinedNeverRetires() {
        val open = invite("pending", "2026-10-01T00:00:00Z", setOf(SocialNotificationAction.Join, SocialNotificationAction.Decline))
        assertFalse(open.isRetired(now))
        assertEquals(SocialPartyNoticeOutcome.Open, open.partyNoticeOutcome())
    }

    @Test fun aPendingInviteWhosePartyEndedReadsAsLapsedNotAccepted() {
        assertEquals(SocialPartyNoticeOutcome.Lapsed, invite("pending", "2026-10-06T10:00:00Z").partyNoticeOutcome())
        assertEquals(SocialPartyNoticeOutcome.Lapsed, invite("expired", "2026-10-06T10:00:00Z").partyNoticeOutcome())
        assertEquals(SocialPartyNoticeOutcome.Declined, invite("declined", "2026-10-06T10:00:00Z").partyNoticeOutcome())
    }

    @Test fun friendRequestHistoryIsNotRetired() {
        val accepted = SocialNotification(
            id = "friend_request:1", kind = SocialNotificationKind.FriendRequest, actor = actor,
            createdAt = "2026-09-01T00:00:00Z", state = "accepted",
        )
        assertFalse(accepted.isRetired(now))
    }
}
