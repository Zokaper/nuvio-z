package com.nuvio.app.features.playback

// No imports, and none may be added. This file is pure by design so it can be compiled and run
// outside Gradle (`AGENTS.md`, "Verifying without Gradle", item 2). The engine that feeds it is
// `PlayerEngine.android.kt`, which no test in this repository can reach.

/**
 * Why a play is producing sound and no picture - **answered from track evidence, never a timer.**
 *
 * ⚠ **The defect this exists for.** On debug `.32` a clean Matroska remux played audio for
 * twenty-seven minutes across three attempts with `onRenderedFirstFrame` firing exactly zero
 * times. Nothing in the app could say why, because the only diagnostic that could have -
 * `logCurrentTracks` - built a `VIDEO` label and then `continue`d past every video group before
 * printing it, so the one question anybody had ("is the video track even supported?") was the one
 * question the logs structurally could not answer.
 *
 * The obvious fix - call a source dead when `STATE_READY` arrives and no frame follows - is the
 * wrong one, and this whole file exists to refuse it. `STATE_READY + no first frame` is true of a
 * decoder that cannot handle the profile, of a track selector that picked nothing, of a surface
 * that has not been attached yet, and of a perfectly healthy 4K remux one keyframe away from its
 * first picture. Four causes, one symptom, and a timer cannot tell them apart - so a timer would
 * be reporting "unsupported codec" about whichever of the four happened to be slow that day.
 *
 * What it classifies instead is the **track census**: how many video groups the demuxer produced,
 * how many of their tracks the renderers said they could decode, and how many the selector
 * actually chose. Those three numbers separate the four causes exactly, and only one of them -
 * [NoSupportedVideoTrack] - is a statement about a codec.
 */
enum class VideoPresentationVerdict {
    /**
     * Not enough is known yet. The tracks have not settled, or a frame may still be coming.
     *
     * The resting state of every healthy startup, and the answer this file gives whenever the
     * evidence is merely absent rather than negative.
     */
    Unknown,

    /**
     * The demuxer produced no video groups at all.
     *
     * ⚠ **Not an unsupported decoder, and must not be reported as one.** Audio-only content is
     * legitimate - a music release, a commentary track, an extracted audio file - and so is a
     * container whose video group has not been parsed yet. The caller decides what to do with
     * this from context it has and this file does not.
     */
    NoVideoTracks,

    /**
     * Video groups exist and **not one of their tracks is decodable**.
     *
     * The only fatal verdict here, and the only one that is a claim about a codec. The renderers
     * were asked, per track, and every one of them said no: no timer, no inference, no "it has
     * been a while". This is what the source fallback is for.
     */
    NoSupportedVideoTrack,

    /**
     * A decodable video track exists and **the selector chose none of them**.
     *
     * A selection failure, not a codec failure. Usually a track-selection parameter - a disabled
     * type, a size or bitrate constraint, an override pointing at a group that went away - and
     * the fix is to find which, never to blame the file.
     */
    NoSelectedVideoTrack,

    /**
     * A decodable video track was selected and **no frame has been presented**.
     *
     * A renderer, surface or startup failure. Everything upstream of presentation is demonstrably
     * fine, which is precisely why calling this an unsupported codec would send every future
     * investigation to the wrong place.
     */
    SelectedNotRendering,

    /** A frame has been presented. Nothing is wrong. */
    Rendering,
}

/**
 * What the engine counted in the current track list, for [classifyVideoPresentation].
 *
 * Counts rather than the tracks themselves, so this file stays free of Media3 - and so the
 * census is the same three numbers on any engine that can produce them.
 */
