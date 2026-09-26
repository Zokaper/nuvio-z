package com.nuvio.app.features.downloads

import com.nuvio.app.features.details.MetaDetails
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the download flow is showing. One at a time, app-wide, drawn by `DownloadFlowHost`. */
sealed interface DownloadFlowStep {
    data object Idle : DownloadFlowStep

    /** Device rule "Ask", started on mobile data: once per app session. */
    data class AskMobileData(val title: DownloadTitleRef) : DownloadFlowStep

    /** Whole show: which seasons, with All / None / Unwatched. */
    data class ChooseSeasons(
        val title: DownloadTitleRef,
        val seasons: List<DownloadFlowRules.SeasonChoice>,
        val selected: Set<Int>,
        val unwatchedOnly: Boolean,
    ) : DownloadFlowStep {
        val episodeCount: Int get() = DownloadFlowRules.episodeCount(seasons, selected, unwatchedOnly)
    }

    /**
     * Discovery for the Assisted sheet. With a [batchId] it runs in the background ("choose when
     * ready"): closing the sheet leaves it running, and the user is told when it is done.
     */
    data class FindingSources(
        val title: DownloadTitleRef,
        val done: Int,
        val total: Int,
        val batchId: String? = null,
        /** Finding the sources again after the process died. */
        val refreshing: Boolean = false,
        /** "Choose now" was used: the resolution the batch will start at once its sources are in. */
        val chosenHeight: Int? = null,
    ) : DownloadFlowStep

    /** Assisted: one row per available resolution, each the best match there. */
    data class ChooseResolution(
        val title: DownloadTitleRef,
        val scope: DownloadScope,
        val targetCount: Int,
        val rows: List<DownloadResolutionRow>,
        val preselectedHeight: Int?,
        /** Only with one target that has at least one source a manual pick could download. */
        val offersChooseManually: Boolean,
        /**
         * "Choose now": discovery is still running, so every row is an estimate
         * ([DownloadResolutionRow.estimate]) and choosing records the resolution rather than
         * starting anything.
         */
        val estimated: Boolean = false,
    ) : DownloadFlowStep

    /** Discovery found nothing usable. Check again / Choose manually (when possible) / Close. */
    data class NothingToDownload(
        val title: DownloadTitleRef,
        val kind: DownloadEntryDecisionKind,
        val offersChooseManually: Boolean,
    ) : DownloadFlowStep

    /** Batch larger than free space: Download what fits / Cancel. */
    data class NotEnoughSpace(
        val title: DownloadTitleRef,
        val neededBytes: Long,
        val freeBytes: Long,
        val fitCount: Int,
        val totalCount: Int,
    ) : DownloadFlowStep
}

/** One Assisted row. For a season, the totals are across the episodes that have it. */
data class DownloadResolutionRow(
    val height: Int,
    val totalBytes: Long,
    val unknownSizeCount: Int,
    val episodeCount: Int,
    val missingCount: Int,
    val overLimit: Boolean,
    /** For a single item: what the file is ("HEVC · HDR10 · Torrentio"). */
    val detail: String?,
    /** "Choose now" rows only: the estimated range, null when no estimate can honestly be made. */
    val estimate: LongRange? = null,
)

/** Navigation the flow needs; the app shell performs it. */
sealed interface DownloadFlowEvent {
    /** The download source list: a tap enqueues, and it never opens the player. */
    data class OpenManualSourceList(val title: DownloadTitleRef, val target: DownloadTarget) : DownloadFlowEvent

    /** Manual, several episodes: the Choose sources screen for this batch. */
    data class OpenChooseSources(val batchId: String) : DownloadFlowEvent
}

/** Messages the flow raises. An interface so tests read them without resources. */
internal interface DownloadFlowNotices {
    fun findingSource()
    fun started(item: DownloadItem?, height: Int?, bytes: Long?, onChange: (() -> Unit)?)
    fun startedMany(count: Int, needAttention: Int)
    fun needsAttention()
    fun nothingNew()

    /** Assisted "choose when ready", with Nuvio on screen: "Lanterns S1 is ready · Choose". */
    fun qualityReady(title: String, season: Int?, onChoose: () -> Unit)

    /** "Choose now": "1080p chosen · downloads start when sources are found". */
    fun qualityChosenEarly(height: Int) {}
}

/**
 * The only way into a download (Phase 9, plan section 4.3). Every entry point - the title
 * button, an episode row, a season, the long-press sheets, "Change" on a toast or a row, "Choose
 * manually" on an attention card - calls this, and the mode decides what happens:
 *
 * - **Automatic** starts at once (a whole show asks for seasons first) and toasts with Change.
 * - **Assisted** shows one row per resolution, pre-selecting the preferred one.
 * - **Manual** opens the download source list for one item, or Choose sources for several.
 *
 * UI-agnostic: [step] is drawn by `DownloadFlowHost`, [events] are navigation for the shell.
 * None of it ever reaches the player.
 */
object DownloadFlowController {
    private val _step = MutableStateFlow<DownloadFlowStep>(DownloadFlowStep.Idle)
    val step: StateFlow<DownloadFlowStep> = _step.asStateFlow()

    private val _events = MutableSharedFlow<DownloadFlowEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<DownloadFlowEvent> = _events.asSharedFlow()

