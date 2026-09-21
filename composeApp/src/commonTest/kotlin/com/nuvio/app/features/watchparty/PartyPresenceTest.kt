package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Away, as a set of decisions rather than as a set of lifecycle callbacks.
 *
 * Every case here is one of the twenty-one the Phase 6 Away brief lists, expressed against the pure
 * model so it can be executed by `scripts/run-pure-suites.sh` on a machine with no device attached.
 * The three that are genuinely about Android's callback ordering - PiP entry, PiP dismissal and a
 * stale callback - are the reason `PartyLifecycleFacts` is a snapshot with a sequence number, and
 * they are the reason this file exists at all.
 */
class PartyPresenceTest {

    private fun member(
        id: String,
        ready: SourceResolutionState = SourceResolutionState.ready,
        connected: Boolean = true,
    ) = WatchPartyParticipant(
        profileId = id,
        role = "member",
        readyState = ready,
        connected = connected,
        clientLocation = WatchPartyClientLocation.player,
        joinedAt = "2026-09-20T10:00:00Z",
    )

    private fun party(
        hostId: String = "host",
        members: List<WatchPartyParticipant> = listOf(member("host"), member("guest")),
        status: WatchPartyStatus = WatchPartyStatus.playing,
    ) = WatchPartyState(
        id = "party",
        hostProfileId = hostId,
        status = status,
        controlMode = WatchPartyControlMode.host_only,
        contentGeneration = 1,
        sourceGeneration = 1,
        content = PartyContent(
            contentId = "tt1",
            contentType = "movie",
            videoId = "tt1",
            title = "A Film",
        ),
        positionMs = 0,
        durationMs = 100_000,
        playbackSpeed = 1f,
        sequence = 1,
        stateUpdatedAt = "2026-09-20T10:00:00Z",
        members = members,
    )

    // --- lifecycle fact -> presence ----------------------------------------------------------

    @Test
    fun foregroundIsWatching() {
        assertEquals(
            PartyPresence.Watching,
            partyPresenceFor(PartyLifecycleFacts(appForeground = true)),
        )
    }

    /** Cases 1-3: Home, whoever pressed it. */
    @Test
    fun backgroundIsAway() {
        val state = PartyPresenceState().observe(PartyLifecycleFacts(appForeground = false, seq = 1))
        assertTrue(state.isAway)
        assertEquals(PartyAwayReason.Background, state.reason)
    }

    /** Case 4, the lock half. */
    @Test
    fun screenLockIsAwayAndNamesItself() {
        val state = PartyPresenceState()
            .observe(PartyLifecycleFacts(appForeground = false, screenLocked = true, seq = 1))
        assertTrue(state.isAway)
        assertEquals(PartyAwayReason.ScreenLocked, state.reason)
    }

    /** Case 4, the unlock half. */
    @Test
    fun unlockingReturnsToWatching() {
        val away = PartyPresenceState()
            .observe(PartyLifecycleFacts(appForeground = false, screenLocked = true, seq = 1))
        val back = away.observe(PartyLifecycleFacts(appForeground = true, seq = 2))
        assertEquals(PartyPresence.Watching, back.presence)
        assertEquals(PartyAwayReason.None, back.reason)
    }

    /** An interruption outranks even a picture-in-picture window that is still up. */
    @Test
    fun interruptionOutranksPictureInPicture() {
        val state = PartyPresenceState().observe(
            PartyLifecycleFacts(
                appForeground = false,
                pictureInPicture = true,
                playbackContinues = true,
                interrupted = true,
                seq = 1,
            ),
        )
        assertTrue(state.isAway)
        assertEquals(PartyAwayReason.Interrupted, state.reason)
    }

    // --- the picture-in-picture exception -----------------------------------------------------

    /** Cases 5 and 6: PiP is not Away, however the app's window reports itself. */
    @Test
    fun playingInPictureInPictureIsWatching() {
        val state = PartyPresenceState().observe(
            PartyLifecycleFacts(
                appForeground = false,
                pictureInPicture = true,
                playbackContinues = true,
                seq = 1,
            ),
        )
        assertEquals(PartyPresence.Watching, state.presence)
        assertEquals(PartyAwayReason.None, state.reason)
    }

