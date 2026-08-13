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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
// and DetailHero.kt already use. Deliberate: this screen reads about seventy keys.
//
// ⚠ That decision covers THIS file only. `SettingsRootPage.kt` imports every key explicitly,
// and adding the "Run setup again" row there without its two imports is what failed the first
// debug-release run of the wizard. Check the host file's style before using a key in it.

/**
 * The first-launch setup wizard.
 *
 * Replaces the standalone playback-mode selector, which was the app's entire onboarding: one
 * question, answered before the user had seen anything the answer applied to. That question is
 * still asked first, still through the same `PlaybackModeCard` so its copy cannot drift from
 * the settings dialog - and every appearance choice behind it is asked against a live preview
 * of the real app.
 *
 * **The preview is the screen; the wizard is a sheet on top of it.** The stage fills the
 * window and the controls sit in a translucent panel docked to the bottom, so the thing being
 * changed is always the largest object on screen. Each step scrolls the preview to whatever it
 * affects - see [SetupPreviewFocus].
 *
 * ⚠ **Every choice is written the moment it is tapped**, through the same repository setter the
 * settings page uses. That is what lets [SetupPreviewStage] render the real composables reading
 * the real state instead of a parallel mock. It also means there is no undo, which matches how
 * every settings page in this app already behaves - and why the exit on the first step is
 * "Skip for now" rather than "use defaults": nothing has been written yet, so there is nothing
 * to restore.
 *
 * @param dismissible true when opened from Settings rather than gating the app.
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
    val step = remember(stepName) {
        SetupStep.entries.firstOrNull { it.name == stepName } ?: SetupStep.Welcome
    }

    // `enabled`, not merely present: an installed-but-disabled addon is not a source, so a
    // profile carrying only those still gets asked.
    val plan = SetupWizardPlan(
        offerSources = addons.addons.none { it.enabled },
        offerTrakt = trakt.mode != TraktConnectionMode.CONNECTED,
    )

    var addonUrl by rememberSaveable { mutableStateOf("") }
    var addonBusy by remember { mutableStateOf(false) }
    // Two states rather than a message plus a boolean: the error text comes from the addon or
    // the manifest fetch and is shown verbatim, while the success line is a formatted string
    // resource and can only be built in composition.
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

    fun advance() {
        if (isFinalSetupStep(step, plan)) {
            complete()
        } else {
            stepName = (nextSetupStep(step, plan) ?: SetupStep.Done).name
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.colors.background),
    ) {
        val windowHeight = maxHeight
        val windowWidth = maxWidth
        val insets = WindowInsets.safeDrawing.asPaddingValues()

        // The sheet takes a little under half the window and is capped, so on a tall phone it
        // does not creep up over the preview and on a desktop window it does not become a
        // letterbox. The preview is told the same figure so its content can scroll clear of it.
        val sheetHeight = (windowHeight * 0.46f).coerceIn(300.dp, 460.dp)

        SetupPreviewStage(
            focus = step.previewFocus,
            catalogRowTitle = stringResource(Res.string.setup_preview_row_title),
            continueWatchingTitle = stringResource(Res.string.setup_preview_continue_watching),
            episodesSectionTitle = stringResource(Res.string.setup_preview_episodes),
            bottomInset = sheetHeight,
            modifier = Modifier.fillMaxSize(),
        )

        SetupSheet(
            step = step,
            plan = plan,
            dismissible = dismissible,
            onDismiss = onDismiss,
            sheetHeight = sheetHeight,
            // Centred and capped on wide windows. One layout everywhere: the preview is the
            // thing that should use the extra width, not the controls.
            maxSheetWidth = if (windowWidth >= 768.dp) 620.dp else windowWidth,
            bottomInset = insets.calculateBottomPadding(),
            onBack = { previousSetupStep(step, plan)?.let { stepName = it.name } },
            onSkipAll = ::complete,
            onAdvance = ::advance,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            SetupStepBody(
                step = step,
                playbackMode = playerSettings.playbackMode,
                posterWidthDp = posterStyle.widthDp,
                posterCornerRadiusDp = posterStyle.cornerRadiusDp,
                landscapeCards = posterStyle.catalogLandscapeModeEnabled,
                hideLabels = posterStyle.hideLabelsEnabled,
                heroEnabled = homeSettings.heroEnabled,
                continueWatchingStyle = continueWatching.style,
                useEpisodeThumbnails = continueWatching.useEpisodeThumbnails,
                blurNextUp = continueWatching.blurNextUp,
                backgroundMode = metaSettings.backgroundMode,
                episodeCardStyle = metaSettings.episodeCardStyle,
                blurUnwatchedEpisodes = metaSettings.blurUnwatchedEpisodes,
                tabLayout = metaSettings.tabLayout,
                selectedTheme = selectedTheme,
                amoledEnabled = amoledEnabled,
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
    }
}

/**
 * Where the preview should be looking while this step is on screen.
 *
 * The steps that change nothing visible rest on the home screen rather than going blank: the
 * wizard is selling the app, and an empty stage behind "Connect Trakt" sells nothing.
 */
