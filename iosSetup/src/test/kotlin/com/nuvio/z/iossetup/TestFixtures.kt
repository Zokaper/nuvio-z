package com.nuvio.z.iossetup

import java.nio.file.Files
import java.nio.file.Path

/** A platform layer that answers instantly and records what the session asked of it. */
internal class FakeOps(var computerOk: Boolean = true, var iloaderFound: Boolean = false, var installOk: Boolean = true) : PlatformSetupOps {
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
