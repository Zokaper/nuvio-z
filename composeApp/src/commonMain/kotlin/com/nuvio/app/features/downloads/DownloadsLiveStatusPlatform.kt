package com.nuvio.app.features.downloads

internal expect object DownloadsLiveStatusPlatform {
    fun onItemsChanged(items: List<DownloadItem>)

    /**
     * Preparation has no [DownloadItem] behind it yet, so it cannot ride on
     * [onItemsChanged]: a batch that is still finding sources is invisible to the
     * item list until the first entry is queued.
     */
    fun onBatchesChanged(batches: List<DownloadBatch>)

    /**
     * The user has just asked for something to be downloaded.
     *
     * Android needs `POST_NOTIFICATIONS` (13+) before it will show the ongoing download
     * notification at all, and downloads never asked for it - only the episode-release feature
     * did - so most installs downloaded with no notification. Asking here, when the user has
     * just expressed intent, is the moment the request makes sense. Other platforms do nothing.
     */
    fun onDownloadRequested()
}
