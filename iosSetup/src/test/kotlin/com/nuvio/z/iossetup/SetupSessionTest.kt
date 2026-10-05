package com.nuvio.z.iossetup

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeOps(var computerOk: Boolean = true, var iloaderFound: Boolean = false, var installOk: Boolean = true) : PlatformSetupOps {
    override val platformName = "Test"
    override val isMac = false
    var computerChecks = 0
    var installs = 0
    override fun checkComputer(): ComputerCheck {
        computerChecks++
        val os = CheckResult("os", if (computerOk) CheckState.PASS else CheckState.FAIL)
        return ComputerCheck(os, CheckResult("net", CheckState.PASS), CheckResult("apple", CheckState.PASS), null)
    }
    override fun installAppleDeviceSupport() = OperationResult(true, "ok")
    override fun isDeviceConnected() = false
    override fun isDeviceTransportReady() = false
    override fun openAppleServiceManager() = OperationResult(true, "ok")
    override fun findIloader(): Path? = if (iloaderFound) Path.of("iloader.exe") else null
    override fun installIloader(onProgress: (Long, Long?) -> Unit): OperationResult {
        installs++
        onProgress(10, 100)
        if (installOk) iloaderFound = true
        return OperationResult(installOk, if (installOk) "ready" else "failed")
    }
    override fun openIloader() = OperationResult(true, "opened")
    override val iloaderBootstrap = IloaderBootstrap(Prereqs.embedded(), false, Files.createTempDirectory("fake-tools"), systemCandidates = emptyList())
}

class SetupSessionTest {
    private val ready = javaClass.getResource("/helper-status-ready.json")!!.readText().trim()
    /** The fixture phone has Nuvio Z Debug installed, so a finishing run uses the Developer channel. */
    private val debugRun = SetupProgress(channel = SetupChannel.DEVELOPER, developerWarningAccepted = true)
    private val humanSteps = listOf(Requirement.PROFILE_TRUST, Requirement.SIDESTORE_READY, Requirement.SOURCE_ADDED)

    /** A scripted helper: [status] is mutable so a test can move the phone through states. */
    private inner class Script(var status: String = ready, var pairOk: Boolean = true) {
        val calls = mutableListOf<String>()
        val helper = DeviceHelper(Path.of("helper.exe"), runner = { args, _ ->
            val command = args[1]; calls += command
            when (command) {
                "status" -> HelperRun(0, status)
                "place-pairing" -> HelperRun(0, if (pairOk) """{"ok":true}""" else """{"ok":false,"stage":"write","error":"x"}""")
                else -> HelperRun(0, """{"ok":true}""")
            }
        })
        fun count(command: String) = calls.count { it == command }
    }

    private fun session(script: Script?, ops: FakeOps = FakeOps(), store: ProgressStore? = null, initial: SetupProgress = SetupProgress()) =
        SetupSession(ops, script?.helper ?: DeviceHelper(null), store, Diagnostics(Files.createTempFile("diag", ".log")), initial)

    private fun SetupSession.status(r: Requirement) = results.first { it.requirement == r }.status

    @Test fun firstTickChecksComputerInstallsToolsAndProbesThePhone() {
        val ops = FakeOps(); val script = Script()
        val s = session(script, ops)
        s.tick()
        assertEquals(1, ops.computerChecks); assertEquals(1, ops.installs)
        assertEquals(ToolsState.Ready, s.tools)
        assertTrue(s.deviceProbed)
        assertEquals(RequirementStatus.SATISFIED, s.status(Requirement.SIDESTORE))
        assertEquals(humanSteps.first(), s.focus?.requirement)
        s.tick(); s.tick()
        assertEquals(1, ops.computerChecks, "computer is checked once, not on every poll")
        assertEquals(1, ops.installs, "iloader is fetched once")
    }

    @Test fun toolsAreNotFetchedWhenTheComputerIsUnsupported() {
        val ops = FakeOps(computerOk = false)
        session(Script(), ops).also { it.tick() }
        assertEquals(0, ops.installs)
    }

    @Test fun anExistingIloaderMeansNoDownload() {
        val ops = FakeOps(iloaderFound = true)
        session(Script(), ops).also { it.tick(); assertEquals(ToolsState.Ready, it.tools) }
        assertEquals(0, ops.installs)
    }

