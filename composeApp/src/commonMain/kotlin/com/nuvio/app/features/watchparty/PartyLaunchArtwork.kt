package com.nuvio.app.features.watchparty

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The display metadata a party launch needs and the party wire does not carry.
 *
 * `PartyContent` is a **sanitized identity**, not a presentation model. The backend whitelist
 * (`sanitize_party_content`) admits the content id, type, video id, title, poster, season, episode
 * and episode title - enough to say unambiguously what is being watched, and nothing about how to
 * draw it. So a party launch reached `PlaybackLoadingScreen` with no logo, no background and no
 * episode thumbnail, and that screen did the right thing with what it was given: it fell back to
 * plain title text. Ordinary playback carries all three and shows the centred logo.
 *
 * ⚠ **The fix is not to widen the wire.** Artwork URLs are presentation that every client can
 * derive for itself from metadata it already caches, and putting them through the party payload
 * would mean sanitizing, storing and versioning them for no gain. Each client hydrates locally
 * instead, from the same `MetaDetailsRepository` ordinary playback reads, so host and guest each
 * get the artwork *their* addons actually resolve.
 */
data class PartyLaunchArtwork(
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val episodeThumbnail: String? = null,
)

/**
 * Picks a party launch's artwork out of already-fetched metadata.
 *
 * Pure, so the selection rules are testable without a repository or a network.
 *
 * The party's own poster wins over the meta's: it is what the host was looking at when they
 * started the party, and the two can legitimately differ when the clients have different addons.
 * Everything else comes from the meta, because the party never carried it.
 *
 * The episode thumbnail is found by `video_id` first - that is the identity the party actually
 * agreed on - and only then by season/episode number, which is the same content addressed the way
 * a differently-configured addon may have numbered it.
 */
fun partyLaunchArtwork(content: PartyContent, meta: MetaDetails?): PartyLaunchArtwork {
    if (meta == null) return PartyLaunchArtwork(poster = content.poster)
    val episode = meta.videos.firstOrNull { it.id == content.videoId }
        ?: content.season?.let { season ->
            content.episode?.let { episode ->
                meta.videos.firstOrNull { it.season == season && it.episode == episode }
            }
        }
    return PartyLaunchArtwork(
        logo = meta.logo,
        poster = content.poster ?: meta.poster,
        background = meta.background,
        episodeThumbnail = episode?.thumbnail,
    )
}

/**
 * How long a party launch will wait for metadata it does not strictly need.
 *
 * The launch is a button press, and the artwork is decoration on the screen that comes next. A
 * cache hit answers in microseconds; this bounds the miss so a slow addon delays a party launch by
 * a moment rather than holding the host - and every guest waiting on them - behind a metadata
 * fetch. Running out is not a failure: [PartyLaunchArtwork] simply carries less, and
 * `PlaybackLoadingScreen`'s fall back to title text is correct when the artwork genuinely is not
 * there.
 */
private const val PartyArtworkHydrationTimeoutMs = 2_500L

/**
 * Reads this party's content artwork from cache, fetching only on a miss.
 *
 * `peek` is the common case by a wide margin - the party was almost always started from a details
 * screen, which is what filled that cache - so this usually costs nothing at all.
 */
suspend fun hydratePartyLaunchArtwork(content: PartyContent): PartyLaunchArtwork {
    MetaDetailsRepository.peek(type = content.contentType, id = content.contentId)
        ?.let { return partyLaunchArtwork(content, it) }
    val fetched = withTimeoutOrNull(PartyArtworkHydrationTimeoutMs) {
        MetaDetailsRepository.fetch(type = content.contentType, id = content.contentId)
    }
    return partyLaunchArtwork(content, fetched)
}
