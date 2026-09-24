package com.nuvio.app.navigation

import com.nuvio.app.AppScreenTab
import com.nuvio.app.core.ui.NativeNavigationTab
import com.nuvio.app.toAppScreenTab
import com.nuvio.app.toNativeNavigationTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Regression tests for navigation tab identity.
 *
 * Verifies that Downloads and Social maintain distinct identities across both
 * the Compose tab model (AppScreenTab) and the native platform bridge (NativeNavigationTab),
 * preventing the tab conflation and icon/title hijacking observed in Bug 6.
 */
class BottomNavItemIdentityTest {

    @Test
    fun appScreenTabHasDistinctDownloadsAndSocial() {
        assertNotEquals(AppScreenTab.Downloads, AppScreenTab.Social)
        assertEquals(AppScreenTab.Downloads, AppScreenTab.fromName("Downloads"))
        assertEquals(AppScreenTab.Downloads, AppScreenTab.fromName("downloads"))
        assertEquals(AppScreenTab.Social, AppScreenTab.fromName("Social"))
        assertEquals(AppScreenTab.Social, AppScreenTab.fromName("social"))
    }

    @Test
    fun nativeNavigationTabHasDistinctDownloadsAndSocial() {
        assertNotEquals(NativeNavigationTab.Downloads, NativeNavigationTab.Social)
        assertEquals(NativeNavigationTab.Downloads, NativeNavigationTab.fromName("Downloads"))
        assertEquals(NativeNavigationTab.Downloads, NativeNavigationTab.fromName("downloads"))
        assertEquals(NativeNavigationTab.Social, NativeNavigationTab.fromName("Social"))
        assertEquals(NativeNavigationTab.Social, NativeNavigationTab.fromName("social"))
    }

    @Test
    fun roundTripBetweenAppScreenTabAndNativeNavigationTab() {
        val allTabs = AppScreenTab.entries
        assertEquals(6, allTabs.size, "Expected 6 root tabs: Home, Search, Library, Downloads, Social, Settings")

        for (tab in allTabs) {
            val nativeTab = tab.toNativeNavigationTab()
            val roundTripTab = nativeTab.toAppScreenTab()
            val expected = if (tab == AppScreenTab.Downloads) AppScreenTab.Library else tab
            assertEquals(expected, roundTripTab, "Tab $tab failed canonical conversion")
        }
    }
}
