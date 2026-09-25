package com.nuvio.app.features.downloads

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Assisted "choose when ready" (Phase 9, decided 2026-09-25): for several episodes, source discovery
 * runs in the background as a batch the Downloads screen shows ("Finding sources · 7 of 22"), and
 * the user chooses the quality once it is done - not inside a modal that blocks for minutes.
 *
 * Discovery lives here, not in the flow's session, so closing the sheet, opening another title or
 * starting a second download cannot cancel it. Only removing the batch does.
 *
 * **What is kept, and where.** The batch (entries, scope, [DownloadBatch.awaitsQualityChoice]) is
 * persisted like any other. The candidates - addon streams, some carrying provider links - are kept
 * **in memory only**: persisting them just to skip a re-run would put temporary provider URLs on
 * disk. After a process death the batch finds them again ([resumeInterrupted], "Refreshing
 * sources…"), and only then says it is ready.
 */
internal object AssistedDiscovery {
    private val lock = SynchronizedObject()
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** batchId -> entryId -> candidates. Complete for a batch only once its job has finished. */
    private val found = mutableMapOf<String, MutableMap<String, List<DownloadSourceCandidate>>>()
    private val jobs = mutableMapOf<String, Job>()

    private val _running = MutableStateFlow<Set<String>>(emptySet())

    /** Batches whose discovery is under way. The Android host stays up while this is not empty. */
    val running: StateFlow<Set<String>> = _running.asStateFlow()

    private val _refreshing = MutableStateFlow<Set<String>>(emptySet())

    /** Batches finding their sources again after the process died - "Refreshing sources…". */
    val refreshing: StateFlow<Set<String>> = _refreshing.asStateFlow()

    /** Test seam: the device is told discovery is running (Android host, iOS background time). */
    internal var onRunningChanged: (Boolean) -> Unit = { DownloadsLiveStatusPlatform.onDiscoveryRunning(it) }

    /**
     * Finds sources for every entry of [batch] still waiting for them. [refresh] marks a re-run of
     * discovery whose results were lost, so the row can say so.
     */
    fun start(batch: DownloadBatch, refresh: Boolean = false) {
        val entries = batch.entries.filter {
            it.state == DownloadBatchEntryState.DISCOVERING || it.state == DownloadBatchEntryState.AWAITING_CHOICE
        }
        if (entries.isEmpty()) return
        val targets = entries.map { DownloadBatchCoordinator.targetOf(batch, it) }
        val batchId = batch.id
        if (refresh) {
            // Found before the process died, but the candidates went with it: looking again.
            entries.filter { it.state == DownloadBatchEntryState.AWAITING_CHOICE }.forEach {
                DownloadsRepository.updateBatchEntry(batchId, it.copy(state = DownloadBatchEntryState.DISCOVERING))
            }
        }
        val (job, becameRunning) = synchronized(lock) {
            if (jobs[batchId]?.isActive == true) return
            found[batchId] = mutableMapOf()
            val first = _running.value.isEmpty()
            _running.update { it + batchId }
            if (refresh) _refreshing.update { it + batchId }
            // Started only once it is registered and the device knows discovery is running, so a
            // run that finishes at once cannot report "idle" before "running".
            val launched = scope.launch(start = CoroutineStart.LAZY) {
                val self = coroutineContext[Job]
                try {
                    discover(batchId, entries, targets)
                } finally {
                    finishRun(batchId, self)
                }
                // Not cancelled: the batch is ready (or found nothing), and the flow says so.
                DownloadFlowController.onDiscoveryFinished(batchId, refreshed = refresh)
            }
            jobs[batchId] = launched
            launched to first
        }
        DownloadDiagnostics.note("assisted_discovery", "batch=${batchId.takeLast(6)} targets=${targets.size} refresh=$refresh")
        if (becameRunning) onRunningChanged(true)
        job.start()
    }

    private suspend fun discover(batchId: String, entries: List<DownloadBatchEntry>, targets: List<DownloadTarget>) = coroutineScope {
        val semaphore = Semaphore(AutomaticDownloadDiscovery.MAX_CONCURRENT_EPISODE_DISCOVERIES)
        var done = 0
        entries.zip(targets).map { (entry, target) ->
            async {
                semaphore.withPermit {
                    val candidates = runCatching { DownloadBatchCoordinator.discover(target) }.getOrDefault(emptyList())
                    val progress = synchronized(lock) {
                        found[batchId]?.put(entry.id, candidates)
                        done += 1
                        done
                    }
                    DownloadsRepository.updateBatchEntry(
                        batchId,
                        entry.copy(state = DownloadBatchEntryState.AWAITING_CHOICE),
                    )
                    DownloadFlowController.onDiscoveryProgress(batchId, progress, targets.size)
                }
            }
        }.awaitAll()
    }

    private fun finishRun(batchId: String, self: Job?) {
        val nowIdle = synchronized(lock) {
            // A cancel may already have removed this run, and a newer one may own the batch since.
            val current = jobs[batchId]
            if (current != null && current !== self) return
            jobs.remove(batchId)
            _running.update { it - batchId }
            _refreshing.update { it - batchId }
            _running.value.isEmpty()
        }
        if (nowIdle) onRunningChanged(false)
    }

    fun isRunning(batchId: String): Boolean = synchronized(lock) { jobs[batchId]?.isActive == true }

    /** Every entry's candidates, once discovery for [batchId] has finished; null while running or lost. */
    fun candidates(batchId: String): Map<String, List<DownloadSourceCandidate>>? = synchronized(lock) {
        if (jobs[batchId]?.isActive == true) null else found[batchId]?.toMap()
    }

    /** The batch was removed: stop looking and drop what was found. */
    fun cancel(batchId: String) {
        val job = synchronized(lock) {
            found.remove(batchId)
            jobs.remove(batchId)
        }
        job?.cancel()
    }

    /** The quality was chosen: the candidates have served their purpose. */
    fun forget(batchId: String) {
        synchronized(lock) { found.remove(batchId) }
    }

    /**
     * Batches that wait for a choice (or were still finding sources) but whose candidates this
     * process does not have - it died in between. Finds them again. Idempotent: called whenever the
     * active profile's batches change, so a profile switch resumes that profile's batches too.
     */
    fun resumeInterrupted(batches: List<DownloadBatch> = DownloadsRepository.batches.value) {
        batches
            .filter { it.awaitsQualityChoice && AssistedChoiceRules.needsDiscovery(it) }
            .filter { batch -> synchronized(lock) { jobs[batch.id]?.isActive != true && found[batch.id] == null } }
            .forEach { start(it, refresh = true) }
    }

    internal fun resetForTests(dispatcher: CoroutineDispatcher) {
        synchronized(lock) {
            jobs.values.forEach { it.cancel() }
            jobs.clear()
            found.clear()
            _running.value = emptySet()
            _refreshing.value = emptySet()
        }
        scope = CoroutineScope(SupervisorJob() + dispatcher)
    }
}

/** The pure decisions of "choose when ready", tested without a device. */
internal object AssistedChoiceRules {
    /** Several episodes go to the background; one film or episode resolves in seconds and keeps the sheet. */
    fun runsInBackground(targetCount: Int, changing: Boolean, intoExistingBatch: Boolean): Boolean =
        targetCount > 1 && !changing && !intoExistingBatch

    /** A batch that still has entries whose sources must be found (again). */
    fun needsDiscovery(batch: DownloadBatch): Boolean = batch.entries.any {
        it.state == DownloadBatchEntryState.DISCOVERING || it.state == DownloadBatchEntryState.AWAITING_CHOICE
    }

    enum class Announcement {
        /** The finding sheet is still open on this batch: go straight to the quality choice. */
        OPEN_SHEET,

        /** Nuvio is on screen: an in-app prompt, no system notification. */
        IN_APP,

        /** Nuvio is in the background: the system notification. */
        SYSTEM,

        /** Already told (a refresh after a process death): the Downloads row says it. */
        NONE,
    }

    fun announcement(sheetShowsBatch: Boolean, alreadyAnnounced: Boolean, appInForeground: Boolean): Announcement = when {
        sheetShowsBatch -> Announcement.OPEN_SHEET
        alreadyAnnounced -> Announcement.NONE
        appInForeground -> Announcement.IN_APP
        else -> Announcement.SYSTEM
    }

    /** "Lanterns S1" for one season, the title alone for several (or a film). */
    fun seasonOf(batch: DownloadBatch): Int? = batch.entries.mapNotNull { it.season }.distinct().singleOrNull()
}
