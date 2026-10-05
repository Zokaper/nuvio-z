package com.nuvio.z.iossetup

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

/**
 * Gets iloader onto the machine without the user ever visiting its download page: a pinned release is
 * downloaded, its SHA-256 is verified against [Prereqs], and it is installed into this app's own
 * folder. Nothing here is redistributed with the setup app, and nothing runs unless the hash matches.
 */
class IloaderBootstrap(
    private val prereqs: Prereqs,
    private val isMac: Boolean,
    private val toolsDir: Path = defaultToolsDir(),
    private val downloader: Downloader = Downloader(),
    private val run: (List<String>, Long) -> HelperRun = { args, seconds -> DeviceHelper.runProcess(args, seconds) },
    private val systemCandidates: List<Path> = defaultSystemCandidates(isMac),
    private val diagnostics: Diagnostics? = null,
) {
    private val asset = prereqs.iloader.forPlatform(isMac)
    val installDir: Path = toolsDir.resolve("iloader-${prereqs.iloader.version}")
    private val marker: Path = installDir.resolve(".nuvioz-pinned")
    private val ownExecutable: Path = installDir.resolve(if (isMac) "iloader.app" else "iloader.exe")

    /** The pinned copy we installed, else any iloader the user already had. Null when there is none. */
    fun find(): Path? {
        val own = ownExecutable.takeIf { exists(it) && markerMatches() }
        val found = own ?: systemCandidates.firstOrNull { exists(it) }
        diagnostics?.iloader(found, if (own != null) prereqs.iloader.version else null)
        return found
    }

    val isOurs: Boolean get() = exists(ownExecutable) && markerMatches()

    fun install(onProgress: (Long, Long?) -> Unit = { _, _ -> }): OperationResult {
        if (find() != null) return OperationResult(true, "iloader is ready.")
        val file = toolsDir.resolve("downloads").resolve("iloader-${prereqs.iloader.version}" + if (isMac) ".app.tar.gz" else "-setup.exe")
        val downloaded = downloader.download(asset.url, file, asset.sha256, asset.bytes, onProgress = onProgress)
        if (downloaded is DownloadResult.Failed) return downloadFailure(downloaded)

        runCatching { deleteTree(installDir); Files.createDirectories(installDir) }
        val installed = if (isMac) run(listOf("/usr/bin/tar", "-xzf", file.toString(), "-C", installDir.toString()), 120)
        else silentInstall(file)
        val ok = installed.exitCode == 0 && exists(ownExecutable)
        if (!ok) {
            diagnostics?.operation("iloader-install", OperationResult(false, "install failed", installed.exitCode))
            return OperationResult(false, "iloader could not be set up automatically.", installed.exitCode, installed.stdout)
        }
        Files.writeString(marker, "${prereqs.iloader.version}\n${asset.sha256}\n")
        runCatching { Files.deleteIfExists(file) }
        return OperationResult(true, "iloader is ready.")
    }

    fun launch(): OperationResult {
        val path = find() ?: return OperationResult(false, "iloader is not set up yet.")
        return try {
            if (isMac) run(listOf("/usr/bin/open", path.toString()), 15) else ProcessBuilder(path.toString()).start()
            OperationResult(true, "iloader opened.")
        } catch (error: Exception) {
            OperationResult(false, "Could not open iloader.", details = error.message.orEmpty())
        }
    }

    /** Removes only what this app installed. A user's own iloader is never touched. */
    fun uninstall(): OperationResult {
        if (!isOurs) return OperationResult(true, "Nothing to remove.")
        if (!isMac) {
            // NSIS's uninstaller also removes the Start Menu shortcut the installer created.
            run(powershell("Start-Process -FilePath '${installDir.resolve("uninstall.exe")}' -ArgumentList '/S _?=${installDir}' -Wait"), 120)
        }
        return runCatching { deleteTree(installDir) }
            .fold({ OperationResult(true, "iloader removed.") }, { OperationResult(false, "Could not remove iloader.", details = it.message.orEmpty()) })
    }

    /**
     * NSIS wants `/D=<dir>` last and unquoted, which Java's argument quoting would break for a path with
     * spaces (a Windows user name often has one). PowerShell passes a single string through verbatim.
     */
    private fun silentInstall(installer: Path): HelperRun =
        run(powershell("Start-Process -FilePath '${installer}' -ArgumentList '/S /D=${installDir}' -Wait"), 300)

    private fun powershell(command: String) = listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command)

    private fun markerMatches(): Boolean = runCatching {
        Files.readAllLines(marker).getOrNull(1)?.equals(asset.sha256, ignoreCase = true) == true
    }.getOrDefault(false)

    private fun exists(path: Path) = if (isMac) Files.isDirectory(path) else Files.isRegularFile(path)

    private fun downloadFailure(failure: DownloadResult.Failed): OperationResult = OperationResult(
        false,
        when (failure.reason) {
            DownloadResult.Reason.NETWORK -> "The download was interrupted. Check your internet connection and try again."
            DownloadResult.Reason.HTTP -> "The download server did not respond as expected. Try again in a minute."
            DownloadResult.Reason.CHECKSUM, DownloadResult.Reason.SIZE ->
                "The downloaded file did not match what we expected, so it was discarded. Try again; if it repeats, use the manual path in Advanced."
        },
        details = failure.detail,
    )

    companion object {
        /** No spaces in the Windows path on purpose: it keeps every external tool's command line simple. */
        fun defaultToolsDir(): Path {
            val os = System.getProperty("os.name").lowercase()
            return if (os.contains("mac")) Path.of(System.getProperty("user.home"), "Library", "Application Support", "Nuvio Z iOS Setup", "tools")
            else Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "NuvioZSetup", "tools")
        }

        fun defaultSystemCandidates(isMac: Boolean): List<Path> = if (isMac) {
            listOf(Path.of("/Applications/iloader.app"), Path.of(System.getProperty("user.home"), "Applications", "iloader.app"))
        } else {
            listOfNotNull(
                System.getenv("LOCALAPPDATA")?.let { Path.of(it, "Programs", "iloader", "iloader.exe") },
                System.getenv("ProgramFiles")?.let { Path.of(it, "iloader", "iloader.exe") },
                System.getenv("ProgramFiles(x86)")?.let { Path.of(it, "iloader", "iloader.exe") },
            )
        }

        private fun deleteTree(path: Path) {
            if (!Files.exists(path)) return
            Files.walk(path).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}
