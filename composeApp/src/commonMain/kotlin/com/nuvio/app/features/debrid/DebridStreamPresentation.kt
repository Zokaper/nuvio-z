package com.nuvio.app.features.debrid

import com.nuvio.app.core.media.ReleaseAudioChannel
import com.nuvio.app.core.media.ReleaseAudioCodec
import com.nuvio.app.core.media.ReleaseDynamicRange
import com.nuvio.app.core.media.ReleaseTags
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamItem

object DebridStreamPresentation {
    private val formatter = DebridStreamFormatter()

    fun apply(groups: List<AddonStreamGroup>, settings: DebridSettings): List<AddonStreamGroup> {
        if (!settings.appliesStreamPresentation) return groups
        return groups.map { group ->
            val visibleStreams = group.streams
                .filterNot { stream -> stream.isInactiveResolverStream(settings) }
                .filterNot { stream -> stream.isUncachedDebridStream }
            // isPresentableStream reads settings and walks streamData, so partition rather than
            // filter twice.
            val (presentableStreams, passthroughStreams) = visibleStreams
                .partition { stream -> stream.isPresentableStream(settings) }
            if (presentableStreams.isEmpty()) return@map group.copy(streams = visibleStreams)

            val presentedStreams = applyPreferences(presentableStreams, settings)
                .map { stream ->
                    if (stream.shouldFormat(settings)) {
                        formatter.format(stream, settings)
                    } else {
                        stream
                    }
                }

            group.copy(streams = presentedStreams + passthroughStreams)
        }
    }

    internal fun applyPreferences(streams: List<StreamItem>, settings: DebridSettings): List<StreamItem> {
        val preferences = DebridStreamMetadata.effectivePreferences(settings)
        val matchedStreams = streams.map { it to DebridStreamMetadata.facts(it, preferences) }
            .filter { (_, facts) -> facts.matchesFilters(preferences) }

        val orderedStreams = if (preferences.sortCriteria.isEmpty()) {
            matchedStreams
        } else {
            matchedStreams.sortedWith { left, right ->
                compareFacts(left.second, right.second, preferences.sortCriteria)
            }
        }

        return applyLimits(orderedStreams, preferences)
            .map { it.first }
    }

    /** Which streams the filter/sort/format pipeline is allowed to touch, per the chosen scope. */
    internal fun StreamItem.isPresentableStream(settings: DebridSettings): Boolean =
        when (settings.streamPreferenceScope) {
            DebridStreamPreferenceScope.RESOLVER_ONLY -> isManagedDebridStream
            DebridStreamPreferenceScope.DEBRID -> isManagedDebridStream || hasExternalDebridEvidence
            DebridStreamPreferenceScope.ALL_ADDON_STREAMS ->
                isManagedDebridStream || (isInstalledAddonStream && playableDirectUrl != null)
        }

    /** An addon that ran debrid itself and said so - AIOStreams' `streamData` is the case. */
    internal val StreamItem.hasExternalDebridEvidence: Boolean
        get() = isInstalledAddonStream &&
            playableDirectUrl != null &&
            streamData?.let { it.debridService != null || it.debridCached != null } == true

    /**
     * Renaming is opt-in per stream, not per group. The default name template renders
     * "Cloud Instant" for anything without a service id, so a plain addon result keeps its own
     * name until either the template is customised or the service is known.
     */
    private fun StreamItem.shouldFormat(settings: DebridSettings): Boolean =
        settings.hasCustomStreamFormatting ||
            DebridStreamFormatter.serviceId(this) != null ||
            badges.isNotEmpty()

    internal val StreamItem.isManagedDebridStream: Boolean
        get() {
            val status = debridCacheStatus
            return isAddonDebridCandidate && (isDirectDebridStream || (
                isTorrentStream &&
                    status != null &&
                    DebridProviders.byId(status.providerId)?.supports(DebridProviderCapability.LocalTorrentCacheCheck) == true &&
                    status.state != StreamDebridCacheState.CHECKING
            ))
        }