    internal var notices: DownloadFlowNotices = ToastDownloadFlowNotices
    internal var modeProvider: () -> DownloadMode = { DownloadPolicyRepository.effectiveMode() }
    internal var policyProvider: () -> DownloadPolicy = {
        DownloadPolicyRepository.ensureLoaded()
        DownloadPolicyRepository.policy.value
    }
    internal var isMeteredNow: () -> Boolean = { DownloadScheduler.isMeteredNetwork() }
    internal var freeBytesProvider: () -> Long = { DownloadsPlatformDownloader.freeStorageBytes() }
    internal var isAppInForeground: () -> Boolean = { DownloadsLiveStatusPlatform.isAppInForeground() }
    internal var postChoiceNotification: (DownloadChoiceNotice) -> Unit = { DownloadsLiveStatusPlatform.notifyChoice(it) }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** The answer to "Ask" for this app session; null until asked. */
    private var sessionMobileDataAnswer: Boolean? = null

    private var session: Session? = null

    private class Session(
        val title: DownloadTitleRef,
        val mode: DownloadMode,
        var scope: DownloadScope,
        val meta: MetaDetails?,
        var targets: List<DownloadTarget>,
        var allowMetered: Boolean = false,
        /** Assisted results write into this batch ("Change", "Let Nuvio pick the rest"). */
        var intoBatchId: String? = null,
        /** "Change": the item being replaced. */
        var changing: DownloadItem? = null,
        var candidates: Map<DownloadTarget, List<DownloadSourceCandidate>> = emptyMap(),
        /** Prepared but held back by the free-space check. */
        var heldBatchId: String? = null,
        var heldEntryIds: List<String> = emptyList(),
        /** Assisted "choose when ready": the background batch this session chooses a quality for. */
        var choiceBatchId: String? = null,
    )

    // --- entry points --------------------------------------------------------------------------

    /** A download asked for from a title: a film, an episode, a season or the whole show. */
    fun request(meta: MetaDetails, scope: DownloadScope) {
        DownloadsLiveStatusPlatform.onDownloadRequested()
        val title = DownloadBatchCoordinator.titleRefOf(meta)
        val mode = modeProvider()
        val route = DownloadEntryRouter.route(mode, scope.requestScope())
        val next = Session(
            title = title,
            mode = mode,
            scope = scope,
            meta = meta,
            targets = if (route.startsWithSeasons()) emptyList() else DownloadBatchCoordinator.targetsFor(meta, scope),
        )
        begin(next) { continueAfterAsk(next) }
    }

    private fun continueAfterAsk(current: Session) {
        val meta = current.meta
        if (meta != null && DownloadEntryRouter.route(current.mode, current.scope.requestScope()).startsWithSeasons()) {
            val seasons = DownloadBatchCoordinator.seasonChoices(meta)
            val asked = (current.scope as? DownloadScope.SelectedSeasons)?.seasons?.takeIf { it.isNotEmpty() }
            val (initial, unwatchedOnly) = if (asked != null) {
                asked to false
            } else {
                DownloadFlowRules.defaultSeasonSelection(seasons)
            }
            _step.value = DownloadFlowStep.ChooseSeasons(current.title, seasons, initial, unwatchedOnly)
        } else {
            proceed(current)
        }
    }

    /** "Change" on the Automatic toast or a Downloads row: the Assisted sheet for that item. */
    fun change(item: DownloadItem) {
        val target = DownloadBatchCoordinator.targetOf(item)
        val next = Session(
            title = DownloadBatchCoordinator.titleRefOf(item),
            mode = DownloadMode.ASSISTED,
            scope = if (item.seasonNumber != null && item.episodeNumber != null) {
                DownloadScope.Episode(item.seasonNumber, item.episodeNumber)
            } else {
                DownloadScope.Movie
            },
            meta = null,
            targets = listOf(target),
            allowMetered = item.allowMeteredNetwork,
            changing = item,
        )
        begin(next, askMobileData = false) { startAssisted(next) }
    }

    /** "Choose manually" on an attention card / Choose sources "Pick". */
    fun chooseEntryManually(batch: DownloadBatch, entry: DownloadBatchEntry) {
        _events.tryEmit(
            DownloadFlowEvent.OpenManualSourceList(
                title = DownloadBatchCoordinator.titleRefOf(batch),
                target = DownloadBatchCoordinator.targetOf(batch, entry),
            ),
        )
    }

    /** Manual's "Let Nuvio pick the rest": Assisted rules for every episode still unpicked. */
    fun pickTheRest(batch: DownloadBatch) {
        val remaining = batch.entries.filter {
            it.state == DownloadBatchEntryState.SKIPPED && it.decision == DownloadEntryDecisionKind.MANUAL_PICK
        }
        if (remaining.isEmpty()) return
        val next = Session(
            title = DownloadBatchCoordinator.titleRefOf(batch),
            mode = DownloadMode.ASSISTED,
            scope = batch.scope,
            meta = null,
            targets = remaining.map { DownloadBatchCoordinator.targetOf(batch, it) },
            allowMetered = batch.allowMeteredNetwork,
            intoBatchId = batch.id,
        )
        begin(next, askMobileData = false) { startAssisted(next) }
    }

