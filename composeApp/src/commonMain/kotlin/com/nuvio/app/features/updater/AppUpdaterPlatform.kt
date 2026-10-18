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
    /**
     * A marker a release's notes must contain to be offered, or null for none.
     *
     * iOS self-signing builds set [releaseMarkerSelfSigning]: an IPA without the signer can't renew
     * itself, so installing one over a self-signing copy would leave it to expire in 7 days.
     */
    val requiredNotesMarker: String? = null,
)

/** Where an install is, for the banner. Only iOS reports more than [Install]; it signs on the phone. */
enum class AppUpdateInstallStage { Download, Prepare, Install }

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

    /**
     * Installs what [downloadUpdateAsset] returned. iOS self-signing downloads, signs and installs here
     * (its download step only hands the URL on) and reports each stage through [onProgress]; the other
     * platforms start the system installer and return.
     */
    suspend fun installDownloadedUpdate(
        path: String,
        onProgress: (stage: AppUpdateInstallStage, fraction: Float?) -> Unit,
    ): Result<Unit>
}
