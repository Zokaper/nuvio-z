package com.nuvio.app.features.watchparty

/** The backend may have expired readiness while a loaded player was suspended. */
fun partyReadinessNeedsReconcile(
    mediaLoaded: Boolean,
    serverState: SourceResolutionState?,
): Boolean = mediaLoaded && serverState in setOf(
    SourceResolutionState.fetching,
    SourceResolutionState.resolving,
    SourceResolutionState.disconnected,
    SourceResolutionState.joined,
)
