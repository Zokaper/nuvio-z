package com.nuvio.app.features.setup

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
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio

/**
 * The band's content on the steps that change nothing visible.
 *
 * ## This is provisional, and it is built to be removed
 *
 * Five steps - Welcome, the playback-mode question, Sources, Trakt and Done - set nothing the
 * specimens can show. Revision 2 left the fake home screen behind them, which put an
 * irrelevant hero banner under the text. The maintainer's call was an illustrative diagram,
 * with the explicit caveat that it may well get cut.
 *
 * So the whole feature is **one file with one public composable and exactly one call site** -
 * the `Diagram` branch of `SetupSpecimenBand`. Removing it means deleting this file, replacing
 * that branch with a `Spacer`, and shrinking `SetupStep.bandHeight` for those steps. Nothing
 * else refers to it.
 *
 * Three deliberate restrictions keep that promise cheap to keep:
 *
 * 1. **Wordless.** No `stringResource`, so no keys to add, translate, or strand when it goes.
 *    The panel underneath is already doing the explaining; this only has to give the eye
 *    somewhere to rest.
 * 2. **No `Canvas`, no path work, no assets.** Rounded rectangles, circles, and five icons that
 *    are already used elsewhere in the app.
 * 3. **No animation of its own.** It inherits the band's crossfade and nothing more. Ambition
 *    is where this would go wrong, so there is none.
 */
@Composable
fun SetupDiagram(
    step: SetupStep,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (step) {
            // The app itself, in the abstract: a banner over two rows of titles. The wizard is
            // about to reshape exactly this, which is the only thing the welcome step means.
            SetupStep.Welcome -> DiagramAppSketch()

            // Three candidate releases, one of them chosen, and playback. The question the step
            // asks is how much of that choosing Nuvio does.
            SetupStep.PlaybackMode -> DiagramSourcePick()

            // An addon on the left filling catalog rows on the right.
            SetupStep.Sources -> DiagramAddonFeed()

            // Two halves exchanging state, which is the whole of what connecting Trakt does.
            SetupStep.Trakt -> DiagramSync()

            SetupStep.Done -> DiagramDone()

            // Never reached - these steps have a real specimen - but enumerated rather than
            // defaulted so that adding a step is a compile error here instead of a blank band.
            SetupStep.Cards,
            SetupStep.Home,
            SetupStep.Details,
            SetupStep.Theme,
            -> DiagramAppSketch()
        }
    }
}

// --- the five diagrams ---------------------------------------------------------------------

@Composable
private fun DiagramAppSketch() {
    val tokens = MaterialTheme.nuvio
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ⚠ 56 + 10 + 40 + 10 + 40 = 156, inside the 180 dp `SetupSpecimen.Diagram` band.
        // This is the tallest of the five diagrams; grow it and it clips.
        DiagramBlock(width = 232.dp, height = 56.dp, color = tokens.colors.accent, alpha = 0.32f)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(3) { DiagramBlock(width = 70.dp, height = 40.dp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(3) { DiagramBlock(width = 70.dp, height = 40.dp, alpha = 0.5f) }
        }
    }
}

@Composable
private fun DiagramSourcePick() {
    val tokens = MaterialTheme.nuvio
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DiagramBlock(width = 104.dp, height = 20.dp, alpha = 0.45f)
            // The chosen one. Accent-filled rather than merely outlined, because "Nuvio picked
            // this" is the entire subject of the step.
            DiagramBlock(width = 104.dp, height = 20.dp, color = tokens.colors.accent, alpha = 1f)
            DiagramBlock(width = 104.dp, height = 20.dp, alpha = 0.45f)
        }
        DiagramArrow(Icons.AutoMirrored.Rounded.KeyboardArrowRight)
        DiagramCircle(icon = Icons.Rounded.PlayArrow)
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
private fun DiagramSync() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) { DiagramBlock(width = 76.dp, height = 20.dp) }
        }
        DiagramArrow(Icons.Rounded.SwapHoriz)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) { DiagramBlock(width = 76.dp, height = 20.dp, alpha = 0.5f) }
        }
    }
}

@Composable
private fun DiagramDone() {
    DiagramCircle(icon = Icons.Rounded.Check, size = 76.dp, filled = true)
}

// --- the three primitives every diagram is made of -------------------------------------------

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
