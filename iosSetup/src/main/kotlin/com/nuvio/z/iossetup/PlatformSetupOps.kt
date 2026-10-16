package com.nuvio.z.iossetup

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.InetSocketAddress
import java.net.Socket
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture

interface PlatformSetupOps {
    val platformName: String
    val isMac: Boolean
    fun checkComputer(): ComputerCheck
    fun installAppleDeviceSupport(): OperationResult
    fun isDeviceConnected(): Boolean
    fun isDeviceTransportReady(): Boolean
    fun openAppleServiceManager(): OperationResult
    fun findIloader(): Path?
    fun installIloader(onProgress: (Long, Long?) -> Unit = { _, _ -> }): OperationResult
    fun openIloader(): OperationResult
    /** The pinned, checksum-verified iloader installer; see [IloaderBootstrap]. */
    val iloaderBootstrap: IloaderBootstrap
}

fun platformSetupOps(diagnostics: Diagnostics): PlatformSetupOps {
    val os = System.getProperty("os.name").lowercase()
    return if (os.contains("mac")) MacSetupOps(diagnostics) else WindowsSetupOps(diagnostics)
}

abstract class ProcessPlatformOps(protected val diagnostics: Diagnostics) : PlatformSetupOps {
    protected val prereqs: Prereqs by lazy { Prereqs.load(::fetchHostedPrereqs) }
    override val iloaderBootstrap: IloaderBootstrap by lazy { IloaderBootstrap(prereqs, isMac, diagnostics = diagnostics) }

    private fun fetchHostedPrereqs(): String? = runCatching {
        val request = HttpRequest.newBuilder(URI(Prereqs.REMOTE_URL)).timeout(Duration.ofSeconds(4)).GET().build()
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build()
            .send(request, HttpResponse.BodyHandlers.ofString()).takeIf { it.statusCode() == 200 }?.body()
    }.getOrNull()