    private val StreamItem.isUncachedDebridStream: Boolean
        get() = isInstalledAddonStream &&
            DebridProviders.byId(debridCacheStatus?.providerId)?.supports(DebridProviderCapability.LocalTorrentCacheCheck) == true &&
            debridCacheStatus?.state == StreamDebridCacheState.NOT_CACHED

    private fun StreamItem.isInactiveResolverStream(settings: DebridSettings): Boolean {
        val streamProviderId = DebridProviders.byId(clientResolve?.service)?.id ?: return false
        val activeProviderId = settings.activeResolverProviderId ?: return false
        return isDirectDebridStream && streamProviderId != activeProviderId
    }

    private fun applyLimits(
        streams: List<Pair<StreamItem, DebridStreamFacts>>,
        preferences: DebridStreamPreferences,
    ): List<Pair<StreamItem, DebridStreamFacts>> {
        val resolutionCounts = mutableMapOf<DebridStreamResolution, Int>()
        val qualityCounts = mutableMapOf<DebridStreamQuality, Int>()
        val result = mutableListOf<Pair<StreamItem, DebridStreamFacts>>()
        for (stream in streams) {
            if (preferences.maxResults > 0 && result.size >= preferences.maxResults) break
            if (preferences.maxPerResolution > 0) {
                val count = resolutionCounts[stream.second.resolution] ?: 0
                if (count >= preferences.maxPerResolution) continue
            }
            if (preferences.maxPerQuality > 0) {
                val count = qualityCounts[stream.second.quality] ?: 0
                if (count >= preferences.maxPerQuality) continue
            }
            resolutionCounts[stream.second.resolution] = (resolutionCounts[stream.second.resolution] ?: 0) + 1
            qualityCounts[stream.second.quality] = (qualityCounts[stream.second.quality] ?: 0) + 1
            result += stream
        }
        return result
    }

    private fun DebridStreamFacts.matchesFilters(preferences: DebridStreamPreferences): Boolean {
        if (preferences.requiredResolutions.isNotEmpty() && resolution !in preferences.requiredResolutions) return false
        if (resolution in preferences.excludedResolutions) return false
        if (preferences.requiredQualities.isNotEmpty() && quality !in preferences.requiredQualities) return false
        if (quality in preferences.excludedQualities) return false
        if (preferences.requiredVisualTags.isNotEmpty() && visualTags.none { it in preferences.requiredVisualTags }) return false
        if (visualTags.any { it in preferences.excludedVisualTags }) return false
        if (preferences.requiredAudioTags.isNotEmpty() && audioTags.none { it in preferences.requiredAudioTags }) return false
        if (audioTags.any { it in preferences.excludedAudioTags }) return false
        if (preferences.requiredAudioChannels.isNotEmpty() && audioChannels.none { it in preferences.requiredAudioChannels }) return false
        if (audioChannels.any { it in preferences.excludedAudioChannels }) return false
        if (preferences.requiredEncodes.isNotEmpty() && encode !in preferences.requiredEncodes) return false
        if (encode in preferences.excludedEncodes) return false
        if (preferences.requiredLanguages.isNotEmpty() && languages.none { it in preferences.requiredLanguages }) return false
        if (languages.isNotEmpty() && languages.all { it in preferences.excludedLanguages }) return false
        if (preferences.requiredReleaseGroups.isNotEmpty() && preferences.requiredReleaseGroups.none { releaseGroup.equals(it, ignoreCase = true) }) return false
        if (preferences.excludedReleaseGroups.any { releaseGroup.equals(it, ignoreCase = true) }) return false
        if (preferences.sizeMinGb > 0 && size != null && size < preferences.sizeMinGb.gigabytes()) return false
        if (preferences.sizeMaxGb > 0 && size != null && size > preferences.sizeMaxGb.gigabytes()) return false
        return true
    }

    private fun compareFacts(
        left: DebridStreamFacts,
        right: DebridStreamFacts,
        criteria: List<DebridStreamSortCriterion>,
    ): Int {
        for (criterion in criteria) {
            val comparison = compareKey(left, right, criterion)
            if (comparison != 0) return comparison
        }
        return 0
    }

