package com.nuvio.z.iossetup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI

/** One downloadable file whose exact bytes we have pinned. */
@Serializable
data class PinnedAsset(val url: String, val sha256: String, val bytes: Long, val kind: String)

@Serializable
data class IloaderRelease(val version: String, val windows: PinnedAsset, val macos: PinnedAsset) {
    fun forPlatform(isMac: Boolean): PinnedAsset = if (isMac) macos else windows
}

/** Apple's installer rotates its URL, so it is verified by publisher signature and size, not by hash. */
@Serializable
data class AppleInstaller(val url: String, val minBytes: Long, val publisher: String)

@Serializable
data class Prereqs(
    val schema: Int,
    val iloader: IloaderRelease,
    val appleInstallerWindows: AppleInstaller,
) {
    companion object {
        const val SCHEMA = 1

        /** Hosts a descriptor may point at. A hosted descriptor can never redirect a download elsewhere. */
        val allowedHosts = setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com", "secure-appldnld.apple.com")

        const val REMOTE_URL = "https://raw.githubusercontent.com/Zokaper/nuvio-z/main/distribution/sidestore/ios-setup-prereqs.json"

        private val json = Json { ignoreUnknownKeys = true }
        private val sha256 = Regex("^[0-9a-f]{64}$")

        fun embedded(): Prereqs = requireNotNull(parse(
            Prereqs::class.java.getResourceAsStream("/prereqs.json")?.bufferedReader()?.readText().orEmpty(),
        )) { "embedded prereqs.json is invalid" }

        /** Parses and validates. Returns null for anything malformed, unpinned or pointing off the allow-list. */
        fun parse(text: String): Prereqs? = runCatching { json.decodeFromString<Prereqs>(text) }.getOrNull()?.takeIf { it.isValid() }

        /**
         * Prefers a fresher hosted descriptor (so a rotated Apple URL or a newer pinned iloader can ship
         * without a new app release) but only if it validates; otherwise the embedded one is used.
         */
        fun load(fetchRemote: () -> String? = { null }, embedded: Prereqs = embedded()): Prereqs =
            runCatching { fetchRemote()?.let(::parse) }.getOrNull() ?: embedded
    }

    fun isValid(): Boolean {
        if (schema != SCHEMA) return false
        val assets = listOf(iloader.windows, iloader.macos)
        return assets.all { sha256.matches(it.sha256) && it.bytes > 0 && trusted(it.url) && it.kind in setOf("nsis", "app-tar-gz") } &&
            iloader.version.matches(Regex("^v\\d+\\.\\d+\\.\\d+$")) &&
            trusted(appleInstallerWindows.url) && appleInstallerWindows.minBytes > 0 && appleInstallerWindows.publisher.isNotBlank()
    }

    private fun trusted(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host in allowedHosts
    }.getOrDefault(false)
}
