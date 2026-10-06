package com.nuvio.app.features.social

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Landscape artwork for titles that Social knows only by id, poster and episode.
 *
 * ⚠ **Friend activity carries no backdrop.** `social_activity_events.background` and
 * `episode_thumbnail` exist, but the publisher only ever had a `WatchedItem`, which has a poster and
 * nothing wider, so in production every one of the 326 events read on 2026-10-06 had a poster and
 * no backdrop. Every 16:9 activity still was therefore a portrait poster cropped to a strip.
 *
 * The fix follows `PartyLaunchArtwork`: artwork is presentation a client can derive for itself from
 * metadata it already caches, so each client hydrates locally from [MetaDetailsRepository] rather
 * than widening the wire. That also covers every event already stored, with no backfill.
 *
 * Process-lifetime and title-keyed, like the metadata cache under it: a title's artwork is not
 * anybody's personal data, so it has no identity boundary to keep.
 */
data class SocialTitleArt(
    val background: String? = null,
    /** Episode stills by video id, and by `season:episode` for addons that number differently. */
    val thumbnailsByVideoId: Map<String, String> = emptyMap(),
    val thumbnailsBySeasonEpisode: Map<String, String> = emptyMap(),
) {
    fun episodeThumbnail(videoId: String?, season: Int?, episode: Int?): String? =
        videoId?.let(thumbnailsByVideoId::get)
            ?: if (season != null && episode != null) thumbnailsBySeasonEpisode["$season:$episode"] else null

    companion object {
        val None = SocialTitleArt()
    }
}

/** The landscape art in [meta]. Pure, so the selection is testable without a repository. */
fun socialTitleArt(meta: MetaDetails?): SocialTitleArt {
    if (meta == null) return SocialTitleArt.None
    val withStills = meta.videos.filter { !it.thumbnail.isNullOrBlank() }
    return SocialTitleArt(
        background = meta.background?.takeIf(String::isNotBlank),
        thumbnailsByVideoId = withStills.associate { it.id to it.thumbnail!! },
        thumbnailsBySeasonEpisode = withStills
            .filter { it.season != null && it.episode != null }
            .associate { "${it.season}:${it.episode}" to it.thumbnail!! },
    )
}

/** Where a composable gets title art from. Render harnesses replace it; the app uses [SocialTitleArtStore]. */
interface SocialTitleArtSource {
    /** Resolved art by `type:id`. A key that is absent is still being looked up. */
    val art: StateFlow<Map<String, SocialTitleArt>>
    fun request(contentType: String, contentId: String)
}

internal fun socialTitleArtKey(contentType: String, contentId: String) = "${contentType.lowercase()}:$contentId"

object SocialTitleArtStore : SocialTitleArtSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val resolved = MutableStateFlow<Map<String, SocialTitleArt>>(emptyMap())
    private val requestedLock = SynchronizedObject()
    private val requested = mutableSetOf<String>()
    private val lookups = Semaphore(SocialTitleArtConcurrency)

    override val art: StateFlow<Map<String, SocialTitleArt>> = resolved.asStateFlow()

    override fun request(contentType: String, contentId: String) {
        val key = socialTitleArtKey(contentType, contentId)
        synchronized(requestedLock) { if (!requested.add(key)) return }
        scope.launch {
            val art = lookups.withPermit {
                MetaDetailsRepository.peek(contentType, contentId)?.let(::socialTitleArt)
                    ?: try {
                        withTimeoutOrNull(SocialTitleArtTimeoutMs) {
                            MetaDetailsRepository.fetch(type = contentType, id = contentId)
                        }.let(::socialTitleArt)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        SocialTitleArt.None
                    }
            }
            resolved.update { it + (key to art) }
        }
    }
}

/**
 * Bounded so a feed of thirty titles cannot fire thirty addon requests at once, and short enough that
 * a missing title settles on its poster instead of sitting on a placeholder.
 */
private const val SocialTitleArtConcurrency = 3
private const val SocialTitleArtTimeoutMs = 6_000L

val LocalSocialTitleArtSource = staticCompositionLocalOf<SocialTitleArtSource> { SocialTitleArtStore }

/**
 * The artwork candidates for a title, best first: episode still, backdrop, then whatever the caller
 * already had, with the poster last.
 *
 * ⚠ **While the lookup is pending this returns no candidates at all**, so the still shows its
 * placeholder rather than the cropped poster. Otherwise the poster would be drawn first and then
 * replaced by the backdrop a moment later, which is the image changing under the viewer. A lookup
 * that fails settles on the poster.
 *
 * When the caller already holds landscape art (a presence row, a recommendation payload), there is
 * nothing to wait for and the lookup is skipped.
 */
@Composable
internal fun rememberSocialTitleArtwork(
    contentType: String,
    contentId: String,
    videoId: String?,
    season: Int?,
    episode: Int?,
    landscape: List<String?>,
    poster: String?,
): List<String?> {
    val known = landscape.filterNot { it.isNullOrBlank() }
    if (known.isNotEmpty() || contentId.isBlank()) return known + poster
    val source = LocalSocialTitleArtSource.current
    val key = remember(contentType, contentId) { socialTitleArtKey(contentType, contentId) }
    LaunchedEffect(source, key) { source.request(contentType, contentId) }
    val all by source.art.collectAsState()
    val art = all[key] ?: return emptyList()
    return listOf(art.episodeThumbnail(videoId, season, episode), art.background, poster)
}

/** A title to draw, by identity, with whatever art its row already carried. */
internal data class SocialArtRef(
    val title: String,
    val contentType: String?,
    val contentId: String?,
    val poster: String?,
    val landscape: List<String?> = emptyList(),
    val videoId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

@Composable
internal fun SocialArtRef.artwork(): List<String?> =
    if (contentType == null || contentId == null) {
        landscape + poster
    } else {
        rememberSocialTitleArtwork(contentType, contentId, videoId, season, episode, landscape, poster)
    }

@Composable
internal fun FriendActivityGroup.artwork(): List<String?> = rememberSocialTitleArtwork(
    contentType = contentType,
    contentId = contentId,
    videoId = latestRun.videoId,
    season = latestRun.season,
    episode = latestRun.episode,
    landscape = listOf(latestRun.episodeThumbnail, background),
    poster = poster,
)

internal fun com.nuvio.app.features.watchparty.PartyContent.artRef() =
    SocialArtRef(title, contentType, contentId, poster, videoId = videoId, season = season, episode = episode)
