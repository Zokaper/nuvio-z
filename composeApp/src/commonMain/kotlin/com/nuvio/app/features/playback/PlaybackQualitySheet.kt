package com.nuvio.app.features.playback

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.updater.formatFileSize
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.playback_progress_choosing
import nuvio.composeapp.generated.resources.playback_quality_best
import nuvio.composeapp.generated.resources.playback_quality_description
import nuvio.composeapp.generated.resources.playback_quality_loading
import nuvio.composeapp.generated.resources.playback_quality_manual
import nuvio.composeapp.generated.resources.playback_quality_needs
import nuvio.composeapp.generated.resources.playback_quality_needs_estimated
import nuvio.composeapp.generated.resources.playback_quality_no_match
import nuvio.composeapp.generated.resources.playback_quality_over_connection
import nuvio.composeapp.generated.resources.playback_quality_summary_with_size
import nuvio.composeapp.generated.resources.playback_quality_title
import nuvio.composeapp.generated.resources.playback_quality_variant_high
import nuvio.composeapp.generated.resources.playback_quality_variant_low
import nuvio.composeapp.generated.resources.playback_quality_variant_mid
import nuvio.composeapp.generated.resources.playback_quality_your_connection
import org.jetbrains.compose.resources.stringResource
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Streamlined's quality picker.
 *
 * Every card here came from a source that exists for this title, so the set is different on
 * every title and a quality nobody released simply has no card. The bandwidth figure is the
 * one the chosen file actually needs, not a preset's nominal number.
 *
 * A thin renderer over pure functions, deliberately: the resolution badge, the tier word, the
 * required speed, the provider line and the connection meter all come from
 * [PlaybackQualityOptions] and [PlaybackSourceSelector], which are testable outside Compose.
 * The same reasoning `PlaybackProgress.step`/`isVisible` are built on.
 *
 * [estimatedMbps] is what the connection is currently thought to carry, and is used only to
 * mark a card as a stretch. It never disables one: the estimate is a guess, and the user may
 * know their line better than the app does.
 *
 * Two structural properties, both of which the previous stacked-list version got wrong:
 *
 * - **The container is chosen from the real window.** [BoxWithConstraints] sits at the top so
 *   it measures the surface this sheet is drawn into - `entry<StreamRoute>`'s full-screen
 *   `Box` - rather than a phone-sized dialog. A `BasicAlertDialog` here would clamp the width
 *   and silently ship the phone layout to a 1080p desktop window.
 * - **Nothing is drawn until the numbers are final.** See [QualitySheetBody].
 *
 * [isLoading] and [isSelecting] are deliberately two parameters and must not be merged.
 * [isLoading] means the figures are still moving, and it replaces the grid with a skeleton.
 * [isSelecting] means the user has already chosen and the app is acting on it; the grid stays
 * exactly as it was and only stops accepting taps. Folding the second into the first would
 * blank the cards out from under the user the instant they picked one - the surface jumping,
 * which is the fault this sheet was rewritten to remove.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackQualitySheet(
    options: List<PlaybackQualityOption>,
    isLoading: Boolean,
    isSelecting: Boolean,
    selectionContext: PlaybackSelectionContext,
    estimatedMbps: Double?,
    onOptionSelected: (PlaybackQualityOption) -> Unit,
    onChooseManually: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // The repo's tablet threshold, as used at App.kt:2051 and
        // ProfileSelectionScreen.kt:112. Matched rather than re-derived so one window is
        // either "tablet" everywhere in the app or nowhere.
        val isWide = maxWidth >= WIDE_LAYOUT_MIN_WIDTH
        // Derived from the measured window, the way AudioTrackModal does it. A literal
        // ceiling is what stopped the third quality band from being reachable.
        val gridMaxHeight = (maxHeight - GRID_HEIGHT_INSET)
            .coerceAtLeast(GRID_MIN_HEIGHT)
            .coerceAtMost(GRID_MAX_HEIGHT)

        if (isWide) {
            // A centred panel, not a bottom sheet: `usesNativeNuvioBottomSheet` is false on
            // desktop, so NuvioModalBottomSheet would fall through to Material's
            // ModalBottomSheet and pin a phone sheet to the bottom of a 1080p window.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(tokens.colors.overlayScrim)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier
                        .widthIn(max = tokens.components.wideDialogMaxWidth)
                        .padding(tokens.spacing.dialogPadding)
                        // Swallows the scrim's dismiss click; the panel itself is not a
                        // dismiss target.
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                    shape = tokens.shapes.dialog,
                    color = tokens.colors.surfaceDialog,
                ) {
                    QualitySheetBody(
                        options = options,
                        isLoading = isLoading,
                        isSelecting = isSelecting,
                        selectionContext = selectionContext,
                        estimatedMbps = estimatedMbps,
                        gridMaxHeight = gridMaxHeight,
                        contentBottomPadding = tokens.spacing.dialogPadding,
                        onOptionSelected = onOptionSelected,
                        onChooseManually = onChooseManually,
                    )
                }
            }
        } else {
            NuvioModalBottomSheet(
                onDismissRequest = {
                    coroutineScope.launch {
                        dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
                    }
                },
                sheetState = sheetState,
            ) {
                QualitySheetBody(
                    options = options,
                    isLoading = isLoading,
                    isSelecting = isSelecting,
                    selectionContext = selectionContext,
                    estimatedMbps = estimatedMbps,
                    gridMaxHeight = gridMaxHeight,
                    contentBottomPadding = nuvioSafeBottomPadding(tokens.spacing.sheetPadding),
                    onOptionSelected = onOptionSelected,
                    onChooseManually = onChooseManually,
                )
            }
        }
    }
}