    /** "Choose" on a failed download's row: the download source list for that item. */
    fun chooseItemManually(item: DownloadItem) {
        _events.tryEmit(
            DownloadFlowEvent.OpenManualSourceList(
                title = DownloadBatchCoordinator.titleRefOf(item),
                target = DownloadBatchCoordinator.targetOf(item),
            ),
        )
    }

    /** "Choose sources" on a Manual attention card. */
    fun openChooseSources(batchId: String) {
        _events.tryEmit(DownloadFlowEvent.OpenChooseSources(batchId))
    }

    /**
     * "Check again" on a download that failed because its source was not cached: discovery
     * again under the Automatic rules, replacing the item if something usable turned up.
     */
    fun recheck(item: DownloadItem) {
        val target = DownloadBatchCoordinator.targetOf(item)
        val current = Session(
            title = DownloadBatchCoordinator.titleRefOf(item),
            mode = DownloadMode.AUTOMATIC,
            scope = if (item.seasonNumber != null && item.episodeNumber != null) {
                DownloadScope.Episode(item.seasonNumber, item.episodeNumber)
            } else {
                DownloadScope.Movie
            },
            meta = null,
            targets = listOf(target),
            allowMetered = item.allowMeteredNetwork,
            changing = item,
        )
        scope.launch {
            val found = DownloadBatchCoordinator.discover(target)
            val entry = DownloadBatchCoordinator.automaticEntry(
                target,
                found,
                DownloadBatchCoordinator.contextFor(target, policyProvider()),
            )
            val batchId = writeEntries(current, listOf(entry))
            queueOrHold(current, batchId, listOf(entry))
            announceSingle(current, entry, onChange = null)
        }
    }

    /** "Check again" on a nothing-cached / no-sources entry: discovery again, Automatic rules. */
    fun checkAgain(batch: DownloadBatch, entry: DownloadBatchEntry) {
        val target = DownloadBatchCoordinator.targetOf(batch, entry)
        scope.launch {
            DownloadsRepository.updateBatchEntry(batch.id, entry.copy(state = DownloadBatchEntryState.DISCOVERING))
            val context = DownloadBatchCoordinator.contextFor(target, policyProvider())
            DownloadBatchCoordinator.prepareEntries(
                batchId = batch.id,
                targets = listOf(target),
                build = { t, found -> DownloadBatchCoordinator.automaticEntry(t, found, context) },
            )
            DownloadsRepository.queueBatch(batch.id, approveUnknownSizes = false, onlyEntryIds = setOf(entry.id))
        }
    }

    // --- answers from the host -----------------------------------------------------------------

    fun answerMobileData(useMobileData: Boolean) {
        val current = session ?: return dismiss()
        sessionMobileDataAnswer = useMobileData
        current.allowMetered = useMobileData
        continueAfterAsk(current)
    }

    fun updateSeasons(selected: Set<Int>, unwatchedOnly: Boolean) {
        val current = _step.value as? DownloadFlowStep.ChooseSeasons ?: return
        _step.value = current.copy(selected = selected, unwatchedOnly = unwatchedOnly)
    }

    fun confirmSeasons() {
        val chosen = _step.value as? DownloadFlowStep.ChooseSeasons ?: return
        val current = session ?: return dismiss()
        val meta = current.meta ?: return dismiss()
        if (chosen.selected.isEmpty()) return
        current.scope = DownloadScope.SelectedSeasons(chosen.selected, chosen.unwatchedOnly)
        current.targets = DownloadBatchCoordinator.targetsFor(meta, current.scope)
        proceed(current)
    }

    fun chooseResolution(height: Int) {
        val current = session ?: return dismiss()
        val choiceBatchId = current.choiceBatchId
        if (choiceBatchId != null && (_step.value as? DownloadFlowStep.ChooseResolution)?.estimated == true) {
            chooseEarly(current, choiceBatchId, height)
            return
        }
        if (choiceBatchId != null) {
            // Found in the background; gone only if the batch was removed meanwhile.
            val found = AssistedDiscovery.candidates(choiceBatchId) ?: return dismiss()
            current.candidates = current.targets.associateWith { found[it.entryId].orEmpty() }
        }
        _step.value = DownloadFlowStep.Idle
        // Not tied to the session: once chosen, it is written and queued whatever the user
        // opens next.
        scope.launch {
            val policy = policyProvider()
            val entries = current.targets.map { target ->
                DownloadBatchCoordinator.resolutionEntry(
                    target,
                    current.candidates[target].orEmpty(),
                    height,
                    DownloadBatchCoordinator.contextFor(target, policy),
                )
            }
            val batchId = writeEntries(current, entries)
            if (choiceBatchId != null) {
                DownloadsRepository.updateBatch(choiceBatchId) { it.copy(awaitsQualityChoice = false) }
                AssistedDiscovery.forget(choiceBatchId)
                DownloadsLiveStatusPlatform.clearChoice(choiceBatchId)
            }
            queueOrHold(current, batchId, entries)
            val changed = current.changing
            if (changed != null || current.targets.size == 1) {
                val entry = entries.single()
                announceSingle(current, entry, onChange = null)
            } else {
                announceMany(entries)
            }
        }
    }

