package com.nuvio.app.core.language

/**
 * Language code and name normalization, with no imports, so **stream metadata can use it too**.
 *
 * This lived inside `features/player/PlayerLanguagePreferences.kt` and served exactly one job:
 * matching an embedded audio or subtitle track against the user's preference. Meanwhile the two
 * places that read a language off a *release* had their own vocabularies and both were nearly
 * useless - `SourceFactsExtractor` knew seven languages and no `MULTI`, and
 * `DebridStreamMetadata` matched only exact two-letter codes, so it rejected even `"eng"`.
 * Every catalogue in the app was being filtered by the weakest of the three.
 *
 * Moved here rather than imported from `features/player` for a concrete reason: that file
 * reaches the generated Compose resource bundle for its localized labels, and
 * `features/downloads/SourceFacts.kt` is compiled by `scripts/run-pure-suites.sh` outside
 * Gradle, where no such bundle exists. `AvailableLanguageOptions` and the label lookup stay
 * there; only the parsing moves. **Keep this file import-free.**
 */

/**
 * The three sentinel values [preferredAudioLanguage][com.nuvio.app.features.player.PlayerSettingsUiState.preferredAudioLanguage]
 * can hold instead of a language code.
 *
 * They live here, beside the parsing, rather than with the localized labels in
 * `features/player/PlayerLanguagePreferences.kt`, because **both** the player's track selection
 * and the source picker have to agree on what they mean. While they lived next to the labels the
 * source picker could not import them - that file reaches the generated Compose resource bundle -
 * so it discarded them instead, and `LanguageStrictness.REQUIRE` was inert for every profile that
 * had never opened the language dialog. `PlayerLanguagePreferences` re-exports both names, so the
 * seventy-odd existing call sites are unchanged.
 */
object AudioLanguageOption {
    const val DEFAULT = "default"
    const val DEVICE = "device"
    const val ORIGINAL = "original"
}

/** [AudioLanguageOption]'s subtitle counterpart, here for the same reason. */
object SubtitleLanguageOption {
    const val NONE = "none"
    const val DEVICE = "device"
    const val FORCED = "forced"
}

internal val LanguageCodeAliases = mapOf(
    "pt-pt" to "pt",
    "pt_br" to "pt-BR",
    "pt-br" to "pt-BR",
    "br" to "pt-BR",
    "pob" to "pt-BR",
    "eng" to "en",
    "spa" to "es",
    "es-419" to "es-419",
    "es_419" to "es-419",
    "es-la" to "es-419",
    "es-lat" to "es-419",
    "fra" to "fr",
    "fre" to "fr",
    "deu" to "de",
    "ger" to "de",
    "ita" to "it",
    "por" to "pt",
    "rus" to "ru",
    "jpn" to "ja",
    "kor" to "ko",
    "zho" to "zh",
    "chi" to "zh",
    "zht" to "zh-TW",
    "zhs" to "zh-CN",
    "chi-tw" to "zh-TW",
    "chi-cn" to "zh-CN",
    "zh-tw" to "zh-TW",
    "zh_tw" to "zh-TW",
    "zh-cn" to "zh-CN",
    "zh_cn" to "zh-CN",
    "ara" to "ar",
    "hin" to "hi",
    "nld" to "nl",
    "dut" to "nl",
    "pol" to "pl",
    "swe" to "sv",
    "nor" to "no",
    "dan" to "da",
    "fin" to "fi",
    "tur" to "tr",
    "ell" to "el",
    "gre" to "el",
    "heb" to "he",
    "tha" to "th",
    "vie" to "vi",
    "ind" to "id",
    "msa" to "ms",
    "may" to "ms",
    "ces" to "cs",
    "cze" to "cs",
    "hun" to "hu",
    "ron" to "ro",
    "rum" to "ro",
    "ukr" to "uk",
    "bul" to "bg",
    "hrv" to "hr",
    "srp" to "sr",
    "slk" to "sk",
    "slo" to "sk",
    "slv" to "sl",
    "cat" to "ca",
    "alb" to "sq",
    "sqi" to "sq",
    "bos" to "bs",
    "mac" to "mk",
    "mkd" to "mk",
    "lav" to "lv",
    "lit" to "lt",
    "est" to "et",
    "isl" to "is",
    "ice" to "is",
    "glg" to "gl",
    "baq" to "eu",
    "eus" to "eu",
    "wel" to "cy",
    "cym" to "cy",
    "gle" to "ga",
    "ben" to "bn",
    "tam" to "ta",
    "tel" to "te",
    "mal" to "ml",
    "kan" to "kn",
    "mar" to "mr",
    "pan" to "pa",
    "guj" to "gu",
    "urd" to "ur",
    "fas" to "fa",
    "per" to "fa",
    "amh" to "am",
    "swa" to "sw",
    "zul" to "zu",
    "afr" to "af",
    "mlt" to "mt",
    "bel" to "be",
    "geo" to "ka",
    "kat" to "ka",
    "arm" to "hy",
    "hye" to "hy",
    "aze" to "az",
    "kaz" to "kk",
    "uzb" to "uz",
    "mon" to "mn",
    "khm" to "km",
    "lao" to "lo",
    "mya" to "my",
    "bur" to "my",
    "sin" to "si",
    "nep" to "ne",
    "tgl" to "tl",
    "fil" to "tl",
)

