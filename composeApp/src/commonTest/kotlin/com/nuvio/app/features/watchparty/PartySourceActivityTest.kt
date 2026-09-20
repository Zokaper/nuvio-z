package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the player says about a source, and the differences it used to lose.
 *
 * Every state here arrived on screen as "Matching source…" or "Loading source", which is why a
 * party that stopped could not be told apart from a party that was starting, a host that had
 * switched release from a guest whose own stream had died, and a member that needed somebody to
 * choose a source from one that simply needed ten more seconds. The copy is asserted rather than
 * described: it is the product surface, and a table that can drift from its own meaning is the
 * thing this file exists to stop.
 */
class PartySourceActivityTest {
    private fun inputs(
        isHost: Boolean = false,
        partyStarted: Boolean = false,
        realization: PartyRealizationPhase = PartyRealizationPhase.None,
        adopting: Boolean = false,
        hostAdvancing: Boolean = false,
        localAttempt: Int = 1,
        needsMatch: Boolean = false,
        alternate: Boolean = false,
        waitingForReadiness: Boolean = false,
        mediaReady: Boolean = false,
        buffering: Boolean = false,
    ) = PartySourceActivityInputs(
        inParty = true,
        isHost = isHost,
        partyStarted = partyStarted,
        realization = realization,
        adoptingNewPartySource = adopting,
        hostAdvancingPartySource = hostAdvancing,
        localAttempt = localAttempt,
        needsPartyMatch = needsMatch,
        usingCompatibleAlternate = alternate,
        waitingForPartyReadiness = waitingForReadiness,
        mediaReady = mediaReady,
        buffering = buffering,
    )

    @Test
    fun joiningIsNotAFailure() {
        val activity = partySourceActivity(inputs(realization = PartyRealizationPhase.Matching))
        assertEquals(PartySourceActivity.InitialPartyMatch, activity)
        val message = partySourceMessage(activity, hostName = "Ahmed")!!
        assertEquals("Joining playback", message.headline)
        assertEquals("Matching Ahmed's source…", message.detail)
        assertEquals(PartyStatusTone.Waiting, message.tone, "nothing has gone wrong yet")
    }

    @Test
    fun theHostChangingReleaseIsSaidPlainlyAndInTwoStages() {
        val matching = partySourceActivity(
            inputs(partyStarted = true, realization = PartyRealizationPhase.Matching, adopting = true),
        )
        assertEquals(PartySourceActivity.HostSourceChangedMatching, matching)
        assertEquals("Host source changed", partySourceMessage(matching)!!.headline)
        assertEquals("Finding a compatible source…", partySourceMessage(matching)!!.detail)

        val resolving = partySourceActivity(
            inputs(partyStarted = true, realization = PartyRealizationPhase.Resolving, adopting = true),
        )
        assertEquals(PartySourceActivity.HostSourceChangedResolving, resolving)
        assertEquals("Resolving the new source…", partySourceMessage(resolving)!!.detail)

        // Two situations, two lines - and two *identities*, or the debounce shows the first and
        // swallows the second.
        assertNotEquals(
            partySourceMessage(matching)!!.headline + partySourceMessage(matching)!!.detail,
            partySourceMessage(resolving)!!.headline + partySourceMessage(resolving)!!.detail,
        )
    }

    @Test
    fun aHostMovingThePartyIsToldItIsSwitching() {
        val activity = partySourceActivity(inputs(isHost = true, partyStarted = true, hostAdvancing = true))
        assertEquals(PartySourceActivity.HostSwitchingSource, activity)
        val message = partySourceMessage(activity, isHost = true)!!
        assertEquals("Switching source", message.headline)
        assertEquals("The current source failed. Finding a replacement…", message.detail)
    }

    @Test
    fun aLocalRetryNeverLooksLikeThePartyChangingSource() {
        // The host's own chain, still on the party's release: nobody else's playback is affected and
        // the copy must not imply otherwise.
        val host = partySourceActivity(inputs(isHost = true, partyStarted = true, localAttempt = 2))
        assertEquals(PartySourceActivity.LocalSourceRetry, host)
        assertEquals("Source failed", partySourceMessage(host, isHost = true)!!.headline)
        assertEquals("Trying another connection…", partySourceMessage(host, isHost = true)!!.detail)

        val guest = partySourceActivity(inputs(partyStarted = true, localAttempt = 2))
        assertEquals(PartySourceActivity.LocalSourceRetry, guest)
        assertEquals("Your source stopped working", partySourceMessage(guest)!!.headline)
        assertEquals("Trying another compatible source…", partySourceMessage(guest)!!.detail)
    }

