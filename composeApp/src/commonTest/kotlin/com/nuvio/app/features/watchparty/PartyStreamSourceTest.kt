package com.nuvio.app.features.watchparty

import com.nuvio.app.features.downloads.SourceFacts
import com.nuvio.app.features.downloads.SourceFactsExtractor
import com.nuvio.app.features.streams.AioParsedFile
import com.nuvio.app.features.streams.AioStreamData
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PartyStreamSourceTest {
    private val hash = "0123456789ABCDEF0123456789ABCDEF01234567"

    @Test fun validTorrentIdentityIsNormalizedWithoutChangingItsFileIndex() {
        val descriptor = stream(infoHash = "  $hash  ", fileIdx = 7).toPartySourceDescriptor(facts())
        assertNotNull(descriptor)
        assertEquals(hash.lowercase(), descriptor.infoHash)
        assertEquals(7, descriptor.fileIndex)
    }

    @Test fun unknownTorrentFileIndexIsNotInvented() {
        val descriptor = stream(infoHash = hash).toPartySourceDescriptor(facts())
        assertNotNull(descriptor)
        assertEquals(hash.lowercase(), descriptor.infoHash)
        assertNull(descriptor.fileIndex)
    }

    @Test fun negativeTorrentFileIndexIsNormalizedOut() {
        val descriptor = stream(infoHash = hash, fileIdx = -1).toPartySourceDescriptor(facts())
        assertNotNull(descriptor)
        assertEquals(hash.lowercase(), descriptor.infoHash)
        assertNull(descriptor.fileIndex)
    }

    @Test fun fileIndexWithoutAValidInfoHashIsNormalizedOut() {
        val descriptor = stream(infoHash = null, fileIdx = 4).toPartySourceDescriptor(facts())
        assertNotNull(descriptor)
        assertNull(descriptor.infoHash)
        assertNull(descriptor.fileIndex)
    }

    /**
     * Physical QA, 2026-09-30: host source selection failed with `invalid_source_media` (22023).
     * The backend refuses a media list of more than 16 entries and the client did not bound them.
     * A multi-audio release from an AIOStreams addon names every audio language it carries.
     */
    @Test fun aMultiAudioReleaseNamingManyLanguagesStaysInsideTheBackendContract() {
        val languages = listOf(
            "English", "French", "German", "Spanish", "Italian", "Portuguese", "Russian", "Japanese",
            "Korean", "Chinese", "Arabic", "Hindi", "Turkish", "Polish", "Dutch", "Swedish",
            "Danish", "Norwegian", "Finnish", "Greek",
        )
        val stream = StreamItem(
            name = "Multi Feature 2026 2160p BluRay REMUX DV HDR10 TrueHD Atmos 7.1",
            addonName = "AIOStreams", addonId = "aiostreams.example",
            partyOriginKind = "addon", partyOriginId = "aiostreams.example",
            streamData = AioStreamData(
                parsedFile = AioParsedFile(
                    resolution = "2160p", quality = "BluRay REMUX", codec = "HEVC",
                    hdr = listOf("DV", "HDR10"), audio = listOf("TrueHD", "Atmos"),
                    languages = languages, size = 85_899_345_920,
                ),
            ),
        )
        val facts = SourceFactsExtractor.extract(stream)
        assertTrue(facts.languages.size > PartySourceMediaListLimit, "fixture must exceed the limit")

        val descriptor = assertNotNull(stream.toPartySourceDescriptor(facts))
        assertNull(descriptor.media.contractViolation())
        assertEquals(PartySourceMediaListLimit, descriptor.media.languages.size)
        // Truncation is deterministic: sorted, so desktop, Android and iOS keep the same members.
        assertEquals(descriptor.media.languages, stream.toPartySourceDescriptor(facts)!!.media.languages)
        assertEquals(descriptor.media.languages.sorted(), descriptor.media.languages.toList())
        // Everything source matching reads is untouched by the bound.
        assertEquals("2160p", descriptor.media.resolution)
        assertEquals(85_899_345_920, descriptor.media.sizeBytes)
        assertEquals(
            PartySourceMatchTier.ExactOriginRelease,
            partySourceMatchTier(descriptor, descriptor.copy(media = descriptor.media.copy(languages = setOf("en")))),
        )
    }

    @Test fun theMediaContractNamesTheFieldThatBreaksIt() {
        assertNull(PartySourceMedia(resolution = "2160p", audioChannels = "8", sizeBytes = 1).contractViolation())
        assertEquals("size_bytes", PartySourceMedia(sizeBytes = -1).contractViolation())
        assertEquals("languages(17 > 16)", PartySourceMedia(languages = (1..17).map { "l$it" }.toSet()).contractViolation())
        assertEquals("audio_codecs(item)", PartySourceMedia(audioCodecs = setOf("dts hd")).contractViolation())
        assertEquals("release_quality", PartySourceMedia(releaseQuality = "x".repeat(25)).contractViolation())
    }

    private fun facts() = SourceFacts(filename = "Stage.14.2026.1080p.WEB-DL.mkv")

    private fun stream(infoHash: String?, fileIdx: Int? = null) = StreamItem(
        name = "Stage 14",
        infoHash = infoHash,
        fileIdx = fileIdx,
        addonName = "Example",
        addonId = "org.example",
        partyOriginKind = "addon",
        partyOriginId = "org.example",
    )
}
