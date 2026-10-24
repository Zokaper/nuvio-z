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
const val SETUP_WIZARD_REVISION: Int = 11

/**
 * Below this, a profile gets the **whole** wizard; from it up to [SETUP_WIZARD_REVISION], only the
 * steps added since (an [SetupWizardRun.Upgrade]). Revision 9 added Sources and deliberately did not
 * replay anything for revision-8 profiles; revision 10 (Phase 9) adds the two download steps, which
 * revision-8 and -9 profiles are asked - and only those.
 */
const val SETUP_WIZARD_FULL_REQUIRED_BELOW: Int = 8

/**
 * The machine's own revision: whether **this installation** has answered the questions that belong
 * to the device itself rather than to any profile - downloads on mobile data and how many run at
 * once, which Social alerts this phone shows; on desktop the close button, Start with Windows,
 * Discord, the interface size and the pop-ups. Asked once, by whichever profile sets the device up
 * first, so a second profile can never change the first one's machine (Device Setup redesign,
 * 2026-10-24). Revision 11 is that redesign; 10 was the Phase 9 mobile-data question alone.
 *
 * Device-local and kept at sign-out (`LocalStoreRegistry`). 0 is written on a fresh install's first
 * launch, so a later launch can tell "fresh, not answered yet" from an installation that predates
 * the redesign - see [deviceSetupSeed].
 */
const val SETUP_DEVICE_REVISION: Int = 11

/**
 * Which wizard, if any, this launch owes **before the app opens**.
 *
 * - [Full]: a fresh profile, or one whose answers predate revision 8.
 * - [Upgrade]: revision 8 to 10 - only the steps [setupStepOfferedOnUpgradeFrom] names. Since
 *   2026-10-20 this no longer gates: it is the "New settings available" offer.
 * - [Device]: **Device Setup** - the per-device steps, once per profile per device. It gates only
 *   for a cross-family arrival here; otherwise it is offered by a pop-up ([shouldOfferDeviceSetup])
 *   and this run is what "Set up" opens.
 * - [None]: nothing gates.
 *
 * **Skipping any of them counts as finishing it**: the revision is written either way, so nobody is
 * asked twice. Skipping an upgrade leaves the Download Mode unanswered, which means derived from
 * Playback Mode - see `DownloadPolicy.mode`.
 */
enum class SetupWizardRun { Full, Upgrade, Device, None }

/**
 * @param profileRevision the synced `setup_wizard_completed_revision`.
 * @param arrivalPending this profile was just imported from the other platform family on this
 *   device (`CrossFamilySettingsImport`) and has not finished its Device Setup here yet.
 *
 * A stored revision **higher** than the current one never re-asks: that is a downgrade, and the
 * user has answered a superset of what this build would ask.
 *
 * The machine's revision no longer starts a run by itself (it did until 2026-10-24, as the phone's
 * mobile-data question): a profile that has not set this device up is offered Device Setup, and the
 * machine's questions ride along in it.
 */
fun setupWizardRun(
    profileRevision: Int?,
    currentRevision: Int = SETUP_WIZARD_REVISION,
    arrivalPending: Boolean = false,
): SetupWizardRun {
    val profile = profileRevision ?: 0
    return when {
        profile < minOf(currentRevision, SETUP_WIZARD_FULL_REQUIRED_BELOW) -> SetupWizardRun.Full
        profile < currentRevision -> SetupWizardRun.Upgrade
        arrivalPending -> SetupWizardRun.Device
        else -> SetupWizardRun.None
    }
}

/**
 * Whether this installation still owes the machine's own questions (see [SETUP_DEVICE_REVISION]).
 * Device-global rather than per profile, on purpose: those settings belong to the installation.
 */
fun isDeviceSetupStale(deviceRevision: Int?): Boolean =
    (deviceRevision ?: 0) < SETUP_DEVICE_REVISION

/**
 * Whether to show the "First time on this device" pop-up (Device Setup redesign, 2026-10-24): the
 * active profile has not set this device up, and nothing that gates the app is owed first. A Full
 * run ends with the device steps itself, and an arrival *is* a Device run, so neither asks.
 *
 * [deviceSetupDone] is per profile and device-local (`SetupProfileFlags.deviceSetupDone`).
 */
fun shouldOfferDeviceSetup(run: SetupWizardRun, deviceSetupDone: Boolean): Boolean =
    !deviceSetupDone && (run == SetupWizardRun.None || run == SetupWizardRun.Upgrade)

