package com.nuvio.app.features.downloads

import com.nuvio.app.features.plugins.PluginRuntimeResult
import com.nuvio.app.features.plugins.PluginScraper
import com.nuvio.app.features.streams.toStreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginStreamMetaTest {
    @Test
    fun pluginMetadataSurvivesIngestIntoSourceFacts() {
        val stream = PluginRuntimeResult(
            title = "Release",
            url = "magnet:?xt=urn:btih:0123456789012345678901234567890123456789",
            quality = "1080p WEB-DL x265",
            size = "1.5 GiB",
            seeders = 87,
            peers = 12,
            provider = "TorBox",
            language = "English",
        ).toStreamItem(scraper())

        val facts = SourceFactsExtractor.extract(stream)

        assertEquals("1080p WEB-DL x265 • 1.5 GiB • English", stream.description)
        assertEquals(1_610_612_736L, stream.pluginMeta?.sizeBytes)
        assertEquals(VideoResolution.FULL_HD_1080, facts.resolution)
        assertEquals("HEVC", facts.codec)
        assertEquals("WEB-DL", facts.releaseQuality)
        assertEquals(setOf("EN"), facts.languages)
        assertEquals(87, facts.seeders)
        assertEquals("TorBox", facts.providerId)
        assertTrue(SourceFactProvenance.PLUGIN_STRUCTURED in facts.provenance)
    }

    private fun scraper() = PluginScraper(
        id = "scraper",
        repositoryUrl = "https://plugins.example/manifest.json",
        name = "Plugin",
        description = "",
        version = "1",
        filename = "plugin.js",
        supportedTypes = listOf("movie", "tv"),
        enabled = true,
        manifestEnabled = true,
        code = "",
    )
}
