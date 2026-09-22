package com.nuvio.app.features.watchparty

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val PartySourceContractVersion = 2

@Serializable
enum class PartySourceOriginKind { addon, plugin, embedded }

@Serializable
data class PartySourceMedia(
    val resolution: String? = null,
    @SerialName("release_quality") val releaseQuality: String? = null,
    val codec: String? = null,
    @SerialName("dynamic_range") val dynamicRange: Set<String> = emptySet(),
    @SerialName("audio_codecs") val audioCodecs: Set<String> = emptySet(),
    @SerialName("audio_channels") val audioChannels: String? = null,
    val languages: Set<String> = emptySet(),
    @SerialName("size_bytes") val sizeBytes: Long? = null,
)

/** The complete source identity allowed to cross the party boundary. */
@Serializable
data class PartySourceDescriptorV2(
    val version: Int = PartySourceContractVersion,
    @SerialName("origin_kind") val originKind: PartySourceOriginKind,
    @SerialName("origin_id") val originId: String,
    @SerialName("origin_version") val originVersion: String? = null,
    @SerialName("info_hash") val infoHash: String? = null,
    @SerialName("file_index") val fileIndex: Int? = null,
    @SerialName("release_fingerprint") val releaseFingerprint: String,
    val media: PartySourceMedia = PartySourceMedia(),
) {
    init {
        require(version == PartySourceContractVersion)
        require(originId.isSafePartyOriginId())
        require(infoHash == null || infoHash.isPartyInfoHash())
        require(fileIndex == null || (fileIndex >= 0 && infoHash != null))
        require(releaseFingerprint.matches(PartyReleaseFingerprintRegex))
    }
}

@Serializable
data class PartyTrackIntent(
    @SerialName("audio_language") val audioLanguage: String? = null,
    @SerialName("subtitle_language") val subtitleLanguage: String? = null,
    @SerialName("subtitles_enabled") val subtitlesEnabled: Boolean? = null,
)

enum class PartySourceMatchTier {
    ExactTorrentFile,
    ExactOriginRelease,
    ExactRelease,
    EquivalentMedia,
    Fallback,
    None,
}

data class PartySourceCandidate<T>(
    val value: T,
    val descriptor: PartySourceDescriptorV2,
    val normalRank: Int,
    val durationMs: Long? = null,
    val contentMatches: Boolean = true,
    val protocolSafe: Boolean = true,
    val addonAllowed: Boolean = true,
    val languageWatchable: Boolean = true,
)

data class TieredPartySources<T>(
    val tier: PartySourceMatchTier,
    val candidates: List<PartySourceCandidate<T>>,
    val fallback: PartySourceCandidate<T>? = null,
)

private val PartyOriginIdRegex = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
private val PartyInfoHashRegex = Regex("^(?:[0-9a-f]{40}|[0-9a-f]{64})$")
private val PartyReleaseFingerprintRegex = Regex("^sha256:[0-9a-f]{64}$")

fun String.isSafePartyOriginId(): Boolean =
    matches(PartyOriginIdRegex) && !contains(Regex("(?i)(?:https?|wss?|file|magnet):|[/?#@]"))

fun String.isPartyInfoHash(): Boolean = lowercase().matches(PartyInfoHashRegex)

fun canonicalPartyRelease(value: String): String = value
    .substringBefore('?')
    .substringBefore('#')
    .lowercase()
    .replace(Regex("(?i)https?://\\S+|magnet:\\S+"), " ")
    .replace(Regex("(?i)\\b(?:seeders?|seeds?|peers?)\\s*[:=]?\\s*\\d+\\b"), " ")
    .replace(Regex("(?i)\\b(?:real[- ]?debrid|alldebrid|premiumize|torbox|debrid)\\b"), " ")
    .replace(Regex("[^a-z0-9]+"), ".")
    .trim('.')
    .replace(Regex("\\.+"), ".")