internal val LanguageNameAliases = mapOf(
    "afrikaans" to "af",
    "albanian" to "sq",
    "amharic" to "am",
    "arabic" to "ar",
    "armenian" to "hy",
    "azerbaijani" to "az",
    "basque" to "eu",
    "belarusian" to "be",
    "bengali" to "bn",
    "bosnian" to "bs",
    "bulgarian" to "bg",
    "burmese" to "my",
    "catalan" to "ca",
    "chinese" to "zh",
    "mandarin" to "zh",
    "croatian" to "hr",
    "czech" to "cs",
    "danish" to "da",
    "dutch" to "nl",
    "english" to "en",
    "estonian" to "et",
    "filipino" to "tl",
    "finnish" to "fi",
    "french" to "fr",
    "galician" to "gl",
    "georgian" to "ka",
    "german" to "de",
    "greek" to "el",
    "gujarati" to "gu",
    "hebrew" to "he",
    "hindi" to "hi",
    "hungarian" to "hu",
    "icelandic" to "is",
    "indonesian" to "id",
    "irish" to "ga",
    "italian" to "it",
    "japanese" to "ja",
    "kannada" to "kn",
    "kazakh" to "kk",
    "khmer" to "km",
    "korean" to "ko",
    "lao" to "lo",
    "latvian" to "lv",
    "lithuanian" to "lt",
    "macedonian" to "mk",
    "malay" to "ms",
    "malayalam" to "ml",
    "maltese" to "mt",
    "marathi" to "mr",
    "mongolian" to "mn",
    "nepali" to "ne",
    "norwegian" to "no",
    "persian" to "fa",
    "polish" to "pl",
    "punjabi" to "pa",
    "romanian" to "ro",
    "russian" to "ru",
    "serbian" to "sr",
    "sinhala" to "si",
    "slovak" to "sk",
    "slovenian" to "sl",
    "swahili" to "sw",
    "swedish" to "sv",
    "tamil" to "ta",
    "telugu" to "te",
    "thai" to "th",
    "turkish" to "tr",
    "ukrainian" to "uk",
    "urdu" to "ur",
    "uzbek" to "uz",
    "vietnamese" to "vi",
    "welsh" to "cy",
    "zulu" to "zu",
    // Market names, which is what addons actually put in a structured `languages` field. The
    // Spanish and Portuguese special cases above only fire when the word "spanish" or
    // "portuguese" is also present, so a bare "Latino" fell through to the unrecognized
    // passthrough and came back as the string `latino` - a value nothing can ever match.
    "latino" to "es-419",
    "latin american" to "es-419",
    "brazilian" to "pt-BR",
)