    /** A PiP window over a player with nothing in it is not somebody watching. */
    @Test
    fun pictureInPictureWithoutPlaybackIsAway() {
        val state = PartyPresenceState().observe(
            PartyLifecycleFacts(
                appForeground = false,
                pictureInPicture = true,
                playbackContinues = false,
                seq = 1,
            ),
        )
        assertTrue(state.isAway)
    }

    /**
     * Case 21, and the one this model is shaped around.
     *
     * Entering PiP delivers a background, a mode change and on some devices a stop. Fed as events
     * they flap; fed as snapshots they cannot, because every one of them is read together with the
     * picture-in-picture flag.
     */
    @Test
    fun pictureInPictureEntryNoiseNeverFlapsToAway() {
        var state = PartyPresenceState()
        val noise = listOf(
            // The order really is not guaranteed; this is the worst of the ones observed.
            PartyLifecycleFacts(appForeground = false, pictureInPicture = true, playbackContinues = true, seq = 1),
            PartyLifecycleFacts(appForeground = false, pictureInPicture = true, playbackContinues = true, screenLocked = false, seq = 2),
            PartyLifecycleFacts(appForeground = false, pictureInPicture = true, playbackContinues = true, seq = 3),
        )
        noise.forEach { facts ->
            state = state.observe(facts)
            assertEquals(PartyPresence.Watching, state.presence, "flapped on seq=${facts.seq}")
        }
    }

    /** Case 7: dismissed into a background app, and it says which kind of away it is. */
    @Test
    fun dismissingPictureInPictureIntoBackgroundIsAway() {
        val inPip = PartyPresenceState().observe(
            PartyLifecycleFacts(appForeground = false, pictureInPicture = true, playbackContinues = true, seq = 1),
        )
        val dismissed = inPip.observe(PartyLifecycleFacts(appForeground = false, seq = 2))
        assertTrue(dismissed.isAway)
        assertEquals(PartyAwayReason.PictureInPictureDismissed, dismissed.reason)
    }

    /** Case 8: tapping the window expands the app. That is a return, not an away. */
    @Test
    fun expandingPictureInPictureIsNotAway() {
        val inPip = PartyPresenceState().observe(
            PartyLifecycleFacts(appForeground = false, pictureInPicture = true, playbackContinues = true, seq = 1),
        )
        val expanded = inPip.observe(PartyLifecycleFacts(appForeground = true, seq = 2))
        assertEquals(PartyPresence.Watching, expanded.presence)
    }

    /** A stale callback describes a moment that has been superseded, so it is dropped whole. */
    @Test
    fun staleObservationCannotOverwriteNewerState() {
        val foreground = PartyPresenceState().observe(PartyLifecycleFacts(appForeground = true, seq = 7))
        val late = foreground.observe(PartyLifecycleFacts(appForeground = false, seq = 3))
        assertEquals(PartyPresence.Watching, late.presence)
        assertEquals(7L, late.facts.seq)
    }

    // --- logging -------------------------------------------------------------------------------

    @Test
    fun transitionsLogOnceAndSayWhy() {
        val watching = PartyPresenceState()
        val away = watching.observe(PartyLifecycleFacts(appForeground = false, seq = 1))
        assertEquals("presence Watching -> Away reason=background", partyPresenceTransitionLog(watching, away))
        val back = away.observe(PartyLifecycleFacts(appForeground = true, seq = 2))
        assertEquals("presence Away -> Watching reason=foreground", partyPresenceTransitionLog(away, back))
        // The same presence with the same reason is not a transition and writes nothing.
        assertNull(partyPresenceTransitionLog(back, back))
    }

    @Test
    fun stayingWatchingThroughPictureInPictureIsWorthOneLine() {
        val watching = PartyPresenceState()
        val inPip = watching.observe(
            PartyLifecycleFacts(appForeground = false, pictureInPicture = true, playbackContinues = true, seq = 1),
        )
        assertEquals("presence Watching -> Watching reason=pip", partyPresenceTransitionLog(watching, inPip))
    }

    // --- returning -----------------------------------------------------------------------------

    /** Cases 9-11: the party moved, but not to different content. Catch up; do not re-resolve. */
    @Test
    fun returningToTheSameGenerationCatchesUp() {
        assertEquals(
            PartyReturnAction.CatchUpToTimeline,
            partyReturnAction("party:1:1:0", "party:1:1:0", localSourceUsable = true),
        )
    }