    private fun compareKey(
        left: DebridStreamFacts,
        right: DebridStreamFacts,
        criterion: DebridStreamSortCriterion,
    ): Int {
        val direction = if (criterion.direction == DebridStreamSortDirection.ASC) 1 else -1
        return when (criterion.key) {
            DebridStreamSortKey.RESOLUTION -> left.resolutionRank.compareTo(right.resolutionRank) * -direction
            DebridStreamSortKey.QUALITY -> left.qualityRank.compareTo(right.qualityRank) * -direction
            DebridStreamSortKey.VISUAL_TAG -> left.visualRank.compareTo(right.visualRank) * -direction
            DebridStreamSortKey.AUDIO_TAG -> left.audioRank.compareTo(right.audioRank) * -direction
            DebridStreamSortKey.AUDIO_CHANNEL -> left.channelRank.compareTo(right.channelRank) * -direction
            DebridStreamSortKey.ENCODE -> left.encodeRank.compareTo(right.encodeRank) * -direction
            DebridStreamSortKey.SIZE -> (left.size ?: 0L).compareTo(right.size ?: 0L) * direction
            DebridStreamSortKey.LANGUAGE -> left.languageRank.compareTo(right.languageRank) * -direction
            DebridStreamSortKey.RELEASE_GROUP -> left.releaseGroup.compareTo(right.releaseGroup, ignoreCase = true)
        }
    }
}

internal object DebridStreamMetadata {
    fun effectivePreferences(settings: DebridSettings): DebridStreamPreferences {
        val default = DebridStreamPreferences()
        if (settings.streamPreferences != default) return settings.streamPreferences.normalized()
        if (
            settings.streamMaxResults == 0 &&
            settings.streamSortMode == DebridStreamSortMode.DEFAULT &&
            settings.streamMinimumQuality == DebridStreamMinimumQuality.ANY &&
            settings.streamDolbyVisionFilter == DebridStreamFeatureFilter.ANY &&
            settings.streamHdrFilter == DebridStreamFeatureFilter.ANY &&
            settings.streamCodecFilter == DebridStreamCodecFilter.ANY
        ) {
            return default
        }
        var preferences = default.copy(
            maxResults = settings.streamMaxResults,
            sortCriteria = when (settings.streamSortMode) {
                DebridStreamSortMode.DEFAULT -> default.sortCriteria
                DebridStreamSortMode.QUALITY_DESC -> listOf(
                    DebridStreamSortCriterion(DebridStreamSortKey.RESOLUTION, DebridStreamSortDirection.DESC),
                    DebridStreamSortCriterion(DebridStreamSortKey.QUALITY, DebridStreamSortDirection.DESC),
                    DebridStreamSortCriterion(DebridStreamSortKey.SIZE, DebridStreamSortDirection.DESC),
                )
                DebridStreamSortMode.SIZE_DESC -> listOf(
                    DebridStreamSortCriterion(DebridStreamSortKey.SIZE, DebridStreamSortDirection.DESC),
                )
                DebridStreamSortMode.SIZE_ASC -> listOf(
                    DebridStreamSortCriterion(DebridStreamSortKey.SIZE, DebridStreamSortDirection.ASC),
                )
            },
            requiredResolutions = DebridStreamResolution.defaultOrder.filter {
                it.value >= settings.streamMinimumQuality.minResolution && it != DebridStreamResolution.UNKNOWN
            },
        )
        preferences = when (settings.streamDolbyVisionFilter) {
            DebridStreamFeatureFilter.ANY -> preferences
            DebridStreamFeatureFilter.EXCLUDE -> preferences.copy(
                excludedVisualTags = preferences.excludedVisualTags + listOf(
                    DebridStreamVisualTag.DV,
                    DebridStreamVisualTag.DV_ONLY,
                    DebridStreamVisualTag.HDR_DV,
                ),
            )
            DebridStreamFeatureFilter.ONLY -> preferences.copy(
                requiredVisualTags = preferences.requiredVisualTags + listOf(
                    DebridStreamVisualTag.DV,
                    DebridStreamVisualTag.DV_ONLY,
                    DebridStreamVisualTag.HDR_DV,
                ),
            )
        }
        preferences = when (settings.streamHdrFilter) {
            DebridStreamFeatureFilter.ANY -> preferences
            DebridStreamFeatureFilter.EXCLUDE -> preferences.copy(
                excludedVisualTags = preferences.excludedVisualTags + listOf(
                    DebridStreamVisualTag.HDR,
                    DebridStreamVisualTag.HDR10,
                    DebridStreamVisualTag.HDR10_PLUS,
                    DebridStreamVisualTag.HLG,
                    DebridStreamVisualTag.HDR_ONLY,
                    DebridStreamVisualTag.HDR_DV,
                ),
            )
            DebridStreamFeatureFilter.ONLY -> preferences.copy(
                requiredVisualTags = preferences.requiredVisualTags + listOf(
                    DebridStreamVisualTag.HDR,
                    DebridStreamVisualTag.HDR10,
                    DebridStreamVisualTag.HDR10_PLUS,
                    DebridStreamVisualTag.HLG,
                    DebridStreamVisualTag.HDR_ONLY,
                    DebridStreamVisualTag.HDR_DV,
                ),
            )
        }
        return when (settings.streamCodecFilter) {
            DebridStreamCodecFilter.ANY -> preferences
            DebridStreamCodecFilter.H264 -> preferences.copy(requiredEncodes = listOf(DebridStreamEncode.AVC))
            DebridStreamCodecFilter.HEVC -> preferences.copy(requiredEncodes = listOf(DebridStreamEncode.HEVC))
            DebridStreamCodecFilter.AV1 -> preferences.copy(requiredEncodes = listOf(DebridStreamEncode.AV1))
        }.normalized()
    }