/**
 * ⚠ **Both of these are hoisted because this function is called from composition.**
 *
 * `normalizeLanguageCode` compiled a fresh `Regex` and sorted the whole of [LanguageNameAliases]
 * *per call*, and the player's subtitle menu calls it once per track per language every time
 * `RenderPlayerRuntimeUi` recomposes. Measured on the desktop debug build, that was 35-77 ms of UI
 * thread per recomposition at exactly the moment a source is chosen - three of the stalls in the
 * "stutter between the source and the loading screen" report, in
 * `buildPlayerControlSubtitleSelection` and `buildSubtitleSelectionOptions`.
 *
 * Neither changes behaviour: the regex is the same pattern and the list is the same order the
 * `sortedByDescending` produced, computed once. If this file gains a mutable alias map, these have
 * to be rebuilt with it.
 */
private val WhitespaceRun = Regex("\\s+")

/**
 * One alias, with the three padded forms the word-boundary test needs already built.
 *
 * Building `"$name "`, `" $name"` and `" $name "` inside the scan meant three string allocations
 * per alias per call, and the scan runs over every alias for any value the two maps above do not
 * answer directly - roughly three hundred allocations per call, on the UI thread. Same comparisons,
 * same order, no allocation.
 */
private class LanguageNameMatcher(
    val name: String,
    val prefix: String,
    val suffix: String,
    val infix: String,
    val code: String,
)

/** Longest name first, so "latin american" is matched before "latin". See [WhitespaceRun]. */
private val LanguageNameMatchers: List<LanguageNameMatcher> by lazy {
    LanguageNameAliases.entries
        .sortedByDescending { it.key.length }
        .map { (name, code) ->
            LanguageNameMatcher(
                name = name,
                prefix = "$name ",
                suffix = " $name",
                infix = " $name ",
                code = code,
            )
        }
}

/** The marker sets, hoisted for the same reason: a `vararg` call allocates an array per call. */
private val PortugueseMarkers = listOf("portuguese", "portugues")
private val BrazilianMarkers =
    listOf("brazil", "brasil", "brazilian", "brasileiro", "pt br", "ptbr", "pob", "(br)")
private val EuropeanPortugueseMarkers =
    listOf("portugal", "european", "europeu", "iberian", "pt pt", "ptpt")
private val SpanishMarkers = listOf("spanish", "espanol", "castellano")
private val LatinSpanishMarkers = listOf(
    "latin", "latino", "latinoamerica", "latinoamericano", "lat am", "latam",
    "es 419", "es419", "(419)",
)

fun normalizeLanguageCode(language: String?): String? {
    val raw = language
        ?.trim()
        ?.replace('_', '-')
        ?.lowercase()
        ?.takeIf { it.isNotBlank() }
        ?: return null

    val tokenized = raw
        .replace('-', ' ')
        .replace('.', ' ')
        .replace('/', ' ')
        .replace(WhitespaceRun, " ")
        .trim()

    fun containsAny(values: List<String>): Boolean =
        values.any { value -> tokenized.contains(value) }

    if (containsAny(PortugueseMarkers)) {
        return when {
            containsAny(BrazilianMarkers) ->
                "pt-br"
            containsAny(EuropeanPortugueseMarkers) ->
                "pt"
            else -> "pt"
        }
    }

    if (containsAny(SpanishMarkers)) {
        return if (containsAny(LatinSpanishMarkers)) {
            "es-419"
        } else {
            "es"
        }
    }

    LanguageCodeAliases[raw]?.let { return it.replace('_', '-').lowercase() }
    LanguageNameAliases[tokenized]?.let { return it }
    LanguageNameMatchers
        .firstOrNull { matcher ->
            tokenized == matcher.name ||
                tokenized.startsWith(matcher.prefix) ||
                tokenized.endsWith(matcher.suffix) ||
                tokenized.contains(matcher.infix)
        }
        ?.let { return it.code }

    val primary = raw.substringBefore('-')
    val primaryAlias = LanguageCodeAliases[primary]?.replace('_', '-')?.lowercase()
    val suffix = raw.substringAfter('-', "")
    return if (suffix.isBlank()) {
        primaryAlias ?: primary
    } else if (primaryAlias != null && !primaryAlias.contains('-')) {
        "$primaryAlias-$suffix"
    } else {
        primaryAlias ?: "$primary-$suffix"
    }
}

