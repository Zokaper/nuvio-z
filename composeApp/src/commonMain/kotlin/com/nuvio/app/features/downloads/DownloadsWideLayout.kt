package com.nuvio.app.features.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioDesktopVerticalScrollbar
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioPlatformExtraTopPadding

/**
 * Which sections of the Downloads screen a list shows. A phone, a narrow window and a show's own
 * page show [All] in one column; a wide desktop window splits the same sections, in the same order,
 * over two panes - what needs doing ([Main]) and what is on the device ([Rail]).
 */
enum class DownloadsPart {
    All,

    /** Needs you, then the downloads under way. */
    Main,

    /** Storage, the watched-cleanup suggestion, then On this device. */
    Rail,
    ;

    val showsMain: Boolean get() = this != Rail
    val showsRail: Boolean get() = this != Main
}

/**
 * The desktop Downloads destination's composition (Phase 9). The single 880dp column left ~290dp of
 * nothing on each side of a 1920 window, while stretching it would bring back rows whose actions
 * sit a screen-width from their title.
 *
 * - **Something under way** (queue, Needs you, a batch finding sources): two panes. The library
 *   pane takes a share of the width ([DownloadsWideRailShare], between [DownloadsWideRailMinWidth]
 *   and [DownloadsWideRailMaxWidth]) rather than a fixed strip, so its backdrop cards read as a
 *   library; the activity pane keeps the rest up to [DownloadsWideMainMaxWidth].
 * - **Nothing under way**: no split at all - an empty activity pane beside a squeezed library was
 *   the awkward part. One column up to [DownloadsLibraryMaxWidth] with the library as a grid
 *   ([downloadsLibraryColumns]); the screen decides which (`DownloadsScreen`).
 */
internal val DownloadsWideMinWidth: Dp = 1000.dp
internal val DownloadsWideMainMaxWidth: Dp = 900.dp
internal val DownloadsWideRailMinWidth: Dp = 360.dp
internal val DownloadsWideRailMaxWidth: Dp = 520.dp
private const val DownloadsWideRailShare = 0.38f
internal val DownloadsWideGutter: Dp = 32.dp
private val DownloadsWidePaneGap: Dp = 40.dp

/** The idle desktop column: wide enough for three or four library cards, never edge to edge. */
internal val DownloadsLibraryMaxWidth: Dp = 1400.dp
private val LibraryCardMinWidth: Dp = 360.dp
private val LibraryCardGap: Dp = 12.dp

/** Cards to a row in a column [width] wide: as many as stay at least 360dp, one to four. */
internal fun downloadsLibraryColumns(width: Dp): Int =
    ((width + LibraryCardGap) / (LibraryCardMinWidth + LibraryCardGap)).toInt().coerceIn(1, 4)

/** True when [availableWidth] fits both panes; below it the screen is one column. */
internal fun downloadsUsesWideLayout(availableWidth: Dp): Boolean = availableWidth >= DownloadsWideMinWidth

/** The library pane's width in a window whose content is [innerWidth] wide. */
internal fun downloadsRailWidth(innerWidth: Dp): Dp =
    (innerWidth * DownloadsWideRailShare).coerceIn(DownloadsWideRailMinWidth, DownloadsWideRailMaxWidth)

@Composable
internal fun DownloadsWideLayout(
    header: @Composable (Modifier) -> Unit,
    content: LazyListScope.(DownloadsPart) -> Unit,
    modifier: Modifier = Modifier,
    mainListState: LazyListState = rememberLazyListState(),
) {
    val tokens = MaterialTheme.nuvio
    val railListState = rememberLazyListState()
    // The top margin every other tab gets from NuvioScreen's content padding; without it the title
    // sat flush against the top of the window.
    val topPadding = tokens.spacing.screenTop +
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
        nuvioPlatformExtraTopPadding
    val maxTotal = DownloadsWideMainMaxWidth + DownloadsWidePaneGap + DownloadsWideRailMaxWidth + DownloadsWideGutter * 2
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().background(tokens.colors.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        val railWidth = downloadsRailWidth(minOf(maxWidth, maxTotal) - DownloadsWideGutter * 2 - DownloadsWidePaneGap)
        Column(
            modifier = Modifier
                .widthIn(max = maxTotal)
                .fillMaxSize()
                .padding(start = DownloadsWideGutter, end = DownloadsWideGutter, top = topPadding),
        ) {
            header(Modifier.fillMaxWidth())
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(DownloadsWidePaneGap),
            ) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    LazyColumn(
                        state = mainListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = tokens.spacing.screenBottom),
                        verticalArrangement = Arrangement.spacedBy(tokens.spacing.listGap),
                    ) { content(DownloadsPart.Main) }
                    NuvioDesktopVerticalScrollbar(
                        state = mainListState,
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
                }
                LazyColumn(
                    state = railListState,
                    modifier = Modifier.width(railWidth).fillMaxHeight(),
                    contentPadding = PaddingValues(bottom = tokens.spacing.screenBottom),
                    verticalArrangement = Arrangement.spacedBy(tokens.spacing.listGap),
                ) { content(DownloadsPart.Rail) }
            }
        }
    }
}
