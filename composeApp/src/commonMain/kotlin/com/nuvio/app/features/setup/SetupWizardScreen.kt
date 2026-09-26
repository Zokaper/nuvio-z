package com.nuvio.app.features.setup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.sync.ProfileSettingsSync
import com.nuvio.app.isDesktop
import com.nuvio.app.isIos
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.PosterCardStyleRepository
import com.nuvio.app.core.ui.ThemeColors
import com.nuvio.app.features.membership.MemberAccessRepository
import com.nuvio.app.features.membership.availableAppThemes
import com.nuvio.app.features.settings.CustomThemeEditor
import com.nuvio.app.core.ui.labelRes
import com.nuvio.app.core.ui.isBackdropBlurSupported
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioConsumePointerEvents
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.details.MetaEpisodeCardStyle
import com.nuvio.app.features.details.MetaScreenBackgroundMode
import com.nuvio.app.features.details.MetaScreenSettingsRepository
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.downloads.DownloadMobileDataRule
import com.nuvio.app.features.downloads.DownloadMode
import com.nuvio.app.features.downloads.DownloadModeCard
import com.nuvio.app.features.downloads.DownloadModeOrder
import com.nuvio.app.features.downloads.DownloadPolicy
import com.nuvio.app.features.downloads.DownloadPolicyRepository
import com.nuvio.app.features.downloads.DownloadResolutionFallback
import com.nuvio.app.features.downloads.DownloadResolutionPreference
import com.nuvio.app.features.downloads.DownloadSizeLevel
import com.nuvio.app.features.downloads.DownloadsLiveStatusPlatform
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.DynamicRangePolicy
import com.nuvio.app.features.downloads.downloadFallbackLabel
import com.nuvio.app.features.downloads.downloadMobileDataLabel
import com.nuvio.app.features.downloads.downloadModeName
import com.nuvio.app.features.downloads.downloadResolutionLabel
import com.nuvio.app.features.downloads.downloadSizeLevelDetail
import com.nuvio.app.features.downloads.downloadSizeLevelFigures
import com.nuvio.app.features.downloads.downloadSizeLevelLabel
import com.nuvio.app.features.downloads.forDownloads
import com.nuvio.app.features.playback.LanguageStrictness
import com.nuvio.app.features.playback.PlaybackMode
import com.nuvio.app.features.playback.PlaybackModeCard
import com.nuvio.app.features.playback.playbackModeName
import com.nuvio.app.features.settings.PLAYBACK_QUALITY_CEILING_STEPS
import com.nuvio.app.features.settings.playbackDynamicRangeLabel
import com.nuvio.app.features.settings.playbackLanguageStrictnessLabel
import com.nuvio.app.features.settings.playbackQualityCeilingLabel
import com.nuvio.app.features.social.SocialFeaturePreferencesRepository
import com.nuvio.app.features.social.SocialIdentityBody
import com.nuvio.app.features.social.SocialRepository
import com.nuvio.app.features.social.shutdownSocialLayer
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.AudioLanguageOption
import com.nuvio.app.features.player.AvailableLanguageOptions
import com.nuvio.app.features.player.SubtitleLanguageOption
import com.nuvio.app.features.player.languageLabelForCode
import com.nuvio.app.features.settings.LanguageSelectionDialog
import com.nuvio.app.features.settings.LanguageSelectionOption
import com.nuvio.app.features.settings.ThemeSettingsRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

// String keys are imported by wildcard, which is the style HomeScreen.kt, MetaDetailsScreen.kt
// and DetailHero.kt already use. Deliberate: this screen reads about sixty keys.
//
// ⚠ That decision covers THIS file only. `SettingsRootPage.kt` imports every key explicitly,
// and adding the "Run setup again" row there without its two imports is what failed the first
// debug-release run of the wizard. Check the host file's style before using a key in it.

/**
 * The first-launch setup wizard.
 *
 * ## Two opaque regions, and the reason it is not one
 *
 * Revision 2 put the controls in a translucent sheet floating over a full-bleed preview of the
 * real home screen. On a device the home screen read straight through the panel - "Continue
 * watching", episode titles and poster art behind the heading - and it was not a matter of
 * tuning the alpha: the gradient made the top of the sheet the most transparent part, which is
 * exactly where the heading sits.
 *
 * So there is no overlap here at all. A [SetupSpecimenBand] on top, an opaque panel below,
 * separated by a hairline. **Nothing is ever drawn behind the text**, which makes readability a
 * property of the layout rather than something to check on each theme.
 *
 * ## The specimen shows what the step changes, and does not move
 *
 * The other half of revision 2's problem was that the preview was a whole fake screen: a step
 * about Continue Watching spent most of its band on a hero banner, and the per-step scroll
 * anchoring that tried to fix that left rows half-clipped. Each step now draws one thing - see
 * [SetupSpecimen].
 *
 * ⚠ Revision 3 went one step further and moved the band to whichever control the user last
 * touched. That was worse on a device: the object being studied kept getting swapped out. The
 * band is **fixed per step** now, and the merged steps draw everything they cover at once so
 * the controls can change it in place instead.
 *
 * ⚠ **Every choice is written the moment it is tapped**, through the same repository setter the
 * settings page uses. There is no undo, which matches how every settings page in this app
 * already behaves - and why the exit on the first step is "Skip for now" rather than "use
 * defaults": nothing has been written yet, so there is nothing to restore.
 *
 * @param dismissible true when opened from Settings rather than gating the app.
 */