fun languageMatchesPreference(trackLanguage: String?, targetLanguage: String): Boolean {
    val normalizedTrack = normalizeLanguageCode(trackLanguage) ?: return false
    val normalizedTarget = normalizeLanguageCode(targetLanguage) ?: return false
    if (normalizedTrack == normalizedTarget) return true

    val trackPrimary = normalizedTrack.substringBefore('-')
    val targetPrimary = normalizedTarget.substringBefore('-')
    return trackPrimary == targetPrimary
}

/**
 * What a release name says about its audio, as opposed to what a track's metadata says.
 *
 * [codes] are normalized language codes; [isMulti] means the release advertises more than one
 * audio track without naming them all. The distinction is the point: `MULTI` and `DUAL` are the
 * two most common language markers in the wild and **neither is a language**. Treating one as a
 * language - or, as this app did until now, not recognising it at all - is how a strict language
 * preference throws away exactly the releases most likely to satisfy it.
 */
data class ReleaseLanguages(
    val codes: Set<String> = emptySet(),
    val isMulti: Boolean = false,
) {
    val isEmpty: Boolean get() = codes.isEmpty() && !isMulti
}

/**
 * Language markers in a release name or display text.
 *
 * ⚠ **Two-letter codes are deliberately not matched here.** `IT.2017`, `De.Palma` and any
 * release group with `LA` in it all look like language tags to a bare two-letter scan, and
 * `DebridStreamPresentation.hasToken` is the standing proof - it scans for exactly that. A
 * three-letter code or a language name in a filename is nearly always what it looks like; a
 * two-letter one is a coin toss, and this decides whether a source is offered at all.
 *
 * Structured metadata does not come through here - `AioParsedFile.languages` and friends are
 * already tagged fields, so they go straight to [normalizeLanguageCode], which does accept short
 * codes because there the value means what it says.
 */
fun releaseLanguagesIn(text: String?): ReleaseLanguages = releaseLanguageEvidenceIn(text).audio

/**
 * Everything a release name says about language, with audio and subtitles kept apart.
 *
 * ⚠ **The split is the fix.** `VOSTFR` is original audio with French subtitles, `LEGENDADO` is
 * original audio with Brazilian subtitles, and `ENG.SUBS` / `ESub` / `SUB.ITA` name a subtitle
 * track. All of these used to be read as the *audio* language, which is why the loading band
 * printed French for an English film subtitled in French, and why a strict English preference
 * demoted a release that was English all along.
 */
data class ReleaseLanguageEvidence(
    val audio: ReleaseLanguages = ReleaseLanguages(),
    /** [ReleaseLanguages.isMulti] here means `MultiSubs`: several subtitle tracks, unnamed. */
    val subtitles: ReleaseLanguages = ReleaseLanguages(),
    /** `DUBBED` - the audio is not the original, whatever the title's original language is. */
    val isDubbed: Boolean = false,
    /** `HC` / `HardSub` - burned into the picture, so not a selectable subtitle track. */
    val isHardSubbed: Boolean = false,
)

