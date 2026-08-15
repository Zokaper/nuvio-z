package com.nuvio.app.features.streams

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BingeGroupCacheRepositoryTest {
    @AfterTest
    fun tearDown() = BingeGroupCacheRepository.clearSessionPins()

    @Test
    fun theChosenQualityLastsForTheSessionAndNoLonger() {
        // Persisting it would silently outlive the decision that produced it: the user picked
        // a band once, in one sitting, against one connection.
        BingeGroupCacheRepository.saveSessionQualityHeight("tt0475784", 1080)

        assertEquals(1080, BingeGroupCacheRepository.sessionQualityHeight("tt0475784"))

        BingeGroupCacheRepository.clearSessionPins()
        assertNull(BingeGroupCacheRepository.sessionQualityHeight("tt0475784"))
    }

    @Test
    fun aMeaninglessHeightIsNotStored() {
        BingeGroupCacheRepository.saveSessionQualityHeight("tt0475784", 1080)
        BingeGroupCacheRepository.saveSessionQualityHeight("tt0475784", 0)
        BingeGroupCacheRepository.saveSessionQualityHeight("", 720)

        // The bad writes are refused rather than clearing the good one.
        assertEquals(1080, BingeGroupCacheRepository.sessionQualityHeight("tt0475784"))
        assertNull(BingeGroupCacheRepository.sessionQualityHeight(""))
    }

    @Test
    fun theChosenQualityIsScopedToTheShow() {
        // Keyed by parentMetaId, not by season: the churn it answers is episode-to-episode
        // within one show, and a season boundary is not a reason to forget the choice.
        BingeGroupCacheRepository.saveSessionQualityHeight("tt0475784", 1080)

        assertNull(BingeGroupCacheRepository.sessionQualityHeight("tt0903747"))
    }
}
