package com.nuvio.app.features.setup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioPosterCard
import com.nuvio.app.core.ui.NuvioPosterShape
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.player.SubtitleStyleState
import com.nuvio.app.features.player.skip.AutoSkipSegmentType
import com.nuvio.app.features.player.skip.NextEpisodeThresholdMode
import com.nuvio.app.features.settings.DesktopNavigationLayout
import com.nuvio.app.features.settings.NavBarStyle
import com.nuvio.app.features.settings.NavigationBarPreview
import com.nuvio.app.features.streams.StreamBackgroundMode
import com.nuvio.app.features.streams.StreamBadgePlacement
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamCard
import com.nuvio.app.features.streams.StreamItem
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

// String keys by wildcard, as `SetupWizardScreen.kt` does: these specimens label a dozen parts.

/**
 * The live previews Advanced Setup draws beside or above its panels (plan §11).
 *
 * ## Same contract as `SetupSpecimen.kt`
 *
 * **Every value arrives as a parameter; nothing here reads a repository.** That is what lets the
 * render harness draw every panel at every width without the app around it, and what keeps a
 * preview from ever showing a value the panel beside it has not just written. The exceptions are
 * the *real* components the plan asks for - [NuvioPosterCard], [NavigationBarPreview],
 * [StreamCard] - which are the shipped composables themselves, fed fixed sample data, so they
 * cannot drift from the app; the poster card reads the poster style it is previewing, exactly as
 * it does on Home.
 *
 * **Offline-safe (W5).** Artwork is the same keyless sample set the wizard uses
 * ([SetupSampleTitle]) and every image sits on a drawn floor, so a preview with no network reads as
 * a dimmed screen, not a broken one.
 *
 * **Pointer-blocked.** A preview is a picture of a control, not the control: [AdvancedPreviewFrame]
 * swallows every pointer event so a tap on the sample stream row or the nav bar does nothing.
 */
@Composable
internal fun AdvancedPreviewFrame(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = modifier
            .background(
                Brush.verticalGradient(
                    0f to tokens.colors.background,
                    0.55f to tokens.colors.background,
                    1f to tokens.colors.surface,
                ),
            )
            .clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        content()
        // Last child, so it is hit first: nothing underneath receives a press, a release or a hover.
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }
                },
        )
    }
}

private const val PreviewTweenMillis = 260

// --- player ------------------------------------------------------------------------------

/**
 * The player's own chrome over a still, in the chosen layout, with the three overlays the Player
 * panel switches shown as they would appear: the content-warning card on the frame, and the loading
 * and pause screens as two small frames beneath it.
 *
 * ⚠ Drawn, not the real player controls: those are bound to a running engine. What must stay true
 * is the *difference* the layout switch makes - the legacy layout's centred transport and top title
 * against the current layout's bottom-anchored title and control row.
 */
@Composable
internal fun SpecimenPlayerChrome(
    legacyLayout: Boolean,
    showLayoutChoice: Boolean,
    pauseOverlay: Boolean,
    loadingOverlay: Boolean,
    contentWarnings: Boolean,
    touchPanel: Boolean,
    touchGestures: Boolean,
    holdToSpeed: Boolean,
    holdSpeed: Float,
    scale: Float = 1f,
) {
    val sample = SetupSampleTitle.rowItems.first()
    Column(
        modifier = Modifier
            .widthIn(max = 520.dp * scale)
            .fillMaxWidth()
            .padding(horizontal = 20.dp * scale),
        verticalArrangement = Arrangement.spacedBy(10.dp * scale),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PreviewVideoFrame(backdropUrl = SetupSampleTitle.backgroundUrl(sample.id)) {
            if (!touchPanel) {
                PlayerChromeOverlay(
                    title = sample.name,
                    legacyLayout = legacyLayout && showLayoutChoice,
                    scale = scale,
                )
                androidx.compose.animation.AnimatedVisibility(
                    visible = contentWarnings,
                    enter = fadeIn(tween(PreviewTweenMillis)),
                    exit = fadeOut(tween(PreviewTweenMillis)),
                    modifier = Modifier.align(if (legacyLayout && showLayoutChoice) Alignment.CenterStart else Alignment.TopStart),
                ) {
                    PreviewContentWarning(scale = scale)
                }
            } else {
                PlayerTouchOverlay(
                    gestures = touchGestures,
                    holdToSpeed = holdToSpeed,
                    holdSpeed = holdSpeed,
                    scale = scale,
                )
            }
        }
        if (!touchPanel) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp * scale),
            ) {
                PreviewMiniFrame(
                    caption = stringResource(Res.string.advanced_preview_while_loading),
                    modifier = Modifier.weight(1f),
                    scale = scale,
                ) {
                    if (loadingOverlay) {
                        AsyncImage(
                            model = SetupSampleTitle.backgroundUrl(sample.id),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
                        AsyncImage(
                            model = SetupSampleTitle.logoUrl(sample.id),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.align(Alignment.Center).fillMaxWidth(0.5f).height(22.dp * scale),
                        )
                    }
                    PreviewSpinnerDot(
                        modifier = Modifier.align(if (loadingOverlay) Alignment.BottomCenter else Alignment.Center)
                            .padding(bottom = if (loadingOverlay) 8.dp * scale else 0.dp),
                        scale = scale,
                    )
                }
                PreviewMiniFrame(
                    caption = stringResource(Res.string.advanced_preview_when_paused),
                    modifier = Modifier.weight(1f),
                    scale = scale,
                ) {
                    AsyncImage(
                        model = SetupSampleTitle.backgroundUrl(sample.id),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (pauseOverlay) {
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)),
                            ),
                        )
                        Column(
                            modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp * scale).fillMaxWidth(0.6f),
                            verticalArrangement = Arrangement.spacedBy(3.dp * scale),
                        ) {
                            PreviewBar(widthFraction = 0.9f, height = 6.dp * scale, alpha = 0.9f)
                            PreviewBar(widthFraction = 0.7f, height = 3.dp * scale, alpha = 0.5f)
                            PreviewBar(widthFraction = 0.8f, height = 3.dp * scale, alpha = 0.5f)
                        }
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Pause,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.align(Alignment.Center).size(20.dp * scale),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewVideoFrame(
    backdropUrl: String,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF101014)),
    ) {
        AsyncImage(
            model = backdropUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        content()
    }
}