/**
 * What the first launch of the Device Setup redesign does to an existing installation.
 *
 * - [Fresh]: nothing was ever set up here. Write revision 0 and ask everything as profiles arrive.
 * - [Legacy]: the installation predates the redesign. Every profile counts as set up on it, and the
 *   machine's questions as answered, so nobody meets a wizard on update day; a profile *created*
 *   later still sets the device up in its Initial Setup.
 * - [None]: already decided on an earlier launch.
 */
enum class DeviceSetupSeed { Fresh, Legacy, None }

/**
 * @param deviceRevision [SETUP_DEVICE_REVISION]'s stored value, null when never written.
 * @param hasCachedProfiles this installation already knows an account's profiles - what tells an
 *   installation that never wrote a device revision (a desktop that never finished a wizard) from a
 *   fresh one.
 */
fun deviceSetupSeed(deviceRevision: Int?, hasCachedProfiles: Boolean): DeviceSetupSeed = when {
    deviceRevision == null -> if (hasCachedProfiles) DeviceSetupSeed.Legacy else DeviceSetupSeed.Fresh
    // 1 to 10: written by a build before the redesign (every wizard run wrote 10 since Phase 9).
    deviceRevision in 1 until SETUP_DEVICE_REVISION -> DeviceSetupSeed.Legacy
    else -> DeviceSetupSeed.None
}

/**
 * Whether the profile's own answers are out of date - [SetupWizardRun.Full] or
 * [SetupWizardRun.Upgrade]. The device half is [setupWizardRun]'s.
 */
fun shouldShowSetupWizard(
    completedRevision: Int?,
    currentRevision: Int = SETUP_WIZARD_REVISION,
): Boolean = (completedRevision ?: 0) < currentRevision

/**
 * The revision each step arrived in, for the steps an upgrade asks. Null means an upgrade never
 * offers it - including Sources (revision 9), which by that revision's own decision is not
 * replayed for anyone who finished revision 8.
 */
fun setupStepOfferedOnUpgradeFrom(step: SetupStep): Int? = when (step) {
    SetupStep.DownloadMode, SetupStep.DownloadSetup -> 10
    // Revision 11 (2026-10-20): the language settings were rebuilt (`LanguageSettingsSections`).
    SetupStep.Language, SetupStep.LanguageOwnTitles, SetupStep.LanguageOtherTitles -> 11
    else -> null
}

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
     * Device Setup's greeting after a cross-family import: "Welcome back - we brought over your Nuvio
     * Z preferences from your other device", with what came over. Offers **Review setup**, which
     * switches to a normal Initial Setup with every imported answer preselected. Sets nothing.
     * Only in a plan with [SetupWizardPlan.arrival].
     */
    WelcomeBack,

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
     * How downloads pick their source (Phase 9): Automatic, Assisted or Manual - its own question,
     * separate from Playback Mode. Always in a full run and in an upgrade from revision 8 or 9.
     */
    DownloadMode,

    /**
     * The few download preferences the chosen Download Mode uses, plus the device's mobile-data
     * rule on phones. Conditional: see [downloadSetupVariant]. In a [SetupWizardRun.Device] run it
     * is the machine's download questions alone (mobile data, downloads at once), and only while
     * the machine has not answered them.
     */
    DownloadSetup,

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

    /**
     * Subtitles for titles made in the user's language (2026-10-22). The Language step was all three
     * Advanced Setup Language panels on one page; it is now those panels, one step each, with their
     * previews - the same bodies Advanced Setup draws.
     */
    LanguageOwnTitles,

    /** Audio and subtitles for titles made in any other language. See [LanguageOwnTitles]. */
    LanguageOtherTitles,

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

    // --- the device steps (Device Setup redesign, 2026-10-24) ---------------------------------
    // Last, before Done: a Full run ends with them, and a Device run is them. Each is an Advanced
    // Setup panel narrowed by `deviceSetupControls`, never a page of its own. Their settings are kept
    // per profile *and* per device: once this device is set up for the profile, sync no longer
    // overwrites them here (`DeviceOwnedSettings`).

    /**
     * **This** platform's navigation - the Android bar's style and glow, the iOS 26 Liquid Glass tab
     * bar, or the desktop sidebar / top bar and its style. Only with
     * [SetupWizardPlan.offerDeviceNavigation].
     */
    DeviceNavigation,

    /**
     * Mobile only: the in-app player's layout (new or legacy controls) and its touch behaviour
     * (gestures, hold-to-speed). Only with [SetupWizardPlan.offerDevicePlayer].
     */
    DevicePlayer,

    /**
     * What may interrupt you here: new-episode alerts on phones (per profile), and - when this
     * installation has not answered it yet - the phone's Social alerts or the desktop's pop-ups. Only
     * with [SetupWizardPlan.offerDeviceNotifications].
     */
    DeviceNotifications,

    /**
     * Desktop only, and only while the machine has not answered it ([SetupWizardPlan.deviceStale]):
     * the close button, Start with Windows, Discord Rich Presence and the interface size - Advanced
     * Setup's This computer area. Only with [SetupWizardPlan.offerThisComputer].
     */
    ThisComputer,

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
 * What the download-setup step asks (Phase 9, plan stage 2): at most four controls, so it never
 * scrolls.
 *
 * | Variant | Controls |
 * | --- | --- |
 * | [Automatic] | resolution, size level, fallback, + mobile data on phones |
 * | [Assisted] | size level, + mobile data on phones |
 * | [DeviceOnly] | mobile data (Manual on a phone); a [SetupWizardRun.Device] run adds downloads at once |
 * | [None] | nothing - Manual on desktop; the step is dropped |
 *
 * Assisted asks no resolution because the user picks one per download; Manual asks no preference at
 * all because the user picks every source.
 */
