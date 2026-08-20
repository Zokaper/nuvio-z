package com.nuvio.app.features.downloads

import com.nuvio.app.core.language.languageMatchesPreference
import com.nuvio.app.core.media.ReleaseAudioCodec
import com.nuvio.app.core.media.ReleaseDynamicRange
import com.nuvio.app.core.media.ReleaseTags
import kotlin.math.abs

/**
 * What the user wants out of a release's audio track.
 *
 * One knob, deliberately: channels feed the score without a second setting, because "5.1 or
 * better" is a consequence of wanting surround, not an independent question.
 */
enum class AudioPreference { ANY, PREFER_SURROUND, PREFER_LOSSLESS, PREFER_IMMERSIVE, REQUIRE_LOSSLESS }

/** Ranking knobs shared by download and playback selection after their protocol gates. */
data class SourceRankingPreferences(
    val preferredAudioLanguage: String? = null,
    /** The user's "also accept" language. Stored for years; never read by ranking until now. */
    val secondaryAudioLanguage: String? = null,
    val codecPreference: CodecPreference = CodecPreference.ANY,
    val dynamicRangePolicy: DynamicRangePolicy = DynamicRangePolicy.ANY,
    val audioPreference: AudioPreference = AudioPreference.ANY,
    val sizePreference: SizePreference = SizePreference.LARGEST_UNDER_CAP,
)

/**
 * The common source comparator. Callers keep their own eligibility and protocol rules;
 * only ordering belongs here.
 */
object SourceRanking {
    fun midRangeTarget(facts: List<SourceFacts>, capBytes: Long): Long? {
        val fittingSizes = facts.mapNotNull(SourceFacts::sizeBytes)
            .filter { it <= capBytes }
            .sorted()
        return fittingSizes.getOrNull(fittingSizes.size / 2)
    }

    fun <T> comparator(
        preferences: SourceRankingPreferences,
        midRangeTarget: Long?,
        factsOf: (T) -> SourceFacts,
        isDirectOf: (T) -> Boolean,
        addonOrderOf: (T) -> Int,
        stableUrlOf: (T) -> String,
    ): Comparator<T> {
        val qualityComparator = compareByDescending<T> {
            factsOf(it).resolution?.height ?: Int.MIN_VALUE
        }.thenByDescending {
            languageScore(factsOf(it), preferences)
        }.thenByDescending {
            mediaScore(factsOf(it), preferences)
        }.thenByDescending {
            // Cached status settles quality ties; it must never cost a resolution tier.
            factsOf(it).isDebridReady == true
        }.thenByDescending(isDirectOf)

        val sizeComparator = when (preferences.sizePreference) {
            SizePreference.LARGEST_UNDER_CAP -> qualityComparator.thenByDescending {
                factsOf(it).sizeBytes ?: Long.MIN_VALUE
            }
            SizePreference.MID_RANGE -> if (midRangeTarget == null) {
                qualityComparator.thenByDescending { factsOf(it).sizeBytes ?: Long.MIN_VALUE }
            } else {
                qualityComparator.thenBy {
                    factsOf(it).sizeBytes?.let { size -> abs(size - midRangeTarget) } ?: Long.MAX_VALUE
                }.thenByDescending {
                    factsOf(it).sizeBytes ?: Long.MIN_VALUE
                }
            }
            SizePreference.SMALLEST -> qualityComparator.thenBy {
                factsOf(it).sizeBytes ?: Long.MAX_VALUE
            }
        }

        return sizeComparator.thenBy(addonOrderOf).thenBy(stableUrlOf)
    }

