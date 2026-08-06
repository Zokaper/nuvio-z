package com.nuvio.app.features.downloads

import kotlin.math.abs

/** Ranking knobs shared by download and playback selection after their protocol gates. */
data class SourceRankingPreferences(
    val preferredAudioLanguage: String? = null,
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
            val preferred = preferences.preferredAudioLanguage?.trim()?.uppercase()
            preferred != null && preferred in factsOf(it).languages
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
