package com.nuvio.z.iossetup

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceLinkTest {
    @Test fun deepLinkMatchesTheV1Format() {
        assertEquals(SetupState(channel = SetupChannel.STABLE).sourceDeepLink, SourceLink.deepLink(SetupChannel.STABLE))
        assertEquals(SetupState(channel = SetupChannel.DEVELOPER).sourceDeepLink, SourceLink.deepLink(SetupChannel.DEVELOPER))
    }

    @Test fun sourceQrIsAlwaysTheSideStoreDeepLinkNeverAWebAddress() {
        for (channel in SetupChannel.entries) {
            val payload = SourceLink.sourceQr(channel)
            assertTrue(SourceLink.opensSideStore(payload), payload)
            assertTrue(payload.startsWith("sidestore://source?url="))
            // The bug this guards: a QR that is just the feed URL opens Safari showing raw JSON text.
            assertFalse(payload.startsWith("http"), "must not be a web address")
            assertTrue(payload != channel.sourceUrl)
            assertFalse(payload.substringAfter("sidestore://").contains("://"), "the feed URL must stay percent-encoded")
        }
    }

    @Test fun webAddressesAreNeverTreatedAsSideStorePayloads() {
        assertFalse(SourceLink.opensSideStore(STABLE_SOURCE_URL))
        assertFalse(SourceLink.opensSideStore(DEVELOPER_SOURCE_URL))
        assertFalse(SourceLink.opensSideStore("https://example.com/sidestore://source?url=x"))
        assertFalse(SourceLink.opensSideStore("sidestore://source?url=http://insecure"))
        assertTrue(SourceLink.opensSideStore(SourceLink.installDeepLink("https://example.com/a.ipa")))
    }

    @Test fun qrDecodesToExactlyThePayload() {
        for (payload in listOf(SourceLink.sourceQr(SetupChannel.STABLE), SourceLink.sourceQr(SetupChannel.DEVELOPER), SourceLink.installDeepLink("https://example.com/a.ipa"))) {
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
    }
}
