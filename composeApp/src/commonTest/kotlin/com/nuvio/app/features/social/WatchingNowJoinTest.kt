package com.nuvio.app.features.social

import com.nuvio.app.features.watchparty.ActivePlaybackContext
import com.nuvio.app.features.watchparty.PartyContent
import com.nuvio.app.features.watchparty.PartyPromotionPreflight
import com.nuvio.app.features.watchparty.PartySourceDescriptorV2
import com.nuvio.app.features.watchparty.PartySourceOriginKind
import com.nuvio.app.features.watchparty.SourceResolutionState
import com.nuvio.app.features.watchparty.WatchPartyParticipant
import com.nuvio.app.features.watchparty.WatchPartyControlMode
import com.nuvio.app.features.watchparty.WatchPartyState
import com.nuvio.app.features.watchparty.WatchPartyStatus
import com.nuvio.app.features.watchparty.decidePartyPromotionPreflight
import com.nuvio.app.features.watchparty.shouldAdoptDiscoveredParty
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Hardware Bug 6 (2026-09-15): Ask to join and Join did nothing on either client. */
class WatchingNowJoinTest {
    private fun party(
        id: String = "party",
        host: String = "host",
        status: WatchPartyStatus = WatchPartyStatus.playing,
        members: List<String> = emptyList(),
        contentId: String = "tt1",
        videoId: String = "tt1",
        origin: String? = null,
    ) = WatchPartyState(
        id = id,
        hostProfileId = host,
        status = status,
        controlMode = WatchPartyControlMode.host_only,
        contentGeneration = 1,
        sourceGeneration = 1,
        content = PartyContent(contentId = contentId, contentType = "movie", videoId = videoId, title = "Movie"),
        sourceFingerprint = null,
        positionMs = 0,
        durationMs = 0,
        playbackSpeed = 1f,
        sequence = 1,
        stateUpdatedAt = "2026-09-15T00:00:00Z",
        authorityEpoch = 1,
        members = members.map { profileId ->
            WatchPartyParticipant(
                profileId = profileId,
                role = if (profileId == host) "host" else "participant",
                readyState = SourceResolutionState.fetching,
                joinedAt = "2026-09-15T00:00:00Z",
            )
        },
        originPresenceSessionId = origin,
    )

    // The guest's button -------------------------------------------------------------------

    @Test fun aDirectJoinOpensThePartyTheServerMadeThisMemberPartOf() {
        val step = decideWatchingNowJoin(Result.success(SocialActionResult("joined", party = party())), "host")
        assertEquals("party", assertIs<WatchingNowJoinStep.OpenParty>(step).party.id)
        // Already a member (a second press, or a membership from before): the same answer.
        assertIs<WatchingNowJoinStep.OpenParty>(
            decideWatchingNowJoin(Result.success(SocialActionResult("already_joined", party = party())), "host"),
        )
    }

    @Test fun askingToJoinWaitsForTheHostInsteadOfStoppingAtAToast() {
        assertEquals(
            WatchingNowJoinStep.AwaitApproval(requestId = "r1", expiresAtMs = 1_789_459_320_000L),
            decideWatchingNowJoin(
                Result.success(SocialActionResult("approval_required", requestId = "r1", expiresAt = "2026-09-15T08:02:00+00:00")),
                "host",
            ),
        )
    }

    @Test fun everyRefusalSaysSomething() {
        listOf("disabled", "stale", "full", "unsupported_contract", "something_new").forEach { outcome ->
            val notice = assertIs<WatchingNowJoinStep.Notice>(
                decideWatchingNowJoin(Result.success(SocialActionResult(outcome)), "host"),
                "outcome=$outcome",
            )
            assertTrue(notice.message.isNotBlank())
        }
        // ⚠ The failure branch that used to be missing entirely.
        val failed = assertIs<WatchingNowJoinStep.Notice>(
            decideWatchingNowJoin(Result.failure(IllegalStateException("friendship_required (42501)")), "host"),
        )
        assertEquals("You can only join friends' playback", failed.message)
        assertIs<WatchingNowJoinStep.Notice>(decideWatchingNowJoin(Result.failure(RuntimeException()), "host"))
    }

