package com.nuvio.z.iossetup

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoRefreshConfigTest {
    private val link = "https://www.icloud.com/shortcuts/0123456789abcdef0123456789abcdef"
    private fun config(url: String?) = """{"schema":1,"shortcutUrl":${url?.let { "\"$it\"" } ?: "null"}}"""

    @Test fun shippedCopyParses() {
        assertEquals(1, AutoRefreshConfig.embedded().schema)
    }

    @Test fun onlyAnICloudShortcutLinkIsAccepted() {
        assertEquals(link, AutoRefreshConfig.parse(config(link))?.shortcutUrl)
        assertNotNull(AutoRefreshConfig.parse(config(null)), "no link yet is valid: the page explains how to build it")
        listOf(
            "http://www.icloud.com/shortcuts/0123456789abcdef0123456789abcdef",
            "https://icloud.com.evil.example/shortcuts/0123456789abcdef0123456789abcdef",
            "https://www.icloud.com/shortcuts/0123456789abcdef0123456789abcdef?next=https://evil.example",
            "https://www.icloud.com/iclouddrive/0123456789abcdef0123456789abcdef",
            "shortcuts://import-shortcut?url=https://evil.example/x.shortcut",
        ).forEach { assertNull(AutoRefreshConfig.parse(config(it)), it) }
        assertNull(AutoRefreshConfig.parse("""{"schema":2,"shortcutUrl":null}"""))
        assertNull(AutoRefreshConfig.parse("garbage"))
    }

    @Test fun hostedLinkWinsOnlyWhenItValidates() {
        val shipped = AutoRefreshConfig(1, null)
        assertEquals(link, AutoRefreshConfig.load({ config(link) }, shipped).shortcutUrl)
        assertEquals(shipped, AutoRefreshConfig.load({ config("https://evil.example/x") }, shipped))
        assertEquals(shipped, AutoRefreshConfig.load({ error("offline") }, shipped))
        assertEquals(shipped, AutoRefreshConfig.load({ null }, shipped))
    }

    /** The hosted copy may run ahead of the shipped one (that is its purpose), but it must always validate. */
    @Test fun repositoryCopyIsValid() {
        val repoCopy = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .map { it.resolve("distribution/sidestore/ios-setup-autorefresh.json") }.firstOrNull(Files::isRegularFile) ?: return
        assertNotNull(AutoRefreshConfig.parse(Files.readString(repoCopy)))
    }

    @Test fun everyTipHasSomethingToDo() {
        val tips = troubleshootingFor(Requirement.AUTO_REFRESH, SetupChannel.STABLE, isMac = false)
        assertTrue(tips.size >= 4)
        assertTrue(tips.all { it.recovery.isNotEmpty() })
        assertTrue(tips.any { "LocalDevVPN" in it.problem })
    }
}
