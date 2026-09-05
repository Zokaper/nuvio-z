package com.nuvio.app.features.playback

import com.nuvio.app.features.downloads.SourceFacts

// The pre-player loading surface's whole vocabulary, with no Compose in it.
//
// [PlaybackProgressStep], [PlaybackProgressInputs], [PlaybackProgress] and
// [PlaybackProgressFailure] used to live in `PlaybackProgressOverlay.kt` beside the composable
// that drew them. That put a derived, wholly testable state machine inside a file the pure
// suite cannot compile, so the rule these types exist to enforce - *derived, never faked* - was
// the one rule nothing could execute. They moved here when the loading screen became shared,
// which is the same reason: two call sites now build this state, and a vocabulary owned by one
// of them is a vocabulary the other will fork.

/**
 * What the automatic playback path is doing, for the overlay Streamlined and Instant show
 * instead of the source list.
 *
 * Every value maps to state that already exists in `entry<StreamRoute>` - see
 * [PlaybackProgress.step]. Nothing here is a timed or faked sequence: a step that cannot be
 * observed is a step that lies about what the app is waiting for.
 */
enum class PlaybackProgressStep {
    /** Addons and plugins are still returning candidates. */
    FindingSources,

    /**
     * Instant only: the candidates are in but the connection measurement has not settled.
     *
     * Instant picks a quality *from* that measurement, so choosing before it lands would be
     * choosing from the unmeasured platform guess - the fault the mode was withdrawn for the
     * first time. The wait is bounded by `NetworkStrengthProbe.PROBE_DEADLINE_MS` and is
     * usually invisible, because the probe runs alongside the fetch and the fetch is slower.
     * It gets its own step for the case where it is not, because "Choosing a source" while the
     * app is measuring a line is the same small lie the connection gauge work spent three
     * passes removing.
     */
    CheckingConnection,

    /** Candidates are in; `PlaybackSourceSelector` is ranking them. */
    ChoosingSource,

    /** A debrid link is being minted for the chosen candidate. Usually the real wait. */
    ResolvingLink,

    /** Chosen and resolved; handing off to the player. */
    StartingPlayback,
}

/**
 * Everything the overlay's state depends on, gathered by the caller.
 *
 * Plain data on purpose - the route entry gathers, this decides, and a test can cover the
 * whole table without a Compose runtime.
 */
data class PlaybackProgressInputs(
    /** `streamsUiState.isAnyLoading`, or the request token not yet matching. */
    val isLoadingSources: Boolean,
    /** `instantSelectionHandled` for Instant, the tier pick for Streamlined. */
    val hasChosenSource: Boolean,
    /** The existing `resolvingDebridStream` flag. */
    val isResolvingLink: Boolean,
    /** 1-based. Above 1 means the failure chain has moved on from a dead candidate. */
    val attempt: Int = 1,
    /**
     * Instant only: `!connectionSettled`, the same signal the quality sheet withholds its
     * figure on.
     *
     * Deliberately not passed by the remembered-band path, which does not need an estimate -
     * its band is exact - and must not claim to be waiting for one.
     */
    val isMeasuringConnection: Boolean = false,
)

object PlaybackProgress {

    /**
     * The retry budget the failure chain runs to, so the overlay and the chain cannot disagree
     * about how many tries the user is being told about.
     *
     * Defined in `StreamRouteSurface.kt` and aliased here. That file has no imports and is the
     * one thing `scripts/run-pure-suites.sh` can actually execute, so the budget and the
     * function that spends it ([playbackChain]) are covered by a test that runs without Gradle -
     * which is how the drift this fixes would have been caught.
     */
    const val MAX_ATTEMPTS: Int = PLAYBACK_MAX_ATTEMPTS

    /**
     * Resolving is checked first because it is the only step with a real, observable wait: a
     * debrid mint can take seconds while `isLoadingSources` is still true for a slow addon
     * that nothing is waiting on any more.
     */
    fun step(inputs: PlaybackProgressInputs): PlaybackProgressStep = when {
        inputs.isResolvingLink -> PlaybackProgressStep.ResolvingLink
        inputs.isLoadingSources -> PlaybackProgressStep.FindingSources
        // Below the fetch, because the two run concurrently and the fetch is nearly always the
        // longer of the two; above the choice, because Instant genuinely cannot choose yet.
        inputs.isMeasuringConnection && !inputs.hasChosenSource ->
            PlaybackProgressStep.CheckingConnection
        !inputs.hasChosenSource -> PlaybackProgressStep.ChoosingSource
        else -> PlaybackProgressStep.StartingPlayback
    }

