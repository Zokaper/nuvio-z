package com.nuvio.app.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.AppScreenTab
import com.nuvio.app.features.settings.NavBarStyle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * How much vertical room the floating navigation bar actually occupies, reported by the bar.
 *
 * WARN **This exists so there is exactly one answer, and it is a measurement rather than a guess.**
 * `LocalNuvioBottomNavigationOverlayPadding` used to be the literal `72.dp`, written in
 * `MainTabsDestination` and hand-tuned to approximate a height computed in *this* file out of an
 * icon size, two paddings, a spacer and a label box. The bar is 62dp tall with its labels collapsed
 * and 79dp with them shown, so the constant was seven short at rest and wrong throughout the
 * animation - which is why content ended up under the bar in its expanded state.
 *
 * A second formula here - `lerp(62.dp, 79.dp, labelFraction)` - would have been a third copy of the
 * same number and the same class of bug. The bar measures itself instead.
 *
 * The value published is the height **above the navigation-bar inset**, because
 * [nuvioSafeBottomPadding] adds that inset itself; publishing the total would count it twice.
 */
@Stable
class NuvioNavBarHeightState {
    /**
     * The **greatest** height the bar has occupied at its current width, not its height right now.
     *
     * Two reasons it is a maximum rather than the live value, and the second is the important one:
     *
     * 1. `LocalNuvioBottomNavigationOverlayPadding` is a *static* composition local, so every
     *    change invalidates the whole tab subtree that reads it. Publishing a value that animates
     *    would recompose every screen on every frame of a label collapse, to move a list's bottom
     *    padding by 17dp.
     * 2. A reserve that animates makes the end of a list move while the user is scrolling it - the
     *    content shifts under the finger that is dragging it. A slightly generous but *fixed*
     *    reserve is what "content must not disappear behind the bar" actually asks for.
     *
     * It resets when the bar's width changes, so a rotation or a window resize re-measures rather
     * than keeping a maximum that belonged to a different layout.
     */
    var overlayHeight: Dp by mutableStateOf(NuvioNavBarOverlayHeightFallback)
        private set

    private var measuredAtWidth: Dp = Dp.Unspecified

    internal fun report(height: Dp, barWidth: Dp) {
        if (barWidth != measuredAtWidth) {
            measuredAtWidth = barWidth
            overlayHeight = height
        } else if (height > overlayHeight) {
            overlayHeight = height
        }
    }
}

/**
 * What [NuvioNavBarHeightState] reports before the bar has been measured, i.e. for one frame.
 *
 * The old hand-tuned constant, kept for exactly this job: a first frame that reserved nothing would
 * lay every screen out too tall and then snap.
 */
val NuvioNavBarOverlayHeightFallback = 72.dp

@Stable
class NuvioNavBarScrollState {
    /** 1f = labels fully visible (expanded), 0f = labels hidden (collapsed, icons only) */
    var labelVisibility by mutableFloatStateOf(1f)
        private set

    /** Accumulated scroll distance from top of page for current active tab */
    var totalScrollOffset by mutableFloatStateOf(0f)
        private set

    private var currentTab: AppScreenTab = AppScreenTab.Home
    private val tabScrollOffsets = mutableMapOf<AppScreenTab, Float>()
    private var accumulatedDelta = 0f

    /** Call when user switches tabs to restore that tab's scroll offset */
    fun switchToTab(tab: AppScreenTab) {
        currentTab = tab
        labelVisibility = 1f
        accumulatedDelta = 0f
        totalScrollOffset = tabScrollOffsets[tab] ?: 0f
    }

    /** Call to expand (show labels) – e.g. when user scrolls back to top */
    fun expand() {
        labelVisibility = 1f
        accumulatedDelta = 0f
    }

    /** Synchronize current scroll offset for a specific tab from child LazyColumn/scroll containers */
    fun updateScrollOffset(tab: AppScreenTab, offset: Float) {
        val newOffset = offset.coerceAtLeast(0f)
        tabScrollOffsets[tab] = newOffset
        if (tab == currentTab) {
            totalScrollOffset = newOffset
        }
    }