    protected fun run(vararg args: String, timeoutSeconds: Long = 30, maxOutput: Int = 8_000): OperationResult = try {
        val process = ProcessBuilder(*args).redirectErrorStream(true).start()
        // Drain while the process runs: a full pipe would stall system_profiler/ioreg until the timeout.
        val reader = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readText() }
        val finished = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            OperationResult(false, "The operation timed out.", details = args.first())
        } else {
            val output = runCatching { reader.get(5, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault("").take(maxOutput)
            val result = OperationResult(process.exitValue() == 0, if (process.exitValue() == 0) "Operation completed." else "The operation did not complete.", process.exitValue(), output)
            diagnostics.operation(args.first(), result)
            result
        }
    } catch (error: Exception) {
        val result = OperationResult(false, error.message ?: "Could not start the operation.")
        diagnostics.operation(args.firstOrNull() ?: "process", result)
        result
    }

    protected fun internetCheck(): CheckResult {
        val reachable = listOf("https://github.com", STABLE_SOURCE_URL)
            .map { url ->
                CompletableFuture.supplyAsync {
                    runCatching {
                        val connection = URI(url).toURL().openConnection() as HttpURLConnection
                        try {
                            connection.requestMethod = "HEAD"
                            connection.connectTimeout = 4_000
                            connection.readTimeout = 4_000
                            connection.setRequestProperty("User-Agent", "Nuvio-Z-iOS-Setup")
                            connection.responseCode in 200..399
                        } finally {
                            connection.disconnect()
                        }
                    }.getOrDefault(false)
                }
            }
            .any { it.join() }
        return if (reachable) {
            CheckResult("Internet connection", CheckState.PASS, "Online")
        } else {
            CheckResult(
                "Internet connection",
                CheckState.ACTION,
                "Couldn’t verify the connection right now. You can continue, but downloads will need internet.",
            )
        }
    }

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
        val serviceFuture = CompletableFuture.supplyAsync {
            run("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "Get-Service -ErrorAction SilentlyContinue | Where-Object { ${'$'}_.Name -match 'Apple.*Mobile|MobileDevice' -or ${'$'}_.DisplayName -match 'Apple Mobile Device' } | Select-Object -First 1 -ExpandProperty Status")
        }
        val registryFuture = CompletableFuture.supplyAsync {
            run("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "if ((Test-Path 'HKLM:\\SOFTWARE\\Apple Inc.\\Apple Mobile Device Support') -or (Test-Path 'HKLM:\\SOFTWARE\\WOW6432Node\\Apple Inc.\\Apple Mobile Device Support') -or (Test-Path (Join-Path ${'$'}env:ProgramFiles 'Common Files\\Apple\\Mobile Device Support')) -or (Get-AppxPackage -ErrorAction SilentlyContinue | Where-Object { ${'$'}_.Name -match 'AppleInc\\.(iTunes|AppleDevices)' })) { exit 0 } else { exit 1 }")
        }
        val wingetFuture = CompletableFuture.supplyAsync {
            run("winget", "list", "--id", "Apple.iTunes", "-e", "--source", "winget", "--accept-source-agreements", timeoutSeconds = 12)
        }
        val internetFuture = CompletableFuture.supplyAsync(::internetCheck)
        val service = serviceFuture.join()
        val registry = registryFuture.join()
        val winget = wingetFuture.join()
        val internet = internetFuture.join()
        val serviceInstalled = service.exitCode == 0 && service.details.isNotBlank()
        val serviceRunning = service.details.contains("Running", true)
        val installed = appleSupportDetected(registry.success, serviceInstalled, winget.success)
        diagnostics.appleProbes(registry.success, serviceInstalled, serviceRunning, winget.success)
        return ComputerCheck(
            CheckResult("64-bit Windows", if (is64) CheckState.PASS else CheckState.FAIL, if (is64) "Supported" else "A 64-bit Windows computer is required."),
            internet,
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

    override fun installAppleDeviceSupport(): OperationResult {
        val apple = prereqs.appleInstallerWindows
        val target = Path.of(System.getProperty("java.io.tmpdir"), "nuvio-z-ios-setup", "iTunes64Setup.exe")
        val downloaded = Downloader().download(apple.url, target, minBytes = apple.minBytes)
        if (downloaded is DownloadResult.Failed) return OperationResult(
            false,
            "Couldn’t download Apple’s installer. Check your internet connection and try again.",
            details = "${downloaded.reason} ${downloaded.detail}",
        )
        // Apple rotates this URL, so trust comes from Apple’s code signature rather than a pinned hash.
        if (!signedBy(target, apple.publisher)) {
            runCatching { Files.deleteIfExists(target) }
            return OperationResult(false, "The downloaded installer was not signed by ${apple.publisher}, so it was not run.")
        }
        val installed = run(target.toString(), timeoutSeconds = 900)
        return if (installed.success) OperationResult(true, "Apple's desktop iTunes installer finished. Checking device communication again.", installed.exitCode)
        else OperationResult(false, "Apple's installer did not complete successfully. Try it again or open the technical details below.", installed.exitCode, installed.details)
    }

    private fun signedBy(file: Path, publisher: String): Boolean = run(
        "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
        "${'$'}s = Get-AuthenticodeSignature -LiteralPath '${file.toString().replace("'", "''")}'; " +
            "if (${'$'}s.Status -eq 'Valid' -and ${'$'}s.SignerCertificate.Subject -match 'O=${publisher.replace("'", "''")}') { exit 0 } else { exit 1 }",
        timeoutSeconds = 60,
    ).success

    override fun isDeviceConnected(): Boolean {
        val result = run("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "if (Get-PnpDevice -PresentOnly -ErrorAction SilentlyContinue | Where-Object { ${'$'}_.InstanceId -like 'USB\\VID_05AC*' -or ${'$'}_.FriendlyName -match 'iPhone|iPad|Apple Mobile Device' }) { exit 0 } else { exit 1 }")
        return result.success.also { diagnostics.deviceDetected(it) }
    }

    override fun isDeviceTransportReady(): Boolean = runCatching {
        Socket().use { socket -> socket.connect(InetSocketAddress("127.0.0.1", 27015), 1_500) }
        true
    }.getOrDefault(false).also { diagnostics.deviceTransportReady(it) }

    override fun openAppleServiceManager(): OperationResult = try {
        ProcessBuilder("mmc.exe", "services.msc").start()
        OperationResult(true, "Windows Services opened. Find Apple Mobile Device Service, set it to Automatic, then stop and start it.")
    } catch (error: Exception) {
        OperationResult(false, "Could not open Windows Services.", details = error.message.orEmpty())
    }

    override fun findIloader(): Path? = iloaderBootstrap.find()

    override fun installIloader(onProgress: (Long, Long?) -> Unit): OperationResult = iloaderBootstrap.install(onProgress)

    override fun openIloader(): OperationResult = iloaderBootstrap.launch()
}

class MacSetupOps(diagnostics: Diagnostics) : ProcessPlatformOps(diagnostics) {
    override val platformName = "macOS"
    override val isMac = true

    override fun checkComputer(): ComputerCheck {
        val supported = System.getProperty("os.name").lowercase().contains("mac")
        return ComputerCheck(
            CheckResult("macOS", if (supported) CheckState.PASS else CheckState.FAIL, "Apple device support is built in."),
            internetCheck(),
            CheckResult("Apple device support", CheckState.PASS, "Built into macOS"),
            null,
        ).also { diagnostics.computerCheck(it) }
    }

    override fun installAppleDeviceSupport() = OperationResult(true, "Apple device support is built into macOS.")

    override fun isDeviceConnected(): Boolean {
        val usbmuxd = Usbmuxd.usbDeviceCount()
        if (usbmuxd != null && usbmuxd > 0) {
            diagnostics.macDeviceProbes(usbmuxd, ioreg = null, systemProfiler = null)
            return true.also { diagnostics.deviceDetected(it) }
        }
        // Fallbacks for a usbmuxd that is unreachable or has not enumerated the phone yet.
        // `ioreg -p IOUSB` lists device names on Intel and Apple silicon alike; system_profiler
        // moved USB to SPUSBHostDataType on Apple silicon and SPUSBDataType is empty there.
        val ioreg = run("/usr/sbin/ioreg", "-p", "IOUSB", "-w0", timeoutSeconds = 10, maxOutput = 200_000)
        val ioregFound = ioreg.success && mentionsIosDevice(ioreg.details)
        val profilerFound = !ioregFound && run(
            "/usr/sbin/system_profiler", "SPUSBHostDataType", "SPUSBDataType", timeoutSeconds = 20, maxOutput = 500_000,
        ).let { it.success && mentionsIosDevice(it.details) }
        diagnostics.macDeviceProbes(usbmuxd, ioregFound, if (ioregFound) null else profilerFound)
        return (ioregFound || profilerFound).also { diagnostics.deviceDetected(it) }
    }

    override fun isDeviceTransportReady(): Boolean = Usbmuxd.isReachable().also { diagnostics.deviceTransportReady(it) }

    override fun openAppleServiceManager(): OperationResult = try {
        ProcessBuilder("/usr/bin/open", "-a", "Finder").start()
        OperationResult(true, "Finder opened. Your iPhone should appear in the sidebar under Locations once it is trusted.")
    } catch (error: Exception) {
        OperationResult(false, "Could not open Finder.", details = error.message.orEmpty())
    }

    override fun findIloader(): Path? = iloaderBootstrap.find()

    override fun installIloader(onProgress: (Long, Long?) -> Unit): OperationResult = iloaderBootstrap.install(onProgress)

    override fun openIloader(): OperationResult = iloaderBootstrap.launch()
}
