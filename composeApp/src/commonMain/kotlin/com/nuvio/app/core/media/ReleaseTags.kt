package com.nuvio.app.core.media

/**
 * The single release-tag vocabulary: what an audio codec, a channel layout and a dynamic range
 * are called inside a release name, and how to read them out of one.
 *
 * **The app used to have two parsers that disagreed about the same file.**
 * `features/debrid/DebridStreamPresentation.kt` drew the badges the user can see and got
 * `hdr10+`, `hdr10plus` and `dovi` right; `features/downloads/SourceFacts.kt` fed the auto-picker
 * and got all three wrong, so an HDR10+ remux ranked as SDR - *below* a plain HDR release - under
 * a preference that asked for HDR. `SourceFacts` also parsed no audio at all, which is why a
 * 95 GB HDR remux with a lossy track could beat a lossless one for a user who asked for lossless.
 * Both now delegate here, so the badge and the pick are answering from the same tables.
 *
 * ⚠ **Import-free on purpose.** This is the same rule `core/language/LanguageCodes.kt` follows:
 * `SourceFacts.kt` and `SourceRanking.kt` have to compile outside Gradle for group 1 of
 * `scripts/run-pure-suites.sh`, and a single import of a Compose resource or a serialization
 * annotation here would take the whole group with it. Keep it plain Kotlin.
 *
 * ⚠ **Token-bounded matching, never `contains`.** `"cam" in "Camelot"` is why `releaseQuality`
 * used to call a WEB-DL of *Camelot* a cam rip. Use [hasReleaseToken] for anything short.
 */

/**
 * Dynamic-range families, best first.
 *
 * [HDR10_PLUS] and [HDR10] are mutually exclusive here: a release tagged `hdr10+` yields
 * [HDR10_PLUS] alone. Callers that need to show both badges widen it themselves - that is a
 * display decision, and collapsing it here is what made the picker read `hdr10+` as `HDR10`.
 */
enum class ReleaseDynamicRange {
    DOLBY_VISION,
    HDR10_PLUS,
    HDR10,
    HDR,
    HLG,
    SDR,
}

/**
 * Audio codecs a release name can name.
 *
 * [isLossless] is the property the "prefer lossless" preference is actually about. **Atmos is
 * not lossless**: it is an object-based extension carried on either TrueHD (lossless) or DD+
 * (lossy), and a release that says only `Atmos` has not said which. It scores as immersive and
 * mid-lossless rather than as either extreme.
 */
enum class ReleaseAudioCodec(val isLossless: Boolean, val isImmersive: Boolean) {
    ATMOS(isLossless = false, isImmersive = true),
    DTS_X(isLossless = true, isImmersive = true),
    TRUEHD(isLossless = true, isImmersive = false),
    DTS_HD_MA(isLossless = true, isImmersive = false),
    FLAC(isLossless = true, isImmersive = false),
    DTS_HD(isLossless = false, isImmersive = false),
    DTS_ES(isLossless = false, isImmersive = false),
    DTS(isLossless = false, isImmersive = false),
    DD_PLUS(isLossless = false, isImmersive = false),
    DD(isLossless = false, isImmersive = false),
    OPUS(isLossless = false, isImmersive = false),
    AAC(isLossless = false, isImmersive = false),
}

/** Channel layouts, carried as the channel count so callers can compare them numerically. */
enum class ReleaseAudioChannel(val channels: Int) {
    CH_7_1(8),
    CH_6_1(7),
    CH_5_1(6),
    CH_2_0(2),
}

object ReleaseTags {

    /**
     * True when [token] appears in this text as a whole token rather than as a substring.
     *
     * The boundary is "not a letter or a digit" on both sides, so `web-dl` and `5.1` work as
     * tokens even though they carry punctuation, and `cam` does not match inside *Camelot*.
     */
    fun hasReleaseToken(text: String, token: String): Boolean {
        if (text.isEmpty() || token.isEmpty()) return false
        return Regex("(^|[^a-z0-9])${Regex.escape(token.lowercase())}([^a-z0-9]|\$)")
            .containsMatchIn(text.lowercase())
    }

