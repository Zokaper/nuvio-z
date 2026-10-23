package com.nuvio.app.core.language

// No imports, and none may be added: this file is the whole of the language model's decisions
// (`PLAN-language-and-whats-new.md`), and the player, the source picker and the settings surfaces
// only render or apply its answers. Same package as `LanguageCodes.kt`, which it builds on.

/**
 * Which subtitles a group of titles gets. One list for both groups, in the order the settings show it.
 *
 * [FULL] prefers a plain track over SDH/CC and [SDH] the reverse; each falls back to the other when a
 * file only has one, so neither choice can end in no subtitles when the language exists.
 */
enum class SubtitleKind(val key: String) {
    OFF("off"),
    FORCED("forced"),
    FULL("full"),
    SDH("sdh"),
    ;

    companion object {
        fun fromKey(key: String?): SubtitleKind? = entries.firstOrNull { it.key == key }
    }
}

/** Audio for titles made in another language. [MY_LANGUAGE] is a dub when there is one. */
enum class OtherLanguageAudio(val key: String) {
    ORIGINAL("original"),
    MY_LANGUAGE("my_language"),
    ;

    companion object {
        fun fromKey(key: String?): OtherLanguageAudio? = entries.firstOrNull { it.key == key }
    }
}

/**
 * The language settings (2026-10-20). Replaces upstream's four language pickers and its "Use Forced
 * Subtitles" switch, whose stored values are still written - see [legacyLanguageFields] - so older
 * builds, vanilla clients and anything that has not learned this model keep behaving.
 *
 * @property myLanguage a language code or [AudioLanguageOption.DEVICE].
 * @property fallbackLanguage used for audio and subtitles when [myLanguage] is not available.
 * @property subtitleLanguage null means "Same as my language", the default.
 */
data class LanguagePreferences(
    val myLanguage: String = AudioLanguageOption.DEVICE,
    val fallbackLanguage: String? = null,
    val subtitleLanguage: String? = null,
    val ownSubtitles: SubtitleKind = SubtitleKind.OFF,
    val otherAudio: OtherLanguageAudio = OtherLanguageAudio.ORIGINAL,
    val otherSubtitles: SubtitleKind = SubtitleKind.FULL,
)

enum class TitleGroup { OWN, OTHER }

/**
 * One title's answer, projected onto the fields upstream's track picker and source ranking already
 * read, so neither is rewritten: audio targets as a primary and secondary language (sentinels
 * allowed), subtitles as a primary and secondary language plus the forced switch.
 *
 * [preferSdh] is the one thing upstream has no field for: null when no full track is wanted.
 */
data class TitleLanguagePlan(
    val group: TitleGroup,
    val subtitleKind: SubtitleKind,
    val preferredAudio: String,
    val secondaryAudio: String?,
    val preferredSubtitle: String,
    val secondarySubtitle: String?,
    val forcedOnly: Boolean,
    val preferSdh: Boolean?,
)

/**
 * The rule: **a title's origin decides the audio; the audio that actually plays decides which
 * subtitle choice applies** only in the two cases the origin cannot answer.
 *
 * - Group: the title's original language against [LanguagePreferences.myLanguage]. Unknown (no TMDB
 *   language, a cold Continue Watching launch) -> the language of the audio that plays; unknown too
 *   -> [TitleGroup.OWN].
 * - Audio: own titles in my language (then the fallback). Other titles in the original language then
 *   mine, or mine then the original, per [LanguagePreferences.otherAudio]. Unknown origin: my language
 *   then the fallback, as for my own titles, and the file's default only when neither is in the file.
 *   This was the file's default until 2026-10-23 (the maintainer's call): a dubbed release whose default
 *   track is the dub played a Russian dub of an English film, which is worse than the rarer case of a
 *   foreign title opening in my language when its origin could not be looked up.
 * - Subtitles: the group's [SubtitleKind] - except an other-language title that wanted its original
 *   audio and is playing mine because the release has no original track. That one "plays like a title
 *   in my language", as the settings page says.
 *
 * @param originalLanguage the title's original language, any form [normalizeLanguageCode] reads.
 * @param playingAudioLanguage the selected audio track's language, null before tracks are known.
 */
