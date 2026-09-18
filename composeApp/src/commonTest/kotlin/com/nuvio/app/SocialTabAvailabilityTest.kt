package com.nuvio.app

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What happens to a saved or launch-supplied tab when the social layer is off.
 *
 * The Social tab is the one navigation destination that can stop existing mid-life, and the
 * dangerous case is not the one on screen - it is the one restored from *outside* the current
 * composition. A saved `Social` tab with no navigation item to leave by is a route with nothing on
 * it and no way out, which is precisely the "hidden or dead social route" the preference is
 * supposed to make impossible.
 *
 * ⚠ These two functions are deliberately separate concerns and this file asserts both halves:
 * [AppScreenTab.fromName] keeps *parsing* "Social" in either state - it is persisted, and it is
 * half of the `NativeNavigationTab` mapping - while [coerceAvailableTab] decides whether it may be
 * *shown*. Folding availability into parsing would make a pure string function depend on a
 * repository.
 */
class SocialTabAvailabilityTest {

    @Test
    fun socialSurvivesWhenTheLayerIsOn() {
        assertEquals(
            AppScreenTab.Social,
            coerceAvailableTab(AppScreenTab.Social, socialEnabled = true),
        )
    }

    @Test
    fun socialFallsBackToHomeWhenTheLayerIsOff() {
        assertEquals(
            AppScreenTab.Home,
            coerceAvailableTab(AppScreenTab.Social, socialEnabled = false),
        )
    }

    @Test
    fun noOtherTabIsAffectedInEitherState() {
        AppScreenTab.entries.filter { it != AppScreenTab.Social }.forEach { tab ->
            assertEquals(tab, coerceAvailableTab(tab, socialEnabled = true), tab.name)
            assertEquals(tab, coerceAvailableTab(tab, socialEnabled = false), tab.name)
        }
    }

    @Test
    fun parsingIsNotAvailability() {
        // `Social` must keep parsing in both states: saved tab state and the native navigation
        // bridge both round-trip the name, and a parse that answered `Home` would silently
        // rewrite a saved tab the user could otherwise get back when they re-enable the feature.
        assertEquals(AppScreenTab.Social, AppScreenTab.fromName("Social"))
        assertEquals(AppScreenTab.Social, AppScreenTab.fromName("social"))
    }

    @Test
    fun anUnknownNameStillAnswersHome() {
        assertEquals(AppScreenTab.Home, AppScreenTab.fromName("Trakt"))
        assertEquals(AppScreenTab.Home, AppScreenTab.fromName(""))
    }

    @Test
    fun coercionIsIdempotent() {
        // Applied at restore and again whenever the preference changes, so running twice must not
        // walk any further than running once.
        val once = coerceAvailableTab(AppScreenTab.Social, socialEnabled = false)
        assertEquals(once, coerceAvailableTab(once, socialEnabled = false))
    }
}
