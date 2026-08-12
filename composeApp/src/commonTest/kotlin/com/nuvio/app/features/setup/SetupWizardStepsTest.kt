package com.nuvio.app.features.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wizard's ordering, its fork, and the rule that decides whether it appears at all.
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
        assertFalse(shouldShowSetupWizard(completedRevision = 1, currentRevision = 1))
    }

    @Test
    fun aNewRevisionAsksAgain() {
        assertTrue(shouldShowSetupWizard(completedRevision = 1, currentRevision = 2))
    }

    @Test
    fun aDowngradeMustNotReAsk() {
        // Storage is synced, so a profile can carry a revision from a newer build than the one
        // reading it. That user has answered a superset of what this build would ask.
        assertFalse(shouldShowSetupWizard(completedRevision = 2, currentRevision = 1))
    }

    // --- the sequence -------------------------------------------------------------------

    @Test
    fun theUndecidedPathCountsTheFullSequence() {
        // Before the fork the progress indicator must assume the long route: a bar that grows
        // when the user picks "Customise" reads as the app changing its mind.
        assertEquals(SetupStep.entries, setupWizardSteps(SetupWizardPlan()))
    }

    @Test
    fun theQuickPathSkipsTheFourFineTuningSteps() {
        val steps = setupWizardSteps(SetupWizardPlan(path = SetupWizardPath.Quick))
        assertEquals(
            listOf(
                SetupStep.Welcome,
                SetupStep.PlaybackMode,
                SetupStep.Look,
                SetupStep.Sources,
                SetupStep.Trakt,
                SetupStep.Done,
            ),
            steps,
        )
    }

    @Test
    fun theFullPathKeepsEverything() {
        assertEquals(
            SetupStep.entries,
            setupWizardSteps(SetupWizardPlan(path = SetupWizardPath.Full)),
        )
    }

    @Test
    fun anOptionalStepWithNothingToOfferIsDroppedNotShown() {
        val steps = setupWizardSteps(
            SetupWizardPlan(
                path = SetupWizardPath.Quick,
                offerSources = false,
                offerTrakt = false,
            ),
        )
        assertEquals(
            listOf(SetupStep.Welcome, SetupStep.PlaybackMode, SetupStep.Look, SetupStep.Done),
            steps,
        )
    }

    // --- next / previous ----------------------------------------------------------------

    @Test
    fun theForkSendsQuickStraightToSources() {
        assertEquals(
            SetupStep.Sources,
            nextSetupStep(SetupStep.Look, SetupWizardPlan(path = SetupWizardPath.Quick)),
        )
    }

    @Test
    fun theForkSendsCustomiseToTheFirstFineTuningStep() {
        assertEquals(
            SetupStep.Cards,
            nextSetupStep(SetupStep.Look, SetupWizardPlan(path = SetupWizardPath.Full)),
        )
    }

    @Test
    fun backFromAQuickPathStepReturnsToTheFork() {
        // The defect this pins: indexing a fixed step list would walk back into Theme, a step
        // the quick run never showed.
        assertEquals(
            SetupStep.Look,
            previousSetupStep(SetupStep.Sources, SetupWizardPlan(path = SetupWizardPath.Quick)),
        )
    }

    @Test
    fun backFromTheFullPathWalksTheFineTuningStepsInOrder() {
        val plan = SetupWizardPlan(path = SetupWizardPath.Full)
        assertEquals(SetupStep.Theme, previousSetupStep(SetupStep.Sources, plan))
        assertEquals(SetupStep.DetailsScreen, previousSetupStep(SetupStep.Theme, plan))
        assertEquals(SetupStep.HomeScreen, previousSetupStep(SetupStep.DetailsScreen, plan))
        assertEquals(SetupStep.Cards, previousSetupStep(SetupStep.HomeScreen, plan))
        assertEquals(SetupStep.Look, previousSetupStep(SetupStep.Cards, plan))
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
    fun aStepTheRunDroppedStillFindsItsWayForward() {
        // Reachable for real: the fork mutates the plan, so the step the user is standing on
        // can leave the sequence under them. Answering null there would strand the wizard.
        val plan = SetupWizardPlan(path = SetupWizardPath.Quick)
        assertEquals(SetupStep.Sources, nextSetupStep(SetupStep.Theme, plan))
        assertEquals(SetupStep.Look, previousSetupStep(SetupStep.Cards, plan))
    }

    @Test
    fun aDroppedOptionalStepIsSteppedOver() {
        val plan = SetupWizardPlan(path = SetupWizardPath.Full, offerSources = false)
        assertEquals(SetupStep.Trakt, nextSetupStep(SetupStep.Theme, plan))
        assertEquals(SetupStep.Theme, previousSetupStep(SetupStep.Trakt, plan))
    }

    // --- progress and completion --------------------------------------------------------

    @Test
    fun positionsAreOneBasedAndFollowThePlan() {
        assertEquals(1, setupStepPosition(SetupStep.Welcome, SetupWizardPlan()))
        assertEquals(
            4,
            setupStepPosition(SetupStep.Sources, SetupWizardPlan(path = SetupWizardPath.Quick)),
        )
        assertEquals(
            8,
            setupStepPosition(SetupStep.Sources, SetupWizardPlan(path = SetupWizardPath.Full)),
        )
    }

    @Test
    fun aStepOutsideThePlanHasNoPosition() {
        assertNull(setupStepPosition(SetupStep.Theme, SetupWizardPlan(path = SetupWizardPath.Quick)))
    }

    @Test
    fun onlyTheLastStepOfTheActualSequenceCompletes() {
        assertTrue(isFinalSetupStep(SetupStep.Done, SetupWizardPlan()))
        assertFalse(isFinalSetupStep(SetupStep.Trakt, SetupWizardPlan()))
        assertFalse(isFinalSetupStep(SetupStep.Look, SetupWizardPlan(path = SetupWizardPath.Quick)))
    }

    @Test
    fun droppingBothOptionalStepsStillReachesAnEnd() {
        val plan = SetupWizardPlan(offerSources = false, offerTrakt = false)
        assertTrue(isFinalSetupStep(SetupStep.Done, plan))
        assertEquals(SetupStep.Done, nextSetupStep(SetupStep.Theme, plan))
    }

    @Test
    fun everyStepInEveryPlanReachesTheEnd() {
        // The property that matters more than any single case above: from any starting step,
        // in any plan, walking `nextSetupStep` terminates at the plan's final step. A wizard
        // that can be entered at a step it cannot leave is the failure mode this whole file
        // exists to make impossible.
        val plans = listOf(
            SetupWizardPlan(),
            SetupWizardPlan(path = SetupWizardPath.Quick),
            SetupWizardPlan(path = SetupWizardPath.Full),
            SetupWizardPlan(path = SetupWizardPath.Quick, offerSources = false),
            SetupWizardPlan(path = SetupWizardPath.Full, offerTrakt = false),
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
}
