package com.nuvio.z.iossetup

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrereqsAndBootstrapTest {
    private val embedded = Prereqs.embedded()

    @Test fun embeddedDescriptorIsValidAndPinsEveryIloaderAsset() {
        assertTrue(embedded.isValid())
        assertEquals("v2.3.5", embedded.iloader.version)
        listOf(embedded.iloader.windows, embedded.iloader.macos).forEach {
            assertEquals(64, it.sha256.length)
            assertTrue(it.url.contains(embedded.iloader.version), "URL must name the pinned version, never 'latest'")
            assertFalse(it.url.contains("latest"))
        }
    }

    @Test fun hostedDescriptorMayNotPointOffTheAllowList() {
        val evil = embedded.copy(iloader = embedded.iloader.copy(windows = embedded.iloader.windows.copy(url = "https://evil.example/iloader.exe")))
        assertFalse(evil.isValid())
        val http = embedded.copy(iloader = embedded.iloader.copy(windows = embedded.iloader.windows.copy(url = embedded.iloader.windows.url.replace("https", "http"))))
        assertFalse(http.isValid())
    }

    @Test fun descriptorWithoutAProperHashOrVersionIsRejected() {
        assertFalse(embedded.copy(iloader = embedded.iloader.copy(macos = embedded.iloader.macos.copy(sha256 = "abc"))).isValid())
        assertFalse(embedded.copy(iloader = embedded.iloader.copy(version = "latest")).isValid())
        assertFalse(embedded.copy(schema = 2).isValid())
        assertNull(Prereqs.parse("{ nope"))
    }

    @Test fun remoteDescriptorWinsOnlyWhenItValidates() {
        val newer = javaClass.getResource("/prereqs.json")!!.readText().replace("v2.3.5", "v2.3.6")
        assertEquals("v2.3.6", Prereqs.load({ newer }, embedded).iloader.version)
        assertEquals("v2.3.5", Prereqs.load({ "garbage" }, embedded).iloader.version)
        assertEquals("v2.3.5", Prereqs.load({ error("offline") }, embedded).iloader.version)
        assertEquals("v2.3.5", Prereqs.load({ null }, embedded).iloader.version)
    }

    @Test fun repositoryCopyOfTheDescriptorMatchesTheEmbeddedOne() {
        val repoCopy = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .map { it.resolve("distribution/sidestore/ios-setup-prereqs.json") }.firstOrNull(Files::isRegularFile) ?: return
        assertEquals(embedded, Prereqs.parse(Files.readString(repoCopy)))
    }

    // ---- bootstrap -------------------------------------------------------------------------------

    private val servers = mutableListOf<HttpServer>()
    @AfterTest fun stop() = servers.forEach { it.stop(0) }

    private fun serve(body: ByteArray): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/f") { e -> e.sendResponseHeaders(200, body.size.toLong()); e.responseBody.write(body); e.close() }
        server.start(); servers += server
        return "http://127.0.0.1:${server.address.port}/f"
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun bootstrap(
        bytes: ByteArray, tools: Path, run: (List<String>, Long) -> HelperRun,
        system: List<Path> = emptyList(), pinSha: String = sha(bytes),
    ): IloaderBootstrap {
        val url = serve(bytes)
        val asset = PinnedAsset(url, pinSha, bytes.size.toLong(), "nsis")
        val prereqs = embedded.copy(iloader = embedded.iloader.copy(windows = asset, macos = asset))
        return IloaderBootstrap(prereqs, isMac = false, toolsDir = tools, downloader = Downloader(attempts = 1, backoffMillis = 1), run = run, systemCandidates = system)
    }

    /** Stands in for the NSIS installer: it "installs" by creating iloader.exe in the requested folder. */
    private fun fakeInstaller(tools: Path): (List<String>, Long) -> HelperRun = { args, _ ->
        val command = args.last()
        val dir = Regex("/D=([^']+)'").find(command)?.groupValues?.get(1)
        if (dir != null && command.contains("/S")) { Files.createDirectories(Path.of(dir)); Files.writeString(Path.of(dir, "iloader.exe"), "fake") }
        HelperRun(0, "")
    }

    @Test fun installDownloadsVerifiesInstallsIntoOurFolderAndRecordsThePin() {
        val tools = Files.createTempDirectory("tools with space")
        val boot = bootstrap("installer-bytes".toByteArray(), tools, fakeInstaller(tools))
        assertNull(boot.find())
        val result = boot.install()
        assertTrue(result.success, result.message)
        assertTrue(boot.isOurs)
        assertEquals(boot.installDir.resolve("iloader.exe"), boot.find())
        assertFalse(Files.exists(tools.resolve("downloads/iloader-v2.3.5-setup.exe")), "installer is cleaned up")
    }

    @Test fun aTamperedDownloadIsNeverRun() {
        val tools = Files.createTempDirectory("tools")
        var ran = false
        val boot = bootstrap("evil".toByteArray(), tools, { _, _ -> ran = true; HelperRun(0, "") }, pinSha = "0".repeat(64))
        val result = boot.install()
        assertFalse(result.success)
        assertFalse(ran, "an installer that fails verification must not be executed")
        assertTrue(result.message.contains("discarded"))
        assertNull(boot.find())
    }

    @Test fun anInstallerThatExitsCleanButInstallsNothingIsAFailure() {
        val tools = Files.createTempDirectory("tools")
        val boot = bootstrap("x".toByteArray(), tools, { _, _ -> HelperRun(0, "") })
        assertFalse(boot.install().success)
        assertNull(boot.find())
    }

    @Test fun anExistingIloaderIsUsedInsteadOfDownloadingAgain() {
        val tools = Files.createTempDirectory("tools")
        val theirs = Files.createTempDirectory("theirs").resolve("iloader.exe").also { Files.writeString(it, "mine") }
        val boot = bootstrap("x".toByteArray(), tools, { _, _ -> error("must not run") }, system = listOf(theirs))
        assertEquals(theirs, boot.find())
        assertTrue(boot.install().success)
        assertFalse(boot.isOurs)
        assertTrue(boot.uninstall().success)
        assertTrue(Files.exists(theirs), "a user's own iloader is never removed")
    }

    @Test fun uninstallRemovesOnlyWhatWeInstalled() {
        val tools = Files.createTempDirectory("tools")
        val commands = mutableListOf<String>()
        val install = fakeInstaller(tools)
        val boot = bootstrap("installer".toByteArray(), tools, { a, s -> commands += a.last(); install(a, s) })
        boot.install()
        assertTrue(boot.uninstall().success)
        assertFalse(Files.exists(boot.installDir))
        assertTrue(commands.any { it.contains("uninstall.exe") }, "runs the NSIS uninstaller so the Start Menu shortcut goes too")
        assertNull(boot.find())
    }

    @Test fun aStaleMarkerFromAnotherPinIsNotTrusted() {
        val tools = Files.createTempDirectory("tools")
        val boot = bootstrap("installer".toByteArray(), tools, fakeInstaller(tools))
        boot.install()
        Files.writeString(boot.installDir.resolve(".nuvioz-pinned"), "v2.3.5\n${"1".repeat(64)}\n")
        assertNull(boot.find())
    }
}