    /** From the Assisted sheet or a nothing-found step, for a single item. */
    fun chooseManually() {
        val current = session ?: return dismiss()
        val target = current.targets.singleOrNull() ?: return dismiss()
        _step.value = DownloadFlowStep.Idle
        session = null
        _events.tryEmit(DownloadFlowEvent.OpenManualSourceList(current.title, target))
    }

    fun checkAgainFromSheet() {
        val current = session ?: return dismiss()
        begin(current, askMobileData = false) { startAssisted(current) }
    }

    fun downloadWhatFits() {
        val step = _step.value as? DownloadFlowStep.NotEnoughSpace ?: return
        val current = session ?: return dismiss()
        val batchId = current.heldBatchId ?: return dismiss()
        val fitting = current.heldEntryIds.take(step.fitCount).toSet()
        dropEntries(batchId, keep = { it.id in fitting || it.state != DownloadBatchEntryState.READY })
        DownloadsRepository.queueBatch(batchId, approveUnknownSizes = false, onlyEntryIds = fitting)
        dismiss()
    }

    fun dismiss() {
        val held = session?.takeIf { _step.value is DownloadFlowStep.NotEnoughSpace }
        if (held != null) {
            // Cancel on the free-space warning: nothing of this batch starts.
            held.heldBatchId?.let { id ->
                dropEntries(id, keep = { it.state != DownloadBatchEntryState.READY })
            }
        }
        job?.cancel()
        job = null
        session = null
        _step.value = DownloadFlowStep.Idle
    }

    // --- internals -----------------------------------------------------------------------------

    private fun begin(next: Session, askMobileData: Boolean = true, then: () -> Unit) {
        job?.cancel()
        session = next
        if (askMobileData) {
            val rule = DownloadsRepository.deviceSettings.value.mobileData
            val answer = sessionMobileDataAnswer
            if (rule == DownloadMobileDataRule.ASK && isMeteredNow()) {
                if (answer == null) {
                    _step.value = DownloadFlowStep.AskMobileData(next.title)
                    return
                }
                next.allowMetered = answer
            }
        }
        then()
    }

    private fun proceed(current: Session) {
        if (current.targets.isEmpty()) {
            notices.nothingNew()
            dismiss()
            return
        }
        when (current.mode) {
            DownloadMode.AUTOMATIC -> startAutomatic(current)
            DownloadMode.ASSISTED -> startAssisted(current)
            DownloadMode.MANUAL -> startManual(current)
        }
    }

    private fun startAutomatic(current: Session) {
        _step.value = DownloadFlowStep.Idle
        notices.findingSource()
        session = null
        // Not the session job: a second download started while this one is still finding its
        // source must not cancel it.
        scope.launch {
            val batch = DownloadBatchCoordinator.newBatch(current.title, current.scope, current.targets, current.allowMetered)
            DownloadsRepository.saveBatch(batch)
            val policy = policyProvider()
            val entries = DownloadBatchCoordinator.prepareEntries(
                batchId = batch.id,
                targets = current.targets,
                build = { target, found ->
                    DownloadBatchCoordinator.automaticEntry(target, found, DownloadBatchCoordinator.contextFor(target, policy))
                },
            )
            queueOrHold(current, batch.id, entries)
            if (current.targets.size == 1) {
                val entry = entries.single()
                announceSingle(current, entry, onChange = { item -> change(item) })
            } else {
                announceMany(entries)
            }
        }
    }

    private fun startAssisted(current: Session) {
        if (
            AssistedChoiceRules.runsInBackground(
                targetCount = current.targets.size,
                changing = current.changing != null,
                intoExistingBatch = current.intoBatchId != null,
            )
        ) {
            startAssistedInBackground(current)
            return
        }
        _step.value = DownloadFlowStep.FindingSources(current.title, 0, current.targets.size)
        launchSession {
            val found = DownloadBatchCoordinator.discoverAll(current.targets) { done ->
                val step = _step.value
                if (step is DownloadFlowStep.FindingSources) _step.value = step.copy(done = done)
            }
            current.candidates = found
            _step.value = assistedStep(current, found, policyProvider())
        }
    }

    /**
     * Assisted, several episodes - "choose when ready" (Phase 9, decided 2026-09-25). The batch
     * exists from the start and discovery runs in [AssistedDiscovery], outside this session: the
     * finding sheet can be closed ("Continue in background") without cancelling anything, and the
     * user is told when the quality can be chosen - see [onDiscoveryFinished].
     */
    private fun startAssistedInBackground(current: Session) {
        val batch = DownloadBatchCoordinator.newBatch(current.title, current.scope, current.targets, current.allowMetered)
            .copy(awaitsQualityChoice = true)
        DownloadsRepository.saveBatch(batch)
        current.choiceBatchId = batch.id
        current.intoBatchId = batch.id
        _step.value = DownloadFlowStep.FindingSources(current.title, 0, current.targets.size, batchId = batch.id)
        AssistedDiscovery.start(batch)
    }

