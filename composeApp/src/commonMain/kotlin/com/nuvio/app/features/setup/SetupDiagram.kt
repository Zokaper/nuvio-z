package com.nuvio.app.features.setup

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
 * `SetupHomeStill.kt`. That took the last resource read out of this file.
 *
 * Restrictions that keep the "cheap to delete" promise:
 *
 * 1. **Entirely string-resource-free.** The only text anywhere is
 *    [setupStoryboardQualityTokens] and [setupStoryboardReleases] - resolutions, source tags and
 *    sizes, all locale-independent - so there is nothing here to translate and nothing stranded
 *    when it goes. ⚠ Revision 5 tried to keep this wordless and the Classic sequence could not
 *    carry its own meaning: "you read the releases" drawn as five blank bars says nothing. The
 *    panel underneath still does the explaining; the drawing no longer has to do it alone.
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
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.width(StoryboardStageWidth),
            contentAlignment = Alignment.Center,
        ) {
            // Every stage is laid out at once and transitioned between, rather than swapped, so
            // the list growing never shoves the arrow and the play circle sideways.
            StoryboardStage(visible = frame.stage == SetupStoryboardStage.Title) {
                StoryboardTitle()
            }
            StoryboardStage(visible = frame.visibleRows > 0) {
                StoryboardSources(frame = frame)
            }
            StoryboardStage(visible = frame.chipsVisible) {
                StoryboardQuality(frame = frame)
            }
        }
        DiagramArrow(Icons.AutoMirrored.Rounded.KeyboardArrowRight)
        DiagramCircle(
            icon = Icons.Rounded.PlayArrow,
            filled = frame.stage == SetupStoryboardStage.Playing,
        )
    }
}

// --- the storyboard's metrics --------------------------------------------------------------
//
// ⚠ Budgeted against `SetupSpecimen.Diagram.preferredHeight`, which is 150 dp because the
// playback-mode step has the tallest panel in the flow and revision 5 clipped its third card.
// The tallest stage is the chips: 3 x 30 + 2 x 6 = 102. Check that sum before growing any of
// these, and the panel on a real phone before growing the band instead.

/** Wide enough for the widest stage plus its gutter, so nothing reflows between frames. */
private val StoryboardStageWidth = 174.dp
private val StoryboardListWidth = 140.dp
private val StoryboardRowHeight = 22.dp
private val StoryboardChipHeight = 30.dp
private val StoryboardRowGap = 6.dp

/** Reserved on every row whether or not a pointer is in it. See [StoryboardPointerGutter]. */
private val StoryboardGutterWidth = 26.dp

/** Long enough to read as movement rather than a cut, short enough not to lag the holds. */
private const val StageTweenMillis = 300

/** How far a stage travels as it fades. Pixels, because it is applied in a `graphicsLayer`. */
private const val StageSlidePx = 26f

/**
 * One stage of the loop, faded **and slid** in and out.
 *
 * ⚠ **The slide is the fix for "janky".** Revision 5 cross-dissolved the stages at the same
 * position, so for a quarter of a second the title card and the release list were both half-drawn
 * on top of each other and neither read as anything. A few dp of travel separates them, and
 * `graphicsLayer` keeps it out of the layout pass so nothing around it reflows.
 */
@Composable
private fun StoryboardStage(visible: Boolean, content: @Composable () -> Unit) {
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(StageTweenMillis, easing = LinearOutSlowInEasing),
        label = "storyboard_stage",
    )
    if (progress <= 0.01f) return
    Box(
        modifier = Modifier.graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * StageSlidePx
        },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** The title being opened. One wide block over a short one - a card with a caption. */
@Composable
private fun StoryboardTitle() {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DiagramBlock(width = 96.dp, height = 42.dp)
        DiagramBlock(width = 64.dp, height = 7.dp, alpha = 0.5f)
    }
}

/**
 * The source list: every release, as text, with one pointer travelling down it.
 *
 * ⚠ **The pointer is a single object whose offset animates**, not one drawn per row. Revision 5
 * drew it inside whichever row was highlighted, so it vanished from one row and reappeared on the
 * next - which reads as a cut, not as reading. It also meant only the highlighted row carried the
 * pointer's width, and the column centred each row on its own width, so **the picked row visibly
 * jumped sideways**. That was the misalignment in the screenshot. The gutter below is reserved on
 * every row and the pointer is positioned over it.
 */
