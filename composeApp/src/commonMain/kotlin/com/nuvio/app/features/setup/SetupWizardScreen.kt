package com.nuvio.app.features.setup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.PosterCardStyleRepository
import com.nuvio.app.core.ui.ThemeColors
import com.nuvio.app.core.ui.labelRes
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.addons.AddAddonResult
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.details.MetaEpisodeCardStyle
import com.nuvio.app.features.details.MetaScreenBackgroundMode
import com.nuvio.app.features.details.MetaScreenSettingsRepository
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.playback.PlaybackMode
import com.nuvio.app.features.playback.PlaybackModeCard
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.settings.ThemeSettingsRepository
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.trakt.TraktConnectionMode
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

// String keys are imported by wildcard, which is the style HomeScreen.kt, MetaDetailsScreen.kt
// and DetailHero.kt already use. Deliberate: this screen reads about sixty keys, and the
// explicit-import style that PlaybackQualitySheet.kt follows is what broke compileAndroidMain
// in 0.4.13-beta when one of them was added to strings.xml and not to the import list.

/** The two windows the stage can show; only some steps let the user switch between them. */
private val allPreviewSurfaces = listOf(SetupPreviewSurface.Home, SetupPreviewSurface.Details)

/**
 * The first-launch setup wizard.
 *
 * Replaces the standalone playback-mode selector, which was the app's entire onboarding: one
 * question, answered before the user had seen anything the answer applied to. This keeps that
 * question - it is still the first thing asked, and still uses the same `PlaybackModeCard`, so
 * its copy cannot drift from the settings dialog - and puts the visual choices behind it,
 * every one of them against a live preview.
 *
 * ⚠ **Every choice is written the moment it is tapped**, through the same repository setter
 * the settings page uses. That is what lets [SetupPreviewStage] render the real composables
 * reading the real state instead of a parallel mock. It also means there is no undo, which
 * matches how every settings page in this app already behaves - and why the exit on the first
 * step is called "Skip for now" rather than "use defaults": nothing has been written yet, so
 * there is nothing to restore.
 *
 * @param dismissible true when opened from Settings rather than gating the app. A dismissible
 *   run gets a close affordance and [onDismiss]; a gating run has no way out but through, or
 *   through the skip on the first step.
 */