@Composable
private fun PreviewMiniFrame(
    caption: String,
    modifier: Modifier,
    scale: Float,
    content: @Composable BoxScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp * scale)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black),
        ) {
            content()
        }
        Text(
            text = caption,
            style = MaterialTheme.typography.labelSmall,
            color = tokens.colors.textMuted,
            maxLines = 1,
        )
    }
}

@Composable
private fun PreviewSpinnerDot(modifier: Modifier, scale: Float) {
    Box(
        modifier = modifier
            .size(14.dp * scale)
            .border(2.dp, Color.White.copy(alpha = 0.85f), CircleShape),
    )
}

@Composable
private fun PreviewBar(widthFraction: Float, height: Dp, alpha: Float, color: Color = Color.White) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(RoundedCornerShape(99.dp))
            .background(color.copy(alpha = alpha)),
    )
}

@Composable
private fun BoxScope.PlayerChromeOverlay(title: String, legacyLayout: Boolean, scale: Float) {
    // The scrim both layouts draw so their white controls read over any frame.
    Box(
        Modifier.matchParentSize().background(
            Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.55f),
                0.35f to Color.Transparent,
                0.6f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.75f),
            ),
        ),
    )
    val iconSize = 18.dp * scale
    if (legacyLayout) {
        // The original controls: title across the top, transport in the middle, seek bar below.
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp * scale),
        )
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(22.dp * scale),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.FastRewind, null, tint = Color.White, modifier = Modifier.size(iconSize * 1.3f))
            Box(
                modifier = Modifier.size(44.dp * scale).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Pause, null, tint = Color.White, modifier = Modifier.size(iconSize * 1.5f))
            }
            Icon(Icons.Rounded.FastForward, null, tint = Color.White, modifier = Modifier.size(iconSize * 1.3f))
        }
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp * scale, vertical = 10.dp * scale),
        ) {
            PreviewSeekBar(progress = 0.38f, scale = scale)
        }
    } else {
        // The current layout: everything anchored to the bottom, title above the seek bar and one
        // row of controls under it.
        Column(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 12.dp * scale, vertical = 10.dp * scale),
            verticalArrangement = Arrangement.spacedBy(6.dp * scale),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
            PreviewSeekBar(progress = 0.38f, scale = scale)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp * scale),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Pause, null, tint = Color.White, modifier = Modifier.size(iconSize))
                Icon(Icons.Rounded.FastRewind, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(iconSize))
                Icon(Icons.Rounded.FastForward, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(iconSize))
                Spacer(Modifier.weight(1f))
                Icon(Icons.Rounded.Subtitles, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(iconSize))
                Icon(Icons.Rounded.SkipNext, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(iconSize))
            }
        }
    }
}

@Composable
private fun PreviewSeekBar(progress: Float, scale: Float) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp * scale)
            .clip(RoundedCornerShape(99.dp))
            .background(Color.White.copy(alpha = 0.3f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress)
                .fillMaxHeight()
                .background(tokens.colors.accent),
        )
    }
}

@Composable
private fun PreviewContentWarning(scale: Float) {
    Row(
        modifier = Modifier
            .padding(10.dp * scale)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 8.dp * scale, vertical = 6.dp * scale),
        horizontalArrangement = Arrangement.spacedBy(6.dp * scale),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(24.dp * scale).background(MaterialTheme.nuvio.colors.accent))
        Column {
            Text(
                text = stringResource(Res.string.settings_playback_parental_guide),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(Res.string.advanced_preview_warning_sample),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.75f),
            )
        }
    }
}