/**
 * The sheet's contents, identical in both containers.
 *
 * **The body renders one of three states and never a blend.** Previously `isLoading` only
 * greyed the rows out, so partial options sat on screen for the whole time they were still
 * changing: a card could say "Needs about 9 Mb/s · 3.2 GB" and, as another addon answered,
 * say something else, with cards appearing and re-banding around it. A figure that is about
 * to be replaced is worse than no figure.
 *
 * The third state is the one that is easy to miss: settled with nothing selectable is
 * reachable - `isStreamlinedSelectionReady` treats it as terminal - and an empty grid with
 * only "Choose source manually" under it is a dead end wearing a grid.
 */
@Composable
private fun QualitySheetBody(
    options: List<PlaybackQualityOption>,
    isLoading: Boolean,
    isSelecting: Boolean,
    selectionContext: PlaybackSelectionContext,
    estimatedMbps: Double?,
    gridMaxHeight: Dp,
    contentBottomPadding: Dp,
    onOptionSelected: (PlaybackQualityOption) -> Unit,
    onChooseManually: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = tokens.spacing.sheetPadding,
                end = tokens.spacing.sheetPadding,
                top = tokens.spacing.sheetPadding,
                bottom = contentBottomPadding,
            ),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
    ) {
        Text(
            text = stringResource(Res.string.playback_quality_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = tokens.colors.textPrimary,
        )
        Text(
            text = when {
                isLoading -> stringResource(Res.string.playback_quality_loading)
                // Already chosen: "Finding available sources" would be a lie, and the
                // progress overlay's own wording is the truthful one.
                isSelecting -> stringResource(Res.string.playback_progress_choosing)
                else -> stringResource(Res.string.playback_quality_description)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textSecondary,
        )
        // Named only when it is known. An unmeasured connection has no number, and printing
        // a placeholder for one is the same untruth as quoting a preset's bandwidth.
        estimatedMbps?.takeIf { it > 0.0 }?.let { estimate ->
            Text(
                text = stringResource(
                    Res.string.playback_quality_your_connection,
                    estimate.roundToInt(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
        }

        when {
            isLoading -> QualitySkeletonGrid(gridMaxHeight = gridMaxHeight)
            options.isEmpty() -> Text(
                text = stringResource(Res.string.playback_quality_no_match),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textSecondary,
            )
            else -> LazyVerticalGrid(
                // Adaptive rather than a fixed count, so one composable serves phone, tablet
                // and desktop with no second branch inside the branch that already exists.
                columns = GridCells.Adaptive(minSize = QUALITY_CARD_MIN_WIDTH),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = gridMaxHeight),
                horizontalArrangement = Arrangement.spacedBy(tokens.spacing.listGap),
                verticalArrangement = Arrangement.spacedBy(tokens.spacing.listGap),
            ) {
                items(options, key = { it.id }) { option ->
                    QualityCard(
                        option = option,
                        selectionContext = selectionContext,
                        estimatedMbps = estimatedMbps,
                        // Disabled, not removed. A second tap while the first is being acted
                        // on would re-arm the selection effect against a different option.
                        enabled = !isSelecting,
                        onClick = { onOptionSelected(option) },
                    )
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onChooseManually) {
                Text(stringResource(Res.string.playback_quality_manual))
            }
        }
    }
}

@Composable
private fun QualityCard(
    option: PlaybackQualityOption,
    selectionContext: PlaybackSelectionContext,
    estimatedMbps: Double?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val fit = PlaybackQualityOptions.connectionFit(option, estimatedMbps)
    val band = variantLabel(option)
    val source = sourceLine(option, selectionContext)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        shape = tokens.shapes.card,
        color = tokens.colors.surfaceCard,
    ) {
        Column(
            modifier = Modifier.padding(tokens.spacing.cardPaddingCompact),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ResolutionBadge(option.resolutionLabel.ifBlank { BEST_AVAILABLE_BADGE })
                if (band.isNotBlank()) {
                    Text(
                        text = band,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = tokens.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = optionSummary(option),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textSecondary,
            )
            if (source.isNotBlank()) {
                Text(
                    text = source,
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (fit != null) {
                ConnectionMeter(fit = fit)
            }
            if (fit?.isOverConnection == true) {
                // Said, not enforced. The estimate is a guess; the user may know better
                // than the app does, and a card they cannot pick is worse than a warning.
                Text(
                    text = stringResource(Res.string.playback_quality_over_connection),
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.colors.danger,
                )
            }
        }
    }
}

/**
 * How much of the line this option would occupy, drawn.
 *
 * The track runs to [PlaybackQualityOptions.MAX_LOAD_FRACTION] times the estimate, so **the
 * marker at the halfway point is the connection itself** and a fill past it is the same
 * statement the warning sentence makes. Both read [PlaybackQualityOptions.connectionFit], so
 * a bar and a sentence that disagree is not a state this can reach.
 */
@Composable
private fun ConnectionMeter(fit: PlaybackQualityOptions.ConnectionFit) {
    val tokens = MaterialTheme.nuvio
    val fillFraction = (fit.loadFraction / PlaybackQualityOptions.MAX_LOAD_FRACTION)
        .coerceIn(0.0, 1.0)
        .toFloat()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(NuvioTokens.Space.s6)
            .clip(tokens.shapes.chip)
            .background(tokens.colors.playerTimelineTrack),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fillFraction)
                .fillMaxHeight()
                .background(
                    if (fit.isOverConnection) tokens.colors.warning else tokens.colors.accent,
                ),
        )
        // The estimate line. Drawn as the trailing edge of a half-width spacer because a
        // fraction cannot be expressed as an alignment.
        Box(
            modifier = Modifier
                .fillMaxWidth(ESTIMATE_MARKER_FRACTION)
                .fillMaxHeight(),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                modifier = Modifier
                    .width(tokens.borders.medium)
                    .fillMaxHeight()
                    .background(tokens.colors.borderStrong),
            )
        }
    }
}

/**
 * The loading state: card-shaped placeholders on the same footprint as the real grid, so the
 * surface does not jump when the figures arrive.
 */
@Composable
private fun QualitySkeletonGrid(gridMaxHeight: Dp) {
    val tokens = MaterialTheme.nuvio
    val transition = rememberInfiniteTransition()
    val alpha by transition.animateFloat(
        initialValue = SKELETON_MIN_ALPHA,
        targetValue = SKELETON_MAX_ALPHA,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SKELETON_PULSE_MILLIS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
    )
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = QUALITY_CARD_MIN_WIDTH),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = gridMaxHeight),
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.listGap),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.listGap),
    ) {
        items(SKELETON_CARD_COUNT) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SKELETON_CARD_HEIGHT)
                    .clip(tokens.shapes.card)
                    .background(tokens.colors.skeleton.copy(alpha = alpha)),
            )
        }
    }
}

