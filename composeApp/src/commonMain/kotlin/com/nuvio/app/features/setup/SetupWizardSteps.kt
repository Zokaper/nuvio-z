package com.nuvio.app.features.setup

// No imports, and none may be added. This file is pure by design so it can be compiled and
// run outside Gradle (`AGENTS.md`, "Verifying without Gradle", item 2). The wizard itself is
// a Compose gate that no test in this repository can reach, so if the ordering, the branch
// rules and the show-once rule are not decided here they are not decided anywhere a test can
// see. Phase 5 made that more important rather than less: three of the nine steps are now
// conditional, and two of those conditions can flip while the user is standing on the step.

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
 * **Revision 7 is the Phase 5 rebuild, and it changes what the wizard is *for*.** Revision 6
 * spent four of its eight steps on appearance and never mentioned the social layer - friends,
 * activity, presence, Watch Together - which is the largest thing separating Nuvio Z from
 * vanilla. Revision 7 asks about playback first, then only the playback configuration the
 * chosen mode can actually use, then whether the social layer should exist at all, then two
 * appearance steps instead of four. Anyone who completed revision 6 answered a set of
 * questions that no longer describes the app.
 *
 * Revisions 1 through 6 each asked a set of questions this build no longer asks: 1 hid them
 * behind presets, 2 put them under an unreadable translucent panel, 3 asked one that led
 * nowhere, 4 asked one that changed nothing, 5 asked all of them through a preview that did
 * not look like the app, and 6 asked four appearance steps and no social question at all.
 *
 * ⚠ **Bumping this shows the wizard once more on the first launch of a build that carries the
 * bump, and that is not the same thing as the wizard failing to stay dismissed.** The two were
 * confused once already, while `replaceFromSyncPayload` was genuinely re-gating the app on every
 * launch - see `mergeMonotonicSyncInt` in `core/sync/SyncPreferenceJson.kt`. When testing this,
 * the launch that proves anything is the **second** one.
 */
const val SETUP_WIZARD_REVISION: Int = 9

/** Revision 9 adds Sources without automatically replaying onboarding for revision-8 profiles. */
private const val SETUP_WIZARD_AUTOMATIC_REQUIRED_REVISION: Int = 8

/**
 * Whether the first-launch wizard should gate the app.
 *
 * A stored revision **higher** than the current one must not re-show: that is a downgrade, and
 * the user has already answered a superset of what this build would ask.
 */
fun shouldShowSetupWizard(
    completedRevision: Int?,
    currentRevision: Int = SETUP_WIZARD_REVISION,
): Boolean =
    (completedRevision ?: 0) < minOf(currentRevision, SETUP_WIZARD_AUTOMATIC_REQUIRED_REVISION)

/**
 * Every screen the wizard can show, in the order they are declared.
 *
 * **One surface per step.** Revision 1 asked one question behind three named presets, which
 * meant most people never reached the individual options. Revision 2 over-corrected into six
 * separate appearance steps, two of which carried a single control each - a whole screen, a
 * whole preview and two taps to answer one toggle.
 *
 * Revision 7's ordering is the Phase 5 product decision: **what Nuvio Z does** before **what it
 * looks like**. Playback is the first substantive question, its configuration follows only when
 * the chosen mode has something to configure, source readiness sits with playback rather than
 * with appearance, and the social layer is opted into explicitly before anything cosmetic is
 * asked.
 */
enum class SetupStep {
    /** Name the thing and offer a way out. Sets nothing. */
    Welcome,

    /**
     * How Nuvio picks sources - the first substantive decision, and the one that decides which
     * of the steps below are worth asking. Reuses `PlaybackModeCard`, so the copy can never
     * drift from the settings dialog.
     */
    PlaybackMode,

    /**
     * The configuration the chosen mode can actually use. Conditional: see
     * [playbackSetupVariant], which decides both whether this step appears and what it asks.
     */
    PlaybackSetup,

