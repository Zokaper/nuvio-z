package com.nuvio.app.features.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioActionLabel
import com.nuvio.app.core.ui.NuvioDropdownChip
import com.nuvio.app.core.ui.NuvioDropdownOption
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.settings.calculateSteps
import com.nuvio.app.features.settings.snapToStep
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Download configuration: presets and the addons that automatic discovery may use.
 * The queue and everything already on the device live in the Downloads tab instead.
 */
@Composable
fun DownloadsSettingsScreen(
    onBack: () -> Unit,
) {
    val sourcePolicy by remember {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.sourcePolicy
    }.collectAsStateWithLifecycle()
    val presets by DownloadsRepository.presets.collectAsStateWithLifecycle()
    val addonsState by AddonRepository.uiState.collectAsStateWithLifecycle()
    val openDownloadsDirectoryFailedText = stringResource(Res.string.downloads_open_directory_failed)

    NuvioScreen {
        stickyHeader {
            NuvioScreenHeader(
                title = stringResource(Res.string.downloads_settings_title),
                onBack = onBack,
                actions = {
                    IconButton(
                        onClick = {
                            if (!DownloadsPlatformDownloader.openDownloadsDirectory()) {
                                NuvioToastController.show(openDownloadsDirectoryFailedText)
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Folder,
                            contentDescription = stringResource(Res.string.downloads_open_directory),
                        )
                    }
                },
            )
        }

        downloadsSettingsContent(
            addons = addonsState.addons.filter { it.enabled && it.manifest != null },
            policy = sourcePolicy,
            presets = presets,
        )
    }
}

private fun LazyListScope.downloadsSettingsContent(
    addons: List<ManagedAddon>,
    policy: DownloadSourcePolicy,
    presets: List<DownloadPreset>,
) {
    item {
        var confirmingReset by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DownloadSectionTitle(stringResource(Res.string.download_presets_settings))
            NuvioActionLabel(
                text = stringResource(Res.string.download_presets_reset),
                modifier = Modifier.padding(horizontal = 14.dp),
                onClick = { confirmingReset = true },
            )
        }
        Text(
            text = stringResource(Res.string.download_presets_settings_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        NuvioStatusModal(
            title = stringResource(Res.string.download_presets_reset),
            message = stringResource(Res.string.download_presets_reset_confirm),
            isVisible = confirmingReset,
            confirmText = stringResource(Res.string.action_reset),
            dismissText = stringResource(Res.string.action_cancel),
            onConfirm = {
                DownloadsRepository.resetPresets()
                confirmingReset = false
            },
            onDismiss = { confirmingReset = false },
        )
    }
    items(presets, key = { "preset-${it.id}" }) { preset ->
        PresetSettingsCard(preset)
    }
    item {
        DownloadSectionTitle(stringResource(Res.string.downloads_allowed_sources))
    }
    item {
        Text(
            text = stringResource(Res.string.downloads_allowed_sources_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
    if (addons.isEmpty()) {
        item {
            Text(
                text = stringResource(Res.string.downloads_no_enabled_sources),
                modifier = Modifier.padding(20.dp),
            )
        }
        return
    }
    items(addons, key = { it.manifestUrl }) { addon ->
        val manifest = requireNotNull(addon.manifest)
        val key = AddonSourceKey(manifest.id, addon.manifestUrl)
        val enabledKeys = addons.mapNotNull { candidate ->
            candidate.manifest?.let { AddonSourceKey(it.id, candidate.manifestUrl) }
        }.toSet()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    DownloadsRepository.setAddonAllowed(
                        key = key,
                        allowed = !policy.allowsAddon(key),
                        enabledKeys = enabledKeys,
                    )
                }
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(manifest.id, style = MaterialTheme.typography.titleSmall)
                    Text(
                        addon.manifestUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(
                    checked = policy.allowsAddon(key),
                    onCheckedChange = {
                        DownloadsRepository.setAddonAllowed(key, it, enabledKeys)
                    },
                )
            }
            val automaticallyDetectedAio = AioStreamsSupport.isAioStreams(
                AioDetectionContext(manifest.id, manifest.name, addon.manifestUrl),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(Res.string.downloads_treat_as_aio),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = automaticallyDetectedAio || key in policy.aioOverrides,
                    enabled = !automaticallyDetectedAio,
                    onCheckedChange = { DownloadsRepository.setAioOverride(key, it) },
                )
            }
            policy.discoveredAioProviders[key]
                .orEmpty()
                .sorted()
                .forEach { provider ->
                    val restriction = policy.allowedAioProviders[key]
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp),
                    ) {
                        Text(
                            text = "${manifest.name} › $provider",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = restriction?.contains(provider) != false,
                            onCheckedChange = {
                                DownloadsRepository.setAioProviderAllowed(key, provider, it)
                            },
                        )
                    }
                }
        }
    }
}

