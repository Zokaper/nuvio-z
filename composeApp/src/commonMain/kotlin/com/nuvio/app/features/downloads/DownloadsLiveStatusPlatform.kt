package com.nuvio.app.features.downloads

internal expect object DownloadsLiveStatusPlatform {
    fun onItemsChanged(items: List<DownloadItem>)

    /**
     * Preparation has no [DownloadItem] behind it yet, so it cannot ride on
     * [onItemsChanged]: a batch that is still finding sources is invisible to the
     * item list until the first entry is queued.
     */
    fun onBatchesChanged(batches: List<DownloadBatch>)
}
