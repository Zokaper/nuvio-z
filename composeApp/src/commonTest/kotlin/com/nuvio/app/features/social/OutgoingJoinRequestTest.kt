package com.nuvio.app.features.social

import com.nuvio.app.features.watchparty.PartyContent
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

class OutgoingJoinRequestTest {
    private val a1 = JoinRequestBinding("profileA", 1)
    private val target = JoinRequestTarget(profileId = "seraph", displayName = "Seraph", sessionId = "s1")
    private val content = JoinRequestContent(contentId = "tt1", videoId = "tt1:1:2", title = "The Punisher", season = 1, episode = 2)

    private fun party(id: String = "party", members: List<String> = listOf("seraph", "profileA")) = WatchPartyState(
        id = id,
        hostProfileId = "seraph",
        status = WatchPartyStatus.playing,
        controlMode = WatchPartyControlMode.host_only,
        contentGeneration = 1,
        content = PartyContent(contentId = "tt1", contentType = "series", videoId = "tt1:1:2", title = "The Punisher"),
        positionMs = 0,
        durationMs = 0,
        playbackSpeed = 1f,
        sequence = 1,
        stateUpdatedAt = "2026-09-15T00:00:00Z",
        members = members.map {
            WatchPartyParticipant(profileId = it, role = "participant", readyState = SourceResolutionState.joined, joinedAt = "2026-09-15T00:00:00Z")
        },
    )

    private fun reduce(state: OutgoingJoinRequestState, vararg events: OutgoingJoinEvent): OutgoingJoinTransition {
        var t = OutgoingJoinTransition(state)
        events.forEach { t = reduceOutgoingJoinRequest(t.state, it) }
        return t
    }

    private fun pending(binding: JoinRequestBinding = a1, nowMs: Long = 1_000L): OutgoingJoinRequestState.Pending {
        val t = reduce(
            OutgoingJoinRequestState.Idle,
            OutgoingJoinEvent.Send(binding, target, content),
            OutgoingJoinEvent.SendAnswered(binding, JoinSendAnswer.ApprovalRequired("req1", nowMs + 120_000), nowMs, inOwnPlayer = false),
        )
        return assertIs(t.state)
    }

    private fun statusRead(
        status: JoinRequestServerStatus,
        party: WatchPartyState? = null,
        binding: JoinRequestBinding = a1,
        nowMs: Long = 5_000L,
        inOwnPlayer: Boolean = false,
    ) = OutgoingJoinEvent.StatusRead(binding, "req1", status, party, null, nowMs, inOwnPlayer)

    private fun OutgoingJoinTransition.navigates() = effects.any { it is OutgoingJoinEffect.OpenLobby }

    // Lifecycle -------------------------------------------------------------------------------

    @Test fun sendThenApprovalRequiredIsPendingWithTheServerExpiry() {
        val t = reduceOutgoingJoinRequest(OutgoingJoinRequestState.Idle, OutgoingJoinEvent.Send(a1, target, content))
        assertIs<OutgoingJoinRequestState.Sending>(t.state)
        assertTrue(t.effects.any { it is OutgoingJoinEffect.SendJoin })
        assertEquals(121_000L, pending().expiresAtMs)
    }

    @Test fun approvalWithoutExpiryAssumesTheTableDefault() {
        val t = reduce(
            OutgoingJoinRequestState.Idle,
            OutgoingJoinEvent.Send(a1, target, content),
            OutgoingJoinEvent.SendAnswered(a1, JoinSendAnswer.ApprovalRequired("req1", null), 10L, false),
        )
        assertEquals(10L + OutgoingJoinDefaultLifetimeMs, assertIs<OutgoingJoinRequestState.Pending>(t.state).expiresAtMs)
    }

    @Test fun aDirectJoinOpensTheLobbyWithoutACountdown() {
        val t = reduce(
            OutgoingJoinRequestState.Idle,
            OutgoingJoinEvent.Send(a1, target, content),
            OutgoingJoinEvent.SendAnswered(a1, JoinSendAnswer.OpenParty(party()), 10L, false),
        )
        assertIs<OutgoingJoinRequestState.Joining>(t.state)
        assertTrue(t.navigates())
        assertEquals(OutgoingJoinRequestState.Idle, reduceOutgoingJoinRequest(t.state, OutgoingJoinEvent.LobbyOpened(a1)).state)
    }

