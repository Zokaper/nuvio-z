package com.nuvio.app

import androidx.compose.material.icons.filled.Home
import com.nuvio.app.core.ui.NativeNavigationTab

enum class AppScreenTab {
    Home,
    Search,
    Library,
    Downloads,
    Social,
    Settings,
    ;

    companion object {
        fun fromName(name: String): AppScreenTab =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Home
    }
}

enum class LibrarySubDestination {
    Library,
    Downloads,
    ;

    companion object {
        fun fromName(name: String?): LibrarySubDestination =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Library
    }
}

/**
 * Where Downloads lives (Phase 9, decided 2026-09-26). On phones it is Library's second tab: the
 * bottom bar has no room for it, and iOS allows five tabs. Desktop has the room, so there it is
 * its own sidebar destination - one screen, [AppScreenTab.Downloads], no Library switcher.
 */
internal val downloadsIsOwnDestination: Boolean get() = isDesktop

sealed interface NavigationIntent {
    data class Tab(
        val tab: AppScreenTab,
        val librarySubDestination: LibrarySubDestination? = null,
    ) : NavigationIntent

    companion object {
        fun fromTab(
            tab: AppScreenTab,
            downloadsOwnDestination: Boolean = downloadsIsOwnDestination,
        ): NavigationIntent = when {
            tab == AppScreenTab.Downloads && !downloadsOwnDestination ->
                Tab(AppScreenTab.Library, LibrarySubDestination.Downloads)
            else -> Tab(tab)
        }
    }
}

/**
 * The tab to actually show for a requested [tab].
 *
 * ⚠ **Separate from [AppScreenTab.fromName] on purpose.** Parsing answers "what did this string
 * mean"; availability answers "may we show it now". Folding the second into the first would make
 * a pure string function reach into a repository, and `Social` still has to *parse* in both
 * states - it is persisted in saved-tab state and it is half of the `NativeNavigationTab`
 * mapping, so the enum value can never simply go away.
 *
 * Applied wherever a tab arrives from outside the current composition - saved-tab restoration and
 * the launch `initialTab` - because that is where a tab whose surfaces no longer exist would
 * otherwise become a route with nothing on it. Without this, turning the social layer off and
 * relaunching lands on an empty Social tab that no navigation item can leave.
 *
 * On phones [AppScreenTab.Downloads] is shown as [AppScreenTab.Library]'s Downloads tab (the bottom
 * bar's room, and the iOS 5-tab limit); on desktop it is its own destination - see
 * [downloadsIsOwnDestination].
 */
fun coerceAvailableTab(
    tab: AppScreenTab,
    socialEnabled: Boolean,
    downloadsOwnDestination: Boolean = downloadsIsOwnDestination,
): AppScreenTab = when {
    tab == AppScreenTab.Downloads && !downloadsOwnDestination -> AppScreenTab.Library
    tab == AppScreenTab.Social && !socialEnabled -> AppScreenTab.Home
    else -> tab
}

internal fun AppScreenTab.toNativeNavigationTab(): NativeNavigationTab = when (this) {
    AppScreenTab.Home -> NativeNavigationTab.Home
    AppScreenTab.Search -> NativeNavigationTab.Search
    AppScreenTab.Library -> NativeNavigationTab.Library
    AppScreenTab.Downloads -> NativeNavigationTab.Library
    AppScreenTab.Social -> NativeNavigationTab.Social
    AppScreenTab.Settings -> NativeNavigationTab.Settings
}

internal fun NativeNavigationTab.toAppScreenTab(): AppScreenTab = when (this) {
    NativeNavigationTab.Home -> AppScreenTab.Home
    NativeNavigationTab.Search -> AppScreenTab.Search
    NativeNavigationTab.Library -> AppScreenTab.Library
    NativeNavigationTab.Downloads -> AppScreenTab.Downloads
    NativeNavigationTab.Social -> AppScreenTab.Social
    NativeNavigationTab.Settings -> AppScreenTab.Settings
}
