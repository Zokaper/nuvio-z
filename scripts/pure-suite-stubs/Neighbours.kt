// Stubbed *neighbours* for running the shipped playback selection sources outside Gradle, per
// AGENTS.md "Verifying without Gradle", item 2. Nothing under test is stubbed:
// PlaybackQualityOptions.kt, PlaybackSourceSelector.kt and SourceRanking.kt are the real
// shipped files, unmodified.
//
// The real SourceFacts/StreamItem reach kotlinx.serialization, the whole stream stack and the
// generated Compose resource bundle, none of which this logic touches. Every member below is
// shaped to match the real declaration for the fields these three files actually read.

package com.nuvio.app.features.downloads

enum class VideoResolution(val height: Int) {
    SD(480),
    HD_720(720),
    FULL_HD_1080(1080),
    QHD_1440(1440),
    UHD_2160(2160),
    UHD_4320(4320);
}

enum class CodecPreference { ANY, HEVC, AV1, AVC }

enum class DynamicRangePolicy { ANY, AVOID_HDR, PREFER_HDR, REQUIRE_HDR, REQUIRE_DOLBY_VISION }

enum class SizePreference { LARGEST_UNDER_CAP, MID_RANGE, SMALLEST }

data class SourceFacts(
    val resolution: VideoResolution? = null,
    val sizeBytes: Long? = null,
    val durationSeconds: Long? = null,
    val codec: String? = null,
    val dynamicRange: Set<String> = emptySet(),
    val audioCodecs: Set<String> = emptySet(),
    val audioChannels: Int? = null,
    val languages: Set<String> = emptySet(),
    val isMultiLanguage: Boolean = false,
    val subtitleLanguages: Set<String> = emptySet(),
    val releaseQuality: String? = null,
    val releaseGroup: String? = null,
    val seeders: Int? = null,
    val providerId: String? = null,
    val providerName: String? = null,
    val debridService: String? = null,
    val isDebridReady: Boolean? = null,
    // The release name. Added when the playback loading screen started printing it, which is
    // how a wrong-show pick becomes visible before it plays - so it is now load-bearing rather
    // than incidental, and the stub has to carry it.
    val filename: String? = null,
)

object SourceFactsExtractor {
    fun extract(stream: com.nuvio.app.features.streams.StreamItem): SourceFacts = SourceFacts()
}