fun releaseLanguageEvidenceIn(text: String?): ReleaseLanguageEvidence {
    val lower = text?.lowercase()?.takeIf { it.isNotBlank() } ?: return ReleaseLanguageEvidence()
    val words = releaseWordsIn(lower)
    val wordSet = words.mapTo(mutableSetOf()) { it.value }

    // Subtitle words first, so the tokens they claim can be blanked out before the audio scan.
    val masked = lower.toCharArray()
    fun consume(word: ReleaseWord) {
        for (index in word.start until word.end) masked[index] = ' '
    }
    val subtitleCodes = mutableSetOf<String>()
    var multiSubtitles = false
    words.forEachIndexed { position, word ->
        SubtitleCompoundWords[word.value]?.let { code ->
            if (code == MULTI_SUBTITLE_MARKER) multiSubtitles = true else subtitleCodes += code
            consume(word)
            return@forEachIndexed
        }
        compoundSubtitleLanguage(word.value)?.let { code ->
            subtitleCodes += code
            consume(word)
            return@forEachIndexed
        }
        if (word.value !in SubtitleWords) return@forEachIndexed
        consume(word)
        // `SUB.ITA` is the Italian convention and `ENG.SUBS` the English one. The following word
        // is tried first so `ITA.SUB.ENG` keeps Italian as the audio and English as the subtitle.
        val following = words.getOrNull(position + 1)
        val preceding = words.getOrNull(position - 1)
        val attached = listOfNotNull(following, preceding).firstOrNull { neighbour ->
            neighbour.value in MultiLanguageTokens || SingleWordReleaseLanguages.containsKey(neighbour.value)
        } ?: return@forEachIndexed
        if (attached.value in MultiLanguageTokens) {
            multiSubtitles = true
        } else {
            subtitleCodes += SingleWordReleaseLanguages.getValue(attached.value)
        }
        consume(attached)
    }

    val audioText = masked.concatToString()
    val audioCodes = mutableSetOf<String>()
    ReleaseLanguageTokens.forEach { (token, code) ->
        if (audioText.containsReleaseToken(token)) audioCodes += code
    }
    audioCodes += flagLanguagesIn(text)

    return ReleaseLanguageEvidence(
        audio = ReleaseLanguages(
            codes = audioCodes,
            isMulti = MultiLanguageTokens.any { audioText.containsReleaseToken(it) },
        ),
        subtitles = ReleaseLanguages(codes = subtitleCodes, isMulti = multiSubtitles),
        isDubbed = DubbedWords.any { it in wordSet },
        isHardSubbed = HardSubWords.any { it in wordSet },
    )
}

private data class ReleaseWord(val value: String, val start: Int, val end: Int)

/** Maximal letter-or-digit runs - the same boundary [containsReleaseToken] uses. */
private fun releaseWordsIn(lower: String): List<ReleaseWord> {
    val words = mutableListOf<ReleaseWord>()
    var start = -1
    for (index in 0..lower.length) {
        val isWord = index < lower.length && lower[index].isLetterOrDigit()
        if (isWord && start < 0) start = index
        if (!isWord && start >= 0) {
            words += ReleaseWord(lower.substring(start, index), start, index)
            start = -1
        }
    }
    return words
}

/** `EngSub`, `ITASubs`, `SubIta`: a language word fused to a subtitle word. */
private fun compoundSubtitleLanguage(word: String): String? {
    for (suffix in CompoundSubtitleAffixes) {
        if (word.length > suffix.length && word.endsWith(suffix)) {
            SingleWordReleaseLanguages[word.removeSuffix(suffix)]?.let { return it }
        }
        if (word.length > suffix.length && word.startsWith(suffix)) {
            SingleWordReleaseLanguages[word.removePrefix(suffix)]?.let { return it }
        }
    }
    return null
}

private const val MULTI_SUBTITLE_MARKER = "*multi*"

private val SubtitleWords = setOf(
    "sub", "subs", "subbed", "subtitle", "subtitles", "subtitled",
    "subtitulado", "subtitulos", "legenda", "legendas", "sottotitoli", "untertitel",
)

private val CompoundSubtitleAffixes = listOf("subs", "sub")

/** Scene words that are subtitle claims in their own right. */
private val SubtitleCompoundWords = mapOf(
    "esub" to "en", "esubs" to "en",
    "vostfr" to "fr", "vost" to "fr",
    "legendado" to "pt-br",
    "vose" to "es",
    "multisub" to MULTI_SUBTITLE_MARKER, "multisubs" to MULTI_SUBTITLE_MARKER,
)

