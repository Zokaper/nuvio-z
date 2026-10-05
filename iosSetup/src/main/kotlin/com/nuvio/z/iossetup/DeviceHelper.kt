package com.nuvio.z.iossetup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Raw outcome of one helper invocation. [stdout] is only the first line; the protocol is one JSON line. */
data class HelperRun(val exitCode: Int?, val stdout: String, val timedOut: Boolean = false)

/** Outcome of building and placing SideStore's pairing file. */
data class PlacePairingResult(val ok: Boolean, val stage: String?, val detail: String) {
    val userMessage: String get() = when {
        ok -> "Pairing file placed and verified."
        stage == "sidestore" -> "SideStore is not installed on the iPhone yet."
        stage == "device" || stage == "usbmuxd" -> "The iPhone is not connected."
        stage == "timeout" -> "The iPhone did not answer in time. Unlock it and try again."
        else -> "The pairing file could not be placed."
    }

    companion object {
        fun parse(line: String): PlacePairingResult = runCatching {
            val root = Json.parseToJsonElement(line.trim()).jsonObject
            PlacePairingResult(
                ok = root["ok"]?.jsonPrimitive?.booleanOrNull == true,
                stage = root["stage"]?.jsonPrimitive?.contentOrNull,
                detail = root["error"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }.getOrElse { PlacePairingResult(false, "protocol", "Unreadable helper output.") }
    }
}

/**
 * Runs the bundled Rust helper (`nuvioz-device-helper`). The helper is optional by design: when it
 * cannot be found or run, [status] returns null and the UI falls back to manual confirmations.
 */
class DeviceHelper(
    private val executable: Path?,
    private val diagnostics: Diagnostics? = null,
    private val runner: (List<String>, Long) -> HelperRun = { args, seconds -> runProcess(args, seconds) },
) {
    val available: Boolean get() = executable != null

    fun status(timeoutSeconds: Long = 25): HelperStatus? {
        val exe = executable ?: return null
        val run = runner(listOf(exe.toString(), "status"), timeoutSeconds)
        val parsed = if (run.exitCode == 0) HelperStatus.parse(run.stdout) else null
        diagnostics?.helperStatus(parsed, run)
        return parsed
    }

    fun placePairing(timeoutSeconds: Long = 120): PlacePairingResult {
        val exe = executable ?: return PlacePairingResult(false, "helper", "The device helper is not available.")
        val run = runner(listOf(exe.toString(), "place-pairing"), timeoutSeconds)
        val result = when {
            run.timedOut -> PlacePairingResult(false, "timeout", "")
            run.exitCode != 0 -> PlacePairingResult(false, "helper", "exit ${run.exitCode}")
            else -> PlacePairingResult.parse(run.stdout)
        }
        diagnostics?.pairingPlaced(result)
        return result
    }

    /** Asks iOS to show the Developer Mode switch in Settings. Reuses the ok/stage result shape. */
    fun revealDeveloperMode(timeoutSeconds: Long = 30): PlacePairingResult {
        val exe = executable ?: return PlacePairingResult(false, "helper", "The device helper is not available.")
        val run = runner(listOf(exe.toString(), "reveal-developer-mode"), timeoutSeconds)
        return when {
            run.timedOut -> PlacePairingResult(false, "timeout", "")
            run.exitCode != 0 -> PlacePairingResult(false, "helper", "exit ${run.exitCode}")
            else -> PlacePairingResult.parse(run.stdout)
        }
    }

    companion object {
        const val NAME = "nuvioz-device-helper"

        /**
         * Looks for the helper in, in order: an explicit override (development), the packaged app
         * resources directory, then a local cargo build next to the module.
         */
        fun locate(
            env: Map<String, String> = System.getenv(),
            resourcesDir: String? = System.getProperty("compose.application.resources.dir"),
            isWindows: Boolean = System.getProperty("os.name").lowercase().contains("win"),
            workingDir: Path = Path.of(System.getProperty("user.dir")),
        ): Path? {
            val file = if (isWindows) "$NAME.exe" else NAME
            val candidates = listOfNotNull(
                env["NUVIOZ_DEVICE_HELPER"]?.let(Path::of),
                resourcesDir?.let { Path.of(it, file) },
                workingDir.resolve("helper/target/release/$file"),
                workingDir.resolve("iosSetup/helper/target/release/$file"),
                workingDir.resolve("helper/target/debug/$file"),
                workingDir.resolve("iosSetup/helper/target/debug/$file"),
            )
            return candidates.firstOrNull(Files::isRegularFile)
        }

        fun runProcess(args: List<String>, timeoutSeconds: Long): HelperRun = try {
            val process = ProcessBuilder(args).redirectErrorStream(false).start()
            val out = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readText() }
            CompletableFuture.runAsync { process.errorStream.copyTo(java.io.OutputStream.nullOutputStream()) }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                HelperRun(null, "", timedOut = true)
            } else {
                val text = runCatching { out.get(5, TimeUnit.SECONDS) }.getOrDefault("")
                HelperRun(process.exitValue(), text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty())
            }
        } catch (error: Exception) {
            HelperRun(null, "")
        }
    }
}
