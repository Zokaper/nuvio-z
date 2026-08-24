package com.nuvio.app.features.playback

/**
 * Separates the two answers produced by one connection probe.
 *
 * Instant needs a bounded answer so it can choose after the deadline, even if a platform reader
 * is still blocked. The quality sheet has a stricter promise: it must not publish a guess while a
 * real measurement is still in flight. Therefore the deadline settles the decision only; probe
 * completion settles both. Nonces keep a late result from an older re-test from settling or
 * regressing the current ask.
 */
data class ConnectionProbeSettlement(
    private val decisionNonce: Int = -1,
    private val figureNonce: Int = -1,
) {
    data class State(
        val isDecisionSettled: Boolean,
        val isFigureSettled: Boolean,
    )

    fun stateFor(nonce: Int): State = State(
        isDecisionSettled = decisionNonce >= nonce,
        isFigureSettled = figureNonce >= nonce,
    )

    fun onDeadline(nonce: Int): ConnectionProbeSettlement = copy(
        decisionNonce = maxOf(decisionNonce, nonce),
    )

    fun onProbeFinished(nonce: Int): ConnectionProbeSettlement = copy(
        decisionNonce = maxOf(decisionNonce, nonce),
        figureNonce = maxOf(figureNonce, nonce),
    )
}