@Composable
fun SetupWizardScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    dismissible: Boolean = false,
    onDismiss: () -> Unit = {},
    run: SetupWizardRun = SetupWizardRun.Full,
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()

    val playerSettings by remember {
        PlayerSettingsRepository.ensureLoaded()
        PlayerSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val posterStyle by remember {
        PosterCardStyleRepository.ensureLoaded()
        PosterCardStyleRepository.uiState
    }.collectAsStateWithLifecycle()
    val homeSettings by remember {
        HomeCatalogSettingsRepository.ensureLoaded()
        HomeCatalogSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val continueWatching by remember {
        ContinueWatchingPreferencesRepository.ensureLoaded()
        ContinueWatchingPreferencesRepository.uiState
    }.collectAsStateWithLifecycle()
    val metaSettings by remember {
        MetaScreenSettingsRepository.ensureLoaded()
        MetaScreenSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val selectedTheme by remember { ThemeSettingsRepository.selectedTheme }.collectAsStateWithLifecycle()
    val amoledEnabled by remember { ThemeSettingsRepository.amoledEnabled }.collectAsStateWithLifecycle()
    val addons by remember { AddonRepository.uiState }.collectAsStateWithLifecycle()
    val socialPreferences by remember {
        SocialFeaturePreferencesRepository.ensureLoaded()
        SocialFeaturePreferencesRepository.uiState
    }.collectAsStateWithLifecycle()
    val socialState by remember { SocialRepository.uiState }.collectAsStateWithLifecycle()
    val profileState by remember { ProfileRepository.state }.collectAsStateWithLifecycle()
    val downloadPolicy by remember {
        DownloadPolicyRepository.ensureLoaded()
        DownloadPolicyRepository.policy
    }.collectAsStateWithLifecycle()
    // Read, not loaded: loading the download store starts the engine, so that waits for the step
    // that asks the device question (below).
    val deviceDownloadSettings by remember { DownloadsRepository.deviceSettings }.collectAsStateWithLifecycle()

    // The mode the download steps show: the stored answer, or the one Playback Mode implies while
    // there is none - exactly what downloads would use right now.
    val effectiveDownloadMode = downloadPolicy.effectiveMode(playerSettings.playbackMode.forDownloads())
    val isPhone = !isDesktop
    // Captured once: finishing writes the new revision, and an upgrade must not re-plan itself
    // from the value it just wrote.
    val fromRevision = remember { playerSettings.setupWizardCompletedRevision }

    // ⚠ **Kicked off here rather than on the social step, and that head start is the point.** The
    // probe only runs for a profile the local cache cannot answer for - a second install, a
    // cleared data root - and it is a signed-in round trip. Starting it when the wizard opens
    // gives it four screens to land in, so the social switch is already pre-selected correctly by
    // the time anyone reads the question. Starting it on the step itself would show the wrong
    // default first and correct it under the user, which is the one thing that must not happen on
    // a question about their existing account.
    val socialProfileId = profileState.activeProfile?.id?.takeIf(String::isNotBlank)
    LaunchedEffect(socialProfileId) {
        SocialFeaturePreferencesRepository.refreshIdentityProbe(socialProfileId)
    }

    // Saved by name, not by ordinal: an enum reordered in a later release must not resume a
    // process-death-restored wizard on a different step than the user left it on. Revision 3
    // deleted two constants, so `setupStepForSavedName` also has to survive a name that no
    // longer resolves - it gates the app, and a crash here is one the user cannot get past.
    var stepName by rememberSaveable {
        // An upgrade or a device run has no Welcome: it opens on its own first step.
        val first = if (run == SetupWizardRun.Full) {
            SetupStep.Welcome
        } else {
            setupWizardSteps(
                SetupWizardPlan(
                    downloadModeName = effectiveDownloadMode.name,
                    isPhone = isPhone,
                    run = run,
                    fromRevision = fromRevision,
                ),
            ).firstOrNull() ?: SetupStep.Done
        }
        mutableStateOf(first.name)
    }
    val step = remember(stepName) { setupStepForSavedName(stepName) }

    val specimen = step.specimen

    // Every input is live repository state, so the plan is derived rather than stored. That is
    // what keeps the immediate-write model honest: each choice is written through the real setter
    // the moment it is tapped, and the shape of the remaining flow is a pure function of what has
    // been written. A draft would mean the wizard and Settings disagreed about the current value
    // for the length of the flow, which is the drift the shared `PlaybackModeCard` exists to stop.
    val plan = SetupWizardPlan(
        playbackModeName = playerSettings.playbackMode.name,
        socialEnabled = socialPreferences.enabled,
        // Either half can answer: the local cache for anyone who has used social on this machine,
        // the probe for a cache-cold install that still has a backend identity.
        offerSocialIdentity = socialState.me == null && !socialPreferences.hasKnownIdentity,
        downloadModeName = effectiveDownloadMode.name,
        isPhone = isPhone,
        run = run,
        fromRevision = fromRevision,
    )
    // A gating upgrade or device run can be put off: its close control is "Not now", and that
    // records the revision like finishing does, so it is not asked again.
    val skippable = !dismissible && run != SetupWizardRun.Full
    val existingStreamAddonName = addons.addons.firstEnabledStreamAddonName()

    // ⚠ **A step can leave the plan while the user is standing on it, and there are two ways.**
    // Going back and choosing Classic drops PlaybackSetup; turning social off drops SocialIdentity.
    // Sources remains in the plan unconditionally. `nextSetupStep` already answers for a step outside
    // the plan - this is what makes the screen act on that answer instead of leaving the user on
    // a screen the run no longer contains, waiting for a tap.
    LaunchedEffect(plan) {
        if (setupStepPosition(step, plan) == null) {
            stepName = (nextSetupStep(step, plan) ?: SetupStep.Done).name
        }
    }

    // Which way the panel body should slide. Read from the step's position in the plan, so a
    // dropped optional step cannot make Back animate forwards.
    val position = setupStepPosition(step, plan) ?: 1
    var lastPosition by remember { mutableStateOf(position) }
    val goingForward = position >= lastPosition
    LaunchedEffect(position) { lastPosition = position }

    // ⚠ `remember`, never `rememberSaveable`: this state holds the TorBox key while it is being typed,
    // and a saveable would write it into the saved-instance bundle. Only the mode is saved.
    var sourcesModeName by rememberSaveable { mutableStateOf(SetupSourcesMode.Choice.name) }
    val sourcesController = remember {
        SetupSourcesController(
            initial = SetupSourcesState(
                mode = SetupSourcesMode.entries.firstOrNull { it.name == sourcesModeName } ?: SetupSourcesMode.Choice,
            ),
        )
    }
    val sources by sourcesController.state.collectAsStateWithLifecycle()
    LaunchedEffect(sources.mode) { sourcesModeName = sources.mode.name }
    // Nothing sensitive outlives the step: not a half-typed key, not the recovery password.
    // The pickers follow the Language step, which comes first, until the user picks one here.
    LaunchedEffect(step) {
        if (step == SetupStep.DownloadSetup && isPhone) DownloadsRepository.ensureLoaded()
        if (step == SetupStep.Sources) {
            sourcesController.seedLanguages(playerSettings.preferredAudioLanguage, playerSettings.preferredSubtitleLanguage)
        } else {
            sourcesController.forgetSensitive()
        }
    }

    // The handle draft survives a process-death restore for the same reason `stepName` does: a
    // half-typed handle is work, and losing it on a step that gates the app is a bad trade.
    var socialHandle by rememberSaveable { mutableStateOf("") }
    var socialHandleBusy by remember { mutableStateOf(false) }
    var socialHandleMessage by remember { mutableStateOf<String?>(null) }

    val emptyUrlMessage = stringResource(Res.string.addons_error_enter_url)
    val nextUpLabel = stringResource(Res.string.setup_specimen_next_up)

    /**
     * Record the social answer, taking the layer down first when it is being switched off.
     *
     * ⚠ **Teardown before the flag, never after.** Turning social off has to leave any live party
     * through the coordinator - which is what tells the server and, for a host, transfers the
     * party - and that has to finish while the social surfaces still exist. Flipping the
     * preference first would remove them mid-departure. `shutdownSocialLayer` is idempotent, so
     * the ordinary first-run case (nothing running) costs nothing.
     */
    fun setSocialEnabled(enabled: Boolean) {
        if (enabled) {
            SocialFeaturePreferencesRepository.setEnabled(true)
            return
        }
        scope.launch {
            shutdownSocialLayer()
            SocialFeaturePreferencesRepository.setEnabled(false)
        }
    }

    fun complete() {
        // ⚠ `playback_mode_selector_seen` used to be written here too. It is gone: nothing had
        // read it since the setup wizard replaced the standalone selector, and it was being
        // kept alive only to spare a 0.4.x downgrade one extra prompt. The key survives as a
        // sync tombstone so an older client's payload still clears the stale local value.
        PlayerSettingsRepository.markSetupWizardCompleted(SETUP_WIZARD_REVISION)
        // Any run answers this device's questions too - a full run and an upgrade both ask them.
        DeviceSetupStorage.saveRevision(SETUP_DEVICE_REVISION)

        // ⚠ Push immediately rather than leaving it to the observer, because while the wizard is
        // gating the app the observer has *just* been started and `combine(...).drop(1)` throws
        // away the first signature - which is the one carrying the revision that was written a
        // line above. Nothing else changes a setting right after setup, so the remote went on
        // answering with the old revision indefinitely and the pull re-gated the app with it.
        // `exportSettingsBlob` exports the whole blob, so this carries every choice the wizard
        // made too. Failure is fine: it retries on the next local change, and the local value is
        // now protected from the stale remote by `mergeMonotonicSyncInt`.
        scope.launch { ProfileSettingsSync.pushCurrentProfileToRemote() }

        onFinished()
    }

    fun advance() {
        // ⚠ **Leaving the social step forward is an answer, and this is where it becomes one.**
        // The switch shows a value resolved from this profile's history, not a stored preference,
        // and until somebody commits it the migration rule keeps re-deriving it on every launch.
        // Seeing the question and walking past it is a decision; the Welcome skip, which never
        // reaches this step, deliberately is not - see `resolveSocialFeaturesEnabled`.
        // ⚠ Through `setSocialEnabled`, not straight to the repository. A first run has no party
        // to leave, but this same screen is the dismissible re-run from Settings, and walking past
        // this step with the switch off is as much a decision to turn social off as moving the
        // switch is - so it has to take the layer down in the same order.
        if (step == SetupStep.SocialOptIn && socialPreferences.storedPreference == null) {
            setSocialEnabled(socialPreferences.enabled)
        }
        // The same rule for the Download Mode: walking past the preselected (derived) mode is
        // choosing it. Skipping the run, which never gets here, leaves it unanswered.
        if (step == SetupStep.DownloadMode && downloadPolicy.mode == null) {
            DownloadPolicyRepository.setMode(effectiveDownloadMode)
        }
        // Android 13+ asks for notifications once; asking here, after the download questions,
        // is when the prompt makes sense. Elsewhere this does nothing.
        if (step == SetupStep.DownloadSetup && isPhone && !isIos) {
            DownloadsLiveStatusPlatform.onDownloadRequested()
        }
        if (isFinalSetupStep(step, plan)) {
            complete()
        } else {
            stepName = (nextSetupStep(step, plan) ?: SetupStep.Done).name
        }
    }

    val advanceState = setupAdvanceFor(step, sources)

    /** Back from a Sources setup path returns to its question, not to the previous step. */
    fun back() {
        if (advanceState == SetupAdvance.Disabled) {
            sourcesController.backToChoice()
            return
        }
        previousSetupStep(step, plan)?.let { stepName = it.name }
    }

    val sourcesActions = SetupSourcesActions(
        onUseRecommended = sourcesController::chooseRecommended,
        onSetUpManually = sourcesController::chooseManual,
        onKeepExisting = { advance() },
        onDoItLater = { advance() },
        onBackToChoice = sourcesController::backToChoice,
        onTorBoxApiKeyChange = sourcesController::setTorBoxApiKey,
        onSourceLanguageChange = sourcesController::setSourceLanguage,
        onSubtitleLanguageChange = sourcesController::setSubtitleLanguage,
        onSetUpRecommended = { scope.launch { sourcesController.setUpRecommended() } },
        onManualUrlChange = sourcesController::setManualUrl,
        onInstallManual = { scope.launch { sourcesController.installManual(emptyUrlMessage) } },
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.colors.background)
            // ⚠ **A background does not consume pointer input, and this screen is drawn OVER the
            // app.** `Settings → Run setup again` renders this on top of `MainAppContent` rather
            // than in place of it (`App.kt`, and its comment there says so), so before this line
            // every tap that missed one of the wizard's own controls - the whole preview band,
            // the paragraphs, the gaps - landed on the settings screen behind it. It was
            // reported as "pressing the tabs takes me to the nuvio mobile vanilla repo, also
            // this random website": those were the user's installed addons, and the tab row
            // happened to sit where an addon row sits.
            //
            // ⚠ **This is the second time this defect has shipped.** `0.5.0-beta` item 1 was the
            // same thing on the stream route - "the surface consumed no pointer input, so the
            // invisible source list underneath was fully tappable". `nuvioConsumePointerEvents`
            // is the fix written then. ⚠ It now consumes only presses and releases: consuming the
            // movement inside a click cancelled this screen's own buttons, which is the post-release
            // "wizard buttons need several presses on macOS" - see the modifier's own comment.
            //
            // The gate path never showed it because there `MainAppContent` is not composed at
            // all. Only the dismissible re-run is affected, which is why it took a re-run to
            // find. The other on-demand overlay, `WhatsNewScreen`, is a `Dialog` and is immune
            // by construction - this one is a plain full-screen sibling and is not.
            .nuvioConsumePointerEvents(),
    ) {
        val windowHeight = maxHeight
        val windowWidth = maxWidth
        val insets = WindowInsets.safeDrawing.asPaddingValues()

        // ⚠ **The one thing that decides mobile-shaped or desktop-shaped, and it is deliberately
        // the same expression `MetaDetailsScreen` uses for `useDesktopDetailLayout`.** Both
        // halves matter: `isDesktop` keeps an Android tablet on the stacked layout it was
        // designed and tested for, and the width test keeps a desktop window the user has
        // dragged narrow from being handed a two-pane layout that no longer fits in it.
        //
        // The default desktop window is 1280x820 (`Main.kt`), so this is true on launch. Note
        // that `NuvioTheme` scales density by `desktopUiScaleForWindow` against that same
        // 1280x820 base, so this threshold is in scaled dp - at the default window the scale is
        // exactly 1.0 and below it the layout only ever gets *more* room per dp.
        val useDesktopWizardLayout = isDesktop && windowWidth >= DesktopWizardMinWidth

        // Each specimen asks for the height it needs, capped so that a short phone always
        // leaves the panel the larger share.
        //
        // ⚠ The cap only bites on the three specimens that ask for more than half the window -
        // Cards, Home and Details. The playback-mode step asks for 150 dp and is nowhere near
        // it: that step has the tallest panel in the flow (three `PlaybackModeCard`s), revision
        // 2 cut it off mid-card and revision 5 cut it off again at 200 dp. Keep
        // `SetupSpecimen.Diagram.preferredHeight` small rather than trusting this cap.
        val bandHeight by animateDpAsState(
            targetValue = specimen.preferredHeight.coerceAtMost(windowHeight * 0.5f),
            animationSpec = tween(340, easing = LinearOutSlowInEasing),
            label = "setup_band_height",
        )

        // ⚠ **Welcome is the one step laid out as an overlay rather than as two stacked
        // regions**, and the exception is deliberate rather than a slide back towards revision 2.
        //
        // Revision 2 floated a translucent panel over a *live* preview on every step, and it came
        // back from a device unreadable: the home screen showed straight through it while the
        // user was trying to read four control labels. The rule that fixed it - nothing is ever
        // drawn behind text - still governs steps 2-8, which is why they keep the split layout.
        //
        // Welcome is different in the two ways that matter. It carries **no controls**, so there
        // is nothing to read but one heading and two sentences; and it is answering "what is
        // this?", where showing the app *behind* the answer is the answer. It also gets a real
        // blur rather than revision 2's plain translucency - see `SetupWelcomeSurface`.
        if (step == SetupStep.Welcome) {
            SetupWelcomeSurface(
                insets = insets,
                maxPanelWidth = if (windowWidth >= 768.dp) 620.dp else windowWidth,
                desktop = useDesktopWizardLayout,
                onAdvance = ::advance,
                onSkipAll = ::complete,
                dismissible = dismissible,
                onDismiss = onDismiss,
            )
            return@BoxWithConstraints
        }

        // Steps 2-8 on a desktop window: the specimen beside the controls rather than above them.
        // Everything the step *is* - the questions, their order, what each control writes - comes
        // from the same `SetupStepBody` the stacked layout calls. Only the frame differs.
        if (useDesktopWizardLayout) {
            SetupWizardDesktopLayout(
                step = step,
                plan = plan,
                specimen = specimen,
                dismissible = dismissible || skippable,
                onDismiss = if (skippable) ::complete else onDismiss,
                playbackMode = playerSettings.playbackMode,
                posterWidthDp = posterStyle.widthDp,
                posterCornerRadiusDp = posterStyle.cornerRadiusDp,
                landscapeCards = posterStyle.catalogLandscapeModeEnabled,
                showCardTitles = !posterStyle.hideLabelsEnabled,
                heroEnabled = homeSettings.heroEnabled,
                continueWatchingStyle = continueWatching.style,
                useEpisodeThumbnails = continueWatching.useEpisodeThumbnails,
                blurNextUp = continueWatching.blurNextUp,
                backgroundMode = metaSettings.backgroundMode,
                episodeCardStyle = metaSettings.episodeCardStyle,
                blurUnwatchedEpisodes = metaSettings.blurUnwatchedEpisodes,
                tabLayout = metaSettings.tabLayout,
                nextUpLabel = nextUpLabel,
                topInset = insets.calculateTopPadding(),
                bottomInset = insets.calculateBottomPadding(),
                onBack = ::back,
                onAdvance = ::advance,
                advance = advanceState,
            ) {
                SetupStepBody(
                    step = step,
                    goingForward = goingForward,
                    playbackMode = playerSettings.playbackMode,
                    languageStrictness = playerSettings.playbackLanguageStrictness,
                    dynamicRangePolicy = playerSettings.playbackDynamicRangePolicy,
                    qualityCeilingMbps = playerSettings.playbackQualityCeilingMbps,
                    preferredAudioLanguage = playerSettings.preferredAudioLanguage,
                    preferredSubtitleLanguage = playerSettings.preferredSubtitleLanguage,
                    posterWidthDp = posterStyle.widthDp,
                    landscapeCards = posterStyle.catalogLandscapeModeEnabled,
                    selectedTheme = selectedTheme,
                    amoledEnabled = amoledEnabled,
                    socialEnabled = socialPreferences.enabled,
                    socialProbeUnknown = socialPreferences.identityProbe == SocialIdentityProbe.Indeterminate,
                    socialSignedIn = socialProfileId != null,
                    socialHandle = socialHandle,
                    socialHandleBusy = socialHandleBusy,
                    socialHandleMessage = socialHandleMessage,
                    onSocialEnabledChange = ::setSocialEnabled,
                    onSocialHandleChange = {
                        socialHandle = it
                        socialHandleMessage = null
                    },
                    onSaveSocialHandle = {
                        saveSocialHandle(
                            scope = scope,
                            profileId = socialProfileId,
                            handle = socialHandle,
                            setBusy = { socialHandleBusy = it },
                            onFailed = { socialHandleMessage = it },
                        )
                    },
                    sources = sources,
                    existingSourceName = existingStreamAddonName,
                    sourcesActions = sourcesActions,
                    downloadMode = effectiveDownloadMode,
                    downloadPolicy = downloadPolicy,
                    mobileDataRule = deviceDownloadSettings.mobileData,
                    downloadSetupVariant = downloadSetupVariant(plan),
                    askMobileData = isPhone,
                    showNotificationNote = isPhone && !isIos,
                )
            }
            return@BoxWithConstraints
        }

        Column(modifier = Modifier.fillMaxSize()) {
            SetupSpecimenBand(
                specimen = specimen,
                step = step,
                playbackMode = playerSettings.playbackMode,
                height = bandHeight,
                contentPaddingTop = insets.calculateTopPadding(),
                posterWidthDp = posterStyle.widthDp,
                posterCornerRadiusDp = posterStyle.cornerRadiusDp,
                landscapeCards = posterStyle.catalogLandscapeModeEnabled,
                showCardTitles = !posterStyle.hideLabelsEnabled,
                heroEnabled = homeSettings.heroEnabled,
                continueWatchingStyle = continueWatching.style,
                useEpisodeThumbnails = continueWatching.useEpisodeThumbnails,
                blurNextUp = continueWatching.blurNextUp,
                backgroundMode = metaSettings.backgroundMode,
                episodeCardStyle = metaSettings.episodeCardStyle,
                blurUnwatchedEpisodes = metaSettings.blurUnwatchedEpisodes,
                tabLayout = metaSettings.tabLayout,
                nextUpLabel = nextUpLabel,
                modifier = Modifier.fillMaxWidth(),
                downloadModeName = plan.downloadModeName,
            )

            // The seam. A hairline alone drew a hard rule across the screen; this is the same
            // hairline over a short gradient that lifts the panel's colour up into the bottom
            // of the band, so the two regions stay distinct without a line through them.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(tokens.borders.hairline)
                    .background(tokens.colors.borderSubtle.copy(alpha = 0.6f)),
            )

            SetupPanel(
                step = step,
                plan = plan,
                playbackMode = playerSettings.playbackMode,
                dismissible = dismissible || skippable,
                onDismiss = if (skippable) ::complete else onDismiss,
                // Centred and capped on wide windows. The band is what should use the extra
                // width, not a line of body text stretched across a desktop monitor.
                maxPanelWidth = if (windowWidth >= 768.dp) 620.dp else windowWidth,
                bottomInset = insets.calculateBottomPadding(),
                onBack = ::back,
                onAdvance = ::advance,
                advance = advanceState,
                modifier = Modifier.weight(1f),
            ) {
                SetupStepBody(
                    step = step,
                    goingForward = goingForward,
                    playbackMode = playerSettings.playbackMode,
                    languageStrictness = playerSettings.playbackLanguageStrictness,
                    dynamicRangePolicy = playerSettings.playbackDynamicRangePolicy,
                    qualityCeilingMbps = playerSettings.playbackQualityCeilingMbps,
                    preferredAudioLanguage = playerSettings.preferredAudioLanguage,
                    preferredSubtitleLanguage = playerSettings.preferredSubtitleLanguage,
                    posterWidthDp = posterStyle.widthDp,
                    landscapeCards = posterStyle.catalogLandscapeModeEnabled,
                    selectedTheme = selectedTheme,
                    amoledEnabled = amoledEnabled,
                    socialEnabled = socialPreferences.enabled,
                    socialProbeUnknown = socialPreferences.identityProbe == SocialIdentityProbe.Indeterminate,
                    socialSignedIn = socialProfileId != null,
                    socialHandle = socialHandle,
                    socialHandleBusy = socialHandleBusy,
                    socialHandleMessage = socialHandleMessage,
                    onSocialEnabledChange = ::setSocialEnabled,
                    onSocialHandleChange = {
                        socialHandle = it
                        socialHandleMessage = null
                    },
                    onSaveSocialHandle = {
                        saveSocialHandle(
                            scope = scope,
                            profileId = socialProfileId,
                            handle = socialHandle,
                            setBusy = { socialHandleBusy = it },
                            onFailed = { socialHandleMessage = it },
                        )
                    },
                    sources = sources,
                    existingSourceName = existingStreamAddonName,
                    sourcesActions = sourcesActions,
                    downloadMode = effectiveDownloadMode,
                    downloadPolicy = downloadPolicy,
                    mobileDataRule = deviceDownloadSettings.mobileData,
                    downloadSetupVariant = downloadSetupVariant(plan),
                    askMobileData = isPhone,
                    showNotificationNote = isPhone && !isIos,
                )
            }
        }
    }
}