private val DubbedWords = listOf("dub", "dubbed", "dubbing")

private val HardSubWords = listOf("hc", "hardsub", "hardsubs", "hardsubbed", "hardcoded")

/**
 * Delimiter-bounded, because release names are dot- and underscore-separated rather than spaced.
 * A bare `contains` would find `ara` inside `Sahara` and `ita` inside `Capitals`.
 */
private fun String.containsReleaseToken(token: String): Boolean {
    if (!token.contains(' ')) {
        var from = 0
        while (true) {
            val at = indexOf(token, from)
            if (at < 0) return false
            if (!getOrNull(at - 1).isReleaseWordChar() && !getOrNull(at + token.length).isReleaseWordChar()) {
                return true
            }
            from = at + 1
        }
    }

    val parts = token.split(' ')
    var from = 0
    while (true) {
        val firstAt = indexOf(parts[0], from)
        if (firstAt < 0) return false
        if (!getOrNull(firstAt - 1).isReleaseWordChar()) {
            var curr = firstAt + parts[0].length
            var matched = true
            for (i in 1 until parts.size) {
                val nextPart = parts[i]
                var delimCount = 0
                while (curr < length && !this[curr].isReleaseWordChar()) {
                    delimCount++
                    curr++
                }
                if (delimCount == 0 || !startsWith(nextPart, curr)) {
                    matched = false
                    break
                }
                curr += nextPart.length
            }
            if (matched && !getOrNull(curr).isReleaseWordChar()) {
                return true
            }
        }
        from = firstAt + 1
    }
}

private fun Char?.isReleaseWordChar(): Boolean = this != null && this.isLetterOrDigit()

/**
 * Languages named by flag emoji.
 *
 * Torrentio, Comet and MediaFusion all label multi-audio releases this way and the app had no
 * support for it whatsoever - no regional-indicator handling anywhere in either repository - so
 * every one of those releases read as declaring no language at all.
 *
 * A flag is a country, not a language, so only the ones whose intent is unambiguous in a release
 * name are mapped. An ambiguous flag is better left unread than guessed at.
 */
fun flagLanguagesIn(text: String?): Set<String> {
    val value = text ?: return emptySet()
    val letters = StringBuilder()
    val found = mutableSetOf<String>()
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAtCompat(index)
        val letter = regionalIndicatorLetter(codePoint)
        if (letter != null) {
            letters.append(letter)
            if (letters.length == 2) {
                FlagCountryToLanguage[letters.toString()]?.let { found += it }
                letters.clear()
            }
        } else {
            letters.clear()
        }
        index += if (codePoint > 0xFFFF) 2 else 1
    }
    return found
}

/** `Character.codePointAt` is JVM-only; this file must stay common and import-free. */
private fun String.codePointAtCompat(index: Int): Int {
    val high = this[index]
    if (high.isHighSurrogate() && index + 1 < length) {
        val low = this[index + 1]
        if (low.isLowSurrogate()) {
            return 0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
        }
    }
    return high.code
}

/** U+1F1E6..U+1F1FF are the regional indicators for A..Z; a pair of them is a flag. */
private fun regionalIndicatorLetter(codePoint: Int): Char? =
    if (codePoint in 0x1F1E6..0x1F1FF) 'a' + (codePoint - 0x1F1E6) else null

