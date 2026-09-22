package com.nuvio.z.iossetup

class SetupController(
    initial: SetupState = SetupState(),
    private val store: ProgressStore? = null,
    private val isMac: Boolean = System.getProperty("os.name").lowercase().contains("mac"),
) {
    var state: SetupState = normalize(initial)
        private set

    fun setChannel(channel: SetupChannel, warningAccepted: Boolean = false): Boolean {
        if (channel == SetupChannel.DEVELOPER && !warningAccepted) return false
        state = state.copy(channel = channel, developerWarningAccepted = warningAccepted)
        persist()
        return true
    }

    fun confirmCurrent(confirmed: Boolean) {
        val updated = if (confirmed) state.manualConfirmations + state.currentStep
        else state.manualConfirmations - state.currentStep
        state = state.copy(manualConfirmations = updated)
        persist()
    }

    fun setDeviceOverride(enabled: Boolean) {
        state = state.copy(advancedDeviceOverride = enabled)
        persist()
    }

    fun canAdvance(autoVerified: Boolean = false): Boolean = when (state.currentStep) {
        SetupStep.WELCOME -> true
        SetupStep.COMPUTER_CHECK, SetupStep.APPLE_DEVICE_SUPPORT, SetupStep.CONNECT_IPHONE,
        SetupStep.ILOADER_INSTALL -> autoVerified ||
            (state.currentStep == SetupStep.CONNECT_IPHONE && state.advancedDeviceOverride)
        SetupStep.FINISH -> false
        else -> state.currentStep in state.manualConfirmations
    }

    fun advance(autoVerified: Boolean = false): Boolean {
        if (!canAdvance(autoVerified)) return false
        val current = state.currentStep
        val next = nextStep(current) ?: return false
        state = state.copy(currentStep = next, completedSteps = state.completedSteps + current)
        persist()
        return true
    }

    fun back(): Boolean {
        val previous = previousStep(state.currentStep) ?: return false
        state = state.copy(currentStep = previous, completedSteps = state.completedSteps - previous)
        persist()
        return true
    }

    fun enterRepairPairing() {
        state = state.copy(currentStep = SetupStep.PAIRING, repairMode = true)
        persist()
    }

    fun setRepairMode(enabled: Boolean) {
        state = state.copy(repairMode = enabled)
        persist()
    }

    fun startOver() {
        store?.clear()
        state = SetupState()
    }

    private fun nextStep(step: SetupStep): SetupStep? {
        val steps = activeSteps()
        return steps.getOrNull(steps.indexOf(step) + 1)
    }

    private fun previousStep(step: SetupStep): SetupStep? {
        val steps = activeSteps()
        return steps.getOrNull(steps.indexOf(step) - 1)
    }

    fun activeSteps(): List<SetupStep> = SetupStep.entries.filterNot {
        isMac && it == SetupStep.APPLE_DEVICE_SUPPORT
    }

    private fun normalize(value: SetupState): SetupState = if (isMac && value.currentStep == SetupStep.APPLE_DEVICE_SUPPORT) {
        value.copy(currentStep = SetupStep.CONNECT_IPHONE)
    } else value

    private fun persist() = store?.save(state)
}