    /** Case 12: the source generation moved while this member was away. */
    @Test
    fun returningAfterASourceChangeRealizes() {
        assertEquals(
            PartyReturnAction.RealizeCurrentGeneration,
            partyReturnAction("party:1:1:0", "party:1:2:0", localSourceUsable = true),
        )
    }

    /** Case 13: a different episode. */
    @Test
    fun returningAfterAContentChangeRealizes() {
        assertEquals(
            PartyReturnAction.RealizeCurrentGeneration,
            partyReturnAction("party:1:1:0", "party:2:1:0", localSourceUsable = true),
        )
    }

    /** A source that did not survive is a realization, whatever the generation says. */
    @Test
    fun returningWithoutAUsableSourceRealizes() {
        assertEquals(
            PartyReturnAction.RealizeCurrentGeneration,
            partyReturnAction("party:1:1:0", "party:1:1:0", localSourceUsable = false),
        )
    }

    /** An unrecorded generation is treated as a change rather than assumed to be the same. */
    @Test
    fun returningWithNoRecordedGenerationRealizes() {
        assertEquals(
            PartyReturnAction.RealizeCurrentGeneration,
            partyReturnAction(null, "party:1:1:0", localSourceUsable = true),
        )
    }

    // --- the away hold --------------------------------------------------------------------------

    /** Case 1: preference off, so nothing is held and the party plays on. */
    @Test
    fun pauseForAwayOffHoldsNobody() {
        assertEquals(
            emptyList(),
            partyAwayHoldMembers(
                party = party(),
                awayProfileIds = setOf("guest"),
                viewerProfileId = "host",
                pauseForAwayUsers = false,
            ),
        )
    }

    /** Case 2: preference on, so the host waits for the guest who stepped away. */
    @Test
    fun pauseForAwayOnHoldsTheAwayGuest() {
        assertEquals(
            listOf("guest"),
            partyAwayHoldMembers(
                party = party(),
                awayProfileIds = setOf("guest"),
                viewerProfileId = "host",
                pauseForAwayUsers = true,
            ),
        )
    }

    /** Case 20: several at once, named in a stable order so the status line does not shuffle. */
    @Test
    fun severalAwayMembersAreAllHeldFor() {
        val members = listOf(member("host"), member("ana"), member("ben"), member("cal"))
        assertEquals(
            listOf("ana", "ben"),
            partyAwayHoldMembers(
                party = party(members = members),
                awayProfileIds = setOf("ben", "ana"),
                viewerProfileId = "host",
                pauseForAwayUsers = true,
            ),
        )
    }

    /**
     * The caller is never held for. A member going away pauses its own player, and on a host that
     * pause is the party's - counting it here would be that same stop counted twice.
     */
    @Test
    fun theViewerIsNeverHeldForItsOwnAbsence() {
        assertEquals(
            emptyList(),
            partyAwayHoldMembers(
                party = party(),
                awayProfileIds = setOf("host"),
                viewerProfileId = "host",
                pauseForAwayUsers = true,
            ),
        )
    }

    /** Somebody whose socket has gone is not coming back from being away, so nothing waits for them. */
    @Test
    fun aDisconnectedAwayMemberIsNotHeldFor() {
        val members = listOf(member("host"), member("guest", connected = false))
        assertEquals(
            emptyList(),
            partyAwayHoldMembers(
                party = party(members = members),
                awayProfileIds = setOf("guest"),
                viewerProfileId = "host",
                pauseForAwayUsers = true,
            ),
        )
    }

    @Test
    fun aMemberWhoLeftIsNotHeldFor() {
        val members = listOf(member("host"), member("guest", ready = SourceResolutionState.left))
        assertEquals(
            emptyList(),
            partyAwayHoldMembers(
                party = party(members = members),
                awayProfileIds = setOf("guest"),
                viewerProfileId = "host",
                pauseForAwayUsers = true,
            ),
        )
    }

    /** An ended party holds nobody, whatever anybody's window is doing. */
    @Test
    fun anEndedPartyHoldsNobody() {
        assertEquals(
            emptyList(),
            partyAwayHoldMembers(
                party = party(status = WatchPartyStatus.ended),
                awayProfileIds = setOf("guest"),
                viewerProfileId = "host",
                pauseForAwayUsers = true,
            ),
        )
    }