    /**
     * The language the user watches in - audio, and subtitles.
     *
     * ⚠ **Unconditional, unlike [PlaybackSetup], and that asymmetry is the point.** Everything
     * PlaybackSetup asks feeds the automatic source picker, which Classic does not have. Language
     * feeds that *and* the player's own track selection, which runs in all three modes - so this
     * is the one playback question worth asking a Classic user.
     *
     * It is also the question revision 7 never asked while asking how hard to try to honour the
     * answer. The audio preference ships as the sentinel `device` and the picker discarded every
     * sentinel, so "Audio language matching: Require" was asked, stored, synced, and inert.
     */
    Language,

    /** Addons. Optional, and the only step that can fail. */
    Sources,

    /**
     * Whether the social product layer exists at all: friends, friend activity, profiles and
     * Watch Together. Not a "skip the username" question - the answer gates the feature across
     * the whole application.
     */
    SocialOptIn,

    /** The handle, and only when social is on and this profile does not already have one. */
    SocialIdentity,

    /** Catalogue cards: poster or wide, and how big. */
    Look,

    /** Accent palette and AMOLED. */
    Theme,

    /** Records the revision. */
    Done,
}

/**
 * What, if anything, the playback-setup step should ask for a given mode.
 *
 * **The branch lives here rather than in the Compose body**, for the same reason the ordering
 * does: `SetupWizardScreen` is a gate no test can reach, so a `when (mode)` inside it is a rule
 * nothing can check. The body renders from this value; it must not decide for itself what a
 * mode means. Mode-descriptive logic has already drifted once when two files described the
 * modes independently - see `PlaybackModeCard`.
 */
enum class PlaybackSetupVariant {
    /**
     * Nothing to ask, so the step is dropped from the plan rather than shown empty.
     *
     * This is Classic's answer. Every row of Settings - Playback - Source preferences is
     * `enabled = !isClassicMode` because they all feed the automatic picker, and Classic has no
     * automatic picker to feed. The one Classic-only setting that does exist,
     * `streamAutoPlayMode`, is deliberately **not** asked here: the mode's own card says "You
     * read the releases and pick one" and the storyboard spends its longest hold showing a
     * pointer walking every row, so offering to skip the list on the very next screen would
     * sell the mode on manual choice and then immediately offer to remove it. It stays in
     * Settings, where an advanced escape hatch belongs.
     */
    None,

    /** The user picks a quality band; these preferences shape the sheet and the pick under it. */
    QualityBand,

    /**
     * Nuvio picks the band from the measured connection. The same preferences apply, but they
     * are the user's only lever over the result, so the ceiling leads and the copy says so.
     */
    AutomaticBand,
}

/**
 * The playback configuration worth asking for [modeName].
 *
 * Takes the mode's **name** rather than the `PlaybackMode` enum so this file can stay
 * import-free, exactly as `setupStoryboardFrames` does. `SetupWizardScreen` passes `mode.name`.
 *
 * An unrecognised name answers [PlaybackSetupVariant.None] rather than guessing. That is
 * reachable for real - a profile can carry a mode written by a newer build, or one withdrawn by
 * this one - and it agrees with `PlaybackMode.fromStorage`, which already resolves an unknown
 * stored value to `CLASSIC`. Asking nothing is the only safe answer: asking the *wrong* mode's
 * questions would write preferences the user's actual mode cannot use.
 */
fun playbackSetupVariant(modeName: String?): PlaybackSetupVariant = when (modeName) {
    "STREAMLINED" -> PlaybackSetupVariant.QualityBand
    "INSTANT" -> PlaybackSetupVariant.AutomaticBand
    else -> PlaybackSetupVariant.None
}

/**
 * The step a saved [name] should resume on.
 *
 * The wizard persists its position **by name** rather than by ordinal, so that reordering the
 * enum in a later release cannot resume a process-death-restored wizard on a different step
 * than the user left it on. The cost of that choice is this function: revision 3 deleted
 * `ContinueWatching` and `Episodes`, revision 4 deleted `Trakt`, and revision 7 deleted `Cards`,
 * `Home` and `Details`, so a wizard restored across an app update can be holding a name that no
 * longer resolves.
 *
 * Falling back to [SetupStep.Welcome] rather than throwing is the whole point - the wizard
 * gates the app, and a crash here is a crash the user cannot get past.
 */
