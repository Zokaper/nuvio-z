package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.language.AudioLanguageOption
import com.nuvio.app.core.language.LanguagePreferences
import com.nuvio.app.core.language.OtherLanguageAudio
import com.nuvio.app.core.language.SubtitleKind
import com.nuvio.app.core.language.normalizeLanguageCode
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.language.LanguagePreferencesRepository
import com.nuvio.app.features.player.AvailableLanguageOptions
import com.nuvio.app.features.player.DeviceLanguagePreferences
import com.nuvio.app.features.player.languageLabelForCode
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_playback_option_device_language
import nuvio.composeapp.generated.resources.settings_playback_option_none
import nuvio.composeapp.generated.resources.settings_playback_section_language
import nuvio.composeapp.generated.resources.subtitle_language_unknown
import nuvio.composeapp.generated.resources.zlang_audio
import nuvio.composeapp.generated.resources.zlang_audio_footer
import nuvio.composeapp.generated.resources.zlang_audio_mine_body
import nuvio.composeapp.generated.resources.zlang_audio_original
import nuvio.composeapp.generated.resources.zlang_audio_original_body
import nuvio.composeapp.generated.resources.zlang_example_other_first
import nuvio.composeapp.generated.resources.zlang_example_other_second
import nuvio.composeapp.generated.resources.zlang_example_other_sound
import nuvio.composeapp.generated.resources.zlang_example_other_speaker
import nuvio.composeapp.generated.resources.zlang_example_own_first
import nuvio.composeapp.generated.resources.zlang_example_own_second
import nuvio.composeapp.generated.resources.zlang_example_own_sound
import nuvio.composeapp.generated.resources.zlang_example_own_speaker
import nuvio.composeapp.generated.resources.zlang_fallback_language
import nuvio.composeapp.generated.resources.zlang_fallback_none
import nuvio.composeapp.generated.resources.zlang_my_language
import nuvio.composeapp.generated.resources.zlang_no_subtitle
import nuvio.composeapp.generated.resources.zlang_said_in
import nuvio.composeapp.generated.resources.zlang_said_in_another
import nuvio.composeapp.generated.resources.zlang_said_in_original
import nuvio.composeapp.generated.resources.zlang_same_as_my_language
import nuvio.composeapp.generated.resources.zlang_same_as_my_language_value
import nuvio.composeapp.generated.resources.zlang_sub_forced
import nuvio.composeapp.generated.resources.zlang_sub_forced_body
import nuvio.composeapp.generated.resources.zlang_sub_full
import nuvio.composeapp.generated.resources.zlang_sub_full_body
import nuvio.composeapp.generated.resources.zlang_sub_off
import nuvio.composeapp.generated.resources.zlang_sub_off_body
import nuvio.composeapp.generated.resources.zlang_sub_sdh
import nuvio.composeapp.generated.resources.zlang_sub_sdh_body
import nuvio.composeapp.generated.resources.zlang_subtitle_language
import nuvio.composeapp.generated.resources.zlang_subtitles
import nuvio.composeapp.generated.resources.zlang_titles_in
import nuvio.composeapp.generated.resources.zlang_titles_other
import org.jetbrains.compose.resources.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Z: the language settings (`core/language/TitleLanguagePlan.kt`) as three settings sections -
 * Languages, Titles in my language, Titles in other languages. Settings → Playback and the setup
 * wizard's Language step both draw this, so the two can never describe the model differently.
 *
 * Picked from `LanguageWhatsNewDesignHarness` round 3 (2026-10-20): subtitles are a list, not a
 * segmented control, and every choice shows the same two moments of one scene so Forced (only the
 * second) and Full (both) differ at a glance.
 */