    /**
     * How well a source's declared languages match what the user can watch. Higher is better.
     *
     * **This used to be a boolean, and the boolean was almost always false on both sides.** It
     * asked `preferred in facts.languages` against a set built from a seven-language table, so
     * an English release (which names no language, because English is the unmarked case) and a
     * Hindi one (whose token the table did not carry) scored identically. The key sat second in
     * the comparator, immediately after resolution, and discriminated nothing at all - which is
     * why sources with no watchable audio kept being auto-played.
     *
     * The ordering below is the argument:
     *
     *  - [NAMES_PREFERRED] - it says it has your language.
     *  - [UNDECLARED] - it says nothing, which is what most English releases do. Deliberately
     *    **above** the secondary language: "probably your first choice" beats "definitely your
     *    second". A release marked `MULTi` sits here too; it carries several tracks and the app
     *    cannot tell which without opening it.
     *  - [NAMES_SECONDARY] - your fallback language, explicitly.
     *  - [SUBTITLES_ONLY] - wrong audio, but it ships subtitles you can read. Watchable, and
     *    not the same thing as unwatchable, which is why it is not the floor.
     *  - [NAMES_OTHER_ONLY] - it named its languages and yours was not among them.
     *
     * Returns [UNDECLARED] for everyone when no preference is set, so the key falls out of the
     * comparator entirely rather than imposing an order nobody asked for.
     */
    fun languageScore(facts: SourceFacts, preferences: SourceRankingPreferences): Int {
        val preferred = preferences.preferredAudioLanguage?.trim()?.takeIf { it.isNotEmpty() }
            ?: return UNDECLARED
        val secondary = preferences.secondaryAudioLanguage?.trim()?.takeIf { it.isNotEmpty() }

        fun Set<String>.covers(target: String?) =
            target != null && any { languageMatchesPreference(it, target) }

        return when {
            facts.languages.covers(preferred) -> NAMES_PREFERRED
            facts.languages.isEmpty() || facts.isMultiLanguage -> UNDECLARED
            facts.languages.covers(secondary) -> NAMES_SECONDARY
            facts.subtitleLanguages.covers(preferred) ||
                facts.subtitleLanguages.covers(secondary) -> SUBTITLES_ONLY
            else -> NAMES_OTHER_ONLY
        }
    }

    const val NAMES_PREFERRED = 4
    const val UNDECLARED = 3
    const val NAMES_SECONDARY = 2
    const val SUBTITLES_ONLY = 1
    const val NAMES_OTHER_ONLY = 0

    /**
     * Whether a source is watchable at all in the user's language.
     *
     * Only [NAMES_OTHER_ONLY] fails: the source listed its languages, yours was not one of them,
     * and it carries no subtitles you can read either. Everything else - including a release
     * whose audio is wrong but whose subtitles are not - stays eligible, because the complaint
     * this answers is "no English audio **or** subs", not "not English audio".
     */
    fun isLanguageWatchable(facts: SourceFacts, preferences: SourceRankingPreferences): Boolean =
        languageScore(facts, preferences) > NAMES_OTHER_ONLY

    /**
     * How well a release satisfies everything the user asked for *about the file itself* -
     * dynamic range, audio format, channel layout, video codec and release quality - as one
     * additive score. Higher is better.
     *
     * **These four used to be four consecutive lexicographic keys, and that is the bug.** HDR was
     * a boolean sitting above codec, which sat above release quality, so the first key that
     * discriminated decided the pick outright and nothing below it could speak. A user asking for
     * lossless audio *and* HDR10 got whichever release won the HDR key - and since audio was not
     * parsed at all, "lossless" never entered the comparison. Adding the components means a
     * release that satisfies both beats one that satisfies either, which is what the request
     * meant.
     *
     * Two asymmetries that look like inconsistencies and are not:
     *
     *  - **Unstated audio scores mid; unstated dynamic range scores as SDR.** HDR is reliably
     *    tagged in release names and audio format frequently is not. Scoring silence at the floor
     *    would demote most WEB-DLs for a user who asked for lossless - the same argument
     *    [UNDECLARED] already makes for languages.
     *  - **`REQUIRE_*` demotes by [UNSATISFIED_REQUIREMENT] rather than excluding.** A hard
     *    demotion keeps the source in the failure chain, exactly as the language gate is
     *    "a partition, never a filter": deleting them would leave a title whose every release
     *    fails the requirement with nothing to play.
     */
    fun mediaScore(facts: SourceFacts, preferences: SourceRankingPreferences): Int =
        dynamicRangeScore(facts, preferences.dynamicRangePolicy) +
            audioScore(facts, preferences.audioPreference) +
            channelScore(facts, preferences.audioPreference) +
            codecScore(facts, preferences.codecPreference) +
            releaseQualityScore(facts.releaseQuality)

    /**
     * Whether the release claims any HDR-family range or Dolby Vision.
     *
     * ⚠ Not `dynamicRange.isNotEmpty()`. The set can now carry `SDR` as a positive claim, so the
     * emptiness test that used to stand in for this would read a release tagged `SDR` as HDR.
     */
    fun claimsHdr(facts: SourceFacts): Boolean =
        facts.dynamicRange.any { it != ReleaseDynamicRange.SDR.name }

