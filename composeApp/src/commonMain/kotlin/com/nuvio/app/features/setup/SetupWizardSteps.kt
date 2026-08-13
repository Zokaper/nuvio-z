package com.nuvio.app.features.setup

// No imports, and none may be added. This file is pure by design so it can be compiled and
// run outside Gradle (`AGENTS.md`, "Verifying without Gradle", item 2). The wizard itself is
// a Compose gate that no test in this repository can reach, so if the ordering and the
// show-once rule are not decided here they are not decided anywhere a test can see.

/**
 * The revision of the setup wizard the user has completed.
 *
 * **An integer, not a boolean and not the app version**, and the choice matters:
 *
 * - A boolean can never re-ask. Adding a step in a later release would reach nobody who had
 *   already finished, which is every existing user.
 * - The app version - what `WhatsNewStorage` stores - would re-show the entire wizard on
 *   **every** release. A first-run flow that reappears after a patch bump reads as a bug.
 *
 * A revision asks exactly once per revision.
 *
 * **Revision 2** ships the redesign: the preset fork is gone, every appearance choice is asked
 * directly, and the preview moved behind the controls. Anyone who completed revision 1
 * answered a flow that no longer exists and never saw most of these options, which is exactly
 * the case this integer was chosen for.
 */
const val SETUP_WIZARD_REVISION: Int = 2

/**
 * Whether the first-launch wizard should gate the app.
 *
 * A stored revision **higher** than the current one must not re-show: that is a downgrade, and
 * the user has already answered a superset of what this build would ask.
 */
fun shouldShowSetupWizard(
    completedRevision: Int?,
    currentRevision: Int = SETUP_WIZARD_REVISION,
): Boolean = (completedRevision ?: 0) < currentRevision

/**
 * Every screen the wizard can show, in the order they are declared.
 *
 * **One topic per step, and no fork.** Revision 1 opened with three named looks and then
 * branched, which meant most people never reached the individual options at all - the preset
 * was doing the choosing, and the live preview it was built around only got looked at once.
 * Each step now asks about one thing, so the sheet stays short and the preview stays large.
 */
enum class SetupStep {
    /** Name the thing and offer a way out. Sets nothing. */
    Welcome,

    /**
     * How Nuvio picks sources - the one step that changes behaviour rather than appearance.
     * Reuses `PlaybackModeCard`, so the copy can never drift from the settings dialog.
     */
    PlaybackMode,

    /** Poster or wide, size, corners, titles underneath. */
    Cards,

    /** The featured banner. */
    HomeScreen,

    /** Continue Watching: card style, episode thumbnails, blurring what is next up. */
    ContinueWatching,

    /** The details screen's background treatment. */
    DetailsScreen,

    /** Episode card style, blurring unwatched episodes, tabbed sections. */
    Episodes,

    /** Accent palette and AMOLED. */
    Theme,

    /** Addons. Optional, and the only step that can fail. */
    Sources,

    /** Trakt. Optional. */
    Trakt,

    /** Records the revision. */
    Done,
}

/**
 * What the wizard is willing to ask about this time.
 *
 * The two optional steps are dropped rather than shown-and-skipped when they have nothing to
 * offer: a re-run from Settings asking a user with five addons to install their first one is
 * noise, and noise in a setup flow reads as the app not knowing what it already has.
 */
data class SetupWizardPlan(
    /** False when the profile already has at least one enabled source. */
    val offerSources: Boolean = true,
    /** False when Trakt is already connected. */
    val offerTrakt: Boolean = true,
)

/** The steps this run will actually show, in order. */
fun setupWizardSteps(plan: SetupWizardPlan): List<SetupStep> = SetupStep.entries.filter { step ->
    when (step) {
        SetupStep.Sources -> plan.offerSources
        SetupStep.Trakt -> plan.offerTrakt
        else -> true
    }
}

/**
 * The step after [current], or null when [current] is the last one.
 *
 * A [current] the plan does not contain answers with the first step that follows it in
 * declaration order and is in the plan, rather than with null. A wizard that gates the app and
 * can be entered at a step it cannot leave is the failure this file exists to prevent, and a
 * dropped optional step is a real way to arrive at one - installing an addon on the Sources
 * step removes that step from the plan under the user's feet.
 */
fun nextSetupStep(current: SetupStep, plan: SetupWizardPlan): SetupStep? {
    val steps = setupWizardSteps(plan)
    val index = steps.indexOf(current)
    if (index < 0) return steps.firstOrNull { it.ordinal > current.ordinal }
    return steps.getOrNull(index + 1)
}

/** The step before [current], or null when [current] is the first one shown. */
fun previousSetupStep(current: SetupStep, plan: SetupWizardPlan): SetupStep? {
    val steps = setupWizardSteps(plan)
    val index = steps.indexOf(current)
    if (index < 0) return steps.lastOrNull { it.ordinal < current.ordinal }
    return steps.getOrNull(index - 1)
}

/** One-based position of [current] for the progress indicator, or null when it is not shown. */
fun setupStepPosition(current: SetupStep, plan: SetupWizardPlan): Int? =
    setupWizardSteps(plan).indexOf(current).takeIf { it >= 0 }?.plus(1)

/**
 * Whether leaving [current] should record the wizard as completed.
 *
 * Expressed against the plan rather than pinned to [SetupStep.Done] because "the user reached
 * the end" and "the user is on the screen called Done" are two different claims, and only the
 * first one should write.
 */
fun isFinalSetupStep(current: SetupStep, plan: SetupWizardPlan): Boolean =
    setupWizardSteps(plan).lastOrNull() == current
