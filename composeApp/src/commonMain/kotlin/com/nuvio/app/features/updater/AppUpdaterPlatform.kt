package com.nuvio.app.features.updater

data class AppUpdateReleaseSource(
    val owner: String,
    val repo: String,
    val channelBranch: String? = null,
    val includePrereleases: Boolean = false,
    val userAgent: String,
    /**
     * Whether this build updates from the debug channel - the prereleases tagged `debug-v*`.
     *
     * **Each repository's platform actual decides, and the two answers differ.** In `nuvio-z` the
     * debuggable Android APK *is* the debug channel's build (`com.nuvio.app.z.debug`, published by
     * `debug-release.yml`), so its actual sets this from `isDebugBuild`. In NuvioZDesktop an Android
     * debug APK is not a channel build and stays on the release line; there only a build packaged
     * with the debug-channel switch reads it. Copying one repository's actual over the other's is
     * how the Phase 6 convergence (e606c2290) silently put every mobile debug install on the stable
     * line - see `AndroidUpdateChannelTest` in `nuvio-z`.
     */
    val debugChannel: Boolean = false,
)

data class AppUpdateAssetSelector(
    val fileExtensions: List<String>,
    val contentTypes: List<String> = emptyList(),
    val preferredNameFragments: List<String> = emptyList(),
    val fallbackNameFragments: List<String> = emptyList(),
)

expect object AppUpdaterPlatform {
    val isSupported: Boolean
    val isDebugBuild: Boolean

    val releaseSource: AppUpdateReleaseSource

    val assetSelector: AppUpdateAssetSelector

    val currentVersionName: String

    fun getIgnoredTag(): String?

    fun setIgnoredTag(tag: String?)

    suspend fun downloadUpdateAsset(
        assetUrl: String,
        assetName: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Result<String>

    fun canInstallDownloadedUpdate(): Boolean

    fun openInstallPermissionSettings()

    fun installDownloadedUpdate(path: String): Result<Unit>
}
