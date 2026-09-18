package com.nuvio.app.features.updater

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.i18n.localizedByteUnit
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

private const val gitHubApiBase = "https://api.github.com"

data class AppUpdate(
    val tag: String,
    val title: String,
    val notes: String,
    val releaseUrl: String?,
    val assetName: String,
    val assetUrl: String,
    val assetSizeBytes: Long?,
)

data class AppUpdaterUiState(
    val isChecking: Boolean = false,
    val update: AppUpdate? = null,
    val isUpdateAvailable: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Float? = null,
    val downloadedUpdatePath: String? = null,
    val showDialog: Boolean = false,
    val showInstallPermissionDialog: Boolean = false,
    val errorMessage: String? = null,
    val isDebugTest: Boolean = false,
)

@Serializable
private data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String? = null,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("target_commitish") val targetCommitish: String? = null,
    val assets: List<GitHubAssetDto> = emptyList(),
)

@Serializable
private data class GitHubAssetDto(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long? = null,
    @SerialName("content_type") val contentType: String? = null,
)

private val appUpdaterJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private class NoChannelReleaseException : IllegalStateException(
    runBlocking { getString(Res.string.updates_no_channel_release) },
)

/** Tag prefix that marks a release as belonging to the debug update channel. */
internal const val debugChannelTagPrefix = "debug-"

internal object VersionUtils {
    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        // The debug prefix has to come off before the "v", and both before parsing: left on,
        // "debug-v0.4.14-beta.2" tokenises to [4, 14, 2] - the leading 0 lost with the "v0"
        // token - and every debug release would read as newer than every local version forever.
        return raw.trim()
            .removePrefix(debugChannelTagPrefix)
            .removePrefix("v")
            .removePrefix("V")
    }

    fun parseVersionParts(raw: String?): List<Int>? {
        val normalized = normalize(raw)
        if (normalized.isBlank()) return null

        val parts = normalized.split('.', '-', '_')
            .filter { it.isNotBlank() }
            .mapNotNull { token -> token.takeWhile { it.isDigit() }.toIntOrNull() }

        return parts.takeIf { it.isNotEmpty() }
    }

    /**
     * The release serial carried in a tag as "+<serial>", or null when there is none.
     *
     * v0.6.0-z1+127 -> 127, debug-v0.6.0-z1.3+128 -> 128, v0.4.14-beta -> null.
     */
    fun parseReleaseSerial(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val marker = raw.lastIndexOf('+')
        if (marker < 0) return null
        return raw.substring(marker + 1)
            .trim()
            .takeWhile { it.isDigit() }
            .toIntOrNull()
    }

    /**
     * Whether [remote] should be offered to a build on [local].
     *
     * Ordering is by [localSerial] against the remote tag's serial WHENEVER BOTH
     * EXIST, and by the version string otherwise. That fallback is the whole point:
     * an install that predates the serial has no local serial to compare, so it must
     * keep ordering exactly as it always did or it would be stranded forever.
     *
     * The serial exists because the version string cannot order what is coming. A
     * Nuvio Z version is a vanilla version plus a Z revision, so adopting vanilla's
     * numbering makes the next release 0.4.9-z1 while installs sit on 0.4.14-beta -
     * numerically lower, and the string comparison refuses it. See the bridge release
     * described in DesktopReleaseSerial.properties.
     *
     * @param localSerial AppVersionConfig.RELEASE_SERIAL, or null/0 for a build that
     *   has none. Defaulted so the existing string-only behaviour is still reachable.
     */
    fun isRemoteNewer(remote: String?, local: String?, localSerial: Int? = null): Boolean {
        val remoteSerial = parseReleaseSerial(remote)
        // 0 is "no serial", not "serial zero": a build predating the serial generates
        // RELEASE_SERIAL = 0 from the gradle default, and must fall through to the string.
        val localSerialOrNull = localSerial?.takeIf { it > 0 }
        if (remoteSerial != null && localSerialOrNull != null) {
            return remoteSerial > localSerialOrNull
        }

        val remoteParts = parseVersionParts(remote)
        val localParts = parseVersionParts(local)

        if (remoteParts == null || localParts == null) {
            val remoteValue = normalize(remote)
            val localValue = normalize(local)
            return remoteValue.isNotBlank() && localValue.isNotBlank() && remoteValue != localValue
        }

        val maxSize = maxOf(remoteParts.size, localParts.size)
        for (index in 0 until maxSize) {
            val remoteValue = remoteParts.getOrElse(index) { 0 }
            val localValue = localParts.getOrElse(index) { 0 }
            if (remoteValue != localValue) return remoteValue > localValue
        }
        return false
    }
}