@Composable
private fun ResolutionBadge(text: String) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = Modifier
            .clip(tokens.shapes.chip)
            .background(tokens.colors.accent.copy(alpha = tokens.opacity.selected))
            .widthIn(min = RESOLUTION_BADGE_MIN_WIDTH)
            .padding(
                horizontal = tokens.components.chipHorizontalPadding,
                vertical = tokens.components.chipVerticalPadding,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            color = tokens.colors.accent,
            maxLines = 1,
        )
    }
}

/**
 * The card's band word, given that the resolution is already in the badge beside it.
 *
 * "4K" belongs in the badge; "High" is a comparison with the cards around it. A resolution
 * with a single card has nothing to compare against, so it says nothing rather than
 * repeating the badge back to the user - the badge alone already names it.
 */
@Composable
private fun variantLabel(option: PlaybackQualityOption): String = when (option.variant) {
    PlaybackQualityOption.Variant.BEST -> stringResource(Res.string.playback_quality_best)
    PlaybackQualityOption.Variant.HIGH -> stringResource(Res.string.playback_quality_variant_high)
    PlaybackQualityOption.Variant.MID -> stringResource(Res.string.playback_quality_variant_mid)
    PlaybackQualityOption.Variant.LOW -> stringResource(Res.string.playback_quality_variant_low)
    PlaybackQualityOption.Variant.SINGLE -> ""
}

