package com.nuvio.app.features.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppUpdateAssetSelectionTest {
    @Test
    fun `selects current architecture installer before other installer assets`() {
        val selected = selectBestUpdateAsset(
            assets = listOf(
                asset("Nuvio-0.2.0-windows-arm64.msi"),
                asset("Nuvio-0.2.0-windows-x64.msi"),
                asset("Nuvio-0.2.0.exe"),
            ),
            selector = AppUpdateAssetSelector(
                fileExtensions = listOf(".msi", ".exe"),
                preferredNameFragments = listOf("x64", "windows"),
                fallbackNameFragments = listOf("universal", "all"),
            ),
        )

        assertEquals("Nuvio-0.2.0-windows-x64.msi", selected?.name)
    }

    @Test
    fun `selects Apple Silicon DMG from a mixed desktop debug release`() {
        val selected = selectBestUpdateAsset(
            assets = listOf(
                asset("Nuvio-Z-Debug-Windows-x64-0.5.0-beta.20.msi"),
                asset("Nuvio-Z-Debug-macOS-arm64-0.5.0-beta.20.dmg"),
                asset("Nuvio-Z-Debug-macOS-x86_64-0.5.0-beta.20.dmg"),
            ),
            selector = AppUpdateAssetSelector(
                fileExtensions = listOf(".dmg", ".pkg"),
                preferredNameFragments = listOf("arm64", "aarch64", "macos", "mac", "darwin"),
                fallbackNameFragments = listOf("universal", "all"),
            ),
        )

        assertEquals("Nuvio-Z-Debug-macOS-arm64-0.5.0-beta.20.dmg", selected?.name)
    }

    @Test
    fun `ignores unrelated octet stream assets when desktop selector has no content types`() {
        val selected = selectBestUpdateAsset(
            assets = listOf(
                asset("checksums.txt", contentType = "application/octet-stream"),
                asset("Nuvio-0.2.0-windows-x64.zip", contentType = "application/octet-stream"),
                asset("Nuvio-0.2.0-windows-x64.msi", contentType = "application/octet-stream"),
            ),
            selector = AppUpdateAssetSelector(
                fileExtensions = listOf(".msi", ".exe"),
                preferredNameFragments = listOf("x64"),
            ),
        )

        assertEquals("Nuvio-0.2.0-windows-x64.msi", selected?.name)
    }

    @Test
    fun `falls back to universal installer when architecture is not present`() {
        val selected = selectBestUpdateAsset(
            assets = listOf(
                asset("Nuvio-0.2.0-universal.msi"),
                asset("Nuvio-0.2.0-arm64.msi"),
            ),
            selector = AppUpdateAssetSelector(
                fileExtensions = listOf(".msi"),
                preferredNameFragments = listOf("x64"),
                fallbackNameFragments = listOf("universal"),
            ),
        )

        assertEquals("Nuvio-0.2.0-universal.msi", selected?.name)
    }

    @Test
    fun `supports android apk content type when extension is missing`() {
        val selected = selectBestUpdateAsset(
            assets = listOf(
                asset("Nuvio-arm64", contentType = "application/vnd.android.package-archive"),
                asset("Nuvio-x86", contentType = "application/vnd.android.package-archive"),
            ),
            selector = AppUpdateAssetSelector(
                fileExtensions = listOf(".apk"),
                contentTypes = listOf("application/vnd.android.package-archive"),
                preferredNameFragments = listOf("x86"),
            ),
        )

        assertEquals("Nuvio-x86", selected?.name)
    }

    @Test
    fun `returns null when no asset matches the selector`() {
        val selected = selectBestUpdateAsset(
            assets = listOf(
                asset("Nuvio-0.2.0-windows-x64.zip"),
                asset("checksums.txt"),
            ),
            selector = AppUpdateAssetSelector(fileExtensions = listOf(".msi", ".exe")),
        )

        assertNull(selected)
    }

    private fun asset(
        name: String,
        contentType: String? = null,
    ): AppUpdateAssetCandidate =
        AppUpdateAssetCandidate(
            name = name,
            downloadUrl = "https://example.test/$name",
            contentType = contentType,
        )
}
