package com.nuvio.app.features.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
 * The desktop Downloads destination's composition (Phase 9, 2026-09-26). The single 880dp column
 * left ~290dp of nothing on each side of a 1920 window, while stretching it would bring back rows
 * whose actions sit a screen-width from their title. Two panes use the width without stretching a
 * row: the main pane stops at [DownloadsWideMainMaxWidth], the rail is [DownloadsWideRailWidth], and
 * the pair centres once the window is wider than both.
 */
internal val DownloadsWideMinWidth: Dp = 1000.dp
internal val DownloadsWideMainMaxWidth: Dp = 860.dp
internal val DownloadsWideRailWidth: Dp = 340.dp
private val DownloadsWideGutter: Dp = 32.dp
private val DownloadsWidePaneGap: Dp = 40.dp

/** True when [availableWidth] fits both panes; below it the screen is one column. */
internal fun downloadsUsesWideLayout(availableWidth: Dp): Boolean = availableWidth >= DownloadsWideMinWidth

@Composable
internal fun DownloadsWideLayout(
    header: @Composable (Modifier) -> Unit,
    content: LazyListScope.(DownloadsPart) -> Unit,
    modifier: Modifier = Modifier,
    mainListState: LazyListState = rememberLazyListState(),
) {
    val tokens = MaterialTheme.nuvio
    val railListState = rememberLazyListState()
    Box(
        modifier = modifier.fillMaxSize().background(tokens.colors.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = DownloadsWideMainMaxWidth + DownloadsWidePaneGap + DownloadsWideRailWidth + DownloadsWideGutter * 2)
                .fillMaxSize()
                .padding(horizontal = DownloadsWideGutter),
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
                    modifier = Modifier.width(DownloadsWideRailWidth).fillMaxHeight(),
                    contentPadding = PaddingValues(bottom = tokens.spacing.screenBottom),
                    verticalArrangement = Arrangement.spacedBy(tokens.spacing.listGap),
                ) { content(DownloadsPart.Rail) }
            }
        }
    }
}