@Composable
fun SetupWizardScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    dismissible: Boolean = false,
    onDismiss: () -> Unit = {},
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

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
    val trakt by remember {
        TraktAuthRepository.ensureLoaded()
        TraktAuthRepository.uiState
    }.collectAsStateWithLifecycle()

    // Saved by name, not by ordinal: an enum reordered in a later release must not resume a
    // process-death-restored wizard on a different step than the user left it on.
    var stepName by rememberSaveable { mutableStateOf(SetupStep.Welcome.name) }
    var pathName by rememberSaveable { mutableStateOf(SetupWizardPath.Undecided.name) }
    var surfaceName by rememberSaveable { mutableStateOf(SetupPreviewSurface.Home.name) }
    val step = remember(stepName) {
        SetupStep.entries.firstOrNull { it.name == stepName } ?: SetupStep.Welcome
    }
    val path = remember(pathName) {
        SetupWizardPath.entries.firstOrNull { it.name == pathName } ?: SetupWizardPath.Undecided
    }
    val previewSurface = remember(surfaceName) {
        SetupPreviewSurface.entries.firstOrNull { it.name == surfaceName } ?: SetupPreviewSurface.Home
    }

    // The optional steps are dropped rather than shown-and-skipped when they have nothing to
    // offer. `enabled` rather than merely present: an installed-but-disabled addon is not a
    // source, so a profile carrying only those still gets asked.
    val plan = SetupWizardPlan(
        path = path,
        offerSources = addons.addons.none { it.enabled },
        offerTrakt = trakt.mode != TraktConnectionMode.CONNECTED,
    )

    var addonUrl by rememberSaveable { mutableStateOf("") }
    var addonBusy by remember { mutableStateOf(false) }
    // Two states rather than a message plus a boolean: the error text comes from the addon or
    // the manifest fetch and is shown verbatim, while the success line is a formatted string
    // resource and can only be built in composition. Collapsing them would mean resolving a
    // string resource outside it.
    var addonError by remember { mutableStateOf<String?>(null) }
    var addonInstalledName by remember { mutableStateOf<String?>(null) }

    val emptyUrlMessage = stringResource(Res.string.addons_error_enter_url)
    val browserFailedMessage = stringResource(Res.string.settings_trakt_failed_open_browser)

    fun complete() {
        // Both, always. `playback_mode_selector_seen` is no longer read by any gate, but it is
        // still what `PlaybackModeDialog` treats as "answered" and it still syncs, so leaving
        // it false would re-prompt anyone who downgrades to 0.4.x.
        PlayerSettingsRepository.markPlaybackModeSelectorSeen()
        PlayerSettingsRepository.markSetupWizardCompleted(SETUP_WIZARD_REVISION)
        onFinished()
    }

    fun goTo(next: SetupStep?) {
        if (next == null) {
            complete()
            return
        }
        stepName = next.name
        // The stage follows the step unless the step is one that lets the user choose.
        when (next) {
            SetupStep.DetailsScreen -> surfaceName = SetupPreviewSurface.Details.name
            SetupStep.Cards, SetupStep.HomeScreen -> surfaceName = SetupPreviewSurface.Home.name
            else -> Unit
        }
    }

    fun advance() {
        if (isFinalSetupStep(step, plan)) complete() else goTo(nextSetupStep(step, plan))
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.colors.background),
    ) {
        val isWide = maxWidth >= 768.dp
        val insets = WindowInsets.safeDrawing.asPaddingValues()
        val stageSurface = if (step.allowsSurfaceChoice) previewSurface else step.previewSurface

        // Declared once and called from both layout branches. The wide and narrow arms differ
        // only in where the stage sits relative to the controls; duplicating this twenty-argument
        // call to say that was how the two arms would silently drift apart.
        val stepBody: @Composable (Modifier) -> Unit = { bodyModifier ->
            SetupStepBody(
                step = step,
                modifier = bodyModifier,
                playbackMode = playerSettings.playbackMode,
                posterWidthDp = posterStyle.widthDp,
                posterCornerRadiusDp = posterStyle.cornerRadiusDp,
                landscapeCards = posterStyle.catalogLandscapeModeEnabled,
                hideLabels = posterStyle.hideLabelsEnabled,
                heroEnabled = homeSettings.heroEnabled,
                continueWatchingStyle = continueWatching.style,
                backgroundMode = metaSettings.backgroundMode,
                episodeCardStyle = metaSettings.episodeCardStyle,
                tabLayout = metaSettings.tabLayout,
                selectedTheme = selectedTheme,
                amoledEnabled = amoledEnabled,
                activePreset = matchingSetupPreset(
                    currentSetupValues(
                        poster = posterStyle,
                        heroEnabled = homeSettings.heroEnabled,
                        continueWatching = continueWatching,
                        meta = metaSettings,
                    ),
                ),
                addonUrl = addonUrl,
                addonBusy = addonBusy,
                addonError = addonError,
                addonInstalledName = addonInstalledName,
                traktMode = trakt.mode,
                traktUsername = trakt.username,
                onAddonUrlChange = {
                    addonUrl = it
                    addonError = null
                    addonInstalledName = null
                },
                onInstallAddon = {
                    installAddon(
                        scope = scope,
                        rawUrl = addonUrl,
                        emptyUrlMessage = emptyUrlMessage,
                        setBusy = { addonBusy = it },
                        onInstalled = { name ->
                            addonUrl = ""
                            addonError = null
                            addonInstalledName = name
                        },
                        onFailed = { message ->
                            addonInstalledName = null
                            addonError = message
                        },
                    )
                },
                onConnectTrakt = { connectTrakt(uriHandler::openUri, browserFailedMessage) },
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = insets.calculateTopPadding(), bottom = insets.calculateBottomPadding()),
        ) {
            SetupHeader(
                step = step,
                plan = plan,
                dismissible = dismissible,
                onDismiss = onDismiss,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (isWide) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 1180.dp)
                            .align(Alignment.Center)
                            .padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                    ) {
                        if (stageSurface != null) {
                            SetupStageColumn(
                                surface = stageSurface,
                                allowChoice = step.allowsSurfaceChoice,
                                onSurfaceSelected = { surfaceName = it.name },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                        stepBody(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp),
                    ) {
                        if (stageSurface != null) {
                            SetupStageColumn(
                                surface = stageSurface,
                                allowChoice = step.allowsSurfaceChoice,
                                onSurfaceSelected = { surfaceName = it.name },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height((maxHeight * 0.40f).coerceIn(220.dp, 420.dp)),
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                        stepBody(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }
            SetupFooter(
                step = step,
                plan = plan,
                onBack = { goTo(previousSetupStep(step, plan)) },
                onSkipAll = ::complete,
                onAdvance = ::advance,
                onChooseQuick = {
                    pathName = SetupWizardPath.Quick.name
                    goTo(nextSetupStep(step, plan.copy(path = SetupWizardPath.Quick)))
                },
                onChooseFull = {
                    pathName = SetupWizardPath.Full.name
                    goTo(nextSetupStep(step, plan.copy(path = SetupWizardPath.Full)))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            )
        }
    }
}

/** Which window the stage shows for a step, or null when the step has no preview. */
private val SetupStep.previewSurface: SetupPreviewSurface?
    get() = when (this) {
        SetupStep.Look, SetupStep.Cards, SetupStep.HomeScreen, SetupStep.Theme ->
            SetupPreviewSurface.Home

        SetupStep.DetailsScreen -> SetupPreviewSurface.Details

        // Welcome, PlaybackMode, Sources, Trakt and Done change nothing the stage can show.
        // A preview that never moves while the user works is worse than no preview: it reads
        // as a stage that has stopped responding.
        else -> null
    }

/**
 * Whether the user may switch the stage between Home and Details on this step.
 *
 * True only where the step's choices affect *both* windows. Look and Theme do - a preset sets
 * the details background as well as the card shape, and the palette recolours everything - so
 * pinning either of them to one window would hide half of what the choice did.
 */
private val SetupStep.allowsSurfaceChoice: Boolean
    get() = this == SetupStep.Look || this == SetupStep.Theme

@Composable
private fun SetupHeader(
    step: SetupStep,
    plan: SetupWizardPlan,
    dismissible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val position = setupStepPosition(step, plan)
    val total = setupWizardSteps(plan).size

    Row(
        modifier = modifier.fillMaxWidth(),
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
                text = stringResource(step.subtitleRes),
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
private fun SetupStageColumn(
    surface: SetupPreviewSurface,
    allowChoice: Boolean,
    onSurfaceSelected: (SetupPreviewSurface) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SetupPreviewStage(
            surface = surface,
            catalogRowTitle = stringResource(Res.string.setup_preview_row_title),
            continueWatchingTitle = stringResource(Res.string.setup_preview_continue_watching),
            episodesSectionTitle = stringResource(Res.string.setup_preview_episodes),
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        if (allowChoice) {
            SetupSurfacePicker(selected = surface, onSelected = onSurfaceSelected)
        }
    }
}

@Composable
private fun SetupSurfacePicker(
    selected: SetupPreviewSurface,
    onSelected: (SetupPreviewSurface) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tokens.colors.overlayHover)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        allPreviewSurfaces.forEach { candidate ->
            val isSelected = candidate == selected
            Text(
                text = stringResource(
                    when (candidate) {
                        SetupPreviewSurface.Home -> Res.string.setup_preview_home
                        SetupPreviewSurface.Details -> Res.string.setup_preview_details
                    },
                ),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) tokens.colors.onAccent else tokens.colors.textSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (isSelected) tokens.colors.accent else Color.Transparent)
                    .clickable { onSelected(candidate) }
                    .padding(horizontal = 16.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun SetupFooter(
    step: SetupStep,
    plan: SetupWizardPlan,
    onBack: () -> Unit,
    onSkipAll: () -> Unit,
    onAdvance: () -> Unit,
    onChooseQuick: () -> Unit,
    onChooseFull: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canGoBack = previousSetupStep(step, plan) != null

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (step) {
            SetupStep.Welcome -> {
                Button(onClick = onAdvance, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(Res.string.setup_welcome_start))
                }
                TextButton(onClick = onSkipAll, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(Res.string.setup_welcome_skip))
                }
            }

            // The fork. Two buttons rather than a Next, because "this look is fine" and "show
            // me the rest" are different intents and a preset-first flow that funnels both
            // into the same long questionnaire is just a decorative first step.
            SetupStep.Look -> {
                Button(onClick = onChooseQuick, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(Res.string.setup_look_use))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canGoBack) {
                        TextButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                            Text(text = stringResource(Res.string.setup_back))
                        }
                    }
                    TextButton(onClick = onChooseFull, modifier = Modifier.weight(1f)) {
                        Text(text = stringResource(Res.string.setup_look_customise))
                    }
                }
            }

            else -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canGoBack) {
                    TextButton(onClick = onBack) {
                        Text(text = stringResource(Res.string.setup_back))
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = onAdvance) {
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
        }
    }
}

@Composable
private fun SetupStepBody(
    step: SetupStep,
    modifier: Modifier,
    playbackMode: PlaybackMode,
    posterWidthDp: Int,
    posterCornerRadiusDp: Int,
    landscapeCards: Boolean,
    hideLabels: Boolean,
    heroEnabled: Boolean,
    continueWatchingStyle: ContinueWatchingSectionStyle,
    backgroundMode: MetaScreenBackgroundMode,
    episodeCardStyle: MetaEpisodeCardStyle,
    tabLayout: Boolean,
    selectedTheme: AppTheme,
    amoledEnabled: Boolean,
    activePreset: SetupPreset?,
    addonUrl: String,
    addonBusy: Boolean,
    addonError: String?,
    addonInstalledName: String?,
    traktMode: TraktConnectionMode,
    traktUsername: String?,
    onAddonUrlChange: (String) -> Unit,
    onInstallAddon: () -> Unit,
    onConnectTrakt: () -> Unit,
) {
    AnimatedContent(
        targetState = step,
        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) },
        label = "setup_step_body",
        modifier = modifier,
    ) { current ->
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (current) {
                SetupStep.Welcome -> SetupParagraph(stringResource(Res.string.setup_welcome_body))

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

                SetupStep.Look -> SetupPreset.entries.forEach { preset ->
                    SetupOptionCard(
                        title = stringResource(preset.titleRes),
                        description = stringResource(preset.descriptionRes),
                        isSelected = preset == activePreset,
                        onClick = { applySetupPreset(preset.values) },
                    )
                }

                SetupStep.Cards -> {
                    SetupChoiceGroup(
                        title = stringResource(Res.string.setup_cards_shape),
                        options = listOf(
                            stringResource(Res.string.setup_cards_shape_poster) to false,
                            stringResource(Res.string.setup_cards_shape_landscape) to true,
                        ),
                        selected = landscapeCards,
                        onSelected = PosterCardStyleRepository::setCatalogLandscapeModeEnabled,
                    )
                    SetupToggleRow(
                        title = stringResource(Res.string.setup_cards_labels),
                        checked = !hideLabels,
                        onCheckedChange = { PosterCardStyleRepository.setHideLabelsEnabled(!it) },
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
                    SetupChoiceGroup(
                        title = stringResource(Res.string.setup_cards_corners),
                        options = listOf(
                            stringResource(Res.string.settings_poster_radius_sharp) to 0,
                            stringResource(Res.string.settings_poster_radius_classic) to 8,
                            stringResource(Res.string.settings_poster_radius_pill) to 16,
                        ),
                        selected = posterCornerRadiusDp,
                        onSelected = PosterCardStyleRepository::setCornerRadiusDp,
                    )
                }

                SetupStep.HomeScreen -> {
                    SetupToggleRow(
                        title = stringResource(Res.string.setup_home_hero),
                        description = stringResource(Res.string.setup_home_hero_description),
                        checked = heroEnabled,
                        onCheckedChange = HomeCatalogSettingsRepository::setHeroEnabled,
                    )
                    SetupChoiceGroup(
                        title = stringResource(Res.string.setup_home_continue),
                        options = listOf(
                            stringResource(Res.string.setup_home_cw_card) to ContinueWatchingSectionStyle.Card,
                            stringResource(Res.string.setup_home_cw_wide) to ContinueWatchingSectionStyle.Wide,
                            stringResource(Res.string.setup_home_cw_poster) to ContinueWatchingSectionStyle.Poster,
                        ),
                        selected = continueWatchingStyle,
                        onSelected = ContinueWatchingPreferencesRepository::setStyle,
                    )
                }

                SetupStep.DetailsScreen -> {
                    SetupChoiceGroup(
                        title = stringResource(Res.string.setup_details_background),
                        options = listOf(
                            stringResource(Res.string.setup_details_background_normal) to MetaScreenBackgroundMode.Normal,
                            stringResource(Res.string.setup_details_background_cinematic) to MetaScreenBackgroundMode.Cinematic,
                            stringResource(Res.string.setup_details_background_dominant) to MetaScreenBackgroundMode.DominantColor,
                        ),
                        selected = backgroundMode,
                        onSelected = MetaScreenSettingsRepository::setBackgroundMode,
                    )
                    SetupChoiceGroup(
                        title = stringResource(Res.string.setup_details_episodes),
                        options = listOf(
                            stringResource(Res.string.setup_details_episodes_horizontal) to MetaEpisodeCardStyle.Horizontal,
                            stringResource(Res.string.setup_details_episodes_list) to MetaEpisodeCardStyle.List,
                        ),
                        selected = episodeCardStyle,
                        onSelected = MetaScreenSettingsRepository::setEpisodeCardStyle,
                    )
                    SetupToggleRow(
                        title = stringResource(Res.string.setup_details_tabs),
                        description = stringResource(Res.string.setup_details_tabs_description),
                        checked = tabLayout,
                        onCheckedChange = MetaScreenSettingsRepository::setTabLayout,
                    )
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

                SetupStep.Sources -> SetupSourcesBody(
                    addonUrl = addonUrl,
                    busy = addonBusy,
                    error = addonError,
                    installedName = addonInstalledName,
                    onAddonUrlChange = onAddonUrlChange,
                    onInstall = onInstallAddon,
                )

                SetupStep.Trakt -> SetupTraktBody(
                    mode = traktMode,
                    username = traktUsername,
                    onConnect = onConnectTrakt,
                )

                SetupStep.Done -> SetupParagraph(stringResource(Res.string.setup_done_body))
            }
        }
    }
}

@Composable
private fun SetupSourcesBody(
    addonUrl: String,
    busy: Boolean,
    error: String?,
    installedName: String?,
    onAddonUrlChange: (String) -> Unit,
    onInstall: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    SetupParagraph(stringResource(Res.string.setup_sources_body))
    NuvioInputField(
        value = addonUrl,
        onValueChange = onAddonUrlChange,
        placeholder = stringResource(Res.string.setup_sources_url_placeholder),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = onInstall,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (busy) {
            NuvioLoadingIndicator(
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Text(text = stringResource(Res.string.setup_sources_install))
        }
    }
    if (error != null) {
        Text(
            text = error,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.danger,
        )
    } else if (installedName != null) {
        Text(
            text = stringResource(Res.string.setup_sources_installed, installedName),
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.success,
        )
    }
    // Debrid is named rather than offered. The wizard gates the app, so it has no nav
    // controller and cannot reach the settings page - and a button that goes nowhere is
    // worse than a sentence that says where to look.
    SetupParagraph(stringResource(Res.string.setup_sources_debrid_hint))
}

@Composable
private fun SetupTraktBody(
    mode: TraktConnectionMode,
    username: String?,
    onConnect: () -> Unit,
) {
    SetupParagraph(stringResource(Res.string.setup_trakt_body))
    when (mode) {
        TraktConnectionMode.CONNECTED -> Text(
            text = stringResource(Res.string.setup_trakt_connected, username.orEmpty()),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.nuvio.colors.success,
        )

        TraktConnectionMode.AWAITING_APPROVAL -> {
            Text(
                text = stringResource(Res.string.setup_trakt_waiting),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.nuvio.colors.textSecondary,
            )
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(Res.string.setup_trakt_connect))
            }
        }

        TraktConnectionMode.DISCONNECTED -> Button(
            onClick = onConnect,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(Res.string.setup_trakt_connect))
        }
    }
}

