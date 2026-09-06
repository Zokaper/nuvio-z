package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import com.nuvio.app.features.watchparty.PartyContent
import com.nuvio.app.features.watchparty.SourceFingerprint

internal data class PlayerScreenArgs(
    val profileId: Int,
    val title: String,
    val sourceUrl: String,
    val sourceAudioUrl: String?,
    val sourceHeaders: Map<String, String>,
    val sourceResponseHeaders: Map<String, String>,
    val streamType: String?,
    val providerName: String,
    val streamTitle: String,
    val streamSubtitle: String?,
    val initialBingeGroup: String?,
    val pauseDescription: String?,
    val onBack: () -> Unit,
    val onOpenInExternalPlayer: ((ExternalPlayerPlaybackRequest) -> Unit)?,
    val onOpenExternalUrl: ((String) -> Unit)?,
    val onFatalPlaybackError: (() -> Unit)? = null,
    val onPlaybackStarted: (() -> Unit)? = null,
    val onStartWatchTogether: ((PartyContent, SourceFingerprint) -> Unit)? = null,
    val modifier: Modifier,
    val logo: String?,
    val poster: String?,
    val background: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val episodeTitle: String?,
    val episodeThumbnail: String?,
    val contentType: String?,
    val videoId: String?,
    val parentMetaId: String,
    val parentMetaType: String,
    val providerAddonId: String?,
    val externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle> = emptyList(),
    val torrentInfoHash: String?,
    val torrentFileIdx: Int?,
    val torrentFilename: String?,
    val torrentTrackers: List<String>,
    val initialPositionMs: Long,
    val initialProgressFraction: Float?,
    val contentLanguage: String? = null,
    /**
     * What the route chose, structured, for the loading screen's band.
     *
     * Carried rather than re-derived: the player has `activeStreamTitle` and
     * `activeProviderName` but nothing structured, and re-parsing the display title here would
     * give the two sides of the hand-off two different answers about the same file - which is
     * exactly the flicker this screen exists to remove.
     */
    val sourceFacts: com.nuvio.app.features.downloads.SourceFacts? = null,
    /** 1-based, from the route's `autoPickAttempt`, so the band keeps counting across the trip. */
    val playbackAttempt: Int = 1,
    /**
     * The catalogue's runtime, for `PlaybackDurationPlausibility`.
     *
     * Null disables the check. Never a default guess: a wrong expectation here abandons a source
     * the user is already watching.
     */
    val expectedRuntimeMinutes: Int? = null,
)