    @Test fun sendFailuresAreFailedWithTheirMessage() {
        val t = reduce(
            OutgoingJoinRequestState.Idle,
            OutgoingJoinEvent.Send(a1, target, content),
            OutgoingJoinEvent.SendAnswered(a1, JoinSendAnswer.Notice("This party is full"), 10L, false),
        )
        val outcome = assertIs<OutgoingJoinRequestState.Outcome>(t.state)
        assertEquals(OutgoingJoinOutcome.Failed, outcome.kind)
        assertEquals("This party is full", outcome.message)
        assertNull(outcome.clearAtMs)
    }

    @Test fun declinedAndExpiredAreDifferentOutcomesThatClearAfterSixSeconds() {
        val declined = reduce(pending(), statusRead(JoinRequestServerStatus.declined, nowMs = 5_000))
        assertEquals(OutgoingJoinOutcome.Declined, assertIs<OutgoingJoinRequestState.Outcome>(declined.state).kind)
        val expired = reduce(pending(), statusRead(JoinRequestServerStatus.expired, nowMs = 5_000))
        assertEquals(OutgoingJoinOutcome.Expired, assertIs<OutgoingJoinRequestState.Outcome>(expired.state).kind)
        assertIs<OutgoingJoinRequestState.Outcome>(reduceOutgoingJoinRequest(expired.state, OutgoingJoinEvent.Tick(10_999)).state)
        assertEquals(OutgoingJoinRequestState.Idle, reduceOutgoingJoinRequest(expired.state, OutgoingJoinEvent.Tick(11_000)).state)
    }

    @Test fun anUnansweredRequestExpiresPastItsDeadlinePlusGrace() {
        val p = pending()
        assertIs<OutgoingJoinRequestState.Pending>(reduceOutgoingJoinRequest(p, OutgoingJoinEvent.Tick(p.expiresAtMs + 4_999)).state)
        val t = reduceOutgoingJoinRequest(p, OutgoingJoinEvent.Tick(p.expiresAtMs + OutgoingJoinExpiryGraceMs))
        assertEquals(OutgoingJoinOutcome.Expired, assertIs<OutgoingJoinRequestState.Outcome>(t.state).kind)
    }

    @Test fun acceptedWhileBrowsingCountsDownThenJoins() {
        val t = reduce(pending(), statusRead(JoinRequestServerStatus.accepted, party(), nowMs = 5_000))
        val accepted = assertIs<OutgoingJoinRequestState.Accepted>(t.state)
        assertEquals(8_000L, accepted.countdownDeadlineMs)
        assertTrue(!t.navigates())
        assertIs<OutgoingJoinRequestState.Accepted>(reduceOutgoingJoinRequest(accepted, OutgoingJoinEvent.CountdownElapsed(a1, 7_999)).state)
        val joined = reduceOutgoingJoinRequest(accepted, OutgoingJoinEvent.CountdownElapsed(a1, 8_000))
        assertIs<OutgoingJoinRequestState.Joining>(joined.state)
        assertTrue(joined.navigates())
    }

    @Test fun acceptedInOwnPlayerWaitsForAnExplicitChoice() {
        val accepted = assertIs<OutgoingJoinRequestState.Accepted>(
            reduce(pending(), statusRead(JoinRequestServerStatus.accepted, party(), inOwnPlayer = true)).state,
        )
        assertNull(accepted.countdownDeadlineMs)
        assertTrue(!reduceOutgoingJoinRequest(accepted, OutgoingJoinEvent.Tick(1_000_000)).navigates())
        assertTrue(reduceOutgoingJoinRequest(accepted, OutgoingJoinEvent.JoinPressed(a1)).navigates())
    }

    @Test fun enteringThePlayerDuringTheCountdownStopsItAndLeavingDoesNotRestartIt() {
        val accepted = reduce(pending(), statusRead(JoinRequestServerStatus.accepted, party())).state
        val inPlayer = reduceOutgoingJoinRequest(accepted, OutgoingJoinEvent.PlayerPresenceChanged(a1, true)).state
        assertNull(assertIs<OutgoingJoinRequestState.Accepted>(inPlayer).countdownDeadlineMs)
        val left = reduceOutgoingJoinRequest(inPlayer, OutgoingJoinEvent.PlayerPresenceChanged(a1, false)).state
        assertNull(assertIs<OutgoingJoinRequestState.Accepted>(left).countdownDeadlineMs)
    }

