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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioDropdownChip
import com.nuvio.app.core.ui.NuvioDropdownOption
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.isDesktop
import com.nuvio.app.isIos
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Settings -> Downloads (Phase 9 stage 8), in the plan's order: the **Download Mode**, then the
 * profile's **Preferences**, then what belongs to **this device**, then **Advanced** (which addons
 * downloads may use). The queue and everything already downloaded live in the Downloads screen.
 *
 * The retired presets are gone from here; `DownloadPolicyMigration` carried an upgraded user's last
 * preset into the Preferences. Every label comes from `DownloadModeUi.kt`, which the setup wizard's
 * download steps also read.
 */
@Composable
fun DownloadsSettingsScreen(
    onBack: () -> Unit,
) {
    val sourcePolicy by remember {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.sourcePolicy
    }.collectAsStateWithLifecycle()
    val policy by remember {
        DownloadPolicyRepository.ensureLoaded()
        DownloadPolicyRepository.policy
    }.collectAsStateWithLifecycle()
    val playerSettings by remember {
        PlayerSettingsRepository.ensureLoaded()
        PlayerSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val deviceSettings by DownloadsRepository.deviceSettings.collectAsStateWithLifecycle()
    val addonsState by AddonRepository.uiState.collectAsStateWithLifecycle()
    val openDownloadsDirectoryFailedText = stringResource(Res.string.downloads_open_directory_failed)
    val openFolder = {
        if (!DownloadsPlatformDownloader.openDownloadsDirectory()) {
            NuvioToastController.show(openDownloadsDirectoryFailedText)
        }
    }

    NuvioScreen {
        stickyHeader {
            NuvioScreenHeader(
                title = stringResource(Res.string.downloads_settings_title),
                onBack = onBack,
                actions = {
                    IconButton(onClick = openFolder) {
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
            downloadPolicy = policy,
            effectiveMode = policy.effectiveMode(playerSettings.playbackMode.forDownloads()),
            deviceSettings = deviceSettings,
            onOpenFolder = openFolder,
        )
    }
}

internal fun LazyListScope.downloadsSettingsContent(
    addons: List<ManagedAddon>,
    policy: DownloadSourcePolicy,
    downloadPolicy: DownloadPolicy,
    effectiveMode: DownloadMode,
    deviceSettings: DownloadDeviceSettings,
    onOpenFolder: () -> Unit,
) {
    // --- Download Mode ---------------------------------------------------------------------
    item(key = "mode_title") {
        DownloadSectionTitle(stringResource(Res.string.downloads_settings_mode))
    }
    if (downloadPolicy.mode == null) {
        item(key = "mode_derived") {
            SettingsNote(stringResource(Res.string.downloads_settings_mode_derived))
        }
    }
    items(DownloadModeOrder, key = { "mode-${it.name}" }) { mode ->
        DownloadModeCard(
            mode = mode,
            isSelected = mode == effectiveMode,
            onClick = { DownloadPolicyRepository.setMode(mode) },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }

    // --- Preferences -----------------------------------------------------------------------
    item(key = "preferences_title") {
        DownloadSectionTitle(stringResource(Res.string.downloads_settings_preferences))
        SettingsNote(stringResource(Res.string.downloads_settings_preferences_description))
    }
    item(key = "preferences") {
        DownloadPreferencesCard(downloadPolicy)
    }

    // --- On this device --------------------------------------------------------------------
    item(key = "device_title") {
        DownloadSectionTitle(stringResource(Res.string.downloads_settings_device))
        SettingsNote(stringResource(Res.string.downloads_settings_device_description))
    }
    item(key = "device") {
        DownloadDeviceCard(deviceSettings, onOpenFolder)
    }

    // --- Advanced: which addons downloads may use ------------------------------------------
    item(key = "advanced_title") {
        DownloadSectionTitle(stringResource(Res.string.downloads_settings_advanced))
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
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

/** The profile's preferences: what Automatic picks by, and what Assisted shows per resolution. */
@Composable
private fun DownloadPreferencesCard(policy: DownloadPolicy) {
    NuvioSurfaceCard(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12)) {
            DownloadPickerRow(
                title = stringResource(Res.string.download_pref_resolution),
                selectedKey = policy.preferredResolution.name,
                label = downloadResolutionLabel(policy.preferredResolution),
                options = DownloadResolutionPreference.entries.map {
                    NuvioDropdownOption(it.name, downloadResolutionLabel(it))
                },
                onSelected = { key ->
                    DownloadPolicyRepository.update { it.copy(preferredResolution = DownloadResolutionPreference.valueOf(key)) }
                },
            )
            DownloadPickerRow(
                title = stringResource(Res.string.download_pref_size_level),
                description = downloadSizeLevelDetail(policy.sizeLevel, policy.preferredResolution),
                selectedKey = policy.sizeLevel.name,
                label = downloadSizeLevelLabel(policy.sizeLevel),
                options = DownloadSizeLevel.entries.map { NuvioDropdownOption(it.name, downloadSizeLevelLabel(it)) },
                onSelected = { key ->
                    DownloadPolicyRepository.update { it.copy(sizeLevel = DownloadSizeLevel.valueOf(key)) }
                },
            )
            DownloadPickerRow(
                title = stringResource(Res.string.download_pref_fallback),
                selectedKey = policy.resolutionFallback.name,
                label = downloadFallbackLabel(policy.resolutionFallback),
                options = DownloadResolutionFallback.entries.map { NuvioDropdownOption(it.name, downloadFallbackLabel(it)) },
                onSelected = { key ->
                    DownloadPolicyRepository.update { it.copy(resolutionFallback = DownloadResolutionFallback.valueOf(key)) }
                },
            )
            DownloadPickerRow(
                title = stringResource(Res.string.download_pref_pick_rule),
                selectedKey = policy.pickRule.name,
                label = downloadPickRuleLabel(policy.pickRule),
                options = DownloadPickRule.entries.map { NuvioDropdownOption(it.name, downloadPickRuleLabel(it)) },
                onSelected = { key ->
                    DownloadPolicyRepository.update { it.copy(pickRule = DownloadPickRule.valueOf(key)) }
                },
            )
            DownloadPickerRow(
                title = stringResource(Res.string.download_pref_range),
                selectedKey = policy.range.name,
                label = downloadRangeLabel(policy.range),
                options = DownloadRange.entries.map { NuvioDropdownOption(it.name, downloadRangeLabel(it)) },
                onSelected = { key ->
                    DownloadPolicyRepository.update { it.copy(range = DownloadRange.valueOf(key)) }
                },
            )
        }
    }
}

/**
 * This device only, never synced: mobile data on phones, downloads at once where the app runs the
 * transfers itself (iOS hands a window to the system and ignores it), and the folder.
 */
@Composable
private fun DownloadDeviceCard(settings: DownloadDeviceSettings, onOpenFolder: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    NuvioSurfaceCard(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12)) {
            if (!isDesktop) {
                DownloadPickerRow(
                    title = stringResource(Res.string.download_pref_mobile_data),
                    description = stringResource(Res.string.download_mobile_data_detail),
                    selectedKey = settings.mobileData.name,
                    label = downloadMobileDataLabel(settings.mobileData),
                    options = DownloadMobileDataRule.entries.map { NuvioDropdownOption(it.name, downloadMobileDataLabel(it)) },
                    onSelected = { key ->
                        DownloadsRepository.updateDeviceSettings { it.copy(mobileData = DownloadMobileDataRule.valueOf(key)) }
                    },
                )
            }
            if (!isIos) {
                val counts = (DownloadDeviceSettings.MIN_CONCURRENT..DownloadDeviceSettings.MAX_CONCURRENT).toList()
                DownloadPickerRow(
                    title = stringResource(Res.string.download_pref_concurrency),
                    selectedKey = settings.effectiveMaxConcurrent.toString(),
                    label = settings.effectiveMaxConcurrent.toString(),
                    options = counts.map { NuvioDropdownOption(it.toString(), it.toString()) },
                    onSelected = { key ->
                        DownloadsRepository.updateDeviceSettings { it.copy(maxConcurrent = key.toInt()) }
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenFolder),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
                ) {
                    Text(
                        text = stringResource(Res.string.downloads_settings_storage),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        color = tokens.colors.textPrimary,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(Res.string.downloads_settings_open_folder),
                        style = MaterialTheme.typography.labelLarge,
                        color = tokens.colors.accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

@Composable
private fun DownloadPickerRow(
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
