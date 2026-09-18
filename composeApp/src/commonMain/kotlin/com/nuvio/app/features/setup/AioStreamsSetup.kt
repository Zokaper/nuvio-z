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
