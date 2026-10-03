package com.nuvio.app.features.home.components

import androidx.compose.foundation.lazy.LazyListItemInfo
import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeHeroSectionTest {

    private class Item(
        override val index: Int,
        override val key: Any,
        override val offset: Int,
        override val size: Int,
    ) : LazyListItemInfo {
        override val contentType: Any? = null
    }

    // The Home list is [offline banner (zero height), hero, rows...]. `heroHeightPx` is 1000.
    private fun home(bannerOffset: Int, heroOffset: Int): List<LazyListItemInfo> = listOf(
        Item(0, "offline_downloads_banner", bannerOffset, 0),
        Item(1, HOME_HERO_ITEM_KEY, heroOffset, 1000),
        Item(2, "row", heroOffset + 1000, 300),
    )

    @Test
    fun `hero at rest below the banner gap has not scrolled`() {
        assertEquals(0f, heroScrollOffsetPx(home(bannerOffset = 0, heroOffset = 24), heroHeightPx = 1000f))
    }

    @Test
    fun `hero at index 1 reports its real partial offset and not the full hero height`() {
        // Debug 83: banner scrolled out, hero is the first visible item (index 1) and 60 px up.
        // The old `firstVisibleItemIndex > 0` rule answered 1000 here, shoving the backdrop ~190 px.
        val visible = listOf(
            Item(1, HOME_HERO_ITEM_KEY, -60, 1000),
            Item(2, "row", 940, 300),
        )
        assertEquals(60f, heroScrollOffsetPx(visible, heroHeightPx = 1000f))
    }

    @Test
    fun `hero scrolled partway tracks the scroll while banner is still the first visible item`() {
        assertEquals(10f, heroScrollOffsetPx(home(bannerOffset = -14, heroOffset = -10), heroHeightPx = 1000f))
    }

    @Test
    fun `hero past the top of the list reports the full hero height`() {
        val visible = listOf(Item(2, "row", -200, 300), Item(3, "row2", 116, 300))
        assertEquals(1000f, heroScrollOffsetPx(visible, heroHeightPx = 1000f))
    }

    @Test
    fun `hero offset never exceeds the hero height`() {
        val visible = listOf(Item(1, HOME_HERO_ITEM_KEY, -1100, 1000), Item(2, "row", -100, 300))
        assertEquals(1000f, heroScrollOffsetPx(visible, heroHeightPx = 1000f))
    }

    @Test
    fun `an empty layout has not scrolled`() {
        assertEquals(0f, heroScrollOffsetPx(emptyList(), heroHeightPx = 1000f))
    }

    @Test
    fun `first and last indicators select adjacent pages across the loop`() {
        assertEquals(79, heroPageForItem(currentPage = 80, itemIndex = 7, itemCount = 8))
        assertEquals(88, heroPageForItem(currentPage = 87, itemIndex = 0, itemCount = 8))
    }

    @Test
    fun `indicators keep the current page when selecting the active title`() {
        assertEquals(83, heroPageForItem(currentPage = 83, itemIndex = 3, itemCount = 8))
    }

    @Test
    fun `indicators select the closest occurrence of a title`() {
        assertEquals(85, heroPageForItem(currentPage = 83, itemIndex = 5, itemCount = 8))
        assertEquals(81, heroPageForItem(currentPage = 83, itemIndex = 1, itemCount = 8))
    }

    @Test
    fun `mobile hero height stays compact without continue watching`() {
        val layout = homeHeroLayout(
            maxWidthDp = 390f,
            viewportHeightDp = 844f,
        )

        assertEquals(false, layout.isTablet)
        assertEquals(452.4f, layout.heroHeight.value, 0.001f)
    }

    @Test
    fun `tablet hero height remains width driven even with viewport height`() {
        val layout = homeHeroLayout(
            maxWidthDp = 840f,
            viewportHeightDp = 1200f,
        )

        assertEquals(true, layout.isTablet)
        assertEquals(386.4f, layout.heroHeight.value, 0.001f)
    }

    @Test
    fun `desktop hero keeps the existing full bleed layout at sixteen by nine`() {
        val layout = homeHeroLayout(
            maxWidthDp = 2560f,
            viewportHeightDp = 1440f,
            preferDesktopLayout = true,
        )

        assertEquals(660f, layout.heroHeight.value, 0.001f)
        assertEquals(2560f, layout.contentContainerMaxWidth.value, 0.001f)
        assertEquals(32f, layout.contentHorizontalPadding.value, 0.001f)
        assertEquals(40f, layout.contentVerticalPadding.value, 0.001f)
        assertEquals(160f, layout.topFadeHeight.value, 0.001f)
        assertEquals(300f, layout.bottomFadeHeight.value, 0.001f)
        assertEquals(1f, layout.backgroundMotionStrength, 0.001f)
    }

    @Test
    fun `desktop hero becomes full bleed and full height at thirty two by nine`() {
        val layout = homeHeroLayout(
            maxWidthDp = 3840f,
            viewportHeightDp = 1080f,
            preferDesktopLayout = true,
        )

        assertEquals(1080f, layout.heroHeight.value, 0.001f)
        assertEquals(3840f, layout.contentContainerMaxWidth.value, 0.001f)
        assertEquals(120f, layout.contentHorizontalPadding.value, 0.001f)
        assertEquals(192f, layout.contentVerticalPadding.value, 0.001f)
        assertEquals(160f, layout.topFadeHeight.value, 0.001f)
        assertEquals(300f, layout.bottomFadeHeight.value, 0.001f)
        assertEquals(0f, layout.backgroundMotionStrength, 0.001f)
    }

    @Test
    fun `mobile hero height leaves room for continue watching card section`() {
        val viewportHeight = 844f
        val continueWatchingLayout = rememberContinueWatchingLayout(maxWidthDp = 390f)
        val continueWatchingHeight = continueWatchingSectionHeightEstimate(
            style = ContinueWatchingSectionStyle.Card,
            layout = continueWatchingLayout,
            basePosterWidthDp = 110,
        )
        val reserveHeight = continueWatchingHeroViewportReserveHeight(
            style = ContinueWatchingSectionStyle.Card,
            layout = continueWatchingLayout,
            basePosterWidthDp = 110,
        )
        val layout = homeHeroLayout(
            maxWidthDp = 390f,
            viewportHeightDp = viewportHeight,
            mobileBelowSectionHeightHintDp = reserveHeight.value,
        )

        assertEquals(24f, viewportHeight - layout.heroHeight.value - continueWatchingHeight.value, 0.001f)
    }

    @Test
    fun `mobile hero can shrink below default minimum to fit short viewport`() {
        val layout = homeHeroLayout(
            maxWidthDp = 390f,
            viewportHeightDp = 568f,
            mobileBelowSectionHeightHintDp = 300f,
        )

        assertEquals(false, layout.isTablet)
        assertEquals(268f, layout.heroHeight.value, 0.001f)
    }
}
