package com.nuvio.app.features.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.player.AndroidPlaybackEngine
import com.nuvio.app.features.player.AvailableLanguageOptions
import com.nuvio.app.features.player.languageLabelForCode
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.SubtitleBackgroundColorSwatches
import com.nuvio.app.features.player.SubtitleColorSwatches
import com.nuvio.app.features.player.subtitleFontSizeRangeSp
import com.nuvio.app.features.player.SubtitleLanguageOption
import com.nuvio.app.isIos
import nuvio.composeapp.generated.resources.*
import nuvio.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource

/**
 * Subtitles, on a page of their own.
 *
 * They were sixteen rows across two sections buried in the middle of an eleven-section Playback
 * page. Nothing here is new; every row keeps the storage key it already had, so there is no
 * migration and `AdvancedSettingsDefault.hasTunedAnAdvancedSetting` is unaffected - it keys on
 * stored values, not on where a row is drawn.
 *
 * The dialogs these rows open still live in `PlaybackSettingsPage.kt`, which is where every
 * settings dialog in this package lives; only the rows moved.
 */
internal fun LazyListScope.subtitlesSettingsContent(
    isTablet: Boolean,
    onOpenPlaybackLanguage: () -> Unit,
) {
    item {
        SubtitlesSettingsSection(
            isTablet = isTablet,
            onOpenPlaybackLanguage = onOpenPlaybackLanguage,
        )
    }
}

