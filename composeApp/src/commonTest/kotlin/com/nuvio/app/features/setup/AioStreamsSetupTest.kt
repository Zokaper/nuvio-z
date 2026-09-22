package com.nuvio.app.features.setup

import com.nuvio.app.features.addons.AddAddonResult
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonResource
import com.nuvio.app.features.addons.ManagedAddon
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class AioStreamsSetupTest {

    @Test
    fun firstEnabledStreamAddonNameReturnsNullForEmptyOrNonStreamAddons() {
        val empty = emptyList<ManagedAddon>()
        assertNull(empty.firstEnabledStreamAddonName())

        val catalogOnly = listOf(
            managedAddon(
                id = "cinemeta",
                name = "Cinemeta",
                enabled = true,
                resources = listOf("catalog", "meta"),
            ),
            managedAddon(
                id = "subtitles",
                name = "OpenSubtitles",
                enabled = true,
                resources = listOf("subtitles"),
            ),
        )
        assertNull(catalogOnly.firstEnabledStreamAddonName())
    }

    @Test
    fun firstEnabledStreamAddonNameIgnoresDisabledStreamAddons() {
        val disabled = listOf(
            managedAddon(
                id = "aiostreams",
                name = "AIOStreams",
                enabled = false,
                resources = listOf("stream"),
            ),
        )
        assertNull(disabled.firstEnabledStreamAddonName())
    }

    @Test
    fun firstEnabledStreamAddonNameIdentifiesStreamCapableAddons() {
        val addons = listOf(
            managedAddon(
                id = "cinemeta",
                name = "Cinemeta",
                enabled = true,
                resources = listOf("catalog", "meta"),
            ),
            managedAddon(
                id = "torbox-stream",
                name = "AIOStreams + TorBox",
                enabled = true,
                resources = listOf("stream"),
            ),
        )

        assertEquals("AIOStreams + TorBox", addons.firstEnabledStreamAddonName())
    }

    @Test
    fun firstEnabledStreamAddonNameMatchesCaseInsensitiveAndRespectsCustomTitle() {
        val addons = listOf(
            managedAddon(
                id = "custom-streams",
                name = "Original Name",
                userSetName = "My Streams",
                enabled = true,
                resources = listOf("STREAM"),
            ),
        )

        assertEquals("My Streams", addons.firstEnabledStreamAddonName())
    }

    @Test
    fun installSetupSourceRejectsBlankUrlWithoutCallingInstaller() = runBlocking {
        var called = false
        val result = installSetupSource(
            rawUrl = "   ",
            emptyUrlMessage = "URL cannot be blank",
            installer = {
                called = true
                AddAddonResult.Error("Should not be called")
            },
        )

        assertEquals(SetupSourceInstallResult.Failed("URL cannot be blank"), result)
        assertEquals(false, called)
    }

    @Test
    fun installSetupSourceHandlesSuccessOutcome() = runBlocking {
        val manifest = testManifest(name = "AIOStreams Provider")
        val result = installSetupSource(
            rawUrl = "https://example.com/manifest.json",
            emptyUrlMessage = "Blank",
            installer = { AddAddonResult.Success(manifest) },
        )

        assertIs<SetupSourceInstallResult.Installed>(result)
        assertEquals("AIOStreams Provider", result.addonName)
    }

    @Test
    fun installSetupSourceHandlesErrorOutcome() = runBlocking {
        val result = installSetupSource(
            rawUrl = "https://example.com/bad-manifest.json",
            emptyUrlMessage = "Blank",
            installer = { AddAddonResult.Error("Network unreachable") },
        )

        assertIs<SetupSourceInstallResult.Failed>(result)
        assertEquals("Network unreachable", result.message)
    }

    private fun managedAddon(
        id: String,
        name: String,
        enabled: Boolean,
        resources: List<String>,
        userSetName: String? = null,
    ): ManagedAddon =
        ManagedAddon(
            manifestUrl = "https://example.com/$id/manifest.json",
            manifest = testManifest(id = id, name = name, resources = resources),
            userSetName = userSetName,
            enabled = enabled,
        )

    private fun testManifest(
        id: String = "test.addon",
        name: String = "Test Addon",
        resources: List<String> = listOf("stream"),
    ): AddonManifest =
        AddonManifest(
            id = id,
            name = name,
            description = "Test addon description",
            version = "1.0.0",
            resources = resources.map { AddonResource(name = it, types = listOf("movie", "series")) },
            types = listOf("movie", "series"),
            transportUrl = "https://example.com/$id/manifest.json",
        )
}