    fun facts(stream: StreamItem, preferences: DebridStreamPreferences): DebridStreamFacts {
        val parsed = stream.clientResolve?.stream?.raw?.parsed
        // Addon-side debrid supplies the same facts under a different shape. Every fallback sits
        // *after* the resolver's own value, so nothing changes for a resolver-resolved stream.
        val aio = stream.streamData?.parsedFile
        val searchText = streamSearchText(stream)
        val resolution = streamResolution(parsed?.resolution, aio?.resolution, parsed?.quality, aio?.quality, searchText)
        val quality = streamQuality(parsed?.quality ?: aio?.quality, searchText)
        val visualTags = streamVisualTags(parsed?.hdr.orEmpty().ifEmpty { aio?.hdr.orEmpty() }, searchText)
        val audioTags = streamAudioTags(parsed?.audio.orEmpty().ifEmpty { aio?.audio.orEmpty() }, searchText)
        // No AIO source for channels or release group - both are recovered from searchText, which
        // the AIO filename and title widen below.
        val audioChannels = streamAudioChannels(parsed?.channels.orEmpty(), searchText)
        val encode = streamEncode(parsed?.codec ?: aio?.codec, searchText)
        val languages = parsed?.languages.orEmpty()
            .ifEmpty { aio?.languages.orEmpty() }
            .mapNotNull { languageFor(it) }
            .ifEmpty { DebridStreamLanguage.entries.filter { searchText.hasToken(it.code) } }
        val releaseGroup = parsed?.group?.takeIf { it.isNotBlank() } ?: releaseGroupFromText(searchText)
        return DebridStreamFacts(
            resolution = resolution,
            quality = quality,
            visualTags = visualTags,
            audioTags = audioTags,
            audioChannels = audioChannels,
            encode = encode,
            languages = languages,
            releaseGroup = releaseGroup,
            size = streamSize(stream),
            resolutionRank = rank(resolution, preferences.preferredResolutions),
            qualityRank = rank(quality, preferences.preferredQualities),
            visualRank = rankAny(visualTags, preferences.preferredVisualTags),
            audioRank = rankAny(audioTags, preferences.preferredAudioTags),
            channelRank = rankAny(audioChannels, preferences.preferredAudioChannels),
            encodeRank = rank(encode, preferences.preferredEncodes),
            languageRank = if (languages.isEmpty()) Int.MAX_VALUE else languages.minOf { rank(it, preferences.preferredLanguages) },
        )
    }