// --- the two things that can fail ---------------------------------------------------------

/**
 * Installs an addon from a pasted manifest URL.
 *
 * Reuses `AddonRepository.addAddon`, which is what `AddonsScreen`'s own `AddAddonCard` calls,
 * so URL normalisation, the manifest fetch and the duplicate check behave identically here -
 * including the errors, which are the point. A first-time user pasting a URL that does not
 * work needs to be told what went wrong, not returned to a blank field.
 *
 * The wizard never blocks on this. The step is skippable whether it succeeds or not: an app
 * with no sources is a bad first experience, but a setup flow the user cannot leave because a
 * server is down is a worse one.
 */
private fun installAddon(
    scope: CoroutineScope,
    rawUrl: String,
    emptyUrlMessage: String,
    setBusy: (Boolean) -> Unit,
    onInstalled: (name: String) -> Unit,
    onFailed: (message: String) -> Unit,
) {
    if (rawUrl.isBlank()) {
        onFailed(emptyUrlMessage)
        return
    }
    scope.launch {
        setBusy(true)
        val result = AddonRepository.addAddon(rawUrl)
        setBusy(false)
        when (result) {
            is AddAddonResult.Success -> onInstalled(result.manifest.name)
            is AddAddonResult.Error -> onFailed(result.message)
        }
    }
}

