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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.AppScreenTab
import com.nuvio.app.DesktopHoverSidebar
import com.nuvio.app.DesktopSidebarCollapsedWidth
import com.nuvio.app.DesktopSidebarExpandedWidth
import com.nuvio.app.core.ui.DesktopNavigationBar
import com.nuvio.app.core.ui.FloatingNavigationItem
import com.nuvio.app.core.ui.NuvioNavBarScrollState
import com.nuvio.app.core.ui.NuvioPosterCard
import com.nuvio.app.core.ui.NuvioPosterShape
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.home.components.HomePosterHoverPreview
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
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
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
    interactive: Boolean = false,
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
        // Absent on the previews that are meant to be tried (see `AdvancedSetupPanel.previewIsInteractive`).
        if (!interactive) Box(
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

// --- navigation --------------------------------------------------------------------------

/**
 * Android's real floating bar in the chosen style (`NavigationBarPreview`, the same preview Settings
 * shows): its tabs select, and in Adaptive its sample grid scrolls and the bar collapses as it does
 * in the app. The frame lets touches through for this one - the hint under it says so, and it is
 * true now.
 */
@Composable
internal fun SpecimenAndroidNavigation(style: NavBarStyle, glowEnabled: Boolean) {
    Box(
        modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth().padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        NavigationBarPreview(style = style, isTablet = false, glowEnabled = glowEnabled)
    }
}

/**
 * iOS 26's native tab bar cannot be drawn by Compose - it is UIKit - so this is a drawing of the
 * difference the switch makes: a floating glass capsule over the content, against the flat bar pinned
 * to the bottom edge. Its tabs select, like the real bar's.
 */
@Composable
internal fun SpecimenIosTabBar(liquidGlass: Boolean, scale: Float = 1f) {
    val tokens = MaterialTheme.nuvio
    val sample = SetupSampleTitle.rowItems[3]
    var selected by remember { mutableStateOf(0) }
    val inset by animateDpAsState(if (liquidGlass) 16.dp * scale else 0.dp, tween(PreviewTweenMillis, easing = LinearOutSlowInEasing), label = "glass_inset")
    val radius by animateDpAsState(if (liquidGlass) 28.dp * scale else 0.dp, tween(PreviewTweenMillis), label = "glass_radius")
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .width(260.dp * scale)
                .height(170.dp * scale)
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
                    .padding(vertical = 6.dp * scale),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                listOf(Icons.Rounded.Home, Icons.Rounded.Search, Icons.Rounded.VideoLibrary, Icons.Rounded.Person).forEachIndexed { index, icon ->
                    Box(
                        modifier = Modifier
                            .size(36.dp * scale)
                            .clip(CircleShape)
                            .background(if (index == selected && liquidGlass) Color.White.copy(alpha = 0.22f) else Color.Transparent)
                            .clickable(role = Role.Tab) { selected = index },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (index == selected) tokens.colors.accent else if (liquidGlass) Color.White else tokens.colors.textSecondary,
                            modifier = Modifier.size(20.dp * scale),
                        )
                    }
                }
            }
        }
        Text(
            text = stringResource(Res.string.settings_nav_bar_preview_tap_hint),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
        )
    }
}

/**
 * The desktop window's navigation, with the **real** components: the hover sidebar
 * (`DesktopHoverSidebar`) or the jelly top bar (`DesktopNavigationBar`), over a page that scrolls.
 * Everything answers as in the app - tabs select; in Adaptive the sidebar opens on hover and the top
 * bar's labels give way on Home and on scroll and come back on hover; Expanded and Compact hold still.
 * The profile entry does nothing here: switching profile from a preview would switch it for real.
 */