    // `isVisible` used to live here and answered only "does the overlay cover the list?".
    // That was half the question: the route also paints an opaque hand-off surface under the
    // overlay, and hiding the overlay while that surface stayed up traded a blank screen for
    // a blank screen one layer down - which is what backing out of the player actually did.
    // The whole stack is decided by `streamRouteSurface` in StreamRouteSurface.kt, so the two
    // cannot disagree - and that file has no imports, so unlike this one it actually runs.
}

/**
 * The source an automatic path has just given up on, for the overlay to name.
 *
 * [label] comes from `PlaybackSourceSelector.describe` - `1080p · WEB-DL · TorBox` - falling
 * back to the stream's own label when nothing is known about it. [reason] is the provider's
 * words when it gave any, and null when it simply failed.
 */
data class PlaybackProgressFailure(
    val label: String,
    val reason: String? = null,
)


/**
 * Everything the one loading surface shows, gathered by whichever side owns the moment.
 *
 * Both the route (`PlaybackProgressOverlay`) and the player (`OpeningOverlay`) build one of
 * these and hand it to [PlaybackLoadingScreen]. That is the whole point: crossing from the
 * route to the player must move nothing on screen, and two call sites rendering one state
 * object is the only version of that guarantee a test can hold.
 *
 * **Derived, never faked** - the rule [PlaybackProgressStep] already carries, extended to the
 * rest of the screen. Every field here is read from state that exists; nothing is a timed
 * sequence and nothing is placeholdered. A source that reported no metadata renders a screen
 * with no chips rather than a screen full of invented ones.
 */
data class PlaybackLoadingState(
    val step: PlaybackProgressStep,
    /** 1-based. Above 1 means the failure chain has moved on from a dead candidate. */
    val attempt: Int = 1,
    val maxAttempts: Int = PLAYBACK_MAX_ATTEMPTS,
    /**
     * What is being opened, once something has been chosen.
     *
     * Null before the choice - Instant's first frames, and every mode's "Looking for
     * sources…" - and null for a source nothing could be parsed from. Both render the same
     * screen minus the band's contents, which is why nothing below may assume it.
     */
    val facts: SourceFacts? = null,
    /** The candidate that just died, when one did. Colours the stage line. */
    val failure: PlaybackProgressFailure? = null,
    /** Whether the way out to the source list is offered yet. */
    val offerManualEscape: Boolean = false,
) {
    /** Above 1, and never past the budget - "Attempt 5 of 3" is unreachable by construction. */
    val displayAttempt: Int get() = attempt.coerceIn(1, maxAttempts)

    val showsAttempt: Boolean get() = attempt > 1

    /**
     * The release name, small and dimmed at the foot of the screen.
     *
     * Kept deliberately: it is how a wrong-show or wrong-release pick becomes visible *before*
     * it plays, which is the user-facing half of the content-identity guard. A guard that
     * rejects silently and a screen that shows nothing are the same failure.
     */
    val releaseName: String? get() = facts?.filename?.takeIf { it.isNotBlank() }
}

/** The five slots the loading band always shows, in the order it shows them. */
enum class PlaybackFactSlot { RESOLUTION, LANGUAGE, DYNAMIC_RANGE, AUDIO, SIZE }

/**
 * One slot and what is known about it.
 *
 * ⚠ [value] is null when the source never said, and the screen draws [PlaybackLoadingFacts.UNKNOWN]
 * for it. The *slot* is fixed so there is a fixed place to look; the *value* is still derived,
 * never faked - a release that named no language has not told you it is English.
 */
data class PlaybackLoadingFact(val slot: PlaybackFactSlot, val value: String?)

object PlaybackLoadingFacts {

    /** Drawn in place of a value the source never reported. Punctuation, not a localized word. */
    const val UNKNOWN: String = "\u2014"

    /**
     * Always five entries, always in [PlaybackFactSlot] order, whatever the source reported.
     *
     * The band used to omit a fact it did not have, so its shape changed per source and there
     * was no fixed place to look for the size. A fixed rail with an honest gap in it is the
     * answer to both: the slot is always there, and an absent value says so.
     *
     * [formatSize] and [languageName] are passed rather than called - this file is compiled and
     * executed outside Gradle by `scripts/run-pure-suites.sh`, where the generated Compose
     * resource bundle does not exist.
     */
    fun facts(
        facts: SourceFacts?,
        formatSize: (Long) -> String,
        languageName: (String) -> String,
    ): List<PlaybackLoadingFact> = listOf(
        PlaybackLoadingFact(
            PlaybackFactSlot.RESOLUTION,
            facts?.resolution.qualityLabel.takeIf { it.isNotBlank() },
        ),
        PlaybackLoadingFact(PlaybackFactSlot.LANGUAGE, languagePairLabel(facts, languageName)),
        PlaybackLoadingFact(PlaybackFactSlot.DYNAMIC_RANGE, dynamicRangeSlot(facts)),
        PlaybackLoadingFact(PlaybackFactSlot.AUDIO, audioLabel(facts)),
        PlaybackLoadingFact(
            PlaybackFactSlot.SIZE,
            facts?.sizeBytes?.takeIf { it > 0L }?.let(formatSize),
        ),
    )

