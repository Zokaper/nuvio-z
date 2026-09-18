package com.nuvio.app.features.details.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier

@Composable
actual fun HeroTrailerPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    playWhenReady: Boolean,
    muted: Boolean,
    // Accepted to satisfy the shared contract. This flavour has no trailer playback at all --
    // it reports failure immediately -- so there is no position to seek to and no frame to fill.
    startPositionMillis: Long,
    fillFrame: Boolean,
    modifier: Modifier,
    onReady: () -> Unit,
    onEnded: () -> Unit,
    onError: () -> Unit,
) {
    LaunchedEffect(sourceUrl) {
        onError()
    }
}