/**
 * The Welcome step: a real still of the home screen under a frosted panel.
 *
 * ## Why this is not revision 2 coming back
 *
 * Revision 2 put a **translucent** panel over a **live** preview on **every** step. All three
 * words matter, and all three are different here:
 *
 * - **Real blur, not translucency.** `hazeEffect` is the same backdrop blur the floating nav bar
 *   and the streams tablet panel already ship. Revision 2 had no blur available to it and used a
 *   gradient alpha instead, which is why the home screen read straight through it.
 * - **A still, not a live preview.** Nothing behind this panel moves or changes; there is no
 *   artwork sliding under a heading mid-sentence.
 * - **One step, not eight.** Steps 2-8 have four controls each to read while the preview changes
 *   underneath them, and they keep the two-opaque-regions layout that made them legible.
 *
 * ⚠ **The tint alone has to carry legibility.** `minSdk` is 24 and Haze cannot reach
 * `RenderEffect` below API 31, so on a large part of the range this is a scrim and nothing else -
 * exactly the conditions revision 2 failed under. The alphas below are chosen for the **no-blur**
 * case; the blur is refinement. Do not thin them after looking at an Android 14 device.
 *
 * ⚠ **The tint is strongest at the top, and that is the inversion of revision 2's mistake.** Its
 * sheet faded a gradient *towards* transparency at the top edge, which is precisely where the
 * heading sits. Here the heading gets the most cover and the fade runs the other way.
 */