    private fun streamResolution(vararg values: String?): DebridStreamResolution =
        values.firstNotNullOfOrNull { resolutionValue(it) } ?: DebridStreamResolution.UNKNOWN

    private fun resolutionValue(value: String?): DebridStreamResolution? {
        val normalized = value?.lowercase().orEmpty()
        return when {
            normalized.hasResolutionToken("2160p?", "4k", "uhd") -> DebridStreamResolution.P2160
            normalized.hasResolutionToken("1440p?", "2k") -> DebridStreamResolution.P1440
            normalized.hasResolutionToken("1080p?", "fhd") -> DebridStreamResolution.P1080
            normalized.hasResolutionToken("720p?", "hd") -> DebridStreamResolution.P720
            normalized.hasResolutionToken("576p?") -> DebridStreamResolution.P576
            normalized.hasResolutionToken("480p?", "sd") -> DebridStreamResolution.P480
            normalized.hasResolutionToken("360p?") -> DebridStreamResolution.P360
            else -> null
        }
    }

    private fun streamQuality(parsedQuality: String?, searchText: String): DebridStreamQuality {
        val text = listOfNotNull(parsedQuality, searchText).joinToString(" ").lowercase()
        return when {
            text.contains("remux") -> DebridStreamQuality.BLURAY_REMUX
            text.contains("blu-ray") || text.contains("bluray") || text.contains("bdrip") || text.contains("brrip") -> DebridStreamQuality.BLURAY
            text.contains("web-dl") || text.contains("webdl") -> DebridStreamQuality.WEB_DL
            text.contains("webrip") || text.contains("web-rip") -> DebridStreamQuality.WEBRIP
            text.contains("hdrip") -> DebridStreamQuality.HDRIP
            text.contains("hd-rip") || text.contains("hcrip") -> DebridStreamQuality.HD_RIP
            text.contains("dvdrip") -> DebridStreamQuality.DVDRIP
            text.contains("hdtv") -> DebridStreamQuality.HDTV
            text.hasToken("cam") -> DebridStreamQuality.CAM
            text.hasToken("ts") -> DebridStreamQuality.TS
            text.hasToken("tc") -> DebridStreamQuality.TC
            text.hasToken("scr") -> DebridStreamQuality.SCR
            else -> DebridStreamQuality.UNKNOWN
        }
    }

    // The dynamic-range, audio-codec and channel tables moved to the import-free
    // `core/media/ReleaseTags.kt` so that `SourceFacts` - and through it the auto-picker - reads
    // the same release name the same way this does. These three functions map the shared result
    // onto the display enums; the labels and their order are unchanged, which is what
    // `DebridStreamPresentationTest` passing unmodified proves.
    private fun streamVisualTags(parsedHdr: List<String>, searchText: String): List<DebridStreamVisualTag> {
        val text = (parsedHdr + searchText).joinToString(" ").lowercase()
        val tags = mutableListOf<DebridStreamVisualTag>()
        val ranges = ReleaseTags.dynamicRanges(parsedHdr, searchText)
        val hasDv = ReleaseDynamicRange.DOLBY_VISION in ranges
        val hasHdr = ReleaseTags.claimsHdrFamily(ranges)
        val hasHdr10Plus = ReleaseDynamicRange.HDR10_PLUS in ranges
        if (hasDv && hasHdr) tags += DebridStreamVisualTag.HDR_DV
        if (hasDv && !hasHdr) tags += DebridStreamVisualTag.DV_ONLY
        if (hasHdr && !hasDv) tags += DebridStreamVisualTag.HDR_ONLY
        if (hasHdr10Plus) tags += DebridStreamVisualTag.HDR10_PLUS
        // HDR10+ is still an HDR10 signal for the badge row, even though the shared vocabulary
        // keeps the two members exclusive so the picker cannot read one as the other.
        if (hasHdr10Plus || ReleaseDynamicRange.HDR10 in ranges) tags += DebridStreamVisualTag.HDR10
        if (hasDv) tags += DebridStreamVisualTag.DV
        if (hasHdr) tags += DebridStreamVisualTag.HDR
        if (ReleaseDynamicRange.HLG in ranges) tags += DebridStreamVisualTag.HLG
        if (text.contains("10bit") || text.contains("10 bit")) tags += DebridStreamVisualTag.TEN_BIT
        if (text.hasToken("3d")) tags += DebridStreamVisualTag.THREE_D
        if (text.hasToken("imax")) tags += DebridStreamVisualTag.IMAX
        if (text.hasToken("ai")) tags += DebridStreamVisualTag.AI
        if (text.hasToken("sdr")) tags += DebridStreamVisualTag.SDR
        if (text.contains("h-ou")) tags += DebridStreamVisualTag.H_OU
        if (text.contains("h-sbs")) tags += DebridStreamVisualTag.H_SBS
        return tags.distinct().ifEmpty { listOf(DebridStreamVisualTag.UNKNOWN) }
    }

