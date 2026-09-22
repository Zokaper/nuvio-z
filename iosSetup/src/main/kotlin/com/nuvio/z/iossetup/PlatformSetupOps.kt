package com.nuvio.z.iossetup

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

interface PlatformSetupOps {
    val platformName: String
    val isMac: Boolean
    fun checkComputer(): ComputerCheck
    fun installAppleDeviceSupport(): OperationResult
    fun isDeviceConnected(): Boolean
    fun findIloader(): Path?
    fun installIloader(): OperationResult
    fun openIloader(): OperationResult
}

fun platformSetupOps(diagnostics: Diagnostics): PlatformSetupOps {
    val os = System.getProperty("os.name").lowercase()
    return if (os.contains("mac")) MacSetupOps(diagnostics) else WindowsSetupOps(diagnostics)
}

abstract class ProcessPlatformOps(protected val diagnostics: Diagnostics) : PlatformSetupOps {
    protected fun run(vararg args: String, timeoutSeconds: Long = 30): OperationResult = try {
        val process = ProcessBuilder(*args).redirectErrorStream(true).start()
        val finished = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            OperationResult(false, "The operation timed out.", details = args.first())
        } else {
            val output = process.inputStream.bufferedReader().readText().take(8_000)
            val result = OperationResult(process.exitValue() == 0, if (process.exitValue() == 0) "Operation completed." else "The operation did not complete.", process.exitValue(), output)
            diagnostics.operation(args.first(), result)
            result
        }
    } catch (error: Exception) {
        val result = OperationResult(false, error.message ?: "Could not start the operation.")
        diagnostics.operation(args.firstOrNull() ?: "process", result)
        result
    }

    protected fun hasInternet(): Boolean = runCatching {
        val connection = URI("https://github.com").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "HEAD"
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.responseCode in 200..399
    }.getOrDefault(false)

    protected fun download(url: String, destination: Path): OperationResult = try {
        Files.createDirectories(destination.parent)
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMinutes(5)).GET().build()
        val response = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()
            .send(request, HttpResponse.BodyHandlers.ofFile(destination))
        val success = response.statusCode() in 200..299 && Files.size(destination) > 0
        OperationResult(success, if (success) "Download complete." else "Download failed with HTTP ${response.statusCode()}.")
    } catch (error: Exception) {
        OperationResult(false, "Download failed. Check your internet connection and try again.", details = error.message.orEmpty())
    }
}

class WindowsSetupOps(diagnostics: Diagnostics) : ProcessPlatformOps(diagnostics) {
    override val platformName = "Windows"
    override val isMac = false

