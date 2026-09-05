package com.nuvio.app.features.playback

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// The one line that differs between nuvio-z and nuviozdesktop.
//
// Desktop wraps coil3 in an expect/actual doing Skia-side downsampling; mobile calls coil3
// directly. The signatures agree, so this alias keeps the rest of the file byte-identical
// across the two repos - the same trick `PlayerOverlays.kt` already uses, for the same reason.
// Matching each repo's own convention is also what keeps the hand-off invisible: this screen
// must decode the backdrop exactly the way its local player does.
import coil3.compose.AsyncImage as AppAsyncImage
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioConsumePointerEvents
import com.nuvio.app.features.downloads.SourceFacts
import com.nuvio.app.features.player.languageLabelForCode
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.playback_fact_audio
import nuvio.composeapp.generated.resources.playback_fact_language
import nuvio.composeapp.generated.resources.playback_fact_range
import nuvio.composeapp.generated.resources.playback_fact_resolution
import nuvio.composeapp.generated.resources.playback_fact_size
import nuvio.composeapp.generated.resources.playback_progress_attempt
import nuvio.composeapp.generated.resources.playback_progress_choosing
import nuvio.composeapp.generated.resources.playback_progress_finding
import nuvio.composeapp.generated.resources.playback_progress_resolving
import nuvio.composeapp.generated.resources.playback_progress_source_failed
import nuvio.composeapp.generated.resources.playback_progress_source_failed_reason
import nuvio.composeapp.generated.resources.playback_progress_starting
import nuvio.composeapp.generated.resources.playback_quality_checking_connection
import nuvio.composeapp.generated.resources.playback_quality_manual
import nuvio.composeapp.generated.resources.subtitle_language_unknown
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The one screen between choosing a source and seeing a frame.
 *
 * Getting from a chosen source to a playing video used to cross three loading surfaces styled
 * by three different hands - the source list's own "Finding streams…", then the route's
 * progress overlay, then the player's opening overlay - two of which appeared and vanished
 * inside a second. This is all three.
 *
 * **It is rendered by both sides of the hand-off** ([PlaybackProgressOverlay] on the route,
 * `OpeningOverlay` in the player) from one [PlaybackLoadingState], and that is the entire
 * design: navigation is untouched, the route still owns resolution and the retry chain and the
 * player still owns playback, but **crossing between them moves nothing on screen**. Same
 * backdrop at the same crop, same logo through the same fallback chain, same band - only the
 * band's text changes.
 *
 * So the rules that keep the hand-off invisible are load-bearing, not styling:
 *
 *  - [artwork] and [logo] must be the same URLs at the same [ContentScale] on both sides, or
 *    the image re-decodes and flickers at the route change.
 *  - **No crossfade, no scale animation, on the shared layers.** The player's overlay used to
 *    pulse its logo between 1f and 1.04f forever; a logo that is mid-pulse on one side of a
 *    route change and at rest on the other is a visible jump even though nothing moved.
 *    Animate the band's contents only.
 *  - One spinner treatment. [ThinProgressLine] replaces the three that used to run here.
 */