    /** Call to collapse (hide labels) */
    fun collapse() {
        labelVisibility = 0f
        accumulatedDelta = 0f
    }

    val nestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val deltaY = available.y
            if (deltaY == 0f) return Offset.Zero

            accumulatedDelta += deltaY

            if (accumulatedDelta < -SCROLL_THRESHOLD && labelVisibility != 0f) {
                // Scrolling down past threshold → snap collapse
                labelVisibility = 0f
                accumulatedDelta = 0f
            } else if (accumulatedDelta > SCROLL_THRESHOLD && labelVisibility != 1f) {
                // Scrolling up past threshold → snap expand
                labelVisibility = 1f
                accumulatedDelta = 0f
            }

            // Reset accumulator if direction changed
            if (deltaY < 0f && accumulatedDelta > 0f) accumulatedDelta = deltaY
            if (deltaY > 0f && accumulatedDelta < 0f) accumulatedDelta = deltaY

            return Offset.Zero // Don't consume any scroll
        }
    }

    companion object {
        private const val SCROLL_THRESHOLD = 60f
    }
}

@Composable
fun rememberNuvioNavBarScrollState(): NuvioNavBarScrollState {
    return androidx.compose.runtime.remember { NuvioNavBarScrollState() }
}

/**
 * Floating pill-shaped navigation bar with scroll-responsive labels.
 *
 * @param hazeState Optional [HazeState] whose source is placed on the content behind this bar.
 *                  When provided, the pill gets a blur-through effect.
 */
@Composable
fun NuvioNavigationBar(
    modifier: Modifier = Modifier,
    scrollState: NuvioNavBarScrollState? = null,
    hazeState: HazeState? = null,
    navBarStyle: NavBarStyle = NavBarStyle.ADAPTIVE,
    /** Receives this bar's measured occupied height. See [NuvioNavBarHeightState]. */
    heightState: NuvioNavBarHeightState? = null,
    content: @Composable NuvioNavigationBarScope.() -> Unit,
) {
    val targetLabelFraction = when (navBarStyle) {
        NavBarStyle.EXPANDED -> 1f
        NavBarStyle.COMPACT -> 0f
        else -> scrollState?.labelVisibility ?: 1f
    }
    val labelFraction by animateFloatAsState(
        targetValue = targetLabelFraction,
        animationSpec = tween(
            durationMillis = NuvioTokens.Motion.sheetEnterMillis,
            easing = NuvioTokens.Motion.standard,
        ),
        label = "nav_label_alpha",
    )

    val navigationBarInsets = nuvioBottomNavigationBarInsets()
    val bottomSafePadding = navigationBarInsets.asPaddingValues().calculateBottomPadding()
    val density = LocalDensity.current

    // Outer container - no background, just safe padding.
    //
    // `BoxWithConstraints` because the pill's horizontal padding and its labels both depend on how
    // much width there is, and the bar is the only thing that knows its own width.
    BoxWithConstraints(
        modifier = modifier
            // WARN **Outermost, so this is the full occupied height including the padding below.**
            // An `onSizeChanged` placed after `padding(...)` reports the inner size and would
            // under-report the bar by the height of its own margin.
            .onSizeChanged { size ->
                with(density) {
                    heightState?.report(
                        height = size.height.toDp() - bottomSafePadding,
                        barWidth = size.width.toDp(),
                    )
                }
            }
            .fillMaxWidth()
            .padding(bottom = bottomSafePadding + nuvioBottomNavigationExtraVerticalPadding + NuvioTokens.Space.s8),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // WARN **A label that cannot fit its cell demotes every label, rather than being cut.**
        //
        // Six tabs across a 411dp phone leave about 55dp per cell, and "Download" and "Settings"
        // measure wider than that. The label `Text` was unconstrained inside a `Box` that set only
        // a height, so it overflowed its cell and was then cut by the neighbouring item's clip -
        // which is how the bar came to read ")ownload" on a real phone.
        //
        // Keyed on `maxWidth` so rotating or resizing re-asks the question; without that key a bar
        // that demoted once at 320dp would stay iconic forever. There is no oscillation, because a
        // demoted bar draws no labels and therefore reports no further overflow.
        var labelsDoNotFit by remember(maxWidth) { mutableStateOf(false) }
        val effectiveLabelFraction = if (labelsDoNotFit) 0f else labelFraction

        // Dynamic horizontal padding: the pill shrinks when labels are hidden, on the same fraction.
        //
        // 28dp was a desktop-ish number a phone cannot afford: on a compact window it is worth
        // about 32dp of label width per cell, which is the difference between "Download" fitting
        // and not.
        val expandedHorizontalPadding =
            if (maxWidth.value < NuvioWindowBreakpoints.MEDIUM_WIDTH_DP) 12.dp else 28.dp
        val collapsedHorizontalPadding = 58.dp
        val horizontalPadding = expandedHorizontalPadding +
            (collapsedHorizontalPadding - expandedHorizontalPadding) * (1f - effectiveLabelFraction)


        // The floating pill
        val pillModifier = Modifier
            .padding(horizontal = horizontalPadding)
            .fillMaxWidth()
            .clip(RoundedCornerShape(NuvioTokens.Radius.full))
            .then(
                if (hazeState != null) {
                    Modifier.hazeEffect(state = hazeState) {
                        blurRadius = 24.dp
                    }
                } else {
                    Modifier
                },
            )
            .background(Color(0xFF1C1C1E).copy(alpha = if (hazeState != null) 0.55f else 0.82f))

        Box(modifier = pillModifier) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = NuvioTokens.Space.s6,
                        vertical = NuvioTokens.Space.s4,
                    ),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NuvioNavigationBarScopeImpl(
                    rowScope = this,
                    labelFraction = effectiveLabelFraction,
                    onLabelDidNotFit = { labelsDoNotFit = true },
                ).content()
            }
        }
    }
}