@Composable
private fun BoxScope.PlayerTouchOverlay(gestures: Boolean, holdToSpeed: Boolean, holdSpeed: Float, scale: Float) {
    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.35f)))
    val gestureAlpha by animateFloatAsState(if (gestures) 1f else 0.18f, tween(PreviewTweenMillis), label = "touch_gestures")
    Row(Modifier.matchParentSize()) {
        TouchZone(
            label = stringResource(Res.string.advanced_preview_swipe_brightness),
            modifier = Modifier.weight(1f).fillMaxHeight(),
            alpha = gestureAlpha,
            scale = scale,
        )
        Box(Modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.15f * gestureAlpha)))
        TouchZone(
            label = stringResource(Res.string.advanced_preview_swipe_volume),
            modifier = Modifier.weight(1f).fillMaxHeight(),
            alpha = gestureAlpha,
            scale = scale,
        )
    }
    Text(
        text = stringResource(if (gestures) Res.string.advanced_preview_double_tap else Res.string.advanced_preview_gestures_off),
        style = MaterialTheme.typography.labelSmall,
        color = Color.White.copy(alpha = 0.85f),
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp * scale),
    )
    androidx.compose.animation.AnimatedVisibility(
        visible = holdToSpeed,
        enter = fadeIn(tween(PreviewTweenMillis)),
        exit = fadeOut(tween(PreviewTweenMillis)),
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp * scale),
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(99.dp))
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 12.dp * scale, vertical = 6.dp * scale),
            horizontalArrangement = Arrangement.spacedBy(6.dp * scale),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.TouchApp, null, tint = Color.White, modifier = Modifier.size(14.dp * scale))
            Text(
                text = stringResource(Res.string.advanced_preview_hold_speed, formatSpeed(holdSpeed)),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun TouchZone(label: String, modifier: Modifier, alpha: Float, scale: Float) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp * scale)) {
            Box(
                Modifier
                    .width(4.dp * scale)
                    .height(34.dp * scale)
                    .clip(RoundedCornerShape(99.dp))
                    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = alpha), Color.White.copy(alpha = 0.1f * alpha)))),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = alpha),
            )
        }
    }
}

/** "2", "1.5", "2.25" - the hold speed the way the settings row writes it. */
internal fun formatSpeed(speed: Float): String {
    val hundredths = kotlin.math.round(speed * 100f).toInt()
    val whole = hundredths / 100
    val fraction = hundredths % 100
    return when {
        fraction == 0 -> whole.toString()
        fraction % 10 == 0 -> "$whole.${fraction / 10}"
        else -> "$whole.${fraction.toString().padStart(2, '0')}"
    }
}

// --- skipping ----------------------------------------------------------------------------

/**
 * One episode as a timeline: recap, intro, the episode, and the end credits, each marked with what
 * the chosen settings do there - a Skip button, skipped automatically, or nothing - and, on the
 * next-episode panel, where the next episode is offered and whether it then starts by itself.
 */
