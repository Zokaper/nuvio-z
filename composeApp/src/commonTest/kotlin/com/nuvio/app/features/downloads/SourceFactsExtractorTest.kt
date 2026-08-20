package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.AioAddonIdentity
import com.nuvio.app.features.streams.AioParsedFile
import com.nuvio.app.features.streams.AioStreamData
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamClientResolve
import com.nuvio.app.features.streams.StreamClientResolveParsed
import com.nuvio.app.features.streams.StreamClientResolveRaw
import com.nuvio.app.features.streams.StreamClientResolveStream
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceFactsExtractorTest {
    @Test
    fun structuredNuvioTakesPriorityAndLargestSizeEnforcesCap() {
        val stream = stream(
            behaviorHints = StreamBehaviorHints(
                filename = "Movie.720p.x264.1.2GB.mkv",
                videoSize = 1_200_000_000,
            ),
            clientResolve = StreamClientResolve(
                stream = StreamClientResolveStream(
                    raw = StreamClientResolveRaw(
                        size = 1_500_000_000,
                        parsed = StreamClientResolveParsed(
                            resolution = "1080p",
                            codec = "HEVC",
                            hdr = listOf("HDR10"),
                            languages = listOf("eng"),
                            quality = "WEB-DL",
                        ),
                    ),
                ),
            ),
        )

        val facts = SourceFactsExtractor.extract(stream)

        assertEquals(VideoResolution.FULL_HD_1080, facts.resolution)
        assertEquals(1_500_000_000, facts.sizeBytes)
        assertEquals("HEVC", facts.codec)
        assertEquals(setOf("en"), facts.languages)
        assertTrue(facts.hasConflictingHardMetadata)
        assertTrue(SourceFactProvenance.NUVIO_STRUCTURED in facts.provenance)
    }

    @Test
    fun parsesStructuredAioWithoutDisplayTemplateDependency() {
        val payload = """
            {"streams":[{
              "name":"anything the user configured",
              "url":"https://cdn.example/video.mkv",
              "streamData":{
                "addon":{"id":"torrentio","name":"Torrentio"},
                "size":2100000000,
                "debrid":{"service":"realdebrid","cached":true},
                "parsedFile":{
                  "title":"Movie.Custom.mkv",
                  "resolution":"2160p",
                  "codec":"x265",
                  "hdr":["DV"],
                  "languages":["English"],
                  "quality":"REMUX",
                  "futureField":"ignored"
                },
                "unknown":"ignored"
              }
            }]}
        """.trimIndent()

        val stream = StreamParser.parse(payload, "Renamed instance", "community.aiostreams")[0]
        val facts = SourceFactsExtractor.extract(
            stream,
            AioDetectionContext(
                manifestId = "community.aiostreams",
                manifestName = "Renamed instance",
                manifestUrl = "https://aio.example/manifest.json",
            ),
        )

        assertTrue(facts.isAioStreams)
        assertEquals("torrentio", facts.providerId)
        assertEquals("Torrentio", facts.providerName)
        assertEquals(VideoResolution.UHD_2160, facts.resolution)
        assertEquals("HEVC", facts.codec)
        assertEquals(true, facts.isDebridReady)
    }

    @Test
    fun missingAioStreamDataDoesNotInventProvider() {
        val facts = SourceFactsExtractor.extract(
            stream(behaviorHints = StreamBehaviorHints(filename = "Show.S01E01.1080p.mkv")),
            AioDetectionContext(
                manifestId = "self.hosted",
                manifestName = "My streams",
                manifestUrl = "https://self.example/manifest.json",
                treatAsAioStreams = true,
            ),
        )

        assertTrue(facts.isAioStreams)
        assertNull(facts.providerId)
        assertNull(facts.providerName)
        assertEquals(VideoResolution.FULL_HD_1080, facts.resolution)
    }

    @Test
    fun standardFilenameAndDisplayFallbackAreLayered() {
        val filename = SourceFactsExtractor.extract(
            stream(behaviorHints = StreamBehaviorHints(filename = "Film.720p.AV1.French.WEBRip.mkv")),
        )
        val display = SourceFactsExtractor.extract(
            stream(name = "2160p HEVC HDR English", description = "4.0 GB"),
        )

        assertEquals(VideoResolution.HD_720, filename.resolution)
        assertEquals("AV1", filename.codec)
        assertEquals(setOf("fr"), filename.languages)
        assertEquals(VideoResolution.UHD_2160, display.resolution)
        assertEquals(SourceConfidence.LOW, display.confidence)
        assertTrue(SourceFactProvenance.DISPLAY_FALLBACK in display.provenance)
    }

    @Test
    fun readsLanguagesTheOldSevenEntryTableCouldNotSee() {
        // The extractor knew en/ar/es/fr/de/ja/ko, so every other language read as "declares
        // nothing" - indistinguishable from an untagged English release, which is why a strict
        // preference had nothing to act on.
        val hindi = SourceFactsExtractor.extract(
            stream(behaviorHints = StreamBehaviorHints(filename = "Film.2024.1080p.HIN.WEB-DL.mkv")),
        )
        val italian = SourceFactsExtractor.extract(
            stream(behaviorHints = StreamBehaviorHints(filename = "Film.2024.1080p.ITA.BluRay.mkv")),
        )

        assertEquals(setOf("hi"), hindi.languages)
        assertEquals(setOf("it"), italian.languages)
    }

    @Test
    fun aMultiMarkerIsCarriedSeparatelyFromTheLanguages() {
        val facts = SourceFactsExtractor.extract(
            stream(behaviorHints = StreamBehaviorHints(filename = "Film.2024.2160p.MULTi.REMUX.mkv")),
        )

        assertTrue(facts.isMultiLanguage)
        assertTrue(facts.languages.isEmpty())
    }

    @Test
    fun readsFlagEmojiOutOfADisplayName() {
        // How Torrentio and friends label audio, and the app had no regional-indicator handling
        // anywhere - so every one of those releases declared nothing.
        val facts = SourceFactsExtractor.extract(stream(name = "🇬🇧 1080p WEB-DL", description = "2.0 GB"))

        assertEquals(setOf("en"), facts.languages)
    }

    @Test
    fun aStructuredMarketNameNoLongerBecomesAnUnmatchableString() {
        // `normalizeLanguageValues` used to `uppercase()` whatever it did not recognise, so an
        // addon sending ["Latino"] produced "LATINO" - a value no preference could ever equal,
        // on a source that had said exactly what it was.
        val facts = SourceFactsExtractor.extract(
            stream(
                streamData = AioStreamData(
                    parsedFile = AioParsedFile(languages = listOf("Latino")),
                ),
            ),
        )

        assertEquals(setOf("es-419"), facts.languages)
    }

    @Test
    fun verifiedSizeNeverLowersReportedCapSize() {
        val facts = SourceFactsExtractor.extract(
            stream(behaviorHints = StreamBehaviorHints(videoSize = 3_000)),
            verifiedSizeBytes = 2_000,
        )
        assertEquals(3_000, facts.sizeBytes)
        assertTrue(SourceFactProvenance.HTTP_VERIFIED in facts.provenance)
    }

    @Test
    fun roundedDisplaySizeDoesNotConflictWithStructuredAioBytes() {
        val exactSize = 1_073_741_824L
        val facts = SourceFactsExtractor.extract(
            stream(
                description = "1080p • 1.07 GB",
                streamData = AioStreamData(
                    addon = AioAddonIdentity(id = "torrentio", name = "Torrentio"),
                    size = exactSize,
                    parsedFile = AioParsedFile(resolution = "1080p"),
                ),
            ),
        )

        assertEquals(exactSize, facts.sizeBytes)
        assertEquals(listOf(exactSize), facts.hardReportedSizes)
        assertEquals(2, facts.reportedSizes.size)
        assertFalse(facts.hasConflictingHardMetadata)
    }

    @Test
    fun httpVerificationToleratesEquivalentBytesButFlagsMaterialDifference() {
        val original = SourceFactsExtractor.extract(
            stream(behaviorHints = StreamBehaviorHints(videoSize = 1_000_000_000L)),
        )

        val equivalent = original.withVerifiedSize(1_010_000_000L)
        val contradictory = original.withVerifiedSize(1_100_000_000L)

        assertFalse(equivalent.hasConflictingHardMetadata)
        assertEquals(1_010_000_000L, equivalent.sizeBytes)
        assertTrue(contradictory.hasConflictingHardMetadata)
        assertEquals(1_100_000_000L, contradictory.sizeBytes)
    }

    @Test
    fun aioDetectionAndHeadersSupportOverrides() {
        val ordinary = AioDetectionContext("other", "Other", "https://x/manifest.json")
        val override = ordinary.copy(treatAsAioStreams = true)
        assertFalse(AioStreamsSupport.isAioStreams(ordinary))
        assertTrue(AioStreamsSupport.isAioStreams(override))
        assertEquals(AioStreamsSupport.ENHANCED_METADATA_USER_AGENT, AioStreamsSupport.requestHeaders(override)["User-Agent"])
    }

    @Test
    fun releaseGroupPrefersStructuredValueThenUsesHyphenatedFilenameSuffix() {
        val structured = SourceFactsExtractor.extract(
            stream(
                behaviorHints = StreamBehaviorHints(filename = "Show.S01E01.1080p-WRONG.mkv"),
                clientResolve = StreamClientResolve(
                    stream = StreamClientResolveStream(
                        raw = StreamClientResolveRaw(
                            parsed = StreamClientResolveParsed(group = "RIGHT"),
                        ),
                    ),
                ),
            ),
        )
        val filename = SourceFactsExtractor.extract(
            stream(behaviorHints = StreamBehaviorHints(filename = "Show.S01E02.1080p-GROUP.mkv")),
        )
        val titleWords = SourceFactsExtractor.extract(
            stream(behaviorHints = StreamBehaviorHints(filename = "THE LAST OF US S01E03.mkv")),
        )

        assertEquals("RIGHT", structured.releaseGroup)
        assertEquals("GROUP", filename.releaseGroup)
        assertNull(titleWords.releaseGroup)
    }

    private fun stream(
        name: String? = null,
        description: String? = null,
        behaviorHints: StreamBehaviorHints = StreamBehaviorHints(),
        clientResolve: StreamClientResolve? = null,
        streamData: AioStreamData? = null,
    ) = StreamItem(
        name = name,
        description = description,
        url = "https://example.com/video.mkv",
        addonName = "Addon",
        addonId = "addon",
        behaviorHints = behaviorHints,
        clientResolve = clientResolve,
        streamData = streamData,
    )
}
