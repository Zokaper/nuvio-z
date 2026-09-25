package com.nuvio.app.features.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.nuvio

/**
 * The Downloads surfaces' shared visual pieces (Phase 9 stage 7 composition pass): the title's
 * artwork, so every row leads with what it is rather than with a status, and the content width,
 * so a desktop window does not stretch a row across 1,900 pixels with its actions at the far end.
 */

/** Past this the Downloads screen and Choose sources stop growing and centre instead. */
val DownloadsContentMaxWidth: Dp = 880.dp

/** Fills the width up to [max], centred beyond it. Every Downloads row and header uses it. */
fun Modifier.downloadsContentWidth(max: Dp = DownloadsContentMaxWidth): Modifier =
    fillMaxWidth()
        .wrapContentWidth(Alignment.CenterHorizontally)
        .widthIn(max = max)
        .fillMaxWidth()

/**
 * A title's poster at 2:3, [width] wide. Without artwork it shows the title's initial on a quiet
 * tile rather than an empty box.
 */
@Composable
fun DownloadPoster(
    url: String?,
    title: String,
    width: Dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 6.dp,
) {
    DownloadArtworkBox(url, title, modifier.width(width).aspectRatio(2f / 3f), cornerRadius)
}

/** Landscape artwork at 16:9 - an episode still, or the title's backdrop. */
@Composable
fun DownloadStill(
    url: String?,
    title: String,
    width: Dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 6.dp,
) {
    DownloadArtworkBox(url, title, modifier.width(width).aspectRatio(16f / 9f), cornerRadius)
}

@Composable
private fun DownloadArtworkBox(url: String?, title: String, modifier: Modifier, cornerRadius: Dp) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(tokens.colors.surfaceElevated),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNullOrBlank()) {
            Text(
                text = title.trim().firstOrNull()?.uppercase() ?: "",
                style = MaterialTheme.typography.titleMedium,
                color = tokens.colors.textMuted,
                fontWeight = FontWeight.SemiBold,
            )
        } else {
            AsyncImage(
                model = url,
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/** A title's poster, else its backdrop. */
internal val DownloadItem.posterArt: String? get() = poster ?: background

/** An episode's still, else the title's backdrop, else its poster. */
internal val DownloadItem.stillArt: String? get() = episodeThumbnail ?: background ?: poster