    private fun streamAudioTags(parsedAudio: List<String>, searchText: String): List<DebridStreamAudioTag> {
        val codecs = ReleaseTags.audioCodecs(parsedAudio, searchText)
        // Kept as an explicit ordered walk rather than a map over the shared enum: this order is
        // the badge order the user sees, and it is not the shared enum's ranking order.
        val tags = listOfNotNull(
            DebridStreamAudioTag.ATMOS.takeIf { ReleaseAudioCodec.ATMOS in codecs },
            DebridStreamAudioTag.DD_PLUS.takeIf { ReleaseAudioCodec.DD_PLUS in codecs },
            DebridStreamAudioTag.DD.takeIf { ReleaseAudioCodec.DD in codecs },
            DebridStreamAudioTag.DTS_X.takeIf { ReleaseAudioCodec.DTS_X in codecs },
            DebridStreamAudioTag.DTS_HD_MA.takeIf { ReleaseAudioCodec.DTS_HD_MA in codecs },
            DebridStreamAudioTag.DTS_HD.takeIf { ReleaseAudioCodec.DTS_HD in codecs },
            DebridStreamAudioTag.DTS_ES.takeIf { ReleaseAudioCodec.DTS_ES in codecs },
            DebridStreamAudioTag.DTS.takeIf { ReleaseAudioCodec.DTS in codecs },
            DebridStreamAudioTag.TRUEHD.takeIf { ReleaseAudioCodec.TRUEHD in codecs },
            DebridStreamAudioTag.OPUS.takeIf { ReleaseAudioCodec.OPUS in codecs },
            DebridStreamAudioTag.FLAC.takeIf { ReleaseAudioCodec.FLAC in codecs },
            DebridStreamAudioTag.AAC.takeIf { ReleaseAudioCodec.AAC in codecs },
        )
        return tags.distinct().ifEmpty { listOf(DebridStreamAudioTag.UNKNOWN) }
    }

    private fun streamAudioChannels(parsedChannels: List<String>, searchText: String): List<DebridStreamAudioChannel> {
        val layouts = ReleaseTags.audioChannels(parsedChannels, searchText)
        val channels = listOfNotNull(
            DebridStreamAudioChannel.CH_7_1.takeIf { ReleaseAudioChannel.CH_7_1 in layouts },
            DebridStreamAudioChannel.CH_6_1.takeIf { ReleaseAudioChannel.CH_6_1 in layouts },
            DebridStreamAudioChannel.CH_5_1.takeIf { ReleaseAudioChannel.CH_5_1 in layouts },
            DebridStreamAudioChannel.CH_2_0.takeIf { ReleaseAudioChannel.CH_2_0 in layouts },
        )
        return channels.distinct().ifEmpty { listOf(DebridStreamAudioChannel.UNKNOWN) }
    }

