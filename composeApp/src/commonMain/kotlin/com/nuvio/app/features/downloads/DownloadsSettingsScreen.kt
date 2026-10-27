package com.nuvio.app.features.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.settings.IosEnumSelectionDialog
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsGroupDivider
import com.nuvio.app.features.settings.SettingsNavigationRow
import com.nuvio.app.features.settings.SettingsSection
import com.nuvio.app.features.settings.SettingsSwitchRow
import com.nuvio.app.isDesktop
import com.nuvio.app.isIos
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Settings -> Downloads (Phase 9 stage 8), in the plan's order: the **Download Mode**, then the
 * profile's **Preferences**, then what belongs to **this device**, then which **sources** downloads
 * may use. The queue and everything already downloaded live in the Downloads screen.
 *
 * Built from the shared Settings rows (2026-10-16): it was a page of its own cards, dropdown chips and
 * bare switches, unlike every other Settings page, and on desktop it replaced the whole Settings screen.
 * Now each choice is a row showing its value, opening the same choice dialog Playback uses, and the
 * Settings tab draws it inline ([com.nuvio.app.features.settings.SettingsPage.Downloads]). The route
 * below remains for the Downloads screen's own settings button.
 *
 * Every label comes from `DownloadModeUi.kt`, which the setup wizard's download steps also read.
 */
@Composable
fun DownloadsSettingsScreen(
    onBack: () -> Unit,
) {
    NuvioScreen {
        stickyHeader {
            NuvioScreenHeader(
                title = stringResource(Res.string.downloads_settings_title),
                onBack = onBack,
            )
        }
        downloadsSettingsPageContent(isTablet = false)
    }
}

/** The page body for a settings list: reads the repositories itself. */
internal fun LazyListScope.downloadsSettingsPageContent(isTablet: Boolean) {
    item(key = "downloads-settings") {
        DownloadsSettingsPageBody(isTablet)
    }
}

@Composable
internal fun DownloadsSettingsPageBody(isTablet: Boolean) {
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
    DownloadsSettingsSections(
        isTablet = isTablet,
        addons = addonsState.addons.filter { it.enabled && it.manifest != null },
        policy = sourcePolicy,
        downloadPolicy = policy,
        effectiveMode = policy.effectiveMode(playerSettings.playbackMode.forDownloads()),
        deviceSettings = deviceSettings,
        onOpenFolder = {
            if (!DownloadsPlatformDownloader.openDownloadsDirectory()) {
                NuvioToastController.show(openDownloadsDirectoryFailedText)
            }
        },
    )
}

/** The stateless page, for the render harness. */
internal fun LazyListScope.downloadsSettingsContent(
    addons: List<ManagedAddon>,
    policy: DownloadSourcePolicy,
    downloadPolicy: DownloadPolicy,
    effectiveMode: DownloadMode,
    deviceSettings: DownloadDeviceSettings,
    onOpenFolder: () -> Unit,
    isTablet: Boolean = false,
) {
    item(key = "downloads-settings") {
        DownloadsSettingsSections(isTablet, addons, policy, downloadPolicy, effectiveMode, deviceSettings, onOpenFolder)
    }
}

private enum class DownloadsDialog { Mode, Resolution, Size, Fallback, PickRule, Range, MobileData, Concurrency }

