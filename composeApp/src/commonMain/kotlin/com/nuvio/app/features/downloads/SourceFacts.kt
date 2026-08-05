package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.AioParsedFile
import com.nuvio.app.features.streams.StreamClientResolveParsed
import com.nuvio.app.features.streams.StreamItem
import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

@Serializable
enum class SourceConfidence { HIGH, MEDIUM, LOW }

@Serializable
enum class SourceFactProvenance {
    NUVIO_STRUCTURED,
    AIO_STRUCTURED,
    STREMIO_BEHAVIOR_HINT,
    FILENAME,
    DISPLAY_FALLBACK,
    HTTP_VERIFIED,
}

@Serializable
enum class VideoResolution(val height: Int) {
    SD(480),
    HD_720(720),
    FULL_HD_1080(1080),
    QHD_1440(1440),
    UHD_2160(2160),
    UHD_4320(4320);
}

@Serializable
data class SourceFacts(
    val resolution: VideoResolution? = null,
    /** Largest reported or verified size. This is always used for cap enforcement. */
    val sizeBytes: Long? = null,
    val reportedSizes: List<Long> = emptyList(),
    /** Exact byte reports from structured fields, Stremio hints, or HTTP verification. */
    val hardReportedSizes: List<Long> = emptyList(),
    val codec: String? = null,
    val dynamicRange: Set<String> = emptySet(),
    val languages: Set<String> = emptySet(),
    val releaseQuality: String? = null,
    val providerId: String? = null,
    val providerName: String? = null,
    val filename: String? = null,
    val confidence: SourceConfidence = SourceConfidence.LOW,
    val provenance: Set<SourceFactProvenance> = emptySet(),
    val hasConflictingHardMetadata: Boolean = false,
    val isAioStreams: Boolean = false,
    val debridService: String? = null,
    val isDebridReady: Boolean? = null,
) {
    fun withVerifiedSize(actualBytes: Long): SourceFacts {
        val normalized = actualBytes.takeIf { it > 0L } ?: return this
        val sizes = (reportedSizes + normalized).distinct().sorted()
        val hardSizes = (hardReportedSizes + normalized).distinct().sorted()
        return copy(
            sizeBytes = sizes.maxOrNull(),
            reportedSizes = sizes,
            hardReportedSizes = hardSizes,
            confidence = SourceConfidence.HIGH,
            provenance = provenance + SourceFactProvenance.HTTP_VERIFIED,
            hasConflictingHardMetadata =
                hasConflictingHardMetadata || sizesMateriallyConflict(hardSizes),
        )
    }
}

data class AioDetectionContext(
    val manifestId: String,
    val manifestName: String,
    val manifestUrl: String,
    val treatAsAioStreams: Boolean = false,
)

object AioStreamsSupport {
    /** Token understood by AIOStreams without tying downloads to a display template. */
    const val ENHANCED_METADATA_USER_AGENT = "Nuvio-Z AIOStreams/1"

    fun isAioStreams(context: AioDetectionContext): Boolean {
        if (context.treatAsAioStreams) return true
        val id = context.manifestId.lowercase()
        val name = context.manifestName.lowercase()
        return id.contains("aiostream") ||
            id.contains("viren070") && name.contains("aio") ||
            name == "aiostreams" ||
            name.startsWith("aiostreams ")
    }

    fun requestHeaders(context: AioDetectionContext): Map<String, String> =
        if (isAioStreams(context)) mapOf("User-Agent" to ENHANCED_METADATA_USER_AGENT) else emptyMap()
}