private val FlagCountryToLanguage = mapOf(
    "gb" to "en", "us" to "en", "au" to "en", "ca" to "en", "ie" to "en", "nz" to "en",
    "fr" to "fr", "de" to "de", "at" to "de", "it" to "it", "es" to "es",
    "mx" to "es-419", "ar" to "es-419", "cl" to "es-419", "co" to "es-419",
    "pt" to "pt", "br" to "pt-br", "ru" to "ru", "ua" to "uk", "pl" to "pl",
    "nl" to "nl", "se" to "sv", "no" to "no", "dk" to "da", "fi" to "fi",
    "jp" to "ja", "kr" to "ko", "cn" to "zh", "tw" to "zh", "hk" to "zh",
    "in" to "hi", "sa" to "ar", "ae" to "ar", "eg" to "ar", "il" to "he",
    "tr" to "tr", "th" to "th", "vn" to "vi", "id" to "id", "gr" to "el",
    "cz" to "cs", "hu" to "hu", "ro" to "ro", "bg" to "bg", "rs" to "sr",
    "ir" to "fa", "ph" to "tl",
)

// `multisub` and `multisubs` used to be here, which made a subtitle claim an audio one.
private val MultiLanguageTokens = listOf(
    "multi", "multilang", "multilanguage", "multiaudio",
    "dual", "dualaudio", "dual audio",
)

/**
 * Three-letter ISO codes, language names, and the release-scene words that name a market.
 *
 * The scene words carry information a code does not: `vostfr` is a French release with original
 * audio, `legendado` a Brazilian one, and `castellano` and `latino` are the two Spanishes people
 * actually distinguish between.
 */
private val ReleaseLanguageTokens: List<Pair<String, String>> = buildList {
    fun put(code: String, vararg tokens: String) = tokens.forEach { add(it to code) }

    put("en", "eng", "english")
    put("es", "spa", "esp", "spanish", "castellano", "espanol")
    put("es-419", "latino", "latin spanish")
    // `vostfr` is French *subtitles* over original audio - see `SubtitleCompoundWords`.
    put("fr", "fre", "fra", "french", "francais", "truefrench", "vff", "vfq", "vfi")
    put("de", "ger", "deu", "german", "deutsch")
    put("it", "ita", "italian", "italiano")
    put("pt", "por", "portuguese", "portugues")
    // `legendado` is Brazilian *subtitles*; `dublado` is the Brazilian dub.
    put("pt-br", "dublado", "brazilian")
    put("ru", "rus", "russian", "ru audio")
    put("uk", "ukr", "ukrainian")
    put("pl", "pol", "polish", "polski", "lektor")
    put("nl", "dut", "nld", "dutch", "nederlands")
    put("sv", "swe", "swedish", "svenska")
    put("no", "nor", "norwegian", "norsk")
    put("da", "dan", "danish", "dansk")
    put("fi", "fin", "finnish", "suomi")
    put("ja", "jpn", "jap", "japanese")
    put("ko", "kor", "korean")
    put("zh", "chi", "zho", "chinese", "mandarin", "cantonese")
    put("hi", "hin", "hindi")
    put("ta", "tam", "tamil")
    put("te", "tel", "telugu")
    put("ml", "mal", "malayalam")
    put("kn", "kan", "kannada")
    put("bn", "ben", "bengali")
    put("mr", "mar", "marathi")
    put("pa", "pan", "punjabi")
    put("ar", "ara", "arabic")
    put("he", "heb", "hebrew")
    put("tr", "tur", "turkish", "turkce")
    put("th", "tha", "thai")
    put("vi", "vie", "vietnamese")
    put("id", "ind", "indonesian")
    put("ms", "may", "msa", "malay")
    put("cs", "cze", "ces", "czech")
    put("sk", "slo", "slk", "slovak")
    put("hu", "hun", "hungarian")
    put("ro", "rum", "ron", "romanian")
    put("bg", "bul", "bulgarian")
    put("el", "gre", "ell", "greek")
    put("sr", "srp", "serbian")
    put("hr", "hrv", "croatian")
    put("fa", "per", "fas", "persian", "farsi")
    put("tl", "tgl", "fil", "tagalog", "filipino")
}

/** The single-word entries of [ReleaseLanguageTokens], for attaching a language to `SUBS`. */
private val SingleWordReleaseLanguages: Map<String, String> = ReleaseLanguageTokens
    .filter { (token, _) -> token.all(Char::isLetterOrDigit) }
    .associate { it }