    /**
     * `SDR` when a release named no dynamic range, and null only when there is no release yet.
     *
     * ⚠ The one slot with a real default, and it is earned rather than assumed: `SourceFacts`
     * records that release names carry dynamic range **reliably** and audio format only
     * sometimes, which is why silence is read here and nowhere else. Before a source is chosen
     * there is nothing to call SDR, so a null [facts] still yields null.
     */
    fun dynamicRangeSlot(facts: SourceFacts?): String? {
        if (facts == null) return null
        return PlaybackSourceSelector.dynamicRangeLabel(facts) ?: "SDR"
    }

    /**
     * `English / English`, `English / —`, `MULTi / —`, or null when neither side is known.
     *
     * The pair is what the maintainer asked to see - what you will hear and what you can read -
     * and it is the one slot where half the answer is common. An em-dash on one side is honest;
     * a slot that silently collapses to one name would let a subtitle-only claim read as an
     * audio one.
     *
     * ⚠ **Empty is not a language claim.** `SourceFacts.languages` documents it: most English
     * releases say nothing at all, so silence on both sides draws the unknown slot, never "EN".
     */
    fun languagePairLabel(facts: SourceFacts?, languageName: (String) -> String): String? {
        val audio = namedLanguages(facts?.languages.orEmpty(), languageName)
            ?: "MULTi".takeIf { facts?.isMultiLanguage == true }
        val subtitles = namedLanguages(facts?.subtitleLanguages.orEmpty(), languageName)
        if (audio == null && subtitles == null) return null
        return "${audio ?: UNKNOWN} / ${subtitles ?: UNKNOWN}"
    }

    /** `English`, `English +2`, or null. One name plus a count - a list would not fit the slot. */
    private fun namedLanguages(codes: Set<String>, languageName: (String) -> String): String? {
        val first = codes.firstOrNull() ?: return null
        val rest = codes.size - 1
        val name = languageName(first)
        return if (rest > 0) "$name +$rest" else name
    }

    /**
     * `EAC3 5.1`, `Atmos 7.1`, `5.1`, or null.
     *
     * One codec word, best first, for the same reason [PlaybackSourceSelector.dynamicRangeLabel]
     * gives one: a release routinely carries Atmos *and* its TrueHD base layer, and printing
     * both spends a chip on two names for one track.
     *
     * Channels alone are still worth a chip - "5.1" is a fact the user chooses on even when the
     * codec went unstated.
     */
    fun audioLabel(facts: SourceFacts?): String? {
        val codec = codecLabel(facts?.audioCodecs.orEmpty())
        val channels = channelLabel(facts?.audioChannels)
        return listOfNotNull(codec, channels).joinToString(" ").takeIf { it.isNotBlank() }
    }

    private fun codecLabel(codecs: Set<String>): String? = when {
        "ATMOS" in codecs -> "Atmos"
        "DTS_X" in codecs -> "DTS:X"
        "TRUEHD" in codecs -> "TrueHD"
        "DTS_HD_MA" in codecs -> "DTS-HD MA"
        "FLAC" in codecs -> "FLAC"
        "DTS_HD" in codecs -> "DTS-HD"
        "DTS_ES" in codecs -> "DTS-ES"
        "DTS" in codecs -> "DTS"
        "DD_PLUS" in codecs -> "DD+"
        "DD" in codecs -> "DD"
        "OPUS" in codecs -> "Opus"
        "AAC" in codecs -> "AAC"
        else -> null
    }

    private fun channelLabel(channels: Int?): String? = when (channels) {
        8 -> "7.1"
        7 -> "6.1"
        6 -> "5.1"
        2 -> "2.0"
        else -> null
    }

    /**
     * `TorBox · Cached`, `Torrentio`, or null.
     *
     * The cached word is only ever printed from a **positive** answer.
     * `SourceFacts.isDebridReady` is nullable precisely because a service that names its cache
     * state only in a display string leaves it unknown, and "Cached" over a link that turns out
     * to need minting is the untruth the tri-state exists to prevent.
     */
    fun providerLine(facts: SourceFacts?): String? {
        val provider = (facts?.debridService ?: facts?.providerName)?.takeIf { it.isNotBlank() }
        val cached = when (facts?.isDebridReady) {
            true -> "Cached"
            else -> null
        }
        return listOfNotNull(provider, cached).joinToString(" · ").takeIf { it.isNotBlank() }
    }
}