@Composable
fun PlaybackLoadingScreen(
    state: PlaybackLoadingState,
    artwork: String?,
    logo: String?,
    title: String?,
    formatSize: (Long) -> String,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 32.dp,
    onBack: (() -> Unit)? = null,
    onChooseManually: (() -> Unit)? = null,
    /**
     * Replaces the derived stage line when the caller genuinely knows better.
     *
     * The P2P path is the only such caller: it reports peers and buffered bytes, which no
     * [PlaybackProgressStep] describes. Everything else must leave this null and let the stage
     * be derived, because a caller-supplied string is exactly the faked stage
     * [PlaybackProgressStep] refuses to be.
     */
    message: String? = null,
    /** A real buffered fraction, or null for the indeterminate line. P2P only. */
    progress: Float? = null,
    /**
     * The one entrance, 0f at the tap and 1f once the screen has arrived.
     *
     * ⚠ **This is not a general-purpose animation hook.** It is driven only by
     * `PlaybackLoadingHost`, only once per session, and is 1f for the entire rest of the flow -
     * including the hand-off to the player and every failover. See [PlaybackLoadingMotion] for
     * why the moment the list is replaced is the single exception to the no-motion rule above.
     */
    entryProgress: Float = 1f,
) {
    // ⚠ **No `graphicsLayer` on this Box, and it must not gain one.** It used to fade and scale
    // the whole surface for the entrance, which meant Compose rendered a full-screen layer -
    // backdrop included - to an offscreen buffer and composited it every frame. On a large window
    // at a 1.4x UI scale that is a very large allocation per frame, and it is the stutter the
    // maintainer reported immediately after choosing a source. The band below carries the
    // entrance instead: it is small, so its layer is cheap.
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.nuvio.colors.background)
            .nuvioConsumePointerEvents(),
    ) {
        PlaybackLoadingBackdrop(
            artwork = artwork,
            scrimProgress = PlaybackLoadingMotion.scrimAlpha(entryProgress),
        )

        if (onBack != null) {
            NuvioBackButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Top))
                    .padding(top = 20.dp, start = horizontalPadding),
                containerColor = Color.Black.copy(alpha = 0.3f),
                contentColor = Color.White,
                buttonSize = 44.dp,
                iconSize = 24.dp,
            )
        }

        // The logo sits on the optical centre of the whole screen and the band grows upward
        // from the bottom, so the band filling in cannot push the logo. That is what makes
        // "same logo, same place" survive a state change as well as a route change.
        PlaybackLoadingTitle(
            logo = logo,
            title = title,
            entryProgress = entryProgress,
            modifier = Modifier.align(Alignment.Center),
        )

        PlaybackLoadingBand(
            state = state,
            formatSize = formatSize,
            onChooseManually = onChooseManually,
            message = message,
            progress = progress,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .graphicsLayer { alpha = PlaybackLoadingMotion.bandAlpha(entryProgress) }
                .padding(horizontal = horizontalPadding)
                .padding(bottom = 48.dp),
        )
    }
}

/**
 * Backdrop and scrim.
 *
 * ⚠ **Also drawn by `StreamDestination`'s opaque hand-off surface**, which is why this is
 * `internal` rather than private. That surface covers the source list from the moment a source is
 * chosen until this screen's session opens, and while it was a flat `nuvio.colors.background` fill
 * it was a grey frame between a list showing the artwork and a loading screen showing the artwork.
 * Every surface on this path has to draw the same picture; a flat one is a flash by definition.
 *
 * Cropped, full-bleed and unanimated. The gradient is the player's, kept verbatim so the two
 * sides of the hand-off scrim the same artwork to the same values - a scrim that differs by
 * even one stop reads as a flash at the route change.
 */
@Composable
internal fun PlaybackLoadingBackdrop(artwork: String?, scrimProgress: Float = 1f) {
    if (artwork.isNullOrBlank()) return
    AppAsyncImage(
        model = artwork,
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.3f * scrimProgress),
                        Color.Black.copy(alpha = 0.6f * scrimProgress),
                        Color.Black.copy(alpha = 0.8f * scrimProgress),
                        Color.Black.copy(alpha = 0.9f * scrimProgress),
                    ),
                ),
            ),
    )
}

/**
 * Logo, or the title, or nothing - `OpeningOverlay`'s fallback chain, not a fork of it.
 *
 * The third arm draws **nothing** rather than a spinner. The old overlay put a spinner here
 * when a title had no logo, which meant the screen's one moving element changed position
 * depending on artwork the user cannot control. The band owns the motion now.
 */
@Composable
private fun PlaybackLoadingTitle(
    logo: String?,
    title: String?,
    entryProgress: Float = 1f,
    modifier: Modifier = Modifier,
) {
    var logoLoadError by remember(logo) { mutableStateOf(false) }
    val logoUrl = logo?.takeIf { it.isNotBlank() }

    Box(
        modifier = modifier
            .padding(horizontal = 24.dp)
            .graphicsLayer { alpha = PlaybackLoadingMotion.titleAlpha(entryProgress) },
        contentAlignment = Alignment.Center,
    ) {
        when {
            logoUrl != null && !logoLoadError -> AppAsyncImage(
                model = logoUrl,
                contentDescription = null,
                modifier = Modifier.width(300.dp).height(180.dp),
                contentScale = ContentScale.Fit,
                onError = { logoLoadError = true },
            )
            !title.isNullOrBlank() -> Text(
                text = title,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.displaySmall
                    .copy(fontWeight = FontWeight.ExtraBold),
            )
        }
    }
}

private val ESCAPE_ROW_HEIGHT = 36.dp