@Composable
private fun SetupWelcomeSurface(
    insets: PaddingValues,
    maxPanelWidth: Dp,
    desktop: Boolean,
    onAdvance: () -> Unit,
    onSkipAll: () -> Unit,
    dismissible: Boolean,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val hazeState = rememberHazeState()

    // ⚠ **Which set of alphas, and it is not a preference.** With a real blur behind it the panel
    // can be genuinely translucent and still read. Without one - `minSdk` is 24 and
    // `RenderEffect` is API 31+ - the same alphas are revision 2's sheet, which came back from a
    // device unreadable with the home screen showing through the heading. See
    // `isBackdropBlurSupported`.
    val blurred = remember { isBackdropBlurSupported() }
    val tintTop = if (blurred) FrostedTintTop else ScrimTintTop
    val tintMid = if (blurred) FrostedTintMid else ScrimTintMid
    val tintBottom = if (blurred) FrostedTintBottom else ScrimTintBottom

    Box(modifier = Modifier.fillMaxSize()) {
        // ⚠ **The scrim is inside the haze source, not layered over it.** `hazeEffect` samples
        // whatever this subtree draws, so a scrim applied as a sibling *after* it would leave the
        // panel blurring the original bright artwork while the screen around the panel was dimmed
        // - the panel would read brighter than its own surroundings, which is backwards.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(state = hazeState),
        ) {
            SetupHomeStill(modifier = Modifier.fillMaxSize())

            // The still is a real home screen - hero artwork, two catalog rows, a Continue
            // Watching row - and on a large display that is a great deal of colour competing with
            // two sentences in a corner. Reported as "the homepage is too overwhelming, you almost
            // don't notice the wizard". Dimming it is half the fix; the panel also got bigger.
            //
            // ⚠ Only on desktop. On a phone the panel already covers most of the window, so a
            // scrim there would darken the screen for nothing.
            if (desktop) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(tokens.colors.background.copy(alpha = WelcomeStillScrimAlpha)),
                )
            }
        }

        // ⚠ **The panel is a bounded card on desktop and a full-width strip on a phone, and the
        // difference is not cosmetic.** On a phone the panel spans the window because the window
        // *is* the panel's natural width. Carried onto a 1280 dp window unchanged, the same code
        // tinted the full width while holding a 620 dp column of text in the middle of it - a
        // wide empty bar with a strip of writing in the centre, which is what "the intro screen
        // looks super off" was.
        //
        // Bottom-**start**, not centre: it sits over the corner of the still that carries the
        // least of the hero's own content, and it lines up with the sidebar rail the still now
        // draws rather than floating free of it.
        val panelAlignment = if (desktop) Alignment.BottomStart else Alignment.BottomCenter
        val panelShape = RoundedCornerShape(WelcomePanelCornerRadius)

        Box(
            modifier = Modifier
                .align(panelAlignment)
                .then(
                    if (desktop) {
                        Modifier
                            .padding(
                                start = SetupStillRailWidth + 32.dp,
                                end = 32.dp,
                                bottom = 48.dp + insets.calculateBottomPadding(),
                            )
                            .widthIn(max = WelcomeDesktopPanelWidth)
                            // Clip *before* the blur and the tint, so both stop at the rounded
                            // edge instead of the card showing square corners over the still.
                            .clip(panelShape)
                    } else {
                        Modifier.fillMaxWidth()
                    },
                )
                .hazeEffect(state = hazeState) { blurRadius = WelcomeBlurRadius }
                .background(
                    Brush.verticalGradient(
                        0f to tokens.colors.background.copy(alpha = tintTop),
                        0.55f to tokens.colors.background.copy(alpha = tintMid),
                        1f to tokens.colors.background.copy(alpha = tintBottom),
                    ),
                )
                .then(
                    if (desktop) {
                        Modifier.border(
                            width = tokens.borders.hairline,
                            color = tokens.colors.borderSubtle.copy(alpha = 0.6f),
                            shape = panelShape,
                        )
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = if (desktop) WelcomeDesktopPanelWidth else maxPanelWidth)
                    .fillMaxWidth()
                    .padding(
                        start = if (desktop) 36.dp else 22.dp,
                        end = if (desktop) 36.dp else 22.dp,
                        top = if (desktop) 36.dp else 26.dp,
                        bottom = if (desktop) 36.dp else 14.dp + insets.calculateBottomPadding(),
                    ),
                verticalArrangement = Arrangement.spacedBy(if (desktop) 20.dp else 16.dp),
            ) {
                SetupPanelHeader(
                    step = SetupStep.Welcome,
                    plan = SetupWizardPlan(),
                    // Welcome's subtitle does not depend on the mode; the default keeps this
                    // surface from having to thread a value it never reads.
                    playbackMode = PlaybackMode.Default,
                    dismissible = dismissible,
                    onDismiss = onDismiss,
                )
                SetupParagraph(stringResource(Res.string.setup_welcome_body))
                if (desktop) {
                    // Sized to their labels. A pointer does not need a 460 dp target, and two
                    // stacked full-width buttons in a card read as a phone sheet.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(onClick = onAdvance) {
                            Text(text = stringResource(Res.string.setup_welcome_start))
                        }
                        TextButton(onClick = onSkipAll) {
                            Text(text = stringResource(Res.string.setup_welcome_skip))
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(onClick = onAdvance, modifier = Modifier.fillMaxWidth()) {
                            Text(text = stringResource(Res.string.setup_welcome_start))
                        }
                        TextButton(onClick = onSkipAll, modifier = Modifier.fillMaxWidth()) {
                            Text(text = stringResource(Res.string.setup_welcome_skip))
                        }
                    }
                }
            }
        }
    }
}

/**
 * How wide the intro card is on desktop.
 *
 * ⚠ Was 520 dp (`NuvioComponentTokens.sheetMaxWidth`) and that was too modest: on a large display
 * a 520 dp card in the corner of the window reads as a tooltip rather than as the thing the screen
 * is for. 640 gives the heading and both sentences room to sit on fewer lines, which is most of
 * what makes it read as a panel.
 */
private val WelcomeDesktopPanelWidth = 640.dp

/**
 * How far the home-screen still is dimmed behind the intro card.
 *
 * ⚠ Deliberately a flat wash rather than a gradient. The panel is anchored in one corner, so a
 * gradient would have to be aimed at it, and every future move of the panel would silently leave
 * the bright end in the wrong place. Flat is also honest about what it is: the app, turned down.
 *
 * This is **not** part of the panel's own tint - see `FrostedTintTop` and the warning above it.
 * Those alphas are tuned for the no-blur case and must not be traded off against this one.
 */
private const val WelcomeStillScrimAlpha = 0.62f

/** `NuvioTokens.Radius.card`. Kept local so the mobile path keeps its square-edged strip. */
private val WelcomePanelCornerRadius = 24.dp

/**
 * The width the two-pane layout needs before it is worth using.
 *
 * Same threshold as `MetaDetailsScreen`'s `useDesktopDetailLayout`. Below it the control pane
 * would be clamped to its 460 dp minimum and the specimen would get less room than the stacked
 * layout already gives it, so the stacked layout is simply better there.
 */
