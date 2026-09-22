package com.nuvio.app.features.watchparty

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PartySourceDescriptorV2Test {
    private val hashA = "0123456789abcdef0123456789abcdef01234567"
    private val hashB = "1123456789abcdef0123456789abcdef01234567"
    private val repositoryJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun fingerprintIsStableAcrossEquivalentPresentation() {
        assertEquals(
            partyReleaseFingerprint("Movie.Name.2026.2160p-WEB DL"),
            partyReleaseFingerprint("movie name 2026 2160P WEB-DL"),
        )
    }

    @Test fun fingerprintDropsTransientAndDebridLabels() {
        assertEquals(
            partyReleaseFingerprint("Movie.Name.2160p"),
            partyReleaseFingerprint("Movie.Name.2160p Real-Debrid seeders: 42"),
        )
    }

    @Test fun descriptorRejectsUrlsAndMalformedHashes() {
        assertFailsWith<IllegalArgumentException> { descriptor(originId = "https://secret.example/manifest.json") }
        assertFailsWith<IllegalArgumentException> { descriptor(infoHash = "not-a-hash") }
    }

    @Test fun exactTorrentRequiresExactFileIndex() {
        val host = descriptor(infoHash = hashA, fileIndex = 4)
        assertEquals(PartySourceMatchTier.ExactTorrentFile, partySourceMatchTier(host, descriptor(infoHash = hashA, fileIndex = 4)))
        assertEquals(PartySourceMatchTier.None, partySourceMatchTier(host, descriptor(infoHash = hashA, fileIndex = 5)))
        assertEquals(PartySourceMatchTier.None, partySourceMatchTier(host, descriptor(infoHash = hashA, fileIndex = null)))
    }

    @Test fun unknownTorrentFileIndexRemainsNullAcrossRepositorySerialization() {
        val descriptor = descriptor(infoHash = hashA, fileIndex = null)
        val encoded = repositoryJson.parseToJsonElement(repositoryJson.encodeToString(descriptor)).jsonObject
        assertEquals(hashA, encoded.getValue("info_hash").toString().trim('"'))
        assertSame(JsonNull, encoded.getValue("file_index"))
        assertNull(repositoryJson.decodeFromString<PartySourceDescriptorV2>(encoded.toString()).fileIndex)
        assertEquals(
            PartySourceMatchTier.None,
            partySourceMatchTier(descriptor(infoHash = hashA, fileIndex = 3), descriptor),
        )
    }

    @Test fun nonTorrentNullIdentityFieldsMatchRepositoryWireShape() {
        val encoded = repositoryJson.parseToJsonElement(repositoryJson.encodeToString(descriptor())).jsonObject
        assertSame(JsonNull, encoded.getValue("info_hash"))
        assertSame(JsonNull, encoded.getValue("file_index"))
    }

    @Test fun invalidFileIndexRelationshipsCannotEnterTheClientModel() {
        assertFailsWith<IllegalArgumentException> { descriptor(infoHash = hashA, fileIndex = -1) }
        assertFailsWith<IllegalArgumentException> { descriptor(infoHash = null, fileIndex = 0) }
    }

    @Test fun originReleasePrecedesCrossOriginRelease() {
        val host = descriptor(originId = "one")
        assertEquals(PartySourceMatchTier.ExactOriginRelease, partySourceMatchTier(host, descriptor(originId = "one")))
        assertEquals(PartySourceMatchTier.ExactRelease, partySourceMatchTier(host, descriptor(originId = "two")))
    }

    @Test fun normalRankNeverMovesFallbackAboveExact() {
        val host = descriptor(originId = "one")
        val fallback = PartySourceCandidate(
            "fallback",
            descriptor(originId = "two",release = "other",media = PartySourceMedia(resolution="1080p",codec="av1")),
            normalRank = 10_000,
        )
        val exact = PartySourceCandidate("exact", descriptor(originId = "one"), normalRank = 1)
        val result = tierPartySourceCandidates(host, listOf(fallback, exact))
        assertEquals(PartySourceMatchTier.ExactOriginRelease, result.tier)
        assertEquals(listOf("exact"), result.candidates.map { it.value })
        assertEquals("fallback", result.fallback?.value)
    }

    @Test fun everyRealizationInStrongestTierIsRetained() {
        val host = descriptor(originId = "one")
        val first = PartySourceCandidate("a", descriptor(originId = "one"), normalRank = 1)
        val second = PartySourceCandidate("b", descriptor(originId = "one", infoHash = hashB, fileIndex = 2), normalRank = 2)
        val result = tierPartySourceCandidates(host, listOf(first, second))
        assertEquals(listOf("b", "a"), result.candidates.map { it.value })
    }

    @Test fun constrainedFallbackRejectsUnsafeOrWrongContent() {
        val host = descriptor()
        val unsafe = PartySourceCandidate("unsafe", descriptor(release = "x"),100,protocolSafe=false)
        val wrong = PartySourceCandidate("wrong", descriptor(release = "y"),90,contentMatches=false)
        val result = tierPartySourceCandidates(host,listOf(unsafe,wrong))
        assertEquals(PartySourceMatchTier.None,result.tier)
        assertNull(result.fallback)
    }

    @Test fun equivalentMediaRequiresThreeStrongSignalsAndNoContradiction() {
        val media = PartySourceMedia(resolution="2160p",releaseQuality="web-dl",codec="hevc",sizeBytes=10_000_000)
        val host = descriptor(release="host",media=media)
        assertEquals(PartySourceMatchTier.EquivalentMedia,partySourceMatchTier(host,descriptor(release="other",media=media)))
        assertEquals(PartySourceMatchTier.Fallback,partySourceMatchTier(host,descriptor(release="other",media=media.copy(codec="av1"))))
    }

    @Test fun durationGateUsesNinetySecondsOrTwoPercent() {
        val host=descriptor()
        val candidate=PartySourceCandidate("too-long",descriptor(release="other"),1,durationMs=800_000)
        val result=tierPartySourceCandidates(host,listOf(candidate),hostDurationMs=600_000)
        assertNull(result.fallback)
        assertTrue(result.candidates.isEmpty())
    }

    private fun descriptor(
        originId: String = "org.example",
        infoHash: String? = null,
        fileIndex: Int? = null,
        release: String = "same",
        media: PartySourceMedia = PartySourceMedia(resolution="2160p",releaseQuality="web-dl",codec="hevc"),
    ) = PartySourceDescriptorV2(
        originKind=PartySourceOriginKind.addon,
        originId=originId,
        infoHash=infoHash,
        fileIndex=fileIndex,
        releaseFingerprint=partyReleaseFingerprint(release),
        media=media,
    )
}