    @Test fun aJoinedOutcomeWithoutALivePartyIsNotOpened() {
        assertIs<WatchingNowJoinStep.Notice>(decideWatchingNowJoin(Result.success(SocialActionResult("joined")), "host"))
        assertIs<WatchingNowJoinStep.Notice>(
            decideWatchingNowJoin(
                Result.success(SocialActionResult("joined", party = party(status = WatchPartyStatus.ended))),
                "host",
            ),
        )
    }

    // The guest's wait -----------------------------------------------------------------------

    @Test fun anAcceptedRequestIsFoundAndOpened() {
        val poll = decideJoinApprovalPoll(elapsedMs = 9_000, heldLivePartyId = null, probe = Result.success(party()))
        assertEquals("party", assertIs<JoinApprovalPoll.Joined>(poll).party.id)
    }

    @Test fun waitingContinuesThroughSilenceAndThroughAFailedProbe() {
        assertEquals(JoinApprovalPoll.Continue, decideJoinApprovalPoll(3_000, null, Result.success(null)))
        // The network blinking is not an answer.
        assertEquals(JoinApprovalPoll.Continue, decideJoinApprovalPoll(3_000, null, Result.failure(RuntimeException())))
        // An ended party is not an acceptance.
        assertEquals(
            JoinApprovalPoll.Continue,
            decideJoinApprovalPoll(3_000, null, Result.success(party(status = WatchPartyStatus.ended))),
        )
    }

    @Test fun theWaitEndsWhenTheRequestCanNoLongerBeAccepted() {
        assertEquals(
            JoinApprovalPoll.GaveUp,
            decideJoinApprovalPoll(WatchingNowApprovalWatchMs, null, Result.success(null)),
        )
        // But an acceptance that lands on the last tick still wins.
        assertIs<JoinApprovalPoll.Joined>(decideJoinApprovalPoll(WatchingNowApprovalWatchMs, null, Result.success(party())))
    }

    @Test fun aMemberAlreadyInAPartyIsNeverDraggedToAnother() {
        // Rejection, cancellation and a stale request must not corrupt party state: a party this
        // member reached some other way is left exactly as it is.
        assertEquals(
            JoinApprovalPoll.Superseded,
            decideJoinApprovalPoll(6_000, heldLivePartyId = "their-own-party", probe = Result.success(party())),
        )
    }

    // The host ---------------------------------------------------------------------------------

    @Test fun joinRequestsAreDeliveredToAnApprovalHostOnItsHeartbeat() {
        assertTrue(shouldPollForJoinRequests(WatchJoinPolicy.approval))
        assertFalse(shouldPollForJoinRequests(WatchJoinPolicy.direct), "direct joins create no request")
        assertFalse(shouldPollForJoinRequests(WatchJoinPolicy.disabled))
    }

    @Test fun aJoinablePlayerWithNoPartyLooksForOneBuiltFromIt() {
        assertTrue(shouldDiscoverPromotedParty(WatchJoinPolicy.direct, heldParty = null))
        assertTrue(shouldDiscoverPromotedParty(WatchJoinPolicy.approval, heldParty = null))
        assertTrue(shouldDiscoverPromotedParty(WatchJoinPolicy.direct, party(status = WatchPartyStatus.ended)))
        assertFalse(shouldDiscoverPromotedParty(WatchJoinPolicy.disabled, heldParty = null))
        assertFalse(shouldDiscoverPromotedParty(WatchJoinPolicy.direct, party()), "already in a live party")
    }

    @Test fun onlyTheHostAdoptsADiscoveredPartyInPlace() {
        assertTrue(shouldAdoptDiscoveredParty(party(host = "me"), selfProfileId = "me", heldLiveParty = null))
        // ⚠ A guest whose request was just accepted is a member too, but that party is not built
        // from the guest's own playback; the guest reaches it through its lobby instead.
        assertFalse(shouldAdoptDiscoveredParty(party(host = "friend"), selfProfileId = "me", heldLiveParty = null))
        assertFalse(shouldAdoptDiscoveredParty(party(host = "me"), selfProfileId = "me", heldLiveParty = party(id = "other")))
        assertFalse(shouldAdoptDiscoveredParty(party(host = "me", status = WatchPartyStatus.ended), "me", null))
        assertFalse(shouldAdoptDiscoveredParty(party(host = "me"), selfProfileId = null, heldLiveParty = null))
    }

