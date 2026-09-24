package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadDeviceSettingsTest {
    private fun item(id: String, rank: Long, allowMetered: Boolean = false) = DownloadItem(
        id = id,
        contentType = "series",
        parentMetaId = "tt1",
        parentMetaType = "series",
        videoId = "tt1:1:$rank",
        title = "Show",
        seasonNumber = 1,
        episodeNumber = rank.toInt(),
        streamTitle = "s",
        providerName = "p",
        fileName = "$id.mkv",
        status = DownloadStatus.Queued,
        allowMeteredNetwork = allowMetered,
        queuePosition = rank,
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
    )

    @Test
    fun theDefaultsAreWifiOnlyAndTwoAtOnce() {
        val settings = DownloadDeviceSettings()
        assertEquals(DownloadMobileDataRule.WIFI_ONLY, settings.mobileData)
        assertEquals(2, settings.effectiveMaxConcurrent)
    }

    @Test
    fun concurrencyIsHeldBetweenOneAndFour() {
        assertEquals(1, DownloadDeviceSettings(maxConcurrent = 0).effectiveMaxConcurrent)
        assertEquals(4, DownloadDeviceSettings(maxConcurrent = 9).effectiveMaxConcurrent)
        assertEquals(3, DownloadDeviceSettings(maxConcurrent = 3).effectiveMaxConcurrent)
    }

    @Test
    fun wifiNeverBlocksAnything() {
        DownloadMobileDataRule.entries.forEach { rule ->
            assertTrue(item("a", 1).mayStartOn(isMetered = false, rule = rule))
        }
    }

    @Test
    fun mobileDataWaitsUnlessTheRuleOrTheItemAllowsIt() {
        assertFalse(item("a", 1).mayStartOn(isMetered = true, rule = DownloadMobileDataRule.WIFI_ONLY))
        assertFalse(item("a", 1).mayStartOn(isMetered = true, rule = DownloadMobileDataRule.ASK))
        assertTrue(item("a", 1).mayStartOn(isMetered = true, rule = DownloadMobileDataRule.ALWAYS))
        assertTrue(item("a", 1, allowMetered = true).mayStartOn(isMetered = true, rule = DownloadMobileDataRule.WIFI_ONLY))
    }

    @Test
    fun thePlannerSkipsItemsTheNetworkDoesNotAllowWithoutLosingQueueOrder() {
        val items = listOf(item("e1", 1), item("e2", 2, allowMetered = true), item("e3", 3, allowMetered = true))
        val started = DownloadQueuePlanner.startable(
            items = items,
            activeIds = emptySet(),
            maxConcurrent = 2,
            nowEpochMs = 0L,
            mayStartOnNetwork = { it.mayStartOn(isMetered = true, rule = DownloadMobileDataRule.WIFI_ONLY) },
        )
        assertEquals(listOf("e2", "e3"), started.map { it.id })
    }
}