enum class DownloadSetupVariant { None, Automatic, Assisted, DeviceOnly }

/**
 * Takes the Download Mode's **name** for the same import-free reason as [playbackSetupVariant].
 * An unrecognised name is treated as Manual - asking Automatic's questions of a mode that ignores
 * them would write preferences nothing reads.
 */
fun downloadSetupVariant(downloadModeName: String?, isPhone: Boolean, run: SetupWizardRun = SetupWizardRun.Full): DownloadSetupVariant =
    when {
        run == SetupWizardRun.Device -> if (isPhone) DownloadSetupVariant.DeviceOnly else DownloadSetupVariant.None
        downloadModeName == "AUTOMATIC" -> DownloadSetupVariant.Automatic
        downloadModeName == "ASSISTED" -> DownloadSetupVariant.Assisted
        isPhone -> DownloadSetupVariant.DeviceOnly
        else -> DownloadSetupVariant.None
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

    /**
     * The Download Mode the step is showing - the stored answer, or the one Playback Mode implies
     * while there is none. A name, for the import-free reason above.
     */
    val downloadModeName: String = "MANUAL",

    /** False on desktop: no mobile-data question, and Manual has nothing left to ask. */
    val isPhone: Boolean = true,

    /** Which wizard this is. A re-run from Settings is always [SetupWizardRun.Full]. */
    val run: SetupWizardRun = SetupWizardRun.Full,

    /** The profile's revision before this run; decides what an [SetupWizardRun.Upgrade] asks. */
    val fromRevision: Int = 0,

    /**
     * The profile arrived on this platform family through the cross-family import. Adds
     * [SetupStep.WelcomeBack] and - for a mode that uses them - the playback preferences the import
     * deliberately did not bring (quality limit and HDR depend on the device and its connection).
     */
    val arrival: Boolean = false,

    /**
     * This installation still owes the machine's own questions (see [isDeviceSetupStale]): the Device
     * run's download step and [SetupStep.ThisComputer], and the machine-wide rows the screen draws.
     */
    val deviceStale: Boolean = true,

    /**
     * This platform has a navigation choice of its own: Android (style, glow), iPhone on iOS 26+
     * (Liquid Glass) and desktop (sidebar / top bar, style). False on iOS before 26, which has nothing
     * to choose. The screen passes `deviceSetupControls(...).isNotEmpty()`, so the step can never be
     * offered with nothing on it.
     */
    val offerDeviceNavigation: Boolean = false,

    /**
     * This platform's in-app player has layout and touch choices: Android and iOS, and not while an
     * external player is chosen (the in-app player's settings would do nothing). False on desktop.
     */
    val offerDevicePlayer: Boolean = false,

    /** [SetupStep.DeviceNotifications] has something to ask here (see `deviceSetupControls`). */
    val offerDeviceNotifications: Boolean = false,

    /** [SetupStep.ThisComputer] has something to ask here: desktop, while [deviceStale]. */
    val offerThisComputer: Boolean = false,
)

