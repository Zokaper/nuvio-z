package com.nuvio.app.features.settings

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import com.nuvio.app.core.ui.NuvioTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The reorganised Settings hub (setup + settings pass) and the search that has to follow it.
 *
 * ⚠ Settings search fails silently: a row that moved but was not re-indexed still finds, then lands
 * on a page where the row no longer is. These pin every entry the move touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h900dp-port")
class ZSettingsHubTest {
    @get:Rule
    val compose = createComposeRule()

    private fun entries(
        liquidGlass: Boolean = false,
        runSetupAgain: Boolean = true,
        checkForUpdates: Boolean = true,
    ): List<SettingsSearchEntry> {
        var result = emptyList<SettingsSearchEntry>()
        compose.setContent {
            NuvioTheme {
                result = settingsSearchEntries(
                    isTablet = false,
                    pluginsEnabled = true,
                    downloadsEnabled = true,
                    notificationsEnabled = true,
                    externalPlayerSupported = true,
                    supportersContributorsPageEnabled = true,
                    accountDeletionEnabled = false,
                    personalMediaAddonCopyEnabled = false,
                    liquidGlassNativeTabBarSupported = liquidGlass,
                    switchProfileAvailable = true,
                    checkForUpdatesAvailable = checkForUpdates,
                    runSetupAgainAvailable = runSetupAgain,
                )
            }
        }
        compose.waitForIdle()
        return result
    }

    private fun List<SettingsSearchEntry>.byKey(key: String): SettingsSearchEntry =
        assertNotNull(firstOrNull { it.key == key }, "no search entry '$key'")

    @Test
    fun advancedSetupIsSearchableAndOpensTheHub() {
        val entry = entries().byKey("advanced-setup")
        assertEquals(SettingsSearchTarget.AdvancedSetup, entry.target)
        assertTrue(entry.searchableText.contains("advanced setup"))
    }

    @Test
    fun navigationRowsLandOnTheNavigationPageNotAppearance() {
        val all = entries()
        assertEquals(SettingsSearchTarget.Page(SettingsPage.Navigation), all.byKey("navigation").target)
        assertEquals(SettingsSearchTarget.Page(SettingsPage.Navigation), all.byKey("nav-bar-style").target)
        // Android only; a Liquid Glass entry would be a dead end on this platform.
        assertFalse(all.any { it.key == "liquid-glass" })
    }

    @Test
    fun movedRowsSayWhereTheyNowLive() {
        val all = entries()
        val rerun = all.byKey("run-setup-again")
        assertEquals("Run Initial Setup again", rerun.title)
        assertEquals(SettingsSearchTarget.RunSetupAgain, rerun.target)
        assertEquals(SettingsSearchTarget.Page(SettingsPage.About), all.byKey("about").target)
        assertEquals("About", all.byKey("check-updates").page)
        // The renamed pages carry their hub names everywhere they are mentioned.
        assertEquals("Appearance", all.byKey("layout").title)
        assertEquals("Sources & addons", all.byKey("content-discovery").title)
        assertFalse(all.any { it.page == "Layout" || it.page == "Content & Discovery" })
    }

    @Test
    fun runSetupAgainIsOnlyIndexedWhereItCanOpen() {
        assertFalse(entries(runSetupAgain = false).any { it.key == "run-setup-again" })
    }

    @Test
    fun trackingLivesUnderIntegrations() {
        assertEquals(SettingsPage.Integrations, SettingsPage.TraktAuthentication.parentPage)
        assertEquals(SettingsSearchTarget.Page(SettingsPage.TraktAuthentication), entries().byKey("tracking").target)
        assertEquals(ZSettingsSection.ContentAndServices, zSettingsSectionFor(SettingsPage.TraktAuthentication))
    }

    @Test
    fun everyPageTargetIsReachableFromTheHub() {
        // A page is reachable when its parent chain ends at Root: the hub links every top-level
        // page, and each child is opened from its parent.
        entries().mapNotNull { (it.target as? SettingsSearchTarget.Page)?.page }.distinct().forEach { page ->
            var current: SettingsPage? = page
            var steps = 0
            while (current != null && current != SettingsPage.Root && steps < 8) {
                current = current.parentPage
                steps++
            }
            assertTrue(page == SettingsPage.Root || current == SettingsPage.Root, "$page is not under Root")
        }
    }

    @Test
    fun theZPagesAreAppendedNeverInserted() {
        // Saved navigation state and `SettingsPageRoute` match pages by name, and upstream's order
        // must survive a sync: the two Z pages come last.
        val tail = SettingsPage.entries.takeLast(2)
        assertEquals(listOf(SettingsPage.Navigation, SettingsPage.About), tail)
    }

    @Test
    fun everyPageHasARailSection() {
        SettingsPage.entries.forEach { zSettingsSectionFor(it) }
        assertEquals(ZSettingsSection.Watching, zSettingsSectionForSavedName("General"))
        assertEquals(ZSettingsSection.SetupAndAbout, zSettingsSectionForSavedName("SetupAndAbout"))
    }
}
