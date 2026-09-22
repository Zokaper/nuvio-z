package com.nuvio.app.features.watchparty

sealed interface ExistingPartyJoinOutcome {
    /** The current player must be released; party realization starts from the pre-playback lobby. */
    data class OpenPrePlaybackLobby(val partyId: String) : ExistingPartyJoinOutcome
}

/** Joining never promotes an existing player, even when it happens to show the same content. */
internal fun existingPartyJoinOutcome(party: WatchPartyState): ExistingPartyJoinOutcome =
    ExistingPartyJoinOutcome.OpenPrePlaybackLobby(party.id)

/**
 * Resolves the Details-screen Watch Together action against the one-active-party contract.
 *
 * A failed source publication can leave a perfectly valid party active. Creating another party in
 * that state is correctly rejected by the backend, so entry must reopen the held/restored party
 * before it attempts creation.
 */
internal suspend fun resolveWatchPartyEntry(
    targetContent: PartyContent,
    heldParty: WatchPartyState?,
    restoreActive: suspend () -> Result<WatchPartyState?>,
    departOldParty: suspend (WatchPartyState) -> Result<Unit> = { Result.success(Unit) },
    createParty: suspend () -> Result<WatchPartyState>,
): Result<WatchPartyState> {
    val activeHeld = heldParty?.takeUnless { it.status == WatchPartyStatus.ended }
    if (activeHeld != null) {
        if (activeHeld.matchesPlayback(targetContent.contentId, targetContent.videoId)) {
            return Result.success(activeHeld)
        }
        departOldParty(activeHeld).getOrElse { return Result.failure(it) }
        return createParty()
    }

    return restoreActive().fold(
        onSuccess = { restored ->
            val activeRestored = restored?.takeUnless { it.status == WatchPartyStatus.ended }
            if (activeRestored != null) {
                if (activeRestored.matchesPlayback(targetContent.contentId, targetContent.videoId)) {
                    Result.success(activeRestored)
                } else {
                    departOldParty(activeRestored).fold(
                        onSuccess = { createParty() },
                        onFailure = { Result.failure(it) },
                    )
                }
            } else {
                createParty()
            }
        },
        onFailure = { Result.failure(it) },
    )
}