/** One published release, for the What's New screen's version history. */
data class AppReleaseNotes(
    val tag: String,
    val title: String,
    val notes: String,
)

/**
 * The last few published releases, newest first.
 *
 * The releases feed the updater already fetches carries every release's `body`, and until now
 * all of it but the newest was discarded. What's New reads that rather than making a second
 * kind of request.
 */
suspend fun fetchRecentReleaseNotes(limit: Int = 5): Result<List<AppReleaseNotes>> = runCatching {
    val source = AppUpdaterPlatform.releaseSource
    AppUpdaterRepository.fetchReleases()
        // Debug releases are excluded on both channels, including from a debug build's own
        // history. What's New is the product's version history; a debug build is a copy of one
        // of those versions, and its notes say only which branch it was cut from.
        .filter { release ->
            !release.draft &&
                release.tagName?.trim()?.startsWith(debugChannelTagPrefix, ignoreCase = true) != true &&
                (source.includePrereleases || !release.prerelease)
        }
        .take(limit)
        .mapNotNull { release ->
            val tag = release.tagName?.takeIf { it.isNotBlank() }
                ?: release.name?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            AppReleaseNotes(
                tag = tag,
                title = release.name?.takeIf { it.isNotBlank() } ?: tag,
                notes = release.body.orEmpty(),
            )
        }
}

private object AppUpdaterRepository {

    suspend fun fetchReleases(): List<GitHubReleaseDto> {
        val source = AppUpdaterPlatform.releaseSource
        val response = httpRequestRaw(
            method = "GET",
            url = "$gitHubApiBase/repos/${source.owner}/${source.repo}/releases?per_page=20",
            headers = mapOf(
                "Accept" to "application/vnd.github+json",
                "User-Agent" to source.userAgent,
            ),
            body = "",
        )
        if (response.status !in 200..299) {
            error(getString(Res.string.updates_github_api_error, response.status))
        }
        return appUpdaterJson.decodeFromString<List<GitHubReleaseDto>>(response.body)
    }

    suspend fun getLatestChannelUpdate(): Result<AppUpdate> = withContext(Dispatchers.Default) {
        runCatching {
            val source = AppUpdaterPlatform.releaseSource
            val releases = fetchReleases()
        // The two channels cannot see each other, and each half of that matters. A debug build
        // takes only `debug-v*` prereleases, so it never installs the release app over itself.
        // A release build rejects them outright rather than relying on `includePrereleases`,
        // because this repository's Android build sets that flag true and would otherwise be
        // offered a desktop MSI it has no asset for.
        val release = if (source.debugChannel) {
            releases.firstOrNull { !it.draft && it.prerelease && it.isDebugChannelRelease() }
                ?: throw NoChannelReleaseException()
        } else {
            releases.firstOrNull { release ->
                release.matchesRequestedChannel() &&
                    !release.draft &&
                    !release.isDebugChannelRelease() &&
                    (source.includePrereleases || !release.prerelease)
            }
                ?: throw NoChannelReleaseException()
        }

        val tag = release.tagName?.takeIf { it.isNotBlank() }
            ?: release.name?.takeIf { it.isNotBlank() }
            ?: error(getString(Res.string.updates_release_missing_title))

        val asset = selectBestUpdateAsset(
            assets = release.assets.map { asset ->
                AppUpdateAssetCandidate(
                    name = asset.name,
                    downloadUrl = asset.browserDownloadUrl,
                    size = asset.size,
                    contentType = asset.contentType,
                )
            },
            selector = AppUpdaterPlatform.assetSelector,
        )
            ?: error(getString(Res.string.updates_update_asset_missing))

            AppUpdate(
                tag = tag,
                title = release.name?.takeIf { it.isNotBlank() } ?: tag,
                notes = release.body.orEmpty(),
                releaseUrl = release.htmlUrl,
                assetName = asset.name,
                assetUrl = asset.downloadUrl,
                assetSizeBytes = asset.size,
            )
        }
    }

    private fun GitHubReleaseDto.isDebugChannelRelease(): Boolean =
        tagName?.trim()?.startsWith(debugChannelTagPrefix, ignoreCase = true) == true

    private fun GitHubReleaseDto.matchesRequestedChannel(): Boolean {
        val channel = AppUpdaterPlatform.releaseSource.channelBranch
            ?.takeIf { it.isNotBlank() }
            ?: return true
        if (targetCommitish?.trim()?.equals(channel, ignoreCase = true) == true) {
            return true
        }

        return listOf(tagName, name)
            .filterNotNull()
            .any { value -> value.contains(channel, ignoreCase = true) }
    }

}