    @Test fun failedToolDownloadIsReportedAndOnlyRetriedOnRequest() {
        val ops = FakeOps(installOk = false)
        val s = session(Script(), ops)
        s.tick(); s.tick()
        assertTrue(s.tools is ToolsState.Failed)
        assertEquals(1, ops.installs)
        ops.installOk = true
        s.retryTools(); s.tick()
        assertEquals(ToolsState.Ready, s.tools)
    }

    @Test fun missingPairingFileIsPlacedAutomaticallyExactlyOnce() {
        val script = Script(status = ready.replace("\"pairingFile\":\"present\"", "\"pairingFile\":\"absent\"")).also { it.pairOk = false }
        val s = session(script)
        s.tick(); s.tick(); s.tick()
        assertEquals(1, script.count("place-pairing"), "a failed placement must not be hammered every 3 seconds")
        assertFalse(s.pairingResult!!.ok)
        s.retryPairing(); s.tick()
        assertEquals(2, script.count("place-pairing"))
    }

    @Test fun successfulPlacementReprobesSoTheTickMarksPairingDone() {
        val script = Script(status = ready.replace("\"pairingFile\":\"present\"", "\"pairingFile\":\"absent\""))
        val s = session(script)
        s.tick()
        assertEquals(RequirementStatus.MISSING, s.status(Requirement.PAIRING), "the stub phone still reports absent")
        script.status = ready
        s.placePairing()
        assertEquals(RequirementStatus.SATISFIED, s.status(Requirement.PAIRING))
    }

    @Test fun pairingIsNotAttemptedBeforeSideStoreExists() {
        val noSideStore = ready.replace(Regex("\"sidestore\":\\{[^}]*\\}"), "\"sidestore\":null").replace("\"pairingFile\":\"present\"", "\"pairingFile\":\"sidestoreMissing\"")
        val script = Script(status = noSideStore)
        session(script).tick()
        assertEquals(0, script.count("place-pairing"))
    }

    @Test fun developerModeSwitchIsRevealedOnceWhenItIsOff() {
        val script = Script(status = ready.replace("\"developerMode\":true", "\"developerMode\":false"))
        val s = session(script)
        s.tick(); s.tick()
        assertEquals(1, script.count("reveal-developer-mode"))
        assertEquals(RequirementStatus.MISSING, s.status(Requirement.DEVELOPER_MODE))
    }

    @Test fun replugAfterAnUnplugMayPlaceAndRevealAgain() {
        val absent = ready.replace("\"pairingFile\":\"present\"", "\"pairingFile\":\"absent\"")
        val none = """{"protocol":1,"helper":"0.1.0","idevice":"0.1.68","usbmuxd":{"reachable":true,"usbDevices":0},"device":null,"errors":{}}"""
        val script = Script(status = absent)
        val s = session(script)
        s.tick(); assertEquals(1, script.count("place-pairing"))
        script.status = none; s.tick()
        script.status = absent; s.tick()
        assertEquals(2, script.count("place-pairing"))
    }

    @Test fun humanAnswersCompleteSetupAndPersist() {
        val dir = Files.createTempDirectory("progress")
        val store = ProgressStore(dir.resolve("setup-state.json"))
        val s = session(Script(), store = store, initial = debugRun)
        s.tick(); assertFalse(s.progress.setupCompleted)
        humanSteps.forEach { s.confirm(it) }
        s.tick()
        assertTrue(s.progress.setupCompleted)
        assertNull(s.focus)
        assertEquals(s.progress, store.loadProgress())
    }

    @Test fun resumeRebuildsEverythingFromThePhoneAndKeepsOnlyHumanAnswers() {
        val dir = Files.createTempDirectory("resume")
        val store = ProgressStore(dir.resolve("setup-state.json"))
        session(Script(), store = store, initial = debugRun).also { it.tick(); it.confirm(Requirement.PROFILE_TRUST) }
        val resumed = session(Script(), store = store, initial = store.loadProgress()!!)
        resumed.tick()
        assertEquals(RequirementStatus.SATISFIED, resumed.status(Requirement.PROFILE_TRUST))
        assertEquals(Requirement.SIDESTORE_READY, resumed.focus?.requirement)
    }

