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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.ManagedAddon
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
        DownloadSectionTitle(stringResource(Res.string.download_presets_settings))
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

@Composable
private fun PresetSettingsCard(preset: DownloadPreset) {
    val resolutions = VideoResolution.entries
    val codecs = CodecPreference.entries
    val ranges = DynamicRangePolicy.entries
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(preset.name, style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.download_preset_resolution), Modifier.weight(1f))
                TextButton(
                    onClick = {
                        val index = resolutions.indexOf(preset.targetResolution)
                        DownloadsRepository.updatePreset(
                            preset.copy(targetResolution = resolutions[(index - 1).coerceAtLeast(0)]),
                        )
                    },
                ) { Text("−") }
                Text("${preset.targetResolution.height}p")
                TextButton(
                    onClick = {
                        val index = resolutions.indexOf(preset.targetResolution)
                        DownloadsRepository.updatePreset(
                            preset.copy(targetResolution = resolutions[(index + 1).coerceAtMost(resolutions.lastIndex)]),
                        )
                    },
                ) { Text("+") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.download_preset_size_limit), Modifier.weight(1f))
                TextButton(
                    onClick = {
                        DownloadsRepository.updatePreset(
                            preset.copy(gigabytesPerHourLimit = (preset.gigabytesPerHourLimit - 0.25).coerceAtLeast(0.25)),
                        )
                    },
                ) { Text("−") }
                Text("${preset.gigabytesPerHourLimit} GB/h")
                TextButton(
                    onClick = {
                        DownloadsRepository.updatePreset(
                            preset.copy(gigabytesPerHourLimit = preset.gigabytesPerHourLimit + 0.25),
                        )
                    },
                ) { Text("+") }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val index = codecs.indexOf(preset.codecPreference)
                        DownloadsRepository.updatePreset(
                            preset.copy(codecPreference = codecs[(index + 1) % codecs.size]),
                        )
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(Res.string.download_preset_codec), Modifier.weight(1f))
                Text(preset.codecPreference.name)
                Switch(
                    checked = preset.requirePreferredCodec,
                    onCheckedChange = {
                        DownloadsRepository.updatePreset(preset.copy(requirePreferredCodec = it))
                    },
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val index = ranges.indexOf(preset.dynamicRangePolicy)
                        DownloadsRepository.updatePreset(
                            preset.copy(dynamicRangePolicy = ranges[(index + 1) % ranges.size]),
                        )
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(Res.string.download_preset_hdr), Modifier.weight(1f))
                Text(preset.dynamicRangePolicy.name.replace('_', ' '))
            }
            // Which end of the size range to take among candidates that already pass
            // every other rule. It only ever picks within the cap, so this is "best
            // picture that fits" against "smallest that will do".
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        DownloadsRepository.updatePreset(
                            preset.copy(
                                sizePreference = when (preset.sizePreference) {
                                    SizePreference.LARGEST_UNDER_CAP -> SizePreference.SMALLEST
                                    SizePreference.SMALLEST -> SizePreference.LARGEST_UNDER_CAP
                                },
                            ),
                        )
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(Res.string.download_preset_size_preference), Modifier.weight(1f))
                Text(
                    when (preset.sizePreference) {
                        SizePreference.LARGEST_UNDER_CAP ->
                            stringResource(Res.string.download_preset_size_largest)
                        SizePreference.SMALLEST ->
                            stringResource(Res.string.download_preset_size_smallest)
                    },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.download_preset_prefer_cached), Modifier.weight(1f))
                Switch(
                    checked = preset.preferCachedSources,
                    onCheckedChange = {
                        DownloadsRepository.updatePreset(preset.copy(preferCachedSources = it))
                    },
                )
            }
            OutlinedTextField(
                value = preset.preferredAudioLanguage.orEmpty(),
                onValueChange = {
                    DownloadsRepository.updatePreset(
                        preset.copy(preferredAudioLanguage = it.trim().takeIf(String::isNotEmpty)),
                    )
                },
                label = { Text(stringResource(Res.string.download_preset_language)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.download_preset_require_language), Modifier.weight(1f))
                Switch(
                    checked = preset.requirePreferredAudioLanguage,
                    onCheckedChange = {
                        DownloadsRepository.updatePreset(preset.copy(requirePreferredAudioLanguage = it))
                    },
                )
            }
        }
    }
}