interface NuvioNavigationBarScope {
    @Composable
    fun NavItem(
        selected: Boolean,
        onClick: () -> Unit,
        icon: ImageVector,
        contentDescription: String?,
        modifier: Modifier = Modifier,
        label: String? = null,
    )

    @Composable
    fun NavItem(
        selected: Boolean,
        onClick: () -> Unit,
        icon: DrawableResource,
        contentDescription: String?,
        modifier: Modifier = Modifier,
        label: String? = null,
    )

    @Composable
    fun NavItem(
        selected: Boolean,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        label: String? = null,
        content: @Composable () -> Unit,
    )
}

private class NuvioNavigationBarScopeImpl(
    private val rowScope: androidx.compose.foundation.layout.RowScope,
    private val labelFraction: Float,
    private val onLabelDidNotFit: () -> Unit,
) : NuvioNavigationBarScope {

    @Composable
    override fun NavItem(
        selected: Boolean,
        onClick: () -> Unit,
        icon: ImageVector,
        contentDescription: String?,
        modifier: Modifier,
        label: String?,
    ) {
        val tokens = MaterialTheme.nuvio
        val palette = MaterialTheme.themePalette
        val iconColor by animateColorAsState(
            targetValue = if (selected) tokens.colors.accent else tokens.colors.textMuted,
            label = "nav_icon_color",
        )
        // Selected item gets a pill-shaped highlight using accent at low opacity
        val selectedBgColor by animateColorAsState(
            targetValue = if (selected) tokens.colors.accent.copy(alpha = NuvioTokens.Opacity.selected)
            else Color.Transparent,
            label = "nav_bg_color",
        )

        with(rowScope) {
            Column(
                modifier = modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(NuvioTokens.Radius.full))
                    .background(selectedBgColor)
                    .selectable(
                        selected = selected,
                        enabled = true,
                        role = Role.Tab,
                        onClick = onClick,
                    )
                    .padding(vertical = NuvioTokens.Space.s6),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    modifier = Modifier
                        .size(28.dp)
                        .then(if (selected) Modifier.gradientMask(palette.accentBrush()) else Modifier),
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = if (selected) Color.White else iconColor,
                )
                NavItemLabel(
                    label = label,
                    labelFraction = labelFraction,
                    iconColor = iconColor,
                    selected = selected,
                    onDidNotFit = onLabelDidNotFit,
                )
            }
        }
    }

    @Composable
    override fun NavItem(
        selected: Boolean,
        onClick: () -> Unit,
        icon: DrawableResource,
        contentDescription: String?,
        modifier: Modifier,
        label: String?,
    ) {
        val tokens = MaterialTheme.nuvio
        val palette = MaterialTheme.themePalette
        val iconColor by animateColorAsState(
            targetValue = if (selected) tokens.colors.accent else tokens.colors.textMuted,
            label = "nav_icon_color",
        )
        val selectedBgColor by animateColorAsState(
            targetValue = if (selected) tokens.colors.accent.copy(alpha = NuvioTokens.Opacity.selected)
            else Color.Transparent,
            label = "nav_bg_color",
        )

        with(rowScope) {
            Column(
                modifier = modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(NuvioTokens.Radius.full))
                    .background(selectedBgColor)
                    .selectable(
                        selected = selected,
                        enabled = true,
                        role = Role.Tab,
                        onClick = onClick,
                    )
                    .padding(vertical = NuvioTokens.Space.s6),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    modifier = Modifier
                        .size(28.dp)
                        .then(if (selected) Modifier.gradientMask(palette.accentBrush()) else Modifier),
                    painter = painterResource(icon),
                    contentDescription = contentDescription,
                    tint = if (selected) Color.White else iconColor,
                )
                NavItemLabel(
                    label = label,
                    labelFraction = labelFraction,
                    iconColor = iconColor,
                    selected = selected,
                    onDidNotFit = onLabelDidNotFit,
                )
            }
        }
    }

    @Composable
    override fun NavItem(
        selected: Boolean,
        onClick: () -> Unit,
        modifier: Modifier,
        label: String?,
        content: @Composable () -> Unit,
    ) {
        val tokens = MaterialTheme.nuvio
        val selectedBgColor by animateColorAsState(
            targetValue = if (selected) tokens.colors.accent.copy(alpha = NuvioTokens.Opacity.selected)
            else Color.Transparent,
            label = "nav_bg_color",
        )
        val iconColor by animateColorAsState(
            targetValue = if (selected) tokens.colors.accent else tokens.colors.textMuted,
            label = "nav_icon_color",
        )

        with(rowScope) {
            Column(
                modifier = modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(NuvioTokens.Radius.full))
                    .background(selectedBgColor)
                    .selectable(
                        selected = selected,
                        enabled = true,
                        role = Role.Tab,
                        onClick = onClick,
                    )
                    .padding(vertical = NuvioTokens.Space.s6),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                content()
                NavItemLabel(
                    label = label,
                    labelFraction = labelFraction,
                    iconColor = iconColor,
                    selected = selected,
                    onDidNotFit = onLabelDidNotFit,
                )
            }
        }
    }
}

