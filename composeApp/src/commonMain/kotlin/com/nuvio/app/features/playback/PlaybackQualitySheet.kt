package com.nuvio.app.features.playback

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.NuvioSkeletonBlock
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.updater.formatFileSize
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.playback_progress_choosing
import nuvio.composeapp.generated.resources.playback_quality_best
import nuvio.composeapp.generated.resources.playback_quality_checking_connection
import nuvio.composeapp.generated.resources.playback_quality_column_needs
import nuvio.composeapp.generated.resources.playback_quality_column_size
import nuvio.composeapp.generated.resources.playback_quality_description
import nuvio.composeapp.generated.resources.playback_quality_estimated_connection
import nuvio.composeapp.generated.resources.playback_quality_last_measured
import nuvio.composeapp.generated.resources.playback_quality_last_measured_short
import nuvio.composeapp.generated.resources.playback_quality_loading
import nuvio.composeapp.generated.resources.playback_quality_manual
import nuvio.composeapp.generated.resources.playback_quality_needs
import nuvio.composeapp.generated.resources.playback_quality_needs_estimated
import nuvio.composeapp.generated.resources.playback_quality_needs_value
import nuvio.composeapp.generated.resources.playback_quality_needs_value_estimated
import nuvio.composeapp.generated.resources.playback_quality_no_match
import nuvio.composeapp.generated.resources.playback_quality_preferences
import nuvio.composeapp.generated.resources.playback_quality_over_connection
import nuvio.composeapp.generated.resources.playback_quality_over_connection_legend
import nuvio.composeapp.generated.resources.playback_quality_retest
import nuvio.composeapp.generated.resources.playback_quality_summary_with_size
import nuvio.composeapp.generated.resources.playback_quality_title
import nuvio.composeapp.generated.resources.playback_quality_variant_high
import nuvio.composeapp.generated.resources.playback_quality_variant_low
import nuvio.composeapp.generated.resources.playback_quality_variant_max
import nuvio.composeapp.generated.resources.playback_quality_variant_mid
import nuvio.composeapp.generated.resources.playback_quality_variant_only
import nuvio.composeapp.generated.resources.playback_quality_variant_tops_resolution
import nuvio.composeapp.generated.resources.playback_quality_your_connection
import org.jetbrains.compose.resources.stringResource
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Streamlined's quality picker.
 *
 * Every band here came from a source that exists for this title, so the set is different on
 * every title and a quality nobody released simply has no card. The bandwidth figure is the
 * one the chosen file actually needs, not a preset's nominal number.
 *
 * **One card per resolution, with its bands stacked inside it.** That is the shape
 * [PlaybackQualityOptions.build] already emits - Best available, then resolutions high to low,
 * each split High/Mid/Low - and the flat grid this replaced threw it away, making three bands
 * of one resolution peers of each other and of every other resolution.
 *
 * A thin renderer over pure functions, deliberately: the grouping, the resolution badge, the
 * band word, the required speed, the provider line and the connection meter all come from
 * [PlaybackQualityOptions] and [PlaybackSourceSelector], which are testable outside Compose.
 * The same reasoning `PlaybackProgress.step`/`isVisible` are built on.
 *
 * [estimatedMbps] is what the connection is thought to carry, [isConnectionMeasured] says whether
 * that came from a measurement or from the link type, and [isConnectionStale] says it came from a
 * measurement the app could not refresh. The header names which kind of number it is holding
 * rather than presenting all three alike.
 *
 * ⚠ **While [isMeasuringConnection] is true the sheet shows no figure at all - not the header
 * number, and not the card meters.** A value that is about to be replaced is worse than no value:
 * the previous build printed the stored figure, then swapped it seconds later when the probe
 * landed, so a user reading the 4K row watched it go from warned to fine having done nothing.
 * Withholding the header alone would not have fixed it, because [estimatedMbps] also feeds every
 * card's `connectionFit` - the meters and the over-connection warnings would still have moved at
 * that moment. The rows remain usable while the probe finishes; only the unresolved figure and
 * the verdicts derived from it are withheld.
 *
 * This is **not** the older behaviour of hiding the figure until it had been measured, which
 * stripped the meters off a connection that simply could not be measured and left it showing less
 * than one nobody had tried to measure. Once the measurement actually finishes - with a value or
 * a failure - the sheet commits to whatever it has, link-type guess included, and that figure
 * holds. The five-second deadline bounds Instant's automatic decision, not this displayed truth.
 *
 * The figure is used only to mark a card as a stretch; it never disables one, because the
 * estimate is still an estimate and the user may know their line better than the app does.
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
    isConnectionMeasured: Boolean,
    isConnectionStale: Boolean,
    isMeasuringConnection: Boolean,
    onOptionSelected: (PlaybackQualityOption) -> Unit,
    onRetestConnection: (() -> Unit)?,
    onChooseManually: () -> Unit,
    onAdjustPreferences: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    // **One figure for the life of the sheet.** The route publishes whatever the repository holds
    // right now, and the active probe lands a second or two after the sheet opens - so the header
    // changed under the user, and with it every card's meter and every over-connection warning.
    // A user reading the 4K row watched it go from warned to fine, or the reverse, having done
    // nothing. Numbers that move while being read are worse than numbers that arrive late.
    //
    // Latched upward only: a later, better measurement is worth showing, and the estimate is a
    // lower bound anyway (nothing feeding it can observe more than it asked for), so a smaller
    // figure arriving afterwards is not new information. Scoped to this composition, so the next
    // sheet starts from whatever is current.
    var latchedMbps by remember { mutableStateOf<Double?>(null) }
    var latchedMeasured by remember { mutableStateOf(false) }
    // **Cleared whenever a measurement begins**, which is the one rule that keeps the latch from
    // outliving its purpose. It exists to stop a figure moving while it is being read; it must
    // never stop a figure the user deliberately asked for from arriving, and a re-test that came
    // back *lower* is exactly the answer they were owed. Keyed on the flag rather than on a
    // re-test counter so the sheet does not need to know why the measurement started.
    LaunchedEffect(isMeasuringConnection) {
        if (isMeasuringConnection) {
            latchedMbps = null
            latchedMeasured = false
        }
    }
    LaunchedEffect(estimatedMbps, isConnectionMeasured, isMeasuringConnection) {
        // Nothing is latched from a figure that is still being replaced - it would be latched
        // and then, being upward-only, could refuse the real measurement that follows it.
        if (isMeasuringConnection) return@LaunchedEffect
        val incoming = estimatedMbps?.takeIf { it > 0.0 } ?: return@LaunchedEffect
        val held = latchedMbps
        val accept = when {
            // Nothing held yet.
            held == null -> true
            // A measurement supersedes a link-type guess however the two numbers compare - that
            // is the one case where the *provenance* decides rather than the value.
            isConnectionMeasured && !latchedMeasured -> true
            // A guess must never displace a measurement, in either direction.
            !isConnectionMeasured && latchedMeasured -> false
            // Like for like: upward only.
            else -> incoming > held
        }
        if (accept) {
            latchedMbps = incoming
            latchedMeasured = isConnectionMeasured
        }
    }
    // **Null while measuring, and that null travels all the way down to the cards.**
    // `PlaybackQualityOptions.connectionFit` already returns null for a null estimate, so every
    // meter and every over-connection verdict disappears for the same window the header number
    // does. Anything less leaves the meters to jump when the probe lands, which is the same
    // fault wearing a smaller hat.
    val shownMbps = if (isMeasuringConnection) null else latchedMbps ?: estimatedMbps
    val shownMeasured = when {
        isMeasuringConnection -> false
        latchedMbps != null -> latchedMeasured
        else -> isConnectionMeasured
    }

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
            val entry = remember { Animatable(0f) }
            LaunchedEffect(Unit) {
                entry.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = PlaybackEntranceMotion.DURATION_MS,
                        easing = NuvioTokens.Motion.emphasized,
                    ),
                )
            }
            val entered = entry.value
            val scrim = tokens.colors.overlayScrim
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(scrim.copy(alpha = scrim.alpha * PlaybackEntranceMotion.scrimAlpha(entered)))
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
                        .graphicsLayer {
                            alpha = PlaybackEntranceMotion.panelAlpha(entered)
                            val s = PlaybackEntranceMotion.panelScale(entered)
                            scaleX = s
                            scaleY = s
                            translationY = PlaybackEntranceMotion.panelRiseDp(entered) * density
                        }
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
                    QualityColumnsBody(
                        options = options,
                        isLoading = isLoading,
                        isSelecting = isSelecting,
                        selectionContext = selectionContext,
                        estimatedMbps = shownMbps,
                        isConnectionMeasured = shownMeasured,
                        isConnectionStale = isConnectionStale,
                        isMeasuringConnection = isMeasuringConnection,
                        onOptionSelected = onOptionSelected,
                        onRetestConnection = onRetestConnection,
                        onChooseManually = onChooseManually,
                        onAdjustPreferences = onAdjustPreferences,
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
                    estimatedMbps = shownMbps,
                    isConnectionMeasured = shownMeasured,
                    isConnectionStale = isConnectionStale,
                    isMeasuringConnection = isMeasuringConnection,
                    onRetestConnection = onRetestConnection,
                    gridMaxHeight = gridMaxHeight,
                    contentBottomPadding = nuvioSafeBottomPadding(tokens.spacing.sheetPadding),
                    onOptionSelected = onOptionSelected,
                    onChooseManually = onChooseManually,
                    onAdjustPreferences = onAdjustPreferences,
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
 *
 * The connection figure obeys the same rule for the same reason. [estimatedMbps] arrives null
 * while it is being measured, so the header says so and no card draws a meter until it lands.
 */
@Composable
private fun QualitySheetBody(
    options: List<PlaybackQualityOption>,
    isLoading: Boolean,
    isSelecting: Boolean,
    selectionContext: PlaybackSelectionContext,
    estimatedMbps: Double?,
    isConnectionMeasured: Boolean,
    isConnectionStale: Boolean,
    isMeasuringConnection: Boolean,
    gridMaxHeight: Dp,
    contentBottomPadding: Dp,
    onOptionSelected: (PlaybackQualityOption) -> Unit,
    onRetestConnection: (() -> Unit)?,
    onChooseManually: () -> Unit,
    onAdjustPreferences: (() -> Unit)?,
) {
    val tokens = MaterialTheme.nuvio
    val groups = remember(options) { PlaybackQualityOptions.group(options) }
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
        // The title and the way into the preferences that shaped this grid.
        //
        // **A dialog over the sheet, not a navigation.** Settings live on a route that is not on
        // this back stack, so opening the real page would pop `StreamRoute` and lose the play -
        // the user would come back to the details screen having asked for an episode. The moment
        // someone wants to change an HDR policy or a ceiling is the moment they are looking at a
        // row they disagree with, and making them abandon the play to act on it is why the
        // preferences went unfound in the first place.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.playback_quality_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            if (onAdjustPreferences != null) {
                TextButton(onClick = onAdjustPreferences) {
                    Text(stringResource(Res.string.playback_quality_preferences))
                }
            }
        }
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
        // One line, always drawn, and it names which kind of number it is holding.
        //
        // ⚠ **"Checking" is tested first and must stay first.** It used to sit below the measured
        // case, and because a figure restored from storage also counts as measured, a sheet that
        // was actively re-measuring still printed the stored number and swapped it a second or
        // two later - "Your connection: about 56 Mb/s" becoming 81 while it was being read. The
        // order of these branches *is* the fix; the caller passes a null figure for the same
        // window so the cards cannot contradict this line.
        //
        // The non-breaking space holds the line open in the last case, where there is nothing to
        // measure with and nothing to report, so the grid does not shift when a figure arrives.
        val connectionLine = when {
            isMeasuringConnection -> stringResource(Res.string.playback_quality_checking_connection)
            estimatedMbps == null || estimatedMbps <= 0.0 -> "\u00A0"
            // A measurement the app could not refresh. Days old and still rendered as "your
            // connection" is the same untruth as printing a preset, arriving by a slower road.
            isConnectionStale -> stringResource(
                Res.string.playback_quality_last_measured,
                estimatedMbps.roundToInt(),
            )
            isConnectionMeasured -> stringResource(
                Res.string.playback_quality_your_connection,
                estimatedMbps.roundToInt(),
            )
            else -> stringResource(
                Res.string.playback_quality_estimated_connection,
                estimatedMbps.roundToInt(),
            )
        }
        Text(
            text = connectionLine,
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
            maxLines = 1,
            // Tapping re-measures. Without it the only way to ask for a fresh reading was to
            // close and reopen the app, and even that did nothing: the estimate outlives the
            // process by a week and the probe is suppressed for ten minutes after each one.
            // Inert while one is already running, so a second tap cannot queue a second probe.
            modifier = Modifier
                .then(
                    if (onRetestConnection != null) {
                        Modifier.clickable(
                            enabled = !isMeasuringConnection,
                            onClick = onRetestConnection,
                        )
                    } else {
                        Modifier
                    },
                )
                .padding(vertical = CONNECTION_LINE_TAP_PADDING),
        )

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
                items(
                    items = groups,
                    // The resolution, not a position: the grid is rebuilt whenever an addon
                    // answers, and a positional key would re-associate every card's state
                    // with a different resolution as the set grows. Same reasoning as
                    // PlaybackQualityOption.id, which this replaces at the group level.
                    key = { it.resolutionLabel.ifBlank { BEST_GROUP_KEY } },
                    // Best available is not a resolution and has no bands, so it heads the
                    // grid on its own line rather than sitting beside 4K as its peer.
                    span = {
                        if (it.resolution == null) GridItemSpan(maxLineSpan) else GridItemSpan(1)
                    },
                ) { group ->
                    ResolutionGroupCard(
                        group = group,
                        selectionContext = selectionContext,
                        estimatedMbps = estimatedMbps,
                        isConnectionMeasured = isConnectionMeasured,
                        // Disabled, not removed. A second tap while the first is being acted
                        // on would re-arm the selection effect against a different option.
                        enabled = !isSelecting,
                        onOptionSelected = onOptionSelected,
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

/**
 * One resolution and every band offered at it.
 *
 * The resolution is named once, in the badge, because it is one decision - the flat grid this
 * replaced made "1080p High", "1080p Mid" and "1080p Low" three peers of each other and of
 * every other resolution, and repeated the word three times to say it once.
 *
 * **The card is not a tap target; the rows inside it are.** A card carries up to three
 * options and there is no sensible default among them, so a tap on the header would either do
 * nothing or silently pick one.
 *
 * **No source count in the header, deliberately.** `option.candidates` is the whole bucket
 * including candidates the protocol and cache gates will skip, so any number printed here
 * would overstate what can actually play - the same class of untruth as describing a row from
 * `candidates.first()` rather than from [PlaybackSourceSelector.previewSelection].
 */
@Composable
private fun ResolutionGroupCard(
    group: PlaybackQualityGroup,
    selectionContext: PlaybackSelectionContext,
    estimatedMbps: Double?,
    isConnectionMeasured: Boolean,
    enabled: Boolean,
    onOptionSelected: (PlaybackQualityOption) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = tokens.shapes.card,
        color = tokens.colors.surfaceCard,
    ) {
        Column(
            modifier = Modifier.padding(tokens.spacing.cardPaddingCompact),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        ) {
            // Best available claims no resolution, so the badge carries its name instead of a
            // glyph standing in for one. A "★" badge over a row that then said "Best
            // available" was the same thing said twice - the fault this layout exists to fix.
            ResolutionBadge(
                group.resolutionLabel.ifBlank { stringResource(Res.string.playback_quality_best) },
            )
            group.options.forEach { option ->
                QualityTierRow(
                    option = option,
                    selectionContext = selectionContext,
                    estimatedMbps = estimatedMbps,
                    isConnectionMeasured = isConnectionMeasured,
                    enabled = enabled,
                    onClick = { onOptionSelected(option) },
                )
            }
        }
    }
}

/**
 * One band within a resolution, and the thing the user actually taps.
 *
 * Everything on it is per-option and none of it can be lifted to the card: the caption names
 * the release *this* band would open, and the over-connection warning is true of one band and
 * false of the one under it. That is why the bands are stacked rows and not a row of chips -
 * a chip three-across on a phone is about 105 dp, which holds the band word and the figures
 * and nothing else, and dropping the provider line to fit would name a release the user never
 * receives.
 *
 * Drawn as a lift over the card rather than a second [Surface] colour: `surfaceCard` on
 * `surfaceCard` is invisible, and there is no third card colour in the token set. The
 * hairline border is what survives if the lift is ever tuned towards the card.
 */
@Composable
private fun QualityTierRow(
    option: PlaybackQualityOption,
    selectionContext: PlaybackSelectionContext,
    estimatedMbps: Double?,
    isConnectionMeasured: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    // Resolved once. Both the headline and the figures describe the source that would really
    // open, and asking twice would let them describe two different ones if the gates ever
    // stopped being deterministic.
    val preview = remember(option, selectionContext) {
        PlaybackSourceSelector.previewSelection(option, selectionContext)
    }
    val isBest = option.variant == PlaybackQualityOption.Variant.BEST
    // Best available spans the whole catalogue, so it has no bucket cost of its own - but the
    // file it would open has one, and that is the number this card has never quoted.
    val requiredMbps = option.requiredMbps
        ?: preview?.let { PlaybackQualityOptions.requiredMbpsFor(it, selectionContext) }
    // The warning is withheld until the estimate is a real measurement - see `connectionFit`.
    // The meter still draws, because a rough baseline is useful to compare rows against; it is
    // the *verdict* that has to be earned.
    val fit = PlaybackQualityOptions.connectionFit(requiredMbps, estimatedMbps, isConnectionMeasured)
    val band = if (isBest) bestReleaseLine(preview) else variantLabel(option)
    val source = preview?.let { PlaybackSourceSelector.describeRelease(it.facts) }.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(tokens.shapes.compactCard)
            .background(tokens.colors.overlayHover)
            .border(
                width = tokens.borders.hairline,
                color = tokens.colors.borderSubtle,
                shape = tokens.shapes.compactCard,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(tokens.spacing.cardPaddingCompact),
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
    ) {
        // Only real figures earn the trailing slot. A source whose size nobody reported still
        // has none, and the sheet's own description standing in for one restated the sentence
        // three lines under itself.
        val figures = requiredMbps?.let {
            optionSummary(
                requiredMbps = it,
                isApproximate = option.isEstimateApproximate,
                // Best available already carries its size in the headline; repeating it in the
                // trailing slot would spend the widest line on the card saying it twice.
                sizeBytes = if (isBest) null else option.representativeSizeBytes,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
            if (preview?.facts?.isAiUpscaled == true) {
                AiUpscaleChip()
            }
            if (figures != null) {
                // Trailing, so the figures line up down a card whose band words differ in
                // width. Carries the whole row for Variant.SINGLE, which has no band word.
                Text(
                    text = figures,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textSecondary,
                    textAlign = if (band.isBlank()) TextAlign.Start else TextAlign.End,
                )
            }
        }
        if (source.isNotBlank()) {
            // A caption under the figures, on every card now. Best available used to reach
            // here with nothing above it and had to carry the row in `bodyMedium`; it has a
            // headline of its own since that headline started saying what the file is.
            val isOnlyContent = band.isBlank() && figures == null
            Text(
                text = source,
                style = if (isOnlyContent) {
                    MaterialTheme.typography.bodyMedium
                } else {
                    MaterialTheme.typography.labelSmall
                },
                color = if (preview?.facts?.isTheatricalCapture == true) {
                    tokens.colors.danger
                } else if (isOnlyContent) {
                    tokens.colors.textPrimary
                } else {
                    tokens.colors.textMuted
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (fit != null) {
            ConnectionMeter(fit = fit)
        }
        if (fit?.isOverConnection == true) {
            // Said, not enforced. The estimate is a guess; the user may know better
            // than the app does, and a band they cannot pick is worse than a warning.
            Text(
                text = stringResource(Res.string.playback_quality_over_connection),
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.danger,
            )
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
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = QUALITY_CARD_MIN_WIDTH),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = gridMaxHeight),
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.listGap),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.listGap),
    ) {
        items(SKELETON_CARD_COUNT) {
            // The shared sweep, not the alpha pulse this used to run. Nuvio had four different
            // indeterminate motions on the path to a frame - this pulse, the sheet's spinner,
            // the list's spinner and the player overlay's logo throb - so the *shape* of the
            // waiting changed two or three times on a journey where nothing had gone wrong.
            NuvioSkeletonBlock(
                modifier = Modifier.fillMaxWidth(),
                height = SKELETON_CARD_HEIGHT,
                cornerRadius = SKELETON_CARD_CORNER_RADIUS,
            )
        }
    }
}

private val SKELETON_CARD_CORNER_RADIUS = 12.dp

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
 * A band named in full - `1080p High`, `4K`, `Best available` - for callers with no badge.
 *
 * The sheet itself never needs this: it puts the resolution in a badge and the band word in the
 * row, and repeating either would be saying one thing twice. Anything *outside* the sheet has
 * neither, and has to name the whole choice - the toast that announces a skipped sheet is the
 * case this exists for, and it has to use the user's own words for the row they picked or the
 * announcement names something they will not recognise.
 *
 * Built from [variantLabel] rather than beside it, so the sheet and everything quoting it
 * cannot drift.
 */
@Composable
fun playbackQualityOptionLabel(option: PlaybackQualityOption): String {
    if (option.variant == PlaybackQualityOption.Variant.BEST) {
        return stringResource(Res.string.playback_quality_best)
    }
    val resolution = option.resolutionLabel
    val variant = variantLabel(option)
    return listOf(resolution, variant).filter { it.isNotBlank() }.joinToString(" ")
}

/**
 * The row's band word, given that the resolution is already in the badge above it.
 *
 * "4K" belongs in the badge; "High" is a comparison with the rows around it. A resolution
 * with a single row has nothing to compare against, so it says nothing rather than
 * repeating the badge back to the user - the badge alone already names it.
 *
 * [PlaybackQualityOption.Variant.BEST] is silent for that same reason and not because it has
 * no name: its card's badge *is* its name, since it has no resolution to put there.
 */
@Composable
private fun variantLabel(option: PlaybackQualityOption): String = variantLabel(option.variant)

@Composable
private fun variantLabel(variant: PlaybackQualityOption.Variant): String = when (variant) {
    PlaybackQualityOption.Variant.BEST -> ""
    PlaybackQualityOption.Variant.MAX -> stringResource(Res.string.playback_quality_variant_max)
    PlaybackQualityOption.Variant.HIGH -> stringResource(Res.string.playback_quality_variant_high)
    PlaybackQualityOption.Variant.MID -> stringResource(Res.string.playback_quality_variant_mid)
    PlaybackQualityOption.Variant.LOW -> stringResource(Res.string.playback_quality_variant_low)
    PlaybackQualityOption.Variant.SINGLE -> ""
}

/**
 * `4K · DV · 18.2 GB` - the headline for Best available.
 *
 * That card has no resolution badge over it, so until now its only line was the shared
 * `WEB-DL · TorBox` caption: on the option a user is most likely to tap, the two facts named
 * were which rip it came from and which host is serving it. Neither is what anyone is
 * choosing between. Resolution, dynamic range and size are, and this card is the one place
 * they are not already on screen.
 *
 * Empty when no source survives the gates - the release the user would actually receive is
 * the only one worth naming, and `option.candidates.first()` is not it.
 */
@Composable
private fun bestReleaseLine(preview: PlaybackSourceCandidate?): String =
    preview?.let { PlaybackSourceSelector.describeBestRelease(it.facts, ::formatFileSize) }
        .orEmpty()

@Composable
private fun optionSummary(
    requiredMbps: Double,
    isApproximate: Boolean,
    sizeBytes: Long?,
): String {
    // Rounded up: quoting 4 Mb/s for something that needs 4.6 is the one direction that
    // turns an informed choice into a stall.
    val speed = stringResource(
        if (isApproximate) {
            Res.string.playback_quality_needs_estimated
        } else {
            Res.string.playback_quality_needs
        },
        ceil(requiredMbps).roundToInt(),
    )
    val size = sizeBytes?.let(::formatFileSize) ?: return speed
    return stringResource(Res.string.playback_quality_summary_with_size, speed, size)
}

/**
 * The grid key for the Best available card, which has no resolution label to be keyed on.
 * Never rendered - a resolution label is what the user sees on every other card.
 */
private const val BEST_GROUP_KEY = "best"

/**
 * Where the estimate sits along the meter's track. Derived from
 * [PlaybackQualityOptions.MAX_LOAD_FRACTION] rather than restating its inverse: the KDoc on
 * [ConnectionMeter] claims the marker *is* the connection, and a literal here would quietly
 * make that false the moment the clamp in the other file changed.
 */
private val ESTIMATE_MARKER_FRACTION =
    (1.0 / PlaybackQualityOptions.MAX_LOAD_FRACTION).toFloat()

/**
 * A settled title is usually Best available plus two or three resolutions, not the four flat
 * options the old grid placed here. Both this and [SKELETON_CARD_HEIGHT] exist only so the
 * surface does not jump when the figures arrive, which makes them wrong the moment the real
 * card's footprint changes - they are not free-standing constants.
 */
/**
 * Vertical padding that turns the connection line into something a finger can hit.
 *
 * One line of `bodySmall` is well under any sane touch target, and the re-test is the only way
 * to ask for a fresh reading. Padding rather than a `height`, so the line still collapses to the
 * non-breaking space when there is nothing to report.
 */
private val CONNECTION_LINE_TAP_PADDING = NuvioTokens.Space.s8

private const val SKELETON_CARD_COUNT = 3

/**
 * Card width below which the over-connection warning stops fitting on two lines - still the
 * part of the card the user asked to keep legible, so it still sets the column width rather
 * than being shrunk to fit more columns in.
 *
 * Wider than the 240 dp the flat grid used, because that warning is now two levels of padding
 * in rather than one: the card's `cardPaddingCompact` and the tier row's own. 280 dp leaves
 * the row the same interior the old card gave it, plus room for the band word and the figures
 * to share a line.
 */
private val QUALITY_CARD_MIN_WIDTH = 280.dp

/** The repo's tablet threshold. See App.kt:2051 and ProfileSelectionScreen.kt:112. */
private val WIDE_LAYOUT_MIN_WIDTH = 768.dp

private val RESOLUTION_BADGE_MIN_WIDTH = NuvioTokens.Space.s56

/** A badge and two tier rows: the middle of the range a group card can occupy. */
private val SKELETON_CARD_HEIGHT = NuvioTokens.Space.s96 * 2 + NuvioTokens.Space.s56
private val GRID_HEIGHT_INSET = NuvioTokens.Space.s96 * 2
private val GRID_MIN_HEIGHT = NuvioTokens.Space.s96 * 2
private val GRID_MAX_HEIGHT = NuvioTokens.Space.s96 * 6

private val PANEL_PADDING = NuvioTokens.Space.s24
private val COLUMN_GAP = NuvioTokens.Space.s16
private val CELL_GAP = NuvioTokens.Space.s8
private val COLUMN_HEADER_HEIGHT = NuvioTokens.Space.s32
private val CHIP_ROW_HEIGHT = NuvioTokens.Space.s20
private val PROVENANCE_ROW_HEIGHT = NuvioTokens.Space.s16
private val HERO_METER_WIDTH = NuvioTokens.Space.s96
private val HERO_SKELETON_HEIGHT = NuvioTokens.Space.s80
private val CELL_SKELETON_HEIGHT = NuvioTokens.Space.s80
private const val SKELETON_COLUMN_COUNT = 4
private const val SKELETON_CELL_COUNT = 3
private const val ACCENT_BORDER_ALPHA = 0.45f
private const val UNKNOWN_VALUE = "—"
private const val FIGURE_SEPARATOR = "·"

/**
 * The wide branch: the recommended option as a strip, then one column per resolution.
 *
 * **Nothing here scrolls, and that is the design rather than an omission.** The catalogue is
 * bounded by construction - `VideoResolution` has six members and `optionsForBucket` emits at
 * most four bands each - so the whole offer fits a desktop window if it is spent across the
 * width instead of down a single column. The table this replaced was capped at 480 dp and cut
 * its last row through the middle of the glyphs, with no scrollbar to say there was more; a
 * layout that cannot overflow cannot do that.
 *
 * ⚠ **The phone branch keeps [QualitySheetBody] and its card grid**, unedited. It also serves
 * tablets under 768 dp, where there is no width to spend.
 */
@Composable
private fun QualityColumnsBody(
    options: List<PlaybackQualityOption>,
    isLoading: Boolean,
    isSelecting: Boolean,
    selectionContext: PlaybackSelectionContext,
    estimatedMbps: Double?,
    isConnectionMeasured: Boolean,
    isConnectionStale: Boolean,
    isMeasuringConnection: Boolean,
    onOptionSelected: (PlaybackQualityOption) -> Unit,
    onRetestConnection: (() -> Unit)?,
    onChooseManually: () -> Unit,
    onAdjustPreferences: (() -> Unit)?,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(PANEL_PADDING),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.playback_quality_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            if (onAdjustPreferences != null) {
                // ⚠ Muted, like every other button on this panel. They are all secondary to
                // picking a row, and rendering them at full strength made the loudest things
                // on the sheet the three things nobody came here to do.
                QuietTextButton(
                    text = stringResource(Res.string.playback_quality_preferences),
                    onClick = onAdjustPreferences,
                )
            }
        }
        Text(
            text = when {
                isLoading -> stringResource(Res.string.playback_quality_loading)
                isSelecting -> stringResource(Res.string.playback_progress_choosing)
                else -> stringResource(Res.string.playback_quality_description)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textSecondary,
        )

        val connectionLine = when {
            isMeasuringConnection -> stringResource(Res.string.playback_quality_checking_connection)
            // A non-breaking space, matching the phone branch: the strip keeps its height
            // before a figure exists, so the columns under it do not start high and then
            // drop when the probe lands.
            estimatedMbps == null || estimatedMbps <= 0.0 -> "\u00A0"
            isConnectionStale -> stringResource(
                Res.string.playback_quality_last_measured_short,
                estimatedMbps.roundToInt(),
            )
            isConnectionMeasured -> stringResource(
                Res.string.playback_quality_your_connection,
                estimatedMbps.roundToInt(),
            )
            else -> stringResource(
                Res.string.playback_quality_estimated_connection,
                estimatedMbps.roundToInt(),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = connectionLine,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            if (onRetestConnection != null) {
                QuietTextButton(
                    text = stringResource(Res.string.playback_quality_retest),
                    onClick = onRetestConnection,
                    enabled = !isMeasuringConnection,
                )
            }
        }

        when {
            isLoading -> QualityColumnsSkeleton()
            options.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(NuvioTokens.Space.s96 * 2),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.playback_quality_no_match),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
            else -> {
                val groups = remember(options) { PlaybackQualityOptions.group(options) }
                val best = groups.firstOrNull { it.resolution == null }?.options?.firstOrNull()
                // Which banded row Best available actually resolves to, so that row can be
                // marked rather than the panel stating one file twice, side by side.
                val bestSourceKey = remember(best, selectionContext) {
                    best?.let { PlaybackSourceSelector.previewSelection(it, selectionContext) }
                        ?.let(PlaybackQualityOptions::sourceKey)
                }
                best?.let {
                    BestAvailableHero(
                        option = it,
                        selectionContext = selectionContext,
                        estimatedMbps = estimatedMbps,
                        isConnectionMeasured = isConnectionMeasured,
                        enabled = !isSelecting,
                        onClick = { onOptionSelected(it) },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP),
                    verticalAlignment = Alignment.Top,
                ) {
                    groups.filter { it.resolution != null }.forEach { group ->
                        // Equal weights rather than measured widths: the columns are being
                        // compared across, and a 4K column wider than the 1080p one because
                        // its release names are longer would put that comparison on a slant.
                        Column(modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(COLUMN_HEADER_HEIGHT)
                                    .padding(horizontal = NuvioTokens.Space.s12),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(
                                    text = group.resolutionLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = tokens.colors.textPrimary,
                                    maxLines = 1,
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(tokens.borders.hairline)
                                    .background(tokens.colors.borderSubtle),
                            )
                            Spacer(Modifier.height(CELL_GAP))
                            Column(verticalArrangement = Arrangement.spacedBy(CELL_GAP)) {
                                group.options.forEach { option ->
                                    QualityColumnCell(
                                        group = group,
                                        option = option,
                                        selectionContext = selectionContext,
                                        estimatedMbps = estimatedMbps,
                                        isConnectionMeasured = isConnectionMeasured,
                                        bestSourceKey = bestSourceKey,
                                        enabled = !isSelecting,
                                        onClick = { onOptionSelected(option) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        val anyOverConnection = remember(options, estimatedMbps, isConnectionMeasured, selectionContext) {
            options.any { option ->
                val requiredMbps = option.requiredMbps
                    ?: PlaybackSourceSelector.previewSelection(option, selectionContext)?.let {
                        PlaybackQualityOptions.requiredMbpsFor(it, selectionContext)
                    }
                PlaybackQualityOptions.connectionFit(requiredMbps, estimatedMbps, isConnectionMeasured)
                    ?.isOverConnection == true
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(tokens.borders.hairline)
                .background(tokens.colors.borderSubtle),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        ) {
            if (anyOverConnection) {
                Box(
                    modifier = Modifier
                        .size(NuvioTokens.Space.s8)
                        .clip(tokens.shapes.chip)
                        .background(tokens.colors.warning),
                )
                Text(
                    text = stringResource(Res.string.playback_quality_over_connection_legend),
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.colors.warning,
                )
            }
            Spacer(Modifier.weight(1f))
            QuietTextButton(
                text = stringResource(Res.string.playback_quality_manual),
                onClick = onChooseManually,
            )
        }
    }
}

/**
 * A `TextButton` that does not outshout the thing it sits beside.
 *
 * Preferences, Re-test and Choose source manually all rendered in the same bright weight as the
 * options themselves, which left the panel's three least likely actions louder than its
 * fourteen most likely ones.
 */
@Composable
private fun QuietTextButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    val tokens = MaterialTheme.nuvio
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = tokens.colors.textSecondary),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Everything a cell or the hero prints about one option, derived once.
 *
 * Both surfaces quote the same figures off the same preview, so deriving them twice is how the
 * hero and the column under it start disagreeing about the same file.
 */
private data class QualityFigures(
    val provenance: String,
    val dynamicRange: String?,
    val audio: String?,
    val resolutionLabel: String,
    val size: String,
    val isSizeKnown: Boolean,
    val needs: String,
    val isNeedsKnown: Boolean,
    val fit: PlaybackQualityOptions.ConnectionFit?,
    val sourceKey: String?,
    val isAiUpscaled: Boolean = false,
    val isTheatricalCapture: Boolean = false,
)

/**
 * ⚠ Every part of this is an already-tested pure function. Nothing here ranks, groups, bands or
 * formats anything of its own - if a new one is needed it belongs in `PlaybackQualityOptions.kt`
 * or `PlaybackSourceSelector.kt` with a case in their tests, because this file is Compose and
 * the pure suite cannot reach it.
 */
@Composable
private fun qualityFigures(
    option: PlaybackQualityOption,
    selectionContext: PlaybackSelectionContext,
    estimatedMbps: Double?,
    isConnectionMeasured: Boolean,
): QualityFigures {
    val preview = remember(option, selectionContext) {
        PlaybackSourceSelector.previewSelection(option, selectionContext)
    }
    val requiredMbps = option.requiredMbps
        ?: preview?.let { PlaybackQualityOptions.requiredMbpsFor(it, selectionContext) }
    val fit = PlaybackQualityOptions.connectionFit(requiredMbps, estimatedMbps, isConnectionMeasured)

    // ⚠ Falls through to the preview's own size. `representativeSizeBytes` is null by
    // construction on Best available, so reading only that printed an em-dash for a file whose
    // size was sitting in the candidate it had already resolved to - and the column beside it
    // printed the figure. One panel, two answers about one file.
    val sizeBytes = option.representativeSizeBytes?.takeIf { it > 0L }
        ?: preview?.facts?.sizeBytes?.takeIf { it > 0L }

    // ⚠ Rounded up. Quoting 4 for something that needs 4.6 is the one direction that turns an
    // informed choice into a stall.
    val needs = if (requiredMbps == null) {
        UNKNOWN_VALUE
    } else {
        val rounded = ceil(requiredMbps).roundToInt()
        if (option.isEstimateApproximate) {
            stringResource(Res.string.playback_quality_needs_value_estimated, rounded)
        } else {
            stringResource(Res.string.playback_quality_needs_value, rounded)
        }
    }

    return QualityFigures(
        provenance = PlaybackSourceSelector.describeProvenance(preview?.facts),
        // ⚠ `dynamicRangeSlot`, not `dynamicRangeLabel`: it answers `SDR` for a release that
        // named no range, and null only when there is no release yet. The bare label left every
        // cell of an SDR column with an empty mark row, which reads as a fact that failed to
        // load rather than as a release that is simply not HDR. The default is earned - the
        // loading band already reads silence this way, because release names carry dynamic
        // range reliably and audio only sometimes.
        dynamicRange = PlaybackLoadingFacts.dynamicRangeSlot(preview?.facts),
        audio = PlaybackLoadingFacts.audioLabel(preview?.facts),
        resolutionLabel = preview?.facts?.resolution.qualityLabel,
        size = sizeBytes?.let(::formatFileSize) ?: UNKNOWN_VALUE,
        isSizeKnown = sizeBytes != null,
        needs = needs,
        isNeedsKnown = requiredMbps != null,
        fit = fit,
        sourceKey = PlaybackQualityOptions.sourceKey(preview),
        isAiUpscaled = preview?.facts?.isAiUpscaled == true,
        isTheatricalCapture = preview?.facts?.isTheatricalCapture == true,
    )
}

/**
 * `Max`, `Mid (Max)`, or `Only option`.
 *
 * The parenthetical is the answer to a real complaint: a title whose 1080p releases all sit
 * under the Max boundary offers "High" as its ceiling at that resolution, and a lone "High"
 * reads as a middling pick rather than as the best 1080p this title has. The band word itself
 * is never rewritten - the bands are absolute, and relabelling one would be exactly the
 * catalogue-relative naming [PlaybackQualityOption.Variant] exists to end.
 */
@Composable
private fun columnBandLabel(
    group: PlaybackQualityGroup,
    option: PlaybackQualityOption,
): String {
    // ⚠ The *derived* band, not `option.variant`. A bucket that collapsed to one row carries
    // Variant.SINGLE and so had no word at all - it read "Only option", which told the reader
    // nothing about what they would get. `bandFor` gives it the class it would have been banded
    // as, off the same absolute boundaries every other row uses, so "1440p Low" means the same
    // thing whether it arrived as a band or as the only release there was.
    val band = PlaybackQualityOptions.bandFor(option)
        ?: return stringResource(Res.string.playback_quality_variant_only)
    val word = variantLabel(band)
    if (!PlaybackQualityOptions.isTopBandBelowMax(group, option)) return word
    return stringResource(
        Res.string.playback_quality_variant_tops_resolution,
        word,
        stringResource(Res.string.playback_quality_variant_max),
    )
}

/**
 * What a release does to your screen and to your speakers, as two marks rather than as words
 * inside a sentence.
 *
 * ⚠ **These are the facts that separate one row from its neighbours**, and they were buried:
 * `describeRelease` folded dynamic range into a run of text whose loudest tokens were the rip
 * type and the host, and audio was not shown at all. Down a column, `BLURAY` repeats and
 * `DV · Atmos 7.1` does not - so the repeating part was the part being read.
 *
 * Outlined rather than filled, at label scale: a filled accent pill is what made the card grid
 * this panel replaced read as a phone screen.
 */
@Composable
private fun FeatureChips(
    dynamicRange: String?,
    audio: String?,
    isAiUpscaled: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    // ⚠ Fixed height whether or not there is anything to draw. A release that names neither
    // fact must leave its neighbours where they are.
    Row(
        modifier = modifier.height(CHIP_ROW_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
    ) {
        if (isAiUpscaled) {
            AiUpscaleChip()
        }
        dynamicRange?.let {
            // ⚠ Accent is for a release that does something extra to your screen. `SDR` is the
            // absence of that, and giving it the same mark spends the panel's one emphasis on
            // the ordinary case - the 4K column's `DV` only reads as special while the columns
            // beside it read as plain.
            val isPlain = it == PlaybackLoadingFacts.SDR
            FeatureChip(
                text = it,
                color = if (isPlain) tokens.colors.textMuted else tokens.colors.accent,
            )
        }
        audio?.let { FeatureChip(text = it, color = tokens.colors.textSecondary) }
    }
}

@Composable
private fun AiUpscaleChip() {
    val tokens = MaterialTheme.nuvio
    val dangerColor = tokens.colors.danger
    Box(
        modifier = Modifier
            .clip(tokens.shapes.chip)
            .background(dangerColor.copy(alpha = 0.12f))
            .border(tokens.borders.hairline, dangerColor.copy(alpha = 0.35f), tokens.shapes.chip)
            .padding(horizontal = NuvioTokens.Space.s6, vertical = NuvioTokens.Space.s2),
    ) {
        Text(
            text = "AI Upscale",
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = NuvioTokens.LetterSpacing.label,
            color = dangerColor,
            maxLines = 1,
        )
    }
}

@Composable
private fun FeatureChip(text: String, color: Color) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = Modifier
            .clip(tokens.shapes.chip)
            .border(tokens.borders.hairline, color.copy(alpha = ACCENT_BORDER_ALPHA), tokens.shapes.chip)
            .padding(horizontal = NuvioTokens.Space.s6, vertical = NuvioTokens.Space.s2),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = NuvioTokens.LetterSpacing.label,
            color = color,
            maxLines = 1,
        )
    }
}

/**
 * The recommended option, given the weight it earns.
 *
 * It is what most plays take and it used to be the first row of a table, indistinguishable from
 * the five under it. A panel whose default choice looks exactly like its alternatives is a list;
 * one that leads with the answer is a layout.
 */
@Composable
private fun BestAvailableHero(
    option: PlaybackQualityOption,
    selectionContext: PlaybackSelectionContext,
    estimatedMbps: Double?,
    isConnectionMeasured: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val figures = qualityFigures(option, selectionContext, estimatedMbps, isConnectionMeasured)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(tokens.shapes.card)
            .background(
                if (hovered && enabled) tokens.colors.overlayHover else tokens.colors.surfaceElevated,
            )
            .border(
                width = tokens.borders.hairline,
                color = tokens.colors.accent.copy(alpha = ACCENT_BORDER_ALPHA),
                shape = tokens.shapes.card,
            )
            .hoverable(interaction, enabled = enabled)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = NuvioTokens.Space.s20, vertical = NuvioTokens.Space.s16),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s24),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
        ) {
            Text(
                text = stringResource(Res.string.playback_quality_best).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = NuvioTokens.LetterSpacing.label,
                color = tokens.colors.accent,
                maxLines = 1,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
            ) {
                Text(
                    text = listOf(figures.resolutionLabel, figures.provenance)
                        .filter { it.isNotBlank() }
                        .joinToString(" $FIGURE_SEPARATOR ")
                        .ifBlank { UNKNOWN_VALUE },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (figures.isTheatricalCapture) tokens.colors.danger else tokens.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                FeatureChips(
                    dynamicRange = figures.dynamicRange,
                    audio = figures.audio,
                    isAiUpscaled = figures.isAiUpscaled,
                )
            }
        }
        HeroFigure(
            label = stringResource(Res.string.playback_quality_column_size),
            value = figures.size,
            valueColor = if (figures.isSizeKnown) tokens.colors.textPrimary else tokens.colors.textMuted,
        )
        HeroFigure(
            label = stringResource(Res.string.playback_quality_column_needs),
            value = figures.needs,
            valueColor = needsColor(figures),
        )
        // ⚠ Reserved whether or not there is a figure for it. The estimate lands seconds after
        // the panel opens, and a meter that appeared then would shift the two figures beside it
        // at exactly the moment they are being read.
        Box(
            modifier = Modifier.width(HERO_METER_WIDTH),
            contentAlignment = Alignment.Center,
        ) {
            figures.fit?.let { ConnectionMeter(it) }
        }
    }
}

@Composable
private fun HeroFigure(label: String, value: String, valueColor: Color) {
    val tokens = MaterialTheme.nuvio
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = NuvioTokens.LetterSpacing.label,
            color = tokens.colors.textMuted,
            maxLines = 1,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = valueColor,
            maxLines = 1,
        )
    }
}

/**
 * One option inside its resolution's column: what band it is, what it will give you, what it
 * costs.
 *
 * Drawn on its own tinted block rather than as three lines of text in a run. Fourteen cells
 * with identical treatment and no edges between them read as a wall however carefully the
 * words inside them are chosen; the block is what makes a cell a thing you can point at.
 *
 * ⚠ [enabled] is `!isSelecting`. **Disabled, not removed and not greyed** - a second click while
 * the first is being acted on would re-arm the selection effect against a different option, and
 * a body that changed shape mid-selection would be the sheet moving under the user.
 */
@Composable
private fun QualityColumnCell(
    group: PlaybackQualityGroup,
    option: PlaybackQualityOption,
    selectionContext: PlaybackSelectionContext,
    estimatedMbps: Double?,
    isConnectionMeasured: Boolean,
    bestSourceKey: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val figures = qualityFigures(option, selectionContext, estimatedMbps, isConnectionMeasured)
    val label = columnBandLabel(group, option)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // The row Best available resolves to. Marked rather than repeated: the hero above states
    // the same file, and two identical offers side by side read as two files.
    val isRecommended = figures.sourceKey != null && figures.sourceKey == bestSourceKey

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(tokens.shapes.compactCard)
            // ⚠ `surfaceCard`, and the hover wash layered over it rather than replacing it.
            // `surface`, `surfaceElevated` and `surfaceDialog` are all the same colour in this
            // theme, so tinting the panel's own surface over itself drew nothing at all and the
            // cells stayed a run of text with no edges. `surfaceCard` is the one token that is
            // actually a block on a surface.
            .background(tokens.colors.surfaceCard)
            .background(if (hovered && enabled) tokens.colors.overlayHover else Color.Transparent)
            .then(
                if (isRecommended) {
                    Modifier.border(
                        width = tokens.borders.hairline,
                        color = tokens.colors.accent.copy(alpha = ACCENT_BORDER_ALPHA),
                        shape = tokens.shapes.compactCard,
                    )
                } else {
                    Modifier
                },
            )
            .hoverable(interaction, enabled = enabled)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s10),
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = tokens.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        FeatureChips(
            dynamicRange = figures.dynamicRange,
            audio = figures.audio,
            isAiUpscaled = figures.isAiUpscaled,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
        ) {
            Text(
                text = figures.size,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = if (figures.isSizeKnown) tokens.colors.textSecondary else tokens.colors.textMuted,
                maxLines = 1,
            )
            Text(
                text = FIGURE_SEPARATOR,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
            Text(
                text = figures.needs,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = needsColor(figures),
                maxLines = 1,
            )
        }
        // ⚠ Reserved, but blank rather than dashed when the source named neither a rip type nor
        // a host. The height has to stay so a band lines up with the same band in the column
        // beside it - reading across is the comparison this layout exists for - but an em-dash
        // as the last line of a cell reads as a broken field, and this is the least important
        // line in the cell. The dash is for a fact that was asked for and missing; nothing here
        // asked.
        Box(
            modifier = Modifier.fillMaxWidth().height(PROVENANCE_ROW_HEIGHT),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (figures.provenance.isNotBlank()) {
                Text(
                    text = figures.provenance,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (figures.isTheatricalCapture) tokens.colors.danger else tokens.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Amber when the line will not carry it, muted when nothing said, otherwise plain.
 *
 * ⚠ `warning`, not `danger`, because [ConnectionMeter] fills in `warning` past the estimate
 * marker and the footer legend names that colour. Three ways of saying "this is the expensive
 * one" have to be one colour or none of them mean anything.
 */
@Composable
private fun needsColor(figures: QualityFigures): Color {
    val tokens = MaterialTheme.nuvio
    return when {
        !figures.isNeedsKnown -> tokens.colors.textMuted
        figures.fit?.isOverConnection == true -> tokens.colors.warning
        else -> tokens.colors.textPrimary
    }
}

/**
 * The same footprint as the real thing, so nothing jumps when the figures arrive.
 *
 * That is the entire purpose of a skeleton; a decorative one is worse than none.
 */
@Composable
private fun QualityColumnsSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s16)) {
        NuvioSkeletonBlock(
            modifier = Modifier.fillMaxWidth(),
            height = HERO_SKELETON_HEIGHT,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP)) {
            repeat(SKELETON_COLUMN_COUNT) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(CELL_GAP),
                ) {
                    NuvioSkeletonBlock(modifier = Modifier.width(64.dp), height = 14.dp)
                    repeat(SKELETON_CELL_COUNT) {
                        NuvioSkeletonBlock(
                            modifier = Modifier.fillMaxWidth(),
                            height = CELL_SKELETON_HEIGHT,
                        )
                    }
                }
            }
        }
    }
}