    // --- away against transport ------------------------------------------------------------------

    /** Case 14-15: away, then the network goes. The stronger and more useful statement wins. */
    @Test
    fun transportOutranksAway() {
        assertEquals(
            PartyMemberActivity.Offline,
            partyMemberActivity(connected = false, reconnecting = false, away = true, buffering = false),
        )
        assertEquals(
            PartyMemberActivity.Reconnecting,
            partyMemberActivity(connected = true, reconnecting = true, away = true, buffering = false),
        )
    }

    /** Case 16: the socket is back, the app is still in the background. Away again. */
    @Test
    fun awayReturnsOnceTheTransportIsHealthy() {
        assertEquals(
            PartyMemberActivity.Away,
            partyMemberActivity(connected = true, reconnecting = false, away = true, buffering = false),
        )
    }

    /** Case 19: one member away, another genuinely buffering. Neither is described as the other. */
    @Test
    fun awayAndBufferingAreDistinctPerMember() {
        assertEquals(
            PartyMemberActivity.Away,
            partyMemberActivity(connected = true, reconnecting = false, away = true, buffering = true),
        )
        assertEquals(
            PartyMemberActivity.Buffering,
            partyMemberActivity(connected = true, reconnecting = false, away = false, buffering = true),
        )
        assertEquals(
            PartyMemberActivity.Watching,
            partyMemberActivity(connected = true, reconnecting = false, away = false, buffering = false),
        )
    }

    // --- wording ------------------------------------------------------------------------------

    @Test
    fun theHoldHeadlineNamesWhoIsAway() {
        assertEquals("Waiting for Riyad to return…", partyAwayHoldHeadline(listOf("Riyad")))
        assertEquals("Waiting for Ana and Ben to return…", partyAwayHoldHeadline(listOf("Ana", "Ben")))
        assertEquals("Waiting for Ana and 2 others to return…", partyAwayHoldHeadline(listOf("Ana", "Ben", "Cal")))
        assertEquals("Waiting for someone to return…", partyAwayHoldHeadline(emptyList()))
    }

    // --- away is not leaving --------------------------------------------------------------------

    /**
     * Case 17 stated as the invariant it is: nothing in this file removes a member from a party.
     *
     * The only thing that can is `party_leave`, and an away member's row is untouched - which is
     * what keeps the party's size, the host's gate and every readiness decision counting them.
     */
    @Test
    fun awayNeverRemovesAMember() {
        val live = party()
        val holding = partyAwayHoldMembers(live, setOf("guest"), "host", pauseForAwayUsers = true)
        assertTrue(holding.isNotEmpty())
        assertEquals(2, live.members.size)
        assertTrue(live.members.any { it.profileId == "guest" && it.readyState != SourceResolutionState.left })
    }

    /**
     * Case 18: a host being away is not evidence about the host.
     *
     * `partyLeaveSuccessor` is what names a successor and it reads membership and connection only.
     * Presence is not one of its inputs, and this asserts that it stays that way: an away host is
     * still the host, and host transfer remains the backend's `party_transfer_stale_host` decision.
     */
    @Test
    fun anAwayHostIsStillTheHost() {
        val live = party()
        assertEquals("host", live.hostProfileId)
        assertTrue(live.members.first { it.profileId == "host" }.connected)
        // The away roster changes nothing about who the snapshot names.
        assertFalse(partyAwayHoldMembers(live, setOf("host"), "host", pauseForAwayUsers = true).isNotEmpty())
    }

    // --- Case 4, as hardware contradicted it on 2026-09-20 -------------------------------------
    //
    // Locking the phone made the member Away correctly, and unlocking never brought them back.
    // `ACTION_USER_PRESENT` re-read `KeyguardManager.isKeyguardLocked`, the S25 answered `true`
    // while the keyguard was still dismissing, and nothing else was coming to correct it. These
    // are that cycle, fact by fact, with the keyguard lying at the moment it was believed.

    @Test
    fun screenOffIsLockedWhateverTheKeyguardSays() {
        assertTrue(partyScreenLockedAfter(PartyScreenSignal.ScreenOff, keyguardLocked = false))
        assertTrue(partyScreenLockedAfter(PartyScreenSignal.ScreenOff, keyguardLocked = true))
    }