/**
 * The band: what is happening, to what, on whose behalf.
 *
 * Bottom-anchored and left-aligned, and every row is optional. A source that parsed to nothing
 * renders the stage line and the progress line alone, which is a usable screen - the case the
 * pure tests pin, because "no metadata" is common and a layout that only works when the chips
 * are present is a layout that breaks on the sources most likely to be failing.
 */
@Composable
private fun PlaybackLoadingBand(
    state: PlaybackLoadingState,
    formatSize: (Long) -> String,
    onChooseManually: (() -> Unit)?,
    message: String?,
    progress: Float?,
    modifier: Modifier = Modifier,
) {
    val namer = rememberLanguageNamer(state.facts)
    val facts = remember(state.facts, namer) {
        PlaybackLoadingFacts.facts(state.facts, formatSize, namer)
    }
    val providerLine = remember(state.facts) { PlaybackLoadingFacts.providerLine(state.facts) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            PlaybackLoadingStageLine(state)
        }

        MetadataRail(facts = facts)

        providerLine?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.82f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // ⚠ **Always present, always this height.** The escape hatch used to sit below the progress
        // line and appear at five seconds, which grew the band and moved the rail under the reader
        // at exactly the moment they were reading it. Reserving the row means the button arriving
        // changes one cell, not the layout. The release name shares the row because the two are
        // opposite ends of the same line and neither needs the width.
        Row(
            modifier = Modifier.fillMaxWidth().height(ESCAPE_ROW_HEIGHT),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.releaseName.orEmpty(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.45f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (onChooseManually != null && state.offerManualEscape) {
                TextButton(
                    onClick = onChooseManually,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.nuvio.colors.accent,
                    ),
                ) { Text(stringResource(Res.string.playback_quality_manual)) }
            }
        }

        ThinProgressLine(fraction = progress)
    }
}

/**
 * One line: the stage, the attempt when there has been more than one, and the dead source.
 *
 * The failure is **appended to the stage line rather than given a line of its own**, so the
 * band does not change height when a candidate dies. A band that grows on failure moves the
 * chips under the user's eye at the exact moment they are trying to read them.
 */
