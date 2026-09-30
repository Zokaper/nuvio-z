package com.nuvio.app.features.watchparty

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Main-thread lifecycle revisions; queued publications must still describe the latest event. */
internal class PartyLifecyclePublication {
    private val mutex = Mutex()
    private var revision = 0L
    fun nextRevision(): Long = ++revision

    suspend fun publishIfCurrent(observedRevision: Long, publish: suspend () -> Unit) {
        mutex.withLock {
            if (observedRevision == revision) publish()
        }
    }
}