    /**
     * The dynamic ranges [structuredValues] and [text] between them claim.
     *
     * [structuredValues] are tagged fields an addon sent (`parsed.hdr`), which are matched
     * exactly; [text] is prose - a release name, a filename, a display string - which is matched
     * token-bounded. Returns an empty set when nothing is claimed, which is *not* the same as
     * [ReleaseDynamicRange.SDR]: most SDR releases say nothing at all.
     */
    fun dynamicRanges(structuredValues: List<String> = emptyList(), text: String = ""): Set<ReleaseDynamicRange> {
        val combined = (structuredValues + text).joinToString(" ").lowercase()
        if (combined.isBlank()) return emptySet()
        val prose = text.lowercase()

        val hasDolbyVision = structuredValues.any(::isDolbyVisionValue) ||
            DOLBY_VISION_REGEX.containsMatchIn(prose)
        // The HDR *family*, not plain HDR10: this is what says the release is not SDR.
        val hasHdrFamily = structuredValues.any(::isHdrValue) || HDR_FAMILY_REGEX.containsMatchIn(prose)
        // `hdr10+` and `hdr10plus` are substring checks by necessity - a trailing `+` is not a
        // word boundary, which is exactly how the old `\bhdr10\+?\b` came to match bare `hdr10`
        // and label every HDR10+ release as plain HDR10.
        val hasHdr10Plus = hasHdrFamily && (
            combined.contains("hdr10+") ||
                combined.contains("hdr10plus") ||
                hasReleaseToken(combined, "hdr10p")
            )
        val hasHdr10 = hasHdrFamily && !hasHdr10Plus && combined.contains("hdr10")
        val hasHlg = hasReleaseToken(combined, "hlg")
        val hasSdr = hasReleaseToken(combined, "sdr")

        return buildSet {
            if (hasDolbyVision) add(ReleaseDynamicRange.DOLBY_VISION)
            if (hasHdr10Plus) add(ReleaseDynamicRange.HDR10_PLUS)
            if (hasHdr10) add(ReleaseDynamicRange.HDR10)
            // Plain HDR only when nothing more specific was found: the specific members already
            // imply the family, and a set carrying both scores no differently.
            if (hasHdrFamily && !hasHdr10Plus && !hasHdr10 && !hasHlg) add(ReleaseDynamicRange.HDR)
            if (hasHlg) add(ReleaseDynamicRange.HLG)
            if (hasSdr && !hasDolbyVision && !hasHdrFamily) add(ReleaseDynamicRange.SDR)
        }
    }

    /**
     * True when the release claims an HDR-family range - `hdr`, `hdr10`, `hdr10+`, `hlg`.
     *
     * Dolby Vision is deliberately **not** part of this: a DV-only release is not HDR10, and the
     * badge row distinguishes "DV" from "HDR | DV" on exactly this question.
     */
    fun claimsHdrFamily(ranges: Set<ReleaseDynamicRange>): Boolean =
        ranges.any {
            it == ReleaseDynamicRange.HDR ||
                it == ReleaseDynamicRange.HDR10 ||
                it == ReleaseDynamicRange.HDR10_PLUS ||
                it == ReleaseDynamicRange.HLG
        }

    /** The best dynamic range claimed, or null when the release claims none. */
    fun bestDynamicRange(ranges: Set<ReleaseDynamicRange>): ReleaseDynamicRange? =
        ReleaseDynamicRange.entries.firstOrNull { it in ranges }

    /**
     * The audio codecs [structuredValues] and [text] between them claim.
     *
     * A release tagged `DD+` yields both [ReleaseAudioCodec.DD_PLUS] and [ReleaseAudioCodec.DD] -
     * `dd` is a real token inside `dd+` and the badge row has always shown both. Callers that
     * want one answer take the best member rather than the only one.
     */
    fun audioCodecs(structuredValues: List<String> = emptyList(), text: String = ""): Set<ReleaseAudioCodec> {
        val combined = (structuredValues + text).joinToString(" ").lowercase()
        if (combined.isBlank()) return emptySet()
        return buildSet {
            if (hasReleaseToken(combined, "atmos")) add(ReleaseAudioCodec.ATMOS)
            if (combined.contains("dd+") || combined.contains("ddp") || combined.contains("dolby digital plus")) {
                add(ReleaseAudioCodec.DD_PLUS)
            }
            if (hasReleaseToken(combined, "dd") || combined.contains("ac3") || combined.contains("dolby digital")) {
                add(ReleaseAudioCodec.DD)
            }
            if (combined.contains("dts:x") || combined.contains("dtsx")) add(ReleaseAudioCodec.DTS_X)
            if (combined.contains("dts-hd ma") || combined.contains("dtshd ma") ||
                combined.contains("dts-hd.ma") || combined.contains("dts.hd.ma")
            ) {
                add(ReleaseAudioCodec.DTS_HD_MA)
            }
            if (combined.contains("dts-hd") || combined.contains("dtshd") || combined.contains("dts.hd")) {
                add(ReleaseAudioCodec.DTS_HD)
            }
            if (combined.contains("dts-es") || combined.contains("dtses")) add(ReleaseAudioCodec.DTS_ES)
            if (hasReleaseToken(combined, "dts")) add(ReleaseAudioCodec.DTS)
            if (combined.contains("truehd") || combined.contains("true hd") || combined.contains("true-hd")) {
                add(ReleaseAudioCodec.TRUEHD)
            }
            if (hasReleaseToken(combined, "opus")) add(ReleaseAudioCodec.OPUS)
            if (hasReleaseToken(combined, "flac")) add(ReleaseAudioCodec.FLAC)
            if (hasReleaseToken(combined, "aac")) add(ReleaseAudioCodec.AAC)
        }
    }