    @Test fun notNowDepartsTheAcceptedPartyAndClears() {
        val accepted = reduce(pending(), statusRead(JoinRequestServerStatus.accepted, party())).state
        val t = reduceOutgoingJoinRequest(accepted, OutgoingJoinEvent.NotNowPressed(a1))
        assertEquals(OutgoingJoinRequestState.Idle, t.state)
        assertTrue(OutgoingJoinEffect.DepartParty("profileA", "party") in t.effects)
        assertTrue(!t.navigates())
    }

    @Test fun cancelSendsAsTheOwnerAndShowsCancelledBriefly() {
        val t = reduceOutgoingJoinRequest(pending(), OutgoingJoinEvent.CancelPressed(a1))
        assertIs<OutgoingJoinRequestState.Cancelling>(t.state)
        val cancel = assertIs<OutgoingJoinEffect.CancelOnServer>(t.effects.single())
        assertEquals("profileA", cancel.ownerProfileId)
        val done = reduceOutgoingJoinRequest(t.state, OutgoingJoinEvent.CancelAnswered(a1, JoinCancelAnswer.Cancelled, 2_000))
        val outcome = assertIs<OutgoingJoinRequestState.Outcome>(done.state)
        assertEquals(OutgoingJoinOutcome.Cancelled, outcome.kind)
        assertEquals(2_000L + OutgoingJoinCancelledDisplayMs, outcome.clearAtMs)
    }

    @Test fun aCancelThatLosesTheRaceToAcceptDepartsInsteadOfOpening() {
        val cancelling = reduceOutgoingJoinRequest(pending(), OutgoingJoinEvent.CancelPressed(a1)).state
        val raced = reduceOutgoingJoinRequest(cancelling, OutgoingJoinEvent.CancelAnswered(a1, JoinCancelAnswer.AlreadyAccepted(party()), 2_000))
        assertTrue(OutgoingJoinEffect.DepartParty("profileA", "party") in raced.effects)
        assertTrue(!raced.navigates())
        // And a status read that says accepted while cancelling departs too.
        val read = reduceOutgoingJoinRequest(cancelling, statusRead(JoinRequestServerStatus.accepted, party()))
        assertEquals(OutgoingJoinRequestState.Idle, read.state)
        assertTrue(OutgoingJoinEffect.DepartParty("profileA", "party") in read.effects)
        assertTrue(!read.navigates())
    }

    @Test fun aFailedCancelGoesBackToWaiting() {
        val cancelling = reduceOutgoingJoinRequest(pending(), OutgoingJoinEvent.CancelPressed(a1)).state
        val t = reduceOutgoingJoinRequest(cancelling, OutgoingJoinEvent.CancelAnswered(a1, JoinCancelAnswer.Failed("offline"), 2_000))
        assertIs<OutgoingJoinRequestState.Pending>(t.state)
    }

    @Test fun targetStoppingEndsTheRequestAndTidiesTheServerRow() {
        val t = reduceOutgoingJoinRequest(pending(), OutgoingJoinEvent.TargetPresence(a1, stillWatching = false, nowMs = 3_000))
        assertEquals(OutgoingJoinOutcome.TargetStopped, assertIs<OutgoingJoinRequestState.Outcome>(t.state).kind)
        assertTrue(t.effects.any { it is OutgoingJoinEffect.CancelOnServer })
        assertIs<OutgoingJoinRequestState.Pending>(reduceOutgoingJoinRequest(pending(), OutgoingJoinEvent.TargetPresence(a1, true, 3_000)).state)
    }

    @Test fun joiningAnotherPartyWhileWaitingSupersedesSilently() {
        val t = reduceOutgoingJoinRequest(pending(), OutgoingJoinEvent.HeldPartyChanged(a1, "other"))
        assertEquals(OutgoingJoinRequestState.Idle, t.state)
        assertTrue(t.effects.none { it is OutgoingJoinEffect.OpenLobby || it is OutgoingJoinEffect.DepartParty })
        // The accepted party being installed is not a supersession.
        val accepted = reduce(pending(), statusRead(JoinRequestServerStatus.accepted, party())).state
        assertIs<OutgoingJoinRequestState.Accepted>(reduceOutgoingJoinRequest(accepted, OutgoingJoinEvent.HeldPartyChanged(a1, "party")).state)
    }

    @Test fun aNewPressReplacesTheOldRequestAfterCancellingIt() {
        val a2 = a1.copy(token = 2)
        val t = reduceOutgoingJoinRequest(pending(), OutgoingJoinEvent.Send(a2, target.copy(sessionId = "s2"), content))
        assertEquals(a2, assertIs<OutgoingJoinRequestState.Sending>(t.state).binding)
        assertTrue(t.effects.any { it is OutgoingJoinEffect.CancelOnServer && it.requestId == "req1" })
    }