@Composable
private fun StoryboardSources(frame: SetupStoryboardFrame) {
    val tokens = MaterialTheme.nuvio
    val pointerOffset by animateDpAsState(
        targetValue = (StoryboardRowHeight + StoryboardRowGap) * (frame.highlightedRow ?: 0),
        animationSpec = tween(StageTweenMillis, easing = LinearOutSlowInEasing),
        label = "storyboard_pointer_y",
    )

    Row {
        Column(verticalArrangement = Arrangement.spacedBy(StoryboardRowGap)) {
            setupStoryboardReleases.forEachIndexed { index, release ->
                val chosen = index == frame.highlightedRow
                Box(
                    modifier = Modifier
                        .width(StoryboardListWidth)
                        .height(StoryboardRowHeight)
                        .clip(RoundedCornerShape(5.dp))
                        .background(
                            if (chosen) {
                                tokens.colors.accent.copy(alpha = 0.22f)
                            } else {
                                tokens.colors.textMuted.copy(alpha = 0.10f)
                            },
                        )
                        .border(
                            width = tokens.borders.hairline,
                            color = if (chosen) {
                                tokens.colors.accent.copy(alpha = 0.7f)
                            } else {
                                tokens.colors.textMuted.copy(alpha = 0.25f)
                            },
                            shape = RoundedCornerShape(5.dp),
                        )
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = release,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (chosen) tokens.colors.textPrimary else tokens.colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        }
        StoryboardPointerGutter(frame = frame, offsetY = pointerOffset)
    }
}

/**
 * Streamlined's quality question, and the release it settles on afterwards.
 *
 * Both live here because they are the same object changing: the chips stay up and the unchosen
 * ones fade, rather than a fresh list appearing. Drawing the pick as a new list would say the
 * user had been shown a list, which is the one thing this mode does not do.
 */
@Composable
private fun StoryboardQuality(frame: SetupStoryboardFrame) {
    val tokens = MaterialTheme.nuvio
    val askingQuality = frame.stage == SetupStoryboardStage.Quality
    val pointerOffset by animateDpAsState(
        targetValue = (StoryboardChipHeight + StoryboardRowGap) * (frame.highlightedRow ?: 0),
        animationSpec = tween(StageTweenMillis, easing = LinearOutSlowInEasing),
        label = "storyboard_chip_pointer_y",
    )

    Row {
        Column(verticalArrangement = Arrangement.spacedBy(StoryboardRowGap)) {
            setupStoryboardQualityTokens.forEachIndexed { index, token ->
                val picked = index == frame.highlightedRow
                // Once the quality is picked the unchosen chips fade out and the picked one stays,
                // which is Nuvio answering - with nothing left to tap.
                val rowAlpha by animateFloatAsState(
                    targetValue = if (askingQuality || picked) 1f else 0f,
                    animationSpec = tween(StageTweenMillis, easing = LinearOutSlowInEasing),
                    label = "storyboard_chip_alpha",
                )
                Box(
                    modifier = Modifier
                        .alpha(rowAlpha)
                        .width(StoryboardListWidth)
                        .height(StoryboardChipHeight)
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            if (picked) tokens.colors.accent else tokens.colors.overlayHover,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = token,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (picked) tokens.colors.onAccent else tokens.colors.textSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
        StoryboardPointerGutter(frame = frame, offsetY = pointerOffset)
    }
}

/**
 * The reserved column the pointer moves in.
 *
 * ⚠ **Always present, always the same width, whether or not a pointer is in it.** Revision 5 put
 * the pointer inside the highlighted row, so that row alone was wider than its neighbours and the
 * centring column shifted it - which is the misaligned `4K` chip in the screenshot.
 */
@Composable
private fun StoryboardPointerGutter(frame: SetupStoryboardFrame, offsetY: Dp) {
    Box(modifier = Modifier.width(StoryboardGutterWidth)) {
        if (frame.pointerVisible) {
            DiagramPointer(
                tapping = frame.tapping,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = offsetY),
            )
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
private fun DiagramPointer(tapping: Boolean, modifier: Modifier = Modifier) {
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
        modifier = modifier.size(StoryboardRowHeight),
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
