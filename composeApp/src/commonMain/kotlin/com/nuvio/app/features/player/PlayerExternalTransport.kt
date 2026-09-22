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
 * (`externalSetPlaybackState`, the same party path as desktop's `setPlaybackStateQuiet`, and a
 * finished scrub for seeks), so the party's permission check and
 * barrier apply exactly as they do for an on-screen button.
 *
 * The engine is still moved directly when no party is active. The runtime only reaches the engine
 * through recomposition, and there are no frames while the app is in the background, which is
 * exactly when a lock-screen button is pressed. In a party the command's own execution moves the
 * engine. It runs in a coroutine, not in composition, so it works in the background too; and a
 * refused guest's player correctly stays where the party is.
 */
internal class PlayerExternalTransport(
    private val onEvent: (String, Double) -> Boolean,
    private val onSeek: (Long) -> Boolean,
) {
    fun play(engine: () -> Unit) {
        if (!onEvent(EVENT, 1.0)) engine()
    }

    fun pause(engine: () -> Unit) {
        if (!onEvent(EVENT, 0.0)) engine()
    }

    fun seekTo(positionMs: Long) {
        onSeek(positionMs.coerceAtLeast(0L))
    }
}

/**
 * Answered `true` when the party took the request, `false` when the engine must be moved directly.
 * Whether a party owns the transport is decided by the runtime against the live repository - an
 * engine-side reading of the controls state is only as fresh as the last frame, and there are no
 * frames in the background.
 */
private const val EVENT = "externalSetPlaybackState"