    @Test fun pollReadsImmediatelyOnInvalidationAndOtherwiseEveryThreeSeconds() {
        val p = pending()
        assertEquals(JoinRequestPollDecision.ReadNow, decideJoinRequestPoll(p, 2_000, lastReadAtMs = null, invalidated = false))
        assertEquals(JoinRequestPollDecision.WaitUntil(4_000), decideJoinRequestPoll(p, 2_000, lastReadAtMs = 1_000, invalidated = false))
        assertEquals(JoinRequestPollDecision.ReadNow, decideJoinRequestPoll(p, 2_000, lastReadAtMs = 1_000, invalidated = true))
        assertEquals(JoinRequestPollDecision.Stop, decideJoinRequestPoll(p, p.expiresAtMs + OutgoingJoinExpiryGraceMs, 1_000, true))
        assertEquals(JoinRequestPollDecision.Stop, decideJoinRequestPoll(OutgoingJoinRequestState.Idle, 0, null, true))
    }

    // Identity boundaries (§4) -----------------------------------------------------------------

    @Test fun profileSwitchMidPendingNeverNavigatesAndCancelsAsTheOldProfile() {
        val t = reduceOutgoingJoinRequest(pending(), OutgoingJoinEvent.IdentityBoundary("profileA", serverCleanup = true))
        assertEquals(OutgoingJoinRequestState.Idle, t.state)
        val cancel = t.effects.filterIsInstance<OutgoingJoinEffect.CancelOnServer>().single()
        assertEquals("profileA", cancel.ownerProfileId)
        assertTrue(cancel.boundary)
        assertTrue(!t.navigates())
        // A status that then arrives for the old request is stale and changes nothing.
        val late = reduceOutgoingJoinRequest(t.state, statusRead(JoinRequestServerStatus.accepted, party()))
        assertEquals(OutgoingJoinRequestState.Idle, late.state)
        assertIs<OutgoingJoinEffect.LogStale>(late.effects.single())
    }

    @Test fun signOutDuringTheAcceptCountdownMeansTheCountdownNeverFires() {
        val accepted = reduce(pending(), statusRead(JoinRequestServerStatus.accepted, party(), nowMs = 5_000)).state
        val boundary = reduceOutgoingJoinRequest(accepted, OutgoingJoinEvent.IdentityBoundary("profileA", serverCleanup = true))
        assertEquals(OutgoingJoinRequestState.Idle, boundary.state)
        assertTrue(OutgoingJoinEffect.DepartParty("profileA", "party") in boundary.effects)
        val elapsed = reduceOutgoingJoinRequest(boundary.state, OutgoingJoinEvent.CountdownElapsed(a1, 100_000))
        assertTrue(!elapsed.navigates())
        assertTrue(!reduceOutgoingJoinRequest(boundary.state, OutgoingJoinEvent.Tick(100_000)).navigates())
    }

    @Test fun socialDisabledWithAnAcceptedRaceAnswerDepartsThenClears() {
        val boundary = reduceOutgoingJoinRequest(pending(), OutgoingJoinEvent.IdentityBoundary("profileA", serverCleanup = true))
        assertEquals(OutgoingJoinRequestState.Idle, boundary.state)
        // The boundary cancel is answered already_accepted: the follow-up leaves that party.
        assertEquals(
            OutgoingJoinEffect.DepartParty("profileA", "party"),
            boundaryCancelFollowUp("profileA", JoinCancelAnswer.AlreadyAccepted(party())),
        )
        assertNull(boundaryCancelFollowUp("profileA", JoinCancelAnswer.Cancelled))
    }

    @Test fun aLateSendResultAfterABoundaryIsDropped() {
        val sending = reduceOutgoingJoinRequest(OutgoingJoinRequestState.Idle, OutgoingJoinEvent.Send(a1, target, content)).state
        val cleared = reduceOutgoingJoinRequest(sending, OutgoingJoinEvent.IdentityBoundary("profileA", true)).state
        val late = reduceOutgoingJoinRequest(cleared, OutgoingJoinEvent.SendAnswered(a1, JoinSendAnswer.OpenParty(party()), 9, false))
        assertEquals(OutgoingJoinRequestState.Idle, late.state)
        assertTrue(!late.navigates())
        // Dropped from the state, never from the server: the membership it made is released as its owner.
        assertEquals(
            listOf(OutgoingJoinEffect.ReleaseOrphanedSend("profileA", JoinSendAnswer.OpenParty(party()))),
            late.effects,
        )
    }