    // A Direct Join aimed at a friend: their party, or a clean failure ---------------------------

    @Test fun aJoinOnlyOpensAPartyTheTargetIsIn() {
        // The target as host (a fresh promotion), and as a member of a party someone else hosts.
        assertIs<WatchingNowJoinStep.OpenParty>(
            decideWatchingNowJoin(Result.success(SocialActionResult("joined", party = party(host = "friend"))), "friend"),
        )
        assertIs<WatchingNowJoinStep.OpenParty>(
            decideWatchingNowJoin(
                Result.success(
                    SocialActionResult("already_joined", party = party(host = "other", members = listOf("other", "friend", "me"))),
                ),
                "friend",
            ),
        )
    }

    @Test fun anAlreadyJoinedAnswerForTheRequestersOwnPartyIsNeverOpened() {
        // ⚠ Regression, hardware 2026-09-15. `social_join_watching` answers `already_joined` with ANY
        // live membership the requester holds. A stray party the guest itself hosts must never become
        // "the party I joined" - that is the guest landing in a lobby as its host.
        val stray = party(id = "stray", host = "me", members = listOf("me"))
        val step = decideWatchingNowJoin(Result.success(SocialActionResult("already_joined", party = stray)), "friend")
        assertEquals("stray", assertIs<WatchingNowJoinStep.ReleaseStrayMembership>(step).party.id)
        // A party the client really holds is the user's to leave; the button only explains.
        assertIs<WatchingNowJoinStep.Notice>(
            decideWatchingNowJoin(
                Result.success(SocialActionResult("already_joined", party = stray)),
                "friend",
                heldLivePartyId = "stray",
            ),
        )
    }

    @Test fun aStrayMembershipIsLeftOnceAndTheJoinAskedAgain() = runBlocking {
        val stray = party(id = "stray", host = "me", members = listOf("me"))
        val friends = party(id = "friends", host = "friend", members = listOf("friend", "me"))
        val answers = ArrayDeque(
            listOf(SocialActionResult("already_joined", party = stray), SocialActionResult("joined", party = friends)),
        )
        val departed = mutableListOf<String>()
        val step = joinWatchingNow(
            item = watching("friend"),
            heldLivePartyId = { null },
            join = { Result.success(answers.removeFirst()) },
            departStray = { departed += it; Result.success(Unit) },
        )
        assertEquals("friends", assertIs<WatchingNowJoinStep.OpenParty>(step).party.id)
        assertEquals(listOf("stray"), departed)
    }

    @Test fun aJoinThatKeepsAnsweringWithAStrayFailsCleanlyInsteadOfLooping() = runBlocking {
        val stray = party(id = "stray", host = "me", members = listOf("me"))
        var joins = 0
        val departed = mutableListOf<String>()
        val step = joinWatchingNow(
            item = watching("friend"),
            heldLivePartyId = { null },
            join = { joins += 1; Result.success(SocialActionResult("already_joined", party = stray)) },
            departStray = { departed += it; Result.success(Unit) },
        )
        assertIs<WatchingNowJoinStep.Notice>(step)
        assertEquals(2, joins)
        assertEquals(listOf("stray"), departed)
        // A departure the server refused is also a clean stop, not a second join.
        joins = 0
        assertIs<WatchingNowJoinStep.Notice>(
            joinWatchingNow(
                item = watching("friend"),
                heldLivePartyId = { null },
                join = { joins += 1; Result.success(SocialActionResult("already_joined", party = stray)) },
                departStray = { Result.failure(IllegalStateException("network")) },
            ),
        )
        assertEquals(1, joins)
    }

