// Data-only neighbours of DownloadFlowRules; full host/desktop suites use the real models.
package com.nuvio.app.features.downloads

enum class DownloadStatus { Queued, Downloading, Paused, Completed, Failed }
data class DownloadItem(
    val id: String, val contentType: String, val parentMetaId: String, val parentMetaType: String,
    val videoId: String, val title: String, val seasonNumber: Int?, val episodeNumber: Int?,
    val streamTitle: String, val providerName: String, val fileName: String, val status: DownloadStatus,
    val createdAtEpochMs: Long, val updatedAtEpochMs: Long,
)
