package com.nuvio.app.features.downloads

import com.nuvio.app.core.language.normalizeLanguageCode
import com.nuvio.app.core.language.releaseLanguagesIn
import com.nuvio.app.core.media.ReleaseTags
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
    PLUGIN_STRUCTURED,
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
    /**
     * This particular file's runtime, when the addon reported one.
     *
     * A per-source runtime is what turns a size into a bitrate honestly. The title-level
     * runtime is often absent - Continue Watching carries none at all - and always describes
     * the *title*, not the release, so an extended cut divided by the theatrical runtime
     * reads as a higher bitrate than it is.
     */
    val durationSeconds: Long? = null,
    val reportedSizes: List<Long> = emptyList(),
    /** Exact byte reports from structured fields, Stremio hints, or HTTP verification. */
    val hardReportedSizes: List<Long> = emptyList(),
    val codec: String? = null,
    /**
     * [com.nuvio.app.core.media.ReleaseDynamicRange] names - `DOLBY_VISION`, `HDR10_PLUS`,
     * `HDR10`, `HDR`, `HLG`, `SDR` - or empty when the release claims none.
     *
     * `HDR10_PLUS` and `HDR10` are exclusive: a release tagged `hdr10+` carries the first only.
     * Reading `hdr10+` as plain `HDR10` is the defect this member set exists to end.
     */
    val dynamicRange: Set<String> = emptySet(),
    /**
     * [com.nuvio.app.core.media.ReleaseAudioCodec] names - `ATMOS`, `TRUEHD`, `DTS_HD_MA`,
     * `DTS_X`, `FLAC`, `DD_PLUS`, ... - or empty when the release names no audio format.
     *
     * **Empty means unstated, never lossy**, and the ranking scores it mid rather than at the
     * floor. Release names carry HDR reliably and audio format only sometimes, so treating
     * silence as "no lossless track" would demote most WEB-DLs for a user who asked for one -
     * a refusal wearing a preference's name.
     */
    val audioCodecs: Set<String> = emptySet(),
    /** Highest channel count claimed - 8 for 7.1, 6 for 5.1, 2 for 2.0 - or null if unstated. */
    val audioChannels: Int? = null,
    /**
     * Normalized audio language codes - `en`, `pt-BR`, `es-419` - or empty when the release
     * names none.
     *
     * **Empty is not "no English".** Most English releases say nothing about language at all,
     * which is why this can only ever be read as a positive claim: a source that names Hindi
     * and not English has told you something, and a source that names nothing has not.
     */
    val languages: Set<String> = emptySet(),
    /**
     * The release advertises several audio tracks without naming them - `MULTi`, `DUAL`.
     *
     * Separate from [languages] because it is not a language, and load-bearing for the same
     * reason: it is what lets a strict preference keep the releases most likely to satisfy it.
     */
    val isMultiLanguage: Boolean = false,
    /** Normalized subtitle language codes, from the stream's own subtitle list. */
    val subtitleLanguages: Set<String> = emptySet(),
    val releaseQuality: String? = null,
    val releaseGroup: String? = null,
    val seeders: Int? = null,
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
        val plugin = stream.pluginMeta
        val aioDetected = aioContext?.let(AioStreamsSupport::isAioStreams) == true || aio != null
        val filenames = listOfNotNull(
            nuvio?.filename,
            stream.clientResolve?.filename,
            aio?.filename,
            aio?.parsedFile?.title,
            stream.behaviorHints.filename,
        ).map(String::trim).filter(String::isNotEmpty)

        val filenameFacts = filenames.firstOrNull()?.let(::parseTextFacts)
        val pluginFacts = parseTextFacts(
            listOfNotNull(plugin?.quality, plugin?.language).joinToString(" "),
        ) ?: TextFacts()
        val fallbackFacts = parseTextFacts(
            listOfNotNull(stream.name, stream.description).joinToString(" "),
        ) ?: TextFacts()
        val structuredResolutions = listOfNotNull(
            parseResolution(nuvioParsed?.resolution),
            parseResolution(aio?.parsedFile?.resolution),
            pluginFacts.resolution,
        )
        val resolution = structuredResolutions.firstOrNull()
            ?: filenameFacts?.resolution
            ?: fallbackFacts.resolution

        val hardReportedSizes = listOfNotNull(
            nuvio?.size?.positive(),
            aio?.size?.positive(),
            aio?.parsedFile?.size?.positive(),
            plugin?.sizeBytes?.positive(),
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
            if (plugin != null) add(SourceFactProvenance.PLUGIN_STRUCTURED)
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
            durationSeconds = normalizeDurationSeconds(nuvioParsed?.duration),
            reportedSizes = reportedSizes,
            hardReportedSizes = hardReportedSizes,
            codec = normalizeCodec(nuvioParsed?.codec)
                ?: normalizeCodec(aio?.parsedFile?.codec)
                ?: pluginFacts.codec
                ?: filenameFacts?.codec
                ?: fallbackFacts.codec,
            dynamicRange = normalizeDynamicRange(nuvioParsed?.hdr.orEmpty())
                .ifEmpty { normalizeDynamicRange(aio?.parsedFile?.hdr.orEmpty()) }
                .ifEmpty { pluginFacts.dynamicRange }
                .ifEmpty { filenameFacts?.dynamicRange.orEmpty() }
                .ifEmpty { fallbackFacts.dynamicRange },
            // `nuvioParsed.channels` has been decoded off the wire since StreamParser was
            // written and read by nothing; the picker had no audio source of truth at all, which
            // is how a 95 GB HDR remux with a lossy track outranked a lossless one for a user
            // who had asked for lossless.
            audioCodecs = normalizeAudioCodecs(nuvioParsed?.audio.orEmpty())
                .ifEmpty { normalizeAudioCodecs(aio?.parsedFile?.audio.orEmpty()) }
                .ifEmpty { pluginFacts.audioCodecs }
                .ifEmpty { filenameFacts?.audioCodecs.orEmpty() }
                .ifEmpty { fallbackFacts.audioCodecs },
            // AIO carries no channel field, so the ladder is one rung shorter here than above.
            audioChannels = normalizeAudioChannels(nuvioParsed?.channels.orEmpty())
                ?: pluginFacts.audioChannels
                ?: filenameFacts?.audioChannels
                ?: fallbackFacts.audioChannels,
            languages = normalizeLanguages(nuvioParsed)
                .ifEmpty { normalizeLanguages(aio?.parsedFile) }
                .ifEmpty { normalizeLanguageValues(listOfNotNull(plugin?.language)) }
                .ifEmpty { filenameFacts?.languages.orEmpty() }
                .ifEmpty { fallbackFacts.languages },
            // ⚠ **Not part of the ladder above, and deliberately so.** A structured field can
            // name three languages while the release name is the only place `MULTi` appears,
            // and vice versa. Falling through on first hit would drop whichever came second,
            // and the marker is what makes a strict language preference survivable - it is the
            // difference between "this release is not for you" and "this release carries
            // several tracks and probably yours".
            isMultiLanguage = nuvioParsed?.audio.orEmpty().size > 1 ||
                aio?.parsedFile?.audio.orEmpty().size > 1 ||
                aio?.parsedFile?.languages.orEmpty().size > 1 ||
                nuvioParsed?.languages.orEmpty().size > 1 ||
                filenames.any { releaseLanguagesIn(it).isMulti } ||
                releaseLanguagesIn(
                    listOfNotNull(stream.name, stream.description, plugin?.language)
                        .joinToString(" "),
                ).isMulti,
            // Subtitles are the other half of "no English audio or subs". A release with the
            // wrong audio but the right subtitle track is not the same as one with neither,
            // and ranking them together threw away the watchable one.
            subtitleLanguages = stream.externalSubtitles
                .mapNotNull { normalizeLanguageCode(it.language) }
                .toSet()
                .ifEmpty { normalizeLanguageValues(nuvioParsed?.languages.orEmpty()) },
            releaseQuality = nuvioParsed?.quality?.normalized()
                ?: aio?.parsedFile?.quality?.normalized()
                ?: pluginFacts.releaseQuality
                ?: filenameFacts?.releaseQuality
                ?: fallbackFacts.releaseQuality,
            releaseGroup = nuvioParsed?.group?.normalized()
                ?: filenames.firstNotNullOfOrNull(::parseFilenameReleaseGroup),
            seeders = plugin?.seeders?.takeIf { it >= 0 },
            providerId = aio?.addon?.id?.normalized() ?: plugin?.provider?.normalized(),
            providerName = aio?.addon?.name?.normalized() ?: plugin?.provider?.normalized(),
            filename = filenames.firstOrNull(),
            confidence = when {
                verifiedSizeBytes?.positive() != null || nuvioParsed != null ||
                    aio?.parsedFile != null || plugin != null ->
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
            isDebridReady = aio?.debridCached
                ?: stream.clientResolve?.isCached
                // Last in the ladder: structured fields win, prose only fills the gap.
                ?: parseDebridCacheMarker(
                    listOfNotNull(stream.name, stream.description).joinToString(" "),
                ),
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
        val audioCodecs: Set<String> = emptySet(),
        val audioChannels: Int? = null,
        val languages: Set<String> = emptySet(),
        val releaseQuality: String? = null,
    ) {
        val hasAnyFact: Boolean
            get() = resolution != null || sizeBytes != null || codec != null ||
                dynamicRange.isNotEmpty() || audioCodecs.isNotEmpty() || audioChannels != null ||
                languages.isNotEmpty() || releaseQuality != null
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
        // Three parse bugs died with the table this replaced, all of them silent: `\bhdr10\+?\b`
        // backtracked and labelled `hdr10+` as plain `HDR10`; `hdr10plus` matched nothing at all,
        // so an HDR10+ release read as SDR and ranked *below* a plain HDR one; and `dovi` was not
        // recognised. The badges the user could see had all three right the whole time.
        val ranges = ReleaseTags.dynamicRanges(text = value).mapTo(mutableSetOf()) { it.name }
        val audioCodecs = ReleaseTags.audioCodecs(text = value).mapTo(mutableSetOf()) { it.name }
        val audioChannels = ReleaseTags.channelCount(ReleaseTags.audioChannels(text = value))
        // The seven-language table this replaced knew en/ar/es/fr/de/ja/ko and nothing else, so
        // a Hindi, Italian or Russian release declared no language at all - and a preference
        // cannot reject what it cannot see. It also had no `MULTi` and no flag emoji, which
        // between them label most of what the big addons return.
        val languages = releaseLanguagesIn(value).codes
        return TextFacts(
            resolution = parseResolution(value),
            sizeBytes = sizeBytes,
            codec = normalizeCodec(value),
            dynamicRange = ranges,
            audioCodecs = audioCodecs,
            audioChannels = audioChannels,
            languages = languages,
            // Token-bounded, because a substring scan called every WEB-DL of *Camelot* a cam rip.
            releaseQuality = ReleaseTags.releaseQuality(lower),
        ).takeIf(TextFacts::hasAnyFact)
    }

    /**
     * The reported duration in seconds, or null when it is absent or not credible.
     *
     * The unit is not documented by the addons that send it, so it is inferred rather than
     * assumed: anything above [MAX_CREDIBLE_DURATION_SECONDS] can only be milliseconds, and
     * anything still out of range after that conversion is discarded. A wrong unit here
     * would silently divide or multiply every derived bitrate by a thousand, so refusing to
     * guess is worth more than the occasional lost sample.
     */
    internal fun normalizeDurationSeconds(value: Long?): Long? {
        val raw = value?.takeIf { it > 0L } ?: return null
        val seconds = if (raw > MAX_CREDIBLE_DURATION_SECONDS) raw / 1_000L else raw
        return seconds.takeIf { it in MIN_CREDIBLE_DURATION_SECONDS..MAX_CREDIBLE_DURATION_SECONDS }
    }

    /** Two minutes. Below this it is a trailer or a placeholder, not the feature. */
    private const val MIN_CREDIBLE_DURATION_SECONDS = 120L

    /** Sixteen hours. Above this the value cannot be seconds. */
    private const val MAX_CREDIBLE_DURATION_SECONDS = 57_600L

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

    /** Release groups use a hyphen-delimited filename suffix; plain all-caps title words do not. */
    /**
     * Debrid cache state advertised in an addon's display text, when no structured field says.
     *
     * Many debrid addons only signal this in the stream name - AIOStreams/ElfHosted use
     * ⏳ for "being prepared" and ⚡ for "instantly available", and `debridCached` is simply
     * absent. Without this, an uncached source reads as *unknown* rather than *not cached*
     * and auto-play happily starts the provider's two-minute placeholder video.
     *
     * Deliberately conservative in both directions. Negative markers are checked first so
     * "not cached" cannot be read as "cached", and the positive set is restricted to markers
     * that carry no other meaning in a release name - `instant` is excluded precisely
     * because *Instant Family* exists. Returns null when nothing is claimed, which leaves
     * the caller's own fail-safe in charge.
     */
    internal fun parseDebridCacheMarker(text: String): Boolean? {
        if (text.isBlank()) return null
        val normalized = text.lowercase()
        val notCached = "⏳" in text ||
            "not cached" in normalized ||
            "uncached" in normalized ||
            "not-cached" in normalized
        if (notCached) return false
        val cached = "⚡" in text || "cached" in normalized
        return if (cached) true else null
    }

    private fun parseFilenameReleaseGroup(filename: String): String? {
        val stem = filename.substringBeforeLast('.', filename).trim()
        val candidate = Regex("""-([A-Za-z0-9][A-Za-z0-9._]{1,31})$""")
            .find(stem)
            ?.groupValues
            ?.get(1)
            ?.trim('.', '_')
            ?.takeIf(String::isNotBlank)
            ?: return null
        val normalized = candidate.uppercase()
        return candidate.takeUnless {
            normalized in RELEASE_GROUP_FALSE_POSITIVES || parseResolution(candidate) != null ||
                normalizeCodec(candidate) != null
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

    /**
     * Structured `hdr` fields, which are tagged values rather than prose.
     *
     * Anything the shared table does not recognise is kept uppercased rather than dropped: an
     * addon that invents a name has still told the user something, and a value nothing scores
     * is harmless where a lost one is not.
     */
    private fun normalizeDynamicRange(values: List<String>): Set<String> {
        if (values.isEmpty()) return emptySet()
        val recognized = ReleaseTags.dynamicRanges(structuredValues = values).map { it.name }.toSet()
        return recognized.ifEmpty {
            values.map { it.trim().uppercase() }.filter(String::isNotEmpty).toSet()
        }
    }

    private fun normalizeAudioCodecs(values: List<String>): Set<String> =
        ReleaseTags.audioCodecs(structuredValues = values).mapTo(mutableSetOf()) { it.name }

    private fun normalizeAudioChannels(values: List<String>): Int? =
        ReleaseTags.channelCount(ReleaseTags.audioChannels(structuredValues = values))

    private fun normalizeLanguages(parsed: StreamClientResolveParsed?): Set<String> =
        parsed?.let { normalizeLanguageValues(it.languages + it.audio) }.orEmpty()

    private fun normalizeLanguages(parsed: AioParsedFile?): Set<String> =
        parsed?.let { normalizeLanguageValues(it.languages + it.audio) }.orEmpty()

    /**
     * Structured values, which are tagged fields rather than prose.
     *
     * Short codes are accepted here and refused by `releaseLanguagesIn` for the same reason:
     * `"it"` in a `languages` array means Italian, and `IT` in a filename means the Stephen
     * King film. This used to `uppercase()` anything it did not recognise, so an addon sending
     * `["Latino"]` produced `"LATINO"` - a value no preference could ever equal, on a source
     * that had told the app exactly what it was.
     */
    private fun normalizeLanguageValues(values: List<String>): Set<String> =
        values.mapNotNull { raw -> normalizeLanguageCode(raw) }.toSet()

    private fun Long.positive(): Long? = takeIf { it > 0L }
    private fun String.normalized(): String? = trim().takeIf(String::isNotEmpty)

    private val RELEASE_GROUP_FALSE_POSITIVES = setOf(
        "WEB", "WEB-DL", "WEBRIP", "BLURAY", "HDTV", "REMUX", "PROPER", "REPACK",
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