@Composable
private fun NavItemLabel(
    label: String?,
    labelFraction: Float,
    iconColor: Color,
    selected: Boolean,
    onDidNotFit: () -> Unit,
) {
    if (label == null || labelFraction <= 0f) return
    Spacer(modifier = Modifier.height(NuvioTokens.Space.s3 * labelFraction))
    Box(
        modifier = Modifier
            // WARN **`fillMaxWidth` is the fix for ")ownload".** The parent item is `weight(1f)`,
            // so the cell is already the right size; this `Box` set a height and left the width
            // unbounded, which let the `Text` measure at its intrinsic width, spill into the
            // neighbouring cells and get cut by their clip. Constrained here it can only ellipsize
            // - and it reports when it has to, so the bar can drop labels entirely rather than
            // show half a word.
            .fillMaxWidth()
            .height(NuvioTokens.Space.s14 * labelFraction)
            .alpha(labelFraction),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = NuvioTokens.Type.labelXs,
                lineHeight = NuvioTokens.LineHeight.labelXs,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = iconColor,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result -> if (result.hasVisualOverflow) onDidNotFit() },
        )
    }
}


/**
 * Classic flat navigation bar — the original pre-pill implementation.
 * No floating pill, no labels, no scroll behavior. Simple icon row with a top divider.
 */