    @Test
    fun aMemberThatCannotMatchOutranksEverythingAndAsksForAChoice() {
        // It is the only one of these a person has to answer, so it may not be buried under a
        // realization phase or a buffer.
        val activity = partySourceActivity(
            inputs(partyStarted = true, realization = PartyRealizationPhase.Matching, needsMatch = true, buffering = true),
        )
        assertEquals(PartySourceActivity.PartySourceUnmatched, activity)
        val message = partySourceMessage(activity)!!
        assertEquals("Couldn't match the party source", message.headline)
        assertEquals("Choose another source to continue with the group.", message.detail)
        assertEquals(PartyStatusTone.Warning, message.tone)
        // And the host sees the situation beside that member rather than a mechanism.
        assertEquals("Needs source", partyMemberSourceLabel(SourceResolutionState.choosing_fallback))
        assertNull(partyMemberSourceLabel(SourceResolutionState.ready))
    }

    @Test
    fun waitingForReadinessIsItsOwnThing() {
        val activity = partySourceActivity(inputs(partyStarted = true, mediaReady = true, waitingForReadiness = true))
        assertEquals(PartySourceActivity.WaitingForPartyReadiness, activity)
        assertEquals("Waiting for everyone…", partySourceMessage(activity)!!.headline)
        assertNull(partySourceMessage(activity)!!.detail, "no source wording on a readiness wait")
    }

    @Test
    fun ordinaryBufferingStaysOrdinary() {
        val activity = partySourceActivity(inputs(partyStarted = true, mediaReady = true, buffering = true))
        assertEquals(PartySourceActivity.Buffering, activity)
        val message = partySourceMessage(activity)!!
        assertEquals("Buffering…", message.headline)
        assertNull(message.detail, "a film that stops for a second is not a source problem")
    }

    @Test
    fun aClientWithNoSourceYetIsNeverCalledBuffering() {
        // The ordering rule, as the case that made it necessary: both are true at once while a
        // party member opens its first stream, and only one of them is useful.
        val activity = partySourceActivity(
            inputs(realization = PartyRealizationPhase.Matching, buffering = true),
        )
        assertEquals(PartySourceActivity.InitialPartyMatch, activity)
    }

    @Test
    fun aCompatibleAlternateSaysSoWithoutNamingATier() {
        val activity = partySourceActivity(inputs(partyStarted = true, alternate = true))
        assertEquals(PartySourceActivity.LocalCompatibleFallback, activity)
        val message = partySourceMessage(activity)!!
        assertEquals("Using a compatible source", message.headline)
        assertTrue(
            PartySourceMatchTier.entries.none { message.headline.contains(it.name) || message.detail.orEmpty().contains(it.name) },
            "a person does not need to know what EquivalentMedia is",
        )
    }

    @Test
    fun anAdvancingChainSaysSoRatherThanFreezing() {
        val activity = partySourceActivity(inputs(partyStarted = true, realization = PartyRealizationPhase.Failed))
        assertEquals(PartySourceActivity.FailedTryingNext, activity)
        assertEquals("Source didn't work", partySourceMessage(activity)!!.headline)
        assertEquals("Trying the next option…", partySourceMessage(activity)!!.detail)
    }

    @Test
    fun outsideAPartyThereIsNothingToSay() {
        assertEquals(
            PartySourceActivity.None,
            partySourceActivity(PartySourceActivityInputs(inParty = false, buffering = true)),
        )
        assertNull(partySourceMessage(PartySourceActivity.None))
    }

    @Test
    fun everyActivityExceptNoneHasCopy() {
        // A state that reaches the screen with no words is the bug this file replaces.
        for (activity in PartySourceActivity.entries) {
            val message = partySourceMessage(activity)
            if (activity == PartySourceActivity.None) {
                assertNull(message)
            } else {
                assertTrue(message != null && message.headline.isNotBlank(), "$activity has no headline")
            }
        }
    }
}
