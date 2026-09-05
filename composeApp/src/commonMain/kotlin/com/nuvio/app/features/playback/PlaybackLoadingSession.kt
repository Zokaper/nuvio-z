package com.nuvio.app.features.playback

import com.nuvio.app.features.downloads.SourceFacts

/**
 * One user-initiated play, from the tap to the first frame - **including every route change and
 * every retry inside it**.
 *
 * ⚠ **This type exists because "the loading screen" used to be three screens.** The route drew
 * [PlaybackProgressOverlay], the player drew `OpeningOverlay`, and on desktop JCEF drew a third
 * copy in `controls.html`; each was created and destroyed by whatever owned it. Phase 2 made
 * them render one [PlaybackLoadingState], which fixed the *pixels* but not the *lifetime*: the
 * hand-off still disposed one renderer and created another, and a failover still popped
 * `PlayerRoute`, re-entered `entry<StreamRoute>` and started the whole thing again. The user saw
 * a stutter, a black frame, and then the loading screen "reloading" itself to say Attempt 2 -
 * three separate reports of what is really one fault.
 *
 * A session is owned above the navigator by `PlaybackLoadingController`, so nothing in the
 * back stack can end it. It ends when playback reaches a first frame, when the chain is spent,
 * or when the user leaves - never because a screen was composed away.
 *
 * Pure, and in the same import-free spirit as `StreamRouteSurface.kt`, so
 * `scripts/run-pure-suites.sh` can execute the lifetime rules. The Compose holder around it is
 * `PlaybackLoadingController.kt` and holds no rules of its own.
 */
data class PlaybackLoadingSession(
    /**
     * Identifies **one tap**, not one route entry and not one attempt.
     *
     * The entrance animation and the manual-escape clock are both keyed on this and on nothing
     * else, which is the whole point: a failover bumps [PlaybackLoadingState.attempt] and leaves
     * this alone, so the screen keeps running rather than re-entering.
     */
    val token: Long,
    val state: PlaybackLoadingState,
    /** Fixed for the life of the session. Changing artwork mid-session is a visible reload. */
    val artwork: String? = null,
    val logo: String? = null,
    val title: String? = null,
    /**
     * Elapsed wall-clock since the tap, fed by the host.
     *
     * Carried on the session rather than measured by the composable because the composable that
     * used to measure it (`PlaybackProgressOverlay`'s `LaunchedEffect(Unit)`) was re-entered by
     * every failover, so the five-second escape hatch restarted from zero exactly when the user
     * had been waiting longest and needed it most.
     */
    val elapsedMs: Long = 0L,
    /**
     * The route has navigated and the player owns the playback; the surface stays up regardless.
     *
     * Only used to decide who may publish a step, never whether to draw. A surface that stopped
     * drawing here is the black frame this whole type removes.
     */
    val handedOff: Boolean = false,
) {
    /** The escape hatch is a property of the *session*, so it survives the hand-off and retries. */
    val offersManualEscape: Boolean
        get() = shouldOfferManualEscape(state.attempt, elapsedMs)

    /** The state the screen actually renders, with the session-owned escape hatch folded in. */
    val renderedState: PlaybackLoadingState
        get() = state.copy(offerManualEscape = offersManualEscape)
}

object PlaybackLoadingSessions {

    /** A new tap. The only thing that starts an entrance and resets the escape clock. */
    fun open(
        token: Long,
        step: PlaybackProgressStep,
        artwork: String? = null,
        logo: String? = null,
        title: String? = null,
        attempt: Int = 1,
        facts: SourceFacts? = null,
    ): PlaybackLoadingSession = PlaybackLoadingSession(
        token = token,
        state = PlaybackLoadingState(step = step, attempt = attempt, facts = facts),
        artwork = artwork,
        logo = logo,
        title = title,
    )

    /**
     * A step, attempt, facts or failure revision **within** the running session.
     *
     * Returns [current] untouched when [token] names a different session. That guard is not
     * defensive tidiness: the route and the player both publish, and during the hand-off both
     * are briefly alive - `entry<StreamRoute>` is still composed for a frame after
     * `navigate(PlayerRoute)`. Without it a stale route publish could overwrite the player's
     * state and the band would flicker backwards through the steps it had already passed.
     */
    fun revise(
        current: PlaybackLoadingSession?,
        token: Long,
        state: PlaybackLoadingState,
    ): PlaybackLoadingSession? {
        if (current == null || current.token != token) return current
        return current.copy(state = state)
    }

    /** The route hands off to the player. Everything visible is deliberately unchanged. */
    fun handOff(current: PlaybackLoadingSession?, token: Long): PlaybackLoadingSession? {
        if (current == null || current.token != token) return current
        return current.copy(handedOff = true)
    }