fun resolveTitleLanguagePlan(
    preferences: LanguagePreferences,
    deviceLanguages: List<String>,
    originalLanguage: String?,
    playingAudioLanguage: String?,
): TitleLanguagePlan {
    val mine = resolvedLanguage(preferences.myLanguage, deviceLanguages)
    fun isMine(code: String): Boolean = mine != null && languageMatchesPreference(code, mine)

    val original = knownLanguage(originalLanguage)
    val playing = knownLanguage(playingAudioLanguage)
    val group = when {
        original != null -> if (isMine(original)) TitleGroup.OWN else TitleGroup.OTHER
        playing != null -> if (isMine(playing)) TitleGroup.OWN else TitleGroup.OTHER
        else -> TitleGroup.OWN
    }

    val fallback = preferences.fallbackLanguage?.takeIf { knownLanguage(it) != null }
    // `device` only means something in the primary slot - upstream's resolvers drop a secondary
    // sentinel - so the second place gets the resolved code.
    val (preferredAudio, secondaryAudio) = when {
        original == null || group == TitleGroup.OWN -> preferences.myLanguage to fallback
        preferences.otherAudio == OtherLanguageAudio.ORIGINAL -> original to mine
        else -> preferences.myLanguage to original
    }

    val playsLikeOwn = group == TitleGroup.OTHER &&
        preferences.otherAudio == OtherLanguageAudio.ORIGINAL &&
        original != null &&
        playing != null &&
        isMine(playing)
    val kind = if (group == TitleGroup.OWN || playsLikeOwn) preferences.ownSubtitles else preferences.otherSubtitles
    val subtitleLanguage = preferences.subtitleLanguage ?: preferences.myLanguage

    return TitleLanguagePlan(
        group = group,
        subtitleKind = kind,
        preferredAudio = pickerLanguage(preferredAudio),
        secondaryAudio = secondaryAudio?.let(::pickerLanguage),
        preferredSubtitle = if (kind == SubtitleKind.OFF) SubtitleLanguageOption.NONE else pickerLanguage(subtitleLanguage),
        secondarySubtitle = if (kind == SubtitleKind.OFF) null else fallback?.let(::pickerLanguage),
        forcedOnly = kind == SubtitleKind.FORCED,
        preferSdh = when (kind) {
            SubtitleKind.FULL -> false
            SubtitleKind.SDH -> true
            else -> null
        },
    )
}

/** Upstream's stored language fields, as this model writes them for everything that still reads them. */
data class LegacyLanguageFields(
    val preferredAudio: String,
    val secondaryAudio: String?,
    val preferredSubtitle: String,
    val secondarySubtitle: String?,
    val useForcedSubtitles: Boolean,
)

/**
 * What the old fields should hold: the answer for a title in my language. Downloads, the external
 * player hand-off, an older build on another device and vanilla Nuvio all read these, and a title in
 * the user's own language is the case every one of them can express.
 */
fun legacyLanguageFields(preferences: LanguagePreferences): LegacyLanguageFields {
    val fallback = preferences.fallbackLanguage?.takeIf { knownLanguage(it) != null }
    val off = preferences.ownSubtitles == SubtitleKind.OFF
    return LegacyLanguageFields(
        preferredAudio = pickerLanguage(preferences.myLanguage),
        secondaryAudio = fallback?.let(::pickerLanguage),
        preferredSubtitle = if (off) SubtitleLanguageOption.NONE else pickerLanguage(preferences.subtitleLanguage ?: preferences.myLanguage),
        secondarySubtitle = if (off) null else fallback?.let(::pickerLanguage),
        useForcedSubtitles = preferences.ownSubtitles == SubtitleKind.FORCED,
    )
}

/**
 * The first load of a profile that has no language preferences yet: read them off upstream's fields
 * so **nobody's playback changes on update**. Every rule below reproduces what the old picker did with
 * the same values; the "New settings available" offer is where a user changes them.
 */