@Composable
private fun SubtitlesSettingsSection(
    isTablet: Boolean,
    onOpenPlaybackLanguage: () -> Unit,
) {
    var showAddonSubtitleStartupModeDialog by remember { mutableStateOf(false) }
    var showSubtitleTextColorDialog by remember { mutableStateOf(false) }
    var showSubtitleBackgroundColorDialog by remember { mutableStateOf(false) }
    var showSubtitleOutlineColorDialog by remember { mutableStateOf(false) }
    var showLibassRenderTypeDialog by remember { mutableStateOf(false) }
    // Read in place rather than threaded down: `SettingsScreen.kt` differs by 602 lines
    // between the repositories, so twenty more value parameters would be twenty more hand-ports.
    // This is the pattern `advancedSettingsContent` and `SettingsRootPage` already follow.
    val autoPlayPlayerSettings by PlayerSettingsRepository.uiState.collectAsStateWithLifecycle()
    val useLibass = autoPlayPlayerSettings.useLibass
    val libassRenderType = autoPlayPlayerSettings.libassRenderType
    val androidPlaybackEngine = autoPlayPlayerSettings.androidPlaybackEngine
    val sectionSpacing = if (isTablet) 18.dp else 12.dp

    Column(
        verticalArrangement = Arrangement.spacedBy(sectionSpacing),
    ) {
        SettingsSection(
            title = stringResource(Res.string.settings_subtitles_section_languages),
            isTablet = isTablet,
        ) {
            // With an external player on, nothing in this section reaches anything: it picks its
            // own subtitles and renders them itself. The *language* rows are the exception, which
            // is part of why they moved - they still apply when subtitle forwarding is on, and
            // they now say so where they are drawn.
            val isExternalPlayer = autoPlayPlayerSettings.externalPlayerEnabled
            val otherSubtitleOptionsEnabled = !isExternalPlayer

            SettingsGroup(isTablet = isTablet) {
                // ⚠ **A pointer, not a duplicate.** The two language pickers moved to
                // Playback - Language, beside the audio ones, because they are one answer to one
                // question. A settings search for "subtitle language" still lands here, so the
                // row that used to be the answer now says where the answer went. Restating the
                // current value would make it look editable from two places.
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_subtitles_languages_moved),
                    description = stringResource(Res.string.settings_subtitles_languages_moved_description),
                    isTablet = isTablet,
                    onClick = onOpenPlaybackLanguage,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_subtitle_use_forced),
                    description = stringResource(Res.string.settings_playback_subtitle_use_forced_description),
                    checked = autoPlayPlayerSettings.subtitleStyle.useForcedSubtitles,
                    enabled = otherSubtitleOptionsEnabled,
                    isTablet = isTablet,
                    onCheckedChange = { enabled ->
                        PlayerSettingsRepository.setSubtitleStyle(
                            autoPlayPlayerSettings.subtitleStyle.copy(useForcedSubtitles = enabled),
                        )
                    },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_subtitle_show_preferred_only),
                    description = stringResource(Res.string.settings_playback_subtitle_show_preferred_only_description),
                    checked = autoPlayPlayerSettings.subtitleStyle.showOnlyPreferredLanguages,
                    enabled = otherSubtitleOptionsEnabled,
                    isTablet = isTablet,
                    onCheckedChange = { enabled ->
                        PlayerSettingsRepository.setSubtitleStyle(
                            autoPlayPlayerSettings.subtitleStyle.copy(showOnlyPreferredLanguages = enabled),
                        )
                    },
                )
                SettingsGroupDivider(isTablet = isTablet)
                // Read by all three native bridges since they were written and writable from
                // nowhere, so it has been stuck off for every user who ever wanted it.
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_subtitle_strip_sdh),
                    description = stringResource(Res.string.settings_playback_subtitle_strip_sdh_description),
                    checked = autoPlayPlayerSettings.subtitleStyle.stripSdh,
                    enabled = otherSubtitleOptionsEnabled,
                    isTablet = isTablet,
                    onCheckedChange = { enabled ->
                        PlayerSettingsRepository.setSubtitleStyle(
                            autoPlayPlayerSettings.subtitleStyle.copy(stripSdh = enabled),
                        )
                    },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_playback_addon_subtitle_startup_mode),
                    description = addonSubtitleStartupModeLabel(autoPlayPlayerSettings.addonSubtitleStartupMode),
                    enabled = otherSubtitleOptionsEnabled,
                    isTablet = isTablet,
                    onClick = { showAddonSubtitleStartupModeDialog = true },
                )
            }
        }

        SettingsSection(
            title = stringResource(Res.string.settings_subtitles_section_rendering),
            isTablet = isTablet,
        ) {
            val subtitleRenderingEnabled = !autoPlayPlayerSettings.externalPlayerEnabled
            SettingsGroup(isTablet = isTablet) {
                val subtitleStyle = autoPlayPlayerSettings.subtitleStyle
                SettingsSliderRow(
                    title = stringResource(Res.string.settings_playback_subtitle_size),
                    value = subtitleStyle.fontSizeSp,
                    valueText = stringResource(Res.string.compose_player_font_size_value, subtitleStyle.fontSizeSp),
                    valueRange = subtitleFontSizeRangeSp,
                    step = 2,
                    isTablet = isTablet,
                    enabled = subtitleRenderingEnabled,
                    onValueChange = { value ->
                        PlayerSettingsRepository.setSubtitleStyle(subtitleStyle.copy(fontSizeSp = value))
                    },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSliderRow(
                    title = stringResource(Res.string.settings_playback_subtitle_vertical_offset),
                    value = subtitleStyle.bottomOffset,
                    valueText = subtitleStyle.bottomOffset.toString(),
                    valueRange = 0..200,
                    step = 5,
                    isTablet = isTablet,
                    enabled = subtitleRenderingEnabled,
                    onValueChange = { value ->
                        PlayerSettingsRepository.setSubtitleStyle(subtitleStyle.copy(bottomOffset = value))
                    },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_subtitle_bold),
                    description = stringResource(Res.string.settings_playback_subtitle_bold_description),
                    checked = subtitleStyle.bold,
                    enabled = subtitleRenderingEnabled,
                    isTablet = isTablet,
                    onCheckedChange = { enabled ->
                        PlayerSettingsRepository.setSubtitleStyle(subtitleStyle.copy(bold = enabled))
                    },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_playback_subtitle_text_color),
                    description = subtitleColorLabel(subtitleStyle.textColor),
                    enabled = subtitleRenderingEnabled,
                    isTablet = isTablet,
                    onClick = { showSubtitleTextColorDialog = true },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_playback_subtitle_background_color),
                    description = subtitleColorLabel(subtitleStyle.backgroundColor),
                    enabled = subtitleRenderingEnabled,
                    isTablet = isTablet,
                    onClick = { showSubtitleBackgroundColorDialog = true },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_subtitle_outline),
                    description = stringResource(Res.string.settings_playback_subtitle_outline_description),
                    checked = subtitleStyle.outlineEnabled,
                    enabled = subtitleRenderingEnabled,
                    isTablet = isTablet,
                    onCheckedChange = { enabled ->
                        PlayerSettingsRepository.setSubtitleStyle(subtitleStyle.copy(outlineEnabled = enabled))
                    },
                )
                if (subtitleStyle.outlineEnabled) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_playback_subtitle_outline_color),
                        description = subtitleColorLabel(subtitleStyle.outlineColor),
                        enabled = subtitleRenderingEnabled,
                        isTablet = isTablet,
                        onClick = { showSubtitleOutlineColorDialog = true },
                    )
                }
                val showLibassSettings = !isIos && androidPlaybackEngine != AndroidPlaybackEngine.Libmpv
                if (showLibassSettings) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_playback_enable_libass),
                        description = stringResource(Res.string.settings_playback_enable_libass_description),
                        checked = useLibass,
                        enabled = subtitleRenderingEnabled,
                        isTablet = isTablet,
                        onCheckedChange = PlayerSettingsRepository::setUseLibass,
                    )
                    if (useLibass) {
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsNavigationRow(
                            title = stringResource(Res.string.settings_playback_render_type),
                            description = libassRenderTypeLabel(libassRenderType),
                            enabled = subtitleRenderingEnabled,
                            isTablet = isTablet,
                            onClick = { showLibassRenderTypeDialog = true },
                        )
                    }
                }
            }
        }
    }

    if (showAddonSubtitleStartupModeDialog) {
        AddonSubtitleStartupModeDialog(
            selectedMode = autoPlayPlayerSettings.addonSubtitleStartupMode,
            onModeSelected = {
                PlayerSettingsRepository.setAddonSubtitleStartupMode(it)
                showAddonSubtitleStartupModeDialog = false
            },
            onDismiss = { showAddonSubtitleStartupModeDialog = false },
        )
    }

    if (showSubtitleTextColorDialog) {
        SubtitleColorDialog(
            title = stringResource(Res.string.settings_playback_subtitle_text_color),
            colors = SubtitleColorSwatches,
            selectedColor = autoPlayPlayerSettings.subtitleStyle.textColor,
            onColorSelected = { color ->
                PlayerSettingsRepository.setSubtitleStyle(autoPlayPlayerSettings.subtitleStyle.copy(textColor = color))
                showSubtitleTextColorDialog = false
            },
            onDismiss = { showSubtitleTextColorDialog = false },
        )
    }

    if (showSubtitleBackgroundColorDialog) {
        SubtitleColorDialog(
            title = stringResource(Res.string.settings_playback_subtitle_background_color),
            colors = SubtitleBackgroundColorSwatches,
            selectedColor = autoPlayPlayerSettings.subtitleStyle.backgroundColor,
            onColorSelected = { color ->
                PlayerSettingsRepository.setSubtitleStyle(autoPlayPlayerSettings.subtitleStyle.copy(backgroundColor = color))
                showSubtitleBackgroundColorDialog = false
            },
            onDismiss = { showSubtitleBackgroundColorDialog = false },
        )
    }

    if (showSubtitleOutlineColorDialog) {
        SubtitleColorDialog(
            title = stringResource(Res.string.settings_playback_subtitle_outline_color),
            colors = SubtitleColorSwatches,
            selectedColor = autoPlayPlayerSettings.subtitleStyle.outlineColor,
            onColorSelected = { color ->
                PlayerSettingsRepository.setSubtitleStyle(autoPlayPlayerSettings.subtitleStyle.copy(outlineColor = color))
                showSubtitleOutlineColorDialog = false
            },
            onDismiss = { showSubtitleOutlineColorDialog = false },
        )
    }

    if (showLibassRenderTypeDialog) {
        LibassRenderTypeDialog(
            selectedRenderType = libassRenderType,
            onRenderTypeSelected = { renderType ->
                PlayerSettingsRepository.setLibassRenderType(renderType)
                showLibassRenderTypeDialog = false
            },
            onDismiss = { showLibassRenderTypeDialog = false },
        )
    }

}
