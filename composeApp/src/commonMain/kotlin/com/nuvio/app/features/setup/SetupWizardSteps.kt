package com.nuvio.app.features.setup

// No imports, and none may be added. This file is pure by design so it can be compiled and
// run outside Gradle (`AGENTS.md`, "Verifying without Gradle", item 2). The wizard itself is
// a Compose gate that no test in this repository can reach, so if the ordering, the fork and
// the show-once rule are not decided here they are not decided anywhere a test can see.

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
 * A revision asks exactly once per revision. `0.5.0-beta` ships revision 1, so a fresh install
 * (stored `null`) and an existing install upgrading in (also `null`, the key has never been
 * written) both see it once, and nobody sees it again until this constant is bumped.
 */
const val SETUP_WIZARD_REVISION: Int = 1

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

/** Every screen the wizard can show, in the order they are declared. */
enum class SetupStep {
    /** Name the thing and offer a way out. Sets nothing. */
    Welcome,

    /**
     * How Nuvio picks sources - the one step that changes behaviour rather than appearance.
     * Reuses `PlaybackModeCard`, so the copy can never drift from the settings dialog.
     */
    PlaybackMode,

    /** The fork: three complete looks, or the long way round. */
    Look,

    /** Poster or landscape, labels, size, corners. */
    Cards,

    /** Hero banner, and the Continue Watching card style. */
    HomeScreen,

    /** Background treatment, episode card style, tabbed or stacked sections. */
    DetailsScreen,

    /** Accent palette and AMOLED. Deliberately not part of a look - see [SetupPreset]. */
    Theme,

    /** Addons and debrid. Optional, and the only step that can fail. */
    Sources,

    /** Trakt. Optional. */
    Trakt,

    /** Records the revision. */
    Done,
}

/**
 * Which route through the wizard the user took at [SetupStep.Look].
 *
 * The preset-first shape only works if picking a look can *end* the customisation - otherwise
 * it is a decorative first step in front of the same long questionnaire.
 */
enum class SetupWizardPath {
    /** Before the fork. The full sequence is assumed so the progress count does not lie. */
    Undecided,

    /** "Use this look" - the preset stands, and the four fine-tuning steps are skipped. */
    Quick,

    /** "Customise" - every step. */
    Full,
}

/**
 * What the wizard is willing to ask about this time.
 *
 * The two optional steps are dropped rather than shown-and-skipped when they have nothing to
 * offer: a re-run from Settings asking a user with five addons to install their first one is
 * noise, and noise in a setup flow reads as the app not knowing what it already has.
 */
data class SetupWizardPlan(
    val path: SetupWizardPath = SetupWizardPath.Undecided,
    /** False when the profile already has at least one enabled source. */
    val offerSources: Boolean = true,
    /** False when Trakt is already connected. */
    val offerTrakt: Boolean = true,
)

/** The four steps a preset fills in, and therefore the four [SetupWizardPath.Quick] skips. */
private val fineTuningSteps = listOf(
    SetupStep.Cards,
    SetupStep.HomeScreen,
    SetupStep.DetailsScreen,
    SetupStep.Theme,
)

/**
 * The steps this run will actually show, in order.
 *
 * Deriving the sequence from the plan rather than tracking an index is what makes Back correct
 * for free: a user who took the quick path and presses Back on [SetupStep.Sources] returns to
 * [SetupStep.Look], not to [SetupStep.Theme] - a step that run never showed.
 *
 * [SetupWizardPath.Undecided] yields the full sequence so that the progress indicator before
 * the fork counts the worst case. Shrinking a progress bar is fine; growing one is not.
 */
fun setupWizardSteps(plan: SetupWizardPlan): List<SetupStep> = SetupStep.entries.filter { step ->
    when (step) {
        in fineTuningSteps -> plan.path != SetupWizardPath.Quick
        SetupStep.Sources -> plan.offerSources
        SetupStep.Trakt -> plan.offerTrakt
        else -> true
    }
}

/**
 * The step after [current], or null when [current] is the last one.
 *
 * A [current] the plan does not contain answers with the first step that follows it in
 * declaration order and is in the plan. That is reachable in one real way: the fork itself
 * changes the plan, so the step the user is standing on when the path flips from
 * [SetupWizardPath.Undecided] may no longer be in the sequence.
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

/**
 * One-based position of [current] for the progress indicator, or null when it is not shown.
 */
fun setupStepPosition(current: SetupStep, plan: SetupWizardPlan): Int? =
    setupWizardSteps(plan).indexOf(current).takeIf { it >= 0 }?.plus(1)

/**
 * Whether leaving [current] should record the wizard as completed.
 *
 * True on the last step of the sequence, whichever that turns out to be. It is expressed
 * against the plan rather than pinned to [SetupStep.Done] because a plan that offers neither
 * optional step still has to finish, and because "the user reached the end" and "the user is
 * on the screen called Done" are two different claims - only the first one should write.
 */
fun isFinalSetupStep(current: SetupStep, plan: SetupWizardPlan): Boolean =
    setupWizardSteps(plan).lastOrNull() == current