    /** The Assisted sheet for what discovery [found]: one row per resolution, or why there is none. */
    private fun assistedStep(
        current: Session,
        found: Map<DownloadTarget, List<DownloadSourceCandidate>>,
        policy: DownloadPolicy,
    ): DownloadFlowStep {
        val optionsByTarget = current.targets.associateWith { target ->
            DownloadSourceSelector.assistedOptions(
                found[target].orEmpty(),
                DownloadBatchCoordinator.contextFor(target, policy),
            )
        }
        val seasonRows = DownloadSourceSelector.seasonRows(optionsByTarget)
        val single = current.targets.singleOrNull()
        if (seasonRows.isEmpty()) {
            val anyCandidates = found.values.any { it.isNotEmpty() }
            val singleCandidates = single?.let { found[it].orEmpty() }.orEmpty()
            return DownloadFlowStep.NothingToDownload(
                title = current.title,
                kind = if (anyCandidates) DownloadEntryDecisionKind.NOTHING_CACHED else DownloadEntryDecisionKind.NO_SOURCES,
                offersChooseManually = single != null &&
                    singleCandidates.any { !DownloadSourceSelector.isKnownNotCached(it) } &&
                    DownloadSourceSelector.hasUsable(singleCandidates, DownloadBatchCoordinator.contextFor(single, policy)),
            )
        }
        val rows = seasonRows.map { row ->
            val singleOption = single?.let { row.optionByEpisode[it] }
            DownloadResolutionRow(
                height = row.height,
                totalBytes = row.totalBytes,
                unknownSizeCount = row.unknownSizeCount,
                episodeCount = row.optionByEpisode.size,
                missingCount = row.missingEpisodes.size,
                overLimit = row.optionByEpisode.values.any { it.overLimit },
                detail = singleOption?.candidate?.let(::sourceDetail),
            )
        }
        return DownloadFlowStep.ChooseResolution(
            title = current.title,
            scope = current.scope,
            targetCount = current.targets.size,
            rows = rows,
            preselectedHeight = DownloadFlowRules.preselectedHeight(rows.map { it.height }, policy.preferredResolution),
            offersChooseManually = single != null,
        )
    }

    // --- Assisted "choose when ready" ----------------------------------------------------------

    /**
     * "Choose quality" - from the Downloads row, the in-app prompt or the notification. Opens the
     * Assisted sheet with the real totals once the sources are found; while they are still being
     * found (or found again after a process death) it shows that progress and moves on by itself.
     */
    fun chooseQuality(batchId: String) {
        val batch = DownloadsRepository.batches.value.firstOrNull { it.id == batchId } ?: return
        if (!batch.awaitsQualityChoice) return
        val entries = batch.entries.filter {
            it.state == DownloadBatchEntryState.DISCOVERING || it.state == DownloadBatchEntryState.AWAITING_CHOICE
        }
        if (entries.isEmpty()) return
        job?.cancel()
        val current = Session(
            title = DownloadBatchCoordinator.titleRefOf(batch),
            mode = DownloadMode.ASSISTED,
            scope = batch.scope,
            meta = null,
            targets = entries.map { DownloadBatchCoordinator.targetOf(batch, it) },
            allowMetered = batch.allowMeteredNetwork,
            intoBatchId = batch.id,
            choiceBatchId = batch.id,
        )
        session = current
        val found = AssistedDiscovery.candidates(batchId)
        if (found == null) {
            if (!AssistedDiscovery.isRunning(batchId)) AssistedDiscovery.start(batch, refresh = true)
            _step.value = DownloadFlowStep.FindingSources(
                title = current.title,
                done = entries.count { it.state == DownloadBatchEntryState.AWAITING_CHOICE },
                total = entries.size,
                batchId = batchId,
                refreshing = batchId in AssistedDiscovery.refreshing.value,
                chosenHeight = batch.earlyResolutionHeight,
            )
            return
        }
        if (batch.earlyResolutionHeight != null) {
            // Chosen early and the sources are in: it is being started, not chosen again.
            session = null
            _step.value = DownloadFlowStep.Idle
            return
        }
        markAnnounced(batchId)
        DownloadsLiveStatusPlatform.clearChoice(batchId)
        _step.value = assistedStep(current, current.targets.associateWith { found[it.entryId].orEmpty() }, policyProvider())
    }

    /** Remove on a background Assisted batch: stop looking, forget what was found, drop the batch. */
    fun removeChoiceBatch(batchId: String) {
        AssistedDiscovery.cancel(batchId)
        DownloadsLiveStatusPlatform.clearChoice(batchId)
        DownloadsRepository.removeBatch(batchId)
        val step = _step.value
        if (session?.choiceBatchId == batchId || (step is DownloadFlowStep.FindingSources && step.batchId == batchId)) {
            session = null
            _step.value = DownloadFlowStep.Idle
        }
    }

    internal fun onDiscoveryProgress(batchId: String, done: Int, total: Int) {
        _step.update { step ->
            if (step is DownloadFlowStep.FindingSources && step.batchId == batchId) step.copy(done = done, total = total) else step
        }
    }

