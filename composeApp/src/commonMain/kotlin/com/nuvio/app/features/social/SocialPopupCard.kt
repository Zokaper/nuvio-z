package com.nuvio.app.features.social

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The pop-up itself: a backdrop card in the Watching Now family (maintainer's pick, 2026-10-16, "B").
 *
 * No buttons. The whole card is the target and opens Nuvio on the relevant place; a pop-up you can act on
 * from the corner of the screen was rendered (C, D1-D3) and turned down in favour of one tap that takes you
 * there. The desktop draws it full size in its pop-up window and the close prompt's picture; a phone draws
 * the [compact] one as an in-app banner across the top of the screen (2026-10-17, "smaller").
 */
@Composable
internal fun SocialPopupCard(
    popup: SocialPopup,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    /** Buttons at the card's end; the text then takes the room between the face and them. */
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val shape = if (compact) SocialBannerShape else SocialPopupShape
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(
            (if (compact) modifier.fillMaxWidth().height(SocialBannerHeight) else modifier.width(SocialPopupWidth).height(SocialPopupHeight))
                .clip(shape)
                .background(Color(0xFF17181C))
                .border(1.dp, Color.White.copy(alpha = 0.08f), shape),
        ) {
            // A friend request has no title: the art seeds on the person, so it is still a picture.
            SocialArtwork(popup.art.artwork(), seed = popup.title.ifBlank { popup.person.profileId }, modifier = Modifier.matchParentSize())
            Box(
                Modifier.matchParentSize().background(
                    Brush.horizontalGradient(
                        listOf(Color.Black.copy(alpha = 0.92f), Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.05f)),
                    ),
                ),
            )
            Row(
                Modifier.align(Alignment.CenterStart)
                    .then(if (trailing != null) Modifier.fillMaxWidth() else Modifier)
                    .padding(if (compact) 12.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 12.dp),
            ) {
                SocialFaces(listOf(popup.person), if (compact) 38.dp else 46.dp, liveRing = popup.kind == SocialPopupKind.FriendWatching)
                Column(if (trailing != null) Modifier.weight(1f) else Modifier.widthIn(max = 250.dp)) {
                    // The name gives way, never the verb: "A Friend With A Ve... wants to be friends".
                    Row {
                        Text(
                            popup.person.displayName,
                            Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            " ${popup.line}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    // Compact: the title and its meta share one line ("Andor · S1 E2").
                    Text(
                        if (compact) listOfNotNull(popup.title, popup.meta).joinToString(" · ") else popup.title,
                        style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!compact) {
                        popup.meta?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                trailing?.invoke(this)
            }
            if (!compact) {
                Text(
                    "NUVIO",
                    Modifier.align(Alignment.TopEnd).padding(10.dp),
                    fontSize = 9.sp,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.55f),
                )
            }
        }
    }
}

internal val SocialPopupWidth = 380.dp
internal val SocialPopupHeight = 132.dp
private val SocialPopupShape = RoundedCornerShape(16.dp)
internal val SocialBannerHeight = 72.dp
private val SocialBannerShape = RoundedCornerShape(14.dp)