private val DesktopWizardMinWidth = 1000.dp

/** Matches the streams tablet panel rather than the nav pill: this pane is much larger. */
private val WelcomeBlurRadius = 40.dp


// The tint, in two sets.
//
// **Frosted** - what a device with a working blur gets. Light enough that the still's colour and
// shapes come through as frosted glass, which is the whole point of drawing a real home screen
// behind it. Safe only because a 40 dp blur has destroyed the high-frequency detail that makes
// text hard to read over a picture.
private const val FrostedTintTop = 0.68f
private const val FrostedTintMid = 0.60f
private const val FrostedTintBottom = 0.54f

// **Scrim** - what a device below API 31 gets, where `hazeEffect` does nothing and this is the
// only thing between the heading and a poster. ⚠ Do not thin these to match the frosted set
// after looking at a modern phone; that is exactly the mistake revision 2 shipped.
private const val ScrimTintTop = 0.94f
private const val ScrimTintMid = 0.88f
private const val ScrimTintBottom = 0.82f

/**
 * What the band shows for a step.
 *
 * ⚠ **Fixed, and it used to be state.** Revision 3 held the current specimen in a
 * `remember(step)` and let each control move it, so the band followed whatever the user last
 * touched. Tested on a device that read as jarring - the object you were studying kept being
 * swapped out from under you. Each step now draws one thing that never changes while you are on
 * it, and the controls change that thing *in place*. There is no specimen state left to get
 * wrong.
 */
private val SetupStep.specimen: SetupSpecimen
    get() = when (this) {
        // ⚠ Never read. Welcome returns early from `SetupWizardScreen` into
        // `SetupWelcomeSurface`, which draws a full-bleed `SetupHomeStill` instead of a band.
        // Enumerated rather than defaulted so that adding a step stays a compile error here.
        SetupStep.Welcome -> SetupSpecimen.Diagram
        SetupStep.Look -> SetupSpecimen.Cards
        SetupStep.Theme -> SetupSpecimen.Theme
        // ⚠ `SetupSpecimen.Home` and `SetupSpecimen.Details` are no longer reachable from the
        // wizard - revision 7 moved those questions to Settings - but the specimens themselves
        // stay in `SetupSpecimen.kt`. They cost nothing, they are byte-shared with the mobile
        // repository, and `SetupWizardRenderHarness` keeps drawing them, which is the only thing
        // that would ever catch them drifting from the real screens they mirror.
        SetupStep.PlaybackMode,
        SetupStep.PlaybackSetup,
        SetupStep.DownloadMode,
        SetupStep.DownloadSetup,
        SetupStep.Language,
        SetupStep.Sources,
        SetupStep.SocialOptIn,
        SetupStep.SocialIdentity,
        SetupStep.Done,
        -> SetupSpecimen.Diagram
    }

/**
 * The opaque panel the controls live in.
 *
 * Plain `colors.surface` and nothing else. ⚠ Note that `surfaceSheet`, `surfaceElevated` and
 * `surface` are all the same colour in this token set - only `surfaceCard` differs - so the
 * separation from the band above comes from the hairline and from the band's own darker
 * gradient floor, **not** from stacking two surfaces. A card-on-surface here would be invisible,
 * which is the trap the quality sheet already hit.
 */
@Composable
private fun SetupPanel(
    step: SetupStep,
    plan: SetupWizardPlan,
    playbackMode: PlaybackMode,
    dismissible: Boolean,
    onDismiss: () -> Unit,
    maxPanelWidth: Dp,
    bottomInset: Dp,
    onBack: () -> Unit,
    onAdvance: () -> Unit,
    advance: SetupAdvance = SetupAdvance.Shown,
    modifier: Modifier = Modifier,
    body: @Composable () -> Unit,
) {
    val tokens = MaterialTheme.nuvio

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(tokens.colors.surface),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = maxPanelWidth)
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(
                    start = 22.dp,
                    end = 22.dp,
                    top = 18.dp,
                    bottom = 14.dp + bottomInset,
                ),
        ) {
            SetupPanelHeader(
                step = step,
                plan = plan,
                playbackMode = playbackMode,
                dismissible = dismissible,
                onDismiss = onDismiss,
            )
            Spacer(modifier = Modifier.height(16.dp))
            // Scrolls only as a safety net - for a large font scale or a very short window.
            // The band caps itself so that in ordinary use nothing here needs scrolling, which
            // is what went wrong in revision 2.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                body()
            }
            Spacer(modifier = Modifier.height(12.dp))
            SetupPanelFooter(
                step = step,
                plan = plan,
                onBack = onBack,
                onAdvance = onAdvance,
                advance = advance,
            )
        }
    }
}

/**
 * Step counter, title, subtitle and the optional close button.
 *
 * `internal` rather than private because `SetupWizardDesktopLayout` draws the same header in its
 * control pane. Sharing it is the point: a second copy is a second place for the progress line to
 * drift from `setupStepPosition`.
 */
@Composable
internal fun SetupPanelHeader(
    step: SetupStep,
    plan: SetupWizardPlan,
    playbackMode: PlaybackMode,
    dismissible: Boolean,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val position = setupStepPosition(step, plan)
    val total = setupWizardSteps(plan).size

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (position != null && step != SetupStep.Welcome) {
                Text(
                    text = stringResource(Res.string.setup_step_progress, position, total),
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.colors.textMuted,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = stringResource(step.titleRes),
                style = MaterialTheme.typography.headlineSmall,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = setupStepSubtitle(step, plan, playbackMode),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textSecondary,
            )
        }
        if (dismissible) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(tokens.colors.overlayHover)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(Res.string.setup_close),
                    tint = tokens.colors.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun SetupPanelFooter(
    step: SetupStep,
    plan: SetupWizardPlan,
    onBack: () -> Unit,
    onAdvance: () -> Unit,
    advance: SetupAdvance = SetupAdvance.Shown,
) {
    // Welcome's own two buttons live in `SetupWelcomeSurface`; it never reaches this panel.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SetupBackButton(step = step, plan = plan, onBack = onBack)
        Spacer(modifier = Modifier.weight(1f))
        SetupAdvanceButton(step = step, plan = plan, onAdvance = onAdvance, advance = advance)
    }
}

/**
 * Back, or nothing on the first step shown.
 *
 * Split out of [SetupPanelFooter] so the desktop pane's footer cannot drift from it. ⚠ The
 * *absence* on the first step is the behaviour, not an oversight: `previousSetupStep` answers
 * against the plan, so a dropped optional step cannot leave a Back button that goes nowhere.
 */
@Composable
internal fun SetupBackButton(
    step: SetupStep,
    plan: SetupWizardPlan,
    onBack: () -> Unit,
) {
    if (previousSetupStep(step, plan) != null) {
        TextButton(onClick = onBack) {
            Text(text = stringResource(Res.string.setup_back))
        }
    }
}

/** Whether the footer offers Next on the current screen. */
enum class SetupAdvance { Shown, Disabled, Hidden }

/**
 * The footer's Next for a step. Only Sources restricts it: people press Next without reading, so the
 * source question has no Next at all (its own buttons, including "Do it later", are the only ways on),
 * and a setup path keeps Next greyed until a source is actually installed. Leaving a path without
 * one is Back, which returns to the question.
 */
internal fun setupAdvanceFor(step: SetupStep, sources: SetupSourcesState): SetupAdvance = when {
    step != SetupStep.Sources || sources.configuredName != null -> SetupAdvance.Shown
    sources.mode == SetupSourcesMode.Choice -> SetupAdvance.Hidden
    else -> SetupAdvance.Disabled
}

/** Next, or Finish on the last step the plan will show. */
@Composable
internal fun SetupAdvanceButton(
    step: SetupStep,
    plan: SetupWizardPlan,
    onAdvance: () -> Unit,
    advance: SetupAdvance = SetupAdvance.Shown,
) {
    if (advance == SetupAdvance.Hidden) return
    Button(onClick = onAdvance, enabled = advance == SetupAdvance.Shown) {
        Text(
            text = stringResource(
                if (isFinalSetupStep(step, plan)) {
                    Res.string.setup_done_finish
                } else {
                    Res.string.setup_next
                },
            ),
        )
    }
}

/**
 * The controls for the current step.
 *
 * `internal` so `SetupWizardRenderHarness` can draw a real step body inside the desktop layout.
 * Before the desktop layout existed no wizard step could be rendered off-screen at all - the step
 * lives in `rememberSaveable` state in [SetupWizardScreen] and there is no way in from outside -
 * so the harness could only ever cover Welcome and the bands in isolation. ⚠ Keep it callable.
 *
 * ⚠ **Reads no repository for its state.** Every displayed value arrives as a parameter, which is
 * what lets the harness draw a step without the app around it, and what lets one implementation
 * serve both the stacked and the two-pane layouts.
 */