private val SetupStep.previewFocus: SetupPreviewFocus
    get() = when (this) {
        SetupStep.Cards -> SetupPreviewFocus.HomeCatalog
        SetupStep.HomeScreen -> SetupPreviewFocus.HomeHero
        SetupStep.ContinueWatching -> SetupPreviewFocus.HomeContinueWatching
        SetupStep.DetailsScreen -> SetupPreviewFocus.DetailsHero
        SetupStep.Episodes -> SetupPreviewFocus.DetailsEpisodes
        SetupStep.Welcome, SetupStep.PlaybackMode, SetupStep.Theme,
        SetupStep.Sources, SetupStep.Trakt, SetupStep.Done,
        -> SetupPreviewFocus.HomeHero
    }

/**
 * The glassy panel the controls live in.
 *
 * ⚠ **This is translucency, not a backdrop blur, and the distinction is a real limitation
 * rather than a shortcut.** `Modifier.blur` blurs a composable's *own* content, not what is
 * painted behind it - `MetaDetailsScreen`'s Cinematic mode works only because it draws a
 * second, blurred copy of the backdrop image itself. Compose Multiplatform has no
 * backdrop-filter primitive, so a true frosted pane would mean rendering the entire stage a
 * second time into a `GraphicsLayer` and drawing it back with a `BlurEffect` - which is also
 * API 31+ on Android, so it would degrade on exactly the devices that need it most.
 *
 * So the glass here is a translucent `surfaceSheet` over a gradient that deepens towards the
 * bottom: the artwork shows through softly at the top edge and the controls sit on solid
 * colour. **Do not thin these alphas to taste** - they are what keeps text readable over a
 * bright poster, and there is no blur underneath to fall back on.
 */
@Composable
private fun SetupSheet(
    step: SetupStep,
    plan: SetupWizardPlan,
    dismissible: Boolean,
    onDismiss: () -> Unit,
    sheetHeight: Dp,
    maxSheetWidth: Dp,
    bottomInset: Dp,
    onBack: () -> Unit,
    onSkipAll: () -> Unit,
    onAdvance: () -> Unit,
    modifier: Modifier = Modifier,
    body: @Composable () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // A short fade above the sheet so the preview does not end on a hard line.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, tokens.colors.background.copy(alpha = 0.55f)),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .widthIn(max = maxSheetWidth)
                .fillMaxWidth()
                .height(sheetHeight)
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            tokens.colors.surfaceSheet.copy(alpha = 0.82f),
                            tokens.colors.surfaceSheet.copy(alpha = 0.97f),
                        ),
                    ),
                )
                .border(
                    width = tokens.borders.hairline,
                    color = tokens.colors.borderSubtle,
                    shape = shape,
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = 22.dp,
                        end = 22.dp,
                        top = 18.dp,
                        bottom = 14.dp + bottomInset,
                    ),
            ) {
                SetupSheetHeader(
                    step = step,
                    plan = plan,
                    dismissible = dismissible,
                    onDismiss = onDismiss,
                )
                Spacer(modifier = Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    body()
                }
                Spacer(modifier = Modifier.height(12.dp))
                SetupSheetFooter(
                    step = step,
                    plan = plan,
                    onBack = onBack,
                    onSkipAll = onSkipAll,
                    onAdvance = onAdvance,
                )
            }
        }
    }
}