@Composable
internal fun SpecimenSkipTimeline(
    skipIntro: Boolean,
    autoSkip: Set<AutoSkipSegmentType>,
    showNextEpisode: Boolean,
    autoPlayNext: Boolean,
    thresholdMode: NextEpisodeThresholdMode,
    thresholdPercent: Float,
    thresholdMinutes: Float,
    scale: Float = 1f,
) {
    val tokens = MaterialTheme.nuvio
    // A 45-minute episode, laid out in proportion.
    val segments = listOf(
        Triple(AutoSkipSegmentType.RECAP, 0.00f, 0.05f),
        Triple(AutoSkipSegmentType.INTRO, 0.05f, 0.11f),
        Triple(null, 0.11f, 0.93f),
        Triple(AutoSkipSegmentType.OUTRO, 0.93f, 1.00f),
    )
    val markerFraction = when (thresholdMode) {
        NextEpisodeThresholdMode.PERCENTAGE -> (thresholdPercent / 100f).coerceIn(0.5f, 1f)
        NextEpisodeThresholdMode.MINUTES_BEFORE_END -> (1f - thresholdMinutes / 45f).coerceIn(0.5f, 1f)
    }
    // The prompt appears at the outro when there is one; the threshold is the fallback.
    val promptAt by animateFloatAsState(minOf(markerFraction, 0.93f), tween(PreviewTweenMillis), label = "next_marker")

    Column(
        modifier = Modifier.widthIn(max = 560.dp * scale).fillMaxWidth().padding(horizontal = 20.dp * scale),
        verticalArrangement = Arrangement.spacedBy(12.dp * scale),
    ) {
        Row(Modifier.fillMaxWidth().height(34.dp * scale).clip(RoundedCornerShape(8.dp))) {
            segments.forEach { (type, start, end) ->
                val state = segmentState(type, skipIntro, autoSkip)
                val color by animateColorAsState(
                    when (state) {
                        SegmentState.Auto -> tokens.colors.accent
                        SegmentState.Button -> tokens.colors.accent.copy(alpha = 0.45f)
                        SegmentState.Plays -> if (type == null) tokens.colors.surfaceCard else tokens.colors.overlayHover
                    },
                    tween(PreviewTweenMillis),
                    label = "segment_color",
                )
                Box(
                    modifier = Modifier
                        .weight(end - start)
                        .fillMaxHeight()
                        .padding(end = if (end < 1f) 2.dp else 0.dp)
                        .background(color),
                    contentAlignment = Alignment.Center,
                ) {
                    if (type == null) {
                        Text(
                            text = "S1 · E1",
                            style = MaterialTheme.typography.labelSmall,
                            color = tokens.colors.textMuted,
                        )
                    }
                }
            }
        }
        // The legend: every marked segment, in order, with what happens there.
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp * scale),
        ) {
            segments.mapNotNull { it.first }.forEach { type ->
                val state = segmentState(type, skipIntro, autoSkip)
                SegmentChip(
                    name = segmentLabel(type),
                    outcome = stringResource(
                        when (state) {
                            SegmentState.Auto -> Res.string.advanced_preview_skipped_auto
                            SegmentState.Button -> Res.string.advanced_preview_skip_button
                            SegmentState.Plays -> Res.string.advanced_preview_segment_plays
                        },
                    ),
                    highlighted = state != SegmentState.Plays,
                    scale = scale,
                )
            }
        }
        androidx.compose.animation.AnimatedVisibility(visible = showNextEpisode) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp * scale)) {
                Box(Modifier.fillMaxWidth().height(10.dp * scale)) {
                    Box(
                        Modifier
                            .fillMaxWidth(promptAt)
                            .height(2.dp)
                            .align(Alignment.CenterStart)
                            .background(tokens.colors.borderSubtle),
                    )
                    Box(
                        Modifier
                            .fillMaxWidth(promptAt)
                            .align(Alignment.CenterStart),
                    ) {
                        Box(
                            Modifier.align(Alignment.CenterEnd).size(10.dp * scale).clip(CircleShape).background(tokens.colors.accent),
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(tokens.colors.surfaceCard)
                        .padding(horizontal = 12.dp * scale, vertical = 10.dp * scale),
                    horizontalArrangement = Arrangement.spacedBy(10.dp * scale),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = SetupSampleTitle.episodeStillUrl(SetupSampleTitle.featuredImdbId, 1, 2),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(64.dp * scale)
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(tokens.colors.overlayHover),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "S1 · E2",
                            style = MaterialTheme.typography.labelMedium,
                            color = tokens.colors.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(
                                if (autoPlayNext) Res.string.advanced_preview_next_plays else Res.string.advanced_preview_next_asks,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = tokens.colors.textSecondary,
                        )
                    }
                    Icon(
                        imageVector = if (autoPlayNext) Icons.Rounded.PlayArrow else Icons.Rounded.SkipNext,
                        contentDescription = null,
                        tint = tokens.colors.accent,
                        modifier = Modifier.size(20.dp * scale),
                    )
                }
            }
        }
    }
}

private enum class SegmentState { Plays, Button, Auto }

private fun segmentState(type: AutoSkipSegmentType?, skipIntro: Boolean, autoSkip: Set<AutoSkipSegmentType>): SegmentState = when {
    type == null -> SegmentState.Plays
    type in autoSkip -> SegmentState.Auto
    // "Skip Intro" is the button for every detected segment, not only intros.
    skipIntro -> SegmentState.Button
    else -> SegmentState.Plays
}

@Composable
private fun segmentLabel(type: AutoSkipSegmentType): String = stringResource(
    when (type) {
        AutoSkipSegmentType.INTRO -> Res.string.settings_playback_auto_skip_intro
        AutoSkipSegmentType.RECAP -> Res.string.settings_playback_auto_skip_recap
        AutoSkipSegmentType.OUTRO -> Res.string.settings_playback_auto_skip_outro
        AutoSkipSegmentType.MOVIE_CREDITS -> Res.string.settings_playback_auto_skip_movie_credits
    },
)

@Composable
private fun SegmentChip(name: String, outcome: String, highlighted: Boolean, scale: Float) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (highlighted) tokens.colors.accent.copy(alpha = 0.14f) else tokens.colors.overlayHover)
            .padding(horizontal = 10.dp * scale, vertical = 6.dp * scale),
    ) {
        Text(text = name, style = MaterialTheme.typography.labelMedium, color = tokens.colors.textPrimary, maxLines = 1)
        Text(
            text = outcome,
            style = MaterialTheme.typography.labelSmall,
            color = if (highlighted) tokens.colors.accent else tokens.colors.textMuted,
            maxLines = 1,
        )
    }
}

