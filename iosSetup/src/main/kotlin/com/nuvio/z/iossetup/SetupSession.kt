package com.nuvio.z.iossetup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Progress of the background download-and-install of iloader. The user never has to start it. */
sealed interface ToolsState {
    data object Idle : ToolsState
    data class Working(val downloaded: Long, val total: Long?) : ToolsState
    data object Ready : ToolsState
    data class Failed(val result: OperationResult) : ToolsState
}

/**
 * The live state of one setup run. Everything the UI shows is derived from [results], which are
 * recomputed from what the computer can actually see ([computer], [helperStatus]) plus the few answers
 * only the user can give ([progress].confirmed). [tick] is called repeatedly from a background loop.
 *
 * All blocking work happens in [tick] and the explicit actions, so callers run them off the UI thread.
 */
class SetupSession(
    val ops: PlatformSetupOps,
    val helper: DeviceHelper,
    private val store: ProgressStore?,
    val diagnostics: Diagnostics,
    initial: SetupProgress = SetupProgress(),
) {
    var progress by mutableStateOf(initial); private set
    var computer by mutableStateOf<ComputerCheck?>(null); private set
    var helperStatus by mutableStateOf<HelperStatus?>(null); private set
    /** False until the first device probe finished, so the UI says "checking" instead of guessing. */
    var deviceProbed by mutableStateOf(false); private set
    var tools by mutableStateOf<ToolsState>(ToolsState.Idle); private set
    var pairingRunning by mutableStateOf(false); private set
    var pairingResult by mutableStateOf<PlacePairingResult?>(null); private set
    var lastOperation by mutableStateOf<OperationResult?>(null); private set
    var busyLabel by mutableStateOf<String?>(null); private set

    private var pairingAutoTried = false
    private var revealAutoTried = false

    /** Probes are skipped in manual mode and when no helper is bundled; the user confirms instead. */
    private val probing: Boolean get() = helper.available && !progress.manualMode

    val results: List<RequirementResult>
        get() = Requirements.evaluate(WorldSnapshot(computer, if (probing) helperStatus else null), progress.channel, progress.confirmed)

    val focus: RequirementResult? get() = Requirements.focus(results)

    /** Why the phone cannot be read right now, in plain language. Null when all is well or unprobed. */
    val error: SetupError? get() = if (!probing || !deviceProbed) null else SetupError.from(helperStatus)

    val isMac: Boolean get() = ops.isMac

    // ---- background loop ----------------------------------------------------------------------------

    fun tick() {
        if (progress.setupCompleted && !progress.repairMode) return
        if (computer == null) checkComputer()
        if (computer?.canContinue == true) ensureTools()
        if (probing) probeDevice() else deviceProbed = true
        autoActions()
        reconcileAndComplete()
    }

    fun checkComputer() {
        computer = ops.checkComputer()
        diagnostics.requirements(results)
    }

    private fun ensureTools() {
        if (tools is ToolsState.Working || tools is ToolsState.Ready || tools is ToolsState.Failed) return
        if (ops.findIloader() != null) { tools = ToolsState.Ready; return }
        tools = ToolsState.Working(0, null)
        val result = ops.installIloader { done, total -> tools = ToolsState.Working(done, total) }
        tools = if (result.success) ToolsState.Ready else ToolsState.Failed(result)
    }

    /** User pressed "Try again" after the automatic download failed. */
    fun retryTools() { tools = ToolsState.Idle }

    private fun probeDevice() {
        helperStatus = helper.status()
        deviceProbed = true
        if (helperStatus?.device == null) { pairingAutoTried = false; revealAutoTried = false }
    }

    /** Things the computer can do for the user the moment they become possible, once each. */
    private fun autoActions() {
        val byRequirement = results.associateBy { it.requirement }
        if (probing && byRequirement[Requirement.PAIRING]?.status == RequirementStatus.MISSING && !pairingAutoTried && !pairingRunning) {
            pairingAutoTried = true
            placePairing()
        }
        if (probing && byRequirement[Requirement.DEVELOPER_MODE]?.status == RequirementStatus.MISSING && !revealAutoTried) {
            revealAutoTried = true
            helper.revealDeveloperMode()
        }
    }

    /** Recomputes the saved confirmations against reality and flips to "completed" once nothing is left. */
    private fun reconcileAndComplete() {
        val current = results
        val kept = Requirements.reconcile(progress.confirmed, current)
        var next = if (kept != progress.confirmed) progress.copy(confirmed = kept) else progress
        if (current.all { it.done } && !next.setupCompleted) next = next.copy(setupCompleted = true, repairMode = false)
        if (next != progress) update { next }
        diagnostics.requirements(current)
    }

    // ---- explicit user actions ------------------------------------------------------------------------

    /** Builds and places SideStore's pairing file now (automatically, from Repair, or on Try again). */
    fun placePairing(): PlacePairingResult {
        pairingRunning = true
        val result = try { helper.placePairing() } finally { pairingRunning = false }
        pairingResult = result
        if (result.ok) probeDevice()
        return result
    }

    fun retryPairing() { pairingAutoTried = false; pairingResult = null }

    fun confirm(requirement: Requirement, value: Boolean = true) = update { it.confirm(requirement, value) }

    fun setChannel(channel: SetupChannel, warningAccepted: Boolean = false): Boolean {
        if (channel == SetupChannel.DEVELOPER && !warningAccepted) return false
        update { it.copy(channel = channel, developerWarningAccepted = warningAccepted, setupCompleted = false) }
        return true
    }

    fun setManualMode(enabled: Boolean) {
        update { it.copy(manualMode = enabled, setupCompleted = false) }
        helperStatus = null; deviceProbed = !enabled
    }

    /** Re-opens setup from the completion page to repair pairing or recheck the phone. */
    fun enterRepair() = update { it.copy(repairMode = true, setupCompleted = false) }

    fun leaveRepair() = update { it.copy(repairMode = false) }

    fun startOver() {
        store?.clear()
        progress = SetupProgress(channel = progress.channel, developerWarningAccepted = progress.developerWarningAccepted)
        computer = null; helperStatus = null; deviceProbed = false; tools = ToolsState.Idle
        pairingAutoTried = false; revealAutoTried = false; pairingResult = null; lastOperation = null
    }

    /** Removes iloader if (and only if) this app installed it. Offered once setup has finished. */
    fun removeInstalledTools(): OperationResult = ops.iloaderBootstrap.uninstall().also { lastOperation = it; if (it.success) tools = ToolsState.Idle }

    fun openIloader(): OperationResult = ops.openIloader().also { lastOperation = it }

    fun installAppleSupport(): OperationResult {
        busyLabel = "Installing Apple device support…"
        return try { ops.installAppleDeviceSupport().also { lastOperation = it; checkComputer() } } finally { busyLabel = null }
    }

    fun openServices(): OperationResult = ops.openAppleServiceManager().also { lastOperation = it }

    private fun update(change: (SetupProgress) -> SetupProgress) {
        progress = change(progress)
        runCatching { store?.saveProgress(progress) }
    }
}