object SourceFactsExtractor {
    fun extract(
        stream: StreamItem,
        aioContext: AioDetectionContext? = null,
        verifiedSizeBytes: Long? = null,
    ): SourceFacts {
        val nuvio = stream.clientResolve?.stream?.raw
        val nuvioParsed = nuvio?.parsed
        val aio = stream.streamData
        val aioDetected = aioContext?.let(AioStreamsSupport::isAioStreams) == true || aio != null
        val filenames = listOfNotNull(
            nuvio?.filename,
            stream.clientResolve?.filename,
            aio?.filename,
            aio?.parsedFile?.title,
            stream.behaviorHints.filename,
        ).map(String::trim).filter(String::isNotEmpty)

        val filenameFacts = filenames.firstOrNull()?.let(::parseTextFacts)
        val fallbackFacts = parseTextFacts(
            listOfNotNull(stream.name, stream.description).joinToString(" "),
        ) ?: TextFacts()
        val structuredResolutions = listOfNotNull(
            parseResolution(nuvioParsed?.resolution),
            parseResolution(aio?.parsedFile?.resolution),
        )
        val resolution = structuredResolutions.firstOrNull()
            ?: filenameFacts?.resolution
            ?: fallbackFacts.resolution

        val hardReportedSizes = listOfNotNull(
            nuvio?.size?.positive(),
            aio?.size?.positive(),
            aio?.parsedFile?.size?.positive(),
            stream.behaviorHints.videoSize?.positive(),
            verifiedSizeBytes?.positive(),
        ).distinct().sorted()
        val reportedSizes = (
            hardReportedSizes +
                listOfNotNull(
                    filenameFacts?.sizeBytes,
                    fallbackFacts.sizeBytes,
                )
            ).distinct().sorted()

        val provenance = buildSet {
            if (nuvioParsed != null || nuvio?.size != null) add(SourceFactProvenance.NUVIO_STRUCTURED)
            if (aio?.parsedFile != null || aio?.size != null || aio?.addon != null) add(SourceFactProvenance.AIO_STRUCTURED)
            if (stream.behaviorHints.videoSize != null || stream.behaviorHints.filename != null) {
                add(SourceFactProvenance.STREMIO_BEHAVIOR_HINT)
            }
            if (filenameFacts != null) add(SourceFactProvenance.FILENAME)
            if (fallbackFacts.hasAnyFact) add(SourceFactProvenance.DISPLAY_FALLBACK)
            if (verifiedSizeBytes?.positive() != null) add(SourceFactProvenance.HTTP_VERIFIED)
        }

        val facts = SourceFacts(
            resolution = resolution,
            sizeBytes = reportedSizes.maxOrNull(),
            reportedSizes = reportedSizes,
            hardReportedSizes = hardReportedSizes,
            codec = normalizeCodec(nuvioParsed?.codec)
                ?: normalizeCodec(aio?.parsedFile?.codec)
                ?: filenameFacts?.codec
                ?: fallbackFacts.codec,
            dynamicRange = normalizeDynamicRange(nuvioParsed?.hdr.orEmpty())
                .ifEmpty { normalizeDynamicRange(aio?.parsedFile?.hdr.orEmpty()) }
                .ifEmpty { filenameFacts?.dynamicRange.orEmpty() }
                .ifEmpty { fallbackFacts.dynamicRange },
            languages = normalizeLanguages(nuvioParsed)
                .ifEmpty { normalizeLanguages(aio?.parsedFile) }
                .ifEmpty { filenameFacts?.languages.orEmpty() }
                .ifEmpty { fallbackFacts.languages },
            releaseQuality = nuvioParsed?.quality?.normalized()
                ?: aio?.parsedFile?.quality?.normalized()
                ?: filenameFacts?.releaseQuality
                ?: fallbackFacts.releaseQuality,
            providerId = aio?.addon?.id?.normalized(),
            providerName = aio?.addon?.name?.normalized(),
            filename = filenames.firstOrNull(),
            confidence = when {
                verifiedSizeBytes?.positive() != null || nuvioParsed != null || aio?.parsedFile != null ->
                    SourceConfidence.HIGH
                stream.behaviorHints.filename != null || stream.behaviorHints.videoSize != null ->
                    SourceConfidence.MEDIUM
                else -> SourceConfidence.LOW
            },
            provenance = provenance,
            hasConflictingHardMetadata =
                sizesMateriallyConflict(hardReportedSizes) ||
                    structuredResolutions.distinct().size > 1,
            isAioStreams = aioDetected,
            debridService = aio?.debridService ?: stream.clientResolve?.service,
            isDebridReady = aio?.debridCached ?: stream.clientResolve?.isCached,
        )
        return if (verifiedSizeBytes?.positive() != null) {
            facts.withVerifiedSize(verifiedSizeBytes)
        } else {
            facts
        }
    }

    private data class TextFacts(
        val resolution: VideoResolution? = null,
        val sizeBytes: Long? = null,
        val codec: String? = null,
        val dynamicRange: Set<String> = emptySet(),
        val languages: Set<String> = emptySet(),
        val releaseQuality: String? = null,
    ) {
        val hasAnyFact: Boolean
            get() = resolution != null || sizeBytes != null || codec != null ||
                dynamicRange.isNotEmpty() || languages.isNotEmpty() || releaseQuality != null
    }