// --- subtitles ---------------------------------------------------------------------------

/**
 * A real line of subtitle text, drawn with the chosen size, colours, outline, weight and offset
 * over a frame of the sample title.
 *
 * The size is the player's own sp value scaled to the frame: the player draws it against a full
 * screen, the preview against a frame roughly half a phone's width, so a straight copy would
 * overstate every size by about half. [SubtitleSizeFrameRatio] is that ratio.
 */
@Composable
internal fun SpecimenSubtitles(
    style: SubtitleStyleState,
    scale: Float = 1f,
) {
    val sample = SetupSampleTitle.rowItems[2]
    val fontSize by animateFloatAsState(style.fontSizeSp * SubtitleSizeFrameRatio * scale, tween(PreviewTweenMillis), label = "subtitle_size")
    val offsetFraction by animateFloatAsState((style.bottomOffset / 200f).coerceIn(0f, 1f), tween(PreviewTweenMillis), label = "subtitle_offset")
    Box(
        modifier = Modifier
            .widthIn(max = 520.dp * scale)
            .fillMaxWidth()
            .padding(horizontal = 20.dp * scale),
    ) {
        PreviewVideoFrame(backdropUrl = SetupSampleTitle.backgroundUrl(sample.id)) {
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.15f)))
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.matchParentSize()) {
                val lift = maxHeight * 0.45f * offsetFraction + 8.dp
                val line = stringResource(Res.string.advanced_preview_subtitle_line)
                val base = TextStyle(
                    fontSize = fontSize.sp,
                    fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    lineHeight = (fontSize * 1.25f).sp,
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp)
                        .offset(y = -lift)
                        .clip(RoundedCornerShape(4.dp))
                        .background(style.backgroundColor)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (style.outlineEnabled) {
                        Text(
                            text = line,
                            style = base.copy(
                                color = style.outlineColor,
                                drawStyle = Stroke(width = (style.outlineWidth.coerceAtLeast(1) * 2f)),
                            ),
                        )
                    }
                    Text(text = line, style = base.copy(color = style.textColor))
                }
            }
        }
    }
}

private const val SubtitleSizeFrameRatio = 0.62f

// --- navigation --------------------------------------------------------------------------

/** Android's real floating bar, in the chosen style, over a strip of the app. */
@Composable
internal fun SpecimenAndroidNavigation(style: NavBarStyle, glowEnabled: Boolean) {
    Box(
        modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth().padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        NavigationBarPreview(style = style, isTablet = false, glowEnabled = glowEnabled)
    }
}

/**
 * iOS 26's native tab bar cannot be drawn by Compose - it is UIKit - so this is a drawing of the
 * difference the switch makes: a floating glass capsule over the content, against the flat bar
 * pinned to the bottom edge.
 */
@Composable
internal fun SpecimenIosTabBar(liquidGlass: Boolean, scale: Float = 1f) {
    val tokens = MaterialTheme.nuvio
    val sample = SetupSampleTitle.rowItems[3]
    val inset by animateDpAsState(if (liquidGlass) 16.dp * scale else 0.dp, tween(PreviewTweenMillis, easing = LinearOutSlowInEasing), label = "glass_inset")
    val radius by animateDpAsState(if (liquidGlass) 28.dp * scale else 0.dp, tween(PreviewTweenMillis), label = "glass_radius")
    Box(
        modifier = Modifier
            .width(260.dp * scale)
            .height(190.dp * scale)
            .clip(RoundedCornerShape(22.dp * scale))
            .background(tokens.colors.background),
    ) {
        AsyncImage(
            model = SetupSampleTitle.backgroundUrl(sample.id),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = inset, end = inset, bottom = inset)
                .clip(RoundedCornerShape(radius))
                .background(
                    if (liquidGlass) {
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.34f), Color.White.copy(alpha = 0.16f)))
                    } else {
                        Brush.verticalGradient(listOf(tokens.colors.surface, tokens.colors.surface))
                    },
                )
                .then(if (liquidGlass) Modifier.border(1.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(radius)) else Modifier)
                .padding(vertical = 10.dp * scale),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            listOf(Icons.Rounded.Home, Icons.Rounded.Search, Icons.Rounded.VideoLibrary, Icons.Rounded.Person).forEachIndexed { index, icon ->
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (index == 0) tokens.colors.accent else if (liquidGlass) Color.White else tokens.colors.textSecondary,
                    modifier = Modifier.size(20.dp * scale),
                )
            }
        }
    }
}

/**
 * The desktop window's navigation: a sidebar down the left or a bar across the top, each in the
 * chosen style - labels always, icons only, or labels that give way when the window narrows.
 */
