package com.nuvio.app.features.setup

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.playback.PlaybackLoadingScreen
import com.nuvio.app.features.playback.PlaybackLoadingState
import com.nuvio.app.features.playback.PlaybackProgressStep
import com.nuvio.app.features.player.GestureFeedbackIcon
import com.nuvio.app.features.player.GestureFeedbackState
import com.nuvio.app.features.player.ParentalWarning
import com.nuvio.app.features.player.PauseMetadataOverlay
import com.nuvio.app.features.player.PlayerControlsShell
import com.nuvio.app.features.player.PlayerGestureOverlay
import com.nuvio.app.features.player.PlayerLayoutMetrics
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.PlayerResizeMode
import com.nuvio.app.features.player.SubtitleRenderer
import com.nuvio.app.features.player.SubtitleStyleState
import com.nuvio.app.features.player.formatPlaybackSpeedLabel
import com.nuvio.app.features.player.subtitleFrameGeometry
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

// String keys by wildcard, as `SetupWizardScreen.kt` does.

/**
 * The player previews of Advanced Setup (and of Device Setup's player step), drawn with the
 * **player's own composables** (setup polish, physical QA).
 *
 * ## Why the real components, when the earlier previews drew their own
 *
 * The first Player preview was a hand-drawn chrome over title artwork, with three grey bars standing
 * in for the pause overlay. On a phone it read as a diagram of a player rather than *this* player.
 * The overlays it has to show are all self-contained and state-free - `PlayerControlsShell` (both
 * layouts), `PauseMetadataOverlay`, `PlaybackLoadingScreen` (the one loading surface), the content
 * warnings of `ParentalGuideOverlay` and the gesture feedback of `PlayerGestureOverlay` - so the
 * preview now composes those, fed a sample episode, and cannot drift from them.
 *
 * ## The stage
 *
 * Those composables branch on their container (`PlayerLayoutMetrics.fromWidth`, compact heights in
 * the pause overlay), so they are laid out at a real screen's size - a landscape phone, or a desktop
 * window - and the whole stage is scaled to whatever the band has room for ([PreviewStage]). Nothing
 * is cropped: the stage always fits, at any band size, which is what the phone-width crop of the
 * previous preview got wrong.
 *
 * ⚠ **Desktop** draws its player chrome on the native controls page, not with these composables;
 * they are the same design, but the desktop preview is the one to check against the real window.
 */

/** A landscape phone, in dp: what the mobile player lays itself out against. */
private val PhoneStageWidth = 800.dp
private val PhoneStageHeight = 370.dp

/** The default desktop window's player area. */
private val DesktopStageWidth = 1280.dp
private val DesktopStageHeight = 720.dp

/**
 * Lays [content] out at a fixed [logicalWidth] x [logicalHeight] and scales the whole thing - text,
 * strokes, touch targets - to fit the space it is given, through a density override. The same idea
 * as `SetupPreviewStage`'s fixed logical size, so a composable that measures its container sees the
 * screen it was designed for.
 *
 * Window insets are consumed at the stage: a preview is not the window, and the player's own
 * `windowInsetsPadding` would otherwise push its chrome down by the real status bar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PreviewStage(
    logicalWidth: Dp,
    logicalHeight: Dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    background: Color = Color.Black,
    stageModifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val byWidth = maxWidth / logicalWidth
        val scale = if (constraints.hasBoundedHeight) minOf(byWidth, maxHeight / logicalHeight) else byWidth
        val density = LocalDensity.current
        Box(
            modifier = Modifier
                .size(logicalWidth * scale, logicalHeight * scale)
                .clip(RoundedCornerShape(cornerRadius))
                .background(background),
        ) {
            CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale)) {
                Box(
                    modifier = Modifier
                        .requiredSize(logicalWidth, logicalHeight)
                        .consumeWindowInsets(WindowInsets.safeContent)
                        .then(stageModifier),
                    content = content,
                )
            }
        }
    }
}

/**
 * The episode the player previews show. Deliberately **not** the details step's show: an episode
 * frame of a different title, so the previews do not read as one poster pasted everywhere.
 */
