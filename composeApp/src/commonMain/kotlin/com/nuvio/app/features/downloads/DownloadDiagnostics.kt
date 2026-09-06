package com.nuvio.app.features.downloads

import co.touchlab.kermit.Logger

/** URL- and credential-free lifecycle diagnostics for download recovery. */
internal object DownloadDiagnostics {
    private val log = Logger.withTag("DownloadDiag")

    fun selection(provider: String?, season: Int?, episode: Int?, lazy: Boolean, outcome: String) =
        event("selection", provider, season, episode, details = "lazy=$lazy outcome=$outcome")

    fun slot(item: DownloadItem) = event("slot", item, "attempt=${item.attemptCount + 1}")
    fun resolving(item: DownloadItem) = event("resolution_start", item, "attempt=${item.attemptCount + 1}")
    fun resolved(item: DownloadItem, bytes: Long?) = event("resolution_ready", item, "bytes=$bytes")
    fun transferOpen(item: DownloadItem, resumed: Long, total: Long?) =
        event("transfer_open", item, "attempt=${item.attemptCount + 1} resumed=$resumed total=$total")
    fun failure(item: DownloadItem, category: String, attempt: Int, bytes: Long) =
        event("failure", item, "category=$category attempt=$attempt bytes=$bytes")
    fun retry(item: DownloadItem, category: String, attempt: Int, at: Long?) =
        event("retry", item, "category=$category attempt=$attempt retryAt=$at bytes=${item.downloadedBytes}")
    fun connectivity(item: DownloadItem, recovered: Boolean) =
        event(if (recovered) "connectivity_recovery" else "connection_wait", item, "bytes=${item.downloadedBytes}")
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
        log.i {
            "event=$name provider=${provider?.take(80) ?: "unknown"} " +
                "season=${season ?: -1} episode=${episode ?: -1} $details"
        }
    }
}
