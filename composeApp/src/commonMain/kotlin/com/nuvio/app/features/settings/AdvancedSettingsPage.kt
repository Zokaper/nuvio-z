package com.nuvio.app.features.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.debug.isDebugBuild
import com.nuvio.app.core.debug.SelfTestHooks
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.p2p.P2pCacheClearResult
import com.nuvio.app.features.p2p.P2pCacheSize
import com.nuvio.app.features.p2p.P2pConsentDialog
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.p2p.P2pStreamingEngine
import com.nuvio.app.features.p2p.P2pStreamingState
import com.nuvio.app.features.p2p.P2pTorrentProfile
import com.nuvio.app.features.playback.PlaybackMode
import com.nuvio.app.features.player.AndroidLibmpvVideoOutput
import com.nuvio.app.features.player.AndroidPlaybackEngine
import com.nuvio.app.features.player.IosAudioOutputMode
import com.nuvio.app.features.player.IosHardwareDecoderMode
import com.nuvio.app.features.player.IosTargetPrimaries
import com.nuvio.app.features.player.IosTargetTransfer
import com.nuvio.app.features.player.localizedLabel
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.STREAM_AUTO_PLAY_TIMEOUT_VALUES
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.plugins.PluginsUiState
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamAutoPlaySource
import com.nuvio.app.features.watchprogress.ContinueWatchingEnrichmentCache
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.isDesktop
import com.nuvio.app.isIos
import com.nuvio.app.isWindows
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_advanced_discord_rich_presence
import nuvio.composeapp.generated.resources.settings_advanced_discord_rich_presence_description
import nuvio.composeapp.generated.resources.settings_advanced_opengl_renderer
import nuvio.composeapp.generated.resources.settings_advanced_opengl_renderer_description
import nuvio.composeapp.generated.resources.settings_advanced_opengl_renderer_external_description
import nuvio.composeapp.generated.resources.settings_advanced_section_discord
import nuvio.composeapp.generated.resources.settings_advanced_section_windows_graphics
import nuvio.composeapp.generated.resources.settings_advanced_sentry_reports_subtitle_desktop
import nuvio.composeapp.generated.resources.sentry_disable_dialog_subtitle
import nuvio.composeapp.generated.resources.sentry_disable_dialog_subtitle_desktop
import nuvio.composeapp.generated.resources.sentry_disable_dialog_title
import nuvio.composeapp.generated.resources.sentry_enable_dialog_subtitle
import nuvio.composeapp.generated.resources.sentry_enable_dialog_subtitle_desktop
import nuvio.composeapp.generated.resources.sentry_enable_dialog_title
import nuvio.composeapp.generated.resources.sentry_help_body
import nuvio.composeapp.generated.resources.sentry_help_body_desktop
import nuvio.composeapp.generated.resources.sentry_help_title
import nuvio.composeapp.generated.resources.sentry_keep_enabled
import nuvio.composeapp.generated.resources.sentry_not_sent_body
import nuvio.composeapp.generated.resources.sentry_not_sent_title
import nuvio.composeapp.generated.resources.sentry_sent_body
import nuvio.composeapp.generated.resources.sentry_sent_body_desktop
import nuvio.composeapp.generated.resources.sentry_sent_title
import nuvio.composeapp.generated.resources.sentry_turn_off
import nuvio.composeapp.generated.resources.sentry_turn_on
import nuvio.composeapp.generated.resources.settings_advanced_clear_cw_cache
import nuvio.composeapp.generated.resources.settings_advanced_clear_cw_cache_done
import nuvio.composeapp.generated.resources.settings_advanced_clear_cw_cache_subtitle
import nuvio.composeapp.generated.resources.settings_advanced_remember_last_profile
import nuvio.composeapp.generated.resources.settings_advanced_remember_last_profile_description
import nuvio.composeapp.generated.resources.settings_advanced_section_cache
import nuvio.composeapp.generated.resources.settings_advanced_section_diagnostics
import nuvio.composeapp.generated.resources.settings_advanced_section_startup
import nuvio.composeapp.generated.resources.settings_advanced_sentry_reports
import nuvio.composeapp.generated.resources.settings_advanced_sentry_reports_subtitle
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.advancedSettingsContent(
    isTablet: Boolean,
    rememberLastProfileEnabled: Boolean,
) {
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_advanced_section_startup),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_advanced_remember_last_profile),
                    description = stringResource(Res.string.settings_advanced_remember_last_profile_description),
                    checked = rememberLastProfileEnabled,
                    isTablet = isTablet,
                    onCheckedChange = ProfileRepository::setRememberLastProfileEnabled,
                )
            }
        }
    }
    if (DesktopRendererSettings.isSupported) {
        item {
            val externallyControlled = remember { DesktopRendererSettings.isExternallyControlled }
            var useOpenGl by remember { mutableStateOf(DesktopRendererSettings.useOpenGl) }

            SettingsSection(
                title = stringResource(Res.string.settings_advanced_section_windows_graphics),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_advanced_opengl_renderer),
                        description = stringResource(
                            if (externallyControlled) {
                                Res.string.settings_advanced_opengl_renderer_external_description
                            } else {
                                Res.string.settings_advanced_opengl_renderer_description
                            },
                        ),
                        checked = useOpenGl,
                        enabled = !externallyControlled,
                        isTablet = isTablet,
                        onCheckedChange = { enabled ->
                            DesktopRendererSettings.setUseOpenGl(enabled)
                            useOpenGl = enabled
                        },
                    )
                }
            }
        }
    }
    // The self-test row shares the Diagnostics heading rather than raising a second one, so the
    // section has to survive Sentry being unsupported on a platform - otherwise the button
    // disappears with it.
    val selfTestAvailable = isDebugBuild && SelfTestHooks.launch != null
    if (SentrySettingsRepository.isSupported || selfTestAvailable) {
        item {
            val sentryEnabledFlow: StateFlow<Boolean> = remember {
                if (SentrySettingsRepository.isSupported) {
                    SentrySettingsRepository.ensureLoaded()
                    SentrySettingsRepository.enabled
                } else {
                    MutableStateFlow(false)
                }
            }
            val sentryEnabled by sentryEnabledFlow.collectAsStateWithLifecycle()
            var showSentryDialog by rememberSaveable { mutableStateOf(false) }

            SettingsSection(
                title = stringResource(Res.string.settings_advanced_section_diagnostics),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    if (SentrySettingsRepository.isSupported) {
                        SettingsSwitchRow(
                            title = stringResource(Res.string.settings_advanced_sentry_reports),
                            description = stringResource(
                                if (SentrySettingsPlatform.usesDesktopCopy) {
                                    Res.string.settings_advanced_sentry_reports_subtitle_desktop
                                } else {
                                    Res.string.settings_advanced_sentry_reports_subtitle
                                },
                            ),
                            checked = sentryEnabled,
                            isTablet = isTablet,
                            onCheckedChange = { showSentryDialog = true },
                        )
                    }
                    if (selfTestAvailable) {
                        if (SentrySettingsRepository.isSupported) {
                            SettingsGroupDivider(isTablet = isTablet)
                        }
                        // Literal strings, not resources: this row never reaches a shipped build,
                        // and the debug HUD toggle in `PlaybackSettingsPage` sets the precedent.
                        SettingsNavigationRow(
                            title = "Run self-test",
                            description = "Exercises addons, debrid, playback, downloads and sync " +
                                "against real services, then writes a report and screenshots.",
                            isTablet = isTablet,
                            onClick = { SelfTestHooks.launch?.invoke() },
                        )
                    }
                }
            }

            if (showSentryDialog) {
                SentrySettingsDialog(
                    enabled = sentryEnabled,
                    onConfirm = {
                        SentrySettingsRepository.setEnabled(!sentryEnabled)
                    },
                    onDismiss = {
                        showSentryDialog = false
                    },
                )
            }
        }
    }
    if (DiscordRichPresenceRepository.isSupported) {
        item {
            val discordEnabledFlow = remember {
                DiscordRichPresenceRepository.ensureLoaded()
                DiscordRichPresenceRepository.enabled
            }
            val discordEnabled by discordEnabledFlow.collectAsStateWithLifecycle()

            SettingsSection(
                title = stringResource(Res.string.settings_advanced_section_discord),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_advanced_discord_rich_presence),
                        description = stringResource(Res.string.settings_advanced_discord_rich_presence_description),
                        checked = discordEnabled,
                        isTablet = isTablet,
                        onCheckedChange = DiscordRichPresenceRepository::setEnabled,
                    )
                }
            }
        }
    }
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_advanced_section_cache),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                val scope = rememberCoroutineScope()
                var cleared by rememberSaveable { mutableStateOf(false) }
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_advanced_clear_cw_cache),
                    description = if (cleared) {
                        stringResource(Res.string.settings_advanced_clear_cw_cache_done)
                    } else {
                        stringResource(Res.string.settings_advanced_clear_cw_cache_subtitle)
                    },
                    isTablet = isTablet,
                    onClick = {
                        if (!cleared) {
                            ContinueWatchingEnrichmentCache.clearAll(ProfileRepository.activeProfileId)
                            cleared = true
                            scope.launch {
                                WatchProgressRepository.clearLocalAndForceSnapshotRefreshFromServer(
                                    ProfileRepository.activeProfileId,
                                )
                            }
                        }
                    },
                )
            }
        }
    }
    item {
        AdvancedPlaybackSections(isTablet = isTablet)
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SentrySettingsDialog(
    enabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    BasicAlertDialog(
        onDismissRequest = onDismiss,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = tokens.colors.surfaceDialog,
            shape = tokens.shapes.dialog,
        ) {
            Column(
                modifier = Modifier.padding(tokens.spacing.dialogPadding),
            ) {
                Text(
                    text = stringResource(
                        if (enabled) {
                            Res.string.sentry_disable_dialog_title
                        } else {
                            Res.string.sentry_enable_dialog_title
                        },
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    color = tokens.colors.textPrimary,
                )
                Spacer(modifier = Modifier.height(tokens.spacing.controlGap))
                Text(
                    text = stringResource(
                        when {
                            enabled && SentrySettingsPlatform.usesDesktopCopy -> {
                                Res.string.sentry_disable_dialog_subtitle_desktop
                            }
                            enabled -> Res.string.sentry_disable_dialog_subtitle
                            SentrySettingsPlatform.usesDesktopCopy -> {
                                Res.string.sentry_enable_dialog_subtitle_desktop
                            }
                            else -> Res.string.sentry_enable_dialog_subtitle
                        },
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = tokens.colors.textMuted,
                )
                Spacer(modifier = Modifier.height(NuvioTokens.Space.s18))
                Column(
                    verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
                ) {
                    SentryInfoSection(
                        title = stringResource(Res.string.sentry_help_title),
                        body = stringResource(
                            if (SentrySettingsPlatform.usesDesktopCopy) {
                                Res.string.sentry_help_body_desktop
                            } else {
                                Res.string.sentry_help_body
                            },
                        ),
                    )
                    SentryInfoSection(
                        title = stringResource(Res.string.sentry_sent_title),
                        body = stringResource(
                            if (SentrySettingsPlatform.usesDesktopCopy) {
                                Res.string.sentry_sent_body_desktop
                            } else {
                                Res.string.sentry_sent_body
                            },
                        ),
                    )
                    SentryInfoSection(
                        title = stringResource(Res.string.sentry_not_sent_title),
                        body = stringResource(Res.string.sentry_not_sent_body),
                    )
                }
                Spacer(modifier = Modifier.height(NuvioTokens.Space.s18))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        onClick = onDismiss,
                        shape = tokens.shapes.button,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tokens.colors.surfaceCard,
                            contentColor = tokens.colors.textPrimary,
                        ),
                    ) {
                        Text(
                            text = stringResource(
                                if (enabled) {
                                    Res.string.sentry_keep_enabled
                                } else {
                                    Res.string.action_cancel
                                },
                            ),
                        )
                    }
                    Spacer(modifier = Modifier.width(NuvioTokens.Space.s10))
                    Button(
                        onClick = {
                            onConfirm()
                            onDismiss()
                        },
                        shape = tokens.shapes.button,
                    ) {
                        Text(
                            text = stringResource(
                                if (enabled) {
                                    Res.string.sentry_turn_off
                                } else {
                                    Res.string.sentry_turn_on
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SentryInfoSection(
    title: String,
    body: String,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textMuted,
        )
    }
}

/**
 * The machine-level playback sections, moved off the Playback page.
 *
 * Decoder, the iOS output modes, P2P, Stream Selection and Stream Auto-Play are settings about
 * *how the app runs*, not about what the user is watching, and they were sitting next to
 * "Content Warnings" on a page with eleven sections while this page had four rows.
 *
 * ⚠ **The Advanced nav row is no longer `isAdvanced`** (`SettingsRootPage.kt`). Playback Engine
 * lives here now, and that is the main lever for fixing broken playback - hiding it behind
 * "Show advanced settings" would have hidden it from exactly the users who need it. The per-row
 * `isAdvanced` gates inside this page stay as they were.
 *
 * State is read in place rather than threaded through `SettingsScreen.kt`, which differs by 602
 * lines between the repositories.
 */
@Composable
private fun AdvancedPlaybackSections(isTablet: Boolean) {
    var showP2pProfileDialog by remember { mutableStateOf(false) }
    var showP2pCacheSizeDialog by remember { mutableStateOf(false) }
    var showP2pConsentDialog by remember { mutableStateOf(false) }
    var showDecoderPriorityDialog by remember { mutableStateOf(false) }
    var showPlaybackEngineDialog by remember { mutableStateOf(false) }
    var showLibmpvVideoOutputDialog by remember { mutableStateOf(false) }
    var showIosHardwareDecoderDialog by remember { mutableStateOf(false) }
    var showIosAudioOutputDialog by remember { mutableStateOf(false) }
    var showIosTargetPrimariesDialog by remember { mutableStateOf(false) }
    var showIosTargetTransferDialog by remember { mutableStateOf(false) }
    var showAutoPlayModeDialog by remember { mutableStateOf(false) }
    var showAutoPlaySourceDialog by remember { mutableStateOf(false) }
    var showAutoPlayAddonSelectionDialog by remember { mutableStateOf(false) }
    var showAutoPlayPluginSelectionDialog by remember { mutableStateOf(false) }
    var showAutoPlayRegexDialog by remember { mutableStateOf(false) }
    var p2pCacheClearResult by remember { mutableStateOf<P2pCacheClearResult?>(null) }
    var p2pCacheClearFailed by remember { mutableStateOf(false) }
    val pluginsEnabled = AppFeaturePolicy.pluginsEnabled
    val autoPlayPlayerSettings by PlayerSettingsRepository.uiState.collectAsStateWithLifecycle()
    val p2pSettings by remember {
        P2pSettingsRepository.ensureLoaded()
        P2pSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val p2pCacheState by P2pStreamingEngine.cacheState.collectAsStateWithLifecycle()
    val p2pStreamingState by P2pStreamingEngine.state.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current
    val addonUiState by AddonRepository.uiState.collectAsStateWithLifecycle()
    val pluginUiState = if (pluginsEnabled) {
        val state by PluginRepository.uiState.collectAsStateWithLifecycle()
        state
    } else {
        PluginsUiState(pluginsEnabled = false)
    }
    val androidPlaybackEngine = autoPlayPlayerSettings.androidPlaybackEngine
    val androidLibmpvVideoOutput = autoPlayPlayerSettings.androidLibmpvVideoOutput
    val androidLibmpvHardwareDecodingEnabled = autoPlayPlayerSettings.androidLibmpvHardwareDecodingEnabled
    val androidLibmpvYuv420pEnabled = autoPlayPlayerSettings.androidLibmpvYuv420pEnabled
    val decoderPriority = autoPlayPlayerSettings.decoderPriority
    val mapDV7ToHevc = autoPlayPlayerSettings.mapDV7ToHevc
    val tunnelingEnabled = autoPlayPlayerSettings.tunnelingEnabled
    val streamAutoPlaySelectedPlugins = autoPlayPlayerSettings.streamAutoPlaySelectedPlugins
    val streamAutoPlayRegex = autoPlayPlayerSettings.streamAutoPlayRegex
    val sectionSpacing = if (isTablet) 18.dp else 12.dp

    Column(
        verticalArrangement = Arrangement.spacedBy(sectionSpacing),
    ) {
        if (!isIos) {
            val decoderEnabled = !autoPlayPlayerSettings.externalPlayerEnabled
            val exoOptionsEnabled = decoderEnabled && androidPlaybackEngine != AndroidPlaybackEngine.Libmpv
            val libmpvOptionsVisible = androidPlaybackEngine != AndroidPlaybackEngine.ExoPlayer
            val libmpvOptionsEnabled = decoderEnabled && libmpvOptionsVisible
            SettingsSection(
                title = stringResource(Res.string.settings_playback_section_decoder),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_playback_engine),
                        description = androidPlaybackEngine.label,
                        enabled = decoderEnabled,
                        isTablet = isTablet,
                        onClick = { showPlaybackEngineDialog = true },
                    )
                    if (libmpvOptionsVisible) {
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsNavigationRow(
                            title = stringResource(Res.string.settings_playback_libmpv_video_output),
                            description = androidLibmpvVideoOutput.label,
                            enabled = libmpvOptionsEnabled,
                            isTablet = isTablet,
                            onClick = { showLibmpvVideoOutputDialog = true },
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsSwitchRow(
                            title = stringResource(Res.string.settings_playback_libmpv_hardware_decoding),
                            description = stringResource(Res.string.settings_playback_libmpv_hardware_decoding_description),
                            checked = androidLibmpvHardwareDecodingEnabled,
                            enabled = libmpvOptionsEnabled,
                            isTablet = isTablet,
                            onCheckedChange = PlayerSettingsRepository::setAndroidLibmpvHardwareDecodingEnabled,
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsSwitchRow(
                            title = stringResource(Res.string.settings_playback_libmpv_yuv420p),
                            description = stringResource(Res.string.settings_playback_libmpv_yuv420p_description),
                            checked = androidLibmpvYuv420pEnabled,
                            enabled = libmpvOptionsEnabled,
                            isTablet = isTablet,
                            onCheckedChange = PlayerSettingsRepository::setAndroidLibmpvYuv420pEnabled,
                        )
                    }
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_playback_decoder_priority),
                        description = decoderPriorityLabel(decoderPriority),
                        enabled = exoOptionsEnabled,
                        isAdvanced = true,
                        isTablet = isTablet,
                        onClick = { showDecoderPriorityDialog = true },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_playback_map_dv7_to_hevc),
                        description = stringResource(Res.string.settings_playback_map_dv7_to_hevc_description),
                        checked = mapDV7ToHevc,
                        enabled = exoOptionsEnabled,
                        isAdvanced = true,
                        isTablet = isTablet,
                        onCheckedChange = PlayerSettingsRepository::setMapDV7ToHevc,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_playback_tunneled_playback),
                        description = stringResource(Res.string.settings_playback_tunneled_playback_description),
                        checked = tunnelingEnabled,
                        enabled = exoOptionsEnabled,
                        isAdvanced = true,
                        isTablet = isTablet,
                        onCheckedChange = PlayerSettingsRepository::setTunnelingEnabled,
                    )
                }
            }
        }

        if (isIos) {
            SettingsSection(
                title = stringResource(Res.string.settings_playback_ios_audio_output_section),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_playback_ios_audio_output),
                        description = autoPlayPlayerSettings.iosAudioOutputMode.label,
                        isTablet = isTablet,
                        onClick = { showIosAudioOutputDialog = true },
                    )
                }
            }

            SettingsSection(
                title = stringResource(Res.string.settings_playback_ios_video_output),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_playback_ios_hardware_decoder),
                        description = autoPlayPlayerSettings.iosHardwareDecoderMode.localizedLabel(),
                        isTablet = isTablet,
                        onClick = { showIosHardwareDecoderDialog = true },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_playback_ios_extended_dynamic_range),
                        description = stringResource(Res.string.settings_playback_ios_extended_dynamic_range_desc),
                        checked = autoPlayPlayerSettings.iosExtendedDynamicRangeEnabled,
                        isTablet = isTablet,
                        onCheckedChange = PlayerSettingsRepository::setIosExtendedDynamicRangeEnabled,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_playback_ios_display_color_hint),
                        description = stringResource(Res.string.settings_playback_ios_display_color_hint_desc),
                        checked = autoPlayPlayerSettings.iosTargetColorspaceHintEnabled,
                        isTablet = isTablet,
                        onCheckedChange = PlayerSettingsRepository::setIosTargetColorspaceHintEnabled,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_playback_ios_target_primaries),
                        description = autoPlayPlayerSettings.iosTargetPrimaries.label,
                        isTablet = isTablet,
                        onClick = { showIosTargetPrimariesDialog = true },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_playback_ios_target_transfer),
                        description = autoPlayPlayerSettings.iosTargetTransfer.label,
                        isTablet = isTablet,
                        onClick = { showIosTargetTransferDialog = true },
                    )
                }
            }
        }

        if (isWindows) {
            SettingsSection(
                title = stringResource(Res.string.settings_playback_nvidia_rtx_video_section),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_playback_nvidia_rtx_super_resolution),
                        description = stringResource(Res.string.settings_playback_nvidia_rtx_super_resolution_desc),
                        checked = autoPlayPlayerSettings.nvidiaRtxSuperResolutionEnabled,
                        isTablet = isTablet,
                        onCheckedChange = PlayerSettingsRepository::setNvidiaRtxSuperResolutionEnabled,
                    )
                }
            }
        }

        if (P2pSettingsRepository.isVisible) {
            SettingsSection(
                title = stringResource(Res.string.settings_playback_section_p2p),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_p2p_title),
                        description = stringResource(Res.string.settings_p2p_subtitle),
                        checked = p2pSettings.p2pEnabled,
                        isTablet = isTablet,
                        onCheckedChange = { enabled ->
                            if (enabled && !p2pSettings.p2pEnabled) {
                                showP2pConsentDialog = true
                            } else {
                                P2pSettingsRepository.setP2pEnabled(enabled)
                            }
                        },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_p2p_hide_stats_title),
                        description = stringResource(Res.string.settings_p2p_hide_stats_subtitle),
                        checked = p2pSettings.hideTorrentStats,
                        isTablet = isTablet,
                        onCheckedChange = P2pSettingsRepository::setHideTorrentStats,
                    )
                    if (!isDesktop) {
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsNavigationRow(
                            title = stringResource(Res.string.settings_p2p_profile_title),
                            description = p2pProfileLabel(p2pSettings.torrentProfile),
                            isTablet = isTablet,
                            onClick = { showP2pProfileDialog = true },
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsNavigationRow(
                            title = stringResource(Res.string.settings_p2p_cache_size_title),
                            description = p2pCacheSizeLabel(p2pSettings.cacheSize),
                            isTablet = isTablet,
                            onClick = { showP2pCacheSizeDialog = true },
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        val cacheClearAvailable = p2pStreamingState !is P2pStreamingState.Connecting &&
                            p2pStreamingState !is P2pStreamingState.Streaming &&
                            !p2pCacheState.isClearing
                        SettingsNavigationRow(
                            title = stringResource(Res.string.settings_p2p_clear_cache_title),
                            description = when {
                                p2pCacheState.isClearing ->
                                    stringResource(Res.string.settings_p2p_clear_cache_clearing)
                                !cacheClearAvailable ->
                                    stringResource(Res.string.settings_p2p_clear_cache_playback_active)
                                p2pCacheClearFailed ->
                                    stringResource(Res.string.settings_p2p_clear_cache_failed)
                                p2pCacheClearResult != null -> stringResource(
                                    Res.string.settings_p2p_clear_cache_done,
                                    formatP2pCacheBytes(p2pCacheClearResult!!.reclaimedBytes),
                                )
                                !p2pCacheState.hasMeasurement ->
                                    stringResource(Res.string.settings_p2p_clear_cache_usage_pending)
                                else -> stringResource(
                                    Res.string.settings_p2p_clear_cache_usage,
                                    formatP2pCacheBytes(p2pCacheState.usedBytes),
                                )
                            },
                            enabled = cacheClearAvailable,
                            isTablet = isTablet,
                            onClick = {
                                p2pCacheClearResult = null
                                p2pCacheClearFailed = false
                                coroutineScope.launch {
                                    runCatching { P2pStreamingEngine.clearCache() }
                                        .onSuccess { p2pCacheClearResult = it }
                                        .onFailure { p2pCacheClearFailed = true }
                                }
                            },
                        )
                    }
                }
            }
        }

        SettingsSection(
            title = stringResource(Res.string.settings_playback_section_stream_auto_play),
            isTablet = isTablet,
        ) {
            val classicAutoPlayEnabled = autoPlayPlayerSettings.playbackMode == PlaybackMode.CLASSIC
            SettingsGroup(isTablet = isTablet) {
                if (!classicAutoPlayEnabled) {
                    Text(
                        text = stringResource(Res.string.settings_playback_auto_play_classic_only),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                }
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_playback_stream_selection_mode),
                    description = stringResource(autoPlayPlayerSettings.streamAutoPlayMode.labelRes),
                    enabled = classicAutoPlayEnabled,
                    isTablet = isTablet,
                    onClick = { showAutoPlayModeDialog = true },
                )
                if (autoPlayPlayerSettings.streamAutoPlayMode == StreamAutoPlayMode.REGEX_MATCH) {
                    SettingsGroupDivider(isTablet = isTablet)
                    val notSetLabel = stringResource(Res.string.settings_playback_not_set)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_playback_regex_pattern),
                        description = autoPlayPlayerSettings.streamAutoPlayRegex.ifBlank { notSetLabel },
                        enabled = classicAutoPlayEnabled,
                        isTablet = isTablet,
                        onClick = { showAutoPlayRegexDialog = true },
                    )
                }
                SettingsGroupDivider(isTablet = isTablet)
                val timeoutSec = autoPlayPlayerSettings.streamAutoPlayTimeoutSeconds
                val timeoutLabel = when (timeoutSec) {
                    0 -> stringResource(Res.string.settings_playback_timeout_instant)
                    Int.MAX_VALUE -> stringResource(Res.string.settings_playback_timeout_unlimited)
                    else -> stringResource(Res.string.settings_playback_timeout_seconds, timeoutSec)
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (classicAutoPlayEnabled) 1f else 0.55f)
                        .padding(horizontal = if (isTablet) 18.dp else 16.dp, vertical = 10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = stringResource(Res.string.settings_playback_stream_timeout),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(Res.string.settings_playback_stream_timeout_description),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        ValueBox(text = timeoutLabel, modifier = Modifier.wrapContentWidth())
                    }
                    val timeoutIndex = STREAM_AUTO_PLAY_TIMEOUT_VALUES.indexOf(timeoutSec)
                        .coerceAtLeast(0)
                    val maxIndex = (STREAM_AUTO_PLAY_TIMEOUT_VALUES.size - 1).toFloat()
                    var sliderValue by remember(timeoutIndex) { mutableFloatStateOf(timeoutIndex.toFloat()) }
                    var lastHapticStep by remember(timeoutIndex) { mutableStateOf(timeoutIndex.toFloat()) }
                    Slider(
                        enabled = classicAutoPlayEnabled,
                        value = sliderValue,
                        onValueChange = {
                            val snapped = snapToStep(it, 1f)
                            sliderValue = snapped

                            if (snapped != lastHapticStep) {
                                lastHapticStep = snapped
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        },
                        onValueChangeFinished = {
                            val index = sliderValue.toInt().coerceIn(0, STREAM_AUTO_PLAY_TIMEOUT_VALUES.size - 1)
                            PlayerSettingsRepository.setStreamAutoPlayTimeoutSeconds(STREAM_AUTO_PLAY_TIMEOUT_VALUES[index])
                        },
                        valueRange = 0f..maxIndex,
                        steps = calculateSteps(0f, maxIndex, 1f),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_playback_source_scope),
                    description = stringResource(autoPlayPlayerSettings.streamAutoPlaySource.labelRes(pluginsEnabled)),
                    enabled = classicAutoPlayEnabled,
                    isTablet = isTablet,
                    onClick = { showAutoPlaySourceDialog = true },
                )
                if (autoPlayPlayerSettings.streamAutoPlaySource != StreamAutoPlaySource.ENABLED_PLUGINS_ONLY) {
                    SettingsGroupDivider(isTablet = isTablet)
                    val addonSubtitle = if (autoPlayPlayerSettings.streamAutoPlaySelectedAddons.isEmpty()) {
                        stringResource(Res.string.settings_playback_all_addons)
                    } else {
                        stringResource(
                            Res.string.settings_playback_selected_count,
                            autoPlayPlayerSettings.streamAutoPlaySelectedAddons.size,
                        )
                    }
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_playback_allowed_addons),
                        description = addonSubtitle,
                        enabled = classicAutoPlayEnabled,
                        isTablet = isTablet,
                        onClick = { showAutoPlayAddonSelectionDialog = true },
                    )
                }
                if (pluginsEnabled && autoPlayPlayerSettings.streamAutoPlaySource != StreamAutoPlaySource.INSTALLED_ADDONS_ONLY) {
                    SettingsGroupDivider(isTablet = isTablet)
                    val pluginSubtitle = if (autoPlayPlayerSettings.streamAutoPlaySelectedPlugins.isEmpty()) {
                        stringResource(Res.string.settings_playback_all_plugins)
                    } else {
                        stringResource(
                            Res.string.settings_playback_selected_count,
                            autoPlayPlayerSettings.streamAutoPlaySelectedPlugins.size,
                        )
                    }
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_playback_allowed_plugins),
                        description = pluginSubtitle,
                        enabled = classicAutoPlayEnabled,
                        isTablet = isTablet,
                        onClick = { showAutoPlayPluginSelectionDialog = true },
                    )
                }
            }
        }

    }

    if (showP2pProfileDialog && !isDesktop) {
        IosEnumSelectionDialog(
            title = stringResource(Res.string.settings_p2p_profile_title),
            options = P2pTorrentProfile.entries,
            selected = p2pSettings.torrentProfile,
            label = { p2pProfileLabel(it) },
            description = { profile ->
                when (profile) {
                    P2pTorrentProfile.SOFT ->
                        stringResource(Res.string.settings_p2p_profile_soft_description)
                    P2pTorrentProfile.BALANCED ->
                        stringResource(Res.string.settings_p2p_profile_balanced_description)
                    P2pTorrentProfile.FAST ->
                        stringResource(Res.string.settings_p2p_profile_fast_description)
                }
            },
            onSelect = { profile ->
                P2pSettingsRepository.setTorrentProfile(profile)
                showP2pProfileDialog = false
            },
            onDismiss = { showP2pProfileDialog = false },
        )
    }

    if (showP2pCacheSizeDialog && !isDesktop) {
        IosEnumSelectionDialog(
            title = stringResource(Res.string.settings_p2p_cache_size_title),
            options = P2pCacheSize.entries,
            selected = p2pSettings.cacheSize,
            label = { p2pCacheSizeLabel(it) },
            onSelect = { size ->
                P2pSettingsRepository.setCacheSize(size)
                p2pCacheClearResult = null
                showP2pCacheSizeDialog = false
            },
            onDismiss = { showP2pCacheSizeDialog = false },
        )
    }

    if (showP2pConsentDialog) {
        P2pConsentDialog(
            onEnableP2p = {
                P2pSettingsRepository.setP2pEnabled(true)
                showP2pConsentDialog = false
            },
            onDismiss = { showP2pConsentDialog = false },
        )
    }

    if (showDecoderPriorityDialog) {
        DecoderPriorityDialog(
            selectedPriority = decoderPriority,
            onPrioritySelected = { priority ->
                PlayerSettingsRepository.setDecoderPriority(priority)
                showDecoderPriorityDialog = false
            },
            onDismiss = { showDecoderPriorityDialog = false },
        )
    }

    if (showPlaybackEngineDialog) {
        PlaybackEngineDialog(
            selectedEngine = androidPlaybackEngine,
            onEngineSelected = { engine ->
                PlayerSettingsRepository.setAndroidPlaybackEngine(engine)
                showPlaybackEngineDialog = false
            },
            onDismiss = { showPlaybackEngineDialog = false },
        )
    }

    if (showLibmpvVideoOutputDialog) {
        IosEnumSelectionDialog(
            title = stringResource(Res.string.settings_playback_libmpv_video_output_dialog),
            options = AndroidLibmpvVideoOutput.entries,
            selected = androidLibmpvVideoOutput,
            label = { it.label },
            description = { it.description },
            onSelect = {
                PlayerSettingsRepository.setAndroidLibmpvVideoOutput(it)
                showLibmpvVideoOutputDialog = false
            },
            onDismiss = { showLibmpvVideoOutputDialog = false },
        )
    }

    if (showIosHardwareDecoderDialog) {
        IosEnumSelectionDialog(
            title = stringResource(Res.string.settings_playback_ios_hw_decoder_dialog),
            options = IosHardwareDecoderMode.entries,
            selected = autoPlayPlayerSettings.iosHardwareDecoderMode,
            label = { it.label },
            onSelect = {
                PlayerSettingsRepository.setIosHardwareDecoderMode(it)
                showIosHardwareDecoderDialog = false
            },
            onDismiss = { showIosHardwareDecoderDialog = false },
        )
    }

    if (showIosAudioOutputDialog) {
        IosEnumSelectionDialog(
            title = stringResource(Res.string.settings_playback_ios_audio_output_dialog),
            options = IosAudioOutputMode.selectableEntries,
            selected = autoPlayPlayerSettings.iosAudioOutputMode,
            label = { it.label },
            description = {
                when (it) {
                    IosAudioOutputMode.Auto -> stringResource(Res.string.settings_playback_ios_audio_output_auto_desc)
                    IosAudioOutputMode.AvFoundation -> stringResource(Res.string.settings_playback_ios_audio_output_avfoundation_desc)
                    IosAudioOutputMode.AudioUnit -> stringResource(Res.string.settings_playback_ios_audio_output_audiounit_desc)
                }
            },
            onSelect = {
                PlayerSettingsRepository.setIosAudioOutputMode(it)
                showIosAudioOutputDialog = false
            },
            onDismiss = { showIosAudioOutputDialog = false },
        )
    }

    if (showIosTargetPrimariesDialog) {
        IosEnumSelectionDialog(
            title = stringResource(Res.string.settings_playback_ios_target_primaries_dialog),
            options = IosTargetPrimaries.entries,
            selected = autoPlayPlayerSettings.iosTargetPrimaries,
            label = { it.label },
            onSelect = {
                PlayerSettingsRepository.setIosTargetPrimaries(it)
                showIosTargetPrimariesDialog = false
            },
            onDismiss = { showIosTargetPrimariesDialog = false },
        )
    }

    if (showIosTargetTransferDialog) {
        IosEnumSelectionDialog(
            title = stringResource(Res.string.settings_playback_ios_target_transfer_dialog),
            options = IosTargetTransfer.entries,
            selected = autoPlayPlayerSettings.iosTargetTransfer,
            label = { it.label },
            onSelect = {
                PlayerSettingsRepository.setIosTargetTransfer(it)
                showIosTargetTransferDialog = false
            },
            onDismiss = { showIosTargetTransferDialog = false },
        )
    }

    if (showAutoPlayModeDialog) {
        StreamAutoPlayModeDialog(
            selectedMode = autoPlayPlayerSettings.streamAutoPlayMode,
            onModeSelected = {
                PlayerSettingsRepository.setStreamAutoPlayMode(it)
                showAutoPlayModeDialog = false
            },
            onDismiss = { showAutoPlayModeDialog = false },
        )
    }

    if (showAutoPlaySourceDialog) {
        StreamAutoPlaySourceDialog(
            pluginsEnabled = pluginsEnabled,
            selectedSource = autoPlayPlayerSettings.streamAutoPlaySource,
            onSourceSelected = {
                PlayerSettingsRepository.setStreamAutoPlaySource(it)
                showAutoPlaySourceDialog = false
            },
            onDismiss = { showAutoPlaySourceDialog = false },
        )
    }

    if (showAutoPlayAddonSelectionDialog) {
        val addonNames = addonUiState.addons
            .enabledAddons()
            .mapNotNull { it.manifest }
            .filter { manifest -> manifest.resources.any { resource -> resource.name == "stream" } }
            .map { it.name }
            .distinct()
            .sorted()
        StreamAutoPlayProviderSelectionDialog(
            title = stringResource(Res.string.settings_playback_allowed_addons),
            allLabel = stringResource(Res.string.settings_playback_all_addons),
            items = addonNames,
            selectedItems = autoPlayPlayerSettings.streamAutoPlaySelectedAddons,
            onSelectionSaved = {
                PlayerSettingsRepository.setStreamAutoPlaySelectedAddons(it)
                showAutoPlayAddonSelectionDialog = false
            },
            onDismiss = { showAutoPlayAddonSelectionDialog = false },
        )
    }

    if (pluginsEnabled && showAutoPlayPluginSelectionDialog) {
        val pluginNames = pluginUiState.scrapers
            .filter { it.enabled }
            .map { it.name }
            .distinct()
            .sorted()
        StreamAutoPlayProviderSelectionDialog(
            title = stringResource(Res.string.settings_playback_allowed_plugins),
            allLabel = stringResource(Res.string.settings_playback_all_plugins),
            items = pluginNames,
            selectedItems = autoPlayPlayerSettings.streamAutoPlaySelectedPlugins,
            onSelectionSaved = {
                PlayerSettingsRepository.setStreamAutoPlaySelectedPlugins(it)
                showAutoPlayPluginSelectionDialog = false
            },
            onDismiss = { showAutoPlayPluginSelectionDialog = false },
        )
    }

    if (showAutoPlayRegexDialog) {
        StreamAutoPlayRegexDialog(
            initialRegex = autoPlayPlayerSettings.streamAutoPlayRegex,
            onSave = {
                PlayerSettingsRepository.setStreamAutoPlayRegex(it)
                showAutoPlayRegexDialog = false
            },
            onDismiss = { showAutoPlayRegexDialog = false },
        )
    }

}
