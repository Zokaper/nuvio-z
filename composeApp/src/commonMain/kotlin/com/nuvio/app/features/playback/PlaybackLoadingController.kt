package com.nuvio.app.features.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nuvio.app.features.downloads.SourceFacts

/**
 * The one owner of the loading surface, deliberately **above the navigator**.
 *
 * ⚠ **Why a process-scoped object and not route state.** The surface has to outlive
 * `entry<StreamRoute>`, `entry<PlayerRoute>` and the pop between them, and nothing inside
 * `NavDisplay` can do that: a route entry stops composing the moment it is not on top, and a
 * failover *pops* the player, which re-enters the stream route and re-creates everything it
 * owns. Every symptom the maintainer reported - the stutter at the hand-off, the black frame,
 * and the loading screen visibly reloading to say "Attempt 2" - is that lifetime, not the
 * pixels. Owning the surface here means the navigation happens *underneath* a screen that never
 * stops drawing, so there is nothing left to animate, tear down or re-enter.
 *
 * Modelled on `PlayerLaunchStore` in `PlayerModels.kt`, which solves the same problem for the
 * launch payload for the same reason.
 *
 * **This object holds no rules.** Every decision is a pure function in
 * `PlaybackLoadingSession.kt` so `scripts/run-pure-suites.sh` can execute it; this is the
 * Compose-state box around them and nothing more.
 */
object PlaybackLoadingController {

    /** The running session, or null when nothing is starting. Read by `PlaybackLoadingHost`. */
    var session by mutableStateOf<PlaybackLoadingSession?>(null)
        private set

    private var nextToken: Long = 1L

    /**
     * A new play begins. Returns the token every later call must quote.
     *
     * Tokens are never reused, so a publish from a route that has already been superseded is
     * ignored rather than resurrecting a finished session.
     */
    fun open(
        step: PlaybackProgressStep,
        artwork: String? = null,
        logo: String? = null,
        title: String? = null,
        attempt: Int = 1,
        facts: SourceFacts? = null,
    ): Long {
        val token = nextToken++
        session = PlaybackLoadingSessions.open(
            token = token,
            step = step,
            artwork = artwork,
            logo = logo,
            title = title,
            attempt = attempt,
            facts = facts,
        )
        return token
    }

    /** A step, attempt, facts or failure revision inside the running session. */
    fun revise(token: Long, state: PlaybackLoadingState) {
        session = PlaybackLoadingSessions.revise(session, token, state)
    }

    /** The route has navigated to the player. Nothing visible changes; that is the point. */
    fun handOff(token: Long) {
        session = PlaybackLoadingSessions.handOff(session, token)
    }

    /** Wall-clock, driven by the host so the escape hatch spans route changes. */
    fun tick(elapsedMs: Long) {
        session = PlaybackLoadingSessions.tick(session, elapsedMs)
    }

    /**
     * The session is over: a first frame arrived, the chain was spent, or the user left.
     *
     * Token-guarded like the rest. A stale close from a route that has already handed off would
     * otherwise blank the screen at exactly the hand-off it is meant to cover.
     */
    fun close(token: Long) {
        if (session?.token != token) return
        session = null
        actions = null
    }

    /** The token of the running session, for a caller that has lost track of its own. */
    val activeToken: Long? get() = session?.token

    /**
     * The player reached a first frame. Ends the session whoever opened it.
     *
     * Gated on [PlaybackLoadingSession.handedOff] rather than on a token, because the player
     * often did not open the session it is finishing - in the automatic modes the *route* opens
     * it and hands it over, which is the entire point. The gate still stops a player that is
     * being torn down from closing a session a *newer* play has just opened.
     */
    fun closeAfterHandOff() {
        if (session?.handedOff == true) {
            session = null
            actions = null
        }
    }

    /**
     * What the surface's two buttons do, supplied by whoever currently owns the chain.
     *
     * ⚠ **Kept off [PlaybackLoadingSession] on purpose.** The session is a pure, comparable data
     * class that the pure suite executes; lambdas in it would make every equality check
     * meaningless and drag Compose into a file that must not import it. This mirrors
     * `registeredPlayerSystemBack` in `MainAppContent.kt`, which registers a handler against a
     * route for the same reason.
     *
     * `entry<StreamRoute>` registers these and keeps them valid across the hand-off: in the
     * automatic modes it stays on the back stack precisely so it can still answer for the chain,
     * so its scope is alive even while the player is on top and it is not composing.
     */
    var actions by mutableStateOf<PlaybackLoadingActions?>(null)
        private set

    fun registerActions(token: Long, actions: PlaybackLoadingActions?) {
        if (session?.token == token) this.actions = actions
    }
}

/**
 * The surface's affordances. Both may be null: a session with no way out is legitimate for the
 * fraction of a second before [shouldOfferManualEscape] opens one.
 */
data class PlaybackLoadingActions(
    val onBack: (() -> Unit)? = null,
    val onChooseManually: (() -> Unit)? = null,
)