    @Test fun cancellingWhileSendingMarksTheSendAbandonedSoItsAnswerIsUndone() {
        val sending = reduceOutgoingJoinRequest(OutgoingJoinRequestState.Idle, OutgoingJoinEvent.Send(a1, target, content)).state
        val cancelled = reduceOutgoingJoinRequest(sending, OutgoingJoinEvent.CancelPressed(a1))
        assertEquals(OutgoingJoinRequestState.Idle, cancelled.state)
        assertTrue(OutgoingJoinEffect.AbandonSend(a1) in cancelled.effects)
        // An Ask that lands after the cancel is a live request on the server, cancelled as its owner.
        val late = reduceOutgoingJoinRequest(
            cancelled.state,
            OutgoingJoinEvent.SendAnswered(a1, JoinSendAnswer.ApprovalRequired("req9", 99L), 9, false),
        )
        assertEquals(OutgoingJoinRequestState.Idle, late.state)
        assertEquals(
            listOf(OutgoingJoinEffect.ReleaseOrphanedSend("profileA", JoinSendAnswer.ApprovalRequired("req9", 99L))),
            late.effects,
        )
    }

    @Test fun aReplacedSendsAnswerIsReleasedAndNeverAppliedToTheNewRequest() {
        val a2 = JoinRequestBinding("profileA", 2)
        val replaced = reduce(
            OutgoingJoinRequestState.Idle,
            OutgoingJoinEvent.Send(a1, target, content),
            OutgoingJoinEvent.Send(a2, target.copy(profileId = "debug", sessionId = "s2"), content),
        )
        val late = reduceOutgoingJoinRequest(replaced.state, OutgoingJoinEvent.SendAnswered(a1, JoinSendAnswer.OpenParty(party()), 9, false))
        assertEquals(a2, assertIs<OutgoingJoinRequestState.Sending>(late.state).binding)
        assertTrue(late.effects.single() is OutgoingJoinEffect.ReleaseOrphanedSend)
    }

    @Test fun aWipeWithNoNetworkClearsLocallyOnly() {
        val t = reduceOutgoingJoinRequest(pending(), OutgoingJoinEvent.IdentityBoundary("profileA", serverCleanup = false))
        assertEquals(OutgoingJoinRequestState.Idle, t.state)
        assertTrue(t.effects.none { it is OutgoingJoinEffect.CancelOnServer || it is OutgoingJoinEffect.DepartParty })
        assertTrue(OutgoingJoinEffect.RememberAbandoned("profileA", "req1") in t.effects)
    }

    @Test fun reactivatingTheSameProfileDoesNotResurrectTheRequest() {
        val cleared = reduceOutgoingJoinRequest(pending(), OutgoingJoinEvent.IdentityBoundary("profileA", true)).state
        // Same profile, but the store has minted a new token; the old one's events stay inert.
        val stale = reduceOutgoingJoinRequest(cleared, OutgoingJoinEvent.StatusRead(a1, "req1", JoinRequestServerStatus.pending, null, 200_000, 6_000, false))
        assertEquals(OutgoingJoinRequestState.Idle, stale.state)
        val fresh = pending(binding = a1.copy(token = 2))
        val oldToken = reduceOutgoingJoinRequest(fresh, statusRead(JoinRequestServerStatus.declined, binding = a1))
        assertIs<OutgoingJoinRequestState.Pending>(oldToken.state)
    }

    @Test fun aBoundaryWithNothingPendingIsANoOp() {
        val t = reduceOutgoingJoinRequest(OutgoingJoinRequestState.Idle, OutgoingJoinEvent.IdentityBoundary("profileA", true))
        assertEquals(OutgoingJoinRequestState.Idle, t.state)
        assertEquals(listOf<OutgoingJoinEffect>(OutgoingJoinEffect.CancelJobs), t.effects)
    }

    @Test fun anOutcomeBoundaryShowsNoPill() {
        val declined = reduce(pending(), statusRead(JoinRequestServerStatus.declined)).state
        assertEquals(OutgoingJoinRequestState.Idle, reduceOutgoingJoinRequest(declined, OutgoingJoinEvent.IdentityBoundary("profileA", true)).state)
    }
}

