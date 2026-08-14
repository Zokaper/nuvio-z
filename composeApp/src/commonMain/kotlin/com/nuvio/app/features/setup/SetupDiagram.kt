package com.nuvio.app.features.setup

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.playback.PlaybackMode
import kotlinx.coroutines.delay

/**
 * The band's content on the steps that change nothing visible.
 *
 * ## This is provisional, and it is built to be removed
 *
 * Three steps - the playback-mode question, Sources and Done - set nothing the specimens can
 * show. Revision 2 left the fake home screen behind them, which put an irrelevant hero banner
 * under the text. The maintainer's call was an illustrative diagram, with the explicit caveat
 * that it may well get cut.
 *
 * So the whole feature is **one file with one public composable and exactly one call site** -
 * the `Diagram` branch of `SetupSpecimenBand`. Removing it means deleting this file and
 * `SetupModeStoryboard.kt`, replacing that branch with a `Spacer`, and shrinking
 * `SetupSpecimen.Diagram.preferredHeight`. Nothing else refers to either.
 *
 * ⚠ **Welcome no longer draws here.** Revision 4 gave it `app_logo_wordmark` over an accent
 * wash; it read as a splash screen bolted onto a settings flow, and the asset has "Nuvio" baked
 * in as pixels above copy that says "Nuvio Z". Welcome now shows the app itself - see
 * `SetupSpecimen.Welcome`. That took the last resource read out of this file.
 *
 * Restrictions that keep the "cheap to delete" promise:
 *
 * 1. **Almost wordless, and now entirely string-resource-free.** The only text anywhere is the
 *    three resolution tokens in [setupStoryboardQualityTokens], which are locale-independent -
 *    so there is nothing here to translate and nothing stranded when it goes. The panel
 *    underneath does the explaining.
 * 2. **No `Canvas` and no path work.** Rounded rectangles, circles, three icons already used
 *    elsewhere in the app.
 * 3. **Animation only where it answers a question.** The playback-mode drawing loops because the
 *    step is *about* a process and a static picture demonstrably could not say - the maintainer
 *    read revision 4's three grey bars and an arrow as "vague as hell". Nothing else animates
 *    beyond the band's own crossfade.
 */
@Composable
fun SetupDiagram(
    step: SetupStep,
    playbackMode: PlaybackMode,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (step) {
            // The step asks how much of the choosing Nuvio does, so the drawing has to answer
            // that differently for each mode - otherwise it is decoration on the one step where
            // it could be doing work.
            SetupStep.PlaybackMode -> DiagramModeStoryboard(playbackMode)

            // An addon on the left filling catalog rows on the right.
            SetupStep.Sources -> DiagramAddonFeed()

            SetupStep.Done -> DiagramDone()

            // Never reached - these steps have a real specimen - but enumerated rather than
            // defaulted so that adding a step is a compile error here instead of a blank band.
            SetupStep.Welcome,
            SetupStep.Cards,
            SetupStep.Home,
            SetupStep.Details,
            SetupStep.Theme,
            -> Unit
        }
    }
}

// --- the playback-mode loop -----------------------------------------------------------------

/**
 * The one animated drawing: how each mode gets from tapping a title to playing it.
 *
 * The sequences live in [setupStoryboardFrames] (`SetupModeStoryboard.kt`), which is import-free
 * and covered by `scripts/run-pure-suites.sh`. Nothing about *what* each mode does is decided
 * here - this file only draws it - because the wizard is a Compose gate no test can reach, and
 * "Streamlined picks the release itself" is a claim that should not rest on someone re-reading a
 * layout.
 *
 * ⚠ **The pointer is the message.** Classic ends with a finger on a release; Streamlined ends
 * with the same release lit up and no finger anywhere. Everything else about the two frames is
 * identical, and it has to be - the difference between the modes *is* who chose.
 */
@Composable
private fun DiagramModeStoryboard(mode: PlaybackMode) {
    val frames = remember(mode) { setupStoryboardFrames(mode.name) }
    var frameIndex by remember(mode) { mutableStateOf(0) }
    val frame = frames.getOrElse(frameIndex) { frames.first() }

    // Restarts whenever the mode changes, so tapping a card in the panel below replays that
    // mode's loop from the beginning rather than resuming part-way through another one's.
    LaunchedEffect(mode) {
        while (true) {
            delay(frames.getOrElse(frameIndex) { frames.first() }.holdMillis.toLong())
            frameIndex = nextSetupStoryboardFrame(frameIndex, frames.size)
        }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.width(StoryboardStageWidth),
            contentAlignment = Alignment.Center,
        ) {
            // Every stage is laid out at once and faded between, rather than swapped, so the
            // list does not jump the arrow and the play circle sideways as it grows.
            StoryboardTitle(visible = frame.stage == SetupStoryboardStage.Title)
            StoryboardSources(frame = frame)
            StoryboardQuality(frame = frame)
        }
        DiagramArrow(Icons.AutoMirrored.Rounded.KeyboardArrowRight)
        DiagramCircle(
            icon = Icons.Rounded.PlayArrow,
            filled = frame.stage == SetupStoryboardStage.Playing,
        )
    }
}

/** Wide enough for the widest stage (the release list) so nothing reflows between frames. */
private val StoryboardStageWidth = 132.dp

/** The title being opened. One wide block over two short ones - a card with a caption. */
@Composable
private fun StoryboardTitle(visible: Boolean) {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(DiagramTweenMillis),
        label = "storyboard_title_alpha",
    )
    Column(
        modifier = Modifier.alpha(alpha),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DiagramBlock(width = 104.dp, height = 46.dp)
        DiagramBlock(width = 70.dp, height = 8.dp, alpha = 0.5f)
    }
}