    private fun streamEncode(parsedCodec: String?, searchText: String): DebridStreamEncode {
        val text = listOfNotNull(parsedCodec, searchText).joinToString(" ").lowercase()
        return when {
            text.hasToken("av1") -> DebridStreamEncode.AV1
            text.hasToken("hevc") || text.hasToken("h265") || text.hasToken("x265") -> DebridStreamEncode.HEVC
            text.hasToken("avc") || text.hasToken("h264") || text.hasToken("x264") -> DebridStreamEncode.AVC
            text.hasToken("xvid") -> DebridStreamEncode.XVID
            text.hasToken("divx") -> DebridStreamEncode.DIVX
            else -> DebridStreamEncode.UNKNOWN
        }
    }

    private fun languageFor(value: String): DebridStreamLanguage? {
        val normalized = value.lowercase()
        return DebridStreamLanguage.entries.firstOrNull {
            normalized == it.code || normalized == it.label.lowercase()
        }
    }

    private fun releaseGroupFromText(text: String): String =
        Regex("-([a-z0-9][a-z0-9._]{1,24})($|\\.)", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()

    private fun <T> rank(value: T, preferred: List<T>): Int {
        val index = preferred.indexOf(value)
        return if (index >= 0) index else Int.MAX_VALUE
    }

    private fun <T> rankAny(values: List<T>, preferred: List<T>): Int =
        values.minOfOrNull { rank(it, preferred) } ?: Int.MAX_VALUE

    private fun String.hasResolutionToken(vararg tokens: String): Boolean =
        Regex("(^|[^a-z0-9])(${tokens.joinToString("|")})([^a-z0-9]|\$)").containsMatchIn(this)

    private fun String.hasToken(token: String): Boolean =
        Regex("(^|[^a-z0-9])${Regex.escape(token.lowercase())}([^a-z0-9]|\$)").containsMatchIn(lowercase())

    private fun streamSize(stream: StreamItem): Long? =
        stream.clientResolve?.stream?.raw?.size
            ?: stream.behaviorHints.videoSize
            ?: stream.streamData?.size
            ?: stream.streamData?.parsedFile?.size
            ?: stream.debridCacheStatus?.cachedSize

    private fun streamSearchText(stream: StreamItem): String {
        val resolve = stream.clientResolve
        val raw = resolve?.stream?.raw
        val parsed = raw?.parsed
        val aio = stream.streamData?.parsedFile
        // Strict superset of the resolver-only text: adding the AIO filename and parsed fields is
        // what lets audio-channel and release-group detection work with no structured source.
        return listOfNotNull(
            stream.name,
            stream.title,
            stream.description,
            stream.behaviorHints.filename,
            stream.debridCacheStatus?.cachedName,
            stream.streamData?.filename,
            resolve?.torrentName,
            resolve?.filename,
            raw?.torrentName,
            raw?.filename,
            parsed?.resolution,
            parsed?.quality,
            parsed?.codec,
            parsed?.hdr?.joinToString(" "),
            parsed?.audio?.joinToString(" "),
            aio?.title,
            aio?.resolution,
            aio?.quality,
            aio?.codec,
            aio?.hdr?.joinToString(" "),
            aio?.audio?.joinToString(" "),
        ).joinToString(" ").lowercase()
    }
}

internal data class DebridStreamFacts(
    val resolution: DebridStreamResolution,
    val quality: DebridStreamQuality,
    val visualTags: List<DebridStreamVisualTag>,
    val audioTags: List<DebridStreamAudioTag>,
    val audioChannels: List<DebridStreamAudioChannel>,
    val encode: DebridStreamEncode,
    val languages: List<DebridStreamLanguage>,
    val releaseGroup: String,
    val size: Long?,
    val resolutionRank: Int,
    val qualityRank: Int,
    val visualRank: Int,
    val audioRank: Int,
    val channelRank: Int,
    val encodeRank: Int,
    val languageRank: Int,
)

private fun Int.gigabytes(): Long = this * 1_000_000_000L