@Composable
internal fun LanguageSettingsSections(isTablet: Boolean, enabled: Boolean = true) {
    val preferences by LanguagePreferencesRepository.uiState.collectAsStateWithLifecycle()
    val myLanguageName = myLanguageName(preferences)
    var dialog by remember { mutableStateOf<LanguageDialog?>(null) }

    SettingsSection(title = stringResource(Res.string.settings_playback_section_language), isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            SettingsNavigationRow(
                title = stringResource(Res.string.zlang_my_language),
                description = if (normalizeLanguageCode(preferences.myLanguage) == AudioLanguageOption.DEVICE) {
                    stringResource(Res.string.settings_playback_option_device_language) + " (" + myLanguageName + ")"
                } else {
                    myLanguageName
                },
                enabled = enabled,
                isTablet = isTablet,
                onClick = { dialog = LanguageDialog.Mine },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsNavigationRow(
                title = stringResource(Res.string.zlang_fallback_language),
                description = preferences.fallbackLanguage?.let { languageLabelForCode(it) }
                    ?: stringResource(Res.string.zlang_fallback_none, myLanguageName),
                enabled = enabled,
                isTablet = isTablet,
                onClick = { dialog = LanguageDialog.Fallback },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsNavigationRow(
                title = stringResource(Res.string.zlang_subtitle_language),
                description = preferences.subtitleLanguage?.let { languageLabelForCode(it) }
                    ?: stringResource(Res.string.zlang_same_as_my_language_value, myLanguageName),
                enabled = enabled,
                isTablet = isTablet,
                onClick = { dialog = LanguageDialog.Subtitles },
            )
        }
    }

    SettingsSection(title = stringResource(Res.string.zlang_titles_in, myLanguageName), isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            SubtitleKindChoices(
                selected = preferences.ownSubtitles,
                scene = ownScene(myLanguageName),
                enabled = enabled,
                onSelect = { kind -> LanguagePreferencesRepository.update { it.copy(ownSubtitles = kind) } },
            )
        }
    }

    SettingsSection(title = stringResource(Res.string.zlang_titles_other), isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            OtherAudioChoices(
                selected = preferences.otherAudio,
                myLanguageName = myLanguageName,
                enabled = enabled,
                onSelect = { audio -> LanguagePreferencesRepository.update { it.copy(otherAudio = audio) } },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SubtitleKindChoices(
                selected = preferences.otherSubtitles,
                scene = otherScene(),
                enabled = enabled,
                onSelect = { kind -> LanguagePreferencesRepository.update { it.copy(otherSubtitles = kind) } },
            )
        }
    }

    when (dialog) {
        LanguageDialog.Mine -> LanguageSelectionDialog(
            title = stringResource(Res.string.zlang_my_language),
            options = listOf(
                LanguageSelectionOption(AudioLanguageOption.DEVICE, stringResource(Res.string.settings_playback_option_device_language)),
            ) + languageOptions(),
            selectedValue = preferences.myLanguage,
            onSelect = { value ->
                LanguagePreferencesRepository.update { it.copy(myLanguage = value ?: AudioLanguageOption.DEVICE) }
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        LanguageDialog.Fallback -> LanguageSelectionDialog(
            title = stringResource(Res.string.zlang_fallback_language),
            options = listOf(LanguageSelectionOption(null, stringResource(Res.string.settings_playback_option_none))) + languageOptions(),
            selectedValue = preferences.fallbackLanguage,
            onSelect = { value ->
                LanguagePreferencesRepository.update { it.copy(fallbackLanguage = value) }
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        LanguageDialog.Subtitles -> LanguageSelectionDialog(
            title = stringResource(Res.string.zlang_subtitle_language),
            options = listOf(LanguageSelectionOption(null, stringResource(Res.string.zlang_same_as_my_language))) + languageOptions(),
            selectedValue = preferences.subtitleLanguage,
            onSelect = { value ->
                LanguagePreferencesRepository.update { it.copy(subtitleLanguage = value) }
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

private enum class LanguageDialog { Mine, Fallback, Subtitles }

@Composable
private fun languageOptions(): List<LanguageSelectionOption> =
    AvailableLanguageOptions.map { option -> LanguageSelectionOption(option.code, stringResource(option.labelRes)) }

/** My language's name, with "Device language" resolved to the language it currently means. */
@Composable
private fun myLanguageName(preferences: LanguagePreferences): String {
    val code = if (normalizeLanguageCode(preferences.myLanguage) == AudioLanguageOption.DEVICE) {
        DeviceLanguagePreferences.preferredLanguageCodes().firstOrNull()
    } else {
        preferences.myLanguage
    }
    // A device reports a regional code ("en-US") that the label table may only know by its language.
    val unknown = stringResource(Res.string.subtitle_language_unknown)
    val label = languageLabelForCode(code)
    return if (label == unknown && code != null) languageLabelForCode(code.replace('_', '-').substringBefore('-')) else label
}

/** Two moments of one scene, as column headers and what each [SubtitleKind] shows for them. */
private class SubtitleScene(val first: String, val second: String, val lines: Map<SubtitleKind, Pair<String?, String?>>)

@Composable
private fun ownScene(myLanguageName: String): SubtitleScene {
    val first = stringResource(Res.string.zlang_example_own_first)
    val second = stringResource(Res.string.zlang_example_own_second)
    val sound = stringResource(Res.string.zlang_example_own_sound)
    val speaker = stringResource(Res.string.zlang_example_own_speaker)
    return SubtitleScene(
        first = stringResource(Res.string.zlang_said_in, myLanguageName),
        second = stringResource(Res.string.zlang_said_in_another),
        lines = mapOf(
            SubtitleKind.OFF to (null to null),
            SubtitleKind.FORCED to (null to second),
            SubtitleKind.FULL to (first to second),
            SubtitleKind.SDH to ("$sound $first" to "$speaker $second"),
        ),
    )
}

@Composable
private fun otherScene(): SubtitleScene {
    val first = stringResource(Res.string.zlang_example_other_first)
    val second = stringResource(Res.string.zlang_example_other_second)
    val sound = stringResource(Res.string.zlang_example_other_sound)
    val speaker = stringResource(Res.string.zlang_example_other_speaker)
    return SubtitleScene(
        first = stringResource(Res.string.zlang_said_in_original),
        second = stringResource(Res.string.zlang_said_in_another),
        lines = mapOf(
            SubtitleKind.OFF to (null to null),
            SubtitleKind.FORCED to (null to second),
            SubtitleKind.FULL to (first to second),
            SubtitleKind.SDH to ("$sound $first" to "$speaker $second"),
        ),
    )
}

@Composable
private fun SubtitleKindChoices(
    selected: SubtitleKind,
    scene: SubtitleScene,
    enabled: Boolean,
    onSelect: (SubtitleKind) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val choices = listOf(
        Triple(SubtitleKind.OFF, Res.string.zlang_sub_off, Res.string.zlang_sub_off_body),
        Triple(SubtitleKind.FORCED, Res.string.zlang_sub_forced, Res.string.zlang_sub_forced_body),
        Triple(SubtitleKind.FULL, Res.string.zlang_sub_full, Res.string.zlang_sub_full_body),
        Triple(SubtitleKind.SDH, Res.string.zlang_sub_sdh, Res.string.zlang_sub_sdh_body),
    )
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Cells beside the names only where both fit: two 220dp cells need ~720dp of row.
        val cellsBeside = maxWidth >= 720.dp
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
                Text(
                    stringResource(Res.string.zlang_subtitles),
                    style = MaterialTheme.typography.bodyLarge,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                if (cellsBeside) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SceneHeader(scene.first, Modifier.width(220.dp))
                        SceneHeader(scene.second, Modifier.width(220.dp))
                    }
                }
            }
            if (!cellsBeside) {
                Row(Modifier.fillMaxWidth().padding(start = 48.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SceneHeader(scene.first, Modifier.weight(1f))
                    SceneHeader(scene.second, Modifier.weight(1f))
                }
            }
            choices.forEach { (kind, name, meaning) ->
                val (first, second) = scene.lines.getValue(kind)
                ChoiceRow(
                    selected = kind == selected,
                    enabled = enabled,
                    title = stringResource(name),
                    body = stringResource(meaning),
                    onClick = { onSelect(kind) },
                    below = if (cellsBeside) null else {
                        {
                            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SubtitleCell(first, Modifier.weight(1f))
                                SubtitleCell(second, Modifier.weight(1f))
                            }
                        }
                    },
                    trailing = if (!cellsBeside) null else {
                        {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SubtitleCell(first, Modifier.width(220.dp))
                                SubtitleCell(second, Modifier.width(220.dp))
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun OtherAudioChoices(
    selected: OtherLanguageAudio,
    myLanguageName: String,
    enabled: Boolean,
    onSelect: (OtherLanguageAudio) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            stringResource(Res.string.zlang_audio),
            style = MaterialTheme.typography.bodyLarge,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
        ChoiceRow(
            selected = selected == OtherLanguageAudio.ORIGINAL,
            enabled = enabled,
            title = stringResource(Res.string.zlang_audio_original),
            body = stringResource(Res.string.zlang_audio_original_body),
            onClick = { onSelect(OtherLanguageAudio.ORIGINAL) },
        )
        ChoiceRow(
            selected = selected == OtherLanguageAudio.MY_LANGUAGE,
            enabled = enabled,
            title = myLanguageName,
            body = stringResource(Res.string.zlang_audio_mine_body, myLanguageName),
            onClick = { onSelect(OtherLanguageAudio.MY_LANGUAGE) },
        )
        Text(
            stringResource(Res.string.zlang_audio_footer, myLanguageName),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
            modifier = Modifier.padding(start = 48.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun ChoiceRow(
    selected: Boolean,
    enabled: Boolean,
    title: String,
    body: String,
    onClick: () -> Unit,
    below: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) tokens.colors.overlaySelected else Color.Transparent)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioDot(selected)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) tokens.colors.textPrimary else tokens.colors.textDisabled,
                fontWeight = FontWeight.SemiBold,
            )
            Text(body, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
            below?.invoke()
        }
        trailing?.invoke()
    }
}

@Composable
private fun RadioDot(selected: Boolean) {
    val tokens = MaterialTheme.nuvio
    Box(
        Modifier.size(20.dp).clip(CircleShape)
            .border(2.dp, if (selected) tokens.colors.accent else tokens.colors.borderStrong, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(tokens.colors.accent))
    }
}

@Composable
private fun SceneHeader(text: String, modifier: Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.nuvio.colors.textMuted,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** One moment of the scene as the player would draw it: the subtitle, or nothing. */
@Composable
private fun SubtitleCell(line: String?, modifier: Modifier) {
    Box(
        modifier.height(46.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (line != null) {
            Text(
                line,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(stringResource(Res.string.zlang_no_subtitle), color = Color.White.copy(alpha = 0.3f), fontSize = 11.sp)
        }
    }
}