private object PlayerSample {
    const val imdbId = "tt1475582"
    const val title = "Sherlock"
    const val season = 1
    const val episode = 1
    const val episodeTitle = "A Study in Pink"
    const val releaseInfo = "2010"
    const val streamTitle = "Sherlock.S01E01.1080p.BluRay.x265"
    const val provider = "Sample source"
    const val synopsis = "A doctor back from the war meets a consulting detective, and a run of " +
        "impossible suicides turns out to be something else entirely."
    const val durationMs = 88L * 60_000L
    const val positionMs = 31L * 60_000L + 12_000L
    val stillUrl: String get() = SetupSampleTitle.episodeStillUrl(imdbId, season, episode)
    val fallbackUrl: String get() = SetupSampleTitle.backgroundUrl(imdbId)
    val logoUrl: String get() = SetupSampleTitle.logoUrl(imdbId)
}

/** An episode frame: the still, or the show's backdrop when the still host does not answer. */
@Composable
private fun EpisodeFrame(stillUrl: String = PlayerSample.stillUrl, fallbackUrl: String = PlayerSample.fallbackUrl) {
    var failed by remember(stillUrl) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0E))) {
        AsyncImage(
            model = if (failed) fallbackUrl else stillUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onError = { failed = true },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun sampleSnapshot(positionMs: Long = PlayerSample.positionMs, playing: Boolean = true, loading: Boolean = false, speed: Float = 1f) =
    PlayerPlaybackSnapshot(
        isLoading = loading,
        isPlaying = playing && !loading,
        durationMs = PlayerSample.durationMs,
        positionMs = positionMs,
        bufferedPositionMs = positionMs + 4L * 60_000L,
        playbackSpeed = speed,
        videoWidth = 1920,
        videoHeight = 1080,
    )

/** The real controls, in the chosen layout, over whatever frame the caller drew. */
@Composable
private fun SampleControls(
    legacyLayout: Boolean,
    snapshot: PlayerPlaybackSnapshot,
    warnings: List<ParentalWarning> = emptyList(),
    showGuide: Boolean = false,
    onGuideDone: () -> Unit = {},
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        PlayerControlsShell(
            title = PlayerSample.title,
            streamTitle = PlayerSample.streamTitle,
            providerName = PlayerSample.provider,
            seasonNumber = PlayerSample.season,
            episodeNumber = PlayerSample.episode,
            episodeTitle = PlayerSample.episodeTitle,
            playbackSnapshot = snapshot,
            displayedPositionMs = snapshot.positionMs,
            metrics = PlayerLayoutMetrics.fromWidth(maxWidth),
            resizeMode = PlayerResizeMode.Fit,
            isLocked = false,
            useLegacyLayout = legacyLayout,
            releaseInfo = PlayerSample.releaseInfo,
            onLockToggle = {},
            onBack = {},
            onTogglePlayback = {},
            onSeekBack = {},
            onSeekForward = {},
            onResizeModeClick = {},
            onSpeedClick = {},
            onSubtitleClick = {},
            onAudioClick = {},
            onSourcesClick = {},
            onEpisodesClick = {},
            parentalWarnings = warnings,
            showParentalGuide = showGuide,
            onParentalGuideAnimationComplete = onGuideDone,
            onScrubChange = {},
            onScrubFinished = {},
            horizontalSafePadding = 0.dp,
        )
    }
}

@Composable
private fun sampleWarnings(): List<ParentalWarning> = listOf(
    ParentalWarning(stringResource(Res.string.parental_violence), stringResource(Res.string.parental_severity_moderate)),
    ParentalWarning(stringResource(Res.string.parental_profanity), stringResource(Res.string.parental_severity_mild)),
    ParentalWarning(stringResource(Res.string.parental_frightening), stringResource(Res.string.parental_severity_moderate)),
)

// --- Player: layout and overlays ------------------------------------------------------------

/**
 * The Player panel: the playing episode with the real controls in the chosen layout (and the content
 * warnings as they appear at the start of an episode), with the loading surface and the paused
 * screen as two smaller frames beneath - each exactly what the matching switch turns on or off.
 */
@Composable
internal fun SpecimenPlayerChrome(
    legacyLayout: Boolean,
    showLayoutChoice: Boolean,
    pauseOverlay: Boolean,
    loadingOverlay: Boolean,
    contentWarnings: Boolean,
    desktop: Boolean,
    modifier: Modifier = Modifier,
) {
    val stageW = if (desktop) DesktopStageWidth else PhoneStageWidth
    val stageH = if (desktop) DesktopStageHeight else PhoneStageHeight
    val warnings = sampleWarnings()
    // The warnings show for a few seconds at the start of an episode and then leave; replayed on a
    // short loop while the switch is on, so the preview keeps showing what the switch does.
    var guideVisible by remember { mutableStateOf(false) }
    LaunchedEffect(contentWarnings) {
        guideVisible = false
        if (contentWarnings) {
            delay(250)
            guideVisible = true
        }
    }
    var guideLoop by remember { mutableStateOf(0) }
    LaunchedEffect(guideLoop) {
        if (guideLoop > 0 && contentWarnings) {
            delay(1_600)
            guideVisible = true
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .widthIn(max = if (desktop) 980.dp else 560.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PreviewStage(stageW, stageH, modifier = Modifier.weight(0.62f).fillMaxWidth()) {
            EpisodeFrame()
            SampleControls(
                legacyLayout = legacyLayout && showLayoutChoice,
                snapshot = sampleSnapshot(),
                warnings = if (contentWarnings) warnings else emptyList(),
                showGuide = guideVisible,
                onGuideDone = {
                    guideVisible = false
                    guideLoop++
                },
            )
        }
        Row(
            modifier = Modifier.weight(0.38f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CaptionedStage(
                caption = stringResource(Res.string.advanced_preview_while_loading),
                stageWidth = stageW,
                stageHeight = stageH,
                modifier = Modifier.weight(1f),
            ) {
                if (loadingOverlay) {
                    PlaybackLoadingScreen(
                        state = PlaybackLoadingState(step = PlaybackProgressStep.StartingPlayback),
                        artwork = PlayerSample.fallbackUrl,
                        logo = PlayerSample.logoUrl,
                        title = PlayerSample.title,
                        formatSize = { it.toString() },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    // Without the loading screen the player opens straight onto its controls over a
                    // black surface, with the play button spinning until the first frame.
                    SampleControls(legacyLayout = legacyLayout && showLayoutChoice, snapshot = sampleSnapshot(positionMs = 0L, loading = true))
                }
            }
            CaptionedStage(
                caption = stringResource(Res.string.advanced_preview_when_paused),
                stageWidth = stageW,
                stageHeight = stageH,
                modifier = Modifier.weight(1f),
            ) {
                EpisodeFrame()
                if (pauseOverlay) {
                    PauseMetadataOverlay(
                        title = PlayerSample.title,
                        logo = PlayerSample.logoUrl,
                        isEpisode = true,
                        seasonNumber = PlayerSample.season,
                        episodeNumber = PlayerSample.episode,
                        episodeTitle = PlayerSample.episodeTitle,
                        pauseDescription = PlayerSample.synopsis,
                        providerName = PlayerSample.provider,
                        metrics = PlayerLayoutMetrics.fromWidth(stageW),
                        horizontalSafePadding = 0.dp,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun CaptionedStage(
    caption: String,
    stageWidth: Dp,
    stageHeight: Dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        PreviewStage(stageWidth, stageHeight, modifier = Modifier.weight(1f).fillMaxWidth(), cornerRadius = 8.dp, content = content)
        Text(
            text = caption,
            style = MaterialTheme.typography.labelSmall,
            color = tokens.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

// --- Touch: an interactive stage ------------------------------------------------------------

/**
 * The Touch panel: a frame of the player that answers the way the real surface does - tap to show or
 * hide the controls, double-tap either side to seek, hold for the chosen speed, swipe the left or
 * right half for brightness or volume - with the real feedback overlays. Nothing plays; a line under
 * the frame says what the gesture did, so a gesture that is switched off visibly does nothing.
 *
 * The rules are `PlayerScreenRuntimeGestureActions.kt`'s: the centre 20% only hides the controls, a
 * double-tap with gestures off only toggles them, seeks add up in 10 s steps, and hold-to-speed runs
 * only while the finger is down.
 */
@Composable
internal fun SpecimenPlayerTouch(
    legacyLayout: Boolean,
    showLayoutChoice: Boolean,
    gestures: Boolean,
    holdToSpeed: Boolean,
    holdSpeed: Float,
    interactive: Boolean,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val legacy = legacyLayout && showLayoutChoice
    var controlsVisible by remember { mutableStateOf(true) }
    var positionMs by remember { mutableStateOf(PlayerSample.positionMs) }
    var speed by remember { mutableStateOf(1f) }
    var feedback by remember { mutableStateOf<GestureFeedbackState?>(null) }
    var renderedFeedback by remember { mutableStateOf<GestureFeedbackState?>(null) }
    var readout by remember { mutableStateOf<TouchReadout?>(null) }
    var seekAccumulated by remember { mutableStateOf(0L to 0) }
    var clearJob by remember { mutableStateOf<Job?>(null) }
    var brightness by remember { mutableStateOf(0.6f) }
    var volume by remember { mutableStateOf(0.7f) }

    fun show(state: GestureFeedbackState?, text: TouchReadout?, clearAfterMs: Long? = 900L) {
        feedback = state
        if (state != null) renderedFeedback = state
        readout = text
        clearJob?.cancel()
        if (clearAfterMs != null) {
            clearJob = scope.launch {
                delay(clearAfterMs)
                feedback = null
                seekAccumulated = 0L to 0
            }
        }
    }

    // A switch turned off under a gesture in progress ends it, as the real runtime does.
    LaunchedEffect(holdToSpeed, gestures) {
        if (!holdToSpeed) speed = 1f
        feedback = null
    }

    val stageW = PhoneStageWidth
    val stageH = PhoneStageHeight
    Column(
        modifier = modifier
            .fillMaxSize()
            .widthIn(max = 560.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PreviewStage(
            logicalWidth = stageW,
            logicalHeight = stageH,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            stageModifier = if (!interactive) Modifier else Modifier
                .pointerInput(gestures, holdToSpeed, holdSpeed, legacy) {
                    val width = size.width.toFloat()
                    detectTapGestures(
                        onPress = {
                            tryAwaitRelease()
                            if (speed != 1f) {
                                speed = 1f
                                show(null, readout, clearAfterMs = null)
                            }
                        },
                        onTap = { offset: Offset ->
                            val inCentre = offset.x in width * 0.4f..width * 0.6f
                            controlsVisible = if (controlsVisible && inCentre) false else !controlsVisible
                            show(null, TouchReadout(if (controlsVisible) TouchEvent.Shown else TouchEvent.Hidden))
                        },
                        onDoubleTap = { offset: Offset ->
                            val side = when {
                                offset.x < width * 0.4f -> -1
                                offset.x > width * 0.6f -> 1
                                else -> 0
                            }
                            if (!gestures || side == 0) {
                                controlsVisible = !controlsVisible
                                show(
                                    null,
                                    TouchReadout(
                                        when {
                                            !gestures -> TouchEvent.DoubleOff
                                            controlsVisible -> TouchEvent.Shown
                                            else -> TouchEvent.Hidden
                                        },
                                    ),
                                )
                            } else {
                                val (amount, direction) = seekAccumulated
                                val next = if (direction == side) amount + 10_000L else 10_000L
                                seekAccumulated = next to side
                                positionMs = (positionMs + side * 10_000L).coerceIn(0L, PlayerSample.durationMs)
                                val seconds = next / 1000L
                                show(
                                    GestureFeedbackState(
                                        messageRes = if (side > 0) Res.string.compose_player_seek_feedback_forward else Res.string.compose_player_seek_feedback_backward,
                                        messageArgs = listOf(seconds),
                                        icon = if (side > 0) GestureFeedbackIcon.SeekForward else GestureFeedbackIcon.SeekBackward,
                                    ),
                                    TouchReadout(if (side > 0) TouchEvent.SeekForward else TouchEvent.SeekBack, seconds.toString()),
                                )
                            }
                        },
                        onLongPress = {
                            if (holdToSpeed) {
                                speed = holdSpeed
                                show(
                                    GestureFeedbackState(message = formatPlaybackSpeedLabel(holdSpeed), icon = GestureFeedbackIcon.Speed),
                                    TouchReadout(TouchEvent.Hold, formatPlaybackSpeedLabel(holdSpeed)),
                                    clearAfterMs = null,
                                )
                            } else {
                                show(null, TouchReadout(TouchEvent.HoldOff))
                            }
                        },
                    )
                }
                .pointerInput(gestures) {
                    val width = size.width.toFloat()
                    val height = size.height.toFloat().coerceAtLeast(1f)
                    var leftSide = true
                    detectVerticalDragGestures(
                        onDragStart = { start -> leftSide = start.x < width * 0.5f },
                        onDragEnd = { if (gestures) show(feedback, readout, clearAfterMs = 700L) },
                        onDragCancel = { if (gestures) show(feedback, readout, clearAfterMs = 700L) },
                    ) { change, dragAmount ->
                        change.consume()
                        if (!gestures) {
                            show(null, TouchReadout(TouchEvent.SwipeOff))
                            return@detectVerticalDragGestures
                        }
                        val delta = -dragAmount / height
                        if (leftSide) {
                            brightness = (brightness + delta).coerceIn(0f, 1f)
                            show(
                                GestureFeedbackState(icon = GestureFeedbackIcon.Brightness, level = brightness),
                                TouchReadout(TouchEvent.Brightness, "${(brightness * 100).toInt()}%"),
                                clearAfterMs = null,
                            )
                        } else {
                            volume = (volume + delta).coerceIn(0f, 1f)
                            show(
                                GestureFeedbackState(
                                    icon = if (volume <= 0f) GestureFeedbackIcon.VolumeMuted else GestureFeedbackIcon.Volume,
                                    level = volume,
                                ),
                                TouchReadout(TouchEvent.Volume, "${(volume * 100).toInt()}%"),
                                clearAfterMs = null,
                            )
                        }
                    }
                },
        ) {
            EpisodeFrame()
            // Brightness is the one gesture a still frame can show itself.
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (0.6f - brightness).coerceIn(0f, 0.6f))))
            androidx.compose.animation.AnimatedVisibility(
                visible = controlsVisible,
                enter = androidx.compose.animation.fadeIn(tween(180)),
                exit = androidx.compose.animation.fadeOut(tween(160)),
            ) {
                SampleControls(legacyLayout = legacy, snapshot = sampleSnapshot(positionMs = positionMs, speed = speed))
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                PlayerGestureOverlay(
                    currentFeedback = feedback,
                    renderedFeedback = renderedFeedback,
                    useLegacyLayout = legacy,
                    horizontalSafePadding = 0.dp,
                    horizontalPadding = PlayerLayoutMetrics.fromWidth(maxWidth).horizontalPadding,
                )
            }
        }
        Text(
            text = readout?.let { touchReadoutText(it) } ?: stringResource(Res.string.advanced_preview_touch_hint),
            style = MaterialTheme.typography.labelMedium,
            color = if (readout != null) tokens.colors.textPrimary else tokens.colors.textMuted,
            fontWeight = if (readout != null) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** What the last gesture on the Touch stage did, for the line under it. */
private enum class TouchEvent { Shown, Hidden, DoubleOff, SeekForward, SeekBack, Hold, HoldOff, Brightness, Volume, SwipeOff }

private data class TouchReadout(val event: TouchEvent, val value: String = "")

@Composable
private fun touchReadoutText(readout: TouchReadout): String = when (readout.event) {
    TouchEvent.Shown -> stringResource(Res.string.advanced_preview_touch_tap_show)
    TouchEvent.Hidden -> stringResource(Res.string.advanced_preview_touch_tap_hide)
    TouchEvent.DoubleOff -> stringResource(Res.string.advanced_preview_touch_double_off)
    TouchEvent.SeekForward -> stringResource(Res.string.advanced_preview_touch_seek_forward, readout.value)
    TouchEvent.SeekBack -> stringResource(Res.string.advanced_preview_touch_seek_back, readout.value)
    TouchEvent.Hold -> stringResource(Res.string.advanced_preview_touch_hold, readout.value)
    TouchEvent.HoldOff -> stringResource(Res.string.advanced_preview_touch_hold_off)
    TouchEvent.Brightness -> stringResource(Res.string.advanced_preview_touch_brightness, readout.value)
    TouchEvent.Volume -> stringResource(Res.string.advanced_preview_touch_volume, readout.value)
    TouchEvent.SwipeOff -> stringResource(Res.string.advanced_preview_touch_swipe_off)
}

// --- Subtitles ------------------------------------------------------------------------------

/**
 * A line of subtitles over an episode frame, drawn the way **this device's** player draws it: the
 * size, the gap above the bottom, the outline and the background box all come from
 * `subtitleFrameGeometry`, which is also what the engines hand their renderer. The frame is a real
 * screen's size (a landscape phone, or the desktop window), scaled to fit - so ExoPlayer's fixed sp
 * text and mpv's frame-relative text each look the size they will.
 */
@Composable
internal fun SpecimenSubtitles(
    style: SubtitleStyleState,
    renderer: SubtitleRenderer,
    desktop: Boolean,
    modifier: Modifier = Modifier,
) {
    val stageW = if (desktop) DesktopStageWidth else PhoneStageWidth
    val stageH = if (desktop) DesktopStageHeight else PhoneStageHeight
    Box(
        modifier = modifier
            .fillMaxSize()
            .widthIn(max = if (desktop) 980.dp else 560.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        PreviewStage(stageW, stageH, modifier = Modifier.fillMaxSize()) {
            EpisodeFrame(stillUrl = SetupSampleTitle.episodeStillUrl(PlayerSample.imdbId, 1, 2))
            val geometry = subtitleFrameGeometry(
                renderer = renderer,
                fontSizeSp = style.fontSizeSp,
                outlineEnabled = style.outlineEnabled,
                outlineWidth = style.outlineWidth,
                bottomOffset = style.bottomOffset,
                backgroundVisible = style.backgroundColor.alpha > 0f,
                frameHeightDp = stageH.value,
            )
            val density = LocalDensity.current
            val sizeSp = if (geometry.fontSizeIsSp) geometry.fontSize else with(density) { geometry.fontSize.dp.toSp().value }
            val animatedSize by animateFloatAsState(sizeSp, tween(PlayerPreviewTweenMillis), label = "subtitle_size")
            val animatedBottom by animateFloatAsState(geometry.bottomDp, tween(PlayerPreviewTweenMillis), label = "subtitle_bottom")
            val line = stringResource(Res.string.advanced_preview_subtitle_line)
            val base = TextStyle(
                fontSize = animatedSize.sp,
                fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                lineHeight = (animatedSize * 1.2f).sp,
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = animatedBottom.dp)
                    .padding(horizontal = stageW * 0.08f)
                    .then(
                        if (geometry.boxed) {
                            Modifier
                                .clip(RoundedCornerShape(geometry.boxCornerDp.dp))
                                .background(style.backgroundColor)
                                .padding(horizontal = geometry.boxPaddingHorizontalDp.dp, vertical = geometry.boxPaddingVerticalDp.dp)
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (geometry.outlineStrokeDp > 0f) {
                    Text(
                        text = line,
                        style = base.copy(
                            color = style.outlineColor,
                            drawStyle = Stroke(width = with(density) { geometry.outlineStrokeDp.dp.toPx() }, join = StrokeJoin.Round),
                        ),
                    )
                }
                Text(text = line, style = base.copy(color = style.textColor))
            }
        }
    }
}

private const val PlayerPreviewTweenMillis = 240