@Composable
internal fun SpecimenDesktopNavigation(layout: DesktopNavigationLayout, style: NavBarStyle, scale: Float = 1f) {
    val tokens = MaterialTheme.nuvio
    val labels = listOf(
        Icons.Rounded.Home to stringResource(Res.string.compose_nav_home),
        Icons.Rounded.Search to stringResource(Res.string.compose_nav_search),
        Icons.Rounded.VideoLibrary to stringResource(Res.string.compose_nav_library),
        Icons.Rounded.Person to stringResource(Res.string.compose_nav_profile),
    )
    val showLabels = style != NavBarStyle.COMPACT
    Box(
        modifier = Modifier
            .width(460.dp * scale)
            .height(260.dp * scale)
            .clip(RoundedCornerShape(14.dp))
            .background(tokens.colors.background)
            .border(1.dp, tokens.colors.borderSubtle, RoundedCornerShape(14.dp)),
    ) {
        if (layout == DesktopNavigationLayout.Sidebar) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .background(tokens.colors.surface)
                        .padding(horizontal = 10.dp * scale, vertical = 14.dp * scale),
                    verticalArrangement = Arrangement.spacedBy(10.dp * scale),
                ) {
                    labels.forEachIndexed { index, (icon, label) ->
                        NavItem(icon = icon, label = label.takeIf { showLabels }, selected = index == 0, scale = scale)
                    }
                }
                PreviewContentRows(Modifier.weight(1f).fillMaxHeight(), scale)
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 12.dp * scale)
                        .clip(RoundedCornerShape(99.dp))
                        .background(tokens.colors.surface)
                        .padding(horizontal = 8.dp * scale, vertical = 6.dp * scale),
                    horizontalArrangement = Arrangement.spacedBy(6.dp * scale),
                ) {
                    labels.forEachIndexed { index, (icon, label) ->
                        NavItem(icon = icon, label = label.takeIf { showLabels }, selected = index == 0, scale = scale)
                    }
                }
                PreviewContentRows(Modifier.weight(1f).fillMaxWidth(), scale)
            }
        }
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String?, selected: Boolean, scale: Float) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(if (selected) tokens.colors.accent.copy(alpha = 0.18f) else Color.Transparent)
            .padding(horizontal = 10.dp * scale, vertical = 6.dp * scale),
        horizontalArrangement = Arrangement.spacedBy(6.dp * scale),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) tokens.colors.accent else tokens.colors.textSecondary, modifier = Modifier.size(16.dp * scale))
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) tokens.colors.textPrimary else tokens.colors.textSecondary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PreviewContentRows(modifier: Modifier, scale: Float) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = modifier.padding(14.dp * scale),
        verticalArrangement = Arrangement.spacedBy(10.dp * scale),
    ) {
        repeat(2) { row ->
            Box(Modifier.width(80.dp * scale).height(8.dp * scale).clip(RoundedCornerShape(99.dp)).background(tokens.colors.overlayHover))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp * scale)) {
                SetupSampleTitle.rowItems.drop(row * 2).take(4).forEach { item ->
                    AsyncImage(
                        model = item.poster,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(44.dp * scale)
                            .aspectRatio(0.675f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(tokens.colors.surfaceCard),
                    )
                }
            }
        }
    }
}

// --- posters -----------------------------------------------------------------------------

/**
 * A catalog row of the **real** [NuvioPosterCard], called the way `HomePosterCard` calls it, so
 * shape, width, corners, labels and the depth effect are the shipped card's own drawing. The card
 * reads the poster style itself; the controls beside it write that same store.
 */
@Composable
internal fun SpecimenPosterRail(
    landscape: Boolean,
    hideLabels: Boolean,
    basePosterWidthDp: Int,
    showHoverCard: Boolean = false,
    hoverTrailer: Boolean = false,
    hoverSound: Boolean = false,
    hoverEnabled: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            SetupSampleTitle.rowItems.forEachIndexed { index, item ->
                Box {
                    NuvioPosterCard(
                        title = item.name,
                        imageUrl = if (landscape) item.banner else item.poster,
                        basePosterWidthDp = basePosterWidthDp,
                        shape = if (landscape) NuvioPosterShape.Landscape else NuvioPosterShape.Poster,
                        detailLine = if (landscape || hideLabels) null else item.releaseInfo,
                        showTitleBelow = !hideLabels,
                        bottomLeftLogoUrl = if (landscape) item.logo else null,
                    )
                    if (showHoverCard && index == 1) {
                        HoverBadge(enabled = hoverEnabled, trailer = hoverTrailer, sound = hoverSound)
                    }
                }
            }
        }
    }
}

