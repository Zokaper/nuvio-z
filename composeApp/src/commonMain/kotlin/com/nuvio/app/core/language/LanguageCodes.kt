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
        .replace(Regex("\\s+"), " ")
        .trim()

    fun containsAny(vararg values: String): Boolean =
        values.any { value -> tokenized.contains(value) }

    if (containsAny("portuguese", "portugues")) {
        return when {
            containsAny("brazil", "brasil", "brazilian", "brasileiro", "pt br", "ptbr", "pob", "(br)") ->
                "pt-br"
            containsAny("portugal", "european", "europeu", "iberian", "pt pt", "ptpt") ->
                "pt"
            else -> "pt"
        }
    }

    if (containsAny("spanish", "espanol", "castellano")) {
        return if (containsAny("latin", "latino", "latinoamerica", "latinoamericano", "lat am", "latam", "es 419", "es419", "(419)")) {
            "es-419"
        } else {
            "es"
        }
    }

    LanguageCodeAliases[raw]?.let { return it.replace('_', '-').lowercase() }
    LanguageNameAliases[tokenized]?.let { return it }
    LanguageNameAliases.entries
        .sortedByDescending { it.key.length }
        .firstOrNull { (name, _) ->
            tokenized == name ||
                tokenized.startsWith("$name ") ||
                tokenized.endsWith(" $name") ||
                tokenized.contains(" $name ")
        }
        ?.let { return it.value }

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
fun releaseLanguagesIn(text: String?): ReleaseLanguages {
    val lower = text?.lowercase()?.takeIf { it.isNotBlank() } ?: return ReleaseLanguages()
    val codes = mutableSetOf<String>()

    ReleaseLanguageTokens.forEach { (token, code) ->
        if (lower.containsReleaseToken(token)) codes += code
    }
    codes += flagLanguagesIn(text)

    val isMulti = MultiLanguageTokens.any { lower.containsReleaseToken(it) }
    return ReleaseLanguages(codes = codes, isMulti = isMulti)
}

/**
 * Delimiter-bounded, because release names are dot- and underscore-separated rather than spaced.
 * A bare `contains` would find `ara` inside `Sahara` and `ita` inside `Capitals`.
 */
private fun String.containsReleaseToken(token: String): Boolean {
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

private val MultiLanguageTokens = listOf(
    "multi", "multilang", "multilanguage", "multiaudio", "multisub", "multisubs",
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
    put("fr", "fre", "fra", "french", "francais", "truefrench", "vostfr", "vff", "vfq", "vfi")
    put("de", "ger", "deu", "german", "deutsch")
    put("it", "ita", "italian", "italiano")
    put("pt", "por", "portuguese", "portugues")
    put("pt-br", "legendado", "dublado", "brazilian")
    put("ru", "rus", "russian")
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
