package com.nuvio.app.features.player

/**
 * The player commands a platform raises outside Compose: the iOS lock screen, Control Center, a
 * headset or AirPods button, CarPlay-style remotes (`MPRemoteCommandCenter`), and the app coming
 * back to the foreground.
 *
 * Implemented here in Kotlin and **called from Swift**. `NowPlayingController.swift` used to move mpv
 * directly, which is the iOS half of the bypass [PlayerExternalTransport] closed on Android: in a
 * party a lock-screen pause stayed local, a host's read as buffering to everyone else, and a guest
 * without control could stop their own player while the party carried on. Every command now goes
 * through the same [PlayerExternalTransport] Android's media session uses, so the party's permission
 * check, barrier and command execution apply exactly as they do for an on-screen button.
 *
 * Public because the Swift side needs the protocol; the routing itself is [PlayerRemoteCommandRouter].
 */
interface PlayerRemoteCommands {
    fun play()
    fun pause()
    fun togglePlayPause()
    fun seekTo(positionMs: Long)
    fun seekBy(offsetMs: Long)

    /**
     * The app is back in the foreground and the engine should return to what the runtime wants.
     *
     * The iOS view controller pauses mpv when the app enters the background and, until now, resumed
     * it unconditionally on the way back. That played a film the user had paused before locking, and
     * in a party it played a stale position under a party that might be paused - the one thing the
     * away return exists to prevent. Android restores `playWhenReady` from the runtime at `ON_START`;
     * this is the same rule.
     */
    fun restorePlaybackIntent()
}

/**
 * [PlayerRemoteCommands] over [PlayerExternalTransport].
 *
 * Lives in `commonMain` although only iOS wires it, so the routing is covered by the host suite -
 * iOS tests cannot run on the Windows machines this project is built on.
 */
internal class PlayerRemoteCommandRouter(
    private val transport: PlayerExternalTransport,
    private val engine: Engine,
    private val playWhenReady: () -> Boolean,
) : PlayerRemoteCommands {

    /** The engine calls the router may make directly: only when nothing owns the request. */
    interface Engine {
        fun play()
        fun pause()
        fun seekTo(positionMs: Long)

        /** mpv's own `pause` flag, not "is playing": a member stalled on a rebuffer is not paused. */
        fun isPaused(): Boolean

        /** Read now, not from the last poll: a skip is relative to where the player actually is. */
        fun positionMs(): Long
    }

    override fun play() = transport.play(engine::play)

    override fun pause() = transport.pause(engine::pause)

    // From the engine's pause flag, not from the runtime's intent: iOS pauses mpv itself on entering
    // the background without telling the runtime, so a headset toggle on the lock screen has to read
    // what the engine was told. And not from "is playing", which is false through a rebuffer - a
    // pause pressed then would be sent as a play, and in a party a play is a force start that drops
    // every hold.
    override fun togglePlayPause() = if (engine.isPaused()) play() else pause()

    override fun seekTo(positionMs: Long) = transport.seekTo(positionMs, engine::seekTo)

    override fun seekBy(offsetMs: Long) = seekTo(engine.positionMs() + offsetMs)

    // The runtime answers from its live intent (`externalRestorePlaybackIntent`); the composed
    // [playWhenReady] is only for a surface with no runtime behind it, like the trailer popup.
    override fun restorePlaybackIntent() = transport.restorePlaybackIntent {
        if (playWhenReady()) engine.play() else engine.pause()
    }
}
