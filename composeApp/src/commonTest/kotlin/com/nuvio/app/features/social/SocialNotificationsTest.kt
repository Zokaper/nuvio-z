package com.nuvio.app.features.social

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
