package com.nuvio.z.iossetup

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceHelperTest {
    private val statusLine = javaClass.getResource("/helper-status-ready.json")!!.readText().trim()
    private val exe: Path = Path.of("nuvioz-device-helper.exe")

    @Test fun statusParsesHelperOutput() {
        val helper = DeviceHelper(exe, runner = { args, _ ->
            assertEquals(listOf(exe.toString(), "status"), args)
            HelperRun(0, statusLine)
        })
        assertEquals(TrustState.VALID, helper.status()?.device?.trust)
    }

    @Test fun missingHelperDegradesToNullInsteadOfThrowing() {
        val helper = DeviceHelper(null)
        assertFalse(helper.available)
        assertNull(helper.status())
        val placed = helper.placePairing()
        assertFalse(placed.ok)
        assertEquals("helper", placed.stage)
    }

    @Test fun crashTimeoutAndGarbageAllMeanNoStatus() {
        assertNull(DeviceHelper(exe, runner = { _, _ -> HelperRun(1, "") }).status())
        assertNull(DeviceHelper(exe, runner = { _, _ -> HelperRun(null, "", timedOut = true) }).status())
        assertNull(DeviceHelper(exe, runner = { _, _ -> HelperRun(0, "Segmentation noise") }).status())
        assertNull(DeviceHelper(exe, runner = { _, _ -> HelperRun(0, statusLine.replace("\"protocol\":1", "\"protocol\":2")) }).status())
    }

    @Test fun placePairingReportsEachFailureStageInPlainLanguage() {
        fun placed(line: String, timedOut: Boolean = false, exit: Int? = 0) =
            DeviceHelper(exe, runner = { _, _ -> HelperRun(exit, line, timedOut) }).placePairing()

        assertTrue(placed("""{"ok":true,"readBackMatches":true}""").ok)
        assertEquals("SideStore is not installed on the iPhone yet.", placed("""{"ok":false,"stage":"sidestore","error":"SideStore not installed"}""").userMessage)
        assertEquals("The iPhone is not connected.", placed("""{"ok":false,"stage":"device","error":"no USB device"}""").userMessage)
        assertTrue(placed("", timedOut = true, exit = null).userMessage.contains("Unlock"))
        assertFalse(placed("garbage").ok)
        assertFalse(placed("""{"ok":true}""", exit = 3).ok)
    }

    @Test fun locatePrefersOverrideThenPackagedResourcesThenLocalBuild() {
        val dir = Files.createTempDirectory("helper-locate")
        val packaged = Files.createDirectories(dir.resolve("res")).resolve("nuvioz-device-helper.exe").also { Files.writeString(it, "x") }
        val override = dir.resolve("override.exe").also { Files.writeString(it, "x") }
        val local = Files.createDirectories(dir.resolve("helper/target/release")).resolve("nuvioz-device-helper.exe").also { Files.writeString(it, "x") }

        assertEquals(override, DeviceHelper.locate(mapOf("NUVIOZ_DEVICE_HELPER" to override.toString()), dir.resolve("res").toString(), true, dir))
        assertEquals(packaged, DeviceHelper.locate(emptyMap(), dir.resolve("res").toString(), true, dir))
        assertEquals(local, DeviceHelper.locate(emptyMap(), null, true, dir))
        assertNull(DeviceHelper.locate(emptyMap(), null, false, dir))
        assertNotNull(DeviceHelper.locate(emptyMap(), null, true, dir))
    }
}
