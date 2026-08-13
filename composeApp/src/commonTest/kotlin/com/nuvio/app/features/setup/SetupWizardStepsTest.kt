package com.nuvio.app.features.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wizard's ordering and the rule that decides whether it appears at all.
 *
 * The wizard is a Compose gate wrapping `AppGateScreen.Main`, so none of it is reachable from
 * a test once it is on screen. Everything asserted here is therefore the only executable proof
 * that a user can get from the first step to the last one and back without falling into a
 * sequence the run never showed them.
 */
class SetupWizardStepsTest {

    // --- shouldShowSetupWizard ----------------------------------------------------------

    @Test
    fun aFreshInstallHasNeverCompletedAnyRevision() {
        assertTrue(shouldShowSetupWizard(completedRevision = null, currentRevision = 1))
    }

    @Test
    fun anExistingInstallUpgradingInIsAlsoNull() {
        // The key has never been written for a profile created before 0.5.0-beta, so the
        // upgrade path and the fresh-install path are the same value. That is intended: an
        // existing user has never seen these options either.
        assertTrue(shouldShowSetupWizard(completedRevision = null, currentRevision = SETUP_WIZARD_REVISION))
    }

    @Test
    fun completingTheCurrentRevisionStopsIt() {
        assertFalse(shouldShowSetupWizard(completedRevision = 2, currentRevision = 2))
    }

    @Test
    fun revisionOneMustSeeTheRedesign() {
        // The reason this constant is at 2. Anyone who finished the preset-fork wizard
        // answered a flow that no longer exists and never saw most of these options.
        assertTrue(shouldShowSetupWizard(completedRevision = 1, currentRevision = SETUP_WIZARD_REVISION))
    }

    @Test
    fun aDowngradeMustNotReAsk() {
        // Storage is synced, so a profile can carry a revision from a newer build than the one
        // reading it. That user has answered a superset of what this build would ask.
        assertFalse(shouldShowSetupWizard(completedRevision = 3, currentRevision = 2))
    }

    // --- the sequence -------------------------------------------------------------------

    @Test
    fun theFullPlanIsEveryStepInDeclarationOrder() {
        assertEquals(SetupStep.entries, setupWizardSteps(SetupWizardPlan()))
    }

    @Test
    fun oneTopicPerStepAndNoFork() {
        // Pins the shape of the redesign: appearance is asked as six separate steps rather
        // than hidden behind a preset. If these collapse again, the preview stops being the
        // thing the user is looking at while they choose.
        val appearance = listOf(
            SetupStep.Cards,
            SetupStep.HomeScreen,
            SetupStep.ContinueWatching,
            SetupStep.DetailsScreen,
            SetupStep.Episodes,
            SetupStep.Theme,
        )
        assertTrue(setupWizardSteps(SetupWizardPlan()).containsAll(appearance))
    }

    @Test
    fun anOptionalStepWithNothingToOfferIsDroppedNotShown() {
        val steps = setupWizardSteps(SetupWizardPlan(offerSources = false, offerTrakt = false))
        assertFalse(steps.contains(SetupStep.Sources))
        assertFalse(steps.contains(SetupStep.Trakt))
        assertEquals(SetupStep.Done, steps.last())
        assertEquals(SetupStep.Theme, steps[steps.size - 2])
    }

    // --- next / previous ----------------------------------------------------------------

    @Test
    fun theAppearanceStepsRunInOrder() {
        val plan = SetupWizardPlan()
        assertEquals(SetupStep.Cards, nextSetupStep(SetupStep.PlaybackMode, plan))
        assertEquals(SetupStep.HomeScreen, nextSetupStep(SetupStep.Cards, plan))
        assertEquals(SetupStep.ContinueWatching, nextSetupStep(SetupStep.HomeScreen, plan))
        assertEquals(SetupStep.DetailsScreen, nextSetupStep(SetupStep.ContinueWatching, plan))
        assertEquals(SetupStep.Episodes, nextSetupStep(SetupStep.DetailsScreen, plan))
        assertEquals(SetupStep.Theme, nextSetupStep(SetupStep.Episodes, plan))
    }

    @Test
    fun backWalksTheSameOrderInReverse() {
        val plan = SetupWizardPlan()
        assertEquals(SetupStep.Episodes, previousSetupStep(SetupStep.Theme, plan))
        assertEquals(SetupStep.DetailsScreen, previousSetupStep(SetupStep.Episodes, plan))
        assertEquals(SetupStep.PlaybackMode, previousSetupStep(SetupStep.Cards, plan))
    }

