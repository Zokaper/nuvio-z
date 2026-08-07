package com.nuvio.app.features.streams

import com.nuvio.app.features.playback.StickySourcePin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BingeGroupCacheRepositoryTest {
    @AfterTest
    fun tearDown() = BingeGroupCacheRepository.clearSessionPins()

    @Test
    fun aStickyPinLastsForTheSessionAndNoLonger() {
        // A pin skips the quality sheet outright. Persisting one silently turns Streamlined
        // into Instant for that season, on that device, with nothing in the UI to undo it.
        val id = BingeGroupCacheRepository.stickyContentId("tt0475784", 2)
        BingeGroupCacheRepository.saveSessionPin(id, StickySourcePin(releaseGroup = "NTb"))

        assertEquals("NTb", BingeGroupCacheRepository.sessionPin(id)?.releaseGroup)

        BingeGroupCacheRepository.clearSessionPins()
        assertNull(BingeGroupCacheRepository.sessionPin(id))
    }

    @Test
    fun anEmptyPinIsNotStored() {
        val id = BingeGroupCacheRepository.stickyContentId("tt0475784", 1)
        BingeGroupCacheRepository.saveSessionPin(id, StickySourcePin(releaseGroup = "NTb"))
        BingeGroupCacheRepository.saveSessionPin(id, StickySourcePin())

        assertNull(BingeGroupCacheRepository.sessionPin(id))
    }

    @Test
    fun pinsAreScopedToTheSeason() {
        val season1 = BingeGroupCacheRepository.stickyContentId("tt0475784", 1)
        val season2 = BingeGroupCacheRepository.stickyContentId("tt0475784", 2)
        BingeGroupCacheRepository.saveSessionPin(season1, StickySourcePin(releaseGroup = "NTb"))

        assertNull(BingeGroupCacheRepository.sessionPin(season2))
    }
}
