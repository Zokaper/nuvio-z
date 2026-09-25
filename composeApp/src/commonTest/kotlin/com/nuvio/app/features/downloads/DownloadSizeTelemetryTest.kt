package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadSizeTelemetryTest {

    @Test
    fun tokenUsesTheSourcesOwnDurationBeforeTheTitles() {
        val facts = SourceFacts(
            resolution = VideoResolution.FULL_HD_1080,
            sizeBytes = 3_000_000_000L,
            durationSeconds = 5_400L,
            releaseQuality = "WEB-DL",
            codec = "HEVC",
            dynamicRange = setOf("HDR10"),
        )
        assertEquals(
            "1080:3000:2.00:C:WEB-DL:HEVC:hdr:s",
            DownloadSizeTelemetry.sourceToken(facts, DownloadCacheEvidence.CACHED, runtimeMinutes = 45),
        )
    }

    @Test
    fun tokenFallsBackToTitleRuntimeThenUnknown() {
        val facts = SourceFacts(resolution = VideoResolution.HD_720, sizeBytes = 700_000_000L)
        assertEquals(
            "720:700:0.93:H:na:na:sdr:t",
            DownloadSizeTelemetry.sourceToken(facts, DownloadCacheEvidence.PLAIN_HTTP, runtimeMinutes = 45),
        )
        assertEquals(
            "na:na:na:N:na:na:sdr:na",
            DownloadSizeTelemetry.sourceToken(SourceFacts(), DownloadCacheEvidence.NOT_USABLE, runtimeMinutes = null),
        )
    }

    @Test
    fun linesAreSplitIntoPartsAndCapped() {
        val sources = List(DownloadSizeTelemetry.MAX_SOURCES + 5) {
            SourceFacts(resolution = VideoResolution.FULL_HD_1080, sizeBytes = 1_000_000_000L) to DownloadCacheEvidence.CACHED
        }
        val lines = DownloadSizeTelemetry.sampleLines(isEpisode = true, runtimeMinutes = 50, sources = sources)
        val parts = DownloadSizeTelemetry.MAX_SOURCES / DownloadSizeTelemetry.SOURCES_PER_LINE
        assertEquals(parts, lines.size)
        assertTrue(lines.first().startsWith("kind=episode runtime=50 total=${sources.size} part=1/$parts sources="))
        val tokens = lines.sumOf { it.substringAfter("sources=").split(",").size }
        assertEquals(DownloadSizeTelemetry.MAX_SOURCES, tokens)
    }

    @Test
    fun nothingIdentifyingReachesALine() {
        val facts = SourceFacts(
            resolution = VideoResolution.UHD_2160,
            sizeBytes = 20_000_000_000L,
            releaseQuality = "https://evil.example/token?x=1 BluRay",
            codec = "x265 Authorization: Bearer abc",
            filename = "Shogun.S01E01.2160p.mkv",
            providerName = "Torrentio",
            debridService = "RealDebrid",
        )
        val line = DownloadSizeTelemetry.sampleLines(false, 120, listOf(facts to DownloadCacheEvidence.CACHED)).single()
        listOf("http", "://", "?", "=1", " Bearer", "Shogun", ".mkv", "Torrentio", "RealDebrid").forEach { leak ->
            assertFalse(leak in line.substringAfter("sources="), "leaked '$leak' in $line")
        }
        assertEquals("other", DownloadSizeTelemetry.safeToken(facts.releaseQuality))
        assertEquals("BluRayREMUX", DownloadSizeTelemetry.safeToken("BluRay REMUX"))
    }
}