data class VideoTrackCensus(
    /** Video groups the demuxer produced. */
    val groupCount: Int = 0,
    /** Video tracks across those groups, counted per track and not per group. */
    val trackCount: Int = 0,
    /**
     * Video tracks the renderers reported they can decode.
     *
     * ⚠ **Per track, never per group.** A group routinely carries several tracks - adaptive
     * ladders, a Dolby Vision base layer beside its enhancement - and asking only about index 0
     * answers about one of them while the selector is free to choose any. That shortcut is how
     * "supported" and "the thing that plays" come apart.
     *
     * ⚠ **"Decodable", not "within the advertised headroom".** A format above a device's rated
     * decoder capability is still selected by every engine here when nothing better exists, and
     * usually plays; counting it out would make [VideoPresentationVerdict.NoSupportedVideoTrack]
     * fire on ordinary 4K remuxes. The caller passes the lenient answer - see
     * `videoTrackCensus` in `PlayerEngine.android.kt`.
     */
    val supportedTrackCount: Int = 0,
    /** Video tracks the selector chose. */
    val selectedTrackCount: Int = 0,
    /** Tracks that are both decodable and chosen - the only ones that can produce a picture. */
    val selectedSupportedTrackCount: Int = 0,
) {
    val hasVideoGroups: Boolean get() = groupCount > 0
    val hasSupportedVideo: Boolean get() = supportedTrackCount > 0
    val hasSelectedSupportedVideo: Boolean get() = selectedSupportedTrackCount > 0
}

/**
 * The verdict for one reading.
 *
 * [tracksSettled] is the caller's statement that the track list is the demuxer's final answer for
 * this source - on Media3, that a `Tracks` callback has been received. Before it, every negative
 * count means "not yet" rather than "not at all", so this returns [VideoPresentationVerdict.Unknown]
 * whatever the census says. A file that has not been parsed has not failed.
 *
 * [hasRenderedFirstFrame] short-circuits everything: a presented frame is the end of the question.
 *
 * ⚠ **There is deliberately no elapsed-time parameter.** Adding one would let a slow start be
 * reported as a dead codec, which is the entire failure mode this file was written to prevent.
 * The caller may wait as long as it likes before *acting* on a verdict; it may not manufacture one.
 */
fun classifyVideoPresentation(
    census: VideoTrackCensus,
    tracksSettled: Boolean,
    hasRenderedFirstFrame: Boolean,
): VideoPresentationVerdict = when {
    hasRenderedFirstFrame -> VideoPresentationVerdict.Rendering
    !tracksSettled -> VideoPresentationVerdict.Unknown
    !census.hasVideoGroups -> VideoPresentationVerdict.NoVideoTracks
    !census.hasSupportedVideo -> VideoPresentationVerdict.NoSupportedVideoTrack
    !census.hasSelectedSupportedVideo -> VideoPresentationVerdict.NoSelectedVideoTrack
    else -> VideoPresentationVerdict.SelectedNotRendering
}

/**
 * Whether a verdict is grounds for abandoning the source.
 *
 * Exactly one is, and the narrowness is the point. [VideoPresentationVerdict.NoVideoTracks] is
 * legitimate audio-only content; [VideoPresentationVerdict.NoSelectedVideoTrack] and
 * [VideoPresentationVerdict.SelectedNotRendering] are this app's faults, not the file's, and
 * failing over to another source would hide a bug behind a retry that fixes nothing.
 */
fun isFatalVideoPresentation(verdict: VideoPresentationVerdict): Boolean =
    verdict == VideoPresentationVerdict.NoSupportedVideoTrack

/** A stable, greppable word per verdict for the diagnostic log. */
fun videoPresentationLogCode(verdict: VideoPresentationVerdict): String = when (verdict) {
    VideoPresentationVerdict.Unknown -> "unknown"
    VideoPresentationVerdict.NoVideoTracks -> "no_video_tracks"
    VideoPresentationVerdict.NoSupportedVideoTrack -> "no_supported_video"
    VideoPresentationVerdict.NoSelectedVideoTrack -> "no_selected_video"
    VideoPresentationVerdict.SelectedNotRendering -> "selected_not_rendering"
    VideoPresentationVerdict.Rendering -> "rendering"
}
