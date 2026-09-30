package com.nuvio.app.features.player

/** EOF during opening, a failed load, or a snapshot from the previous source cannot advance TV. */
internal fun PlayerScreenRuntime.hasCompletedCurrentEpisode(): Boolean =
    initialLoadCompleted && playbackSnapshot.isEnded && playbackSnapshot.durationMs > 0L &&
        isNextEpisodeSnapshotSettled()