class WatchingNowJoinAffordanceTest {
    private fun item(policy: WatchJoinPolicy, session: String = "s1", who: String = "seraph") = WatchingNowItem(
        profile = SocialProfileSummary(profileId = who, handle = who, displayName = who),
        contentId = "tt1",
        contentType = "movie",
        videoId = "tt1",
        title = "Mayday",
        sessionId = session,
        effectiveJoinPolicy = policy,
        positionMs = 0,
        durationMs = 0,
        state = SocialPlaybackState.playing,
        heartbeatAt = "2026-09-15T00:00:00Z",
    )

    private val binding = JoinRequestBinding("me", 1)
    private val content = JoinRequestContent("tt1", "tt1", "Mayday")
    private fun target(session: String = "s1") = JoinRequestTarget("seraph", "Seraph", sessionId = session)

    private fun partyWith(vararg ids: String) = WatchPartyState(
        id = "p", hostProfileId = ids.first(), status = WatchPartyStatus.playing,
        controlMode = WatchPartyControlMode.host_only, contentGeneration = 1,
        content = PartyContent("tt1", "movie", "tt1", "Mayday"), positionMs = 0, durationMs = 0, playbackSpeed = 1f,
        sequence = 1, stateUpdatedAt = "",
        members = ids.map { WatchPartyParticipant(profileId = it, role = "participant", readyState = SourceResolutionState.ready, joinedAt = "") },
    )

    @Test fun policyDecidesTheIdleButton() {
        val idle = OutgoingJoinRequestState.Idle
        assertEquals(WatchingNowJoinAffordance.Join, watchingNowJoinAffordance(item(WatchJoinPolicy.direct), idle, null))
        assertEquals(WatchingNowJoinAffordance.AskToJoin, watchingNowJoinAffordance(item(WatchJoinPolicy.approval), idle, null))
        assertEquals(WatchingNowJoinAffordance.None, watchingNowJoinAffordance(item(WatchJoinPolicy.disabled), idle, null))
        assertNull(WatchingNowJoinAffordance.None.label)
    }

    @Test fun aPendingRequestForThisSessionReadsRequested() {
        val pending = OutgoingJoinRequestState.Pending(binding, target(), content, "r", 99_000)
        assertEquals(WatchingNowJoinAffordance.Requested(99_000, false), watchingNowJoinAffordance(item(WatchJoinPolicy.approval), pending, null))
        // Another session of the same friend is not the one requested.
        assertEquals(WatchingNowJoinAffordance.AskToJoin, watchingNowJoinAffordance(item(WatchJoinPolicy.approval, session = "s2"), pending, null))
    }

    @Test fun sendingAcceptedAndJoiningReadJoining() {
        val sending = OutgoingJoinRequestState.Sending(binding, target(), content)
        assertEquals(WatchingNowJoinAffordance.Joining, watchingNowJoinAffordance(item(WatchJoinPolicy.direct), sending, null))
        assertEquals("Joining…", WatchingNowJoinAffordance.Joining.label)
    }

    @Test fun anOutcomeOffersTheActionAgain() {
        val declined = OutgoingJoinRequestState.Outcome(binding, target(), content, OutgoingJoinOutcome.Declined, 1L)
        assertEquals(WatchingNowJoinAffordance.AskToJoin, watchingNowJoinAffordance(item(WatchJoinPolicy.approval), declined, null))
    }

    @Test fun sameTitleCardsSitTogetherButAreNeverMerged() {
        val a1 = item(WatchJoinPolicy.direct, session = "a", who = "ahmed")
        val b = item(WatchJoinPolicy.direct, session = "b", who = "seraph").copy(contentId = "tt9", videoId = "tt9")
        val a2 = item(WatchJoinPolicy.direct, session = "c", who = "debug")
        val ordered = orderWatchingNowForDisplay(listOf(a1, b, a2))
        assertEquals(listOf("a", "c", "b"), ordered.map { it.sessionId })
        assertEquals(3, ordered.size)
    }

    @Test fun aFriendAlreadyInYourPartyIsNotAJoinTarget() {
        assertEquals(
            WatchingNowJoinAffordance.InYourParty,
            watchingNowJoinAffordance(item(WatchJoinPolicy.disabled), OutgoingJoinRequestState.Idle, partyWith("me", "seraph")),
        )
        assertEquals(
            WatchingNowJoinAffordance.Join,
            watchingNowJoinAffordance(item(WatchJoinPolicy.direct), OutgoingJoinRequestState.Idle, partyWith("me", "other")),
        )
    }
}