@Composable
private fun SetupSheetHeader(
    step: SetupStep,
    plan: SetupWizardPlan,
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
private fun SetupSheetFooter(
    step: SetupStep,
    plan: SetupWizardPlan,
    onBack: () -> Unit,
    onSkipAll: () -> Unit,
    onAdvance: () -> Unit,
) {
    if (step == SetupStep.Welcome) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = onAdvance, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(Res.string.setup_welcome_start))
            }
            TextButton(onClick = onSkipAll, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(Res.string.setup_welcome_skip))
            }
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (previousSetupStep(step, plan) != null) {
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

@Composable
private fun SetupStepBody(
    step: SetupStep,
    playbackMode: PlaybackMode,
    posterWidthDp: Int,
    posterCornerRadiusDp: Int,
    landscapeCards: Boolean,
    hideLabels: Boolean,
    heroEnabled: Boolean,
    continueWatchingStyle: ContinueWatchingSectionStyle,
    useEpisodeThumbnails: Boolean,
    blurNextUp: Boolean,
    backgroundMode: MetaScreenBackgroundMode,
    episodeCardStyle: MetaEpisodeCardStyle,
    blurUnwatchedEpisodes: Boolean,
    tabLayout: Boolean,
    selectedTheme: AppTheme,
    amoledEnabled: Boolean,
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
    ) { current ->
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                    SetupToggleRow(
                        title = stringResource(Res.string.setup_cards_labels),
                        checked = !hideLabels,
                        onCheckedChange = { PosterCardStyleRepository.setHideLabelsEnabled(!it) },
                    )
                }

                SetupStep.HomeScreen -> SetupToggleRow(
                    title = stringResource(Res.string.setup_home_hero),
                    description = stringResource(Res.string.setup_home_hero_description),
                    checked = heroEnabled,
                    onCheckedChange = HomeCatalogSettingsRepository::setHeroEnabled,
                )

                SetupStep.ContinueWatching -> {
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
                    SetupToggleRow(
                        title = stringResource(Res.string.setup_cw_thumbnails),
                        description = stringResource(Res.string.setup_cw_thumbnails_description),
                        checked = useEpisodeThumbnails,
                        onCheckedChange = ContinueWatchingPreferencesRepository::setUseEpisodeThumbnails,
                    )
                    // Only meaningful over a thumbnail; with artwork off there is nothing to
                    // blur, and a toggle that visibly does nothing reads as broken.
                    if (useEpisodeThumbnails) {
                        SetupToggleRow(
                            title = stringResource(Res.string.setup_cw_blur_next_up),
                            description = stringResource(Res.string.setup_cw_blur_next_up_description),
                            checked = blurNextUp,
                            onCheckedChange = ContinueWatchingPreferencesRepository::setBlurNextUp,
                        )
                    }
                }

                SetupStep.DetailsScreen -> SetupChoiceGroup(
                    title = stringResource(Res.string.setup_details_background),
                    options = listOf(
                        stringResource(Res.string.setup_details_background_normal) to MetaScreenBackgroundMode.Normal,
                        stringResource(Res.string.setup_details_background_cinematic) to MetaScreenBackgroundMode.Cinematic,
                        stringResource(Res.string.setup_details_background_dominant) to MetaScreenBackgroundMode.DominantColor,
                    ),
                    selected = backgroundMode,
                    onSelected = MetaScreenSettingsRepository::setBackgroundMode,
                )

                SetupStep.Episodes -> {
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
                        title = stringResource(Res.string.setup_episodes_blur_unwatched),
                        description = stringResource(Res.string.setup_episodes_blur_unwatched_description),
                        checked = blurUnwatchedEpisodes,
                        onCheckedChange = MetaScreenSettingsRepository::setBlurUnwatchedEpisodes,
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
    // controller and cannot reach the settings page - and a button that goes nowhere is worse
    // than a sentence that says where to look.
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
 * A labelled row of mutually exclusive chips.
 *
 * Not `NuvioSurfaceCard`-based: that takes its colour from `colors.surface`, and
 * `surfaceSheet == surface` in the token set, so a card on this sheet would be invisible - the
 * trap the quality sheet hit. These use an `overlayHover` lift instead, which is what that
 * sheet settled on.
 */
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
                        .padding(vertical = 11.dp),
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
        SetupStep.Cards -> Res.string.setup_cards_title
        SetupStep.HomeScreen -> Res.string.setup_home_title
        SetupStep.ContinueWatching -> Res.string.setup_cw_title
        SetupStep.DetailsScreen -> Res.string.setup_details_title
        SetupStep.Episodes -> Res.string.setup_episodes_title
        SetupStep.Theme -> Res.string.setup_theme_title
        SetupStep.Sources -> Res.string.setup_sources_title
        SetupStep.Trakt -> Res.string.setup_trakt_title
        SetupStep.Done -> Res.string.setup_done_title
    }

private val SetupStep.subtitleRes
    get() = when (this) {
        SetupStep.Welcome -> Res.string.setup_welcome_subtitle
        SetupStep.PlaybackMode -> Res.string.playback_mode_selector_subtitle
        SetupStep.Cards -> Res.string.setup_cards_subtitle
        SetupStep.HomeScreen -> Res.string.setup_home_subtitle
        SetupStep.ContinueWatching -> Res.string.setup_cw_subtitle
        SetupStep.DetailsScreen -> Res.string.setup_details_subtitle
        SetupStep.Episodes -> Res.string.setup_episodes_subtitle
        SetupStep.Theme -> Res.string.setup_theme_subtitle
        SetupStep.Sources -> Res.string.setup_sources_subtitle
        SetupStep.Trakt -> Res.string.setup_trakt_subtitle
        SetupStep.Done -> Res.string.setup_done_subtitle
    }