    @Test fun deletedSideStoreInvalidatesTheHumanAnswers() {
        val script = Script()
        val s = session(script)
        s.tick(); humanSteps.take(2).forEach { s.confirm(it) }
        script.status = ready.replace(Regex("\"sidestore\":\\{[^}]*\\}"), "\"sidestore\":null").replace("\"pairingFile\":\"present\"", "\"pairingFile\":\"sidestoreMissing\"")
        s.tick()
        assertTrue(s.progress.confirmed.none { it.manualOnly })
        assertEquals(Requirement.SIDESTORE, s.focus?.requirement)
    }

    @Test fun withoutAHelperEveryPhoneStepFallsBackToTheUserAndStillCompletes() {
        val s = session(script = null)
        s.tick()
        assertTrue(s.deviceProbed)
        assertNull(s.error, "no helper is a mode, not an error banner")
        val phone = Requirement.entries.filter { it != Requirement.COMPUTER }
        assertEquals(Requirement.DEVICE, s.focus?.requirement)
        phone.forEach { assertTrue(s.status(it) != RequirementStatus.MISSING, "${it.name} must be confirmable, not stuck") }
        phone.forEach { s.confirm(it); s.tick() }
        assertTrue(s.progress.setupCompleted)
    }

    @Test fun manualModeNeverCallsTheHelper() {
        val script = Script()
        val s = session(script, initial = SetupProgress(manualMode = true))
        s.tick(); s.tick()
        assertEquals(0, script.calls.size)
        assertEquals(Requirement.DEVICE, s.focus?.requirement)
    }

    @Test fun errorsSurfaceFromTheHelperReport() {
        val none = """{"protocol":1,"helper":"0.1.0","idevice":"0.1.68","usbmuxd":{"reachable":true,"usbDevices":0},"device":null,"errors":{}}"""
        val s = session(Script(status = none))
        assertNull(s.error)
        s.tick()
        assertEquals(ErrorCode.NO_DEVICE, s.error?.code)
        assertEquals(Requirement.DEVICE, s.focus?.requirement)
    }

    @Test fun aBrokenHelperIsReportedAndFallsBackToConfirmation() {
        val script = Script(status = "garbage")
        val s = session(script)
        s.tick()
        assertEquals(ErrorCode.HELPER_UNAVAILABLE, s.error?.code)
        assertEquals(RequirementStatus.UNKNOWN, s.status(Requirement.DEVICE))
    }

    @Test fun developerChannelNeedsADeliberateWarning() {
        val s = session(Script())
        assertFalse(s.setChannel(SetupChannel.DEVELOPER))
        assertEquals(SetupChannel.STABLE, s.progress.channel)
        assertTrue(s.setChannel(SetupChannel.DEVELOPER, warningAccepted = true))
        assertEquals(SetupChannel.DEVELOPER, s.progress.channel)
    }

    @Test fun completedSetupDoesNotKeepPollingThePhone() {
        val script = Script()
        session(script, initial = SetupProgress(setupCompleted = true)).tick()
        assertEquals(0, script.calls.size)
    }

    @Test fun repairReopensTheBoardAndFinishesAgain() {
        val s = session(Script(), initial = debugRun.copy(setupCompleted = true, confirmed = humanSteps.toSet()))
        s.enterRepair()
        assertFalse(s.progress.setupCompleted)
        s.tick()
        assertTrue(s.progress.setupCompleted); assertFalse(s.progress.repairMode)
    }

    @Test fun startOverClearsSavedProgressButKeepsTheChosenChannel() {
        val dir = Files.createTempDirectory("clear")
        val store = ProgressStore(dir.resolve("setup-state.json"))
        val s = session(Script(), store = store, initial = SetupProgress(channel = SetupChannel.DEVELOPER, developerWarningAccepted = true))
        s.confirm(Requirement.SOURCE_ADDED)
        assertTrue(store.exists())
        s.startOver()
        assertFalse(store.exists())
        assertTrue(s.progress.confirmed.isEmpty())
        assertEquals(SetupChannel.DEVELOPER, s.progress.channel)
    }
}
