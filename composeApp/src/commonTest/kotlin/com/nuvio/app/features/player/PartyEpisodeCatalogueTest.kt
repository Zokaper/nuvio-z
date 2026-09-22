package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamsUiState
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The guest's content handoff realizes from the episode catalogue it requested, and only that.
 *
 * Hardware Bug 2 (2026-09-15) was this wiring: the handoff requested the new episode's streams and
 * then waited on the *sources* panel's catalogue, which that request never fills, so every guest
 * stayed on the previous episode.
 */
class PartyEpisodeCatalogueTest {
    private val loaded = StreamsUiState(isAnyLoading = false)

    @Test fun theCatalogueForTheAdoptedEpisodeIsUsed() {
        assertSame(loaded, partyEpisodeCatalogueFor("tt2:2:10", loadedVideoId = "tt2:2:10", catalogue = loaded))
    }

    @Test fun aCatalogueForAnyOtherVideoIsNotYetAnAnswer() {
        // The previous episode's streams are the wrong video to match the host's new release against.
        assertNull(partyEpisodeCatalogueFor("tt2:2:10", loadedVideoId = "tt2:2:9", catalogue = loaded))
        // And a cleared catalogue describes nothing at all.
        assertNull(partyEpisodeCatalogueFor("tt2:2:10", loadedVideoId = null, catalogue = loaded))
    }
}
