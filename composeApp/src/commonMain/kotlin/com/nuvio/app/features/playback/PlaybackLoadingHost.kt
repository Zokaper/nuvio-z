package com.nuvio.app.features.playback

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import com.nuvio.app.core.debug.isDebugBuild
import com.nuvio.app.features.updater.formatFileSize
import co.touchlab.kermit.Logger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay

/**
 * Draws the one loading surface, **outside `NavDisplay` and above it**.
 *
 * ⚠ **The position in the tree is the fix.** Every previous version of this screen was owned by
 * a route entry, and a route entry is exactly the wrong owner: it stops composing when it is not
 * on top and it is re-created by a pop. So handing off to the player crossfaded one copy out
 * while another faded in over a black player root, and a failover - which pops `PlayerRoute` -
 * re-entered the stream route and started the screen again, restarting its progress line and its
 * escape-hatch clock. That is the "reload" the maintainer saw when the chain moved to attempt 2.
 *
 * Here, the navigation happens *underneath* a surface that never stops drawing. The hand-off and
 * the failover are state changes to a running composable, so there is nothing to animate and
 * nothing to re-enter - which is why neither has a transition and neither should ever acquire
 * one.
 *
 * On desktop this holds the screen until JCEF has painted its own copy of the band; see
 * `NativePlayerHost`'s `didPaintOpening` gate. A heavyweight `SwingPanel` paints over all Compose
 * content regardless of z-order, so "above `NavDisplay`" is necessary but not sufficient there.
 */
@Composable
fun PlaybackLoadingHost(modifier: Modifier = Modifier) {
    val session = PlaybackLoadingController.session
    val actions = PlaybackLoadingController.actions

    // The surface has to keep drawing while it fades out, after the controller has already
    // dropped the session - so the last one is held for exactly that long. Without this the
    // screen would vanish on the frame the first picture arrived, which is a cut, not a
    // hand-over.
    var lastSession by remember { mutableStateOf<PlaybackLoadingSession?>(null) }
    if (session != null) lastSession = session
    val rendered = lastSession ?: return

    AnimatedVisibility(
        visible = session != null,
        // ⚠ **No enter transition, deliberately**, and the same rule the player's overlay
        // carries. The entrance is [PlaybackLoadingMotion], driven by `entryProgress` below, so
        // that the backdrop and the band can arrive on a stagger; an `AnimatedVisibility` fade on
        // top of it would be a second, unsynchronised entrance.
        enter = EnterTransition.None,
        // The one exit in the flow: the surface dissolving into the first frame. It runs only
        // once `PlaybackHandover.hasFirstFrame` is satisfied, so it never reveals a black plane.
        exit = fadeOut(animationSpec = tween(EXIT_DURATION_MS)),
        modifier = modifier,
    ) {
        // One entrance per session. Keyed on the token and nothing else, so a revision, a
        // hand-off and a failover all leave it finished at 1f.
        val entry = remember { Animatable(0f) }
        // ⚠ **Measuring the surface's own lifetime.** On desktop this screen is replaced by the
        // native canvas the moment the player route composes, so it may live only a few hundred
        // milliseconds - in which case a 220 ms entrance is most of its life and reads as a
        // flicker rather than a transition. That is a guess until this number exists.
        LaunchedEffect(rendered.token) {
            val shownAt = kotlin.time.TimeSource.Monotonic.markNow()
            loadingLog.i { "loading surface token=${rendered.token} opened" }
            try {
                awaitCancellation()
            } finally {
                loadingLog.i {
                    "loading surface token=${rendered.token} " +
                        "visibleMs=${shownAt.elapsedNow().inWholeMilliseconds}"
                }
            }
        }

        // ⚠ **The stutter, measured as the user sees it.** Everything else here reports what the
        // surface *intended*; this reports what was actually presented. A frame gap is the only
        // honest description of a stutter, and pairing one against the `EdtStall` report at the
        // same timestamp says both that it happened and what was holding the thread. Debug builds
        // only, and it stops on its own once the entrance is well past.
        LaunchedEffect(rendered.token) {
            if (!isDebugBuild) return@LaunchedEffect
            val start = withFrameNanos { it }
            var previous = start
            var frames = 0
            var worstGapMs = 0L
            val dropped = StringBuilder()
            while (true) {
                val now = withFrameNanos { it }
                val gapMs = (now - previous) / 1_000_000L
                val sinceStartMs = (now - start) / 1_000_000L
                previous = now
                frames++
                if (gapMs >= FRAME_GAP_REPORT_MS) {
                    if (gapMs > worstGapMs) worstGapMs = gapMs
                    dropped.append(" +${sinceStartMs}ms/${gapMs}ms")
                }
                if (sinceStartMs >= FRAME_PROBE_WINDOW_MS) break
            }
            loadingLog.i {
                "loading entrance token=${rendered.token} frames=$frames " +
                    "worstGapMs=$worstGapMs gaps=[${dropped.toString().trim()}]"
            }
        }

        LaunchedEffect(rendered.token) {
            entry.snapTo(0f)
            entry.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = PlaybackLoadingMotion.ENTRY_DURATION_MS,
                    easing = LinearOutSlowInEasing,
                ),
            )
        }

        // The escape-hatch clock, driven here rather than inside the screen so it measures the
        // wait the user is actually having - across the hand-off and across every retry - instead
        // of restarting each time some route below re-composed.
        LaunchedEffect(rendered.token) {
            var elapsedMs = 0L
            while (true) {
                delay(ESCAPE_CLOCK_TICK_MS)
                elapsedMs += ESCAPE_CLOCK_TICK_MS
                PlaybackLoadingController.tick(elapsedMs)
            }
        }

        PlaybackLoadingScreen(
            state = rendered.renderedState,
            artwork = rendered.artwork,
            logo = rendered.logo,
            title = rendered.title,
            formatSize = { formatFileSize(it) },
            onBack = actions?.onBack,
            onChooseManually = actions?.onChooseManually?.takeIf { rendered.offersManualEscape },
            entryProgress = entry.value,
        )
    }
}

/** The dissolve into the first frame. Matches the player overlay's own fade, which it replaces. */
private const val EXIT_DURATION_MS: Int = 300

/**
 * Coarse on purpose: this only feeds [shouldOfferManualEscape]'s five-second threshold, and a
 * tick is a state write that recomposes the surface.
 */
private const val ESCAPE_CLOCK_TICK_MS: Long = 250L

/** Two dropped frames at 60 Hz - the point at which a gap stops being scheduler noise. */
private const val FRAME_GAP_REPORT_MS: Long = 34L

/** Long enough to cover the entrance, the navigation and the native player being built. */
private const val FRAME_PROBE_WINDOW_MS: Long = 2_500L

private val loadingLog = Logger.withTag("PlaybackStartup")
