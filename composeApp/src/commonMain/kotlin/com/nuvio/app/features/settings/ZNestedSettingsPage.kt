package com.nuvio.app.features.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioConsumePointerEvents
import com.nuvio.app.features.mdblist.MdbListSettingsRepository
import com.nuvio.app.features.simkl.SimklAuthRepository
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.tracking.TrackingSettingsRepository
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.trakt.TraktCommentsSettings
import com.nuvio.app.navigation.LocalNativeNavigationBarHidden
import com.nuvio.app.navigation.LocalUseNativeNavigation
import org.jetbrains.compose.resources.stringResource

/**
 * One Settings page, shown **over** whatever asked for it, with its own Back (setup polish, physical
 * QA).
 *
 * Advanced Setup's "Set up in Settings" used to close the hub and hand the page to `MainAppContent`,
 * which took the user out of the tour with no way back to where they were. This draws the very same
 * page - the same `LazyListScope` builder Settings uses, bound to the same repositories, so the
 * OAuth and API-key flows are Settings' own and nothing is duplicated - above the hub, which stays
 * composed underneath with its panel and tour position intact. Back (the header arrow or the system
 * back) removes the page and the user is on the panel they left.
 *
 * Only the pages Advanced Setup links to are hosted; anything else answers false from [supports] so
 * the caller can fall back to real Settings navigation.
 */
internal object ZNestedSettingsPage {
    fun supports(page: SettingsPage): Boolean = page in hosted

    private val hosted = setOf(
        SettingsPage.MdbListRatings,
        SettingsPage.TraktAuthentication,
        SettingsPage.TmdbEnrichment,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ZNestedSettingsPageHost(
    page: SettingsPage,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tmdbSettings by remember {
        TmdbSettingsRepository.ensureLoaded()
        TmdbSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val mdbListSettings by remember {
        MdbListSettingsRepository.ensureLoaded()
        MdbListSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val traktAuthUiState by remember {
        TraktAuthRepository.ensureLoaded()
        TraktAuthRepository.uiState
    }.collectAsStateWithLifecycle()
    val simklAuthUiState by remember {
        SimklAuthRepository.ensureLoaded()
        SimklAuthRepository.uiState
    }.collectAsStateWithLifecycle()
    val traktCommentsEnabled by remember {
        TraktCommentsSettings.ensureLoaded()
        TraktCommentsSettings.enabled
    }.collectAsStateWithLifecycle()
    val trackingSettingsUiState by remember {
        TrackingSettingsRepository.ensureLoaded()
        TrackingSettingsRepository.uiState
    }.collectAsStateWithLifecycle()

    // Registered after the hub's own handler, so system back closes this page first.
    PlatformBackHandler(enabled = true, onBack = onBack)

    // The page draws its own header with a Back arrow; a native navigation host would otherwise hide
    // it in favour of a system bar that this overlay does not have.
    CompositionLocalProvider(
        LocalUseNativeNavigation provides false,
        LocalNativeNavigationBarHidden provides false,
    ) {
        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.nuvio.colors.background)
                .nuvioConsumePointerEvents(),
        ) {
            val isTablet = maxWidth >= 768.dp
            NuvioScreen {
                stickyHeader {
                    NuvioScreenHeader(title = stringResource(page.titleRes), onBack = onBack)
                }
                when (page) {
                    SettingsPage.MdbListRatings -> mdbListSettingsContent(isTablet = isTablet, settings = mdbListSettings)
                    SettingsPage.TmdbEnrichment -> tmdbSettingsContent(isTablet = isTablet, settings = tmdbSettings)
                    SettingsPage.TraktAuthentication -> trackingSettingsContent(
                        isTablet = isTablet,
                        traktUiState = traktAuthUiState,
                        simklUiState = simklAuthUiState,
                        settingsUiState = trackingSettingsUiState,
                        commentsEnabled = traktCommentsEnabled,
                        onCommentsEnabledChange = TraktCommentsSettings::setEnabled,
                    )
                    else -> Unit
                }
            }
        }
    }
}
