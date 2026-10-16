package com.nuvio.app.features.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import com.nuvio.app.isDesktop

/**
 * Home's row list. On desktop it keeps rows composed for several screens behind the viewport and
 * composes one screen ahead between frames, so scrolling back and forth over Home stops rebuilding
 * every row and its posters each pass. The ahead part is composed by DesktopPrefetchScheduler in
 * idle time; rows kept behind are composed and measured but not drawn.
 *
 * Mobile keeps the stock state: Android already prefetches, and memory there is tighter.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun rememberHomeListState(): LazyListState =
    if (isDesktop) {
        rememberLazyListState(cacheWindow = DesktopHomeCacheWindow)
    } else {
        rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    }

@OptIn(ExperimentalFoundationApi::class)
private val DesktopHomeCacheWindow = LazyLayoutCacheWindow(aheadFraction = 1f, behindFraction = 6f)
