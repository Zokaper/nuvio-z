package com.nuvio.app.features.player

import com.nuvio.app.features.social.SocialNotification
import com.nuvio.app.features.social.SocialNotificationAction
import com.nuvio.app.features.social.SocialNotificationKind
import com.nuvio.app.features.social.SocialProfileSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MobileWatchTogetherPanelTest {
    private fun events(actions: List<MobilePartyAction>) = actions.map { it.event to it.value }

    @Test fun idleOffersOnlyStartingAParty() {
        assertEquals(listOf("wtStartParty" to 0.0), events(mobilePartyStateActions(WatchTogetherBridgeState(stateName = "idle"))))
    }

    @Test fun busyAndUnshareableStatesOfferNothingToPress() {
        listOf("starting", "connecting", "unshareable").forEach { name ->
            assertTrue(mobilePartyStateActions(WatchTogetherBridgeState(stateName = name)).isEmpty(), name)
        }
    }

    @Test fun startFailureOffersTheExistingPartyOnlyWhenThereIsOne() {
        assertEquals(
            listOf("wtRetry" to 0.0),
            events(mobilePartyStateActions(WatchTogetherBridgeState(stateName = "startFailed"))),
        )
        assertEquals(
            listOf("wtRetry" to 0.0, "wtOpenExisting" to 0.0),
            events(mobilePartyStateActions(WatchTogetherBridgeState(stateName = "startFailed", offersOpenExisting = true))),
        )
    }

    @Test fun aPartyElsewhereCanBeOpenedOrLeft() {
        assertEquals(
            listOf("wtOpenExisting" to 0.0, "wtLeaveElsewhere" to 0.0),
            events(mobilePartyStateActions(WatchTogetherBridgeState(stateName = "activeElsewhere"))),
        )
    }

    @Test fun anEndedPartyOffersContinueOrExit() {
        assertEquals(
            listOf("partyEndContinue" to 0.0, "partyEndExit" to 0.0),
            events(mobilePartyStateActions(WatchTogetherBridgeState(stateName = "ended"))),
        )
    }

    @Test fun aGuestLeavesWithoutConfirmation() {
        assertEquals(
            listOf("partyLeave" to 0.0),
            events(mobilePartyStateActions(WatchTogetherBridgeState(stateName = "active", isHost = false))),
        )
    }

    @Test fun theHostEndsOnlyThroughTheConfirmationStep() {
        val host = WatchTogetherBridgeState(stateName = "active", isHost = true)
        assertEquals(listOf("wtEndConfirm" to 1.0), events(mobilePartyStateActions(host)))
        assertEquals(
            listOf("partyEnd" to 0.0, "wtEndConfirm" to 0.0),
            events(mobilePartyStateActions(host.copy(endConfirm = true))),
        )
    }

    @Test fun outgoingRequestActionsFollowItsPhase() {
        assertTrue(mobileOutgoingRequestActions(WatchTogetherBridgeState()).isEmpty())
        val pending = WatchTogetherBridgeState(outgoingVisible = true, outgoingPhase = "pending", outgoingName = "Sam")
        assertEquals(listOf("wtCancelOutgoing" to 0.0), events(mobileOutgoingRequestActions(pending)))
        assertEquals("Asking Sam to join", mobileOutgoingRequestTitle(pending))
        listOf("accepted", "joining").forEach { phase ->
            val answered = pending.copy(outgoingPhase = phase)
            assertEquals(
                listOf("wtJoinAccepted" to 0.0, "wtDismissAccepted" to 0.0),
                events(mobileOutgoingRequestActions(answered)),
            )
            assertEquals("Sam let you in", mobileOutgoingRequestTitle(answered))
        }
    }

    @Test fun theHeaderButtonStaysReachableWhileThePartyHasSomethingToSay() {
        assertFalse(mobileWatchTogetherButtonVisible(false, WatchTogetherBridgeState()))
        assertTrue(mobileWatchTogetherButtonVisible(true, WatchTogetherBridgeState()))
        listOf("active", "busy", "incoming", "outgoing").forEach { badge ->
            assertTrue(mobileWatchTogetherButtonVisible(false, WatchTogetherBridgeState(badge = badge)), badge)
        }
    }

    @Test fun thePillSendsExactlyTheCommandsTheBridgeChose() {
        assertTrue(mobilePartyStatusActions(PartyStatusBridgeState(visible = true, text = "Waiting")).isEmpty())
        val status = PartyStatusBridgeState(
            visible = true,
            text = "Waiting for Sam",
            action = "wtStartAnyway",
            actionLabel = "Start anyway",
            secondaryAction = "wtDontWait",
            secondaryActionLabel = "Don't wait",
        )
        assertEquals(
            listOf(MobilePartyAction("Start anyway", "wtStartAnyway", emphasis = MobilePartyActionEmphasis.Primary), MobilePartyAction("Don't wait", "wtDontWait")),
            mobilePartyStatusActions(status),
        )
    }

    @Test fun theSocialCardMapsActionNamesToTheirEventsAndCanAlwaysBeDismissed() {
        assertEquals(
            listOf("socialNotificationJoin", "socialNotificationDecline", "socialNotificationDismiss"),
            mobileSocialNotificationActions(listOf("decline", "join")).map { it.event },
        )
        assertEquals(
            listOf("socialNotificationAccept", "socialNotificationDecline", "socialNotificationDismiss"),
            mobileSocialNotificationActions(listOf("accept", "decline")).map { it.event },
        )
        assertEquals(listOf("socialNotificationDismiss"), mobileSocialNotificationActions(emptyList()).map { it.event })
    }

    private val actor = SocialProfileSummary("p", "friend", "Friend")
    private fun notification(id: String, kind: SocialNotificationKind, readAt: String? = null) = SocialNotification(
        id = id,
        kind = kind,
        actor = actor,
        createdAt = "2026-09-18T00:00:00Z",
        readAt = readAt,
        state = "pending",
        availableActions = setOf(SocialNotificationAction.Accept, SocialNotificationAction.Decline),
    )

    @Test fun theCardAnswersItsOwnNotificationNotAPendingJoinRequest() {
        // The join request is first and offers Accept too; the card must still resolve to the
        // friend request it is showing, or Accept on it would let the requester into the party.
        val joinRequest = notification("join", SocialNotificationKind.WatchingNowJoinRequest)
        val friendRequest = notification("friend", SocialNotificationKind.FriendRequest)
        assertEquals("friend", inPlayerSocialCardNotification(listOf(joinRequest, friendRequest))?.id)
        assertNull(inPlayerSocialCardNotification(listOf(joinRequest)))
        assertNull(inPlayerSocialCardNotification(listOf(friendRequest.copy(readAt = "2026-09-18T00:00:01Z"))))
        assertNull(inPlayerSocialCardNotification(listOf(friendRequest.copy(availableActions = emptySet()))))
    }
}
