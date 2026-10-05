package com.nuvio.z.iossetup

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the assistant in every interesting state to PNGs under build/render/, headlessly, so layout
 * can be reviewed without a phone or a window. Fails only if a render is empty or the UI throws.
 */
@OptIn(ExperimentalComposeUiApi::class)
class BoardRenderHarness {
    private val ready = javaClass.getResource("/helper-status-ready.json")!!.readText().trim()
    private val noDevice = """{"protocol":1,"helper":"0.1.0","idevice":"0.1.68","usbmuxd":{"reachable":true,"usbDevices":0},"device":null,"errors":{}}"""
    private val out: Path = Path.of("build", "render").also { Files.createDirectories(it) }
    private val humanSteps = listOf(Requirement.PROFILE_TRUST, Requirement.NOTIFICATIONS, Requirement.SIDESTORE_READY, Requirement.SOURCE_ADDED)

    private fun helper(status: String, pairOk: Boolean = true) = DeviceHelper(Path.of("helper.exe"), runner = { args, _ ->
        when (args[1]) {
            "status" -> HelperRun(0, status)
            "place-pairing" -> HelperRun(0, if (pairOk) """{"ok":true}""" else """{"ok":false,"stage":"write","error":"x"}""")
            else -> HelperRun(0, """{"ok":true}""")
        }
    })

    private fun session(helper: DeviceHelper?, progress: SetupProgress = SetupProgress(), ops: FakeOps = FakeOps()) =
        SetupSession(ops, helper ?: DeviceHelper(null), null, Diagnostics(Files.createTempFile("render", ".log")), progress)

    private val debug = SetupProgress(channel = SetupChannel.DEVELOPER, developerWarningAccepted = true)

    private fun render(name: String, session: SetupSession, width: Int = 1120, height: Int = 720) {
        val scene = ImageComposeScene(width, height, Density(1f)) {
            NuvioTheme { SetupLayout(session, onDiagnostics = {}, onAdvanced = {}) }
        }
        try {
            scene.render(0)
            val png = scene.render(200_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            Files.write(out.resolve("$name-${width}x$height.png"), png)
            assertTrue(png.size > 8_000, "$name rendered an empty frame")
        } finally {
            scene.close()
        }
    }

    @Test fun renderEveryState() {
        render("01-computer-checking", session(helper(ready)))
        session(helper(noDevice), debug).also { it.tick(); render("02-device-waiting", it) }
        session(helper(ready.replace("\"trust\":\"valid\"", "\"trust\":\"invalid\"")), debug).also { it.tick(); render("03-trust-waiting", it) }
        session(helper(ready.replace(Regex("\"localDevVpn\":\\{[^}]*\\}"), "\"localDevVpn\":null")), debug).also { it.tick(); render("04-localdevvpn", it) }
        session(helper(ready.replace(Regex("\"sidestore\":\\{[^}]*\\}"), "\"sidestore\":null").replace("\"pairingFile\":\"present\"", "\"pairingFile\":\"sidestoreMissing\"")), debug)
            .also { it.tick(); render("05-sidestore-install", it) }
        session(helper(ready.replace("\"pairingFile\":\"present\"", "\"pairingFile\":\"absent\""), pairOk = false), debug).also { it.tick(); render("06-pairing-failed", it) }
        session(helper(ready.replace("\"developerMode\":true", "\"developerMode\":false")), debug).also { it.tick(); render("07-developer-mode", it) }
        session(helper(ready), debug).also { it.tick(); render("08-profile-trust", it); render("08-profile-trust", it, 1440, 900) }
        session(helper(ready), debug).also { it.tick(); it.confirm(Requirement.PROFILE_TRUST); render("08b-notifications", it) }
        session(helper(ready), debug).also { it.tick(); it.confirm(Requirement.PROFILE_TRUST); it.confirm(Requirement.NOTIFICATIONS); it.confirm(Requirement.SIDESTORE_READY); render("09-source-added", it); render("09-source-added", it, 1440, 900) }
        session(helper(ready), SetupProgress()).also { it.tick(); humanSteps.forEach { s -> it.confirm(s) }; render("10-install-nuvio", it) }
        session(helper(ready), debug).also { it.tick(); humanSteps.forEach { s -> it.confirm(s) }; it.tick(); render("11-complete", it); render("11-complete", it, 1440, 900) }
        session(null, SetupProgress(manualMode = true)).also { it.tick(); render("12-manual-mode", it) }
        session(helper(ready), debug.copy(setupCompleted = true, confirmed = humanSteps.toSet())).also { it.enterRepair(); it.tick(); render("13-repair", it) }
        session(helper(ready), debug, FakeOps(installOk = false)).also { it.tick(); render("14-tools-failed", it) }
    }
}
