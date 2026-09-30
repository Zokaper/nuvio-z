package com.nuvio.app.features.watchparty

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PartyLifecyclePublicationTest {
    @Test
    fun queuedReturnCannotClearANewerBackgroundEvent() = runTest {
        val publication = PartyLifecyclePublication()
        val release = CompletableDeferred<Unit>()
        val writes = mutableListOf<Boolean>()
        val firstAway = publication.nextRevision()
        launch { publication.publishIfCurrent(firstAway) { writes.add(true); release.await() } }
        runCurrent()
        val briefReturn = publication.nextRevision()
        launch { publication.publishIfCurrent(briefReturn) { writes.add(false) } }
        runCurrent()
        val nextAway = publication.nextRevision()
        launch { publication.publishIfCurrent(nextAway) { writes.add(true) } }
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf(true, true), writes)
    }

    @Test
    fun latestReturnClearsAwayAfterAnInFlightPublication() = runTest {
        val publication = PartyLifecyclePublication()
        val release = CompletableDeferred<Unit>()
        val writes = mutableListOf<Boolean>()
        val away = publication.nextRevision()
        launch { publication.publishIfCurrent(away) { release.await(); writes.add(true) } }
        runCurrent()
        val returned = publication.nextRevision()
        launch { publication.publishIfCurrent(returned) { writes.add(false) } }
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf(true, false), writes)
    }
}
