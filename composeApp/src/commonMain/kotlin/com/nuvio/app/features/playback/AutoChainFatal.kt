package com.nuvio.app.features.playback

import com.nuvio.app.features.streams.StreamsRepository

/** What the player must do after reporting a fatal failure on an automatic (Instant/Streamlined) chain. */
internal enum class AutoChainFatalOutcome {
    /** A next candidate is armed; the stream route relaunches it behind the same loading surface. */
    Retry,

    /** Nothing is left to try. The loading session is closed and the source list has been requested. */
    Exhausted,
}

/** The `uncoverPath` the stream route records when an exhausted chain lands on the source list. */
internal const val CHAIN_EXHAUSTED_SOURCE_LIST_PATH = "chain_exhausted_from_player"

/**
 * Steps the chain past the source that just died, and settles what the exit means.
 *
 * ⚠ **Exhaustion is a terminal state with its own exit, not a Back press.** Debug 83: a one-candidate
 * Instant chain hit `NeverStarted`, the player toasted "No safe automatic source matched" and popped
 * - and the stream route could not tell that pop from the user leaving, because *saying* a retry
 * is the only way it can. It routed to the details screen and left a loading session open over it
 * for 25.8 s. The two exits that are not a retry are now both *said*: this closes the handed-off
 * session before the pop and asks the route, through the same signal as "Choose source manually",
 * to uncover the source list - where the toast's own advice ("choose a source manually") is
 * actually possible.
 *
 * Idempotent per call site: the caller guards it with its single-shot flag.
 */
internal fun resolveAutoChainFatal(): AutoChainFatalOutcome {
    val failed = StreamsRepository.uiState.value.autoPlayStream
    // A null `autoPlayStream` does not mean the chain is spent - it means playback started and
    // `onPlaybackStarted` consumed it. That is the common failure: a source that opens, plays a
    // second, and dies.
    val hasNext = if (failed != null) {
        StreamsRepository.skipAutoPlayStream(failed)
    } else {
        StreamsRepository.failOverAfterPlaybackStarted()
    }
    if (hasNext) {
        // Say so, rather than leaving the stream route to guess from state a back press
        // produces just as well.
        StreamsRepository.signalFailoverRetry()
        return AutoChainFatalOutcome.Retry
    }
    StreamsRepository.consumeAutoPlay()
    PlaybackLoadingController.closeAfterHandOff(reason = "chain_exhausted")
    StreamsRepository.signalManualSourceRequest(CHAIN_EXHAUSTED_SOURCE_LIST_PATH)
    return AutoChainFatalOutcome.Exhausted
}
