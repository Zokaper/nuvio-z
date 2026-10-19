package com.nuvio.app.features.social

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import nuvio.composeapp.generated.resources.social_watching_empty
import nuvio.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource

/**
 * Watching Now's sizes and labels. The V2 cards themselves are in `SocialV2Components.kt`; the V1
 * card that lived here was removed on 2026-10-18 once nothing drew it.
 */

/** Home's Watching Now shelf card. The Social feed's own cards are sized by [SocialFeedMetrics]. */
internal val SocialWatchingNowCardWidth = 344.dp

/**
 * A minimum rather than a fixed height: it evens a row of cards out and still grows rather than
 * clipping if a translation runs long. Also the number that keeps Watching Now visibly under a
 * Continue Watching card.
 */
internal val SocialWatchingNowCardHeight = 132.dp

/** Artwork widths. Home's shelf is compact; the Social feed passes the wide one on multi-column. */
internal val SocialWatchingNowArtworkWidth = 116.dp
internal val SocialWatchingNowArtworkWidthWide = 160.dp

/** The phone card's still: 96 x 54, beside the text rather than above it. */
internal val SocialWatchingNowArtworkWidthCompact = 96.dp

/** Below this card width the artwork drops above the text instead of beside it (§2). */
internal val SocialWatchingNowStackedBelow = 340.dp

/** The friends roster beside the feed on a wide window, so the feed's own width excludes it. */
internal val SocialFriendsRailWidth = 360.dp

/**
 * "Ana", "Ana & Ben", "Ana, Ben & Cy", or "Ana & 3 others" - friends on the card first, then anyone
 * else in the party this viewer cannot see.
 */
internal fun watchingNowPeopleLabel(item: WatchingNowItem): String {
    val names = (listOf(item.profile) + item.partyCompanions).map { it.displayName.ifBlank { it.handle } }
    val total = maxOf(names.size, item.partyMemberCount?.takeIf { item.partyId != null } ?: 0)
    return when {
        total <= 1 -> names.first()
        total == names.size && total == 2 -> "${names[0]} & ${names[1]}"
        total == names.size && total == 3 -> "${names[0]}, ${names[1]} & ${names[2]}"
        total == 2 -> "${names[0]} & 1 other"
        else -> "${names[0]} & ${total - 1} others"
    }
}

/**
 * An empty Watching Now: one muted line with an icon, no bordered box. The old copy promised "start a
 * party on it", which was never the action on offer.
 */
@Composable
internal fun WatchingNowEmptyLine(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.Tv, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            stringResource(Res.string.social_watching_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
