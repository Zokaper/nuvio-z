package com.nuvio.app.core.sync

import com.nuvio.app.features.home.HomePresentationSync
import com.nuvio.app.features.shuffle.EpisodeShuffleSync

/**
 * ⚠ **The one per-repository file of the Z sync seam - never merge or copy it across.** `nuvio-z`
 * lists Random Episode, which `NuvioZDesktop` does not have. Everything else about the seam
 * (`ZProfileSyncContributor`, `ProfileSettingsSync`) is shared and identical.
 */
internal val zProfileSyncContributors: List<ZProfileSyncContributor> = listOf(
    HomePresentationSync,
    EpisodeShuffleSync,
)