/**
 * `WEB-DL · TorBox` for the source this card would really open.
 *
 * Not `option.candidates.first()`: the protocol and cache gates can skip several candidates
 * before landing on one, and naming a release the user never receives is the same class of
 * untruth as quoting a season pack's bandwidth for a card.
 */
private fun sourceLine(
    option: PlaybackQualityOption,
    context: PlaybackSelectionContext,
): String = PlaybackSourceSelector.previewSelection(option, context)
    ?.let { PlaybackSourceSelector.describeRelease(it.facts) }
    .orEmpty()

@Composable
private fun optionSummary(option: PlaybackQualityOption): String {
    val required = option.requiredMbps
        ?: return stringResource(Res.string.playback_quality_description)
    // Rounded up: quoting 4 Mb/s for something that needs 4.6 is the one direction that
    // turns an informed choice into a stall.
    val speed = stringResource(
        if (option.isEstimateApproximate) {
            Res.string.playback_quality_needs_estimated
        } else {
            Res.string.playback_quality_needs
        },
        ceil(required).roundToInt(),
    )
    val size = option.representativeSizeBytes?.let(::formatFileSize) ?: return speed
    return stringResource(Res.string.playback_quality_summary_with_size, speed, size)
}

/** Standing in for a resolution on the Best available card, which claims no resolution. */
private const val BEST_AVAILABLE_BADGE = "★"

/**
 * Where the estimate sits along the meter's track. Derived from
 * [PlaybackQualityOptions.MAX_LOAD_FRACTION] rather than restating its inverse: the KDoc on
 * [ConnectionMeter] claims the marker *is* the connection, and a literal here would quietly
 * make that false the moment the clamp in the other file changed.
 */
private val ESTIMATE_MARKER_FRACTION =
    (1.0 / PlaybackQualityOptions.MAX_LOAD_FRACTION).toFloat()

private const val SKELETON_CARD_COUNT = 4
private const val SKELETON_PULSE_MILLIS = 900
private const val SKELETON_MIN_ALPHA = 0.4f
private const val SKELETON_MAX_ALPHA = 1f

/**
 * Card width below which the over-connection warning stops fitting on two lines. It is the
 * part of the card the user asked to keep legible, so it sets the column width rather than
 * being shrunk to fit more columns in.
 */
private val QUALITY_CARD_MIN_WIDTH = 240.dp

/** The repo's tablet threshold. See App.kt:2051 and ProfileSelectionScreen.kt:112. */
private val WIDE_LAYOUT_MIN_WIDTH = 768.dp

private val RESOLUTION_BADGE_MIN_WIDTH = NuvioTokens.Space.s56
private val SKELETON_CARD_HEIGHT = NuvioTokens.Space.s96 + NuvioTokens.Space.s24
private val GRID_HEIGHT_INSET = NuvioTokens.Space.s96 * 2
private val GRID_MIN_HEIGHT = NuvioTokens.Space.s96 * 2
private val GRID_MAX_HEIGHT = NuvioTokens.Space.s96 * 6