    override fun checkComputer(): ComputerCheck {
        val is64 = System.getenv("PROCESSOR_ARCHITEW6432") != null || System.getProperty("os.arch").contains("64")
        val service = run("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "Get-Service -ErrorAction SilentlyContinue | Where-Object { ${'$'}_.Name -match 'Apple.*Mobile|MobileDevice' -or ${'$'}_.DisplayName -match 'Apple Mobile Device' } | Select-Object -First 1 -ExpandProperty Status")
        val registry = run("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "if ((Test-Path 'HKLM:\\SOFTWARE\\Apple Inc.\\Apple Mobile Device Support') -or (Test-Path 'HKLM:\\SOFTWARE\\WOW6432Node\\Apple Inc.\\Apple Mobile Device Support') -or (Test-Path (Join-Path ${'$'}env:ProgramFiles 'Common Files\\Apple\\Mobile Device Support')) -or (Get-AppxPackage -ErrorAction SilentlyContinue | Where-Object { ${'$'}_.Name -match 'AppleInc\\.(iTunes|AppleDevices)' })) { exit 0 } else { exit 1 }")
        val winget = run("winget", "list", "--id", "Apple.iTunes", "-e", "--source", "winget", "--accept-source-agreements", timeoutSeconds = 60)
        val serviceInstalled = service.exitCode == 0 && service.details.isNotBlank()
        val serviceRunning = service.details.contains("Running", true)
        val installed = appleSupportDetected(registry.success, serviceInstalled, winget.success)
        diagnostics.appleProbes(registry.success, serviceInstalled, serviceRunning, winget.success)
        return ComputerCheck(
            CheckResult("64-bit Windows", if (is64) CheckState.PASS else CheckState.FAIL, if (is64) "Supported" else "A 64-bit Windows computer is required."),
            CheckResult("Internet connection", if (hasInternet()) CheckState.PASS else CheckState.FAIL, "Needed to download iloader and the Nuvio Z source."),
            CheckResult("Apple device support", if (installed) CheckState.PASS else CheckState.ACTION, if (installed) "Installed" else "Install Apple's iPhone drivers."),
            CheckResult(
                "Apple Mobile Device Service",
                when {
                    serviceRunning -> CheckState.PASS
                    serviceInstalled -> CheckState.ACTION
                    else -> CheckState.ACTION
                },
                when {
                    serviceRunning -> "Running"
                    serviceInstalled -> "Installed but not running yet. You can continue; iPhone detection is the final check."
                    registry.success -> "Apple device support is installed. The service may start when the iPhone is connected."
                    winget.success -> "iTunes is installed. The USB connection on the next page will verify its drivers."
                    else -> "Not detected yet."
                },
            ),
        ).also { diagnostics.computerCheck(it) }
    }

    override fun installAppleDeviceSupport(): OperationResult = run(
        "winget", "install", "--id", "Apple.iTunes", "-e", "--source", "winget",
        "--accept-package-agreements", "--accept-source-agreements", timeoutSeconds = 900,
    )

    override fun isDeviceConnected(): Boolean {
        val result = run("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "if (Get-PnpDevice -PresentOnly -ErrorAction SilentlyContinue | Where-Object { ${'$'}_.InstanceId -like 'USB\\VID_05AC*' -or ${'$'}_.FriendlyName -match 'iPhone|iPad|Apple Mobile Device' }) { exit 0 } else { exit 1 }")
        return result.success.also { diagnostics.deviceDetected(it) }
    }

    override fun findIloader(): Path? {
        val roots = listOfNotNull(
            System.getenv("LOCALAPPDATA")?.let { Path.of(it, "Programs", "iloader", "iloader.exe") },
            System.getenv("ProgramFiles")?.let { Path.of(it, "iloader", "iloader.exe") },
            System.getenv("ProgramFiles(x86)")?.let { Path.of(it, "iloader", "iloader.exe") },
        )
        return roots.firstOrNull(Files::isRegularFile).also { path ->
            val version = path?.let {
                run("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "(Get-Item -LiteralPath '${it.toString().replace("'", "''")}').VersionInfo.ProductVersion").details.trim()
            }
            diagnostics.iloader(path, version)
        }
    }

    override fun installIloader(): OperationResult {
        val target = Path.of(System.getProperty("java.io.tmpdir"), "nuvio-z-ios-setup", "iloader-windows-x64.msi")
        val downloaded = download("https://github.com/nab138/iloader/releases/latest/download/iloader-windows-x64.msi", target)
        if (!downloaded.success) return downloaded
        val result = run("msiexec.exe", "/i", target.toString(), timeoutSeconds = 900)
        return if (result.success && findIloader() != null) OperationResult(true, "iloader is installed.", result.exitCode)
        else OperationResult(false, "iloader's installer closed, but iloader was not found. Choose Check again after completing the installer.", result.exitCode, result.details)
    }

    override fun openIloader(): OperationResult {
        val path = findIloader() ?: return OperationResult(false, "iloader is not installed yet.")
        return try {
            ProcessBuilder(path.toString()).start()
            OperationResult(true, "iloader opened. Return here after completing the instructions on your iPhone.")
        } catch (error: Exception) {
            OperationResult(false, "Could not open iloader.", details = error.message.orEmpty())
        }
    }
}

class MacSetupOps(diagnostics: Diagnostics) : ProcessPlatformOps(diagnostics) {
    override val platformName = "macOS"
    override val isMac = true

    override fun checkComputer(): ComputerCheck {
        val supported = System.getProperty("os.name").lowercase().contains("mac")
        return ComputerCheck(
            CheckResult("macOS", if (supported) CheckState.PASS else CheckState.FAIL, "Apple device support is built in."),
            CheckResult("Internet connection", if (hasInternet()) CheckState.PASS else CheckState.FAIL),
            CheckResult("Apple device support", CheckState.PASS, "Built into macOS"),
            null,
        ).also { diagnostics.computerCheck(it) }
    }

    override fun installAppleDeviceSupport() = OperationResult(true, "Apple device support is built into macOS.")

    override fun isDeviceConnected(): Boolean {
        val usb = run("/usr/sbin/system_profiler", "SPUSBDataType", "-detailLevel", "mini", timeoutSeconds = 20)
        return (usb.success && (usb.details.contains("iPhone", true) || usb.details.contains("iPad", true))).also { diagnostics.deviceDetected(it) }
    }

    override fun findIloader(): Path? = listOf(
        Path.of("/Applications/iloader.app"), Path.of(System.getProperty("user.home"), "Applications", "iloader.app")
    ).firstOrNull(Files::isDirectory).also { path ->
        val version = path?.let { run("/usr/bin/defaults", "read", it.resolve("Contents/Info").toString(), "CFBundleShortVersionString").details.trim() }
        diagnostics.iloader(path, version)
    }

    override fun installIloader(): OperationResult {
        val target = Path.of(System.getProperty("java.io.tmpdir"), "nuvio-z-ios-setup", "iloader.dmg")
        val downloaded = download("https://github.com/nab138/iloader/releases/latest/download/iloader-darwin-universal.dmg", target)
        if (!downloaded.success) return downloaded
        val opened = run("/usr/bin/open", target.toString())
        return if (opened.success) OperationResult(false, "The iloader disk image is open. Drag iloader to Applications, then choose Check again.") else opened
    }

    override fun openIloader(): OperationResult {
        val path = findIloader() ?: return OperationResult(false, "iloader is not installed in Applications yet.")
        val result = run("/usr/bin/open", path.toString())
        return if (result.success) OperationResult(true, "iloader opened. Closing it will not advance this wizard.") else result
    }
}
