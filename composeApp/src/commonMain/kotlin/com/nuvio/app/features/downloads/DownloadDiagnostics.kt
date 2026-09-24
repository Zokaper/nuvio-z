package com.nuvio.app.features.downloads

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.NetworkQualityPlatform

/** URL- and credential-free lifecycle diagnostics for download recovery. */
internal object DownloadDiagnostics {
    private val log = Logger.withTag("DownloadDiag")

    /** Where the iOS Debug build mirrors these lines, next to the session's own events. */
    internal var sink: ((String) -> Unit)? = null

    /**
     * What the platform can add about the app's own state when something goes wrong - on Android,
     * whether the app is in the foreground and whether the background host is running. Without it
     * a failure logged while the screen was off looked the same as one logged in front of the user.
     */
    internal var appState: (() -> String)? = null

    /** The network and app state at the moment of a failure, retry or connection wait. */
    private fun context(): String {
        val network = runCatching {
            val quality = NetworkQualityPlatform.current()
            "net=${quality.connectionType} metered=${quality.isMetered}"
        }.getOrDefault("net=unknown")
        val app = runCatching { appState?.invoke() }.getOrNull()
        return if (app.isNullOrBlank()) network else "$network $app"
    }

    /** Anything without an item to hang it on - a fenced callback, an inventory decision. */
    fun note(name: String, details: String) {
        val line = "event=$name $details"
        log.i { line }
        sink?.invoke(line)
    }

    fun selection(provider: String?, season: Int?, episode: Int?, lazy: Boolean, outcome: String) =
        event("selection", provider, season, episode, details = "lazy=$lazy outcome=$outcome")

    fun slot(item: DownloadItem) = event("slot", item, "attempt=${item.attemptCount + 1}")
    fun resolving(item: DownloadItem) = event("resolution_start", item, "attempt=${item.attemptCount + 1}")
    fun resolved(item: DownloadItem, bytes: Long?) = event("resolution_ready", item, "bytes=$bytes")
    fun transferOpen(item: DownloadItem, resumed: Long, total: Long?) =
        event("transfer_open", item, "attempt=${item.attemptCount + 1} resumed=$resumed total=$total")
    fun failure(item: DownloadItem, category: String, attempt: Int, bytes: Long) =
        event("failure", item, "category=$category attempt=$attempt bytes=$bytes ${context()}")
    fun retry(item: DownloadItem, category: String, attempt: Int, at: Long?) =
        event("retry", item, "category=$category attempt=$attempt retryAt=$at bytes=${item.downloadedBytes} ${context()}")
    fun connectivity(item: DownloadItem, recovered: Boolean) =
        event(
            if (recovered) "connectivity_recovery" else "connection_wait",
            item,
            "bytes=${item.downloadedBytes} ${context()}",
        )
    fun wifiWait(item: DownloadItem, waiting: Boolean) =
        event(if (waiting) "wifi_wait" else "wifi_wait_end", item, "bytes=${item.downloadedBytes} ${context()}")
    fun completion(item: DownloadItem, bytes: Long) = event("completion", item, "bytes=$bytes")

    private fun event(name: String, item: DownloadItem, details: String) =
        event(name, item.providerName, item.seasonNumber, item.episodeNumber, details)

    private fun event(
        name: String,
        provider: String?,
        season: Int?,
        episode: Int?,
        details: String,
    ) {
        val line = "event=$name provider=${provider?.take(80) ?: "unknown"} " +
            "season=${season ?: -1} episode=${episode ?: -1} $details"
        log.i { line }
        sink?.invoke(line)
    }
}
