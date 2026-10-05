package com.nuvio.z.iossetup

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceLinkTest {
    @Test fun deepLinkMatchesTheV1Format() {
        assertEquals(SetupState(channel = SetupChannel.STABLE).sourceDeepLink, SourceLink.deepLink(SetupChannel.STABLE))
        assertEquals(SetupState(channel = SetupChannel.DEVELOPER).sourceDeepLink, SourceLink.deepLink(SetupChannel.DEVELOPER))
    }

    @Test fun qrUsesTheLandingPageOnlyWhenItIsLive() {
        assertTrue(SourceLink.qrPayload(SetupChannel.STABLE, landingReachable = true).startsWith("https://"))
        assertTrue(SourceLink.qrPayload(SetupChannel.STABLE, landingReachable = false).startsWith("sidestore://source?url="))
        assertTrue(SourceLink.landingUrl(SetupChannel.DEVELOPER).endsWith("?channel=debug"))
        assertTrue(SourceLink.landingUrl(SetupChannel.STABLE).endsWith("?channel=stable"))
    }

    @Test fun qrDecodesToExactlyThePayload() {
        for (payload in listOf(SourceLink.landingUrl(SetupChannel.STABLE), SourceLink.deepLink(SetupChannel.DEVELOPER))) {
            val bitmap = BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(QrCode.buffered(payload))))
            assertEquals(payload, MultiFormatReader().decode(bitmap).text)
        }
    }

    @Test fun latestIpaIsTheFirstVersionOfTheFirstApp() {
        val feed = """{"apps":[{"versions":[{"version":"2","downloadURL":"https://example.com/new.ipa"},{"version":"1","downloadURL":"https://example.com/old.ipa"}]}]}"""
        assertEquals("https://example.com/new.ipa", SourceLink.latestIpaUrl(feed))
        assertEquals(null, SourceLink.latestIpaUrl("""{"apps":[{"versions":[{"downloadURL":"http://insecure/x.ipa"}]}]}"""))
        assertEquals(null, SourceLink.latestIpaUrl("{}"))
        assertEquals(null, SourceLink.latestIpaUrl("nope"))
    }

    @Test fun installLinksCarryTheEncodedIpa() {
        assertEquals("sidestore://install?url=https%3A%2F%2Fexample.com%2Fa%20b.ipa", SourceLink.installDeepLink("https://example.com/a b.ipa"))
        assertTrue(SourceLink.installLandingUrl(SetupChannel.STABLE).endsWith("?channel=stable&action=install"))
    }

    @Test fun unreachableHostIsReportedAsNotReachable() {
        assertFalse(SourceLink.isReachable("http://127.0.0.1:1/", timeoutMillis = 300))
    }

    @Test fun landingPageCarriesTheSameSourceUrlsAsTheApp() {
        val page = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .map { it.resolve("distribution/sidestore/add/index.html") }.firstOrNull(Files::isRegularFile) ?: return
        val html = Files.readString(page)
        assertTrue(html.contains(STABLE_SOURCE_URL), "landing page stable source drifted from SetupChannel")
        assertTrue(html.contains(DEVELOPER_SOURCE_URL), "landing page debug source drifted from SetupChannel")
        assertTrue(html.contains("sidestore://source?url="))
        assertFalse(html.contains("http://"), "no insecure URLs")
    }
}