/** What the download-setup step asks in this plan. */
fun downloadSetupVariant(plan: SetupWizardPlan): DownloadSetupVariant =
    downloadSetupVariant(plan.downloadModeName, plan.isPhone, plan.run)

/**
 * The steps this run will actually show, in order.
 *
 * A Device run whose only step would be Done shows nothing at all - a Classic profile on an iPhone
 * before iOS 26, say, with an external player and the machine already answered - and a screen that
 * only says "done" is not a wizard.
 */
fun setupWizardSteps(plan: SetupWizardPlan): List<SetupStep> {
    val steps = setupWizardStepsUnpruned(plan)
    return if (plan.run == SetupWizardRun.Device && steps == listOf(SetupStep.Done)) emptyList() else steps
}

private fun setupWizardStepsUnpruned(plan: SetupWizardPlan): List<SetupStep> = SetupStep.entries.filter { step ->
    val offeredHere = when (plan.run) {
        SetupWizardRun.Full -> true
        SetupWizardRun.Upgrade -> (setupStepOfferedOnUpgradeFrom(step) ?: 0) > plan.fromRevision
        // Device Setup (2026-10-24): a fixed set per platform family, whatever the profile answered
        // elsewhere. The picture questions are PlaybackSetup's own (quality limit and HDR depend on
        // the screen and the connection); the download step is the machine's questions alone.
        SetupWizardRun.Device -> step in DeviceRunSteps
        SetupWizardRun.None -> false
    }
    val arrivalStep = isArrivalStep(step, plan)
    (offeredHere || arrivalStep) && when (step) {
        SetupStep.WelcomeBack -> plan.arrival
        SetupStep.DeviceNavigation -> plan.offerDeviceNavigation
        SetupStep.DevicePlayer -> plan.offerDevicePlayer
        SetupStep.DeviceNotifications -> plan.offerDeviceNotifications
        SetupStep.ThisComputer -> plan.offerThisComputer
        SetupStep.PlaybackSetup ->
            playbackSetupVariant(plan.playbackModeName) != PlaybackSetupVariant.None
        SetupStep.DownloadSetup ->
            downloadSetupVariant(plan) != DownloadSetupVariant.None &&
                (plan.run != SetupWizardRun.Device || plan.deviceStale)
        SetupStep.SocialIdentity -> plan.socialEnabled && plan.offerSocialIdentity
        else -> true
    }
}

/** Every step a Device run can show; the plan's flags decide which of them it does. */
private val DeviceRunSteps: Set<SetupStep> = setOf(
    SetupStep.WelcomeBack,
    SetupStep.PlaybackSetup,
    SetupStep.DownloadSetup,
    SetupStep.DeviceNavigation,
    SetupStep.DevicePlayer,
    SetupStep.DeviceNotifications,
    SetupStep.ThisComputer,
    SetupStep.Done,
)

/**
 * What an arrival adds to an **Upgrade** (the imported revision was 8 to 10): the greeting, the
 * device-sensitive playback preferences the import deliberately left behind (quality and HDR - only
 * for a mode that picks, see [playbackSetupVariant]), and the platform's navigation and player
 * choices the other family could never have answered. An arrival that owes no upgrade is a Device
 * run, which carries all of these anyway.
 */
