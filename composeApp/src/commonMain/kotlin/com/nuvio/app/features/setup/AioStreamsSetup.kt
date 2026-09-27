package com.nuvio.app.features.setup

import com.nuvio.app.features.addons.AddAddonResult
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.ManagedAddon

/** Stable public instance documented by AIOStreams and operated by TorBox's community manager. */
internal const val AIOSTREAMS_INSTANCE_BASE_URL =
    "https://aiostreamsfortheweebsstable.midnightignite.me"

/** Long-lived raw URL maintained on the repository's dedicated templates branch. Fetched at setup time. */
internal const val NUVIO_Z_AIOSTREAMS_TEMPLATE_URL =
    "https://raw.githubusercontent.com/Zokaper/NuvioZDesktop/refs/heads/templates/nuvio-z-torbox-v1.json"

internal fun List<ManagedAddon>.firstEnabledStreamAddonName(): String? =
    firstOrNull { addon ->
        addon.enabled && addon.manifest?.resources?.any { resource ->
            resource.name.equals("stream", ignoreCase = true)
        } == true
    }?.displayTitle

/** What the Sources step needs to know about one installed addon (see `setupSourcesStatus`). */
internal fun ManagedAddon.setupFacts(): SetupSourceAddonFacts = SetupSourceAddonFacts(
    name = manifest?.name,
    enabled = enabled,
    loaded = manifest != null,
    providesStreams = manifest?.resources?.any { it.name.equals("stream", ignoreCase = true) } == true,
    refreshing = isRefreshing,
    failed = !errorMessage.isNullOrBlank(),
)

/** How the Sources step finds this profile's addons. */
internal fun List<ManagedAddon>.setupSourcesStatus(): SetupSourcesStatus =
    setupSourcesStatus(map { it.setupFacts() }, NUVIO_Z_RECOMMENDED_ADDON_NAMES)

/** The installed recommended source's name, for "… is installed and ready". */
internal fun List<ManagedAddon>.recommendedSourceName(): String? =
    firstOrNull { addon -> addon.enabled && addon.manifest?.name in NUVIO_Z_RECOMMENDED_ADDON_NAMES }?.displayTitle

internal sealed interface SetupSourceInstallResult {
    data class Installed(val addonName: String) : SetupSourceInstallResult
    data class Failed(val message: String) : SetupSourceInstallResult
}

/** Keeps the wizard on the canonical addon installer while making both outcomes testable. */
internal suspend fun installSetupSource(
    rawUrl: String,
    emptyUrlMessage: String,
    installer: suspend (String) -> AddAddonResult = AddonRepository::addAddon,
): SetupSourceInstallResult {
    if (rawUrl.isBlank()) return SetupSourceInstallResult.Failed(emptyUrlMessage)
    return when (val result = installer(rawUrl)) {
        is AddAddonResult.Success -> SetupSourceInstallResult.Installed(result.manifest.name)
        is AddAddonResult.Error -> SetupSourceInstallResult.Failed(result.message)
    }
}