fun partyReleaseFingerprint(value: String): String = "sha256:${sha256(canonicalPartyRelease(value))}"

fun partySourceMatchTier(
    host: PartySourceDescriptorV2,
    candidate: PartySourceDescriptorV2,
): PartySourceMatchTier {
    val hostHash = host.infoHash?.lowercase()
    val candidateHash = candidate.infoHash?.lowercase()
    if (hostHash != null && candidateHash != null && hostHash == candidateHash) {
        if (host.fileIndex != null && candidate.fileIndex != null && host.fileIndex == candidate.fileIndex) {
            return PartySourceMatchTier.ExactTorrentFile
        }
        // A torrent file is the release identity for multi-file torrents. Unknown or different
        // indexes are contradictory evidence and may not be relabelled as an exact digest match.
        return PartySourceMatchTier.None
    }
    if (host.originKind == candidate.originKind && host.originId == candidate.originId &&
        host.releaseFingerprint == candidate.releaseFingerprint
    ) return PartySourceMatchTier.ExactOriginRelease
    if (host.releaseFingerprint == candidate.releaseFingerprint && !mediaContradicts(host.media, candidate.media) &&
        (hostHash == null || candidateHash == null || hostHash == candidateHash)
    ) return PartySourceMatchTier.ExactRelease
    if (mediaEquivalent(host.media, candidate.media)) return PartySourceMatchTier.EquivalentMedia
    return PartySourceMatchTier.Fallback
}

fun <T> tierPartySourceCandidates(
    host: PartySourceDescriptorV2,
    candidates: List<PartySourceCandidate<T>>,
    hostDurationMs: Long? = null,
): TieredPartySources<T> {
    val eligible = candidates.filter {
        it.contentMatches && it.protocolSafe && it.addonAllowed && it.languageWatchable &&
            (hostDurationMs == null || it.durationMs == null || arePartyDurationsCompatible(hostDurationMs, it.durationMs))
    }
    val grouped = eligible.groupBy { partySourceMatchTier(host, it.descriptor) }
    val strongest = listOf(
        PartySourceMatchTier.ExactTorrentFile,
        PartySourceMatchTier.ExactOriginRelease,
        PartySourceMatchTier.ExactRelease,
        PartySourceMatchTier.EquivalentMedia,
    ).firstOrNull { !grouped[it].isNullOrEmpty() }
    val fallback = grouped[PartySourceMatchTier.Fallback]
        ?.maxWithOrNull(compareBy<PartySourceCandidate<T>> { it.normalRank }.thenBy { it.descriptor.releaseFingerprint })
    return if (strongest == null) {
        TieredPartySources(PartySourceMatchTier.None, emptyList(), fallback)
    } else {
        TieredPartySources(
            tier = strongest,
            candidates = grouped.getValue(strongest)
                .distinctBy { it.descriptor }
                .sortedWith(compareByDescending<PartySourceCandidate<T>> { it.normalRank }.thenBy { it.descriptor.releaseFingerprint }),
            fallback = fallback,
        )
    }
}

private fun mediaContradicts(left: PartySourceMedia, right: PartySourceMedia): Boolean =
    listOf(left.resolution to right.resolution, left.releaseQuality to right.releaseQuality, left.codec to right.codec)
        .any { (a, b) -> a != null && b != null && !a.equals(b, ignoreCase = true) } ||
        (left.sizeBytes != null && right.sizeBytes != null && left.sizeBytes > 0 &&
            kotlin.math.abs(left.sizeBytes - right.sizeBytes).toDouble() / left.sizeBytes > 0.08)

private fun mediaEquivalent(left: PartySourceMedia, right: PartySourceMedia): Boolean {
    if (mediaContradicts(left, right)) return false
    val sameResolution = left.resolution != null && left.resolution.equals(right.resolution, true)
    val sameCodec = left.codec != null && left.codec.equals(right.codec, true)
    val sameQuality = left.releaseQuality != null && left.releaseQuality.equals(right.releaseQuality, true)
    val closeSize = left.sizeBytes != null && right.sizeBytes != null && left.sizeBytes > 0 &&
        kotlin.math.abs(left.sizeBytes - right.sizeBytes).toDouble() / left.sizeBytes <= 0.02
    return listOf(sameResolution, sameCodec, sameQuality, closeSize).count { it } >= 3
}