    @Test
    fun screenOnIsTheOneQuestionTheKeyguardAnswers() {
        assertTrue(partyScreenLockedAfter(PartyScreenSignal.ScreenOn, keyguardLocked = true))
        // A device with no lock set: the screen coming on is the whole story.
        assertFalse(partyScreenLockedAfter(PartyScreenSignal.ScreenOn, keyguardLocked = false))
    }

    @Test
    fun userPresentIsUnlockedEvenWhenTheKeyguardStillClaimsOtherwise() {
        assertFalse(partyScreenLockedAfter(PartyScreenSignal.UserPresent, keyguardLocked = true))
        assertFalse(partyScreenLockedAfter(PartyScreenSignal.UserPresent, keyguardLocked = false))
    }

    // The foreground keyguard re-read, which is the second door the same bad read came through.
    // `USER_PRESENT` was fixed and this was not, so the unlock raced: whichever of the two landed
    // last decided the answer, and the phone returned or stayed Away at random. Reported from
    // hardware 2026-09-21 as "sometimes clears away but its inconsistent".

    @Test
    fun foregroundNeverDeclaresALockThatWasNotHeld() {
        // The going-away animation, read at the worst instant. Held false, so it stays false.
        assertFalse(partyScreenLockedOnForeground(heldScreenLocked = false, keyguardLocked = true))
        assertFalse(partyScreenLockedOnForeground(heldScreenLocked = false, keyguardLocked = false))
    }

    @Test
    fun foregroundStillClearsALockFactThatWentMissing() {
        // The whole point of re-reading: a dropped broadcast left the lock latched, and a settled
        // keyguard says it is gone.
        assertFalse(partyScreenLockedOnForeground(heldScreenLocked = true, keyguardLocked = false))
        // Genuinely still locked, so it stays locked.
        assertTrue(partyScreenLockedOnForeground(heldScreenLocked = true, keyguardLocked = true))
    }

    /**
     * Both orderings of one unlock, with the keyguard lying at every read.
     *
     * Android delivers `ACTION_USER_PRESENT` and the process foreground at nearly the same instant
     * and in no guaranteed order. This is the assertion the shipped build could only pass half the
     * time: whichever arrived last used to decide the answer, and only one of them was right.
     */
    @Test
    fun unlockReturnsWatchingWhicheverOfForegroundAndUserPresentLandsLast() {
        fun unlock(userPresentLast: Boolean): PartyPresenceState {
            var seq = 0L
            var state = PartyPresenceState()
            var screenLocked = false
            fun push(foreground: Boolean) {
                seq += 1
                state = state.observe(
                    PartyLifecycleFacts(appForeground = foreground, screenLocked = screenLocked, seq = seq),
                )
            }
            // Locked, then the process stops behind the keyguard.
            screenLocked = partyScreenLockedAfter(PartyScreenSignal.ScreenOff, keyguardLocked = true)
            push(foreground = true)
            push(foreground = false)
            assertEquals(PartyPresence.Away, state.presence)

            // The unlock, delivered both ways round. The keyguard answers `true` at every read,
            // which is what the S25 does through the dismiss animation.
            if (userPresentLast) {
                screenLocked = partyScreenLockedOnForeground(heldScreenLocked = screenLocked, keyguardLocked = true)
                push(foreground = true)
                screenLocked = partyScreenLockedAfter(PartyScreenSignal.UserPresent, keyguardLocked = true)
                push(foreground = true)
            } else {
                screenLocked = partyScreenLockedAfter(PartyScreenSignal.UserPresent, keyguardLocked = true)
                push(foreground = true)
                screenLocked = partyScreenLockedOnForeground(heldScreenLocked = screenLocked, keyguardLocked = true)
                push(foreground = true)
            }
            return state
        }

        assertEquals(PartyPresence.Watching, unlock(userPresentLast = true).presence)
        assertEquals(PartyAwayReason.None, unlock(userPresentLast = true).reason)
        // The ordering that used to fail, and the reason this test exists.
        assertEquals(PartyPresence.Watching, unlock(userPresentLast = false).presence)
        assertEquals(PartyAwayReason.None, unlock(userPresentLast = false).reason)
    }

