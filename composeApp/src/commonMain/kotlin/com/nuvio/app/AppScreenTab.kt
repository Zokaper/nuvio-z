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

sealed interface NavigationIntent {
    data class Tab(
        val tab: AppScreenTab,
        val librarySubDestination: LibrarySubDestination? = null,
    ) : NavigationIntent

    companion object {
        fun fromTab(tab: AppScreenTab): NavigationIntent = when (tab) {
            AppScreenTab.Downloads -> Tab(AppScreenTab.Library, LibrarySubDestination.Downloads)
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
 * [AppScreenTab.Downloads] is unified into [AppScreenTab.Library] across platforms to preserve
 * mobile bottom navigation constraints (including the iOS 5-tab limit).
 */
fun coerceAvailableTab(tab: AppScreenTab, socialEnabled: Boolean): AppScreenTab = when {
    tab == AppScreenTab.Downloads -> AppScreenTab.Library
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
