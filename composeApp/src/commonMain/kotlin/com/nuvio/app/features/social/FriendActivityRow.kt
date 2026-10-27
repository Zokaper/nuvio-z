package com.nuvio.app.features.social

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The friends'-activity sizes the feed metrics still compute with, and [SocialAvatarStack]. The V1
 * mini-poster row that lived here was removed on 2026-10-18 once nothing drew it (V2 draws activity
 * as 16:9 stills, never mini portrait posters).
 */
internal val FriendActivityPosterWidth = 44.dp
internal val FriendActivityRowHeight = 66.dp

/** Home shelf row widths by window class (§6). */
internal fun friendActivityRowWidth(windowWidth: Dp): Dp = when {
    windowWidth < 720.dp -> 260.dp
    windowWidth >= 1440.dp -> 300.dp
    else -> 280.dp
}

/** Up to three overlapping avatars, then "+N". */
@Composable
internal fun SocialAvatarStack(
    friends: List<SocialProfileSummary>,
    overflow: Int,
    size: Dp,
) {
    // Negative spacing rather than offsets, so the stack measures to the width it actually draws and
    // the names beside it start where the last avatar ends.
    val overlap = size * 0.35f
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(-overlap)) {
        friends.forEach { friend ->
            Box(
                Modifier.clip(CircleShape)
                    .background(MaterialTheme.colorScheme.background)
                    .padding(1.5.dp),
            ) {
                SocialAvatar(friend.displayName, friend.avatarUrl, friend.avatarColorHex, size)
            }
        }
        if (overflow > 0) {
            Text(
                "+$overflow",
                modifier = Modifier.padding(start = overlap + 3.dp),
                fontSize = (size.value * 0.6f).sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
