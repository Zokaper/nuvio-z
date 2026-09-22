package com.nuvio.app.features.player

import com.nuvio.app.features.playback.PlaybackMode

/**
 * "Prefer built-in subtitles", the half that runs **after** mpv has opened the file.
 *
 * ## Why after, and only after
 *
 * The obvious way to learn whether a source carries subtitles is to look inside it before
 * choosing - a HEAD, a range request, an ffprobe. That is exactly what broke AIOStreams: a debrid
 * link is minted for, and bound to, the first IP that opens it, and anything other than mpv opening
 * it first produced "Wrong IP". So the ranking half (`SourceRanking.embeddedSubtitleScore`) may only
 * read release names, which are hints, and this half reads mpv's own `track-list` once the file is
 * open, which is the truth.
 *
 * ## What a failed verification does
 *
 * **Nothing dramatic, deliberately.** The file is already open and usually already playing; trying
 * another source now would restart startup, spend a debrid mint, and could loop between two releases
 * that both claim subtitles they lack. So a miss logs what was found and hands the decision back to
 * the ordinary subtitle auto-selection - addon and sidecar subtitles work exactly as they did.
 *
 * Pure so it can be unit-tested; the runtime calls it from `tryAutoSelectPreferredSubtitleFromAvailableTracks`.
 */
internal enum class EmbeddedSubtitleOutcome {
    /** A non-forced track inside the container matches the preferred subtitle language. */
    CONFIRMED,

    /** The container has subtitle tracks, none in the preferred language. */
    OTHER_LANGUAGES_ONLY,

    /** The container has no subtitle tracks of its own. */
    NO_EMBEDDED_TRACKS,

    /** The engine does not say which tracks are inside the file, so nothing can be confirmed. */
    ORIGIN_UNKNOWN,
}

internal data class EmbeddedSubtitleVerification(
    val outcome: EmbeddedSubtitleOutcome,
    /** Index into the track list of the track to select, or -1 unless [outcome] is CONFIRMED. */
    val trackIndex: Int = -1,
    /** Languages the container's own subtitle tracks are tagged with, for the log. */
    val embeddedLanguages: List<String> = emptyList(),
)

/**
 * Whether the preference applies to the source now playing.
 *
 * Only Streamlined and Instant, only a source they chose, and never once the user has chosen a
 * subtitle themselves. Everything else - Classic, a manual pick in any mode - returns false and the
 * player behaves exactly as it did before the preference existed.
 */
internal fun isEmbeddedSubtitlePreferenceActive(
    enabled: Boolean,
    mode: PlaybackMode,
    sourceAutoPicked: Boolean,
    userChoseSubtitle: Boolean,
): Boolean = enabled &&
    (mode == PlaybackMode.STREAMLINED || mode == PlaybackMode.INSTANT) &&
    sourceAutoPicked &&
    !userChoseSubtitle

/**
 * Reads mpv's track list for a container subtitle in [primaryTarget].
 *
 * Forced tracks are not a match: "Prefer built-in subtitles" means full subtitles, and forced-only
 * selection keeps its own path untouched. Among several matches the ordinary language tie-breaks
 * (`pt` vs `pt-BR`, `es` vs `es-419`) decide first, and the container's `default` flag breaks what
 * is left.
 */
internal fun verifyEmbeddedSubtitles(
    tracks: List<SubtitleTrack>,
    primaryTarget: String?,
): EmbeddedSubtitleVerification {
    if (tracks.isNotEmpty() && tracks.none { it.isOriginKnown }) {
        return EmbeddedSubtitleVerification(EmbeddedSubtitleOutcome.ORIGIN_UNKNOWN)
    }
    val embeddedPositions = tracks.indices.filter { tracks[it].isOriginKnown && !tracks[it].isExternal }
    val embeddedLanguages = embeddedPositions.mapNotNull { tracks[it].language?.takeIf(String::isNotBlank) }
    if (embeddedPositions.isEmpty()) {
        return EmbeddedSubtitleVerification(EmbeddedSubtitleOutcome.NO_EMBEDDED_TRACKS)
    }
    val target = primaryTarget?.trim()?.takeIf { it.isNotEmpty() }
        ?: return EmbeddedSubtitleVerification(
            EmbeddedSubtitleOutcome.OTHER_LANGUAGES_ONLY,
            embeddedLanguages = embeddedLanguages,
        )

    val embedded = embeddedPositions.map { tracks[it] }
    val pick = findPreferredSubtitleTrackIndex(
        tracks = embedded,
        targets = listOf(target),
        mode = SubtitleAutoSelectionMode.NORMAL_ONLY,
    )
    if (pick < 0) {
        return EmbeddedSubtitleVerification(
            EmbeddedSubtitleOutcome.OTHER_LANGUAGES_ONLY,
            embeddedLanguages = embeddedLanguages,
        )
    }
    val picked = embedded[pick]
    val variant = subtitleVariant(picked)
    val preferDefault = if (picked.isDefault) {
        pick
    } else {
        embedded.indices.firstOrNull { index ->
            val track = embedded[index]
            track.isDefault && !track.isForced && subtitleVariant(track) == variant
        } ?: pick
    }
    return EmbeddedSubtitleVerification(
        outcome = EmbeddedSubtitleOutcome.CONFIRMED,
        trackIndex = embeddedPositions[preferDefault],
        embeddedLanguages = embeddedLanguages,
    )
}

private fun subtitleVariant(track: SubtitleTrack): String =
    SubtitleLanguageMatching.detectTrackLanguageVariant(
        language = track.language,
        name = track.label,
        trackId = track.id,
    )
