package com.nuvio.app.features.debrid

import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.AioAddonIdentity
import com.nuvio.app.features.streams.AioParsedFile
import com.nuvio.app.features.streams.AioStreamData
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamClientResolve
import com.nuvio.app.features.streams.StreamClientResolveParsed
import com.nuvio.app.features.streams.StreamClientResolveRaw
import com.nuvio.app.features.streams.StreamClientResolveStream
import com.nuvio.app.features.streams.StreamBadge
import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamDebridCacheStatus
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DebridStreamPresentationTest {
    @Test
    fun `formats cached addon torrent streams with custom templates`() {
        val stream = localTorboxStream(
            filename = "Lost.S01E01.2160p.WEB-DL.H265.AAC-NAKSU.mkv",
            size = 8_589_934_592,
        )

        val formatted = DebridStreamFormatter().format(
            stream = stream,
            settings = DebridSettings(
                enabled = true,
                providerApiKeys = mapOf(DebridProviders.TORBOX_ID to "key"),
                streamNameTemplate = "{stream.resolution} {service.shortName} {service.cached::istrue[\"Ready\"||\"Not Ready\"]}",
                streamDescriptionTemplate = "{stream.quality} {stream.encode}\n{stream.size::bytes}\n{stream.filename}",
            ),
        )

        assertEquals("2160p TB Ready", formatted.name)
        val description = formatted.description.orEmpty()
        assertContains(description, "WEB-DL HEVC")
        assertContains(description, "8 GB")
        assertContains(description, "Lost.S01E01.2160p.WEB-DL.H265.AAC-NAKSU.mkv")
    }

    @Test
    fun `blank templates preserve original stream name and description`() {
        val stream = localTorboxStream(
            name = "Original torrent",
            filename = "Movie.2026.1080p.WEB-DL.H265-GRP.mkv",
            size = 4_000_000_000,
        ).copy(description = "Original addon details")

        val formatted = DebridStreamFormatter().format(
            stream = stream,
            settings = DebridSettings(
                enabled = true,
                providerApiKeys = mapOf(DebridProviders.TORBOX_ID to "key"),
                streamNameTemplate = "",
                streamDescriptionTemplate = "",
            ),
        )

        assertEquals("Original torrent", formatted.name)
        assertEquals("Original addon details", formatted.description)
    }

    @Test
    fun `formats existing stream badges in template values`() {
        val stream = localTorboxStream(
            filename = "Movie.2024.2160p.BluRay.REMUX.TrueHD.7.1-GRP.mkv",
            size = 40_000_000_000,
        ).copy(
            badges = listOf(
                StreamBadge(
                    name = "TRUEHD",
                    imageURL = "https://example.test/truehd.png",
                ),
            ),
        )

        val formatted = DebridStreamFormatter().format(
            stream = stream,
            settings = DebridSettings(
                enabled = true,
                providerApiKeys = mapOf(DebridProviders.TORBOX_ID to "key"),
                streamNameTemplate = "{stream.rseMatched::join(' | ')}",
                streamDescriptionTemplate = "{stream.regexMatched::~TRUEHD[\"has-truehd\"||\"missing-truehd\"]}",
            ),
        )

        assertEquals("TRUEHD", formatted.name)
        assertEquals("has-truehd", formatted.description)
        assertEquals(listOf("TRUEHD"), formatted.badges.map { it.name })
        assertEquals(listOf("https://example.test/truehd.png"), formatted.badges.map { it.imageURL })
    }

    @Test
    fun `default formatter replaces addon source labels for managed streams`() {
        val stream = premiumizeDirectStream(
            name = "[P2P] Torrentio 2160p - PM Instant",
            filename = "The.Boys.S03E01.Payback.2160p.WEB-DL.H265.mkv",
            size = 12_000_000_000,
        )

        val presented = DebridStreamPresentation.apply(
            groups = listOf(
                AddonStreamGroup(
                    addonName = "Torrentio",
                    addonId = "addon:torrentio",
                    streams = listOf(stream),
                ),
            ),
            settings = DebridSettings(
                enabled = true,
                providerApiKeys = mapOf(DebridProviders.PREMIUMIZE_ID to "pm_key"),
            ),
        ).single().streams.single()

        val name = presented.name.orEmpty()
        assertEquals("2160p PM Instant", name)
        assertFalse(name.contains("P2P", ignoreCase = true))
        assertFalse(name.contains("torrent", ignoreCase = true))
        assertFalse(name.contains("Torrentio", ignoreCase = true))
        assertFalse(name.contains("Comet", ignoreCase = true))
    }

    @Test
    fun `preserves original addon order by default`() {
        val low = localTorboxStream(
            name = "Low",
            filename = "Movie.720p.BluRay.x264-GRP.mkv",
            size = 4_000_000_000,
        )
        val large = localTorboxStream(
            name = "Large",
            filename = "Movie.2160p.BluRay.REMUX.HEVC-GRP.mkv",
            size = 40_000_000_000,
        )
        val mid = localTorboxStream(
            name = "Mid",
            filename = "Movie.1080p.WEB-DL.HEVC-GRP.mkv",
            size = 10_000_000_000,
        )

        val presented = DebridStreamPresentation.apply(
            groups = listOf(
                AddonStreamGroup(
                    addonName = "Addon",
                    addonId = "addon:test",
                    streams = listOf(low, large, mid),
                ),
            ),
            settings = DebridSettings(
                enabled = true,
                providerApiKeys = mapOf(DebridProviders.TORBOX_ID to "key"),
            ),
        ).single().streams

        assertEquals(listOf("720p TB Instant", "2160p TB Instant", "1080p TB Instant"), presented.map { it.name })
    }

    @Test
    fun `sorts by best quality when quality criteria are selected`() {
        val low = localTorboxStream(
            name = "Low",
            filename = "Movie.720p.BluRay.x264-GRP.mkv",
            size = 4_000_000_000,
        )
        val large = localTorboxStream(
            name = "Large",
            filename = "Movie.2160p.BluRay.REMUX.HEVC-GRP.mkv",
            size = 40_000_000_000,
        )
        val mid = localTorboxStream(
            name = "Mid",
            filename = "Movie.1080p.WEB-DL.HEVC-GRP.mkv",
            size = 10_000_000_000,
        )

        val presented = DebridStreamPresentation.apply(
            groups = listOf(
                AddonStreamGroup(
                    addonName = "Addon",
                    addonId = "addon:test",
                    streams = listOf(low, large, mid),
                ),
            ),
            settings = DebridSettings(
                enabled = true,
                providerApiKeys = mapOf(DebridProviders.TORBOX_ID to "key"),
                streamPreferences = DebridStreamPreferences(
                    sortCriteria = DebridStreamSortCriterion.defaultOrder,
                ),
            ),
        ).single().streams

        assertEquals(listOf("2160p TB Instant", "1080p TB Instant", "720p TB Instant"), presented.map { it.name })
    }

    @Test
    fun `applies debrid sort filters and limits without removing normal urls`() {
        val low = localTorboxStream(
            name = "Low",
            filename = "Movie.720p.BluRay.x264-GRP.mkv",
            size = 4_000_000_000,
        )
        val large = localTorboxStream(
            name = "Large",
            filename = "Movie.2160p.BluRay.REMUX.HEVC-GRP.mkv",
            size = 40_000_000_000,
        )
        val mid = localTorboxStream(
            name = "Mid",
            filename = "Movie.1080p.WEB-DL.HEVC-GRP.mkv",
            size = 10_000_000_000,
        )
        val urlStream = StreamItem(
            name = "Resolved addon URL",
            url = "https://example.test/video.m3u8",
            addonName = "Addon",
            addonId = "addon:test",
        )

        val group = AddonStreamGroup(
            addonName = "Addon",
            addonId = "addon:test",
            streams = listOf(low, large, mid, urlStream),
        )
        val presented = DebridStreamPresentation.apply(
            groups = listOf(group),
            settings = DebridSettings(
                enabled = true,
                providerApiKeys = mapOf(DebridProviders.TORBOX_ID to "key"),
                streamPreferenceScope = DebridStreamPreferenceScope.RESOLVER_ONLY,
                streamMaxResults = 2,
                streamSortMode = DebridStreamSortMode.QUALITY_DESC,
                streamMinimumQuality = DebridStreamMinimumQuality.P1080,
                streamCodecFilter = DebridStreamCodecFilter.HEVC,
            ),
        ).single().streams

        assertEquals(listOf("2160p TB Instant", "1080p TB Instant", "Resolved addon URL"), presented.map { it.name })
    }

    @Test
    fun `hides addon torrent streams that are not cached`() {
        val cached = localTorboxStream(
            name = "Cached",
            filename = "Movie.1080p.WEB-DL.HEVC-GRP.mkv",
            size = 10_000_000_000,
        )
        val uncached = localTorboxStream(
            name = "Uncached",
            filename = "Movie.2160p.WEB-DL.HEVC-GRP.mkv",
            size = 20_000_000_000,
            cacheState = StreamDebridCacheState.NOT_CACHED,
        )

        val presented = DebridStreamPresentation.apply(
            groups = listOf(
                AddonStreamGroup(
                    addonName = "Addon",
                    addonId = "addon:test",
                    streams = listOf(cached, uncached),
                ),
            ),
            settings = DebridSettings(
                enabled = true,
                providerApiKeys = mapOf(DebridProviders.TORBOX_ID to "key"),
            ),
        ).single().streams

        assertEquals(listOf("1080p TB Instant"), presented.map { it.name })
    }

    @Test
    fun `leaves cloud-service results untouched when link resolving is off`() {
        val uncached = localTorboxStream(
            name = "Uncached",
            filename = "Movie.2160p.WEB-DL.HEVC-GRP.mkv",
            size = 20_000_000_000,
            cacheState = StreamDebridCacheState.NOT_CACHED,
        )

        val presented = DebridStreamPresentation.apply(
            groups = listOf(
                AddonStreamGroup(
                    addonName = "Addon",
                    addonId = "addon:test",
                    streams = listOf(uncached),
                ),
            ),
            settings = DebridSettings(
                enabled = false,
                providerApiKeys = mapOf(DebridProviders.TORBOX_ID to "key"),
                streamPreferenceScope = DebridStreamPreferenceScope.RESOLVER_ONLY,
            ),
        ).single().streams

        assertEquals(listOf("Uncached"), presented.map { it.name })
    }

    @Test
    fun `filters and sorts addon-side debrid results with no resolver connected`() {
        val group = AddonStreamGroup(
            addonName = "AIOStreams",
            addonId = "addon:aiostreams",
            streams = listOf(
                aioStream(name = "720p row", filename = "Show.S01E01.720p.WEB-DL.H264-GRP.mkv", size = 1_500_000_000),
                aioStream(name = "1080p row", filename = "Show.S01E01.1080p.WEB-DL.H265-GRP.mkv", size = 4_000_000_000),
                aioStream(name = "2160p row", filename = "Show.S01E01.2160p.WEB-DL.H265-GRP.mkv", size = 18_000_000_000),
            ),
        )

        val presented = DebridStreamPresentation.apply(
            groups = listOf(group),
            settings = DebridSettings(
                enabled = false,
                providerApiKeys = emptyMap(),
                streamSortMode = DebridStreamSortMode.QUALITY_DESC,
                streamMinimumQuality = DebridStreamMinimumQuality.P1080,
            ),
        ).single().streams

        assertEquals(listOf("2160p AD Instant", "1080p AD Instant"), presented.map { it.name })
    }

    @Test
    fun `renders the addon-reported service short name`() {
        val presented = DebridStreamPresentation.apply(
            groups = listOf(
                AddonStreamGroup(
                    addonName = "AIOStreams",
                    addonId = "addon:aiostreams",
                    streams = listOf(
                        aioStream(filename = "Show.S01E01.2160p.WEB-DL.H265-GRP.mkv", size = 18_000_000_000),
                    ),
                ),
            ),
            settings = DebridSettings(enabled = false, providerApiKeys = emptyMap()),
        ).single().streams.single()

        assertEquals("2160p AD Instant", presented.name)
    }

    @Test
    fun `sources size resolution and filename from addon stream data`() {
        val presented = DebridStreamPresentation.apply(
            groups = listOf(
                AddonStreamGroup(
                    addonName = "AIOStreams",
                    addonId = "addon:aiostreams",
                    streams = listOf(
                        aioStream(filename = "Show.S01E01.2160p.WEB-DL.H265-GRP.mkv", size = 17_179_869_184),
                    ),
                ),
            ),
            settings = DebridSettings(
                enabled = false,
                providerApiKeys = emptyMap(),
                streamNameTemplate = "{stream.resolution} {stream.quality} {stream.encode} {stream.size::bytes}",
                streamDescriptionTemplate = "{stream.filename}\n{stream.indexer}\n{service.cached::istrue[\"Ready\"||\"Not Ready\"]}",
            ),
        ).single().streams.single()

        assertEquals("2160p WEB-DL HEVC 16 GB", presented.name)
        val description = presented.description.orEmpty()
        assertContains(description, "Show.S01E01.2160p.WEB-DL.H265-GRP.mkv")
        assertContains(description, "Torrentio")
        assertContains(description, "Ready")
    }

    @Test
    fun `plain addon results keep their own name under the default templates`() {
        // The default name template renders "Cloud Instant" for anything without a service, so
        // widening the scope must not rename ordinary addon rows.
        val plain = StreamItem(
            name = "Some addon result",
            url = "https://example.test/video.m3u8",
            addonName = "Addon",
            addonId = "addon:test",
        )

        val presented = DebridStreamPresentation.apply(
            groups = listOf(
                AddonStreamGroup(addonName = "Addon", addonId = "addon:test", streams = listOf(plain)),
            ),
            settings = DebridSettings(enabled = false, providerApiKeys = emptyMap()),
        ).single().streams

        assertEquals(listOf("Some addon result"), presented.map { it.name })
    }

    @Test
    fun `plain addon results are formatted once a template is customized`() {
        val plain = StreamItem(
            name = "Some addon result",
            url = "https://example.test/video.mkv",
            addonName = "Addon",
            addonId = "addon:test",
            behaviorHints = StreamBehaviorHints(filename = "Movie.2026.1080p.WEB-DL.H265-GRP.mkv"),
        )

        val presented = DebridStreamPresentation.apply(
            groups = listOf(
                AddonStreamGroup(addonName = "Addon", addonId = "addon:test", streams = listOf(plain)),
            ),
            settings = DebridSettings(
                enabled = false,
                providerApiKeys = emptyMap(),
                streamNameTemplate = "{stream.resolution} {stream.encode}",
            ),
        ).single().streams

        assertEquals(listOf("1080p HEVC"), presented.map { it.name })
    }

    @Test
    fun `unresolved magnets are left alone under the widest scope`() {
        val magnet = StreamItem(
            name = "Magnet row",
            url = "magnet:?xt=urn:btih:abcdef1234567890abcdef1234567890abcdef12",
            addonName = "Addon",
            addonId = "addon:test",
        )

        val presented = DebridStreamPresentation.apply(
            groups = listOf(
                AddonStreamGroup(addonName = "Addon", addonId = "addon:test", streams = listOf(magnet)),
            ),
            settings = DebridSettings(
                enabled = false,
                providerApiKeys = emptyMap(),
                streamMinimumQuality = DebridStreamMinimumQuality.P1080,
            ),
        ).single().streams

        assertEquals(listOf("Magnet row"), presented.map { it.name })
    }

    @Test
    fun `resolver-only scope reproduces the old gate`() {
        val presented = DebridStreamPresentation.apply(
            groups = listOf(
                AddonStreamGroup(
                    addonName = "AIOStreams",
                    addonId = "addon:aiostreams",
                    streams = listOf(
                        aioStream(name = "Untouched", filename = "Show.S01E01.720p.WEB-DL.H264-GRP.mkv", size = 1_500_000_000),
                    ),
                ),
            ),
            settings = DebridSettings(
                enabled = false,
                providerApiKeys = emptyMap(),
                streamPreferenceScope = DebridStreamPreferenceScope.RESOLVER_ONLY,
                streamMinimumQuality = DebridStreamMinimumQuality.P1080,
            ),
        ).single().streams

        assertEquals(listOf("Untouched"), presented.map { it.name })
    }

    @Test
    fun `running without a resolver does not start hiding results`() {
        // The inactive-resolver filter needs an active provider to compare against, so it must
        // stay inert now that the pipeline runs for a user who has none. (The uncached-torrent
        // filter needs a debridCacheStatus, which only the resolver-gated availability service
        // ever writes.)
        val aio = aioStream(name = "AIO", filename = "Show.S01E01.1080p.WEB-DL.H265-GRP.mkv", size = 4_000_000_000)
        val otherProvider = premiumizeDirectStream(
            name = "Premiumize row",
            filename = "Show.S01E01.2160p.WEB-DL.H265-GRP.mkv",
            size = 20_000_000_000,
        )

        val presented = DebridStreamPresentation.apply(
            groups = listOf(
                AddonStreamGroup(addonName = "Addon", addonId = "addon:test", streams = listOf(aio, otherProvider)),
            ),
            settings = DebridSettings(enabled = false, providerApiKeys = emptyMap()),
        ).single().streams

        assertEquals(2, presented.size)
        assertContains(presented.map { it.name.orEmpty() }, "2160p PM Instant")
    }

    private fun aioStream(
        name: String = "AIO result",
        filename: String,
        size: Long,
        service: String = "alldebrid",
        cached: Boolean = true,
    ): StreamItem =
        StreamItem(
            name = name,
            url = "https://aio.test/$filename",
            addonName = "AIOStreams",
            addonId = "addon:aiostreams",
            streamData = AioStreamData(
                addon = AioAddonIdentity(id = "torrentio", name = "Torrentio"),
                parsedFile = AioParsedFile(
                    resolution = filename.substringAfter("E01.").substringBefore('.'),
                    quality = "WEB-DL",
                    codec = if ("H265" in filename) "HEVC" else "AVC",
                    languages = listOf("English"),
                    title = "Show",
                    size = size,
                ),
                size = size,
                filename = filename,
                debridService = service,
                debridCached = cached,
            ),
        )

    private fun localTorboxStream(
        name: String = "Torrent",
        filename: String,
        size: Long,
        cacheState: StreamDebridCacheState = StreamDebridCacheState.CACHED,
    ): StreamItem =
        StreamItem(
            name = name,
            infoHash = "abcdef1234567890abcdef1234567890abcdef12$size".take(40),
            addonName = "Addon",
            addonId = "addon:test",
            behaviorHints = StreamBehaviorHints(
                filename = filename,
                videoSize = size,
            ),
            debridCacheStatus = StreamDebridCacheStatus(
                providerId = DebridProviders.TORBOX_ID,
                providerName = DebridProviders.Torbox.displayName,
                state = cacheState,
                cachedName = filename,
                cachedSize = size,
            ),
        )

    private fun premiumizeDirectStream(
        name: String,
        filename: String,
        size: Long,
    ): StreamItem =
        StreamItem(
            name = name,
            addonName = "Torrentio",
            addonId = "addon:torrentio",
            clientResolve = StreamClientResolve(
                type = "debrid",
                service = DebridProviders.PREMIUMIZE_ID,
                filename = filename,
                isCached = true,
                stream = StreamClientResolveStream(
                    raw = StreamClientResolveRaw(
                        filename = filename,
                        size = size,
                        parsed = StreamClientResolveParsed(
                            resolution = "2160p",
                        ),
                    ),
                ),
            ),
        )
}