@Composable
private fun DownloadsSettingsSections(
    isTablet: Boolean,
    addons: List<ManagedAddon>,
    policy: DownloadSourcePolicy,
    downloadPolicy: DownloadPolicy,
    effectiveMode: DownloadMode,
    deviceSettings: DownloadDeviceSettings,
    onOpenFolder: () -> Unit,
) {
    var dialog by remember { mutableStateOf<DownloadsDialog?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(if (isTablet) 22.dp else 18.dp)) {
        // --- Download Mode -------------------------------------------------------------------
        SettingsSection(title = stringResource(Res.string.downloads_settings_mode), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.downloads_settings_mode),
                    description = if (downloadPolicy.mode == null) {
                        downloadModeName(effectiveMode) + " · " + stringResource(Res.string.downloads_settings_mode_derived)
                    } else {
                        downloadModeName(effectiveMode)
                    },
                    isTablet = isTablet,
                                        onClick = { dialog = DownloadsDialog.Mode },
                )
            }
        }

        // --- Preferences ---------------------------------------------------------------------
        SettingsSection(title = stringResource(Res.string.downloads_settings_preferences), isTablet = isTablet) {
            SectionNote(stringResource(Res.string.downloads_settings_preferences_description), isTablet)
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.download_pref_resolution),
                    description = downloadResolutionLabel(downloadPolicy.preferredResolution),
                    isTablet = isTablet,
                                        onClick = { dialog = DownloadsDialog.Resolution },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.download_pref_size_level),
                    description = downloadSizeLevelLabel(downloadPolicy.sizeLevel) + " · " +
                        downloadSizeLevelDetail(downloadPolicy.sizeLevel, downloadPolicy.preferredResolution),
                    isTablet = isTablet,
                                        onClick = { dialog = DownloadsDialog.Size },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.download_pref_fallback),
                    description = downloadFallbackLabel(downloadPolicy.resolutionFallback),
                    isTablet = isTablet,
                                        onClick = { dialog = DownloadsDialog.Fallback },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.download_pref_pick_rule),
                    description = downloadPickRuleLabel(downloadPolicy.pickRule),
                    isTablet = isTablet,
                                        onClick = { dialog = DownloadsDialog.PickRule },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.download_pref_range),
                    description = downloadRangeLabel(downloadPolicy.range),
                    isTablet = isTablet,
                                        onClick = { dialog = DownloadsDialog.Range },
                )
            }
        }

        // --- On this device ------------------------------------------------------------------
        // Mobile data on phones, downloads at once where the app runs the transfers itself (iOS hands a
        // window to the system and ignores it), and the folder. Never synced.
        run {
            SettingsSection(title = stringResource(Res.string.downloads_settings_device), isTablet = isTablet) {
                SectionNote(stringResource(Res.string.downloads_settings_device_description), isTablet)
                SettingsGroup(isTablet = isTablet) {
                    var needsDivider = false
                    if (!isDesktop) {
                        SettingsNavigationRow(
                            title = stringResource(Res.string.download_pref_mobile_data),
                            description = downloadMobileDataLabel(deviceSettings.mobileData),
                            isTablet = isTablet,
                                                        onClick = { dialog = DownloadsDialog.MobileData },
                        )
                        needsDivider = true
                    }
                    if (!isIos) {
                        if (needsDivider) SettingsGroupDivider(isTablet = isTablet)
                        SettingsNavigationRow(
                            title = stringResource(Res.string.download_pref_concurrency),
                            description = deviceSettings.effectiveMaxConcurrent.toString(),
                            isTablet = isTablet,
                                                        onClick = { dialog = DownloadsDialog.Concurrency },
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsNavigationRow(
                            title = stringResource(Res.string.downloads_settings_storage),
                            description = stringResource(Res.string.downloads_settings_open_folder),
                            icon = Icons.Rounded.Folder,
                            isTablet = isTablet,
                            onClick = onOpenFolder,
                        )
                    }
                }
            }
        }

        // --- Sources: which addons downloads may use -----------------------------------------
        SettingsSection(title = stringResource(Res.string.downloads_allowed_sources), isTablet = isTablet) {
            SectionNote(stringResource(Res.string.downloads_allowed_sources_description), isTablet)
            if (addons.isEmpty()) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsNavigationRow(
                        title = stringResource(Res.string.downloads_no_enabled_sources),
                        description = null,
                        enabled = false,
                        isTablet = isTablet,
                        onClick = {},
                    )
                }
            } else {
                val enabledKeys = addons.mapNotNull { candidate ->
                    candidate.manifest?.let { AddonSourceKey(it.id, candidate.manifestUrl) }
                }.toSet()
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    addons.forEach { addon ->
                        AddonSourceGroup(addon, policy, enabledKeys, isTablet)
                    }
                }
            }
        }
    }

    when (dialog) {
        DownloadsDialog.Mode -> DownloadModeDialog(
            selected = effectiveMode,
            onSelect = {
                DownloadPolicyRepository.setMode(it)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        DownloadsDialog.Resolution -> IosEnumSelectionDialog(
            title = stringResource(Res.string.download_pref_resolution),
            options = DownloadResolutionPreference.entries,
            selected = downloadPolicy.preferredResolution,
            label = { downloadResolutionLabel(it) },
            onSelect = { choice ->
                DownloadPolicyRepository.update { it.copy(preferredResolution = choice) }
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        DownloadsDialog.Size -> IosEnumSelectionDialog(
            title = stringResource(Res.string.download_pref_size_level),
            options = DownloadSizeLevel.entries,
            selected = downloadPolicy.sizeLevel,
            label = { downloadSizeLevelLabel(it) },
            description = { downloadSizeLevelDetail(it, downloadPolicy.preferredResolution) },
            onSelect = { choice ->
                DownloadPolicyRepository.update { it.copy(sizeLevel = choice) }
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        DownloadsDialog.Fallback -> IosEnumSelectionDialog(
            title = stringResource(Res.string.download_pref_fallback),
            options = DownloadResolutionFallback.entries,
            selected = downloadPolicy.resolutionFallback,
            label = { downloadFallbackLabel(it) },
            onSelect = { choice ->
                DownloadPolicyRepository.update { it.copy(resolutionFallback = choice) }
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        DownloadsDialog.PickRule -> IosEnumSelectionDialog(
            title = stringResource(Res.string.download_pref_pick_rule),
            options = DownloadPickRule.entries,
            selected = downloadPolicy.pickRule,
            label = { downloadPickRuleLabel(it) },
            onSelect = { choice ->
                DownloadPolicyRepository.update { it.copy(pickRule = choice) }
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        DownloadsDialog.Range -> IosEnumSelectionDialog(
            title = stringResource(Res.string.download_pref_range),
            options = DownloadRange.entries,
            selected = downloadPolicy.range,
            label = { downloadRangeLabel(it) },
            onSelect = { choice ->
                DownloadPolicyRepository.update { it.copy(range = choice) }
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        DownloadsDialog.MobileData -> IosEnumSelectionDialog(
            title = stringResource(Res.string.download_pref_mobile_data),
            options = DownloadMobileDataRule.entries,
            selected = deviceSettings.mobileData,
            label = { downloadMobileDataLabel(it) },
            onSelect = { choice ->
                DownloadsRepository.updateDeviceSettings { it.copy(mobileData = choice) }
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        DownloadsDialog.Concurrency -> IosEnumSelectionDialog(
            title = stringResource(Res.string.download_pref_concurrency),
            options = (DownloadDeviceSettings.MIN_CONCURRENT..DownloadDeviceSettings.MAX_CONCURRENT).toList(),
            selected = deviceSettings.effectiveMaxConcurrent,
            label = { it.toString() },
            onSelect = { choice ->
                DownloadsRepository.updateDeviceSettings { it.copy(maxConcurrent = choice) }
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

/**
 * One addon as its own group: may downloads use it, whether it is an AIOStreams instance (detected ones
 * are locked on), and for an AIOStreams addon, which of its providers.
 */
@Composable
private fun AddonSourceGroup(
    addon: ManagedAddon,
    policy: DownloadSourcePolicy,
    enabledKeys: Set<AddonSourceKey>,
    isTablet: Boolean,
) {
    val manifest = requireNotNull(addon.manifest)
    val key = AddonSourceKey(manifest.id, addon.manifestUrl)
    val allowed = policy.allowsAddon(key)
    val automaticallyDetectedAio = AioStreamsSupport.isAioStreams(
        AioDetectionContext(manifest.id, manifest.name, addon.manifestUrl),
    )
    SettingsGroup(isTablet = isTablet) {
        SettingsSwitchRow(
            title = manifest.name.ifBlank { manifest.id },
            description = addonHost(addon.manifestUrl),
            checked = allowed,
            isTablet = isTablet,
            onCheckedChange = { DownloadsRepository.setAddonAllowed(key, it, enabledKeys) },
        )
        SettingsGroupDivider(isTablet = isTablet)
        SettingsSwitchRow(
            title = stringResource(Res.string.downloads_treat_as_aio),
            checked = automaticallyDetectedAio || key in policy.aioOverrides,
            enabled = allowed && !automaticallyDetectedAio,
            isTablet = isTablet,
            onCheckedChange = { DownloadsRepository.setAioOverride(key, it) },
        )
        policy.discoveredAioProviders[key]
            .orEmpty()
            .sorted()
            .forEach { provider ->
                val restriction = policy.allowedAioProviders[key]
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = provider,
                    description = manifest.name,
                    checked = restriction?.contains(provider) != false,
                    enabled = allowed,
                    isTablet = isTablet,
                    onCheckedChange = { DownloadsRepository.setAioProviderAllowed(key, provider, it) },
                )
            }
    }
}

/** "aiostreams.example.com" rather than the whole manifest URL, which carries config and tokens. */
private fun addonHost(manifestUrl: String): String =
    manifestUrl.substringAfter("://").substringBefore('/').ifBlank { manifestUrl }

@Composable
private fun SectionNote(text: String, isTablet: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = if (isTablet) 12.dp else 10.dp),
    )
}

/** The three modes as the same cards the setup wizard shows, like Playback's mode dialog. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun DownloadModeDialog(
    selected: DownloadMode,
    onSelect: (DownloadMode) -> Unit,
    onDismiss: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(Res.string.downloads_settings_mode),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DownloadModeOrder.forEach { mode ->
                        DownloadModeCard(mode = mode, isSelected = mode == selected, onClick = { onSelect(mode) })
                    }
                }
            }
        }
    }
}
