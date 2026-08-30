package com.nuvio.app.features.playback

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import nuvio.composeapp.generated.resources.playback_quality_checking_connection
import nuvio.composeapp.generated.resources.playback_quality_description
import nuvio.composeapp.generated.resources.playback_quality_estimated_connection
import nuvio.composeapp.generated.resources.playback_quality_last_measured
import nuvio.composeapp.generated.resources.playback_quality_loading
import nuvio.composeapp.generated.resources.playback_quality_manual
import nuvio.composeapp.generated.resources.playback_quality_needs
import nuvio.composeapp.generated.resources.playback_quality_needs_estimated
import nuvio.composeapp.generated.resources.playback_quality_no_match
import nuvio.composeapp.generated.resources.playback_quality_preferences
import nuvio.composeapp.generated.resources.playback_quality_over_connection
import nuvio.composeapp.generated.resources.playback_quality_summary_with_size
import nuvio.composeapp.generated.resources.playback_quality_title
import nuvio.composeapp.generated.resources.playback_quality_variant_high
import nuvio.composeapp.generated.resources.playback_quality_variant_low
import nuvio.composeapp.generated.resources.playback_quality_variant_max
import nuvio.composeapp.generated.resources.playback_quality_variant_mid
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
                        estimatedMbps = shownMbps,
                        isConnectionMeasured = shownMeasured,
                        isConnectionStale = isConnectionStale,
                        isMeasuringConnection = isMeasuringConnection,
                        onRetestConnection = onRetestConnection,
                        gridMaxHeight = gridMaxHeight,
                        contentBottomPadding = tokens.spacing.dialogPadding,
                        onOptionSelected = onOptionSelected,
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
                color = if (isOnlyContent) tokens.colors.textPrimary else tokens.colors.textMuted,
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
private fun variantLabel(option: PlaybackQualityOption): String = when (option.variant) {
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
private const val SKELETON_PULSE_MILLIS = 900
private const val SKELETON_MIN_ALPHA = 0.4f
private const val SKELETON_MAX_ALPHA = 1f

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