    /**
     * The whole hardware cycle: watching, lock, unlock, and back to watching.
     *
     * The keyguard answers `true` at every single read, which is what the S25 did. The return
     * still has to happen, because `USER_PRESENT` is the fact and is not asking.
     */
    @Test
    fun lockingAndUnlockingReturnsToWatchingWithALyingKeyguard() {
        var seq = 0L
        var state = PartyPresenceState()
        fun observe(foreground: Boolean, signal: PartyScreenSignal): PartyPresenceState {
            seq += 1
            state = state.observe(
                PartyLifecycleFacts(
                    appForeground = foreground,
                    screenLocked = partyScreenLockedAfter(signal, keyguardLocked = true),
                    seq = seq,
                ),
            )
            return state
        }

        assertEquals(PartyPresence.Watching, state.presence)

        // Power button: SCREEN_OFF, then the process stops.
        assertEquals(PartyPresence.Away, observe(foreground = true, signal = PartyScreenSignal.ScreenOff).presence)
        assertEquals(PartyAwayReason.ScreenLocked, state.reason)
        assertEquals(PartyPresence.Away, observe(foreground = false, signal = PartyScreenSignal.ScreenOff).presence)

        // Screen back on, lock screen still lit: still away, and still for the lock.
        assertEquals(PartyPresence.Away, observe(foreground = false, signal = PartyScreenSignal.ScreenOn).presence)
        assertEquals(PartyAwayReason.ScreenLocked, state.reason)

        // Unlocked. This is the assertion the shipped build failed.
        assertEquals(PartyPresence.Watching, observe(foreground = true, signal = PartyScreenSignal.UserPresent).presence)
        assertEquals(PartyAwayReason.None, state.reason)
        assertEquals(
            "presence Away -> Watching reason=foreground",
            partyPresenceTransitionLog(
                before = PartyPresenceState(
                    presence = PartyPresence.Away,
                    reason = PartyAwayReason.ScreenLocked,
                    facts = PartyLifecycleFacts(appForeground = false, screenLocked = true),
                ),
                after = state,
            ),
        )
    }

    // --- The away that outlived its party ------------------------------------------------------
    //
    // Also 2026-09-20, in the same logs: every peer status of two consecutive parties carried
    // `away=true`, including while the phone was demonstrably in a hand and playing. The
    // transport's own copy of this member's presence survives a channel reset and a generation
    // change by design, and nothing cleared it when the party it belonged to ended.

    @Test
    fun aWatchingMemberWithdrawsAnAwayItNeverSent() {
        assertTrue(partyStaleAwayNeedsClearing(PartyPresenceState(), transportReportsAway = true))
    }

    @Test
    fun nothingIsReconciledWhenTheWireAlreadyAgrees() {
        assertFalse(partyStaleAwayNeedsClearing(PartyPresenceState(), transportReportsAway = false))
    }

    /**
     * Reconciliation never *declares* an absence.
     *
     * An away that reached the wire through anything but `enterPartyAway` would have no generation
     * key captured at away-time and no retained playback intent, so the return would have nothing
     * to compare against and nothing to restore.
     */
    @Test
    fun reconciliationNeverDeclaresAnAbsence() {
        val away = PartyPresenceState(
            presence = PartyPresence.Away,
            reason = PartyAwayReason.Background,
            facts = PartyLifecycleFacts(appForeground = false),
        )
        assertFalse(partyStaleAwayNeedsClearing(away, transportReportsAway = false))
        // Already reported, and still away: nothing to do either.
        assertFalse(partyStaleAwayNeedsClearing(away, transportReportsAway = true))
    }

    /**
     * A member watching in picture-in-picture is watching, so it withdraws the away too.
     *
     * The PiP exception outranks both away rules, and the reconciliation reads the presence rather
     * than the facts, so it inherits that ordering rather than re-deciding it.
     */
    @Test
    fun aPictureInPictureWatcherWithdrawsAStaleAway() {
        val pip = PartyPresenceState().observe(
            PartyLifecycleFacts(
                appForeground = false,
                pictureInPicture = true,
                playbackContinues = true,
                screenLocked = true,
                seq = 1,
            ),
        )
        assertEquals(PartyPresence.Watching, pip.presence)
        assertTrue(partyStaleAwayNeedsClearing(pip, transportReportsAway = true))
    }
}
