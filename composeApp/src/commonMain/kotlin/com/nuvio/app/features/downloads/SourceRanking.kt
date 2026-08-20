package com.nuvio.app.features.downloads

import com.nuvio.app.core.language.languageMatchesPreference
import kotlin.math.abs

/** Ranking knobs shared by download and playback selection after their protocol gates. */
data class SourceRankingPreferences(
    val preferredAudioLanguage: String? = null,
    /** The user's "also accept" language. Stored for years; never read by ranking until now. */
    val secondaryAudioLanguage: String? = null,
    val codecPreference: CodecPreference = CodecPreference.ANY,
    val dynamicRangePolicy: DynamicRangePolicy = DynamicRangePolicy.ANY,
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
            when (preferences.dynamicRangePolicy) {
                DynamicRangePolicy.AVOID_HDR -> factsOf(it).dynamicRange.isEmpty()
                DynamicRangePolicy.PREFER_HDR -> factsOf(it).dynamicRange.isNotEmpty()
                else -> true
            }
        }.thenByDescending {
            preferences.codecPreference == CodecPreference.ANY ||
                factsOf(it).codec == preferences.codecPreference.name
        }.thenByDescending {
            releaseQualityScore(factsOf(it).releaseQuality)
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