    /** Wall-clock, fed by the host's ticker. */
    fun tick(current: PlaybackLoadingSession?, elapsedMs: Long): PlaybackLoadingSession? =
        current?.copy(elapsedMs = elapsedMs)

    /**
     * Whether moving from [previous] to [next] is a **new entrance**.
     *
     * True only when a session begins from nothing. Explicitly false for a revision, a
     * hand-off, and a failover, which are the three cases the old code animated and should not
     * have: the screen is already up and the user is already reading it.
     */
    fun isEntering(
        previous: PlaybackLoadingSession?,
        next: PlaybackLoadingSession?,
    ): Boolean = next != null && previous?.token != next.token
}

/**
 * The entrance, and the only motion in the whole flow that is allowed to exist.
 *
 * ⚠ **Read `PlaybackLoadingScreen`'s "no crossfade on the shared layers" rule before touching
 * this.** That rule is about the *hand-off*, where the screen is already up and any motion is a
 * visible jump. This is the opposite moment: the source list is being replaced, the content
 * genuinely changes, and a hard swap is what the maintainer reported as the UI "tweaking out".
 * One entrance, played once per session, is what makes the difference between a screen that
 * appears and a screen that pops.
 *
 * Everything after it - the hand-off, the failover, the native takeover - is deliberately
 * **zero-duration**, which is why these figures are not parameters. There is one entrance and
 * one exit in the flow and nothing in between may animate.
 */
object PlaybackLoadingMotion {

    /** Was 220. Long enough that the scrim and the band read as arriving rather than cutting. */
    const val ENTRY_DURATION_MS: Int = 260

    /** The logo leads the band, so the two do not land together and read as one hard swap. */
    const val TITLE_STAGGER_MS: Int = 40

    /**
     * The band waits this long behind the backdrop.
     *
     * So the backdrop lands first and the text settles onto a surface that has already arrived,
     * rather than the two racing and the chips appearing over a half-transparent poster.
     */
    const val BAND_STAGGER_MS: Int = 80

    /**
     * ⚠ **The backdrop does not animate, and must not be made to.**
     *
     * The first version faded and scaled the whole surface, which forced Compose to composite a
     * full-screen offscreen layer every frame for 220 ms - on a large window at a 1.4x UI scale,
     * the stutter reported immediately after choosing a source. Both are kept as identities rather
     * than deleted so the intent is explicit at the call site: the surface arrives at rest.
     */
    fun surfaceAlpha(entryProgress: Float): Float = 1f

    /** See [surfaceAlpha]. The surface never scales. */
    fun surfaceScale(entryProgress: Float): Float = 1f

    /** The scrim, delegated so the sheet and this screen ramp on one curve. */
    fun scrimAlpha(entryProgress: Float): Float =
        PlaybackEntranceMotion.scrimAlpha(entryProgress)

    fun titleAlpha(entryProgress: Float): Float = staggered(entryProgress, TITLE_STAGGER_MS)

    fun bandAlpha(entryProgress: Float): Float = staggered(entryProgress, BAND_STAGGER_MS)

    private fun staggered(entryProgress: Float, staggerMs: Int): Float {
        val start = staggerMs.toFloat() / ENTRY_DURATION_MS.toFloat()
        return ((entryProgress.coerceIn(0f, 1f) - start) / (1f - start)).coerceIn(0f, 1f)
    }
}

/**
 * When the loading surface may stop covering the player.
 *
 * ⚠ **`!isLoading` was not enough, and the gap is visible.** The engine drops that flag once it
 * has opened the media, which is before it has decoded anything - so the surface faded out onto a
 * black video plane and the picture appeared a moment later. Waiting for evidence that a *frame*
 * exists moves the fade to where the user expects it.
 *
 * Pure and primitive-typed so the decision runs in `scripts/run-pure-suites.sh`;
 * `PlayerPlaybackSnapshot` lives in `features/player` and must not be dragged in here.
 */
object PlaybackHandover {

    /**
     * [videoWidth]/[videoHeight] are the direct signal: an engine reports them only once a frame
     * has been decoded.
     *
     * The second arm is not a weakening but a necessity - not every engine reports dimensions,
     * and audio-only content never will. Real, advancing playback is the same proof by another
     * route. Both still require [isLoading] to be false, so neither can fire on a pending seek.
     */
    fun hasFirstFrame(
        isLoading: Boolean,
        isPlaying: Boolean,
        positionMs: Long,
        videoWidth: Int,
        videoHeight: Int,
    ): Boolean {
        if (isLoading) return false
        if (videoWidth > 0 && videoHeight > 0) return true
        return isPlaying && positionMs > 0L
    }
}