private fun isArrivalStep(step: SetupStep, plan: SetupWizardPlan): Boolean {
    if (!plan.arrival) return false
    if (plan.run != SetupWizardRun.Device && plan.run != SetupWizardRun.Upgrade) return false
    return when (step) {
        SetupStep.WelcomeBack, SetupStep.PlaybackSetup -> true
        SetupStep.DeviceNavigation -> plan.offerDeviceNavigation
        SetupStep.DevicePlayer -> plan.offerDevicePlayer
        else -> false
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

// --- the Sources step's starting state (wizard polish, 2026-09-27) ---------------------------

/**
 * What the Sources step finds when it opens, which decides what it says and offers.
 *
 * Before this, "an enabled stream addon exists" was the only question asked, so a profile already
 * running the recommended setup was told its sources were configured and then offered "Use
 * recommended setup instead" - the setup it already had. Each state now has its own copy and its own
 * actions, and the footer's forward action follows from it (see `setupAdvanceFor`).
 */
enum class SetupSourcesStatus {
    /** Nuvio Z's recommended source is installed, enabled and loaded. Nothing to do: Next. */
    Recommended,

    /** A working stream addon that is not the recommended one. Keep it (Next), or add the recommended one. */
    Custom,

    /** An addon is still loading and nothing usable has answered yet. Say so rather than guess. */
    Checking,

    /** Stream addons are installed but none works - failed to load, or all turned off. */
    NeedsAttention,

    /** No stream source at all. */
    None,
}

/** The facts about one installed addon the Sources step reads. Plain values, so this stays pure. */
data class SetupSourceAddonFacts(
    val name: String?,
    val enabled: Boolean,
    /** Its manifest has loaded. */
    val loaded: Boolean,
    /** Its manifest offers the `stream` resource. Unknowable (false) until loaded. */
    val providesStreams: Boolean,
    val refreshing: Boolean = false,
    /** The last load failed. */
    val failed: Boolean = false,
)

/**
 * Classifies the installed addons. [recommendedNames] are the manifest names the recommended setup
 * installs under (`NUVIO_Z_RECOMMENDED_ADDON_NAMES`).
 *
 * A usable source always wins over a broken one: one working addon beside a failed one is a working
 * setup, not an alarm. "Checking" only when nothing usable has answered and something is still
 * loading - a verdict shown before the data settles would change under the user.
 */
fun setupSourcesStatus(
    addons: List<SetupSourceAddonFacts>,
    recommendedNames: Set<String>,
): SetupSourcesStatus {
    val usable = addons.filter { it.enabled && it.loaded && it.providesStreams }
    return when {
        usable.any { it.name in recommendedNames } -> SetupSourcesStatus.Recommended
        usable.isNotEmpty() -> SetupSourcesStatus.Custom
        addons.any { it.enabled && !it.loaded && it.refreshing } -> SetupSourcesStatus.Checking
        addons.any { (it.enabled && !it.loaded && it.failed) || (!it.enabled && it.providesStreams) } ->
            SetupSourcesStatus.NeedsAttention
        else -> SetupSourcesStatus.None
    }
}

// --- the stacked layout's band height ------------------------------------------------------------

/**
 * The tallest drawing the Diagram band holds, in dp: the storyboard's chip stage (3 x 30 + 2 x 6,
 * see the metrics block in `SetupDiagram.kt`). Change both together.
 */
internal const val SetupDiagramContentDp = 102f

/** The least room kept above and below a shrunk diagram, so it never touches the seam. */
private const val SetupDiagramMarginDp = 8f

/** How far a diagram may be scaled down before it stops reading as the drawing it is. */
private const val SetupDiagramMinScale = 0.6f

/**
 * The band's height in the stacked (phone) layout, in dp.
 *
 * **Priority: the panel's controls, then its pinned footer, then the illustration.** The band asks
 * for [preferredDp] (capped at half the window, as it always was) and gives way - down to
 * [minimumDp], never below - only when the panel would otherwise scroll. [requiredPanelDp] is what
 * the panel measured it needs (header + body + footer + padding); null until it has been measured,
 * which keeps the preferred height rather than guessing. [aboveDp] is what sits above the panel
 * besides the band itself: the status-bar inset the band pads by, and the seam.
 *
 * Neither input depends on the band, so this settles in one pass instead of chasing its own tail.
 * A tall phone gets exactly the old height back: its panel fits, so there is nothing to give.
 */
internal fun setupStackedBandHeightDp(
    preferredDp: Float,
    minimumDp: Float,
    windowHeightDp: Float,
    aboveDp: Float,
    requiredPanelDp: Float?,
): Float {
    val preferred = minOf(preferredDp, windowHeightDp * 0.5f)
    if (requiredPanelDp == null) return preferred
    val room = windowHeightDp - aboveDp - requiredPanelDp
    return room.coerceIn(minOf(minimumDp, preferred), preferred)
}

/**
 * The scale a Diagram band's drawing is laid out at when the band is [bandDp] tall. 1 at the
 * preferred 150 dp (and anywhere the drawing fits with its margins); below that the drawing shrinks
 * as a whole - text included, through the density override in `SetupDiagram` - instead of being
 * clipped at the seam, and never under [SetupDiagramMinScale].
 */
internal fun setupDiagramScale(bandDp: Float): Float =
    ((bandDp - 2 * SetupDiagramMarginDp) / SetupDiagramContentDp).coerceIn(SetupDiagramMinScale, 1f)