/** What hovering the second card does on desktop: nothing, a details card, or a playing trailer. */
@Composable
private fun BoxScope.HoverBadge(enabled: Boolean, trailer: Boolean, sound: Boolean) {
    val tokens = MaterialTheme.nuvio
    androidx.compose.animation.AnimatedVisibility(
        visible = enabled,
        enter = fadeIn(tween(PreviewTweenMillis)),
        exit = fadeOut(tween(PreviewTweenMillis)),
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(99.dp))
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (trailer) Icons.Rounded.PlayArrow else Icons.Rounded.Check,
                contentDescription = null,
                tint = tokens.colors.accent,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = stringResource(
                    when {
                        trailer && sound -> Res.string.advanced_preview_hover_trailer_sound
                        trailer -> Res.string.advanced_preview_hover_trailer
                        else -> Res.string.advanced_preview_hover_details
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
            )
        }
    }
}

// --- source list -------------------------------------------------------------------------

/**
 * Two rows of the **real** [StreamCard] with fixed sample streams (no addon, no network: the sizes
 * are in the behaviour hints and the logo is the sample artwork host), over the chosen background.
 */
@Composable
internal fun SpecimenSourceList(
    backgroundMode: StreamBackgroundMode,
    showSizeBadges: Boolean,
    badgePlacement: StreamBadgePlacement,
    showAddonLogo: Boolean,
    scale: Float = 1f,
) {
    val tokens = MaterialTheme.nuvio
    val sample = SetupSampleTitle.rowItems.first()
    val streams = listOf(
        StreamItem(
            name = "Sample 4K",
            title = "Breaking.Bad.S01E01.2160p.WEB-DL.HDR.DDP5.1",
            addonName = "Sample source",
            addonId = "sample",
            addonLogo = SetupSampleTitle.logoUrl(sample.id),
            behaviorHints = StreamBehaviorHints(videoSize = 6_200_000_000L),
        ),
        StreamItem(
            name = "Sample 1080p",
            title = "Breaking.Bad.S01E01.1080p.BluRay.x265",
            addonName = "Sample source",
            addonId = "sample",
            addonLogo = SetupSampleTitle.logoUrl(sample.id),
            behaviorHints = StreamBehaviorHints(videoSize = 1_400_000_000L),
        ),
    )
    Box(
        modifier = Modifier
            .widthIn(max = 520.dp * scale)
            .fillMaxWidth()
            .padding(horizontal = 16.dp * scale)
            .clip(RoundedCornerShape(16.dp))
            .background(tokens.colors.background),
    ) {
        if (backgroundMode == StreamBackgroundMode.Cinematic) {
            AsyncImage(
                model = SetupSampleTitle.backgroundUrl(sample.id),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            Box(Modifier.matchParentSize().background(tokens.colors.background.copy(alpha = 0.78f)))
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            streams.forEach { stream ->
                StreamCard(
                    stream = stream,
                    enabled = true,
                    appendInstantServiceToDefaultName = false,
                    showFileSizeBadges = showSizeBadges,
                    showAddonLogo = showAddonLogo,
                    badgePlacement = badgePlacement,
                    onClick = {},
                )
            }
        }
    }
}

// --- social ------------------------------------------------------------------------------

/**
 * What your friends see: the Social tab's activity rows as a friend would see *you* - watching now,
 * then a finished episode - each fading to "hidden" when the matching share switch is off, and the
 * whole thing reduced to one quiet line when social is off.
 */
@Composable
internal fun SpecimenFriendActivity(
    socialEnabled: Boolean,
    shareWatchingNow: Boolean,
    shareWatched: Boolean,
    scale: Float = 1f,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier
            .widthIn(max = 460.dp * scale)
            .fillMaxWidth()
            .padding(horizontal = 20.dp * scale),
        verticalArrangement = Arrangement.spacedBy(10.dp * scale),
    ) {
        Text(
            text = stringResource(Res.string.advanced_preview_friends_see),
            style = MaterialTheme.typography.labelMedium,
            color = tokens.colors.textMuted,
        )
        if (!socialEnabled) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(tokens.colors.surfaceCard)
                    .padding(14.dp * scale),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.VisibilityOff, null, tint = tokens.colors.textMuted, modifier = Modifier.size(18.dp))
                Text(
                    text = stringResource(Res.string.advanced_preview_social_off),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textSecondary,
                )
            }
            return@Column
        }
        FriendRow(
            item = SetupSampleTitle.rowItems[0],
            status = stringResource(Res.string.advanced_preview_friend_watching),
            live = true,
            visible = shareWatchingNow,
            scale = scale,
        )
        FriendRow(
            item = SetupSampleTitle.rowItems[3],
            status = stringResource(Res.string.advanced_preview_friend_watched),
            live = false,
            visible = shareWatched,
            scale = scale,
        )
    }
}

