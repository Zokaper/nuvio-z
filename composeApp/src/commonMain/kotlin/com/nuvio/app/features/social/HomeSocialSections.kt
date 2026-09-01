package com.nuvio.app.features.social

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.social_recently_watched
import nuvio.composeapp.generated.resources.social_watching_now
import org.jetbrains.compose.resources.stringResource

fun LazyListScope.homeSocialSections(
    watchingNow: List<WatchingNowItem>,
    activity: List<RecentActivityRun>,
    sectionPadding: Dp,
    onOpenContent: (contentType: String, contentId: String, title: String) -> Unit,
) {
    if (watchingNow.isNotEmpty()) {
        item(key = "z-social-watching-now") {
            SocialHomeRow(
                title = stringResource(Res.string.social_watching_now),
                sectionPadding = sectionPadding,
                items = watchingNow.take(SocialHomeItemLimit),
                key = { "${it.profile.profileId}:${it.videoId}" },
            ) { item ->
                SocialHomeCard(
                    title = item.title,
                    subtitle = "${item.profile.displayName} · ${item.roundedProgressPercent}%",
                    progress = item.progressFraction,
                    onClick = { onOpenContent(item.contentType, item.contentId, item.title) },
                )
            }
        }
    }
    if (activity.isNotEmpty()) {
        item(key = "z-social-recent") {
            SocialHomeRow(
                title = stringResource(Res.string.social_recently_watched),
                sectionPadding = sectionPadding,
                items = activity.take(SocialHomeItemLimit),
                key = RecentActivityRun::runId,
            ) { run ->
                SocialHomeCard(
                    title = run.title,
                    subtitle = "${run.profile.displayName}${if (run.eventCount > 1) " · ${run.eventCount} episodes" else ""}",
                    onClick = { onOpenContent(run.contentType, run.contentId, run.title) },
                )
            }
        }
    }
}

@Composable
private fun <T> SocialHomeRow(
    title: String,
    sectionPadding: Dp,
    items: List<T>,
    key: (T) -> Any,
    card: @Composable (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            modifier = Modifier.padding(horizontal = sectionPadding),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = sectionPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) { items(items, key = key) { card(it) } }
    }
}

@Composable
private fun SocialHomeCard(title: String, subtitle: String, progress: Float? = null, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.width(210.dp).clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
        }
    }
}
