package com.nuvio.app.features.setup

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.playback.PlaybackMode
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.app_brand_name
import nuvio.composeapp.generated.resources.app_logo_wordmark
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The band's content on the steps that change nothing visible.
 *
 * ## This is provisional, and it is built to be removed
 *
 * Four steps - Welcome, the playback-mode question, Sources and Done - set nothing the specimens
 * can show. Revision 2 left the fake home screen behind them, which put an irrelevant hero
 * banner under the text. The maintainer's call was an illustrative diagram, with the explicit
 * caveat that it may well get cut.
 *
 * So the whole feature is **one file with one public composable and exactly one call site** -
 * the `Diagram` branch of `SetupSpecimenBand`. Removing it means deleting this file, replacing
 * that branch with a `Spacer`, and shrinking `SetupSpecimen.Diagram.preferredHeight`. Nothing
 * else refers to it.
 *
 * Restrictions that keep that promise cheap to keep:
 *
 * 1. **Almost wordless.** The only string is `app_brand_name`, and only as the wordmark's
 *    accessibility label - so there is nothing here to translate and nothing stranded when it
 *    goes. The panel underneath does the explaining.
 * 2. **No `Canvas` and no path work.** Rounded rectangles, circles, four icons already used
 *    elsewhere in the app, and one existing drawable.
 * 3. **Animation only where it answers a question.** The playback-mode diagram moves because
 *    the step is *about* how much Nuvio decides and a static picture could not say. Nothing
 *    else animates beyond the band's own crossfade.
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
            // The app's own wordmark. ⚠ The asset has "Nuvio" baked into it as pixels, so it
            // reads "Nuvio" above copy that says "Nuvio Z" - a known mismatch the maintainer
            // accepted rather than have the welcome step use different lettering from the
            // splash and auth screens, which draw this same PNG. Redrawing it fixes all three.
            SetupStep.Welcome -> DiagramWordmark()

            // Candidate releases and playback. The step asks how much of the choosing Nuvio
            // does, so the drawing has to answer that differently for each mode - otherwise it
            // is decoration on the one step where it could be doing work.
            SetupStep.PlaybackMode -> DiagramSourcePick(playbackMode)

            // An addon on the left filling catalog rows on the right.
            SetupStep.Sources -> DiagramAddonFeed()

            SetupStep.Done -> DiagramDone()

            // Never reached - these steps have a real specimen - but enumerated rather than
            // defaulted so that adding a step is a compile error here instead of a blank band.
            SetupStep.Cards,
            SetupStep.Home,
            SetupStep.Details,
            SetupStep.Theme,
            -> DiagramWordmark()
        }
    }
}

// --- the five diagrams ---------------------------------------------------------------------

/**
 * The app's wordmark, over a soft accent wash.
 *
 * The only decorative use of accent colour in the whole wizard - everything else keeps the
 * app's flat dark surfaces. The wash is a vertical gradient rather than a radial because
 * `Brush.radialGradient` needs a pixel radius and this has to look the same on every screen.
 */
@Composable
private fun DiagramWordmark() {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = Modifier
            .width(300.dp)
            .height(132.dp)
            .background(
                Brush.verticalGradient(
                    listOf(
                        tokens.colors.accent.copy(alpha = 0.14f),
                        Color.Transparent,
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(Res.drawable.app_logo_wordmark),
            contentDescription = stringResource(Res.string.app_brand_name),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth(0.78f),
        )
    }
}

/**
 * Candidate releases on the left, playback on the right, and how much of the gap Nuvio closes.
 *
 * The highlight animates between states rather than cutting, so tapping a mode in the panel
 * below reads as the diagram answering rather than as a different picture appearing.
 */
@Composable
private fun DiagramSourcePick(mode: PlaybackMode) {
    val tokens = MaterialTheme.nuvio

    // Which of the three releases Nuvio has settled on: none in Classic (the user reads them
    // all), the middle one in Streamlined, and in Instant the list has collapsed to one.
    val chosenIndex = when (mode) {
        PlaybackMode.CLASSIC -> -1
        PlaybackMode.STREAMLINED -> 1
        PlaybackMode.INSTANT -> 1
    }
    val listAlpha by animateFloatAsState(
        targetValue = if (mode == PlaybackMode.INSTANT) 0f else 1f,
        animationSpec = tween(DiagramTweenMillis),
        label = "diagram_list_alpha",
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) { index ->
                val chosen = index == chosenIndex
                // Instant fades the unchosen releases away entirely: nothing is presented,
                // which is the difference between it and Streamlined.
                val alpha by animateFloatAsState(
                    targetValue = when {
                        chosen -> 1f
                        else -> 0.45f * listAlpha
                    },
                    animationSpec = tween(DiagramTweenMillis),
                    label = "diagram_row_alpha",
                )
                DiagramBlock(
                    width = 104.dp,
                    height = 20.dp,
                    color = if (chosen) tokens.colors.accent else tokens.colors.textMuted,
                    alpha = alpha,
                )
            }
        }
        DiagramArrow(Icons.AutoMirrored.Rounded.KeyboardArrowRight)
        DiagramCircle(icon = Icons.Rounded.PlayArrow, filled = mode == PlaybackMode.INSTANT)
    }
}

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

// --- the three primitives every diagram is made of -------------------------------------------

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