@Composable
private fun FriendRow(
    item: com.nuvio.app.features.home.MetaPreview,
    status: String,
    live: Boolean,
    visible: Boolean,
    scale: Float,
) {
    val tokens = MaterialTheme.nuvio
    val alpha by animateFloatAsState(if (visible) 1f else 0.35f, tween(PreviewTweenMillis), label = "friend_row_alpha")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(tokens.colors.surfaceCard)
            .padding(10.dp * scale),
        horizontalArrangement = Arrangement.spacedBy(10.dp * scale),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(34.dp * scale).clip(CircleShape).background(tokens.colors.accent.copy(alpha = 0.3f * alpha)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "Y", style = MaterialTheme.typography.labelLarge, color = tokens.colors.textPrimary.copy(alpha = alpha))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = if (visible) item.name else stringResource(Res.string.advanced_preview_friend_hidden),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textPrimary.copy(alpha = alpha),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                if (live && visible) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(tokens.colors.accent))
                }
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.colors.textSecondary.copy(alpha = alpha),
                )
            }
        }
        if (visible) {
            AsyncImage(
                model = item.poster,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(30.dp * scale)
                    .aspectRatio(0.675f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(tokens.colors.overlayHover),
            )
        }
    }
}

// --- enhanced metadata -------------------------------------------------------------------

/**
 * A detail page's top, with and without enrichment: the addon's own poster and plot against
 * TMDB's artwork, logo, cast and trailers. Two labelled columns rather than a toggle animation,
 * because the point of the panel is the difference.
 */
@Composable
internal fun SpecimenMetadata(enriched: Boolean, scale: Float = 1f) {
    val tokens = MaterialTheme.nuvio
    val sample = SetupSampleTitle.rowItems.first()
    Column(
        modifier = Modifier
            .widthIn(max = 460.dp * scale)
            .fillMaxWidth()
            .padding(horizontal = 20.dp * scale),
        verticalArrangement = Arrangement.spacedBy(8.dp * scale),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2.1f)
                .clip(RoundedCornerShape(14.dp))
                .background(tokens.colors.surfaceCard),
        ) {
            if (enriched) {
                AsyncImage(
                    model = SetupSampleTitle.backgroundUrl(sample.id),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, tokens.colors.background.copy(alpha = 0.9f)))))
                AsyncImage(
                    model = SetupSampleTitle.logoUrl(sample.id),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp).fillMaxWidth(0.45f).height(34.dp * scale),
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AsyncImage(
                        model = sample.poster,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxHeight().aspectRatio(0.675f).clip(RoundedCornerShape(8.dp)).background(tokens.colors.overlayHover),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(sample.name, style = MaterialTheme.typography.titleSmall, color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold)
                        PreviewBar(widthFraction = 0.9f, height = 5.dp, alpha = 0.25f, color = tokens.colors.textPrimary)
                        PreviewBar(widthFraction = 0.7f, height = 5.dp, alpha = 0.25f, color = tokens.colors.textPrimary)
                    }
                }
            }
        }
        androidx.compose.animation.AnimatedVisibility(visible = enriched) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp * scale)) {
                Text(
                    text = stringResource(Res.string.settings_meta_cast),
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.colors.textSecondary,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp * scale)) {
                    repeat(6) {
                        Box(Modifier.size(30.dp * scale).clip(CircleShape).background(tokens.colors.overlayHover))
                    }
                }
                Text(
                    text = stringResource(Res.string.settings_meta_trailers),
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.colors.textSecondary,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp * scale)) {
                    repeat(3) {
                        Box(
                            Modifier.width(78.dp * scale).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(tokens.colors.overlayHover),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.PlayArrow, null, tint = tokens.colors.textMuted, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
        Text(
            text = stringResource(if (enriched) Res.string.advanced_preview_meta_enhanced else Res.string.advanced_preview_meta_basic),
            style = MaterialTheme.typography.labelSmall,
            color = tokens.colors.textMuted,
        )
    }
}

// --- tracking ----------------------------------------------------------------------------

/**
 * What a tracking service adds: this app and the service exchanging history, progress and lists.
 * An illustration in the style of `SetupDiagram` - blocks and an arrow, no service logos.
 */
@Composable
internal fun SpecimenTracking(scale: Float = 1f) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier.padding(horizontal = 24.dp * scale),
        horizontalArrangement = Arrangement.spacedBy(14.dp * scale),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackingBlock(title = "Nuvio Z", scale = scale)
        Icon(Icons.Rounded.Sync, null, tint = tokens.colors.accent, modifier = Modifier.size(28.dp * scale))
        TrackingBlock(title = "Trakt · Simkl", scale = scale)
    }
}

@Composable
private fun TrackingBlock(title: String, scale: Float) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier
            .width(140.dp * scale)
            .clip(RoundedCornerShape(14.dp))
            .background(tokens.colors.surfaceCard)
            .padding(12.dp * scale),
        verticalArrangement = Arrangement.spacedBy(6.dp * scale),
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1)
        listOf(
            Res.string.advanced_preview_tracking_history,
            Res.string.advanced_preview_tracking_progress,
            Res.string.advanced_preview_tracking_lists,
        ).forEach { label ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Check, null, tint = tokens.colors.accent, modifier = Modifier.size(12.dp * scale))
                Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = tokens.colors.textSecondary, maxLines = 1)
            }
        }
    }
}