/**
 * One preset, edited in place.
 *
 * Every choice is a labelled control that says what it currently is: the page used to
 * be `−`/`+` steppers and rows that silently cycled an enum when tapped, printing raw
 * names like `AVOID_HDR` at the end of them.
 */
@Composable
private fun PresetSettingsCard(preset: DownloadPreset) {
    val tokens = MaterialTheme.nuvio
    NuvioSurfaceCard(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12)) {
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2)) {
                Text(
                    text = preset.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = preset.summaryLine(),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            }

            PresetPickerRow(
                title = stringResource(Res.string.download_preset_resolution),
                selectedKey = preset.targetResolution.name,
                label = preset.targetResolution.presetLabel(),
                options = VideoResolution.entries.map { resolution ->
                    NuvioDropdownOption(resolution.name, resolution.presetLabel())
                },
                onSelected = { key ->
                    DownloadsRepository.updatePreset(
                        preset.copy(targetResolution = VideoResolution.valueOf(key)),
                    )
                },
            )

            PresetSizeLimitControl(preset)

            PresetPickerRow(
                title = stringResource(Res.string.download_preset_codec_label),
                selectedKey = preset.codecPreference.name,
                label = preset.codecPreference.presetLabel(),
                options = CodecPreference.entries.map { codec ->
                    NuvioDropdownOption(codec.name, codec.presetLabel())
                },
                onSelected = { key ->
                    DownloadsRepository.updatePreset(
                        preset.copy(codecPreference = CodecPreference.valueOf(key)),
                    )
                },
            )
            PresetSwitchRow(
                title = stringResource(Res.string.download_preset_codec_require),
                description = stringResource(Res.string.download_preset_codec_require_description),
                checked = preset.requirePreferredCodec,
                enabled = preset.codecPreference != CodecPreference.ANY,
                onCheckedChange = {
                    DownloadsRepository.updatePreset(preset.copy(requirePreferredCodec = it))
                },
            )

            PresetPickerRow(
                title = stringResource(Res.string.download_preset_hdr),
                selectedKey = preset.dynamicRangePolicy.name,
                label = preset.dynamicRangePolicy.presetLabel(),
                options = DynamicRangePolicy.entries.map { policy ->
                    NuvioDropdownOption(policy.name, policy.presetLabel())
                },
                onSelected = { key ->
                    DownloadsRepository.updatePreset(
                        preset.copy(dynamicRangePolicy = DynamicRangePolicy.valueOf(key)),
                    )
                },
            )

            // Which end of the size range to take among candidates that already pass
            // every other rule. It only ever picks within the cap, so this runs from
            // "best picture that fits" through the middle to "smallest that will do".
            PresetPickerRow(
                title = stringResource(Res.string.download_preset_size_preference),
                description = preset.sizePreference.presetDescription(),
                selectedKey = preset.sizePreference.name,
                label = preset.sizePreference.presetLabel(),
                options = SizePreference.entries.map { preference ->
                    NuvioDropdownOption(preference.name, preference.presetLabel())
                },
                onSelected = { key ->
                    DownloadsRepository.updatePreset(
                        preset.copy(sizePreference = SizePreference.valueOf(key)),
                    )
                },
            )

            PresetSwitchRow(
                title = stringResource(Res.string.download_preset_prefer_cached),
                description = stringResource(Res.string.download_preset_prefer_cached_description),
                checked = preset.preferCachedSources,
                onCheckedChange = {
                    DownloadsRepository.updatePreset(preset.copy(preferCachedSources = it))
                },
            )

            Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
                Text(
                    text = stringResource(Res.string.download_preset_language),
                    style = MaterialTheme.typography.bodyLarge,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.Medium,
                )
                NuvioInputField(
                    value = preset.preferredAudioLanguage.orEmpty(),
                    onValueChange = {
                        DownloadsRepository.updatePreset(
                            preset.copy(preferredAudioLanguage = it.trim().takeIf(String::isNotEmpty)),
                        )
                    },
                    placeholder = stringResource(Res.string.download_preset_language_placeholder),
                )
            }
            PresetSwitchRow(
                title = stringResource(Res.string.download_preset_require_language),
                description = stringResource(Res.string.download_preset_require_language_description),
                checked = preset.requirePreferredAudioLanguage,
                enabled = !preset.preferredAudioLanguage.isNullOrBlank(),
                onCheckedChange = {
                    DownloadsRepository.updatePreset(preset.copy(requirePreferredAudioLanguage = it))
                },
            )
        }
    }
}