fun migrateLegacyLanguageFields(
    preferredAudio: String?,
    secondaryAudio: String?,
    preferredSubtitle: String?,
    secondarySubtitle: String?,
    useForcedSubtitles: Boolean,
    deviceLanguages: List<String>,
): LanguagePreferences {
    val audio = normalizeLanguageCode(preferredAudio)
    val subtitle = normalizeLanguageCode(preferredSubtitle)
    val subtitleIsLanguage = isConcreteLanguage(subtitle) || subtitle == SubtitleLanguageOption.DEVICE

    val myLanguage = when {
        isConcreteLanguage(audio) -> audio!!
        audio == AudioLanguageOption.DEVICE -> AudioLanguageOption.DEVICE
        isConcreteLanguage(subtitle) -> subtitle!!
        else -> AudioLanguageOption.DEVICE
    }
    val mine = resolvedLanguage(myLanguage, deviceLanguages)
    val subtitleOverride = subtitle
        ?.takeIf { subtitleIsLanguage }
        ?.takeUnless { code ->
            val resolved = resolvedLanguage(code, deviceLanguages)
            resolved == null || (mine != null && languageMatchesPreference(resolved, mine))
        }
    val fallback = listOf(secondaryAudio, secondarySubtitle)
        .map(::normalizeLanguageCode)
        .firstOrNull { code -> isConcreteLanguage(code) && (mine == null || !languageMatchesPreference(code!!, mine)) }

    // Old picker: a real subtitle language gave full subtitles, or forced-only while the audio matched
    // it when the switch was on. "None"/"Forced" gave nothing, except forced-only in the audio's own
    // language when the switch was on.
    val ownSubtitles = when {
        useForcedSubtitles -> SubtitleKind.FORCED
        subtitleIsLanguage -> SubtitleKind.FULL
        else -> SubtitleKind.OFF
    }
    return LanguagePreferences(
        myLanguage = myLanguage,
        fallbackLanguage = fallback,
        subtitleLanguage = subtitleOverride,
        ownSubtitles = ownSubtitles,
        otherAudio = if (audio == AudioLanguageOption.ORIGINAL || audio == AudioLanguageOption.DEFAULT) {
            OtherLanguageAudio.ORIGINAL
        } else {
            OtherLanguageAudio.MY_LANGUAGE
        },
        otherSubtitles = if (subtitleIsLanguage) SubtitleKind.FULL else SubtitleKind.OFF,
    )
}

private val LanguageSentinels = setOf(
    AudioLanguageOption.DEFAULT,
    AudioLanguageOption.DEVICE,
    AudioLanguageOption.ORIGINAL,
    SubtitleLanguageOption.NONE,
    SubtitleLanguageOption.FORCED,
)

/**
 * [code] in the form upstream's track pickers can match. They keep a region only where it names a
 * different subtitle or dub (pt-BR, es-419, zh-TW / zh-CN) and otherwise compare it whole, so a stored
 * "en-sa" (a device locale, carried over by [migrateLegacyLanguageFields]) matched no English track:
 * Attack on Titan played with its only subtitle track, English and on by default, switched off. Any
 * other region is dropped here, the one seam both pickers read through; sentinels pass unchanged.
 */
internal fun pickerLanguage(code: String): String {
    val normalized = normalizeLanguageCode(code) ?: return code
    val base = normalized.substringBefore('-')
    return if (base == normalized || base in RegionalPickerLanguages) normalized else base
}

private val RegionalPickerLanguages = setOf("pt", "es", "zh")

private fun isConcreteLanguage(code: String?): Boolean =
    code != null && code !in LanguageSentinels && code != "und" && code != "unknown"

/** A language code, or null for blank, `und` and the sentinels. */
private fun knownLanguage(value: String?): String? =
    normalizeLanguageCode(value)?.takeIf(::isConcreteLanguage)

/** [AudioLanguageOption.DEVICE] resolved to the device's first language; a code as itself. */
private fun resolvedLanguage(value: String?, deviceLanguages: List<String>): String? =
    if (normalizeLanguageCode(value) == AudioLanguageOption.DEVICE) {
        deviceLanguages.firstNotNullOfOrNull(::knownLanguage)
    } else {
        knownLanguage(value)
    }