@Composable
private fun PlaybackLoadingStageLine(state: PlaybackLoadingState) {
    val stage = stageLabel(state.step)
    val attempt = if (state.showsAttempt) {
        stringResource(
            Res.string.playback_progress_attempt,
            state.displayAttempt,
            state.maxAttempts,
        )
    } else {
        null
    }
    val failed = state.failure
    val failureText = when {
        failed == null -> null
        failed.reason.isNullOrBlank() ->
            stringResource(Res.string.playback_progress_source_failed, failed.label)
        else -> stringResource(
            Res.string.playback_progress_source_failed_reason,
            failed.label,
            failed.reason,
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = listOfNotNull(stage, attempt).joinToString(" · "),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (failureText != null) {
                MaterialTheme.nuvio.colors.danger
            } else {
                Color.White
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        failureText?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.nuvio.colors.danger,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Display names for exactly the codes this source names, resolved in composition.
 *
 * `languageLabelForCode` is `@Composable` (it reads the resource bundle) so it cannot be handed
 * to `PlaybackLoadingFacts.facts` as a lambda. It also answers "Unknown" for a code it does not
 * carry, which is worse than the code itself in a four-character slot - hence the fallback.
 */
@Composable
internal fun rememberLanguageNamer(facts: SourceFacts?): (String) -> String {
    val codes = remember(facts) {
        (facts?.languages.orEmpty() + facts?.subtitleLanguages.orEmpty()).toList()
    }
    val unknown = stringResource(Res.string.subtitle_language_unknown)
    val names = codes.associateWith { code ->
        languageLabelForCode(code).takeIf { it != unknown } ?: code.uppercase()
    }
    return remember(names) { { code -> names[code] ?: code.uppercase() } }
}

/**
 * The five facts, always all five, laid out as a spec strip.
 *
 * Wrapping is hand-rolled rather than `FlowRow`, which is still experimental in this Compose
 * version. ⚠ **No dividers between cells and no pill backgrounds.** The pills are most of what
 * made this band read as a phone screen; the label-over-value stack carries the rhythm on its
 * own, and a divider that has to survive a wrap is three times the code for less.
 */
@Composable
private fun MetadataRail(facts: List<PlaybackLoadingFact>, spacing: Dp = 24.dp) {
    Layout(
        content = { facts.forEach { FactCell(it) } },
    ) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        val rows = mutableListOf<MutableList<Pair<Int, androidx.compose.ui.layout.Placeable>>>()
        var row = mutableListOf<Pair<Int, androidx.compose.ui.layout.Placeable>>()
        var x = 0
        placeables.forEach { placeable ->
            if (row.isNotEmpty() && x + placeable.width > constraints.maxWidth) {
                rows += row
                row = mutableListOf()
                x = 0
            }
            row += x to placeable
            x += placeable.width + gap
        }
        if (row.isNotEmpty()) rows += row

        val rowHeights = rows.map { line -> line.maxOf { it.second.height } }
        val height = rowHeights.sum() + gap * (rows.size - 1).coerceAtLeast(0)
        layout(constraints.maxWidth, height) {
            var y = 0
            rows.forEachIndexed { index, line ->
                line.forEach { (offsetX, placeable) -> placeable.place(offsetX, y) }
                y += rowHeights[index] + gap
            }
        }
    }
}

@Composable
private fun FactCell(fact: PlaybackLoadingFact) {
    val known = fact.value != null
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(playbackFactSlotLabelRes(fact.slot)).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = NuvioTokens.LetterSpacing.label,
            color = Color.White.copy(alpha = 0.45f),
            maxLines = 1,
        )
        Text(
            text = fact.value ?: PlaybackLoadingFacts.UNKNOWN,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = if (known) 0.92f else 0.35f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun playbackFactSlotLabelRes(slot: PlaybackFactSlot): StringResource = when (slot) {
    PlaybackFactSlot.RESOLUTION -> Res.string.playback_fact_resolution
    PlaybackFactSlot.LANGUAGE -> Res.string.playback_fact_language
    PlaybackFactSlot.DYNAMIC_RANGE -> Res.string.playback_fact_range
    PlaybackFactSlot.AUDIO -> Res.string.playback_fact_audio
    PlaybackFactSlot.SIZE -> Res.string.playback_fact_size
}

/**
 * The app's one indeterminate treatment: a thin band sweeping left to right.
 *
 * It replaces three spinners that used to run on this path - `NuvioLoadingIndicator` in the
 * route overlay, the player overlay's logo pulse, and the source list's own - which between
 * them meant the *shape of the motion* changed twice on the way to a frame, on a path where
 * nothing had actually gone wrong.
 *
 * Indeterminate on purpose. Resolution has no honest completion figure: the stages are derived
 * from state, not from a fraction, and a bar that fills on a timer is the lie
 * [PlaybackProgressStep] was written to avoid.
 */
@Composable
private fun ThinProgressLine(fraction: Float? = null, modifier: Modifier = Modifier) {
    if (fraction != null) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(Color.White.copy(alpha = 0.14f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(2.dp)
                    .background(MaterialTheme.nuvio.colors.accent),
            )
        }
        return
    }
    val transition = rememberInfiniteTransition(label = "playbackLoadingProgress")
    val head by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "playbackLoadingProgressHead",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(2.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(Color.White.copy(alpha = 0.14f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        // A moving window rather than a growing fill, so the line never
                        // implies a percentage it cannot know.
                        colorStops = arrayOf(
                            ((head - 0.25f).coerceIn(0f, 1f)) to Color.Transparent,
                            head.coerceIn(0f, 1f) to MaterialTheme.nuvio.colors.accent,
                            ((head + 0.25f).coerceIn(0f, 1f)) to Color.Transparent,
                        ),
                    ),
                ),
        )
    }
}

@Composable
private fun stageLabel(step: PlaybackProgressStep): String = when (step) {
    PlaybackProgressStep.FindingSources -> stringResource(Res.string.playback_progress_finding)
    PlaybackProgressStep.CheckingConnection ->
        stringResource(Res.string.playback_quality_checking_connection)
    PlaybackProgressStep.ChoosingSource -> stringResource(Res.string.playback_progress_choosing)
    PlaybackProgressStep.ResolvingLink -> stringResource(Res.string.playback_progress_resolving)
    PlaybackProgressStep.StartingPlayback -> stringResource(Res.string.playback_progress_starting)
}

/** Convenience for call sites that have facts but no state yet. */
fun playbackLoadingState(
    step: PlaybackProgressStep,
    attempt: Int = 1,
    facts: SourceFacts? = null,
    failure: PlaybackProgressFailure? = null,
    offerManualEscape: Boolean = false,
): PlaybackLoadingState = PlaybackLoadingState(
    step = step,
    attempt = attempt,
    facts = facts,
    failure = failure,
    offerManualEscape = offerManualEscape,
)