    /**
     * Discovery for a background batch is done. If the finding sheet is still open on it, straight
     * to the quality choice; otherwise the user is told - in the app when it is on screen, by a
     * system notification when it is not. Never both, and never "Needs you": choosing a quality is
     * the Assisted flow working as intended. Only when nothing at all can be downloaded do the
     * entries become "Needs you" cards (nothing cached / no sources), as Automatic's would.
     */
    internal fun onDiscoveryFinished(batchId: String, refreshed: Boolean) {
        val batch = DownloadsRepository.batches.value.firstOrNull { it.id == batchId } ?: return
        if (!batch.awaitsQualityChoice) return
        val found = AssistedDiscovery.candidates(batchId) ?: return
        val step = _step.value
        // The finding sheet on this batch, or its "Choose now" estimates: either way the user is
        // looking at it, and the exact sheet replaces what they see.
        val sheetShowsBatch = (step is DownloadFlowStep.FindingSources && step.batchId == batchId) ||
            (step is DownloadFlowStep.ChooseResolution && step.estimated && session?.choiceBatchId == batchId)
        val title = DownloadBatchCoordinator.titleRefOf(batch)
        val policy = policyProvider()
        val targets = batch.entries
            .filter { it.state == DownloadBatchEntryState.AWAITING_CHOICE }
            .map { DownloadBatchCoordinator.targetOf(batch, it) }
        val early = batch.earlyResolutionHeight
        if (early != null) {
            val choosingThis = session?.choiceBatchId == batchId
            if (sheetShowsBatch || choosingThis) {
                session = null
                _step.value = DownloadFlowStep.Idle
            }
            applyEarlyChoice(batch, targets, found, early, policy)
            return
        }
        val probe = Session(title, DownloadMode.ASSISTED, batch.scope, meta = null, targets = targets)
        val sheet = assistedStep(probe, targets.associateWith { found[it.entryId].orEmpty() }, policy)
        val season = AssistedChoiceRules.seasonOf(batch)

        if (sheet is DownloadFlowStep.NothingToDownload) {
            targets.forEach { target ->
                val candidates = found[target.entryId].orEmpty()
                val reason = if (candidates.isEmpty()) DownloadDecisionReason.NoSources else DownloadDecisionReason.NothingCached
                DownloadsRepository.updateBatchEntry(
                    batchId,
                    DownloadBatchCoordinator.entryFor(
                        target,
                        DownloadDecision.NeedsDecision(reason),
                        candidates,
                        DownloadBatchCoordinator.contextFor(target, policy),
                    ),
                )
            }
            DownloadsRepository.updateBatch(batchId) { it.copy(awaitsQualityChoice = false) }
            AssistedDiscovery.forget(batchId)
            if (sheetShowsBatch) {
                session = null
                _step.value = DownloadFlowStep.Idle
            }
            if (isAppInForeground()) {
                notices.needsAttention()
            } else {
                postChoiceNotification(DownloadChoiceNotice(batchId, title.title, season, ready = false))
            }
            return
        }

        val announcement = AssistedChoiceRules.announcement(
            sheetShowsBatch = sheetShowsBatch,
            alreadyAnnounced = batch.choiceAnnouncedAtEpochMs != null,
            appInForeground = isAppInForeground(),
        )
        DownloadDiagnostics.note(
            "assisted_ready",
            "batch=${batchId.takeLast(6)} entries=${targets.size} refreshed=$refreshed announce=$announcement",
        )
        when (announcement) {
            AssistedChoiceRules.Announcement.OPEN_SHEET -> chooseQuality(batchId)
            AssistedChoiceRules.Announcement.IN_APP -> {
                markAnnounced(batchId)
                notices.qualityReady(title.title, season) { chooseQuality(batchId) }
            }
            AssistedChoiceRules.Announcement.SYSTEM -> {
                markAnnounced(batchId)
                postChoiceNotification(DownloadChoiceNotice(batchId, title.title, season, ready = true))
            }
            AssistedChoiceRules.Announcement.NONE -> Unit
        }
    }

    /**
     * "Choose now" on the finding sheet: the Assisted sheet before the sources are found, every row
     * an estimate from the episodes' runtimes and the size level. The exact sheet stays the default -
     * this is only for not waiting.
     */
    fun chooseNow() {
        val step = _step.value as? DownloadFlowStep.FindingSources ?: return
        val batchId = step.batchId ?: return
        val batch = DownloadsRepository.batches.value.firstOrNull { it.id == batchId } ?: return
        if (!batch.awaitsQualityChoice) return
        val current = session?.takeIf { it.choiceBatchId == batchId } ?: Session(
            title = DownloadBatchCoordinator.titleRefOf(batch),
            mode = DownloadMode.ASSISTED,
            scope = batch.scope,
            meta = null,
            targets = batch.entries
                .filter { it.state == DownloadBatchEntryState.DISCOVERING || it.state == DownloadBatchEntryState.AWAITING_CHOICE }
                .map { DownloadBatchCoordinator.targetOf(batch, it) },
            allowMetered = batch.allowMeteredNetwork,
            intoBatchId = batch.id,
            choiceBatchId = batch.id,
        ).also { session = it }
        val policy = policyProvider()
        val runtimes = current.targets.map { it.runtimeMinutes }
        val rows = DownloadFlowRules.earlyChoiceHeights.map { height ->
            DownloadResolutionRow(
                height = height,
                totalBytes = 0L,
                unknownSizeCount = 0,
                episodeCount = current.targets.size,
                missingCount = 0,
                overLimit = false,
                detail = null,
                estimate = DownloadSizeLevels.estimateBytes(policy.sizeLevel, height, runtimes),
            )
        }
        _step.value = DownloadFlowStep.ChooseResolution(
            title = current.title,
            scope = current.scope,
            targetCount = current.targets.size,
            rows = rows,
            preselectedHeight = batch.earlyResolutionHeight ?: DownloadFlowRules.earlyPreselectedHeight(policy.preferredResolution),
            offersChooseManually = false,
            estimated = true,
        )
    }

