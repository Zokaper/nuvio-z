package com.nuvio.app.features.downloads

import com.nuvio.app.core.deeplink.buildChooseQualityDeepLinkUrl
import com.nuvio.app.core.deeplink.buildDownloadsDeepLinkUrl

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

    /** Whether Nuvio is on screen. Decides between the in-app "ready" prompt and a system notification. */
    fun isAppInForeground(): Boolean

    /**
     * Assisted "choose when ready", with Nuvio in the background: "Lanterns S1 is ready - Choose
     * download quality" (or, when nothing was found, that there is nothing to download). Tapping it
     * opens [DownloadChoiceNotice.deepLinkUrl]. Desktop never gets here: it is always "on screen".
     */
    fun notifyChoice(notice: DownloadChoiceNotice)

    /** The choice was made or the batch removed: a notification still in the shade has nothing left to offer. */
    fun clearChoice(batchId: String)

    /**
     * Background discovery started ([running] true) or everything finished. Android keeps its
     * download host up meanwhile so a backgrounded app is not frozen mid-search; iOS asks for
     * background time; desktop has nothing to do.
     */
    fun onDiscoveryRunning(running: Boolean)
}

/** What the "ready to choose" notification says; the platform words it. */
data class DownloadChoiceNotice(
    val batchId: String,
    val title: String,
    val season: Int?,
    /** False when discovery found nothing at all to download. */
    val ready: Boolean,
) {
    val deepLinkUrl: String get() = if (ready) buildChooseQualityDeepLinkUrl(batchId) else buildDownloadsDeepLinkUrl()
}
