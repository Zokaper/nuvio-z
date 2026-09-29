package com.nuvio.app.features.watchparty

import com.nuvio.app.features.downloads.SourceFacts
import com.nuvio.app.features.downloads.SourceFactsExtractor
import com.nuvio.app.features.streams.StreamItem

/** Builds the wire-safe descriptor before debrid resolution can replace the source object. */
fun StreamItem.toPartySourceDescriptor(
    facts: SourceFacts = SourceFactsExtractor.extract(this),
): PartySourceDescriptorV2? {
    val kind = when (partyOriginKind) {
        "addon" -> PartySourceOriginKind.addon
        "plugin" -> PartySourceOriginKind.plugin
        "embedded" -> PartySourceOriginKind.embedded
        else -> return null
    }
    val safeOrigin = partyOriginId?.takeIf(String::isSafePartyOriginId) ?: return null
    val releaseIdentity = listOfNotNull(
        facts.filename,
        streamData?.filename,
        behaviorHints.filename,
        clientResolve?.filename,
        clientResolve?.torrentName,
        title,
        name,
    ).firstOrNull { it.isNotBlank() } ?: return null
    val normalizedHash = p2pInfoHash?.lowercase()?.takeIf(String::isPartyInfoHash)
    val normalizedIndex = p2pFileIdx?.takeIf { it >= 0 && normalizedHash != null }
    return PartySourceDescriptorV2(
        originKind=kind,
        originId=safeOrigin,
        originVersion=partyOriginVersion?.takeIf { it.length<=64 && it.none { character -> character in "/?#@" } },
        infoHash=normalizedHash,
        fileIndex=normalizedIndex,
        releaseFingerprint=partyReleaseFingerprint(releaseIdentity),
        media=PartySourceMedia(
            resolution=facts.resolution?.height?.let { "${it}p" },
            releaseQuality=facts.releaseQuality?.safeMediaToken(),
            codec=facts.codec?.safeMediaToken(),
            dynamicRange=facts.dynamicRange.boundedMediaTokens(),
            audioCodecs=facts.audioCodecs.boundedMediaTokens(),
            audioChannels=facts.audioChannels?.toString(),
            languages=facts.languages.boundedMediaTokens(),
            sizeBytes=facts.sizeBytes?.takeIf { it>=0 },
        ),
    )
}

/**
 * Tokens in a fixed order, cut to what the backend accepts. A multi-audio release can legitimately
 * name more than [PartySourceMediaListLimit] languages, and the list is advisory - source matching
 * reads resolution, quality, codec and size only - so the honest response to an over-long list is
 * a shorter one. Sorted first so every platform keeps the same members of a truncated set.
 */
private fun Iterable<String>.boundedMediaTokens(): Set<String> =
    mapNotNull(String::safeMediaToken).distinct().sorted().take(PartySourceMediaListLimit).toSet()

private fun String.safeMediaToken(): String? = trim().lowercase()
    .replace('_','-')
    .takeIf { it.length in 1..24 && it.matches(Regex("^[a-z0-9][a-z0-9.+-]{0,23}$")) }
