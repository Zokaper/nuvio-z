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
        assertFalse(
            shouldShowSetupWizard(
                completedRevision = SETUP_WIZARD_REVISION,
                currentRevision = SETUP_WIZARD_REVISION,
            ),
        )
    }

    @Test
    fun everyEarlierRevisionMustSeeTheCurrentFlow() {
        // The reason this constant keeps moving. Revision 1 was the preset fork; revision 2 was
        // the six-step flow behind a translucent panel; revision 3 asked for a Trakt connection
        // that did not work. Each asked a set of questions this build no longer asks.
        assertTrue(shouldShowSetupWizard(completedRevision = 1, currentRevision = SETUP_WIZARD_REVISION))
        assertTrue(shouldShowSetupWizard(completedRevision = 2, currentRevision = SETUP_WIZARD_REVISION))
        assertTrue(shouldShowSetupWizard(completedRevision = 3, currentRevision = SETUP_WIZARD_REVISION))
    }

    @Test
    fun aDowngradeMustNotReAsk() {
        // Storage is synced, so a profile can carry a revision from a newer build than the one
        // reading it. That user has answered a superset of what this build would ask.
        assertFalse(
            shouldShowSetupWizard(
                completedRevision = SETUP_WIZARD_REVISION + 1,
                currentRevision = SETUP_WIZARD_REVISION,
            ),
        )
    }

    // --- the sequence -------------------------------------------------------------------

    @Test
    fun theFullPlanIsEveryStepInDeclarationOrder() {
        assertEquals(SetupStep.entries, setupWizardSteps(SetupWizardPlan()))
    }

    @Test
    fun appearanceIsAskedAsFourStepsGroupedBySurface() {
        // Pins the shape of revision 3. Four steps, not six and not one: grouped by the surface
        // the settings belong to, so no step carries a single control and none carries enough
        // to need scrolling on a phone.
        val appearance = listOf(
            SetupStep.Cards,
            SetupStep.Home,
            SetupStep.Details,
            SetupStep.Theme,
        )
        assertTrue(setupWizardSteps(SetupWizardPlan()).containsAll(appearance))
        assertEquals(8, SetupStep.entries.size)
    }

    @Test
    fun anOptionalStepWithNothingToOfferIsDroppedNotShown() {
        val steps = setupWizardSteps(SetupWizardPlan(offerSources = false))
        assertFalse(steps.contains(SetupStep.Sources))
        assertEquals(SetupStep.Done, steps.last())
        assertEquals(SetupStep.Theme, steps[steps.size - 2])
    }

    @Test
    fun traktIsGoneEntirelyRatherThanDefaultedOff() {
        // Revision 4. It offered a connection that does not work yet, and a first-run flow that
        // asks for an account it cannot use is worse than one that does not ask.
        assertFalse(SetupStep.entries.any { it.name == "Trakt" })
    }

    // --- next / previous ----------------------------------------------------------------

    @Test
    fun theAppearanceStepsRunInOrder() {
        val plan = SetupWizardPlan()
        assertEquals(SetupStep.Cards, nextSetupStep(SetupStep.PlaybackMode, plan))
        assertEquals(SetupStep.Home, nextSetupStep(SetupStep.Cards, plan))
        assertEquals(SetupStep.Details, nextSetupStep(SetupStep.Home, plan))
        assertEquals(SetupStep.Theme, nextSetupStep(SetupStep.Details, plan))
    }

    @Test
    fun backWalksTheSameOrderInReverse() {
        val plan = SetupWizardPlan()
        assertEquals(SetupStep.Details, previousSetupStep(SetupStep.Theme, plan))
        assertEquals(SetupStep.Home, previousSetupStep(SetupStep.Details, plan))
        assertEquals(SetupStep.PlaybackMode, previousSetupStep(SetupStep.Cards, plan))
    }

    // --- resuming a saved position ------------------------------------------------------

    @Test
    fun aSavedStepResumesWhereItWasLeft() {
        assertEquals(SetupStep.Details, setupStepForSavedName("Details"))
    }

    @Test
    fun aSavedStepThatNoLongerExistsFallsBackToTheStart() {
        // Reachable for real: revision 3 deleted `ContinueWatching` and `Episodes` and revision
        // 4 deleted `Trakt`, so a wizard restored after an app update can be holding any of
        // them. The wizard gates the app, so the only acceptable answer is a step that exists.
        assertEquals(SetupStep.Welcome, setupStepForSavedName("Trakt"))
        assertEquals(SetupStep.Welcome, setupStepForSavedName("ContinueWatching"))
        assertEquals(SetupStep.Welcome, setupStepForSavedName("Episodes"))
        assertEquals(SetupStep.Welcome, setupStepForSavedName("HomeScreen"))
        assertEquals(SetupStep.Welcome, setupStepForSavedName("DetailsScreen"))
        assertEquals(SetupStep.Welcome, setupStepForSavedName(null))
        assertEquals(SetupStep.Welcome, setupStepForSavedName(""))
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
        assertEquals(SetupStep.Done, nextSetupStep(SetupStep.Theme, plan))
        assertEquals(SetupStep.Theme, previousSetupStep(SetupStep.Done, plan))
    }

    @Test
    fun aStepTheRunDroppedStillFindsItsWayForward() {
        // Reachable for real, and it is the one that would strand a user: installing an addon
        // on the Sources step flips offerSources false, so the step the user is standing on
        // leaves the sequence under them.
        val plan = SetupWizardPlan(offerSources = false)
        assertEquals(SetupStep.Done, nextSetupStep(SetupStep.Sources, plan))
        assertEquals(SetupStep.Theme, previousSetupStep(SetupStep.Sources, plan))
    }

    // --- progress and completion --------------------------------------------------------

    @Test
    fun positionsAreOneBasedAndFollowThePlan() {
        assertEquals(1, setupStepPosition(SetupStep.Welcome, SetupWizardPlan()))
        assertEquals(7, setupStepPosition(SetupStep.Sources, SetupWizardPlan()))
        assertEquals(8, setupStepPosition(SetupStep.Done, SetupWizardPlan()))
        assertEquals(
            7,
            setupStepPosition(SetupStep.Done, SetupWizardPlan(offerSources = false)),
        )
    }

    @Test
    fun aStepOutsideThePlanHasNoPosition() {
        assertNull(setupStepPosition(SetupStep.Sources, SetupWizardPlan(offerSources = false)))
    }

    @Test
    fun onlyTheLastStepOfTheActualSequenceCompletes() {
        assertTrue(isFinalSetupStep(SetupStep.Done, SetupWizardPlan()))
        assertFalse(isFinalSetupStep(SetupStep.Sources, SetupWizardPlan()))
        assertTrue(
            isFinalSetupStep(
                SetupStep.Done,
                SetupWizardPlan(offerSources = false),
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