    /** Records the early choice; if the sources arrived while the sheet was open, applies it now. */
    private fun chooseEarly(current: Session, batchId: String, height: Int) {
        session = null
        _step.value = DownloadFlowStep.Idle
        DownloadsRepository.updateBatch(batchId) { it.copy(earlyResolutionHeight = height) }
        DownloadDiagnostics.note("assisted_early_choice", "batch=${batchId.takeLast(6)} height=$height")
        notices.qualityChosenEarly(height)
        if (AssistedDiscovery.candidates(batchId) != null) {
            // Discovery ended while the estimate sheet was up: its result already went by.
            onDiscoveryFinished(batchId, refreshed = false)
        }
    }

    /**
     * The sources are in for a batch chosen early. Each entry is decided as Automatic would decide
     * it with the chosen resolution as the preference: inside the size rule, the user's fallback
     * when the resolution is missing (Ask -> the grouped Needs you card), the over-limit decision,
     * nothing cached / no sources. Then the usual free-space check. Nothing asks for the
     * resolution again.
     */
    private fun applyEarlyChoice(
        batch: DownloadBatch,
        targets: List<DownloadTarget>,
        found: Map<String, List<DownloadSourceCandidate>>,
        height: Int,
        policy: DownloadPolicy,
    ) {
        val batchId = batch.id
        val chosen = policy.copy(preferredResolution = DownloadFlowRules.preferenceForHeight(height))
        val current = Session(
            title = DownloadBatchCoordinator.titleRefOf(batch),
            mode = DownloadMode.ASSISTED,
            scope = batch.scope,
            meta = null,
            targets = targets,
            allowMetered = batch.allowMeteredNetwork,
            intoBatchId = batchId,
        )
        // Claimed at once, so a second "finished" (a refresh racing this) cannot apply it twice.
        DownloadsRepository.updateBatch(batchId) { it.copy(awaitsQualityChoice = false) }
        AssistedDiscovery.forget(batchId)
        DownloadsLiveStatusPlatform.clearChoice(batchId)
        scope.launch {
            val entries = targets.map { target ->
                DownloadBatchCoordinator.automaticEntry(
                    target,
                    found[target.entryId].orEmpty(),
                    DownloadBatchCoordinator.contextFor(target, chosen),
                )
            }
            entries.forEach { DownloadsRepository.updateBatchEntry(batchId, it) }
            val ready = entries.count { it.state == DownloadBatchEntryState.READY }
            DownloadDiagnostics.note(
                "assisted_early_applied",
                "batch=${batchId.takeLast(6)} height=$height entries=${entries.size} ready=$ready",
            )
            queueOrHold(current, batchId, entries)
            if (isAppInForeground()) {
                announceMany(entries)
            } else if (ready == 0) {
                // In the background the summary notification shows what started; only a batch
                // where nothing could start needs telling.
                postChoiceNotification(
                    DownloadChoiceNotice(batchId, current.title.title, AssistedChoiceRules.seasonOf(batch), ready = false),
                )
            }
        }
    }

    private fun markAnnounced(batchId: String) {
        DownloadsRepository.updateBatch(batchId) { batch ->
            if (batch.choiceAnnouncedAtEpochMs != null) batch else batch.copy(choiceAnnouncedAtEpochMs = DownloadsClock.nowEpochMs())
        }
    }

    private fun startManual(current: Session) {
        val single = current.targets.singleOrNull()
        if (single != null && (current.scope is DownloadScope.Movie || current.scope is DownloadScope.Episode)) {
            session = null
            _step.value = DownloadFlowStep.Idle
            _events.tryEmit(DownloadFlowEvent.OpenManualSourceList(current.title, single))
            return
        }
        val batch = DownloadBatchCoordinator.newBatch(current.title, current.scope, current.targets, current.allowMetered)
            .let { it.copy(entries = current.targets.map(DownloadBatchCoordinator::manualPickEntry)) }
        DownloadsRepository.saveBatch(batch)
        session = null
        _step.value = DownloadFlowStep.Idle
        _events.tryEmit(DownloadFlowEvent.OpenChooseSources(batch.id))
    }

    /** Writes Assisted entries: into the Change / pick-the-rest batch, or a new one. */
    private fun writeEntries(current: Session, entries: List<DownloadBatchEntry>): String {
        val changing = current.changing
        val existingBatchId = current.intoBatchId ?: changing?.let { item ->
            DownloadsRepository.batches.value.firstOrNull { batch ->
                batch.parentMetaId == item.parentMetaId && batch.entries.any { it.videoId == item.videoId }
            }?.id
        }
        if (existingBatchId != null &&
            DownloadsRepository.batches.value.any { it.id == existingBatchId }
        ) {
            entries.forEach { DownloadsRepository.updateBatchEntry(existingBatchId, it) }
            val batch = DownloadsRepository.batches.value.first { it.id == existingBatchId }
            // A Change of an item whose batch never had an entry for it (it was enqueued from a list).
            val missing = entries.filter { entry -> batch.entries.none { it.id == entry.id } }
            if (missing.isNotEmpty()) DownloadsRepository.saveBatch(batch.copy(entries = batch.entries + missing))
            return existingBatchId
        }
        val batch = DownloadBatchCoordinator.newBatch(current.title, current.scope, current.targets, current.allowMetered)
            .copy(entries = entries)
        DownloadsRepository.saveBatch(batch)
        return batch.id
    }

