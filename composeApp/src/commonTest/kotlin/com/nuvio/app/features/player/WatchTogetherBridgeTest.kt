package com.nuvio.app.features.player

import com.nuvio.app.features.social.WatchJoinPolicy
import com.nuvio.app.features.watchparty.PartyPromotionFailure
import com.nuvio.app.features.watchparty.PartyReadyTone
import com.nuvio.app.features.watchparty.PartyStatusAction
import com.nuvio.app.features.watchparty.PartyStatusKind
import com.nuvio.app.features.watchparty.PartyStatusLine
import com.nuvio.app.features.watchparty.PartyStatusPerson
import com.nuvio.app.features.watchparty.PartyStatusTone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchTogetherBridgeTest {
    private val request = IncomingJoinRequestRow("r", "ahmed", "Ahmed", null, "#000000", 99L)
    private fun active(host: Boolean, incoming: IncomingJoinRequestRow? = null) = WatchTogetherPanelState.Active(
        role = if (host) WatchTogetherRole.Host else WatchTogetherRole.Guest,
        title = "Mayday",
        subline = "You're hosting · 2 people",
        connection = PartyConnectionChip.Delayed,
        people = listOf(WatchTogetherPersonRow("me", "You", null, "#1", true, true, "Playing", PartyReadyTone.Ready)),
        incomingRequest = incoming,
        settings = if (host) WatchTogetherHostSettings(true, false, JoinPolicyControl(WatchJoinPolicy.disabled)) else null,
        leaveHelper = if (host) "Seraph becomes host" else null,
        errorMessage = null,
        syncDetails = null,
    )
    private val invites = listOf(WatchTogetherBridgeInvite(0, "Seraph", "", invited = false))

    @Test fun theBadgeFollowsThePlanPriority() {
        assertEquals("busy", watchTogetherBridgeState(WatchTogetherPanelState.Starting, open = false).badge)
        assertEquals("busy", watchTogetherBridgeState(WatchTogetherPanelState.Connecting, open = false).badge)
        assertEquals("incoming", watchTogetherBridgeState(active(host = true, incoming = request), open = false).badge)
        val outgoing = WatchTogetherOutgoingMirror("Seraph", null, "#1", "pending", 5L)
        assertEquals("outgoing", watchTogetherBridgeState(WatchTogetherPanelState.Unshareable, false, outgoing = outgoing).badge)
        assertEquals("active", watchTogetherBridgeState(active(host = false), open = false).badge)
        assertEquals("none", watchTogetherBridgeState(WatchTogetherPanelState.Unshareable, open = false).badge)
        assertEquals("Watch Together, Ahmed wants to join", watchTogetherBridgeState(active(true, request), false).buttonLabel)
        assertEquals("Watch Together, 1 person in your party", watchTogetherBridgeState(active(false), false).buttonLabel)
    }

    @Test fun hostOnlyFieldsNeverReachAGuest() {
        val guest = watchTogetherBridgeState(active(host = false, incoming = request), open = true, inviteTargets = invites, inviteCode = "ABC", endConfirm = true)
        assertFalse(guest.isHost)
        assertFalse(guest.joinPolicyVisible)
        assertTrue(guest.inviteTargets.isEmpty())
        assertEquals("", guest.inviteCode)
        assertFalse(guest.endConfirm)
        assertEquals("", guest.leaveHelper)

        val host = watchTogetherBridgeState(active(host = true), open = true, inviteTargets = invites, inviteCode = "ABC", endConfirm = true)
        assertTrue(host.isHost && host.joinPolicyVisible && host.endConfirm)
        assertEquals(2, host.joinPolicy)
        assertEquals("Friends can see what you're watching but can't join.", host.joinPolicyExplanation)
        assertEquals("delayed", host.connection)
        assertEquals("Live sync is down; staying in step every few seconds", host.connectionTooltip)
        assertTrue(host.guestsControl)
        assertFalse(host.pauseWhenBuffers)
        assertEquals("ready", host.people.single().tone)
    }

    @Test fun everyStateHasItsWords() {
        assertTrue(watchTogetherBridgeState(WatchTogetherPanelState.Unshareable, false).message.startsWith("This source can't be shared"))
        val idle = watchTogetherBridgeState(
            WatchTogetherPanelState.Idle("Mayday", JoinPolicyControl(WatchJoinPolicy.direct, saving = true, errorMessage = "nope"), null),
            open = true,
        )
        assertEquals("idle", idle.stateName)
        assertEquals(0, idle.joinPolicy)
        assertTrue(idle.joinPolicySaving)
        assertEquals("nope", idle.joinPolicyError)
        val failed = watchTogetherBridgeState(
            WatchTogetherPanelState.StartFailed(PartyPromotionFailure.AlreadyInAnotherParty, "You're already in another party"),
            open = true,
        )
        assertTrue(failed.offersOpenExisting)
        assertEquals("ended", watchTogetherBridgeState(WatchTogetherPanelState.Ended("Seraph ended the party"), true).stateName)
        assertEquals(
            "You're in a party watching Other",
            watchTogetherBridgeState(WatchTogetherPanelState.ActiveElsewhere("Other"), true).message,
        )
    }

    @Test fun policySegmentsRoundTrip() {
        WatchJoinPolicy.entries.forEach { assertEquals(it, joinPolicyForSegment(it.segmentIndex())) }
        assertNull(joinPolicyForSegment(3))
    }

    @Test fun theStatusPillCarriesOnlyCommandsTheRuntimeAnswers() {
        val line = PartyStatusLine(
            PartyStatusKind.IncomingJoinRequest,
            "Ahmed wants to join",
            listOf(PartyStatusPerson("a", "Ahmed", null, "#8E24AA"), PartyStatusPerson("b", "B"), PartyStatusPerson("c", "C")),
            action = PartyStatusAction.LetIn,
            secondaryAction = PartyStatusAction.Decline,
            tone = PartyStatusTone.Waiting,
        )
        val pill = partyStatusBridgeState(line)
        assertTrue(pill.visible)
        assertEquals("waiting", pill.tone)
        assertEquals("wtAcceptRequest" to "Let in", pill.action to pill.actionLabel)
        assertEquals("wtDeclineRequest" to "Decline", pill.secondaryAction to pill.secondaryActionLabel)
        assertEquals(listOf("Ahmed", "B"), pill.people.map { it.name })
        assertEquals("", pill.people[0].avatarUrl)
        assertFalse(partyStatusBridgeState(line, suppressed = true).visible)
        assertFalse(partyStatusBridgeState(null).visible)
        assertEquals(
            setOf("wtChooseSource", "wtStartAnyway", "wtDontWait", "wtAcceptRequest", "wtDeclineRequest", "wtCancelOutgoing"),
            PartyStatusAction.entries.map { it.command() }.toSet(),
        )
        assertEquals("", partyStatusBridgeState(line.copy(action = null, secondaryAction = null)).action)
    }
}