@Composable
internal fun SetupStepBody(
    step: SetupStep,
    goingForward: Boolean,
    playbackMode: PlaybackMode,
    languageStrictness: LanguageStrictness,
    dynamicRangePolicy: DynamicRangePolicy,
    qualityCeilingMbps: Int,
    preferredAudioLanguage: String,
    preferredSubtitleLanguage: String,
    posterWidthDp: Int,
    landscapeCards: Boolean,
    selectedTheme: AppTheme,
    amoledEnabled: Boolean,
    socialEnabled: Boolean,
    socialProbeUnknown: Boolean,
    socialSignedIn: Boolean,
    socialHandle: String,
    socialHandleBusy: Boolean,
    socialHandleMessage: String?,
    onSocialEnabledChange: (Boolean) -> Unit,
    onSocialHandleChange: (String) -> Unit,
    onSaveSocialHandle: () -> Unit,
    sources: SetupSourcesState,
    existingSourceName: String?,
    sourcesActions: SetupSourcesActions,
    downloadMode: DownloadMode = DownloadMode.MANUAL,
    downloadPolicy: DownloadPolicy = DownloadPolicy(),
    mobileDataRule: DownloadMobileDataRule = DownloadMobileDataRule.WIFI_ONLY,
    downloadSetupVariant: DownloadSetupVariant = DownloadSetupVariant.None,
    askMobileData: Boolean = false,
    showNotificationNote: Boolean = false,
) {
    AnimatedContent(
        targetState = step,
        // Slide as well as fade, in the direction of travel, so Back visibly reverses Next.
        // A crossfade alone read as a flicker: two sets of controls at similar positions
        // dissolving into each other with nothing to say which way the flow went.
        transitionSpec = {
            val offset = if (goingForward) 1 else -1
            (
                fadeIn(tween(220)) +
                    slideInHorizontally(tween(280, easing = LinearOutSlowInEasing)) { it / 6 * offset }
                ) togetherWith (
                fadeOut(tween(140)) +
                    slideOutHorizontally(tween(280, easing = LinearOutSlowInEasing)) { -it / 6 * offset }
                )
        },
        label = "setup_step_body",
    ) { current ->
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (current) {
                // Unreachable - Welcome is drawn by `SetupWelcomeSurface`, which owns its own
                // copy. Enumerated so that adding a step is a compile error rather than a blank
                // panel.
                SetupStep.Welcome -> Unit

                SetupStep.PlaybackMode -> {
                    PlaybackMode.entries.forEach { mode ->
                        PlaybackModeCard(
                            mode = mode,
                            isSelected = mode == playbackMode,
                            onClick = { PlayerSettingsRepository.setPlaybackMode(mode) },
                            enabled = mode.isSelectable,
                        )
                    }
                    SetupParagraph(stringResource(Res.string.playback_mode_escape_hatch))
                }

                SetupStep.PlaybackSetup -> SetupPlaybackSetupBody(
                    variant = playbackSetupVariant(playbackMode.name),
                    languageStrictness = languageStrictness,
                    dynamicRangePolicy = dynamicRangePolicy,
                    qualityCeilingMbps = qualityCeilingMbps,
                )

                // The same card Settings -> Downloads shows, written through the same setter.
                SetupStep.DownloadMode -> {
                    DownloadModeOrder.forEach { mode ->
                        DownloadModeCard(
                            mode = mode,
                            isSelected = mode == downloadMode,
                            onClick = { DownloadPolicyRepository.setMode(mode) },
                        )
                    }
                }

                SetupStep.DownloadSetup -> SetupDownloadSetupBody(
                    variant = downloadSetupVariant,
                    policy = downloadPolicy,
                    mobileDataRule = mobileDataRule,
                    askMobileData = askMobileData,
                    showNotificationNote = showNotificationNote,
                )

                SetupStep.Language -> SetupLanguageBody(
                    preferredAudioLanguage = preferredAudioLanguage,
                    preferredSubtitleLanguage = preferredSubtitleLanguage,
                )

                SetupStep.Sources -> SetupSourcesBody(
                    state = sources,
                    existingSourceName = existingSourceName,
                    actions = sourcesActions,
                )

                SetupStep.SocialOptIn -> {
                    SetupToggleRow(
                        title = stringResource(Res.string.setup_social_toggle),
                        description = stringResource(Res.string.setup_social_toggle_description),
                        checked = socialEnabled,
                        onCheckedChange = onSocialEnabledChange,
                    )
                    // The consequence of the answer, in the words of the answer itself. One
                    // paragraph that changes with the switch says more than two hedging about
                    // what might happen either way.
                    SetupParagraph(
                        stringResource(
                            if (socialEnabled) {
                                Res.string.setup_social_on_body
                            } else {
                                Res.string.setup_social_off_body
                            },
                        ),
                    )
                    // ⚠ Only when the probe genuinely could not answer. Telling somebody we could
                    // not check their account when we did check it is worse than saying nothing.
                    if (socialProbeUnknown) {
                        SetupParagraph(stringResource(Res.string.setup_social_probe_unknown))
                    }
                }

                SetupStep.SocialIdentity -> {
                    if (socialSignedIn) {
                        SetupParagraph(stringResource(Res.string.setup_social_identity_body))
                        SocialIdentityBody(
                            handle = socialHandle,
                            onHandleChange = onSocialHandleChange,
                            message = socialHandleMessage,
                            busy = socialHandleBusy,
                            onSave = onSaveSocialHandle,
                            showHeading = false,
                        )
                    } else {
                        // ⚠ **Explains, never blocks.** The wizard gates the app, and a step the
                        // user cannot leave because they are signed out - or because a server is
                        // down - is the failure the Sources step already refuses to have. Social
                        // stays on; the handle is finished later from Settings, or from the
                        // Social tab needsHandleSetup path, which is untouched by any of this.
                        SetupParagraph(stringResource(Res.string.setup_social_identity_signed_out))
                    }
                }

                SetupStep.Look -> {
                    SetupChoiceGroup(
                        title = stringResource(Res.string.setup_cards_shape),
                        options = listOf(
                            stringResource(Res.string.setup_cards_shape_poster) to false,
                            stringResource(Res.string.setup_cards_shape_landscape) to true,
                        ),
                        selected = landscapeCards,
                        onSelected = PosterCardStyleRepository::setCatalogLandscapeModeEnabled,
                    )
                    SetupChoiceGroup(
                        title = stringResource(Res.string.setup_cards_size),
                        options = listOf(
                            stringResource(Res.string.settings_poster_width_dense) to 112,
                            stringResource(Res.string.settings_poster_width_balanced) to 126,
                            stringResource(Res.string.settings_poster_width_large) to 140,
                        ),
                        selected = posterWidthDp,
                        onSelected = PosterCardStyleRepository::setWidthDp,
                    )
                    // ⚠ Corners and card titles used to be asked here and are now Settings-only.
                    // Naming where they went is the difference between condensing the step and
                    // appearing to have dropped the features.
                    SetupParagraph(stringResource(Res.string.setup_look_more))
                }

                SetupStep.Theme -> {
                    SetupThemeGrid(
                        selected = selectedTheme,
                        onSelected = ThemeSettingsRepository::setTheme,
                    )
                    SetupToggleRow(
                        title = stringResource(Res.string.setup_theme_amoled),
                        description = stringResource(Res.string.setup_theme_amoled_description),
                        checked = amoledEnabled,
                        onCheckedChange = ThemeSettingsRepository::setAmoled,
                    )
                }

                SetupStep.Done -> {
                    SetupSummaryRow(
                        label = stringResource(Res.string.setup_done_playback),
                        value = playbackModeName(playbackMode),
                    )
                    SetupSummaryRow(
                        label = stringResource(Res.string.setup_done_downloads),
                        value = downloadModeName(downloadMode),
                    )
                    SetupSummaryRow(
                        label = stringResource(Res.string.setup_done_social),
                        value = stringResource(
                            if (socialEnabled) {
                                Res.string.setup_done_social_on
                            } else {
                                Res.string.setup_done_social_off
                            },
                        ),
                    )
                    // Only worth a line when there is something to report. A profile that skipped
                    // the Sources step has no answer here, and a row saying nothing is the kind
                    // of filler a three-line summary exists to avoid.
                    if (sources.configuredName != null || existingSourceName != null) {
                        SetupSummaryRow(
                            label = stringResource(Res.string.setup_done_sources),
                            value = sources.configuredName
                                ?: stringResource(Res.string.setup_done_sources_ready),
                        )
                    }
                    SetupParagraph(stringResource(Res.string.setup_done_body))
                }
            }
        }
    }
}

/**
 * The playback configuration that the chosen mode can actually use.
 *
 * ⚠ **Renders from [playbackSetupVariant] rather than deciding for itself what a mode means.**
 * The rule lives in `SetupWizardSteps.kt` because that file is the only part of the wizard a test
 * can reach; a second `when (mode)` here would be a rule nothing checks, and mode-descriptive
 * logic has already drifted once for exactly that reason - see `PlaybackModeCard`.
 *
 * [PlaybackSetupVariant.None] is unreachable: the plan drops the whole step for a mode with
 * nothing to ask. It is handled anyway, so that a variant added without a body renders nothing
 * rather than crashing a screen that gates the app.
 *
 * Every control writes through the same `PlayerSettingsRepository` setter that
 * Settings - Playback - Source preferences uses, and shows the same words for the same values by
 * importing that page own label functions. There is no wizard-only copy of any of it.
 */
/**
 * The one playback question worth asking every mode.
 *
 * ⚠ **Unconditional, where `SetupPlaybackSetupBody` is not.** Everything that step asks feeds the
 * automatic source picker, which Classic does not have. Language feeds that *and* the player's own
 * track selection, which runs in all three modes - so a Classic user who skips the step above still
 * answers this one, and the answer still does something.
 *
 * Revision 7 asked how hard to try for a language it never asked the user to name. The audio
 * preference shipped as the sentinel `device` and the source picker discarded every sentinel, so
 * "Audio language matching" was asked, stored, synced and inert. Naming the language is what makes
 * that row mean anything.
 *
 * Two rows rather than a chip group, because there are seventy-nine languages and a wrapping flow
 * of seventy-nine chips is not a setup step. They open `LanguageSelectionDialog` - the same dialog
 * Settings opens, with the same options and the same labels - so there is no wizard-only copy of
 * the list, which is the rule the rest of this file follows.
 */
