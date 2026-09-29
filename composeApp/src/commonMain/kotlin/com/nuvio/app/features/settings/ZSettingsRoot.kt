package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.build.AppVersionPolicy
import com.nuvio.app.core.build.NuvioZVersion
import com.nuvio.app.core.ui.NuvioSegment
import com.nuvio.app.core.ui.NuvioSegmentedChoice
import com.nuvio.app.core.ui.floatingNavigationGlowSupported
import com.nuvio.app.core.ui.isLiquidGlassNativeTabBarSupported
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.downloads.DownloadPolicyRepository
import com.nuvio.app.features.downloads.downloadModeName
import com.nuvio.app.features.downloads.forDownloads
import com.nuvio.app.features.playback.playbackModeName
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.setup.AdvancedSetupBadge
import com.nuvio.app.features.setup.AdvancedSetupLauncher
import com.nuvio.app.features.social.SocialFeaturePreferencesRepository
import com.nuvio.app.isDesktop
import com.nuvio.app.isIos
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

// String keys by wildcard: this file reads about forty, most of them upstream's own row copy.

/**
 * The Nuvio Z Settings hub (setup + settings pass, plan §1).
 *
 * ## Why a second root rather than an edited one
 *
 * The earlier settings re-layout (C2) edited upstream's pages in place and became the named
 * anti-pattern in `Docs/UPSTREAM.md`: every upstream sync re-fought it. This pass reorganises at the
 * **hub** only. Upstream's `settingsRootContent` stays untouched (and unused); `SettingsScreen.kt`
 * calls [zSettingsRootContent] at its two call sites, and every page it links to is upstream's own
 * page, re-parented at most. The two Z pages - [SettingsPage.Navigation] and [SettingsPage.About] -
 * hold rows that had no home of their own.
 *
 * | Section | Rows |
 * | --- | --- |
 * | Watching | Playback (mode as its description), Downloads (derived mode) |
 * | Look & feel | Appearance, Navigation (hidden where it would be empty: iOS before 26) |
 * | Content & services | Sources & addons, Integrations (now with Trakt & Simkl) |
 * | Profile & social | Account, Switch profile, Social, Notifications (mobile) |
 * | Setup & about | Advanced Setup [New], Run Initial Setup again, What's New, About |
 * | Advanced | Show advanced settings, Advanced |
 *
 * On a phone every section is listed; on a tablet or desktop window each section is one entry of
 * the rail ([ZSettingsSection]) and the root shows that section only.
 */
internal enum class ZSettingsSection(
    val labelRes: StringResource,
    val railLabelRes: StringResource,
    val icon: ImageVector,
) {
    Watching(Res.string.zsettings_section_watching, Res.string.zsettings_rail_watching, Icons.Rounded.PlayArrow),
    LookAndFeel(Res.string.zsettings_section_look, Res.string.zsettings_rail_look, Icons.Rounded.Palette),
    ContentAndServices(Res.string.zsettings_section_content, Res.string.zsettings_rail_content, Icons.Rounded.Extension),
    ProfileAndSocial(Res.string.zsettings_section_profile, Res.string.zsettings_rail_profile, Icons.Rounded.People),
    SetupAndAbout(Res.string.zsettings_section_setup, Res.string.zsettings_rail_setup, Icons.Rounded.Info),
    Advanced(Res.string.compose_settings_root_advanced_section, Res.string.compose_settings_page_advanced, Icons.Rounded.Tune),
}

/** The rail entry a page belongs to, so a tablet opening a page highlights the right section. */
internal fun zSettingsSectionFor(page: SettingsPage): ZSettingsSection = when (page) {
    SettingsPage.Root,
    SettingsPage.Playback,
    SettingsPage.Subtitles,
    -> ZSettingsSection.Watching
    SettingsPage.Appearance,
    SettingsPage.HoverPreview,
    SettingsPage.Streams,
    SettingsPage.ContinueWatching,
    SettingsPage.PosterCustomization,
    SettingsPage.Homescreen,
    SettingsPage.MetaScreen,
    SettingsPage.Navigation,
    -> ZSettingsSection.LookAndFeel
    SettingsPage.ContentDiscovery,
    SettingsPage.Addons,
    SettingsPage.Plugins,
    SettingsPage.Integrations,
    SettingsPage.TmdbEnrichment,
    SettingsPage.MdbListRatings,
    SettingsPage.Debrid,
    SettingsPage.TraktAuthentication,
    -> ZSettingsSection.ContentAndServices
    SettingsPage.Account,
    SettingsPage.Social,
    SettingsPage.Notifications,
    -> ZSettingsSection.ProfileAndSocial
    SettingsPage.About,
    SettingsPage.SupportersContributors,
    SettingsPage.LicensesAttributions,
    -> ZSettingsSection.SetupAndAbout
    SettingsPage.Advanced -> ZSettingsSection.Advanced
}