@Composable
internal fun SpecimenDesktopNavigation(
    layout: DesktopNavigationLayout,
    style: NavBarStyle,
    heroEnabled: Boolean,
    socialEnabled: Boolean,
    glowEnabled: Boolean,
) {
    val tokens = MaterialTheme.nuvio
    var selectedTab by remember { mutableStateOf(AppScreenTab.Home) }
    val tabs = buildList {
        add(Triple(AppScreenTab.Home, Res.string.compose_nav_home, Icons.Rounded.Home))
        add(Triple(AppScreenTab.Search, Res.string.compose_nav_search, Icons.Rounded.Search))
        add(Triple(AppScreenTab.Library, Res.string.compose_nav_library, Icons.Rounded.VideoLibrary))
        add(Triple(AppScreenTab.Downloads, Res.string.compose_nav_downloads, Icons.Rounded.Download))
        if (socialEnabled) add(Triple(AppScreenTab.Social, Res.string.compose_nav_social, Icons.Rounded.Groups))
        add(Triple(AppScreenTab.Settings, Res.string.compose_nav_settings, Icons.Rounded.Settings))
    }
    val items = tabs.map { (tab, label, icon) ->
        FloatingNavigationItem(
            label = stringResource(label),
            selected = selectedTab == tab,
            onClick = { selectedTab = tab },
            icon = icon,
        )
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PreviewStage(
            logicalWidth = 1280.dp,
            logicalHeight = 720.dp,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            cornerRadius = 12.dp,
            background = tokens.colors.background,
        ) {
            val scrollState = remember(style, layout) { NuvioNavBarScrollState() }
            val hazeState = rememberHazeState()
            Box(
                Modifier
                    .fillMaxSize()
                    .hazeSource(hazeState)
                    .nestedScroll(scrollState.nestedScrollConnection),
            ) {
                PreviewScrollingPage(
                    topPadding = if (layout == DesktopNavigationLayout.TopBar) 112.dp else 32.dp,
                    startPadding = if (layout == DesktopNavigationLayout.Sidebar) DesktopSidebarCollapsedWidth else 0.dp,
                )
            }
            if (layout == DesktopNavigationLayout.Sidebar) {
                val hoverSource = remember { MutableInteractionSource() }
                val hovered by hoverSource.collectIsHoveredAsState()
                val expanded = when (style) {
                    NavBarStyle.EXPANDED -> true
                    NavBarStyle.COMPACT -> false
                    else -> hovered
                }
                val width by animateDpAsState(
                    if (expanded) DesktopSidebarExpandedWidth else DesktopSidebarCollapsedWidth,
                    tween(200),
                    label = "preview_sidebar_width",
                )
                DesktopHoverSidebar(
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it },
                    onProfileSelected = {},
                    onAddProfileRequested = {},
                    sidebarExpanded = expanded,
                    sidebarWidth = width,
                    hoverSource = hoverSource,
                    profileStackVisible = false,
                    onProfileStackVisibleChange = {},
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            } else {
                DesktopNavigationBar(
                    items = items,
                    modifier = Modifier.align(Alignment.TopCenter),
                    contentPadding = PaddingValues(top = 22.dp, bottom = 8.dp),
                    scrollState = scrollState,
                    hazeState = hazeState,
                    navBarStyle = style,
                    isHeroEnabled = heroEnabled,
                    windowWidth = 1280.dp,
                    glowEnabled = glowEnabled,
                )
            }
        }
        Text(
            text = stringResource(
                when {
                    style != NavBarStyle.ADAPTIVE -> Res.string.advanced_preview_desktop_nav_tap
                    layout == DesktopNavigationLayout.Sidebar -> Res.string.advanced_preview_desktop_nav_sidebar_adaptive
                    else -> Res.string.advanced_preview_desktop_nav_topbar_adaptive
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
        )
    }
}

/** A page of catalog rows that scrolls, for the navigation previews to react to. */
@Composable
private fun PreviewScrollingPage(topPadding: Dp, startPadding: Dp) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = startPadding + 40.dp, end = 40.dp, top = topPadding, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        repeat(4) { row ->
            Text(
                text = SetupSampleTitle.rowItems[row].genres.first(),
                style = MaterialTheme.typography.titleMedium,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                (SetupSampleTitle.rowItems.drop(row) + SetupSampleTitle.rowItems.take(row)).forEach { item ->
                    AsyncImage(
                        model = item.poster,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(150.dp)
                            .aspectRatio(0.675f)
                            .clip(RoundedCornerShape(12.dp))
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
 *
 * On the Hover panel (desktop) every card is wrapped in the **real** `HomePosterHoverPreview`, which
 * reads the same hover settings the switches write: point at a card and, when previews are on, the
 * preview card opens after the app's own delay, and a trailer starts in it - muted or with sound -
 * when trailers are on and the title has one. A line under the row says what the settings will do,
 * so the behaviour is readable even for a title without a trailer.
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
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            SetupSampleTitle.rowItems.forEach { item ->
                val card: @Composable (Modifier) -> Unit = { cardModifier ->
                    NuvioPosterCard(
                        title = item.name,
                        imageUrl = if (landscape) item.banner else item.poster,
                        modifier = cardModifier,
                        basePosterWidthDp = basePosterWidthDp,
                        shape = if (landscape) NuvioPosterShape.Landscape else NuvioPosterShape.Poster,
                        detailLine = if (landscape || hideLabels) null else item.releaseInfo,
                        showTitleBelow = !hideLabels,
                        bottomLeftLogoUrl = if (landscape) item.logo else null,
                    )
                }
                if (showHoverCard) {
                    HomePosterHoverPreview(
                        item = item,
                        isWatched = false,
                        // No action row: its buttons would change the real library.
                        onClick = null,
                        onLongClick = null,
                        content = card,
                    )
                } else {
                    card(Modifier)
                }
            }
        }
        if (showHoverCard) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(tokens.colors.surfaceCard)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = when {
                        !hoverEnabled -> Icons.Rounded.VisibilityOff
                        hoverTrailer && hoverSound -> Icons.AutoMirrored.Rounded.VolumeUp
                        hoverTrailer -> Icons.AutoMirrored.Rounded.VolumeOff
                        else -> Icons.Rounded.Image
                    },
                    contentDescription = null,
                    tint = if (hoverEnabled) tokens.colors.accent else tokens.colors.textMuted,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(
                        when {
                            !hoverEnabled -> Res.string.advanced_preview_hover_off
                            hoverTrailer && hoverSound -> Res.string.advanced_preview_hover_try_sound
                            hoverTrailer -> Res.string.advanced_preview_hover_try_muted
                            else -> Res.string.advanced_preview_hover_try_card
                        },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.colors.textPrimary,
                )
            }
        }
    }
}

// --- source list -------------------------------------------------------------------------

/**
 * Five rows of the **real** [StreamCard] - different qualities, formats, sizes and addons - over the
 * chosen background, so every control on the panel visibly changes them: the size badges appear and
 * move between top and bottom, the addon column appears, and Cinematic puts the title's artwork
 * behind the list. The list scrolls, at the app's own size, rather than shrinking into a picture.
 *
 * No addon, no network: sizes are the behaviour hints, and the addon column shows each addon's name
 * (a real addon also shows the logo from its manifest, which a sample has none of).
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
    val sample = SetupSampleTitle.rowItems[3]
    val streams = remember { sampleStreams() }
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier
            .widthIn(max = 560.dp * scale)
            .fillMaxSize()
            .padding(horizontal = 14.dp * scale, vertical = 10.dp),
    ) {
    val listScale = if (maxWidth < 480.dp) 0.8f else 1f
    PreviewStage(
        logicalWidth = maxWidth / listScale,
        logicalHeight = maxHeight / listScale,
        modifier = Modifier.fillMaxSize(),
        cornerRadius = 16.dp,
        background = tokens.colors.background,
    ) {
        if (backgroundMode == StreamBackgroundMode.Cinematic) {
            AsyncImage(
                model = SetupSampleTitle.backgroundUrl(sample.id),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().blur(24.dp),
            )
            Box(Modifier.matchParentSize().background(tokens.colors.background.copy(alpha = 0.72f)))
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
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
        // The list continues below the band; a soft fade says so.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(28.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, tokens.colors.background.copy(alpha = 0.9f)))),
        )
    }
    }
}

private fun sampleStreams(): List<StreamItem> {
    fun stream(name: String, description: String, addon: String, bytes: Long) = StreamItem(
        name = name,
        description = description,
        addonName = addon,
        addonId = addon.lowercase().replace(' ', '-'),
        behaviorHints = StreamBehaviorHints(videoSize = bytes),
    )
    return listOf(
        stream("4K HDR · Remux", "Sherlock.S01E01.2160p.UHD.BluRay.REMUX.HDR.HEVC.DTS-HD.MA.5.1", "Torrentio", 38_400_000_000L),
        stream("4K DV · WEB-DL", "Sherlock.S01E01.2160p.WEB-DL.DV.HDR10.DDP5.1.Atmos.H.265", "Comet", 9_800_000_000L),
        stream("1080p · BluRay", "Sherlock.S01E01.1080p.BluRay.x265.10bit.AAC5.1", "MediaFusion", 2_100_000_000L),
        stream("1080p · WEB-DL", "Sherlock.S01E01.1080p.WEB-DL.DDP5.1.H.264", "Torrentio", 3_400_000_000L),
        stream("720p · HDTV", "Sherlock.S01E01.720p.HDTV.x264.AAC", "Comet", 740_000_000L),
    )
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