/** The GB/hour cap, with what it works out to for a typical episode and film. */
@Composable
private fun PresetSizeLimitControl(preset: DownloadPreset) {
    val tokens = MaterialTheme.nuvio
    var sliderValue by remember(preset.id, preset.gigabytesPerHourLimit) {
        mutableFloatStateOf(preset.gigabytesPerHourLimit.toFloat())
    }
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.download_preset_size_limit),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(
                    Res.string.download_preset_size_limit_value,
                    formatGigabytes(sliderValue.toDouble()),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.accent,
            )
        }
        Slider(
            value = sliderValue.coerceIn(SIZE_LIMIT_MIN, SIZE_LIMIT_MAX),
            onValueChange = { sliderValue = snapToStep(it, SIZE_LIMIT_STEP) },
            onValueChangeFinished = {
                DownloadsRepository.updatePreset(
                    preset.copy(
                        gigabytesPerHourLimit = sliderValue
                            .coerceIn(SIZE_LIMIT_MIN, SIZE_LIMIT_MAX)
                            .toDouble(),
                    ),
                )
            },
            valueRange = SIZE_LIMIT_MIN..SIZE_LIMIT_MAX,
            steps = calculateSteps(SIZE_LIMIT_MIN, SIZE_LIMIT_MAX, SIZE_LIMIT_STEP),
            colors = SliderDefaults.colors(
                thumbColor = tokens.colors.accent,
                activeTrackColor = tokens.colors.accent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        val previewPreset = preset.copy(gigabytesPerHourLimit = sliderValue.toDouble())
        Text(
            text = stringResource(
                Res.string.download_preset_size_limit_helper,
                formatDownloadBytes(previewPreset.sizeCapBytes(45, isEpisode = true)),
                formatDownloadBytes(previewPreset.sizeCapBytes(120, isEpisode = false)),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
        )
    }
}

private const val SIZE_LIMIT_MIN = 0.25f
private const val SIZE_LIMIT_MAX = 20f
private const val SIZE_LIMIT_STEP = 0.25f

@Composable
private fun PresetPickerRow(
    title: String,
    selectedKey: String,
    label: String,
    options: List<NuvioDropdownOption>,
    onSelected: (String) -> Unit,
    description: String? = null,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.Medium,
            )
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            }
        }
        NuvioDropdownChip(
            title = title,
            label = label,
            selectedKey = selectedKey,
            options = options,
            onSelected = { onSelected(it.key) },
        )
    }
}

@Composable
private fun PresetSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .alpha(if (enabled) NuvioTokens.Opacity.visible else tokens.opacity.medium),
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = tokens.colors.onAccent,
                checkedTrackColor = tokens.colors.accent,
                uncheckedThumbColor = tokens.colors.textMuted,
                uncheckedTrackColor = tokens.colors.borderDefault,
            ),
        )
    }
}
