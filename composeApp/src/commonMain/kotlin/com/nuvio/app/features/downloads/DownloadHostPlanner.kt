package com.nuvio.app.features.downloads

/**
 * What the Android background host needs to know about the queue, as pure functions so the
 * lifecycle is testable without a device (Phase 9, stage 4).
 *
 * The host is one job for the whole device queue. Its decisions used to be made per transfer:
 * each start scheduled the job with *that item's* mobile-data permission, and the first schedule
 * won. On mobile data that meant a job constrained to an unmetered network - accepted by the
 * system, so the worker fallback never ran, and never started. That fits the `.49` report
 * ("Waiting to retry" after screen off, on mobile data), but it is a hypothesis until a device run
 * confirms it.
 */
internal object DownloadHostPlanner {
    /** Unfinished work the host exists to carry. Paused and failed items are not its business. */
    fun hasWork(items: List<DownloadItem>): Boolean = items.any(::isHostWork)

    /**
     * Whether the host may run on a metered network: yes as soon as any unfinished item may use
     * mobile data - the device rule says Always, or the user said "Download now anyway". An item
     * that may not is held by the queue ("Waiting for Wi-Fi"), not by the job's constraint.
     */
    fun mayUseMeteredNetwork(items: List<DownloadItem>, rule: DownloadMobileDataRule): Boolean =
        items.any { isHostWork(it) && it.mayUseMeteredNetwork(rule) }

    /** One line for the diagnostics log. */
    fun describe(items: List<DownloadItem>, rule: DownloadMobileDataRule): String {
        val work = items.filter(::isHostWork)
        return "work=${work.size} downloading=${work.count { it.status == DownloadStatus.Downloading }} " +
            "wifiWait=${work.count { it.activity == DownloadActivity.WAITING_FOR_WIFI }} " +
            "retrying=${work.count { it.nextRetryAtEpochMs != null }} rule=$rule " +
            "metered=${mayUseMeteredNetwork(items, rule)}"
    }

    private fun isHostWork(item: DownloadItem): Boolean =
        item.status == DownloadStatus.Downloading || item.status == DownloadStatus.Queued
}