@Composable
fun NuvioClassicNavigationBar(
    modifier: Modifier = Modifier,
    content: @Composable NuvioNavigationBarScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(modifier.fillMaxWidth()) {
        androidx.compose.material3.HorizontalDivider(
            thickness = NuvioTokens.Space.hairline,
            color = tokens.colors.borderDefault,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(nuvioBottomNavigationBarInsets().asPaddingValues())
                .padding(horizontal = NuvioTokens.Space.s4, vertical = nuvioBottomNavigationExtraVerticalPadding),
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap, Alignment.CenterHorizontally),
        ) {
            NuvioClassicNavigationBarScopeImpl(this).content()
        }
    }
}

private class NuvioClassicNavigationBarScopeImpl(
    private val rowScope: androidx.compose.foundation.layout.RowScope,
) : NuvioNavigationBarScope {

    @Composable
    override fun NavItem(
        selected: Boolean,
        onClick: () -> Unit,
        icon: ImageVector,
        contentDescription: String?,
        modifier: Modifier,
        label: String?,
    ) {
        val tokens = MaterialTheme.nuvio
        val palette = MaterialTheme.themePalette
        val iconColor by animateColorAsState(
            targetValue = if (selected) tokens.colors.accent else tokens.colors.textMuted,
            label = "classic_nav_icon_color",
        )
        with(rowScope) {
            Icon(
                modifier = modifier
                    .widthIn(max = tokens.components.navItemMaxWidth)
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .clip(tokens.components.navItemShape)
                    .selectable(
                        selected = selected,
                        enabled = true,
                        role = Role.Tab,
                        onClick = onClick,
                    )
                    .padding(NuvioTokens.Space.s10)
                    .size(tokens.components.navIconSize)
                    .then(if (selected) Modifier.gradientMask(palette.accentBrush()) else Modifier),
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (selected) Color.White else iconColor,
            )
        }
    }

    @Composable
    override fun NavItem(
        selected: Boolean,
        onClick: () -> Unit,
        icon: DrawableResource,
        contentDescription: String?,
        modifier: Modifier,
        label: String?,
    ) {
        val tokens = MaterialTheme.nuvio
        val palette = MaterialTheme.themePalette
        val iconColor by animateColorAsState(
            targetValue = if (selected) tokens.colors.accent else tokens.colors.textMuted,
            label = "classic_nav_icon_color",
        )
        with(rowScope) {
            Icon(
                modifier = modifier
                    .widthIn(max = tokens.components.navItemMaxWidth)
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .clip(tokens.components.navItemShape)
                    .selectable(
                        selected = selected,
                        enabled = true,
                        role = Role.Tab,
                        onClick = onClick,
                    )
                    .padding(NuvioTokens.Space.s10)
                    .size(tokens.components.navIconSize)
                    .then(if (selected) Modifier.gradientMask(palette.accentBrush()) else Modifier),
                painter = painterResource(icon),
                contentDescription = contentDescription,
                tint = if (selected) Color.White else iconColor,
            )
        }
    }

    @Composable
    override fun NavItem(
        selected: Boolean,
        onClick: () -> Unit,
        modifier: Modifier,
        label: String?,
        content: @Composable () -> Unit,
    ) {
        val tokens = MaterialTheme.nuvio
        with(rowScope) {
            Box(
                modifier = modifier
                    .widthIn(max = tokens.components.navItemMaxWidth)
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .clip(tokens.components.navItemShape)
                    .selectable(
                        selected = selected,
                        enabled = true,
                        role = Role.Tab,
                        onClick = onClick,
                    )
                    .padding(NuvioTokens.Space.s10),
                contentAlignment = Alignment.Center,
            ) {
                content()
            }
        }
    }
}