    @Test
    fun theFirstStepHasNothingBeforeIt() {
        assertNull(previousSetupStep(SetupStep.Welcome, SetupWizardPlan()))
    }

    @Test
    fun theLastStepHasNothingAfterIt() {
        assertNull(nextSetupStep(SetupStep.Done, SetupWizardPlan()))
    }

    @Test
    fun aDroppedOptionalStepIsSteppedOver() {
        val plan = SetupWizardPlan(offerSources = false)
        assertEquals(SetupStep.Trakt, nextSetupStep(SetupStep.Theme, plan))
        assertEquals(SetupStep.Theme, previousSetupStep(SetupStep.Trakt, plan))
    }

    @Test
    fun aStepTheRunDroppedStillFindsItsWayForward() {
        // Reachable for real, and it is the one that would strand a user: installing an addon
        // on the Sources step flips offerSources false, so the step the user is standing on
        // leaves the sequence under them.
        val plan = SetupWizardPlan(offerSources = false)
        assertEquals(SetupStep.Trakt, nextSetupStep(SetupStep.Sources, plan))
        assertEquals(SetupStep.Theme, previousSetupStep(SetupStep.Sources, plan))
    }

    // --- progress and completion --------------------------------------------------------

    @Test
    fun positionsAreOneBasedAndFollowThePlan() {
        assertEquals(1, setupStepPosition(SetupStep.Welcome, SetupWizardPlan()))
        assertEquals(9, setupStepPosition(SetupStep.Sources, SetupWizardPlan()))
        assertEquals(
            9,
            setupStepPosition(SetupStep.Trakt, SetupWizardPlan(offerSources = false)),
        )
    }

    @Test
    fun aStepOutsideThePlanHasNoPosition() {
        assertNull(setupStepPosition(SetupStep.Sources, SetupWizardPlan(offerSources = false)))
    }

    @Test
    fun onlyTheLastStepOfTheActualSequenceCompletes() {
        assertTrue(isFinalSetupStep(SetupStep.Done, SetupWizardPlan()))
        assertFalse(isFinalSetupStep(SetupStep.Trakt, SetupWizardPlan()))
        assertTrue(
            isFinalSetupStep(
                SetupStep.Done,
                SetupWizardPlan(offerSources = false, offerTrakt = false),
            ),
        )
    }

    @Test
    fun everyStepInEveryPlanReachesTheEnd() {
        // The property that matters more than any single case above: from any starting step,
        // in any plan, walking `nextSetupStep` terminates at the plan's final step. A wizard
        // that can be entered at a step it cannot leave is the failure mode this whole file
        // exists to make impossible.
        val plans = listOf(
            SetupWizardPlan(),
            SetupWizardPlan(offerSources = false),
            SetupWizardPlan(offerTrakt = false),
            SetupWizardPlan(offerSources = false, offerTrakt = false),
        )
        plans.forEach { plan ->
            SetupStep.entries.forEach { start ->
                var current = start
                var hops = 0
                while (!isFinalSetupStep(current, plan)) {
                    val next = nextSetupStep(current, plan)
                    assertTrue(next != null, "$start stranded at $current in $plan")
                    current = next
                    hops++
                    assertTrue(hops <= SetupStep.entries.size, "$start looped in $plan")
                }
            }
        }
    }

    @Test
    fun everyStepInEveryPlanReachesTheStart() {
        // The same property backwards. Back is the direction a user is most likely to press
        // repeatedly, and the one where a dropped step used to walk into a sequence the run
        // never showed.
        val plans = listOf(
            SetupWizardPlan(),
            SetupWizardPlan(offerSources = false),
            SetupWizardPlan(offerSources = false, offerTrakt = false),
        )
        plans.forEach { plan ->
            SetupStep.entries.forEach { start ->
                var current = start
                var hops = 0
                while (current != SetupStep.Welcome) {
                    val previous = previousSetupStep(current, plan)
                    assertTrue(previous != null, "$start stranded at $current in $plan")
                    current = previous
                    hops++
                    assertTrue(hops <= SetupStep.entries.size, "$start looped back in $plan")
                }
            }
        }
    }
}