/**
 * The source list, and the pointer travelling down it.
 *
 * Only Classic ever reaches this stage with rows visible - Streamlined's own release pick is
 * drawn by [StoryboardQuality]'s collapsed state, because it never shows the list at all.
 */
@Composable
private fun StoryboardSources(frame: SetupStoryboardFrame) {
    val tokens = MaterialTheme.nuvio
    val visible = frame.visibleRows > 0
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(DiagramTweenMillis),
        label = "storyboard_sources_alpha",
    )

    Column(
        modifier = Modifier.alpha(alpha),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(SETUP_STORYBOARD_SOURCE_ROWS) { index ->
            val chosen = index == frame.highlightedRow && visible
            Row(verticalAlignment = Alignment.CenterVertically) {
                DiagramBlock(
                    width = 104.dp,
                    height = 14.dp,
                    color = if (chosen) tokens.colors.accent else tokens.colors.textMuted,
                    alpha = if (chosen) 1f else 0.55f,
                )
                if (chosen && frame.pointerVisible) {
                    DiagramPointer(tapping = frame.tapping)
                }
            }
        }
    }
}

/**
 * Streamlined's quality question, and the release it settles on afterwards.
 *
 * Both live here because they are the same object changing: three chips collapse into the one
 * release Nuvio picked. Drawing the pick as a fresh list would say the user was shown a list.
 */
@Composable
private fun StoryboardQuality(frame: SetupStoryboardFrame) {
    val tokens = MaterialTheme.nuvio
    val askingQuality = frame.stage == SetupStoryboardStage.Quality
    val alpha by animateFloatAsState(
        targetValue = if (frame.chipsVisible) 1f else 0f,
        animationSpec = tween(DiagramTweenMillis),
        label = "storyboard_quality_alpha",
    )

    Column(
        modifier = Modifier.alpha(alpha),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        setupStoryboardQualityTokens.forEachIndexed { index, token ->
            val picked = index == frame.highlightedRow
            // Once the quality is picked, the chips that were not chosen fade out and the
            // remaining one drops to a release row - Nuvio answering, with nothing to tap.
            val rowAlpha by animateFloatAsState(
                targetValue = when {
                    askingQuality -> 1f
                    picked -> 1f
                    else -> 0f
                },
                animationSpec = tween(DiagramTweenMillis),
                label = "storyboard_chip_alpha",
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = token,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (picked) tokens.colors.onAccent else tokens.colors.textSecondary,
                    modifier = Modifier
                        .alpha(rowAlpha)
                        .width(80.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            if (picked) tokens.colors.accent else tokens.colors.overlayHover,
                        )
                        .padding(vertical = 5.dp),
                )
                // ⚠ Only while the quality is being asked for. The auto-pick frame is the one
                // that says Nuvio decided, and a finger on it would say the opposite.
                if (picked && frame.pointerVisible) {
                    DiagramPointer(tapping = frame.tapping)
                }
            }
        }
    }
}

// --- the other two diagrams ------------------------------------------------------------------

@Composable
private fun DiagramAddonFeed() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DiagramCircle(icon = Icons.Rounded.Extension)
        DiagramArrow(Icons.AutoMirrored.Rounded.KeyboardArrowRight)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { DiagramBlock(width = 44.dp, height = 34.dp) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { DiagramBlock(width = 44.dp, height = 34.dp, alpha = 0.5f) }
            }
        }
    }
}

@Composable
private fun DiagramDone() {
    DiagramCircle(icon = Icons.Rounded.Check, size = 76.dp, filled = true)
}

// --- the primitives every diagram is made of -------------------------------------------------

/** Matches the band's own crossfade, so nothing here races the step transition. */
private const val DiagramTweenMillis = 260

@Composable
private fun DiagramBlock(
    width: Dp,
    height: Dp,
    color: Color = MaterialTheme.nuvio.colors.textMuted,
    alpha: Float = 0.7f,
) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.28f * alpha))
            .border(
                width = MaterialTheme.nuvio.borders.hairline,
                color = color.copy(alpha = 0.4f * alpha),
                shape = RoundedCornerShape(6.dp),
            ),
    )
}

@Composable
private fun DiagramArrow(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.nuvio.colors.textMuted,
        modifier = Modifier.size(26.dp),
    )
}

@Composable
private fun DiagramCircle(
    icon: ImageVector,
    size: Dp = 58.dp,
    filled: Boolean = false,
) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                if (filled) tokens.colors.accent else tokens.colors.accent.copy(alpha = 0.18f),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (filled) tokens.colors.onAccent else tokens.colors.accent,
            modifier = Modifier.size(size * 0.46f),
        )
    }
}

/**
 * A fingertip, with a ring that expands on the tap.
 *
 * Two circles rather than a cursor icon, because this is a touch app and a mouse pointer would
 * be describing the wrong gesture on the platform most of these users are on.
 */
@Composable
private fun DiagramPointer(tapping: Boolean) {
    val tokens = MaterialTheme.nuvio
    val ringScale by animateFloatAsState(
        targetValue = if (tapping) 1.9f else 1f,
        animationSpec = tween(DiagramTweenMillis, easing = LinearOutSlowInEasing),
        label = "storyboard_pointer_ring",
    )
    val ringAlpha by animateFloatAsState(
        targetValue = if (tapping) 0f else 0.45f,
        animationSpec = tween(DiagramTweenMillis, easing = LinearOutSlowInEasing),
        label = "storyboard_pointer_ring_alpha",
    )

    Box(
        modifier = Modifier.padding(start = 6.dp).size(18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .scale(ringScale)
                .alpha(ringAlpha)
                .clip(CircleShape)
                .background(tokens.colors.textPrimary),
        )
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(tokens.colors.textPrimary.copy(alpha = 0.85f)),
        )
    }
}