@Composable
private fun SetupLanguageBody(
    preferredAudioLanguage: String,
    preferredSubtitleLanguage: String,
) {
    var showAudioDialog by remember { mutableStateOf(false) }
    var showSubtitleDialog by remember { mutableStateOf(false) }

    SetupParagraph(stringResource(Res.string.setup_language_body))

    SetupLanguageRow(
        title = stringResource(Res.string.settings_playback_preferred_audio_language),
        value = when (preferredAudioLanguage) {
            AudioLanguageOption.DEFAULT -> stringResource(Res.string.settings_playback_option_default)
            AudioLanguageOption.DEVICE -> stringResource(Res.string.settings_playback_option_device_language)
            AudioLanguageOption.ORIGINAL -> stringResource(Res.string.settings_playback_option_original)
            else -> languageLabelForCode(preferredAudioLanguage)
        },
        onClick = { showAudioDialog = true },
    )
    SetupLanguageRow(
        title = stringResource(Res.string.settings_playback_preferred_subtitle_language),
        value = when (preferredSubtitleLanguage) {
            SubtitleLanguageOption.NONE -> stringResource(Res.string.settings_playback_option_none)
            SubtitleLanguageOption.DEVICE -> stringResource(Res.string.settings_playback_option_device_language)
            SubtitleLanguageOption.FORCED -> stringResource(Res.string.settings_playback_option_forced)
            else -> languageLabelForCode(preferredSubtitleLanguage)
        },
        onClick = { showSubtitleDialog = true },
    )

    SetupParagraph(stringResource(Res.string.setup_language_more))

    if (showAudioDialog) {
        val originalHint = stringResource(Res.string.settings_playback_option_original_hint)
        LanguageSelectionDialog(
            title = stringResource(Res.string.settings_playback_preferred_audio_language),
            options = listOf(
                LanguageSelectionOption(
                    AudioLanguageOption.DEVICE,
                    stringResource(Res.string.settings_playback_option_device_language),
                ),
                LanguageSelectionOption(
                    AudioLanguageOption.ORIGINAL,
                    stringResource(Res.string.settings_playback_option_original),
                    description = originalHint,
                ),
            ) + AvailableLanguageOptions.map { option ->
                LanguageSelectionOption(option.code, stringResource(option.labelRes))
            },
            selectedValue = preferredAudioLanguage,
            onSelect = { value ->
                PlayerSettingsRepository.setPreferredAudioLanguage(value ?: AudioLanguageOption.DEVICE)
                showAudioDialog = false
            },
            onDismiss = { showAudioDialog = false },
        )
    }

    if (showSubtitleDialog) {
        LanguageSelectionDialog(
            title = stringResource(Res.string.settings_playback_preferred_subtitle_language),
            options = listOf(
                LanguageSelectionOption(
                    SubtitleLanguageOption.NONE,
                    stringResource(Res.string.settings_playback_option_none),
                ),
                LanguageSelectionOption(
                    SubtitleLanguageOption.DEVICE,
                    stringResource(Res.string.settings_playback_option_device_language),
                ),
            ) + AvailableLanguageOptions.map { option ->
                LanguageSelectionOption(option.code, stringResource(option.labelRes))
            },
            selectedValue = preferredSubtitleLanguage,
            onSelect = { value ->
                PlayerSettingsRepository.setPreferredSubtitleLanguage(value ?: SubtitleLanguageOption.NONE)
                showSubtitleDialog = false
            },
            onDismiss = { showSubtitleDialog = false },
        )
    }
}

/** A label and the current answer, sized to its content like [SetupChoiceGroup]'s chips. */
@Composable
internal fun SetupLanguageRow(
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Surface(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
            shape = RoundedCornerShape(12.dp),
            color = tokens.colors.surfaceCard,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textPrimary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun SetupPlaybackSetupBody(
    variant: PlaybackSetupVariant,
    languageStrictness: LanguageStrictness,
    dynamicRangePolicy: DynamicRangePolicy,
    qualityCeilingMbps: Int,
) {
    if (variant == PlaybackSetupVariant.None) return

    SetupParagraph(
        stringResource(
            when (variant) {
                PlaybackSetupVariant.AutomaticBand -> Res.string.setup_playback_setup_body_instant
                else -> Res.string.setup_playback_setup_body_streamlined
            },
        ),
    )

    // ⚠ The ceiling leads, and in Instant that ordering is the whole point: the user never sees a
    // quality list there, so this is the only lever they have over what gets chosen for them.
    SetupChoiceGroup(
        title = stringResource(Res.string.settings_playback_quality_ceiling),
        options = PLAYBACK_QUALITY_CEILING_STEPS.map { playbackQualityCeilingLabel(it) to it },
        // A stored ceiling need not be one of the five steps: it can arrive from a build with a
        // different ladder, or from the quality sheet cycling row. Snap to the nearest rather
        // than drawing a group with nothing selected.
        selected = PLAYBACK_QUALITY_CEILING_STEPS.minByOrNull { step ->
            abs(step - qualityCeilingMbps)
        } ?: 0,
        onSelected = PlayerSettingsRepository::setPlaybackQualityCeilingMbps,
    )
    SetupChoiceGroup(
        title = stringResource(Res.string.settings_playback_language_strictness),
        options = LanguageStrictness.entries.map { playbackLanguageStrictnessLabel(it) to it },
        selected = languageStrictness,
        onSelected = PlayerSettingsRepository::setPlaybackLanguageStrictness,
    )
    SetupChoiceGroup(
        title = stringResource(Res.string.settings_playback_dynamic_range),
        options = DynamicRangePolicy.entries.map { playbackDynamicRangeLabel(it) to it },
        selected = dynamicRangePolicy,
        onSelected = PlayerSettingsRepository::setPlaybackDynamicRangePolicy,
    )
    SetupParagraph(stringResource(Res.string.setup_playback_setup_more))
}

/**
 * The download preferences the chosen Download Mode uses (Phase 9), from [downloadSetupVariant].
 *
 * At most four controls - the plan's limit, so the step does not scroll on a phone: Automatic asks
 * resolution, size level and fallback; Assisted only the size level (the user picks the
 * resolution per download); Manual nothing. Phones add the device's mobile-data rule, and a
 * [SetupWizardRun.Device] run is that question alone. Every control writes through the setter
 * Settings -> Downloads uses, with the same labels from `DownloadModeUi.kt`.
 */
@Composable
private fun SetupDownloadSetupBody(
    variant: DownloadSetupVariant,
    policy: DownloadPolicy,
    mobileDataRule: DownloadMobileDataRule,
    askMobileData: Boolean,
    showNotificationNote: Boolean,
) {
    if (variant == DownloadSetupVariant.None) return
    if (variant == DownloadSetupVariant.Automatic) {
        SetupChoiceGroup(
            title = stringResource(Res.string.download_pref_resolution),
            options = DownloadResolutionPreference.entries.map { downloadResolutionLabel(it) to it },
            selected = policy.preferredResolution,
            onSelected = { value -> DownloadPolicyRepository.update { it.copy(preferredResolution = value) } },
        )
    }
    if (variant == DownloadSetupVariant.Automatic || variant == DownloadSetupVariant.Assisted) {
        SetupChoiceGroup(
            title = stringResource(Res.string.download_pref_size_level),
            options = DownloadSizeLevel.entries.map { downloadSizeLevelLabel(it) to it },
            selected = policy.sizeLevel,
            onSelected = { value -> DownloadPolicyRepository.update { it.copy(sizeLevel = value) } },
            // Every level shows its number, so none has to be tapped to be read.
            sublabels = DownloadSizeLevel.entries.associateWith { downloadSizeLevelFigures(it).joinToString("\n") },
        )
        SetupParagraph(downloadSizeLevelDetail(policy.sizeLevel, policy.preferredResolution))
    }
    if (variant == DownloadSetupVariant.Automatic) {
        SetupChoiceGroup(
            title = stringResource(Res.string.download_pref_fallback),
            options = DownloadResolutionFallback.entries.map { downloadFallbackLabel(it) to it },
            selected = policy.resolutionFallback,
            onSelected = { value -> DownloadPolicyRepository.update { it.copy(resolutionFallback = value) } },
        )
    }
    if (askMobileData || variant == DownloadSetupVariant.DeviceOnly) {
        SetupChoiceGroup(
            title = stringResource(Res.string.download_pref_mobile_data),
            options = DownloadMobileDataRule.entries.map { downloadMobileDataLabel(it) to it },
            selected = mobileDataRule,
            onSelected = { value -> DownloadsRepository.updateDeviceSettings { it.copy(mobileData = value) } },
        )
        if (showNotificationNote) {
            SetupParagraph(stringResource(Res.string.download_setup_notifications))
        }
    }
}

/**
 * One line of the closing summary.
 *
 * A label and a value, and nothing else. The finish screen is three facts and a button; anything
 * more decorative here turns it back into the success page revision 7 exists to remove.
 */
@Composable
private fun SetupSummaryRow(label: String, value: String) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// --- the things that can fail --------------------------------------------------------------

/**
 * Saves the handle through the same call the Social tab uses.
 *
 * ⚠ **The result is read.** `SocialScreen` discarded it once and a handle that never saved looked
 * exactly like one that had - which is how an empty database went unnoticed while the screen
 * appeared to work. Server-side uniqueness lives in `social_upsert_profile`, so its rejection is
 * what the user is shown, verbatim.
 *
 * Like the addon install, this never blocks the step: a failure leaves a message and the user can
 * still press Next. The wizard gates the app.
 */
private fun saveSocialHandle(
    scope: CoroutineScope,
    profileId: String?,
    handle: String,
    setBusy: (Boolean) -> Unit,
    onFailed: (message: String) -> Unit,
) {
    scope.launch {
        setBusy(true)
        // ⚠ The profile is passed explicitly: the social layer is not activated until the app shell
        // runs, which is after the wizard. Success drops this step from the plan, which advances.
        SocialRepository.setupHandle(handle, profileId)
            .onSuccess { SocialFeaturePreferencesRepository.recordKnownIdentity() }
            .onFailure { error -> onFailed(error.message ?: "Could not save that handle") }
        setBusy(false)
    }
}

// --- small shared pieces -----------------------------------------------------------------

@Composable
internal fun SetupParagraph(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.nuvio.colors.textSecondary,
    )
}

/**
 * A labelled row of mutually exclusive chips.
 *
 * Not `NuvioSurfaceCard`-based: that takes its colour from `colors.surface`, which is exactly
 * what the panel is painted with, so a card here would be invisible - the trap the quality
 * sheet hit. These use an `overlayHover` lift instead, which is what that sheet settled on.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> SetupChoiceGroup(
    title: String,
    options: List<Pair<String, T>>,
    selected: T,
    onSelected: (T) -> Unit,
    /** A second, quieter line inside the chip - a size level's GB/h. Absent for most groups. */
    sublabels: Map<T, String> = emptyMap(),
) {
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        // ⚠ **A wrapping flow of content-width chips, not a row of equal-weight ones, and the
        // render harness is what settled it.** Equal weights plus `maxLines = 1` are fine for two
        // or three short words and silently destroy anything longer: revision 7's playback step
        // drew "Only play what I can watch" as "Only play what I", and rendered *Prefer SDR*,
        // *Prefer HDR*, *Require HDR* and *Require Dolby Vision* as "Prefer", "Prefer", "Require"
        // and "Require" - four chips, two visible labels, and no way to tell them apart.
        //
        // Sizing to the label instead means the option decides the chip rather than the chip
        // truncating the option, and a group that does not fit wraps onto a second line instead
        // of squeezing. That is also what lets the same component carry a two-option group and a
        // five-option one without either being tuned by hand.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (label, value) ->
                val isSelected = value == selected
                val sublabel = sublabels[value]
                if (sublabel != null) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(if (isSelected) tokens.colors.accent else tokens.colors.overlayHover)
                            .clickable { onSelected(value) }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) tokens.colors.onAccent else tokens.colors.textPrimary,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                        Text(
                            text = sublabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) tokens.colors.onAccent.copy(alpha = 0.85f) else tokens.colors.textMuted,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                        )
                    }
                    return@forEach
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) tokens.colors.onAccent else tokens.colors.textSecondary,
                    // ⚠ `textAlign` is load-bearing. Without it the label sits hard left inside
                    // its pill, which shipped in every build from revision 2 to revision 3
                    // before anyone named it. Still true now that the pill hugs the label: a
                    // chip that wraps to two lines centres them.
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (isSelected) tokens.colors.accent else tokens.colors.overlayHover)
                        .clickable { onSelected(value) }
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                )
            }
        }
    }
}