    private fun parseTextFacts(value: String): TextFacts? {
        if (value.isBlank()) return null
        val lower = value.lowercase()
        val sizeMatch = Regex("""(\d+(?:\.\d+)?)\s*(tb|gb|gib|mb|mib)\b""", RegexOption.IGNORE_CASE)
            .find(value)
        val sizeBytes = sizeMatch?.let {
            val amount = it.groupValues[1].toDoubleOrNull() ?: return@let null
            val multiplier = when (it.groupValues[2].lowercase()) {
                "tb" -> 1_000_000_000_000.0
                "gb" -> 1_000_000_000.0
                "gib" -> 1_073_741_824.0
                "mb" -> 1_000_000.0
                else -> 1_048_576.0
            }
            (amount * multiplier).roundToLong().takeIf { bytes -> bytes > 0L }
        }
        val ranges = buildSet {
            if ("dolby vision" in lower || Regex("""\bdv\b""").containsMatchIn(lower)) add("DOLBY_VISION")
            if (Regex("""\bhdr10\+?\b""").containsMatchIn(lower)) add("HDR10")
            else if (Regex("""\bhdr\b""").containsMatchIn(lower)) add("HDR")
            if (Regex("""\bhlg\b""").containsMatchIn(lower)) add("HLG")
        }
        val languages = buildSet {
            LANGUAGE_TOKENS.forEach { (token, normalized) ->
                if (Regex("""(?:^|[ ._\-\[\]()])${Regex.escape(token)}(?:$|[ ._\-\[\]()])""")
                        .containsMatchIn(lower)
                ) add(normalized)
            }
        }
        return TextFacts(
            resolution = parseResolution(value),
            sizeBytes = sizeBytes,
            codec = normalizeCodec(value),
            dynamicRange = ranges,
            languages = languages,
            releaseQuality = QUALITY_TOKENS.firstOrNull { it in lower }?.uppercase(),
        ).takeIf(TextFacts::hasAnyFact)
    }

    private fun parseResolution(value: String?): VideoResolution? {
        val lower = value?.lowercase() ?: return null
        return when {
            Regex("""\b(8k|4320p?)\b""").containsMatchIn(lower) -> VideoResolution.UHD_4320
            Regex("""\b(4k|2160p?|uhd)\b""").containsMatchIn(lower) -> VideoResolution.UHD_2160
            Regex("""\b1440p?\b""").containsMatchIn(lower) -> VideoResolution.QHD_1440
            Regex("""\b(1080p?|fullhd|fhd)\b""").containsMatchIn(lower) -> VideoResolution.FULL_HD_1080
            Regex("""\b(720p?|hd)\b""").containsMatchIn(lower) -> VideoResolution.HD_720
            Regex("""\b(480p?|sd)\b""").containsMatchIn(lower) -> VideoResolution.SD
            else -> null
        }
    }

    private fun normalizeCodec(value: String?): String? {
        val lower = value?.lowercase() ?: return null
        return when {
            "hevc" in lower || "h265" in lower || "h.265" in lower || "x265" in lower -> "HEVC"
            "av1" in lower -> "AV1"
            "h264" in lower || "h.264" in lower || "x264" in lower || "avc" in lower -> "AVC"
            "vp9" in lower -> "VP9"
            else -> null
        }
    }

    private fun normalizeDynamicRange(values: List<String>): Set<String> =
        values.flatMap { value ->
            parseTextFacts(value)?.dynamicRange.orEmpty().ifEmpty {
                listOf(value.trim().uppercase()).filter(String::isNotEmpty)
            }
        }.toSet()

    private fun normalizeLanguages(parsed: StreamClientResolveParsed?): Set<String> =
        parsed?.let { normalizeLanguageValues(it.languages + it.audio) }.orEmpty()

    private fun normalizeLanguages(parsed: AioParsedFile?): Set<String> =
        parsed?.let { normalizeLanguageValues(it.languages + it.audio) }.orEmpty()

    private fun normalizeLanguageValues(values: List<String>): Set<String> =
        values.mapNotNull { raw ->
            val lower = raw.trim().lowercase()
            LANGUAGE_TOKENS[lower] ?: lower.takeIf(String::isNotEmpty)?.uppercase()
        }.toSet()

    private fun Long.positive(): Long? = takeIf { it > 0L }
    private fun String.normalized(): String? = trim().takeIf(String::isNotEmpty)

    private val QUALITY_TOKENS = listOf(
        "remux", "bluray", "blu-ray", "web-dl", "webrip", "hdtv", "dvdrip", "cam",
    )
    private val LANGUAGE_TOKENS = mapOf(
        "en" to "EN", "eng" to "EN", "english" to "EN",
        "ar" to "AR", "ara" to "AR", "arabic" to "AR",
        "es" to "ES", "spa" to "ES", "spanish" to "ES",
        "fr" to "FR", "fre" to "FR", "fra" to "FR", "french" to "FR",
        "de" to "DE", "ger" to "DE", "deu" to "DE", "german" to "DE",
        "ja" to "JA", "jpn" to "JA", "japanese" to "JA",
        "ko" to "KO", "kor" to "KO", "korean" to "KO",
    )
}

/**
 * Structured byte counts can differ slightly when one producer rounds or uses
 * container/file accounting. Only a material difference needs user approval.
 */
internal fun sizesMateriallyConflict(sizes: List<Long>): Boolean {
    if (sizes.size < 2) return false
    val smallest = sizes.minOrNull() ?: return false
    val largest = sizes.maxOrNull() ?: return false
    val tolerance = maxOf(1_048_576L, largest / 50L)
    return largest - smallest > tolerance
}
