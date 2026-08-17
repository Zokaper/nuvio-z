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

    @Test
    fun oneChoiceIsRecordedForBothOfItsReaders() {
        // The height steers the in-player next episode as a tie-break; the id lets the stream
        // route skip the sheet outright. One write, so the two can never describe different
        // choices - which is the failure mode of storing the same decision twice.
        BingeGroupCacheRepository.saveSessionQualityBand("tt0475784", height = 1080, optionId = "1080_low")

        assertEquals(1080, BingeGroupCacheRepository.sessionQualityHeight("tt0475784"))
        assertEquals("1080_low", BingeGroupCacheRepository.sessionQualityBandId("tt0475784"))
    }

    @Test
    fun changingTheSourceRetiresBothReadings() {
        // Pressing "Change" is the user saying the automatic pick was wrong. Clearing only the
        // id would leave the height still steering the in-player next episode towards the band
        // they just rejected.
        BingeGroupCacheRepository.saveSessionQualityBand("tt0475784", height = 1080, optionId = "1080_low")
        BingeGroupCacheRepository.clearSessionQualityBand("tt0475784")

        assertNull(BingeGroupCacheRepository.sessionQualityBandId("tt0475784"))
        assertNull(BingeGroupCacheRepository.sessionQualityHeight("tt0475784"))
    }

    @Test
    fun onlyAnArmedChangeForgetsABand() {
        // `NuvioToastAction` is a typed enum with one central handler and no content identity,
        // so the show being talked about travels as data. A "Change source" pressed for a
        // reason of its own - three episodes later, from the player's own overflow - must not
        // forget a band nobody complained about.
        BingeGroupCacheRepository.saveSessionQualityBand("tt0475784", height = 1080, optionId = "1080_low")

        BingeGroupCacheRepository.consumeArmedBandChange()
        assertEquals("1080_low", BingeGroupCacheRepository.sessionQualityBandId("tt0475784"))

        BingeGroupCacheRepository.armBandChange("tt0475784")
        BingeGroupCacheRepository.consumeArmedBandChange()
        assertNull(BingeGroupCacheRepository.sessionQualityBandId("tt0475784"))
    }

    @Test
    fun anArmingIsSpentOnce() {
        // Consuming it twice would let the *next* show's Change press forget a band belonging
        // to this one.
        BingeGroupCacheRepository.saveSessionQualityBand("tt0475784", height = 1080, optionId = "1080_low")
        BingeGroupCacheRepository.armBandChange("tt0475784")
        BingeGroupCacheRepository.consumeArmedBandChange()

        BingeGroupCacheRepository.saveSessionQualityBand("tt0903747", height = 720, optionId = "720_single")
        BingeGroupCacheRepository.consumeArmedBandChange()
        assertEquals("720_single", BingeGroupCacheRepository.sessionQualityBandId("tt0903747"))
    }

    @Test
    fun disarmingLeavesTheBandAlone() {
        // An explicit tap on the sheet retires the arming without retiring the choice: the user
        // just answered the question, so a later Change is about this pick, not the last one.
        BingeGroupCacheRepository.saveSessionQualityBand("tt0475784", height = 1080, optionId = "1080_low")
        BingeGroupCacheRepository.armBandChange("tt0475784")
        BingeGroupCacheRepository.disarmBandChange()
        BingeGroupCacheRepository.consumeArmedBandChange()

        assertEquals("1080_low", BingeGroupCacheRepository.sessionQualityBandId("tt0475784"))
    }
}