    @Test fun aSecondPressWhileAJoinIsInFlightSendsNothing() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var joins = 0
        val first = async {
            joinWatchingNow(
                item = watching("friend"),
                heldLivePartyId = { null },
                join = {
                    joins += 1
                    entered.complete(Unit)
                    release.await()
                    Result.success(SocialActionResult("joined", party = party(host = "friend")))
                },
                departStray = { Result.success(Unit) },
            )
        }
        entered.await()
        val second = joinWatchingNow(
            item = watching("friend"),
            heldLivePartyId = { null },
            join = { joins += 1; Result.success(SocialActionResult("joined", party = party(host = "friend"))) },
            departStray = { Result.success(Unit) },
        )
        release.complete(Unit)
        assertEquals(null, second)
        assertIs<WatchingNowJoinStep.OpenParty>(first.await())
        assertEquals(1, joins)
    }

    // "Start Watch Together" never displaces a party the server already holds -------------------

    @Test fun promotionCreatesOnlyWhenTheProfileHoldsNoLiveParty() {
        assertEquals(
            PartyPromotionPreflight.Promote,
            decidePartyPromotionPreflight(Result.success(null), "me", playback(), "s2"),
        )
        assertEquals(
            PartyPromotionPreflight.Promote,
            decidePartyPromotionPreflight(
                Result.success(party(host = "me", status = WatchPartyStatus.ended)),
                "me",
                playback(),
                "s2",
            ),
        )
        // A probe that failed cannot show that promoting is harmless.
        assertEquals(
            PartyPromotionPreflight.Unverified,
            decidePartyPromotionPreflight(Result.failure(RuntimeException()), "me", playback(), "s2"),
        )
    }

    @Test fun aHostPromotingPlaybackAFriendAlreadyJoinedAdoptsThatPartyInsteadOfBuildingASecond() {
        // ⚠ Regression, hardware 2026-09-15 (party e7d7fc48): the direct join built the party from
        // presence session s1; the host, not yet having adopted it, promoted session s2. The server
        // made a second party and the single-membership trigger handed the first one to the guest.
        val joined = party(id = "joined", host = "me", members = listOf("me", "guest"), origin = "s1")
        assertEquals(
            "joined",
            assertIs<PartyPromotionPreflight.Adopt>(
                decidePartyPromotionPreflight(Result.success(joined), "me", playback(), presenceSessionId = "s2"),
            ).party.id,
        )
        // And by origin alone, when the content fields disagree.
        assertIs<PartyPromotionPreflight.Adopt>(
            decidePartyPromotionPreflight(Result.success(joined), "me", playback(videoId = "other"), presenceSessionId = "s1"),
        )
    }

    @Test fun promotionNeverSilentlyLeavesADifferentLiveParty() {
        // Someone else's party this profile is a guest in.
        assertEquals(
            PartyPromotionPreflight.AlreadyInAnotherParty,
            decidePartyPromotionPreflight(
                Result.success(party(host = "friend", members = listOf("friend", "me"))),
                "me",
                playback(),
                "s2",
            ),
        )
        // A party this profile hosts for something else entirely.
        assertEquals(
            PartyPromotionPreflight.AlreadyInAnotherParty,
            decidePartyPromotionPreflight(
                Result.success(party(host = "me", contentId = "tt9", videoId = "tt9", origin = "s0")),
                "me",
                playback(),
                "s2",
            ),
        )
    }

    private fun playback(contentId: String = "tt1", videoId: String = "tt1") = ActivePlaybackContext(
        attachmentId = "a",
        contentId = contentId,
        videoId = videoId,
        descriptor = PartySourceDescriptorV2(
            originKind = PartySourceOriginKind.addon,
            originId = "org.example",
            releaseFingerprint = "sha256:" + "0123456789abcdef".repeat(4),
        ),
        positionMs = 0,
        durationMs = 1,
        playbackSpeed = 1f,
    )

    private fun watching(profileId: String) = WatchingNowItem(
        profile = SocialProfileSummary(profileId = profileId, handle = profileId, displayName = profileId),
        contentId = "tt1",
        contentType = "movie",
        videoId = "tt1",
        title = "Movie",
        sessionId = "s1",
        effectiveJoinPolicy = WatchJoinPolicy.direct,
        positionMs = 0,
        durationMs = 1,
        state = SocialPlaybackState.playing,
        heartbeatAt = "2026-09-15T00:00:00Z",
    )
}