@Composable
private fun SetupToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.Medium,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = tokens.colors.onAccent,
                checkedTrackColor = tokens.colors.accent,
                uncheckedThumbColor = tokens.colors.textSecondary,
                uncheckedTrackColor = tokens.colors.overlayHover,
            ),
        )
    }
}

/**
 * The palettes this account can use, as colour swatches.
 *
 * ⚠ **The same list Settings offers, from [availableAppThemes].** Listing every [AppTheme] showed
 * supporter-only palettes (Gold, Jade, Rose Gold, Arctic Blue, Graphite) that
 * `ThemeSettingsRepository.setTheme` silently refuses without the entitlement, so tapping them did
 * nothing. Custom opens the same editor Settings does rather than applying unseen colours.
 *
 * The swatch is the palette's own accent read straight from [ThemeColors], so it cannot drift
 * from what tapping it produces - and tapping it recolours the whole wizard, band included,
 * because the wizard lives inside the app's real `NuvioTheme`.
 */
@Composable
private fun SetupThemeGrid(
    selected: AppTheme,
    onSelected: (AppTheme) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val memberAccess by remember {
        MemberAccessRepository.ensureStarted()
        MemberAccessRepository.access
    }.collectAsStateWithLifecycle()
    val customColors by ThemeSettingsRepository.customThemeColors.collectAsStateWithLifecycle()
    var showCustomEditor by remember { mutableStateOf(false) }
    val themes = availableAppThemes(memberAccess.entitlements)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        themes.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { theme ->
                    val isSelected = theme == selected
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(tokens.colors.overlayHover)
                            .border(
                                width = if (isSelected) 1.5.dp else tokens.borders.hairline,
                                color = if (isSelected) tokens.colors.accent else tokens.colors.borderSubtle,
                                shape = RoundedCornerShape(12.dp),
                            )
                            .clickable {
                                if (theme == AppTheme.CUSTOM) showCustomEditor = true else onSelected(theme)
                            }
                            .padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(ThemeColors.getColorPalette(theme, customColors).secondary),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = tokens.colors.onAccent,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                        Text(
                            text = stringResource(theme.labelRes),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isSelected) tokens.colors.textPrimary else tokens.colors.textMuted,
                            maxLines = 1,
                        )
                    }
                }
                // A short last row keeps its chips the same width as a full row's instead of
                // stretching them.
                repeat(4 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
    if (showCustomEditor) {
        CustomThemeEditor(
            initialColors = customColors,
            allowGradient = memberAccess.tier != null,
            onSave = ThemeSettingsRepository::setCustomTheme,
            onDismiss = { showCustomEditor = false },
        )
    }
}

// --- copy ---------------------------------------------------------------------------------

private val SetupStep.titleRes
    get() = when (this) {
        SetupStep.Welcome -> Res.string.setup_welcome_title
        SetupStep.PlaybackMode -> Res.string.playback_mode_selector_title
        SetupStep.PlaybackSetup -> Res.string.setup_playback_setup_title
        SetupStep.DownloadMode -> Res.string.download_mode_title
        SetupStep.DownloadSetup -> Res.string.download_setup_title
        SetupStep.Language -> Res.string.setup_language_title
        SetupStep.Sources -> Res.string.setup_sources_title
        SetupStep.SocialOptIn -> Res.string.setup_social_title
        SetupStep.SocialIdentity -> Res.string.setup_social_identity_title
        SetupStep.Look -> Res.string.setup_look_title
        SetupStep.Theme -> Res.string.setup_theme_title
        SetupStep.Done -> Res.string.setup_done_title
    }

/**
 * The subtitle, which for one step depends on the answer to the step before it.
 *
 * ⚠ [SetupStep.PlaybackSetup] asks the same three questions in Streamlined and Instant but for
 * opposite reasons - in one the user is narrowing what they will be offered, in the other they are
 * bounding what Nuvio will choose without asking. Same controls, same setters, different promise,
 * so the header has to say which one this is. It reads the variant rather than the mode for the
 * same reason the body does.
 */
@Composable
private fun setupStepSubtitle(step: SetupStep, plan: SetupWizardPlan, playbackMode: PlaybackMode): String = when (step) {
    SetupStep.PlaybackSetup -> stringResource(
        when (playbackSetupVariant(playbackMode.name)) {
            PlaybackSetupVariant.AutomaticBand -> Res.string.setup_playback_setup_subtitle_instant
            else -> Res.string.setup_playback_setup_subtitle_streamlined
        },
    )
    // An upgrade opens on this step with no Welcome in front of it, so it says why it is here.
    SetupStep.DownloadMode -> stringResource(
        if (plan.run == SetupWizardRun.Upgrade) Res.string.download_mode_upgrade_subtitle else Res.string.download_mode_subtitle,
    )
    SetupStep.DownloadSetup -> stringResource(
        when (downloadSetupVariant(plan)) {
            DownloadSetupVariant.Automatic -> Res.string.download_setup_subtitle_automatic
            DownloadSetupVariant.Assisted -> Res.string.download_setup_subtitle_assisted
            else -> Res.string.download_setup_subtitle_device
        },
    )
    else -> stringResource(step.subtitleRes)
}

private val SetupStep.subtitleRes
    get() = when (this) {
        SetupStep.Welcome -> Res.string.setup_welcome_subtitle
        SetupStep.PlaybackMode -> Res.string.playback_mode_selector_subtitle
        // Never read - `setupStepSubtitle` answers for this step - but enumerated so that a new
        // step is a compile error here rather than a header with no subtitle.
        SetupStep.PlaybackSetup -> Res.string.setup_playback_setup_subtitle_streamlined
        SetupStep.DownloadMode -> Res.string.download_mode_subtitle
        SetupStep.DownloadSetup -> Res.string.download_setup_subtitle_device
        SetupStep.Language -> Res.string.setup_language_subtitle
        SetupStep.Sources -> Res.string.setup_sources_subtitle
        SetupStep.SocialOptIn -> Res.string.setup_social_subtitle
        SetupStep.SocialIdentity -> Res.string.setup_social_identity_subtitle
        SetupStep.Look -> Res.string.setup_look_subtitle
        SetupStep.Theme -> Res.string.setup_theme_subtitle
        SetupStep.Done -> Res.string.setup_done_subtitle
    }
