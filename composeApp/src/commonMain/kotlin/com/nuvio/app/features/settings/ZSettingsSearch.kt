package com.nuvio.app.features.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.runtime.Composable
import com.nuvio.app.core.ui.floatingNavigationGlowSupported
import com.nuvio.app.isDesktop
import com.nuvio.app.isIos
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Settings search for the reorganised hub (setup + settings pass, plan §2).
 *
 * ⚠ **Search fails silently.** A row that moved but was never re-indexed still *finds* - and then
 * lands on the old page, where the row no longer is. `ZSettingsSearchTest` pins every entry this
 * touches, because nothing else would notice.
 *
 * Called once, at the end of upstream's `settingsSearchEntries`, so upstream's list stays as it is
 * and a sync only has to keep one line:
 * - the Z rows: Advanced Setup, the Navigation page and its rows, the About page;
 * - the moved rows retargeted: Run Initial Setup again and Check for updates now live under Setup &
 *   about / About, and the pages renamed for the hub ("Layout" is "Appearance", "Content &
 *   Discovery" is "Sources & addons") carry their new names so the result reads right.
 */
@Composable
internal fun zAmendSettingsSearchEntries(
    entries: MutableList<SettingsSearchEntry>,
    liquidGlassNativeTabBarSupported: Boolean,
    runSetupAgainAvailable: Boolean,
) {
    val setupSection = stringResource(Res.string.zsettings_section_setup)
    val setupCategory = stringResource(Res.string.zsettings_rail_setup)
    val lookCategory = stringResource(Res.string.zsettings_rail_look)
    val aboutPage = stringResource(Res.string.compose_settings_category_about)
    val appearancePage = stringResource(Res.string.zsettings_appearance)
    val sourcesPage = stringResource(Res.string.zsettings_sources)
    val navigationPage = stringResource(Res.string.zsettings_navigation)
    val oldLayoutPage = stringResource(Res.string.compose_settings_page_appearance)
    val oldContentPage = stringResource(Res.string.compose_settings_page_content_discovery)
    val runInitialSetupTitle = stringResource(Res.string.zsettings_run_initial_setup)
    val runInitialSetupDescription = stringResource(Res.string.zsettings_run_initial_setup_description)

    // The renamed pages: every entry that named them, as title or as the page it sits on.
    entries.updateEach { entry ->
        entry.copy(
            title = when (entry.title) {
                oldLayoutPage -> appearancePage
                oldContentPage -> sourcesPage
                else -> entry.title
            },
            page = when (entry.page) {
                oldLayoutPage -> appearancePage
                oldContentPage -> sourcesPage
                else -> entry.page
            },
        )
    }

    // Moved rows: they are found under their new section, and say so.
    entries.updateEach { entry ->
        when (entry.key) {
            "run-setup-again" -> entry.copy(
                title = runInitialSetupTitle,
                description = runInitialSetupDescription,
                page = setupSection,
                section = setupSection,
                category = setupCategory,
            )
            "check-updates" -> entry.copy(page = aboutPage, section = aboutPage, category = setupCategory)
            else -> entry
        }
    }
    if (!runSetupAgainAvailable) entries.removeAll { it.key == "run-setup-again" }

    fun add(key: String, title: String, description: String, page: String, section: String, category: String, target: SettingsSearchTarget, icon: androidx.compose.ui.graphics.vector.ImageVector) {
        entries += SettingsSearchEntry(
            key = key,
            title = title,
            description = description,
            page = page,
            section = section,
            category = category,
            icon = icon,
            target = target,
        )
    }

    add(
        key = "advanced-setup",
        title = stringResource(Res.string.advanced_setup_title),
        description = stringResource(Res.string.zsettings_advanced_setup_description),
        page = setupSection,
        section = setupSection,
        category = setupCategory,
        target = SettingsSearchTarget.AdvancedSetup,
        icon = Icons.Rounded.AutoAwesome,
    )
    add(
        key = "about",
        title = aboutPage,
        description = stringResource(Res.string.zsettings_about_description),
        page = aboutPage,
        section = setupSection,
        category = setupCategory,
        target = SettingsSearchTarget.Page(SettingsPage.About),
        icon = Icons.Rounded.Info,
    )

    if (!zNavigationPageAvailable()) return
    val toNavigation = SettingsSearchTarget.Page(SettingsPage.Navigation)
    add(
        key = "navigation",
        title = navigationPage,
        description = stringResource(
            when {
                isDesktop -> Res.string.zsettings_navigation_description_desktop
                isIos -> Res.string.zsettings_navigation_description_ios
                else -> Res.string.zsettings_navigation_description_android
            },
        ),
        page = navigationPage,
        section = stringResource(Res.string.zsettings_section_look),
        category = lookCategory,
        target = toNavigation,
        icon = Icons.Rounded.Explore,
    )
    when {
        isIos -> if (liquidGlassNativeTabBarSupported) {
            add(
                key = "liquid-glass",
                title = stringResource(Res.string.settings_appearance_liquid_glass),
                description = stringResource(Res.string.settings_appearance_liquid_glass_description),
                page = navigationPage,
                section = navigationPage,
                category = lookCategory,
                target = toNavigation,
                icon = Icons.Rounded.Explore,
            )
        }
        isDesktop -> {
            add(
                key = "desktop-navigation",
                title = stringResource(Res.string.settings_appearance_desktop_navigation),
                description = stringResource(Res.string.advanced_setup_desktop_nav_detail),
                page = navigationPage,
                section = navigationPage,
                category = lookCategory,
                target = toNavigation,
                icon = Icons.Rounded.Explore,
            )
            add(
                key = "nav-bar-style",
                title = stringResource(Res.string.settings_appearance_top_bar_style),
                description = stringResource(Res.string.settings_appearance_sidebar_style),
                page = navigationPage,
                section = navigationPage,
                category = lookCategory,
                target = toNavigation,
                icon = Icons.Rounded.Explore,
            )
        }
        else -> {
            add(
                key = "nav-bar-style",
                title = stringResource(Res.string.settings_appearance_nav_bar_style),
                description = stringResource(Res.string.zsettings_navigation_style_android),
                page = navigationPage,
                section = navigationPage,
                category = lookCategory,
                target = toNavigation,
                icon = Icons.Rounded.Explore,
            )
            if (floatingNavigationGlowSupported) {
                add(
                    key = "nav-bar-glow",
                    title = stringResource(Res.string.settings_nav_bar_glow),
                    description = stringResource(Res.string.settings_nav_bar_glow_description),
                    page = navigationPage,
                    section = navigationPage,
                    category = lookCategory,
                    target = toNavigation,
                    icon = Icons.Rounded.Explore,
                )
            }
        }
    }
}

/**
 * In-place map. Not `MutableList.replaceAll`: that is a JVM API, and on Kotlin/Native it needs an
 * `ExperimentalNativeApi` opt-in - which is what failed the first iOS build of this file.
 */
private inline fun MutableList<SettingsSearchEntry>.updateEach(transform: (SettingsSearchEntry) -> SettingsSearchEntry) {
    for (index in indices) this[index] = transform(this[index])
}
