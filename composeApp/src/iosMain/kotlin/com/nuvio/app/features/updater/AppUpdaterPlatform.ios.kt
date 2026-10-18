package com.nuvio.app.features.updater

import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.debug.SelfSigningSpike
import com.nuvio.app.core.debug.selfSigningActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.updates_install_failed
import nuvio.composeapp.generated.resources.updates_ios_code
import nuvio.composeapp.generated.resources.updates_ios_connect_vpn
import nuvio.composeapp.generated.resources.updates_ios_no_account
import nuvio.composeapp.generated.resources.updates_ios_stopped
import nuvio.composeapp.generated.resources.updates_not_available
import org.jetbrains.compose.resources.getString

/**
 * iOS updates only in builds that sign themselves (`NUVIO_IOS_SELF_SIGNING`): the phone downloads the IPA, signs it
 * with the saved Apple Account and installs it over this app through LocalDevVPN. Other iOS builds have nothing
 * that could install an IPA, so the updater stays off there.
 *
 * Only releases whose notes carry [releaseMarkerSelfSigning] are offered: an IPA without the signer would install
 * fine and then expire in 7 days with no way to renew itself.
 */
actual object AppUpdaterPlatform {
    actual val isSupported: Boolean
        get() = selfSigningActive

    actual val isDebugBuild: Boolean
        get() = com.nuvio.app.core.debug.isDebugBuild

    actual val releaseSource: AppUpdateReleaseSource
        get() = AppUpdateReleaseSource(
            owner = "Zokaper",
            repo = "nuvio-z",
            channelBranch = null,
            // As on Android: the debug IPA is the debug channel's build (debug-v* prereleases, `com.nuvio.app.z.debug`),
            // and a stable build never takes a prerelease.
            includePrereleases = isDebugBuild,
            userAgent = "NuvioZ",
            debugChannel = isDebugBuild,
            requiredNotesMarker = releaseMarkerSelfSigning,
        )

    actual val assetSelector: AppUpdateAssetSelector = AppUpdateAssetSelector(
        fileExtensions = listOf(".ipa"),
    )

    actual val currentVersionName: String
        get() = if (isDebugBuild) AppVersionConfig.DEBUG_VERSION_NAME else AppVersionConfig.VERSION_NAME

    actual fun getIgnoredTag(): String? = null

    actual fun setIgnoredTag(tag: String?) = Unit

    /** The signer downloads the IPA itself while it installs (with progress), so this only hands the URL on. */
    actual suspend fun downloadUpdateAsset(
        assetUrl: String,
        assetName: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Result<String> =
        if (selfSigningActive && assetUrl.startsWith("https://")) {
            Result.success(assetUrl)
        } else {
            Result.failure(IllegalStateException(getString(Res.string.updates_not_available)))
        }

    actual fun canInstallDownloadedUpdate(): Boolean = selfSigningActive

    actual fun openInstallPermissionSettings() = Unit

    /**
     * Downloads, signs and installs [path] (the IPA's URL). iOS ends this app when the install finishes, so success
     * usually never comes back here: the next start notices the new signature and says so.
     */
    actual suspend fun installDownloadedUpdate(
        path: String,
        onProgress: (stage: AppUpdateInstallStage, fraction: Float?) -> Unit,
    ): Result<Unit> {
        val platform = SelfSigningSpike.platform
            ?: return Result.failure(IllegalStateException(getString(Res.string.updates_not_available)))
        if (platform.savedAccount() == null) {
            return Result.failure(IllegalStateException(getString(Res.string.updates_ios_no_account)))
        }
        if (!platform.vpnReachable()) {
            return Result.failure(IllegalStateException(getString(Res.string.updates_ios_connect_vpn)))
        }
        onProgress(AppUpdateInstallStage.Download, 0f)
        val raw = coroutineScope {
            val poll = launch {
                while (isActive) {
                    platform.updateProgress()?.let(::parseProgress)?.let { (stage, fraction) -> onProgress(stage, fraction) }
                    delay(400)
                }
            }
            try {
                platform.installUpdate(path, null)
            } finally {
                poll.cancel()
            }
        }
        return updateResult(raw)
    }
}

private val updateJson = Json { ignoreUnknownKeys = true }

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.content

/** The signer's `{ stage, fraction }`. */
internal fun parseProgress(raw: String): Pair<AppUpdateInstallStage, Float?>? = runCatching {
    val root = updateJson.parseToJsonElement(raw).jsonObject
    val stage = when (root.text("stage")) {
        "download" -> AppUpdateInstallStage.Download
        "sign" -> AppUpdateInstallStage.Prepare
        "install" -> AppUpdateInstallStage.Install
        else -> return@runCatching null
    }
    stage to (root["fraction"] as? JsonPrimitive)?.floatOrNull
}.getOrNull()

/** The signer's result as the banner needs it: success, or one plain sentence. */
internal suspend fun updateResult(raw: String): Result<Unit> {
    val root = runCatching { updateJson.parseToJsonElement(raw).jsonObject }.getOrNull()
        ?: return Result.failure(IllegalStateException(getString(Res.string.updates_ios_stopped)))
    if (root.text("ok") == "true") return Result.success(Unit)
    val error = root["error"] as? JsonObject
    val message = when (error?.text("kind")) {
        "codeNeeded" -> getString(Res.string.updates_ios_code)
        else -> error?.text("summary") ?: root.text("error") ?: getString(Res.string.updates_install_failed)
    }
    return Result.failure(IllegalStateException(message))
}
