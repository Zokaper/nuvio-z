package com.nuvio.z.iossetup

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class Diagnostics(private val path: Path = ProgressStore.defaultProgressPath().resolveSibling("diagnostics.log")) {
    init {
        log("Nuvio Z iOS Setup 1.0.2 started")
        log("OS=${System.getProperty("os.name")} ${System.getProperty("os.version")}; arch=${System.getProperty("os.arch")}")
    }

    fun step(step: SetupStep) = log("wizard_step=${step.name}")
    fun deviceDetected(value: Boolean) = log("device_detected=$value")
    fun iloader(path: Path?, version: String? = null) = log("iloader_path=${path ?: "not_found"} iloader_version=${version?.ifBlank { "unknown" } ?: "unknown"}")
    fun computerCheck(value: ComputerCheck) = log("computer_check os=${value.supportedOs.state} internet=${value.internet.state} apple=${value.appleSupport.state} service=${value.appleService?.state ?: "n/a"}")
    fun appleProbes(registryOrDriver: Boolean, serviceInstalled: Boolean, serviceRunning: Boolean, wingetInstalled: Boolean) =
        log("apple_probes registry_or_driver=$registryOrDriver service_installed=$serviceInstalled service_running=$serviceRunning winget_installed=$wingetInstalled")
    fun operation(name: String, result: OperationResult) = log("operation=${name.substringAfterLast('/').substringAfterLast('\\')} success=${result.success} exit=${result.exitCode ?: "n/a"} message=${result.message}")

    fun report(currentStep: SetupStep): String {
        val body = runCatching { Files.readString(path) }.getOrDefault("No diagnostic events recorded.")
        return "Nuvio Z iOS Setup diagnostics\nCurrent step: ${currentStep.name}\nProgress file: ${path.parent.resolve("setup-state.json")}\n\n$body"
    }

    private fun log(raw: String) {
        val safe = redact(raw)
        runCatching {
            Files.createDirectories(path.parent)
            Files.writeString(path, "${Instant.now()} $safe\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
        }
    }

    companion object {
        private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
        private val secrets = Regex("(?i)(password|token|secret|2fa|authorization)[=: ]+[^\\s,;]+")
        fun redact(value: String): String = value.replace(email, "[redacted-account]").replace(secrets) { "${it.groupValues[1]}=[redacted]" }
    }
}