    /** Queues what is ready, unless it does not fit - then the free-space warning holds it. */
    private fun queueOrHold(current: Session, batchId: String, entries: List<DownloadBatchEntry>) {
        val ready = entries.filter { it.state == DownloadBatchEntryState.READY }
        // A Change replaces a file already counted in used space; the transfer's own check covers it.
        val verdict = if (current.changing != null) {
            DownloadFlowRules.FreeSpaceVerdict.Fits
        } else {
            DownloadFlowRules.freeSpace(ready.map { it.expectedBytes() }, freeBytesProvider())
        }
        when (verdict) {
            DownloadFlowRules.FreeSpaceVerdict.Fits -> {
                DownloadsRepository.queueBatch(batchId, approveUnknownSizes = false, onlyEntryIds = ready.mapTo(mutableSetOf()) { it.id })
                if (session === current) {
                    session = null
                    if (_step.value !is DownloadFlowStep.AskMobileData) _step.value = DownloadFlowStep.Idle
                }
            }
            is DownloadFlowRules.FreeSpaceVerdict.TooBig -> {
                current.heldBatchId = batchId
                current.heldEntryIds = ready.map { it.id }
                session = current
                _step.value = DownloadFlowStep.NotEnoughSpace(
                    title = current.title,
                    neededBytes = verdict.neededBytes,
                    freeBytes = verdict.freeBytes,
                    fitCount = verdict.fitCount,
                    totalCount = ready.size,
                )
            }
        }
    }

    private fun announceSingle(current: Session, entry: DownloadBatchEntry, onChange: ((DownloadItem) -> Unit)?) {
        if (_step.value is DownloadFlowStep.NotEnoughSpace) return
        if (entry.state != DownloadBatchEntryState.READY) {
            notices.needsAttention()
            return
        }
        val item = DownloadsRepository.uiState.value.items.firstOrNull {
            it.parentMetaId == current.title.parentMetaId &&
                it.videoId == entry.videoId &&
                it.seasonNumber == entry.season &&
                it.episodeNumber == entry.episode
        }
        val selection = entry.selection as? SourceSelectionResult.Selected
        notices.started(
            item = item,
            height = selection?.facts?.resolution?.height,
            bytes = selection?.facts?.sizeBytes,
            onChange = if (item != null && onChange != null) ({ onChange(item) }) else null,
        )
    }

    private fun announceMany(entries: List<DownloadBatchEntry>) {
        if (_step.value is DownloadFlowStep.NotEnoughSpace) return
        val started = entries.count { it.state == DownloadBatchEntryState.READY }
        notices.startedMany(started, needAttention = entries.size - started)
    }

    private fun dropEntries(batchId: String, keep: (DownloadBatchEntry) -> Boolean) {
        val batch = DownloadsRepository.batches.value.firstOrNull { it.id == batchId } ?: return
        val kept = batch.entries.filter(keep)
        if (kept.isEmpty()) {
            DownloadsRepository.removeBatch(batchId)
        } else {
            DownloadsRepository.saveBatch(batch.copy(entries = kept))
        }
    }

    private fun launchSession(block: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch { block() }
    }

    private fun sourceDetail(candidate: DownloadSourceCandidate): String {
        val facts = candidate.facts
        return listOfNotNull(
            facts.codec,
            facts.dynamicRange.firstOrNull { it != "SDR" },
            candidate.stream.addonName.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
    }

    internal fun resetForTests(dispatcher: CoroutineDispatcher) {
        AssistedDiscovery.resetForTests(dispatcher)
        scope = CoroutineScope(SupervisorJob() + dispatcher)
        job?.cancel()
        job = null
        session = null
        sessionMobileDataAnswer = null
        _step.value = DownloadFlowStep.Idle
    }
}

internal fun DownloadScope.requestScope(): DownloadRequestScope = when (this) {
    DownloadScope.Movie, is DownloadScope.Episode -> DownloadRequestScope.SINGLE
    is DownloadScope.Season, is DownloadScope.SeasonUnwatched -> DownloadRequestScope.SEASON
    is DownloadScope.SelectedSeasons -> DownloadRequestScope.WHOLE_SHOW
}

private fun DownloadEntryRoute.startsWithSeasons(): Boolean =
    this == DownloadEntryRoute.SEASON_CHOOSER_THEN_START ||
        this == DownloadEntryRoute.SEASON_CHOOSER_THEN_ASSISTED ||
        this == DownloadEntryRoute.SEASON_CHOOSER_THEN_MANUAL

private fun DownloadBatchEntry.expectedBytes(): Long? = when (val s = selection) {
    is SourceSelectionResult.Selected -> s.facts.sizeBytes
    is SourceSelectionResult.ApprovalNeeded -> s.facts.sizeBytes
    else -> null
}
