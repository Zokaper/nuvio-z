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
            dynamicRange=facts.dynamicRange.mapNotNull(String::safeMediaToken).toSet(),
            audioCodecs=facts.audioCodecs.mapNotNull(String::safeMediaToken).toSet(),
            audioChannels=facts.audioChannels?.toString(),
            languages=facts.languages.mapNotNull(String::safeMediaToken).toSet(),
            sizeBytes=facts.sizeBytes?.takeIf { it>=0 },
        ),
    )
}

private fun String.safeMediaToken(): String? = trim().lowercase()
    .replace('_','-')
    .takeIf { it.length in 1..24 && it.matches(Regex("^[a-z0-9][a-z0-9.+-]{0,23}$")) }
