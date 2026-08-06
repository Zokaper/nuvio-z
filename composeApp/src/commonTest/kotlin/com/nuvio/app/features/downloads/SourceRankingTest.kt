package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals

class SourceRankingTest {
    private data class Candidate(
        val id: String,
        val facts: SourceFacts,
        val direct: Boolean = true,
        val order: Int = 0,
    )

    @Test
    fun qualityKeysOutrankCacheAndDirectness() {
        val high = Candidate(
            "high",
            SourceFacts(resolution = VideoResolution.FULL_HD_1080, isDebridReady = false),
            direct = false,
        )
        val low = Candidate(
            "low",
            SourceFacts(resolution = VideoResolution.HD_720, isDebridReady = true),
        )

        assertEquals(listOf("high", "low"), listOf(low, high).sortedWith(comparator()).map { it.id })
    }

    @Test
    fun deterministicTiesUseAddonOrderThenUrl() {
        val facts = SourceFacts(resolution = VideoResolution.FULL_HD_1080, sizeBytes = 1_000)
        val later = Candidate("z", facts, order = 2)
        val firstB = Candidate("b", facts, order = 1)
        val firstA = Candidate("a", facts, order = 1)

        assertEquals(
            listOf("a", "b", "z"),
            listOf(later, firstB, firstA).sortedWith(comparator()).map { it.id },
        )
    }

    private fun comparator() = SourceRanking.comparator<Candidate>(
        preferences = SourceRankingPreferences(),
        midRangeTarget = null,
        factsOf = Candidate::facts,
        isDirectOf = Candidate::direct,
        addonOrderOf = Candidate::order,
        stableUrlOf = Candidate::id,
    )
}
