package com.nuvio.app.features.downloads

import kotlinx.serialization.Serializable

/**
 * Download settings that belong to **this device**, not the profile (Phase 9): whether downloads
 * may use mobile data, and how many run at once. They live with the device's download state and
 * never sync - Wi-Fi rules on a phone mean nothing on a desktop, and the other way round.
 */
@Serializable
enum class DownloadMobileDataRule {
    /** Downloads wait for Wi-Fi unless the user says "Download now anyway" for an item. */
    WIFI_ONLY,

    /** The app asks when a download is started on mobile data. */
    ASK,

    /** Mobile data is fine. */
    ALWAYS,
}

@Serializable
data class DownloadDeviceSettings(
    val mobileData: DownloadMobileDataRule = DownloadMobileDataRule.WIFI_ONLY,
    /** Android and desktop only; iOS hands a window to the system and ignores this. */
    val maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
) {
    val effectiveMaxConcurrent: Int
        get() = maxConcurrent.coerceIn(MIN_CONCURRENT, MAX_CONCURRENT)

    companion object {
        const val DEFAULT_MAX_CONCURRENT = 2
        const val MIN_CONCURRENT = 1
        const val MAX_CONCURRENT = 4
    }
}

/**
 * Whether this item may use mobile data at all: the device rule says Always, or the user said
 * "Download now anyway" for it. This is also what the platform request carries, so iOS
 * (`allowsCellularAccess`) and Android (the job's network constraint) agree with the queue.
 */
internal fun DownloadItem.mayUseMeteredNetwork(rule: DownloadMobileDataRule): Boolean =
    rule == DownloadMobileDataRule.ALWAYS || allowMeteredNetwork

/**
 * Whether an item may start on the network the device is on right now. Import-free logic so the
 * queue's rule is testable on its own.
 */
internal fun DownloadItem.mayStartOn(isMetered: Boolean, rule: DownloadMobileDataRule): Boolean =
    !isMetered || mayUseMeteredNetwork(rule)