/**
 * Starts, or resumes, the Trakt authorisation.
 *
 * `pendingAuthorizationUrl` first, exactly as `TraktSettingsPage` does: a user who tapped
 * connect, lost the browser and came back must land on the authorisation already in flight
 * rather than start a second one.
 */
private fun connectTrakt(openUri: (String) -> Unit, browserFailedMessage: String) {
    val authUrl = TraktAuthRepository.pendingAuthorizationUrl()
        ?: TraktAuthRepository.onConnectRequested()
        ?: return
    runCatching { openUri(authUrl) }
        .onFailure { TraktAuthRepository.onAuthLaunchFailed(it.message ?: browserFailedMessage) }
}

// --- small shared pieces -----------------------------------------------------------------

@Composable
private fun SetupParagraph(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.nuvio.colors.textSecondary,
    )
}

/**
 * A selectable card.
 *
 * ⚠ **Not `NuvioSurfaceCard`.** That takes its colour from `colors.surface`, and the wizard's
 * background is `colors.background` with `surfaceSheet == surface` in the token set - the same
 * trap that kept `NuvioSurfaceCard` off the quality sheet. This uses the fix that sheet
 * settled on: an `overlayHover` lift plus a `borderSubtle` hairline.
 */
@Composable
private fun SetupOptionCard(
    title: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (isSelected) tokens.colors.focusBackground else tokens.colors.overlayHover)
            .border(
                width = if (isSelected) 1.5.dp else tokens.borders.hairline,
                color = if (isSelected) tokens.colors.accent else tokens.colors.borderSubtle,
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textSecondary,
            )
        }
        if (isSelected) {
            Box(
                modifier = Modifier.size(22.dp).clip(CircleShape).background(tokens.colors.accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = tokens.colors.onAccent,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/** A labelled row of mutually exclusive chips. */
@Composable
private fun <T> SetupChoiceGroup(
    title: String,
    options: List<Pair<String, T>>,
    selected: T,
    onSelected: (T) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (label, value) ->
                val isSelected = value == selected
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) tokens.colors.onAccent else tokens.colors.textSecondary,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (isSelected) tokens.colors.accent else tokens.colors.overlayHover)
                        .clickable { onSelected(value) }
                        .padding(vertical = 10.dp),
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
 * The seven palettes as colour swatches.
 *
 * The swatch is the palette's own accent read straight from [ThemeColors], so it cannot drift
 * from what tapping it produces - and tapping it recolours the whole wizard, including the
 * stage, because the wizard lives inside the app's real `NuvioTheme`.
 */
@Composable
private fun SetupThemeGrid(
    selected: AppTheme,
    onSelected: (AppTheme) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AppTheme.entries.chunked(4).forEach { row ->
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
                            .clickable { onSelected(theme) }
                            .padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(ThemeColors.getColorPalette(theme).secondary),
                        )
                        Text(
                            text = stringResource(theme.labelRes),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isSelected) tokens.colors.textPrimary else tokens.colors.textMuted,
                            maxLines = 1,
                        )
                    }
                }
                // Seven palettes over rows of four leaves one gap; an explicit spacer keeps the
                // last row's chips the same width as the first's instead of stretching them.
                repeat(4 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

// --- copy ---------------------------------------------------------------------------------

private val SetupStep.titleRes
    get() = when (this) {
        SetupStep.Welcome -> Res.string.setup_welcome_title
        SetupStep.PlaybackMode -> Res.string.playback_mode_selector_title
        SetupStep.Look -> Res.string.setup_look_title
        SetupStep.Cards -> Res.string.setup_cards_title
        SetupStep.HomeScreen -> Res.string.setup_home_title
        SetupStep.DetailsScreen -> Res.string.setup_details_title
        SetupStep.Theme -> Res.string.setup_theme_title
        SetupStep.Sources -> Res.string.setup_sources_title
        SetupStep.Trakt -> Res.string.setup_trakt_title
        SetupStep.Done -> Res.string.setup_done_title
    }

private val SetupStep.subtitleRes
    get() = when (this) {
        SetupStep.Welcome -> Res.string.setup_welcome_subtitle
        SetupStep.PlaybackMode -> Res.string.playback_mode_selector_subtitle
        SetupStep.Look -> Res.string.setup_look_subtitle
        SetupStep.Cards -> Res.string.setup_cards_subtitle
        SetupStep.HomeScreen -> Res.string.setup_home_subtitle
        SetupStep.DetailsScreen -> Res.string.setup_details_subtitle
        SetupStep.Theme -> Res.string.setup_theme_subtitle
        SetupStep.Sources -> Res.string.setup_sources_subtitle
        SetupStep.Trakt -> Res.string.setup_trakt_subtitle
        SetupStep.Done -> Res.string.setup_done_subtitle
    }

private val SetupPreset.titleRes
    get() = when (this) {
        SetupPreset.Simple -> Res.string.setup_preset_simple
        SetupPreset.Cinematic -> Res.string.setup_preset_cinematic
        SetupPreset.Compact -> Res.string.setup_preset_compact
    }

private val SetupPreset.descriptionRes
    get() = when (this) {
        SetupPreset.Simple -> Res.string.setup_preset_simple_description
        SetupPreset.Cinematic -> Res.string.setup_preset_cinematic_description
        SetupPreset.Compact -> Res.string.setup_preset_compact_description
    }