internal data class AppUpdateAssetCandidate(
    val name: String,
    val downloadUrl: String,
    val size: Long? = null,
    val contentType: String? = null,
)

internal fun selectBestUpdateAsset(
    assets: List<AppUpdateAssetCandidate>,
    selector: AppUpdateAssetSelector,
): AppUpdateAssetCandidate? {
    val updateAssets = assets.filter { asset -> asset.matches(selector) }
    if (updateAssets.isEmpty()) return null
    if (updateAssets.size == 1) return updateAssets.first()

    for (fragment in selector.preferredNameFragments) {
        val candidate = updateAssets.firstOrNull { asset ->
            asset.name.contains(fragment, ignoreCase = true)
        }
        if (candidate != null) return candidate
    }

    return updateAssets.firstOrNull { asset ->
        val name = asset.name.lowercase()
        selector.fallbackNameFragments.any { fragment -> name.contains(fragment.lowercase()) }
    } ?: updateAssets.first()
}

private fun AppUpdateAssetCandidate.matches(selector: AppUpdateAssetSelector): Boolean =
    selector.fileExtensions.any { extension -> name.endsWith(extension, ignoreCase = true) } ||
        selector.contentTypes.any { contentType -> contentType.equals(this.contentType, ignoreCase = true) }

class AppUpdaterController internal constructor(
    private val scope: CoroutineScope,
) {
    private val _uiState = MutableStateFlow(AppUpdaterUiState())
    val uiState: StateFlow<AppUpdaterUiState> = _uiState.asStateFlow()

    private var autoCheckStarted = false

    fun ensureAutoCheckStarted() {
        if (autoCheckStarted || !AppFeaturePolicy.inAppUpdaterEnabled || !AppUpdaterPlatform.isSupported) {
            return
        }
        autoCheckStarted = true
        checkForUpdates(force = false, showNoUpdateFeedback = false)
    }

    fun checkForUpdates(force: Boolean, showNoUpdateFeedback: Boolean) {
        if (!AppFeaturePolicy.inAppUpdaterEnabled || !AppUpdaterPlatform.isSupported) {
            if (showNoUpdateFeedback) {
                scope.launch {
                    NuvioToastController.show(getString(Res.string.updates_not_available))
                }
            }
            return
        }

        scope.launch {
            _uiState.update { state ->
                state.copy(
                    isChecking = true,
                    errorMessage = null,
                    showInstallPermissionDialog = false,
                    isDebugTest = false,
                )
            }

            val ignoredTag = withContext(Dispatchers.Default) {
                AppUpdaterPlatform.getIgnoredTag()
            }
            val result = AppUpdaterRepository.getLatestChannelUpdate()

            result.onSuccess { update ->
                val remoteNewer = VersionUtils.isRemoteNewer(
                    remote = update.tag,
                    local = AppUpdaterPlatform.currentVersionName,
                    localSerial = AppVersionConfig.RELEASE_SERIAL,
                )
                val ignored = ignoredTag != null && ignoredTag == update.tag
                val shouldShowDialog = force || (remoteNewer && !ignored)

                _uiState.update { state ->
                    state.copy(
                        isChecking = false,
                        update = update.takeIf { remoteNewer },
                        isUpdateAvailable = remoteNewer,
                        isDownloading = false,
                        downloadProgress = null,
                        downloadedUpdatePath = state.downloadedUpdatePath.takeIf { remoteNewer },
                        showDialog = shouldShowDialog,
                        showInstallPermissionDialog = false,
                        errorMessage = null,
                    )
                }

                if (showNoUpdateFeedback && !remoteNewer) {
                    NuvioToastController.show(getString(Res.string.updates_latest_version))
                }
            }.onFailure { error ->
                _uiState.update { state ->
                    state.copy(
                        isChecking = false,
                        isDownloading = false,
                        downloadProgress = null,
                        downloadedUpdatePath = null,
                        update = null,
                        isUpdateAvailable = false,
                        showDialog = force && error !is NoChannelReleaseException,
                        showInstallPermissionDialog = false,
                        errorMessage = if (force && error !is NoChannelReleaseException) {
                            error.message ?: getString(Res.string.updates_check_failed)
                        } else {
                            null
                        },
                    )
                }

                if (showNoUpdateFeedback) {
                    NuvioToastController.show(error.message ?: getString(Res.string.updates_check_failed))
                }
            }
        }
    }

    fun dismissDialog() {
        _uiState.update { state ->
            state.copy(
                showDialog = false,
                showInstallPermissionDialog = false,
                errorMessage = null,
            )
        }
    }

    fun ignoreThisVersion() {
        val tag = _uiState.value.update?.tag ?: return
        AppUpdaterPlatform.setIgnoredTag(tag)
        dismissDialog()
    }

    fun downloadUpdate() {
        val update = _uiState.value.update ?: return
        if (_uiState.value.isDebugTest) {
            runDebugDownloadTest()
            return
        }

        scope.launch {
            _uiState.update { state ->
                state.copy(
                    isDownloading = true,
                    downloadProgress = 0f,
                    errorMessage = null,
                )
            }

            AppUpdaterPlatform.downloadUpdateAsset(
                assetUrl = update.assetUrl,
                assetName = update.assetName,
            ) { downloadedBytes, totalBytes ->
                val progress = if (totalBytes != null && totalBytes > 0L) {
                    (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                } else {
                    null
                }
                _uiState.update { state -> state.copy(downloadProgress = progress) }
            }.onSuccess { path ->
                _uiState.update { state ->
                    state.copy(
                        isDownloading = false,
                        downloadProgress = 1f,
                        downloadedUpdatePath = path,
                        errorMessage = null,
                    )
                }
                installDownloadedUpdate()
            }.onFailure { error ->
                _uiState.update { state ->
                    state.copy(
                        isDownloading = false,
                        downloadProgress = null,
                        downloadedUpdatePath = null,
                        errorMessage = error.message ?: getString(Res.string.updates_download_failed),
                        showDialog = true,
                    )
                }
            }
        }
    }

    fun installDownloadedUpdate() {
        val updatePath = _uiState.value.downloadedUpdatePath ?: return
        if (!AppUpdaterPlatform.canInstallDownloadedUpdate()) {
            _uiState.update { state -> state.copy(showInstallPermissionDialog = true, showDialog = true) }
            return
        }

        AppUpdaterPlatform.installDownloadedUpdate(updatePath).onSuccess {
            _uiState.update { state -> state.copy(showInstallPermissionDialog = false) }
        }.onFailure { error ->
            scope.launch {
                val fallbackMessage = error.message ?: getString(Res.string.updates_install_failed)
                _uiState.update { state ->
                    state.copy(
                        errorMessage = fallbackMessage,
                        showDialog = true,
                    )
                }
            }
        }
    }

    fun resumeInstallation() {
        if (AppUpdaterPlatform.canInstallDownloadedUpdate()) {
            installDownloadedUpdate()
        } else {
            AppUpdaterPlatform.openInstallPermissionSettings()
        }
    }

    fun showDebugTestUpdate() {
        if (!AppUpdaterPlatform.isDebugBuild || !AppUpdaterPlatform.isSupported) return

        _uiState.value = AppUpdaterUiState(
            update = AppUpdate(
                tag = "9.9.9",
                title = "Nuvio 9.9.9",
                notes = """
                    A local preview of the new update experience.

                    - The banner pushes the app content down.
                    - Download progress fills the banner with the primary accent.
                    - Release notes live behind the info button.
                """.trimIndent(),
                releaseUrl = null,
                assetName = "Nuvio-debug-preview.apk",
                assetUrl = "debug://update-preview",
                assetSizeBytes = 185L * 1024L * 1024L,
            ),
            isUpdateAvailable = true,
            showDialog = true,
            isDebugTest = true,
        )
    }

    private fun runDebugDownloadTest() {
        scope.launch {
            _uiState.update { state ->
                state.copy(
                    isDownloading = true,
                    downloadProgress = 0f,
                    errorMessage = null,
                )
            }

            for (step in 1..100) {
                delay(35)
                _uiState.update { state -> state.copy(downloadProgress = step / 100f) }
            }

            _uiState.update { state ->
                state.copy(
                    isDownloading = false,
                    isUpdateAvailable = false,
                    downloadProgress = 1f,
                )
            }
        }
    }
}

@Composable
fun rememberAppUpdaterController(): AppUpdaterController {
    val scope = rememberCoroutineScope()
    return remember(scope) { AppUpdaterController(scope) }
}

internal fun formatFileSize(sizeBytes: Long): String {
    if (sizeBytes <= 0L) return "0 ${localizedByteUnit("B")}"
    val units = listOf("B", "KB", "MB", "GB")
    var value = sizeBytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    val roundedValue = if (value >= 10 || unitIndex == 0) {
        value.toInt().toString()
    } else {
        ((value * 10).toInt() / 10.0).toString()
    }
    return "$roundedValue ${localizedByteUnit(units[unitIndex])}"
}