fun setupStepForSavedName(name: String?): SetupStep =
    SetupStep.entries.firstOrNull { it.name == name } ?: SetupStep.Welcome

/**
 * What the wizard is willing to ask about this time.
 *
 * An optional step is dropped rather than shown-and-skipped when it has nothing to offer.
 * Sources is deliberately not optional: it recognises an existing stream addon and still offers
 * the recommended AIOStreams setup, so it remains useful during a manual replay.
 *
 * Revision 3 had a second optional step, Trakt. It is gone rather than defaulted off, because
 * the connection it offered is not working yet - a first-run flow that asks for an account and
 * then cannot use it is worse than not asking.
 */
data class SetupWizardPlan(
    /**
     * The chosen playback mode's name.
     *
     * Carried rather than a pre-computed [PlaybackSetupVariant] because the mode is the one
     * input and [playbackSetupVariant] is the one rule; holding both would be two places to
     * disagree. A `String` because this file is import-free.
     */
    val playbackModeName: String = "CLASSIC",

    /** The answer to the social question so far. */
    val socialEnabled: Boolean = false,

    /** False when this profile already has a social identity, from cache or from a probe. */
    val offerSocialIdentity: Boolean = true,
)

/** The steps this run will actually show, in order. */
fun setupWizardSteps(plan: SetupWizardPlan): List<SetupStep> = SetupStep.entries.filter { step ->
    when (step) {
        SetupStep.PlaybackSetup ->
            playbackSetupVariant(plan.playbackModeName) != PlaybackSetupVariant.None
        SetupStep.SocialIdentity -> plan.socialEnabled && plan.offerSocialIdentity
        else -> true
    }
}

/**
 * The step after [current], or null when [current] is the last one.
 *
 * A [current] the plan does not contain answers with the first step that follows it in
 * declaration order and is in the plan, rather than with null. A wizard that gates the app and
 * can be entered at a step it cannot leave is the failure this file exists to prevent, and a
 * dropped optional step is a real way to arrive at one. There are two such ways now:
 * going back and choosing Classic removes `PlaybackSetup`, and turning social off removes
 * `SocialIdentity`. Sources remains in the plan unconditionally.
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

/**
 * What a backend probe was able to say about a profile's social identity.
 *
 * Separate from a plain `Boolean?` because "we asked and there is none" and "we could not ask"
 * lead to the same *behaviour* but must never lead to the same *persistence*: an
 * [Indeterminate] answer may not be written down as though the user had chosen it. See
 * [resolveSocialFeaturesEnabled].
 */
enum class SocialIdentityProbe {
    /** No probe has run yet for this profile on this launch. */
    NotRun,

    /** The backend answered, and this profile already has a social identity. */
    Present,

    /** The backend answered, and it does not. */
    Absent,

    /** Signed out, offline, or the call failed. Nothing may be concluded, and nothing stored. */
    Indeterminate,
}

/**
 * Whether the social layer is on for a profile that may predate this preference.
 *
 * **An explicit answer always wins.** Failing that, a profile that has *already been a social
 * user* keeps its social layer - hiding an established identity, friend list and activity
 * behind a new boolean defaulting false is the one outcome this rule exists to prevent.
 *
 * The local cache answers that for anyone who has used social on this machine. It cannot answer
 * for a second install, a cleared cache or a fresh data root, and those users have a real
 * backend identity while looking locally new - so [probe] answers instead, from the existing
 * social RPCs. When even that cannot answer, the fallback is off, and ⚠ **the caller must not
 * persist that as an explicit preference.** The three moments an explicit preference is written
 * are enumerated on `SocialFeaturePreferencesRepository.setEnabled`.
 *
 * Pure and import-free so mobile inherits this rule in Phase 6 rather than re-deriving it.
 */
fun resolveSocialFeaturesEnabled(
    stored: Boolean?,
    hasCachedIdentity: Boolean,
    probe: SocialIdentityProbe,
): Boolean = when {
    stored != null -> stored
    hasCachedIdentity -> true
    probe == SocialIdentityProbe.Present -> true
    else -> false
}
