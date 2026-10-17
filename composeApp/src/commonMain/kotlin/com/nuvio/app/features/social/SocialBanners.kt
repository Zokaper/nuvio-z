package com.nuvio.app.features.social

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.watchparty.currentEpochMs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The phone's Social pop-ups: the desktop's corner cards, smaller, as in-app banners across the top of the
 * screen (2026-10-17). iOS has no system notifications for these at all, and Android's are not built yet,
 * so this is the only place they appear while the app is open.
 *
 * Same events and the same once-per-device rule as the desktop ([SocialPopupLedger]). Fed only while the
 * app is in the foreground, so a banner is never spent on a screen nobody is looking at; on returning,
 * anything newer than [FreshnessMs] still pops. Not over the player, which has its own social card - what
 * arrives there is marked seen without a banner, so leaving the player does not replay it.
 *
 * A tap opens the Social tab on the relevant place and dismisses; a swipe up dismisses. No buttons.
 */
internal object SocialBanners {
    private val ledger = SocialPopupLedger(SocialStorage::loadPopupSeen, SocialStorage::savePopupSeen, FreshnessMs)

    private val _visible = MutableStateFlow<List<SocialPopup>>(emptyList())
    val visible: StateFlow<List<SocialPopup>> = _visible.asStateFlow()

    fun onState(state: SocialUiState, enabled: Boolean, playerOpen: Boolean) {
        if (!enabled) {
            _visible.value = emptyList()
            return
        }
        if (playerOpen) _visible.value = emptyList()
        val fresh = ledger.take(state, currentEpochMs())
        if (fresh.isEmpty() || playerOpen) return
        _visible.value = (_visible.value.filterNot { old -> fresh.any { it.key == old.key } } + fresh).takeLast(MaxVisible)
    }

    fun dismiss(key: String) {
        _visible.value = _visible.value.filterNot { it.key == key }
    }

    fun onClick(popup: SocialPopup) {
        dismiss(popup.key)
        SocialNavigation.open(popup.target)
    }

    internal const val VisibleMs = 6_000L
    private const val FreshnessMs = 30L * 60L * 1000L
    private const val MaxVisible = 2
}

/** The banners, newest at the bottom, under the status bar. Drawn by `MainAppContent` above everything. */
@Composable
internal fun SocialBannerHost(playerOpen: Boolean, modifier: Modifier = Modifier) {
    val enabled = rememberSocialEnabled()
    // Lifecycle-aware: nothing is taken from the ledger while the app is in the background.
    val state by SocialRepository.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state, enabled, playerOpen) { SocialBanners.onState(state, enabled, playerOpen) }
    val banners by SocialBanners.visible.collectAsStateWithLifecycle()
    if (banners.isEmpty()) return
    Column(
        modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .widthIn(max = 440.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        banners.forEach { popup -> key(popup.key) { SocialBanner(popup) } }
    }
}

@Composable
private fun SocialBanner(popup: SocialPopup) {
    LaunchedEffect(popup.key) {
        delay(SocialBanners.VisibleMs)
        SocialBanners.dismiss(popup.key)
    }
    val appear = remember { MutableTransitionState(false).apply { targetState = true } }
    var dragY by remember { mutableFloatStateOf(0f) }
    AnimatedVisibility(appear, enter = slideInVertically { -it } + fadeIn()) {
        SocialPopupCard(
            popup,
            Modifier
                .graphicsLayer { translationY = dragY.coerceAtMost(0f) }
                .shadow(12.dp, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                .pointerInput(popup.key) {
                    detectVerticalDragGestures(
                        onDragEnd = { if (dragY < -24.dp.toPx()) SocialBanners.dismiss(popup.key) else dragY = 0f },
                        onDragCancel = { dragY = 0f },
                    ) { _, dy -> dragY += dy }
                }
                .clickable { SocialBanners.onClick(popup) },
            compact = true,
        )
    }
}
