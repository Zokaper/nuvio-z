package com.nuvio.app.features.updater

import com.nuvio.app.core.build.AppVersionConfig

actual object AppUpdaterPlatform {
    actual val isSupported: Boolean = true
    actual val isDebugBuild: Boolean
        get() = AndroidAppUpdaterPlatform.isDebugBuild()

    /**
     * Android updates come from **`Zokaper/nuvio-z`**, this repository.
     *
     * Not `NuvioZDesktop`. The converged desktop copy of this file named the desktop repository
     * here, which would have offered an Android user a Windows MSI; desktop never compiles its
     * `androidFull` source set, so nothing there could catch it. `Zokaper/nuvio-z` must stay
     * public, because the updater is unauthenticated -- see `Docs/Z-FEATURES.md` C5, which records
     * the same class of fault shipping on web.
     */
    actual val releaseSource: AppUpdateReleaseSource
        get() = AppUpdateReleaseSource(
            owner = "Zokaper",
            repo = "nuvio-z",
            // Releases target an explicit commit, so a release's targetCommitish is a SHA rather
            // than a branch name; filtering by branch rejects them all.
            channelBranch = null,
            // Stable mobile releases are ordinary GitHub releases. A prerelease is never a
            // stable update, even when it lacks the debug prefix; accepting one here would let a
            // partially staged mobile release escape to installed users. Debug builds still take
            // only debug-v* prereleases through debugChannel below.
            includePrereleases = isDebugBuild,
            userAgent = "NuvioZ",
            // ⚠ **In this repository the debuggable APK *is* the debug channel's build**
            // (`com.nuvio.app.z.debug`, published by `debug-release.yml` as `debug-v*`), and has
            // been since the channel was introduced (770e7c0cb). The shared `debugChannel` doc says
            // the opposite because it was written for NuvioZDesktop, whose Android debug APK is not
            // a channel build. The Phase 6 convergence (e606c2290) took the desktop copy of this
            // file, dropped this line, and put every debug install on the stable line: `.29` could
            // no longer see `.30`. Guarded by `AndroidUpdateChannelTest`.
            debugChannel = isDebugBuild,
        )

    actual val assetSelector: AppUpdateAssetSelector
        get() = AppUpdateAssetSelector(
            fileExtensions = listOf(".apk"),
            contentTypes = listOf("application/vnd.android.package-archive"),
            preferredNameFragments = AndroidAppUpdaterPlatform.getSupportedAbis(),
            fallbackNameFragments = listOf("universal", "all"),
        )

    /**
     * The version this install compares releases against.
     *
     * A debug-channel build carries a fourth component (`0.4.13-z1.30`), and only that one orders
     * debug releases cut from the same release version - with the plain release name every
     * `debug-v0.4.13-z1.*` would look like the same version. Lost in the same convergence as
     * [releaseSource]'s channel, and guarded by the same test.
     */
    actual val currentVersionName: String
        get() = if (isDebugBuild) AppVersionConfig.DEBUG_VERSION_NAME else AppVersionConfig.VERSION_NAME

    actual fun getIgnoredTag(): String? = AndroidAppUpdaterPlatform.getIgnoredTag()

    actual fun setIgnoredTag(tag: String?) {
        AndroidAppUpdaterPlatform.setIgnoredTag(tag)
    }

    actual suspend fun downloadUpdateAsset(
        assetUrl: String,
        assetName: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Result<String> = AndroidAppUpdaterPlatform.downloadUpdateAsset(assetUrl, assetName, onProgress)

    actual fun canInstallDownloadedUpdate(): Boolean = AndroidAppUpdaterPlatform.canRequestPackageInstalls()

    actual fun openInstallPermissionSettings() {
        AndroidAppUpdaterPlatform.openUnknownSourcesSettings()
    }

    actual fun installDownloadedUpdate(path: String): Result<Unit> = AndroidAppUpdaterPlatform.installDownloadedUpdate(path)
}