/** A rail selection restored by name; a stale upstream category name falls back to the first. */
internal fun zSettingsSectionForSavedName(name: String?): ZSettingsSection =
    ZSettingsSection.entries.firstOrNull { it.name == name } ?: ZSettingsSection.Watching

/**
 * The navigation rows left Appearance → Display for their own page. Read by the one guard in
 * `AppearanceSettingsPage.kt`, so the move is a single switch rather than deleted upstream code.
 */
internal const val zNavigationHasOwnPage: Boolean = true

/**
 * Whether the Navigation page has anything to show here: Android's bar, the desktop layout, or iOS
 * 26's Liquid Glass tab bar. On iOS before 26 there is nothing, so the row is hidden (plan §1).
 */
internal fun zNavigationPageAvailable(): Boolean =
    !isIos || isLiquidGlassNativeTabBarSupported()

internal fun LazyListScope.zSettingsRootContent(
    isTablet: Boolean,
    /** The rail's section on a tablet; null lists every section (phone). */
    section: ZSettingsSection?,
    onPageChange: (SettingsPage) -> Unit,
    onDownloadsClick: () -> Unit,
    onSwitchProfileClick: (() -> Unit)?,
    onWhatsNewClick: (() -> Unit)?,
    onRunSetupAgainClick: (() -> Unit)?,
    showDownloadsEntry: Boolean,
    showNotificationsEntry: Boolean,
) {
    fun shows(target: ZSettingsSection) = section == null || section == target

    if (shows(ZSettingsSection.Watching)) {
        item(key = "z-watching") {
            val playerSettings by remember {
                PlayerSettingsRepository.ensureLoaded()
                PlayerSettingsRepository.uiState
            }.collectAsStateWithLifecycle()
            val downloadPolicy by remember {
                DownloadPolicyRepository.ensureLoaded()
                DownloadPolicyRepository.policy
            }.collectAsStateWithLifecycle()
            ZSection(ZSettingsSection.Watching, isTablet) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_playback),
                    // The mode is the one playback answer worth reading at a glance; it used to be
                    // a second row under Account that opened a duplicate dialog.
                    description = playbackModeName(playerSettings.playbackMode),
                    icon = Icons.Rounded.PlayArrow,
                    isTablet = isTablet,
                    onClick = { onPageChange(SettingsPage.Playback) },
                )
                if (showDownloadsEntry) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.downloads_settings_title),
                        // The stored mode, or the one Playback Mode implies while there is none -
                        // exactly what a download would use right now.
                        description = downloadModeName(downloadPolicy.effectiveMode(playerSettings.playbackMode.forDownloads())),
                        icon = Icons.Rounded.CloudDownload,
                        isTablet = isTablet,
                        onClick = onDownloadsClick,
                    )
                }
            }
        }
    }

    if (shows(ZSettingsSection.LookAndFeel)) {
        item(key = "z-look") {
            ZSection(ZSettingsSection.LookAndFeel, isTablet) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.zsettings_appearance),
                    description = stringResource(Res.string.zsettings_appearance_description),
                    icon = Icons.Rounded.Palette,
                    isTablet = isTablet,
                    onClick = { onPageChange(SettingsPage.Appearance) },
                )
                if (zNavigationPageAvailable()) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.zsettings_navigation),
                        description = stringResource(zNavigationDescription()),
                        icon = Icons.Rounded.Explore,
                        isTablet = isTablet,
                        onClick = { onPageChange(SettingsPage.Navigation) },
                    )
                }
            }
        }
    }

    if (shows(ZSettingsSection.ContentAndServices)) {
        item(key = "z-content") {
            ZSection(ZSettingsSection.ContentAndServices, isTablet) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.zsettings_sources),
                    description = stringResource(Res.string.compose_settings_root_content_discovery_description),
                    icon = Icons.Rounded.Extension,
                    isTablet = isTablet,
                    onClick = { onPageChange(SettingsPage.ContentDiscovery) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_integrations),
                    description = stringResource(Res.string.zsettings_integrations_description),
                    icon = Icons.Rounded.Link,
                    isTablet = isTablet,
                    onClick = { onPageChange(SettingsPage.Integrations) },
                )
            }
        }
    }

    if (shows(ZSettingsSection.ProfileAndSocial)) {
        item(key = "z-profile") {
            val socialPreferences by remember {
                SocialFeaturePreferencesRepository.ensureLoaded()
                SocialFeaturePreferencesRepository.uiState
            }.collectAsStateWithLifecycle()
            ZSection(ZSettingsSection.ProfileAndSocial, isTablet) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_account),
                    description = stringResource(Res.string.compose_settings_root_account_description),
                    icon = Icons.Rounded.AccountCircle,
                    isTablet = isTablet,
                    onClick = { onPageChange(SettingsPage.Account) },
                )
                if (onSwitchProfileClick != null) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.compose_settings_root_switch_profile_title),
                        description = stringResource(Res.string.compose_settings_root_switch_profile_description),
                        icon = Icons.Rounded.People,
                        isTablet = isTablet,
                        onClick = onSwitchProfileClick,
                    )
                }
                SettingsGroupDivider(isTablet = isTablet)
                // ⚠ Always listed, in both states: it is the only way back into social once it is off.
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_social_title),
                    description = stringResource(if (socialPreferences.enabled) Res.string.advanced_setup_on else Res.string.advanced_setup_off),
                    icon = Icons.Rounded.People,
                    isTablet = isTablet,
                    onClick = { onPageChange(SettingsPage.Social) },
                )
                if (showNotificationsEntry) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.compose_settings_page_notifications),
                        description = stringResource(Res.string.compose_settings_root_notifications_description),
                        icon = Icons.Rounded.Notifications,
                        isTablet = isTablet,
                        onClick = { onPageChange(SettingsPage.Notifications) },
                    )
                }
            }
        }
    }

    if (shows(ZSettingsSection.SetupAndAbout)) {
        item(key = "z-setup") {
            val opened by AdvancedSetupBadge.opened.collectAsStateWithLifecycle()
            val profileId = ProfileRepository.activeProfileId
            LaunchedEffect(profileId) { AdvancedSetupBadge.refresh(profileId) }
            ZSection(ZSettingsSection.SetupAndAbout, isTablet) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.advanced_setup_title),
                    description = stringResource(Res.string.zsettings_advanced_setup_description),
                    icon = Icons.Rounded.AutoAwesome,
                    isTablet = isTablet,
                    trailingContent = if (opened) null else { { ZNewBadge() } },
                    onClick = AdvancedSetupLauncher::open,
                )
                if (onRunSetupAgainClick != null) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.zsettings_run_initial_setup),
                        description = stringResource(Res.string.zsettings_run_initial_setup_description),
                        icon = Icons.Rounded.Replay,
                        isTablet = isTablet,
                        onClick = onRunSetupAgainClick,
                    )
                }
                if (onWhatsNewClick != null) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.whats_new_title),
                        description = stringResource(Res.string.whats_new_version, AppVersionPolicy.displayVersionName),
                        icon = Icons.Rounded.NewReleases,
                        isTablet = isTablet,
                        onClick = onWhatsNewClick,
                    )
                }
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_category_about),
                    description = stringResource(Res.string.zsettings_about_description),
                    icon = Icons.Rounded.Info,
                    isTablet = isTablet,
                    onClick = { onPageChange(SettingsPage.About) },
                )
            }
        }
    }

    if (shows(ZSettingsSection.Advanced)) {
        item(key = "z-advanced") {
            // The stored value, not the search-reveal-augmented `LocalShowAdvancedSettings`.
            val playerSettings by remember {
                PlayerSettingsRepository.ensureLoaded()
                PlayerSettingsRepository.uiState
            }.collectAsStateWithLifecycle()
            ZSection(ZSettingsSection.Advanced, isTablet) {
                // Never advanced itself: hiding the way back would trap whoever turned it off.
                SettingsSwitchRow(
                    title = stringResource(Res.string.compose_settings_root_show_advanced),
                    description = stringResource(Res.string.compose_settings_root_show_advanced_description),
                    checked = playerSettings.showAdvancedSettings,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setShowAdvancedSettings,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_advanced),
                    description = stringResource(Res.string.compose_settings_root_advanced_description),
                    icon = Icons.Rounded.Tune,
                    isTablet = isTablet,
                    onClick = { onPageChange(SettingsPage.Advanced) },
                )
            }
        }
    }

    if (section == null) {
        item(key = "z-footer") { ZSettingsFooter(isTablet = isTablet) }
    }
}

