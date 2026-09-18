package com.nuvio.app.features.player

/**
 * Play, pause and seek asked for from outside the player's own controls: the system media
 * notification, the lock screen, a headset or Bluetooth button, the picture-in-picture buttons.
 *
 * ⚠ **These used to move the engine directly, and that skipped the runtime twice over.**
 *
 * - **Outside a party**, `shouldPlay` never learned that the user had paused. When the app came back
 *   to the foreground, `ON_START` restored `playWhenReady` from it and the film started again
 *   under them.
 * - **In a party**, the pause never became a party command. An authorized member's pause read as
 *   "buffering" to everyone else. A guest without control could stop their own player while the
 *   party carried on without them, until drift correction started it again.
 *
 * Each request now goes to the runtime through the same events desktop's controls page sends
 * (`setPlaybackStateQuiet`, and a finished scrub for seeks), so the party's permission check and
 * barrier apply exactly as they do for an on-screen button.
 *
 * The engine is still moved directly when no party is active. The runtime only reaches the engine
 * through recomposition, and there are no frames while the app is in the background, which is
 * exactly when a lock-screen button is pressed. In a party the command's own execution moves the
 * engine. It runs in a coroutine, not in composition, so it works in the background too; and a
 * refused guest's player correctly stays where the party is.
 */
internal class PlayerExternalTransport(
    private val partyActive: () -> Boolean,
    private val onEvent: (String, Double) -> Boolean,
    private val onSeek: (Long) -> Boolean,
) {
    fun play(engine: () -> Unit) {
        if (!partyActive()) engine()
        onEvent("setPlaybackStateQuiet", 1.0)
    }

    fun pause(engine: () -> Unit) {
        if (!partyActive()) engine()
        onEvent("setPlaybackStateQuiet", 0.0)
    }

    fun seekTo(positionMs: Long) {
        onSeek(positionMs.coerceAtLeast(0L))
    }
}

/** Whether a party owns this player's transport, as far as the controls state can tell. */
internal fun PlayerControlsState.partyOwnsExternalTransport(): Boolean = watchTogether.stateName == "active"