    fun claimsDolbyVision(facts: SourceFacts): Boolean =
        ReleaseDynamicRange.DOLBY_VISION.name in facts.dynamicRange

    fun dynamicRangeScore(facts: SourceFacts, policy: DynamicRangePolicy): Int {
        val best = facts.dynamicRange.mapNotNull(ReleaseTags::dynamicRangeNamed).toSet()
            .let(ReleaseTags::bestDynamicRange)
        return when (policy) {
            // ANY scores every candidate the same, so the component falls out of the comparison
            // rather than imposing an order nobody asked for.
            DynamicRangePolicy.ANY -> 0
            DynamicRangePolicy.PREFER_HDR -> when (best) {
                ReleaseDynamicRange.DOLBY_VISION, ReleaseDynamicRange.HDR10_PLUS -> 6
                ReleaseDynamicRange.HDR10 -> 5
                ReleaseDynamicRange.HDR -> 4
                ReleaseDynamicRange.HLG -> 3
                else -> 0
            }
            DynamicRangePolicy.AVOID_HDR -> if (claimsHdr(facts)) 0 else 6
            DynamicRangePolicy.REQUIRE_HDR ->
                if (claimsHdr(facts)) 6 else UNSATISFIED_REQUIREMENT
            DynamicRangePolicy.REQUIRE_DOLBY_VISION ->
                if (claimsDolbyVision(facts)) 6 else UNSATISFIED_REQUIREMENT
        }
    }

    fun audioScore(facts: SourceFacts, preference: AudioPreference): Int {
        if (preference == AudioPreference.ANY) return 0
        val codecs = facts.audioCodecs.mapNotNull(ReleaseTags::audioCodecNamed).toSet()
        val statedNothing = codecs.isEmpty() && facts.audioChannels == null
        return when (preference) {
            AudioPreference.ANY -> 0
            AudioPreference.PREFER_LOSSLESS -> when {
                codecs.any(ReleaseAudioCodec::isLossless) -> 6
                // Atmos with no lossless carrier named, and DD+, are both a step above plain
                // lossy without being what was asked for.
                ReleaseAudioCodec.ATMOS in codecs || ReleaseAudioCodec.DD_PLUS in codecs -> 3
                statedNothing -> UNSTATED
                else -> 0
            }
            AudioPreference.PREFER_IMMERSIVE -> when {
                codecs.any(ReleaseAudioCodec::isImmersive) -> 6
                statedNothing -> UNSTATED
                else -> 0
            }
            AudioPreference.PREFER_SURROUND -> when {
                (facts.audioChannels ?: 0) >= SURROUND_CHANNELS -> 6
                statedNothing -> UNSTATED
                else -> 0
            }
            // A requirement nothing satisfies orders every candidate identically, which is the
            // intended outcome: it demotes, it does not empty the list.
            AudioPreference.REQUIRE_LOSSLESS ->
                if (codecs.any(ReleaseAudioCodec::isLossless)) 6 else UNSATISFIED_REQUIREMENT
        }
    }

    /** Channels are a tie-break inside an audio preference, never a preference of their own. */
    fun channelScore(facts: SourceFacts, preference: AudioPreference): Int {
        if (preference == AudioPreference.ANY) return 0
        val channels = facts.audioChannels ?: return 0
        return when {
            channels >= 7 -> 2
            channels >= 6 -> 1
            else -> 0
        }
    }

    fun codecScore(facts: SourceFacts, preference: CodecPreference): Int = when {
        preference == CodecPreference.ANY -> 0
        facts.codec == preference.name -> 2
        else -> 0
    }

    /** Audio the release did not name. Mid, not floor - see [mediaScore]. */
    const val UNSTATED = 2

    /** 5.1 and up. Below this the release is stereo, whatever it calls it. */
    const val SURROUND_CHANNELS = 6

    /**
     * What an unmet `REQUIRE_*` costs. Large enough that nothing outranks it back, small enough
     * that the candidate keeps its place in the ordering below everything that qualifies.
     */
    const val UNSATISFIED_REQUIREMENT = -100

    fun releaseQualityScore(value: String?): Int {
        val normalized = value?.uppercase().orEmpty()
        return when {
            "REMUX" in normalized -> 6
            "BLURAY" in normalized || "BLU-RAY" in normalized -> 5
            "WEB-DL" in normalized -> 4
            "WEBRIP" in normalized -> 3
            "HDTV" in normalized -> 2
            "CAM" in normalized -> 0
            else -> 1
        }
    }
}