/** Small import-free SHA-256 implementation so common code and pure tests share one digest. */
private fun sha256(input: String): String {
    val constants = intArrayOf(
        0x428a2f98,0x71374491,0xb5c0fbcf.toInt(),0xe9b5dba5.toInt(),0x3956c25b,0x59f111f1,0x923f82a4.toInt(),0xab1c5ed5.toInt(),
        0xd807aa98.toInt(),0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe.toInt(),0x9bdc06a7.toInt(),0xc19bf174.toInt(),
        0xe49b69c1.toInt(),0xefbe4786.toInt(),0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
        0x983e5152.toInt(),0xa831c66d.toInt(),0xb00327c8.toInt(),0xbf597fc7.toInt(),0xc6e00bf3.toInt(),0xd5a79147.toInt(),0x06ca6351,0x14292967,
        0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e.toInt(),0x92722c85.toInt(),
        0xa2bfe8a1.toInt(),0xa81a664b.toInt(),0xc24b8b70.toInt(),0xc76c51a3.toInt(),0xd192e819.toInt(),0xd6990624.toInt(),0xf40e3585.toInt(),0x106aa070,
        0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
        0x748f82ee,0x78a5636f,0x84c87814.toInt(),0x8cc70208.toInt(),0x90befffa.toInt(),0xa4506ceb.toInt(),0xbef9a3f7.toInt(),0xc67178f2.toInt(),
    )
    val bytes = input.encodeToByteArray().toMutableList()
    val bitLength = bytes.size.toLong() * 8
    bytes += 0x80.toByte()
    while (bytes.size % 64 != 56) bytes += 0.toByte()
    for (shift in 56 downTo 0 step 8) bytes += ((bitLength ushr shift) and 0xff).toByte()
    val h = intArrayOf(0x6a09e667,0xbb67ae85.toInt(),0x3c6ef372,0xa54ff53a.toInt(),0x510e527f,0x9b05688c.toInt(),0x1f83d9ab,0x5be0cd19)
    for (offset in bytes.indices step 64) {
        val w = IntArray(64)
        for (i in 0 until 16) {
            val p = offset + i * 4
            w[i] = ((bytes[p].toInt() and 0xff) shl 24) or ((bytes[p+1].toInt() and 0xff) shl 16) or
                ((bytes[p+2].toInt() and 0xff) shl 8) or (bytes[p+3].toInt() and 0xff)
        }
        for (i in 16 until 64) {
            val x = w[i-15]; val y = w[i-2]
            val s0 = x.rotateRight(7) xor x.rotateRight(18) xor (x ushr 3)
            val s1 = y.rotateRight(17) xor y.rotateRight(19) xor (y ushr 10)
            w[i] = w[i-16] + s0 + w[i-7] + s1
        }
        var a=h[0]; var b=h[1]; var c=h[2]; var d=h[3]; var e=h[4]; var f=h[5]; var g=h[6]; var hh=h[7]
        for (i in 0 until 64) {
            val s1=e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val ch=(e and f) xor (e.inv() and g)
            val t1=hh+s1+ch+constants[i]+w[i]
            val s0=a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val maj=(a and b) xor (a and c) xor (b and c)
            val t2=s0+maj
            hh=g; g=f; f=e; e=d+t1; d=c; c=b; b=a; a=t1+t2
        }
        h[0]+=a;h[1]+=b;h[2]+=c;h[3]+=d;h[4]+=e;h[5]+=f;h[6]+=g;h[7]+=hh
    }
    return h.joinToString("") { it.toUInt().toString(16).padStart(8,'0') }
}
