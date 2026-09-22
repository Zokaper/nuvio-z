package com.nuvio.app.features.playback

import com.nuvio.app.core.language.AudioLanguageOption
import com.nuvio.app.core.language.SubtitleLanguageOption
import com.nuvio.app.core.language.normalizeLanguageCode

/**
 * The user's four language settings, resolved to codes a *release* can be ranked against.
 *
 * A null field means "no opinion" - never "no match". Every consumer treats it as the absence of
 * a key rather than as a filter, because a sentinel that names no language must not be allowed to
 * exclude anything.
 */
data class RankableLanguages(
    val audio: String? = null,
    val secondaryAudio: String? = null,
    val subtitle: String? = null,
    val secondarySubtitle: String? = null,
)

/**
 * One resolution seam for both halves of playback.
 *
 * Two things used to resolve language and they disagreed. The player's
 * `resolvePreferredAudioLanguageTargets` expanded `device` to the OS locale and `original` to the
 * title's own language; the source picker's `rankableAudioLanguage` **discarded** both, along with
 * `default`, and handed `SourceRanking` a null. The visible consequence was that
 * [LanguageStrictness.REQUIRE] - the shipped default - did nothing at all for any profile that had
 * never opened the audio-language dialog, because that profile's stored value was the sentinel
 * `device`. The setting was not unwired; it was being thrown away one call before it was read.
 *
 * It also closes the case the strictness setting exists for. "Original audio, subtitles in my
 * language" is the ordinary way to watch anime, and it was expressible in the player and invisible
 * to the picker, so the file that opened was chosen without reference to it and the player then
 * did its best with whatever tracks that file happened to carry.
 *
 * ⚠ **This is the only place the sentinels are interpreted for ranking.** Two call sites build a
 * [PlaybackSelectionContext] for the first episode and a third builds one for the next, and a rule
 * applied in one of them is a rule that holds for one episode and not the following one - which is
 * exactly how those three builders drifted apart before.
 *
 * @param deviceLanguages the OS locale list, most-preferred first. Empty is honest and resolves to
 *   no opinion rather than to a guess.
 * @param contentOriginalLanguage this title's own language, or null when meta did not report one.
 *   `original` resolves to null in that case: the player can fall back to the device list once it
 *   can see the actual tracks, but a ranker guessing a language off a title it knows nothing about
 *   would be inventing the preference it claims to be honouring.
 */
fun resolveRankableLanguages(
    preferredAudio: String,
    secondaryAudio: String?,
    preferredSubtitle: String,
    secondarySubtitle: String?,
    deviceLanguages: List<String>,
    contentOriginalLanguage: String?,
): RankableLanguages {
    val device = deviceLanguages.firstNotNullOfOrNull(::rankableCode)
    val original = rankableCode(contentOriginalLanguage)

    fun resolve(value: String?): String? = when (normalizeLanguageCode(value)) {
        null,
        AudioLanguageOption.DEFAULT,
        SubtitleLanguageOption.NONE,
        SubtitleLanguageOption.FORCED,
        -> null
        // `device` and `original` are the same constant for audio and subtitles, so one branch
        // each serves both. `SubtitleLanguageOption.ORIGINAL` does not exist - a subtitle in the
        // original language is what you are trying to avoid needing - but a stored `original`
        // still resolves rather than falling through to the concrete-code branch.
        AudioLanguageOption.DEVICE -> device
        AudioLanguageOption.ORIGINAL -> original
        else -> rankableCode(value)
    }

    return RankableLanguages(
        audio = resolve(preferredAudio),
        secondaryAudio = resolve(secondaryAudio),
        subtitle = resolve(preferredSubtitle),
        secondarySubtitle = resolve(secondarySubtitle),
    )
}

/**
 * A normalized code, or null if the value is blank or is itself a sentinel.
 *
 * The sentinel check is repeated here rather than left to the caller because this is also the
 * device-list and content-language path, and an OS that reported a locale literally called
 * `default` would otherwise become a language nothing can match.
 */
private fun rankableCode(value: String?): String? = normalizeLanguageCode(value)?.takeIf {
    it != AudioLanguageOption.DEFAULT &&
        it != AudioLanguageOption.DEVICE &&
        it != AudioLanguageOption.ORIGINAL &&
        it != SubtitleLanguageOption.NONE &&
        it != SubtitleLanguageOption.FORCED
}

/**
 * The one-shot rule that gives an existing profile a language to actually rank against.
 *
 * Resolving `device` at ranking time fixes what the *picker* does, but it leaves the setting
 * screen telling the user "Device language" while the strictness row above it talks about "your
 * language" - two different words for a thing the user has still never been asked. Worse, the
 * value that would be synced to their other devices is still the sentinel, so the question would
 * follow them around unanswered.
 *
 * So the sentinel is settled once, on disk, into the code it already resolved to. Nothing changes
 * about what plays; what changes is that the profile can now *see* and edit the answer.
 *
 * ⚠ **Only the unanswered case is touched.** `default` and `original` are choices someone made on
 * purpose and neither is a stand-in for a concrete language. The subtitle preference is not
 * migrated at all: it ships as `none`, and "none" is a legitimate answer that half the users hold
 * deliberately - turning it into the device language would switch subtitles on for people who had
 * silently chosen to have none.
 *
 * @return the code to write, or null to leave the stored value exactly as it is.
 */
fun migratedPreferredAudioLanguage(
    alreadyMigrated: Boolean,
    storedPreferredAudio: String?,
    deviceLanguages: List<String>,
): String? {
    if (alreadyMigrated) return null
    val stored = normalizeLanguageCode(storedPreferredAudio)
    // Unset is the same case as `device`: both mean "whatever this machine is set to", and both
    // are what a profile that never opened the dialog will be carrying.
    if (stored != null && stored != AudioLanguageOption.DEVICE) return null
    return deviceLanguages.firstNotNullOfOrNull(::rankableCode)
}
