package com.nuvio.app.features.setup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.shuffle.EpisodeShuffleRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.layout_random_episode_sub
import nuvio.composeapp.generated.resources.random_episode_title
import nuvio.composeapp.generated.resources.shuffle_save_failed
import org.jetbrains.compose.resources.stringResource

// ⚠ **The one per-repository file of Advanced Setup** - the `ZProfileSyncContributors.kt` pattern.
// Everything else under `features/setup/` is byte-identical in `NuvioZDesktop`; this file is not,
// because what it binds exists only in this repository. Never carry it across as-is: desktop's copy
// is the same declarations with empty bodies.

/** Whether Random Episode is on, for the Detail page preview's Shuffle action. Always false on desktop. */
@Composable
internal fun rememberAdvancedRandomEpisodeAvailable(): Boolean {
    val shuffle by remember {
        EpisodeShuffleRepository.ensureLoaded()
        EpisodeShuffleRepository.uiState
    }.collectAsStateWithLifecycle()
    return shuffle.available
}

/**
 * Detail page → Random Episode (mobile only, off by default, last on its panel). Writes the same
 * store and shows the same toast as the Settings row in `MetaScreenSettingsPage.kt`.
 */
@Composable
internal fun AdvancedRandomEpisodeRow() {
    val shuffle by remember {
        EpisodeShuffleRepository.ensureLoaded()
        EpisodeShuffleRepository.uiState
    }.collectAsStateWithLifecycle()
    val failed = stringResource(Res.string.shuffle_save_failed)
    SetupToggleRow(
        title = stringResource(Res.string.random_episode_title),
        description = stringResource(Res.string.layout_random_episode_sub),
        checked = shuffle.available,
        onCheckedChange = { if (!EpisodeShuffleRepository.setAvailable(it)) NuvioToastController.show(failed) },
    )
}