@Composable
private fun ZSection(
    section: ZSettingsSection,
    isTablet: Boolean,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    SettingsSection(title = stringResource(section.labelRes), isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet, content = content)
    }
}

@Composable
private fun ZNewBadge() {
    val tokens = MaterialTheme.nuvio
    Surface(
        color = tokens.colors.accent,
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier.padding(horizontal = 6.dp),
    ) {
        Text(
            text = stringResource(Res.string.zsettings_new_badge),
            style = MaterialTheme.typography.labelSmall,
            color = tokens.colors.onAccent,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

private fun zNavigationDescription(): StringResource = when {
    isDesktop -> Res.string.zsettings_navigation_description_desktop
    isIos -> Res.string.zsettings_navigation_description_ios
    else -> Res.string.zsettings_navigation_description_android
}

// --- Navigation page -----------------------------------------------------------------------

/**
 * The navigation choices, gathered from Appearance → Display (plan §2): Android's floating bar
 * with its real preview, style and glow; desktop's sidebar or top bar and its style; iOS 26's
 * Liquid Glass tab bar - the row that was lost in the upstream convergence, restored here.
 */
internal fun LazyListScope.zNavigationSettingsContent(isTablet: Boolean) {
    item(key = "z-navigation") {
        val navBarStyle by remember {
            ThemeSettingsRepository.ensureLoaded()
            ThemeSettingsRepository.navBarStyle
        }.collectAsStateWithLifecycle()
        val glowEnabled by ThemeSettingsRepository.navBarGlowEnabled.collectAsStateWithLifecycle()
        val liquidGlass by ThemeSettingsRepository.liquidGlassNativeTabBarEnabled.collectAsStateWithLifecycle()
        val desktopLayout by ThemeSettingsRepository.desktopNavigationLayout.collectAsStateWithLifecycle()

        SettingsSection(title = stringResource(Res.string.zsettings_navigation).uppercase(), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                when {
                    isIos -> {
                        if (isLiquidGlassNativeTabBarSupported()) {
                            SettingsSwitchRow(
                                title = stringResource(Res.string.settings_appearance_liquid_glass),
                                description = stringResource(Res.string.settings_appearance_liquid_glass_description),
                                checked = liquidGlass,
                                isTablet = isTablet,
                                onCheckedChange = ThemeSettingsRepository::setLiquidGlassNativeTabBar,
                            )
                        }
                    }
                    isDesktop -> {
                        if (isTablet) {
                            ZChoiceRow(
                                title = stringResource(Res.string.settings_appearance_desktop_navigation),
                                description = stringResource(Res.string.advanced_setup_desktop_nav_detail),
                                isTablet = isTablet,
                                segments = DesktopNavigationLayout.entries.map { NuvioSegment(stringResource(it.labelRes), it) },
                                selected = desktopLayout,
                                onSelected = ThemeSettingsRepository::setDesktopNavigationLayout,
                            )
                            SettingsGroupDivider(isTablet = isTablet)
                        }
                        val sidebar = isTablet && desktopLayout == DesktopNavigationLayout.Sidebar
                        ZChoiceRow(
                            title = stringResource(
                                if (sidebar) Res.string.settings_appearance_sidebar_style else Res.string.settings_appearance_top_bar_style,
                            ),
                            description = stringResource(Res.string.zsettings_navigation_style_desktop),
                            isTablet = isTablet,
                            segments = listOf(NavBarStyle.ADAPTIVE, NavBarStyle.EXPANDED, NavBarStyle.COMPACT)
                                .map { NuvioSegment(stringResource(it.labelRes), it) },
                            selected = navBarStyle,
                            onSelected = ThemeSettingsRepository::setNavBarStyle,
                        )
                    }
                    else -> {
                        // A phone-class tablet always uses the compact floating bar; the preview
                        // shows that and the style choice is not offered - the sheet's rule.
                        val effectiveStyle = if (isTablet) NavBarStyle.COMPACT else navBarStyle
                        Column(Modifier.padding(vertical = 8.dp)) {
                            NavigationBarPreview(effectiveStyle, isTablet, glowEnabled)
                        }
                        if (!isTablet) {
                            SettingsGroupDivider(isTablet = isTablet)
                            ZChoiceRow(
                                title = stringResource(Res.string.settings_appearance_nav_bar_style),
                                description = stringResource(Res.string.zsettings_navigation_style_android),
                                isTablet = isTablet,
                                segments = NavBarStyle.entries.map { NuvioSegment(stringResource(it.labelRes), it) },
                                selected = navBarStyle,
                                onSelected = ThemeSettingsRepository::setNavBarStyle,
                            )
                        }
                        if (floatingNavigationGlowSupported && effectiveStyle != NavBarStyle.CLASSIC) {
                            SettingsGroupDivider(isTablet = isTablet)
                            SettingsSwitchRow(
                                title = stringResource(Res.string.settings_nav_bar_glow),
                                description = stringResource(Res.string.settings_nav_bar_glow_description),
                                checked = glowEnabled,
                                isTablet = isTablet,
                                onCheckedChange = ThemeSettingsRepository::setNavBarGlowEnabled,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A titled one-row choice. Z's own rather than upstream's `SettingsChipRow`, which exists in the
 * mobile repository only: this file is byte-identical in both, so it draws with the shared
 * `NuvioSegmentedChoice` the setup flows already use.
 */
@Composable
private fun <T> ZChoiceRow(
    title: String,
    description: String,
    isTablet: Boolean,
    segments: List<NuvioSegment<T>>,
    selected: T,
    onSelected: (T) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = if (isTablet) 16.dp else 14.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textMuted,
        )
        Spacer(Modifier.height(10.dp))
        NuvioSegmentedChoice(
            segments = segments,
            selected = selected,
            onSelected = onSelected,
            compact = segments.size > 3,
        )
    }
}

// --- About page ----------------------------------------------------------------------------

/**
 * About (plan §1): what used to be the root's About section, minus the rows that moved to Setup &
 * about. Supporters, privacy, licenses, updates and the debug test banner, then the version.
 */
internal fun LazyListScope.zAboutSettingsContent(
    isTablet: Boolean,
    onSupportersContributorsClick: () -> Unit,
    onLicensesAttributionsClick: () -> Unit,
    onCheckForUpdatesClick: (() -> Unit)?,
    onTestUpdateBannerClick: (() -> Unit)?,
    showSupportersContributorsPage: Boolean,
) {
    item(key = "z-about") {
        val uriHandler = LocalUriHandler.current
        SettingsSection(title = stringResource(Res.string.compose_settings_root_about_section), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                if (showSupportersContributorsPage) {
                    SettingsNavigationRow(
                        title = stringResource(Res.string.compose_settings_page_supporters_contributors),
                        description = stringResource(Res.string.about_supporters_contributors_subtitle),
                        icon = Icons.Rounded.Favorite,
                        isTablet = isTablet,
                        onClick = onSupportersContributorsClick,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                }
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_privacy_policy),
                    description = stringResource(Res.string.compose_settings_root_privacy_policy_description),
                    icon = Icons.Rounded.Policy,
                    isTablet = isTablet,
                    onClick = { uriHandler.openUri(ZPrivacyPolicyUrl) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_licenses_attributions),
                    description = stringResource(Res.string.about_licenses_attributions_subtitle),
                    icon = Icons.Rounded.Info,
                    isTablet = isTablet,
                    onClick = onLicensesAttributionsClick,
                )
                if (onCheckForUpdatesClick != null) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.compose_settings_root_check_updates_title),
                        description = stringResource(Res.string.compose_settings_root_check_updates_description),
                        icon = Icons.Rounded.CloudDownload,
                        isTablet = isTablet,
                        onClick = onCheckForUpdatesClick,
                    )
                }
                if (onTestUpdateBannerClick != null) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.updates_debug_test_title),
                        description = stringResource(Res.string.updates_debug_test_description),
                        icon = Icons.Rounded.BugReport,
                        isTablet = isTablet,
                        onClick = onTestUpdateBannerClick,
                    )
                }
            }
        }
    }
    item(key = "z-about-footer") { ZSettingsFooter(isTablet = isTablet) }
}

/** Upstream's privacy policy link (private to `SettingsRootPage.kt`, so repeated here). */
private const val ZPrivacyPolicyUrl = "https://nuvio.tv/privacy-policy"

/** The wordmark and version lines upstream's root ends with, unchanged. */
@Composable
private fun ZSettingsFooter(isTablet: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = if (isTablet) 20.dp else 16.dp),
    ) {
        MemberBrandWordmark(
            height = if (isTablet) 30.dp else 26.dp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(modifier = Modifier.height(if (isTablet) 10.dp else 8.dp))
        listOfNotNull(
            stringResource(Res.string.compose_about_made_with),
            stringResource(
                Res.string.compose_about_version_format,
                AppVersionPolicy.displayVersionName,
                AppVersionPolicy.displayVersionCode,
            ),
            NuvioZVersion.vanillaBaseVersion(AppVersionPolicy.displayVersionName)?.let {
                stringResource(Res.string.compose_about_based_on_version_format, it)
            },
        ).forEach { line ->
            Text(
                text = line,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