    /** The channel layouts [structuredValues] and [text] between them claim. */
    fun audioChannels(structuredValues: List<String> = emptyList(), text: String = ""): Set<ReleaseAudioChannel> {
        val combined = (structuredValues + text).joinToString(" ").lowercase()
        if (combined.isBlank()) return emptySet()
        return buildSet {
            if (hasChannelToken(combined, "7.1")) add(ReleaseAudioChannel.CH_7_1)
            if (hasChannelToken(combined, "6.1")) add(ReleaseAudioChannel.CH_6_1)
            if (hasChannelToken(combined, "5.1") || hasReleaseToken(combined, "6ch")) {
                add(ReleaseAudioChannel.CH_5_1)
            }
            if (hasChannelToken(combined, "2.0")) add(ReleaseAudioChannel.CH_2_0)
        }
    }

    /** The highest channel count claimed, or null when the release names none. */
    fun channelCount(channels: Set<ReleaseAudioChannel>): Int? =
        channels.maxOfOrNull { it.channels }

    /** Reads a stored [ReleaseDynamicRange] name back, tolerating anything unrecognised. */
    fun dynamicRangeNamed(value: String): ReleaseDynamicRange? =
        ReleaseDynamicRange.entries.firstOrNull { it.name == value.trim().uppercase() }

    /** Reads a stored [ReleaseAudioCodec] name back, tolerating anything unrecognised. */
    fun audioCodecNamed(value: String): ReleaseAudioCodec? =
        ReleaseAudioCodec.entries.firstOrNull { it.name == value.trim().uppercase() }

    /**
     * Release-quality tokens, best first, each with how it must be matched.
     *
     * **Not uniformly token-bounded, and the mixture is the point.** The long tokens are glued
     * to a prefix all the time - `UHDRemux`, `BDRemux` - so demanding a boundary in front of
     * them would lose a real remux, which is a worse error than the one being fixed. The short
     * ones are false-positive magnets and get the boundary: `"cam" in "Camelot"` was calling a
     * Blu-ray of *Camelot* a cam rip and scoring it at the floor.
     */
    private data class QualityToken(val token: String, val tokenBounded: Boolean)

    private val QUALITY_TOKEN_RULES: List<QualityToken> = listOf(
        QualityToken("remux", tokenBounded = false),
        QualityToken("bluray", tokenBounded = false),
        QualityToken("blu-ray", tokenBounded = false),
        QualityToken("web-dl", tokenBounded = false),
        QualityToken("webrip", tokenBounded = false),
        QualityToken("hdtv", tokenBounded = false),
        QualityToken("dvdrip", tokenBounded = false),
        QualityToken("cam", tokenBounded = true),
    )

    /** Release-quality tokens, best first. */
    val QUALITY_TOKENS: List<String> = QUALITY_TOKEN_RULES.map(QualityToken::token)

    /** The release-quality token this text claims, uppercased, or null. */
    fun releaseQuality(text: String): String? {
        if (text.isBlank()) return null
        val lower = text.lowercase()
        return QUALITY_TOKEN_RULES.firstOrNull { rule ->
            if (rule.tokenBounded) hasReleaseToken(lower, rule.token) else rule.token in lower
        }?.token?.uppercase()
    }

    /**
     * A channel layout, bounded by **digits** rather than by letters.
     *
     * ⚠ The general token rule is wrong for these. `DDP5.1`, `DD5.1`, `AAC2.0` and `TrueHD7.1`
     * glue the layout straight onto the codec, and a letter boundary threw all of them away -
     * which is most of the WEB-DLs in any catalogue. Digits still bound it, so the `5.1` inside
     * `x265.1` is not read as surround.
     */
    private fun hasChannelToken(text: String, token: String): Boolean =
        Regex("(^|[^0-9])${Regex.escape(token)}([^0-9]|\$)").containsMatchIn(text)

    private fun isDolbyVisionValue(value: String): Boolean {
        val normalized = value.lowercase().filter { it.isLetterOrDigit() }
        return normalized == "dv" || normalized == "dovi" || normalized == "dolbyvision"
    }

    private fun isHdrValue(value: String): Boolean {
        val normalized = value.lowercase().filter { it.isLetterOrDigit() || it == '+' }
        return normalized == "hdr" ||
            normalized == "hdr10" ||
            normalized == "hdr10+" ||
            normalized == "hdr10plus" ||
            normalized == "hdr10p" ||
            normalized == "hlg"
    }

    private val DOLBY_VISION_REGEX = Regex("(^|[^a-z0-9])(dv|dovi|dolby[ ._-]?vision)([^a-z0-9]|\$)")
    private val HDR_FAMILY_REGEX = Regex("(^|[^a-z0-9])(hdr|hdr10|hdr10p|hdr10plus|hdr10\\+|hlg)([^a-z0-9]|\$)")
}
