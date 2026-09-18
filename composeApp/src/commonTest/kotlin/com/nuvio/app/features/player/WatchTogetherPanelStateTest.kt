package com.nuvio.app.features.player

import com.nuvio.app.features.social.WatchJoinPolicy
import com.nuvio.app.features.watchparty.PartyApiHealth
import com.nuvio.app.features.watchparty.PartyContent
import com.nuvio.app.features.watchparty.PartyHealthState
import com.nuvio.app.features.watchparty.PartyMemberPresentation
import com.nuvio.app.features.watchparty.PartyParticipantProfile
import com.nuvio.app.features.watchparty.PartyPromotionFailure
import com.nuvio.app.features.watchparty.PartyReadyTone
import com.nuvio.app.features.watchparty.PartyRealtimeHealth
import com.nuvio.app.features.watchparty.PartySourceMatch
import com.nuvio.app.features.watchparty.SourceResolutionState
import com.nuvio.app.features.watchparty.WatchPartyControlMode
import com.nuvio.app.features.watchparty.WatchPartyParticipant
import com.nuvio.app.features.watchparty.WatchPartyState
import com.nuvio.app.features.watchparty.WatchPartyStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchTogetherPanelStateTest {
    private fun member(id: String, name: String, joinedAt: String, connected: Boolean = true) = WatchPartyParticipant(
        profileId = id,
        role = "participant",
        readyState = SourceResolutionState.ready,
        profile = PartyParticipantProfile(displayName = name),
        connected = connected,
        joinedAt = joinedAt,
    )

    private fun party(
        host: String = "me",
        contentId: String = "tt1",
        mode: WatchPartyControlMode = WatchPartyControlMode.host_only,
        status: WatchPartyStatus = WatchPartyStatus.playing,
        members: List<WatchPartyParticipant> = listOf(
            member("me", "Rayo", "2026-09-15T10:00:00Z"),
            member("debug", "debug", "2026-09-15T10:02:00Z"),
            member("seraph", "Seraph", "2026-09-15T10:01:00Z"),
        ),
    ) = WatchPartyState(
        id = "p", hostProfileId = host, status = status, controlMode = mode, contentGeneration = 1,
        content = PartyContent(contentId, "movie", contentId, if (contentId == "tt1") "Mayday" else "Other"),
        positionMs = 0, durationMs = 0, playbackSpeed = 1f, sequence = 1, stateUpdatedAt = "", members = members,
    )

    private val base = WatchTogetherPanelInputs(
        shareable = true,
        playbackContentId = "tt1",
        playbackVideoId = "tt1",
        playbackTitle = "Mayday",
        viewerProfileId = "me",
        party = null,
    )

    @Test fun noPartyIsIdleWithThePolicyControl() {
        val idle = assertIs<WatchTogetherPanelState.Idle>(projectWatchTogetherPanel(base))
        assertEquals("Mayday", idle.title)
        assertEquals("You'll approve each friend.", idle.joinPolicy.explanation)
        assertEquals("Friends can jump straight in.", JoinPolicyControl(WatchJoinPolicy.direct).explanation)
        assertEquals("Friends can see what you're watching but can't join.", JoinPolicyControl(WatchJoinPolicy.disabled).explanation)
    }

    @Test fun unshareableIsAStateNotAHiddenButton() {
        assertEquals(WatchTogetherPanelState.Unshareable, projectWatchTogetherPanel(base.copy(shareable = false)))
    }

    @Test fun startingAndStartFailed() {
        assertEquals(WatchTogetherPanelState.Starting, projectWatchTogetherPanel(base.copy(promotion = PartyPromotionProgress.Starting)))
        val failed = assertIs<WatchTogetherPanelState.StartFailed>(
            projectWatchTogetherPanel(base.copy(promotion = PartyPromotionProgress.Failed(PartyPromotionFailure.AlreadyInAnotherParty))),
        )
        assertEquals("You're already in another party", failed.message)
        assertTrue(failed.offersOpenExisting)
        assertEquals("Getting ready to share, try again in a moment", promotionFailureMessage(PartyPromotionFailure.PresenceStale))
        assertEquals("Couldn't start the party", promotionFailureMessage(PartyPromotionFailure.Refused))
    }

    @Test fun connectingOutranksStarting() {
        assertEquals(
            WatchTogetherPanelState.Connecting,
            projectWatchTogetherPanel(base.copy(connecting = true, promotion = PartyPromotionProgress.Starting)),
        )
    }

    @Test fun aLivePartyOutranksPromotionProgressSoThePanelMovesToActiveInPlace() {
        val state = projectWatchTogetherPanel(base.copy(party = party(), promotion = PartyPromotionProgress.Starting))
        assertIs<WatchTogetherPanelState.Active>(state)
    }

    @Test fun aPartyOnOtherContentIsActiveElsewhere() {
        val state = projectWatchTogetherPanel(base.copy(party = party(contentId = "tt2")))
        assertEquals(WatchTogetherPanelState.ActiveElsewhere("Other"), state)
    }

    @Test fun anEndedPartyIsIdleButThePostEndChoiceIsEnded() {
        assertIs<WatchTogetherPanelState.Idle>(projectWatchTogetherPanel(base.copy(party = party(status = WatchPartyStatus.ended))))
        assertEquals(
            WatchTogetherPanelState.Ended("Seraph ended the party"),
            projectWatchTogetherPanel(base.copy(postEndChoice = true, endedByName = "Seraph", party = party())),
        )
        assertEquals(WatchTogetherPanelState.Ended("The party ended"), projectWatchTogetherPanel(base.copy(postEndChoice = true)))
    }

    @Test fun hostLayout() {
        val active = assertIs<WatchTogetherPanelState.Active>(
            projectWatchTogetherPanel(
                base.copy(
                    party = party(),
                    health = PartyHealthState(api = PartyApiHealth.Reachable, realtime = PartyRealtimeHealth.Live),
                    waitForEveryone = false,
                    members = mapOf("seraph" to PartyMemberPresentation("seraph", "Buffering", PartyReadyTone.Buffering, true)),
                    incomingRequest = IncomingJoinRequestRow("r", "ahmed", "Ahmed", null, "#000", null),
                ),
            ),
        )
        assertEquals(WatchTogetherRole.Host, active.role)
        assertEquals("You're hosting · 3 people", active.subline)
        assertEquals(PartyConnectionChip.Live, active.connection)
        assertEquals(listOf("You", "Seraph", "debug"), active.people.map { it.name })
        assertTrue(active.people.first().isHost && active.people.first().isSelf)
        assertEquals("Buffering", active.people[1].statusLabel)
        assertEquals(false, active.settings!!.pauseWhenSomeoneBuffers)
        assertEquals(false, active.settings!!.guestsControlPlayback)
        // Seraph joined before debug: party_live_successor would pick Seraph.
        assertEquals("Seraph becomes host", active.leaveHelper)
        assertEquals("Ahmed", active.incomingRequest?.name)
    }

    @Test fun guestLayout() {
        val active = assertIs<WatchTogetherPanelState.Active>(
            projectWatchTogetherPanel(
                base.copy(
                    party = party(host = "seraph"),
                    incomingRequest = IncomingJoinRequestRow("r", "ahmed", "Ahmed", null, "#000", null),
                    sourceMatch = PartySourceMatch.alternate,
                    releaseName = "Mayday.2160p",
                ),
            ),
        )
        assertEquals(WatchTogetherRole.Guest, active.role)
        assertEquals("Seraph controls playback", active.subline)
        assertNull(active.settings)
        assertNull(active.leaveHelper)
        assertNull(active.incomingRequest, "requests are the host's")
        assertEquals("Similar version · Mayday.2160p", active.syncDetails)
        val collaborative = assertIs<WatchTogetherPanelState.Active>(
            projectWatchTogetherPanel(base.copy(party = party(host = "seraph", mode = WatchPartyControlMode.collaborative))),
        )
        assertEquals("Anyone can pause & seek", collaborative.subline)
    }

    @Test fun successorSkipsDisconnectedMembersAndIsNullWhenAlone() {
        val p = party(
            members = listOf(
                member("me", "Rayo", "2026-09-15T10:00:00Z"),
                member("seraph", "Seraph", "2026-09-15T10:01:00Z", connected = false),
                member("debug", "debug", "2026-09-15T10:02:00Z"),
            ),
        )
        assertEquals("debug", partyLeaveSuccessor(p)?.profileId)
        assertNull(partyLeaveSuccessor(party(members = listOf(member("me", "Rayo", "x")))))
    }

    @Test fun connectionChip() {
        val reachable = PartyHealthState(api = PartyApiHealth.Reachable)
        assertEquals(PartyConnectionChip.Live, partyConnectionChip(reachable.copy(realtime = PartyRealtimeHealth.Live), 0, 100_000))
        assertEquals(PartyConnectionChip.Live, partyConnectionChip(reachable.copy(realtime = PartyRealtimeHealth.Degraded), 0, 3_000))
        assertEquals(PartyConnectionChip.Reconnecting, partyConnectionChip(reachable.copy(realtime = PartyRealtimeHealth.Degraded), 0, 3_001))
        assertEquals(PartyConnectionChip.Reconnecting, partyConnectionChip(reachable.copy(realtime = PartyRealtimeHealth.Connecting), 0, 10_000))
        assertEquals(PartyConnectionChip.Live, partyConnectionChip(reachable.copy(realtime = PartyRealtimeHealth.SubscribedUnverified), 0, 19_000))
        assertEquals(PartyConnectionChip.Delayed, partyConnectionChip(reachable.copy(realtime = PartyRealtimeHealth.SubscribedUnverified), 0, 60_000))
        assertEquals(PartyConnectionChip.Delayed, partyConnectionChip(reachable.copy(realtime = PartyRealtimeHealth.Detached), 0, 60_000))
        assertEquals(PartyConnectionChip.Offline, partyConnectionChip(PartyHealthState(api = PartyApiHealth.Unreachable), 0, 60_000))
    }
}
