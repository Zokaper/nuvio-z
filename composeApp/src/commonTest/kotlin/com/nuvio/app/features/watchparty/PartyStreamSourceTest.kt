package com.nuvio.app.features.watchparty

import com.nuvio.app.features.downloads.SourceFacts
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
