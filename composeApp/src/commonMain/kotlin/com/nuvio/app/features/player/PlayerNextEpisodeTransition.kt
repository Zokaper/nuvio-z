package com.nuvio.app.features.player

internal enum class PlayerNextEpisodeOrigin {
    MANUAL,
    AUTOMATIC,
}

internal enum class PlayerNextEpisodePhase {
    IDLE,
    RESOLVING,
    AWAITING_CHOICE,
    COUNTDOWN,
    STARTING,
    FAILED,
}

internal enum class PlayerNextEpisodeFailureReason {
    TIMED_OUT,
    EMPTY_RESULTS,
    NO_SAFE_CANDIDATE,
}

/**
 * One source of truth for every route into the next episode.
 *
 * [requestId] makes results from cancelled stream requests harmless. The target identity also
 * prevents a late result for one episode from being applied after playback has already moved on.
 */
internal data class PlayerNextEpisodeTransition(
    val requestId: Long = 0L,
    val currentVideoId: String? = null,
    val targetVideoId: String? = null,
    val origin: PlayerNextEpisodeOrigin? = null,
    val phase: PlayerNextEpisodePhase = PlayerNextEpisodePhase.IDLE,
    val sourceName: String? = null,
    val countdownSeconds: Int? = null,
) {
    val isActive: Boolean
        get() = phase != PlayerNextEpisodePhase.IDLE && phase != PlayerNextEpisodePhase.FAILED

    fun isRequest(requestId: Long, targetVideoId: String): Boolean =
        this.requestId == requestId && this.targetVideoId == targetVideoId && isActive

    fun shouldCountDown(): Boolean = origin == PlayerNextEpisodeOrigin.AUTOMATIC

    fun canAcceptManualTap(): Boolean = when {
        phase == PlayerNextEpisodePhase.IDLE -> true
        origin == PlayerNextEpisodeOrigin.AUTOMATIC &&
            phase in setOf(PlayerNextEpisodePhase.RESOLVING, PlayerNextEpisodePhase.COUNTDOWN) -> true
        else -> false
    }

    companion object {
        val Idle = PlayerNextEpisodeTransition()
    }
}

internal object PlayerNextEpisodeTransitionPolicy {
    fun begin(
        previousRequestId: Long,
        currentVideoId: String?,
        targetVideoId: String,
        origin: PlayerNextEpisodeOrigin,
        phase: PlayerNextEpisodePhase = PlayerNextEpisodePhase.RESOLVING,
    ): PlayerNextEpisodeTransition = PlayerNextEpisodeTransition(
        requestId = previousRequestId + 1L,
        currentVideoId = currentVideoId,
        targetVideoId = targetVideoId,
        origin = origin,
        phase = phase,
    )

    fun promoteToManual(state: PlayerNextEpisodeTransition): PlayerNextEpisodeTransition =
        if (state.origin == PlayerNextEpisodeOrigin.AUTOMATIC && state.isActive) {
            state.copy(
                origin = PlayerNextEpisodeOrigin.MANUAL,
                countdownSeconds = null,
            )
        } else {
            state
        }

    fun cancel(state: PlayerNextEpisodeTransition): PlayerNextEpisodeTransition = state.copy(
        phase = PlayerNextEpisodePhase.IDLE,
        origin = null,
        currentVideoId = null,
        targetVideoId = null,
        sourceName = null,
        countdownSeconds = null,
    )

    fun isPromptSuppressed(dismissedForVideoId: String?, currentVideoId: String?): Boolean =
        dismissedForVideoId != null && dismissedForVideoId == currentVideoId

    fun update(
        state: PlayerNextEpisodeTransition,
        requestId: Long,
        targetVideoId: String,
        phase: PlayerNextEpisodePhase,
        sourceName: String? = state.sourceName,
        countdownSeconds: Int? = null,
    ): PlayerNextEpisodeTransition =
        if (state.isRequest(requestId, targetVideoId)) {
            state.copy(
                phase = phase,
                sourceName = sourceName,
                countdownSeconds = countdownSeconds,
            )
        } else {
            state
        }
}
