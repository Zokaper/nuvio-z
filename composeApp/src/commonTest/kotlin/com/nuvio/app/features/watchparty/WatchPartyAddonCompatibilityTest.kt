package com.nuvio.app.features.watchparty

import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonResource
import com.nuvio.app.features.addons.ManagedAddon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchPartyAddonCompatibilityTest {
    @Test
    fun signatureContainsOnlyEnabledStreamAddonsAndNoUrls() {
        val stream = addon("streamer", "2.1", "stream", "https://secret.example/abc/manifest.json")
        val catalog = addon("catalog", "1", "catalog", "https://example/catalog/manifest.json")
        val disabled = addon("disabled", "3", "stream", "https://example/disabled/manifest.json", enabled = false)

        assertEquals(
            listOf(PartyAddonSignature("streamer", "2.1")),
            watchPartyAddonSignature(listOf(catalog, disabled, stream)),
        )
        assertFalse(watchPartyAddonSignature(listOf(stream)).toString().contains("secret.example"))
    }

    @Test
    fun mismatchIncludesMissingAndVersionDifferences() {
        val host = listOf(PartyAddonSignature("a", "1"), PartyAddonSignature("b", "2"))
        val guest = listOf(PartyAddonSignature("a", "2"), PartyAddonSignature("b", "2"))
        val mismatch = comparePartyAddonSignatures(host, guest)

        assertTrue(mismatch.differs)
        assertEquals(listOf(PartyAddonSignature("a", "1")), mismatch.missing)
        assertEquals(listOf(PartyAddonSignature("a", "2")), mismatch.extra)
    }

    private fun addon(id: String, version: String, resource: String, url: String, enabled: Boolean = true) =
        ManagedAddon(
            manifestUrl = url,
            enabled = enabled,
            manifest = AddonManifest(
                id = id,
                name = id,
                description = "",
                version = version,
                resources = listOf(AddonResource(resource, listOf("movie"))),
                types = listOf("movie"),
                transportUrl = url,
            ),
        )
}
