package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.lang_afrikaans
import nuvio.composeapp.generated.resources.lang_albanian
import nuvio.composeapp.generated.resources.lang_amharic
import nuvio.composeapp.generated.resources.lang_arabic
import nuvio.composeapp.generated.resources.lang_armenian
import nuvio.composeapp.generated.resources.lang_azerbaijani
import nuvio.composeapp.generated.resources.lang_basque
import nuvio.composeapp.generated.resources.lang_belarusian
import nuvio.composeapp.generated.resources.lang_bengali
import nuvio.composeapp.generated.resources.lang_bosnian
import nuvio.composeapp.generated.resources.lang_bulgarian
import nuvio.composeapp.generated.resources.lang_burmese
import nuvio.composeapp.generated.resources.lang_catalan
import nuvio.composeapp.generated.resources.lang_chinese
import nuvio.composeapp.generated.resources.lang_chinese_simplified
import nuvio.composeapp.generated.resources.lang_chinese_traditional
import nuvio.composeapp.generated.resources.lang_croatian
import nuvio.composeapp.generated.resources.lang_czech
import nuvio.composeapp.generated.resources.lang_danish
import nuvio.composeapp.generated.resources.lang_dutch
import nuvio.composeapp.generated.resources.lang_english
import nuvio.composeapp.generated.resources.lang_estonian
import nuvio.composeapp.generated.resources.lang_filipino
import nuvio.composeapp.generated.resources.lang_finnish
import nuvio.composeapp.generated.resources.lang_french
import nuvio.composeapp.generated.resources.lang_galician
import nuvio.composeapp.generated.resources.lang_georgian
import nuvio.composeapp.generated.resources.lang_german
import nuvio.composeapp.generated.resources.lang_greek
import nuvio.composeapp.generated.resources.lang_gujarati
import nuvio.composeapp.generated.resources.lang_hebrew
import nuvio.composeapp.generated.resources.lang_hindi
import nuvio.composeapp.generated.resources.lang_hungarian
import nuvio.composeapp.generated.resources.lang_icelandic
import nuvio.composeapp.generated.resources.lang_indonesian
import nuvio.composeapp.generated.resources.lang_irish
import nuvio.composeapp.generated.resources.lang_italian
import nuvio.composeapp.generated.resources.lang_japanese
import nuvio.composeapp.generated.resources.lang_kannada
import nuvio.composeapp.generated.resources.lang_kazakh
import nuvio.composeapp.generated.resources.lang_khmer
import nuvio.composeapp.generated.resources.lang_korean
import nuvio.composeapp.generated.resources.lang_lao
import nuvio.composeapp.generated.resources.lang_latvian
import nuvio.composeapp.generated.resources.lang_lithuanian
import nuvio.composeapp.generated.resources.lang_macedonian
import nuvio.composeapp.generated.resources.lang_malay
import nuvio.composeapp.generated.resources.lang_malayalam
import nuvio.composeapp.generated.resources.lang_maltese
import nuvio.composeapp.generated.resources.lang_marathi
import nuvio.composeapp.generated.resources.lang_mongolian
import nuvio.composeapp.generated.resources.lang_nepali
import nuvio.composeapp.generated.resources.lang_norwegian
import nuvio.composeapp.generated.resources.lang_persian
import nuvio.composeapp.generated.resources.lang_polish
import nuvio.composeapp.generated.resources.lang_portuguese_brazil
import nuvio.composeapp.generated.resources.lang_portuguese_portugal
import nuvio.composeapp.generated.resources.lang_punjabi
import nuvio.composeapp.generated.resources.lang_romanian
import nuvio.composeapp.generated.resources.lang_russian
import nuvio.composeapp.generated.resources.lang_serbian
import nuvio.composeapp.generated.resources.lang_sinhala
import nuvio.composeapp.generated.resources.lang_slovak
import nuvio.composeapp.generated.resources.lang_slovenian
import nuvio.composeapp.generated.resources.lang_spanish
import nuvio.composeapp.generated.resources.lang_spanish_latin_america
import nuvio.composeapp.generated.resources.lang_swahili
import nuvio.composeapp.generated.resources.lang_swedish
import nuvio.composeapp.generated.resources.lang_tamil
import nuvio.composeapp.generated.resources.lang_telugu
import nuvio.composeapp.generated.resources.lang_thai
import nuvio.composeapp.generated.resources.lang_turkish
import nuvio.composeapp.generated.resources.lang_ukrainian
import nuvio.composeapp.generated.resources.lang_urdu
import nuvio.composeapp.generated.resources.lang_uzbek
import nuvio.composeapp.generated.resources.lang_vietnamese
import nuvio.composeapp.generated.resources.lang_welsh
import nuvio.composeapp.generated.resources.lang_zulu
import nuvio.composeapp.generated.resources.settings_playback_option_default
import nuvio.composeapp.generated.resources.settings_playback_option_device_language
import nuvio.composeapp.generated.resources.settings_playback_option_forced
import nuvio.composeapp.generated.resources.settings_playback_option_none
import nuvio.composeapp.generated.resources.settings_playback_option_original
import nuvio.composeapp.generated.resources.subtitle_language_unknown
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

data class LanguagePreferenceOption(
    val code: String,
    val labelRes: StringResource,
)

object AudioLanguageOption {
    const val DEFAULT = "default"
    const val DEVICE = "device"
    const val ORIGINAL = "original"
}

object SubtitleLanguageOption {
    const val NONE = "none"
    const val DEVICE = "device"
    const val FORCED = "forced"
}

val AvailableLanguageOptions: List<LanguagePreferenceOption> = listOf(
    LanguagePreferenceOption("af", Res.string.lang_afrikaans),
    LanguagePreferenceOption("sq", Res.string.lang_albanian),
    LanguagePreferenceOption("am", Res.string.lang_amharic),
    LanguagePreferenceOption("ar", Res.string.lang_arabic),
    LanguagePreferenceOption("hy", Res.string.lang_armenian),
    LanguagePreferenceOption("az", Res.string.lang_azerbaijani),
    LanguagePreferenceOption("eu", Res.string.lang_basque),
    LanguagePreferenceOption("be", Res.string.lang_belarusian),
    LanguagePreferenceOption("bn", Res.string.lang_bengali),
    LanguagePreferenceOption("bs", Res.string.lang_bosnian),
    LanguagePreferenceOption("bg", Res.string.lang_bulgarian),
    LanguagePreferenceOption("my", Res.string.lang_burmese),
    LanguagePreferenceOption("ca", Res.string.lang_catalan),
    LanguagePreferenceOption("zh", Res.string.lang_chinese),
    LanguagePreferenceOption("zh-CN", Res.string.lang_chinese_simplified),
    LanguagePreferenceOption("zh-TW", Res.string.lang_chinese_traditional),
    LanguagePreferenceOption("hr", Res.string.lang_croatian),
    LanguagePreferenceOption("cs", Res.string.lang_czech),
    LanguagePreferenceOption("da", Res.string.lang_danish),
    LanguagePreferenceOption("nl", Res.string.lang_dutch),
    LanguagePreferenceOption("en", Res.string.lang_english),
    LanguagePreferenceOption("et", Res.string.lang_estonian),
    LanguagePreferenceOption("tl", Res.string.lang_filipino),
    LanguagePreferenceOption("fi", Res.string.lang_finnish),
    LanguagePreferenceOption("fr", Res.string.lang_french),
    LanguagePreferenceOption("gl", Res.string.lang_galician),
    LanguagePreferenceOption("ka", Res.string.lang_georgian),
    LanguagePreferenceOption("de", Res.string.lang_german),
    LanguagePreferenceOption("el", Res.string.lang_greek),
    LanguagePreferenceOption("gu", Res.string.lang_gujarati),
    LanguagePreferenceOption("he", Res.string.lang_hebrew),
    LanguagePreferenceOption("hi", Res.string.lang_hindi),
    LanguagePreferenceOption("hu", Res.string.lang_hungarian),
    LanguagePreferenceOption("is", Res.string.lang_icelandic),
    LanguagePreferenceOption("id", Res.string.lang_indonesian),
    LanguagePreferenceOption("ga", Res.string.lang_irish),
    LanguagePreferenceOption("it", Res.string.lang_italian),
    LanguagePreferenceOption("ja", Res.string.lang_japanese),
    LanguagePreferenceOption("kn", Res.string.lang_kannada),
    LanguagePreferenceOption("kk", Res.string.lang_kazakh),
    LanguagePreferenceOption("km", Res.string.lang_khmer),
    LanguagePreferenceOption("ko", Res.string.lang_korean),
    LanguagePreferenceOption("lo", Res.string.lang_lao),
    LanguagePreferenceOption("lv", Res.string.lang_latvian),
    LanguagePreferenceOption("lt", Res.string.lang_lithuanian),
    LanguagePreferenceOption("mk", Res.string.lang_macedonian),
    LanguagePreferenceOption("ms", Res.string.lang_malay),
    LanguagePreferenceOption("ml", Res.string.lang_malayalam),
    LanguagePreferenceOption("mt", Res.string.lang_maltese),
    LanguagePreferenceOption("mr", Res.string.lang_marathi),
    LanguagePreferenceOption("mn", Res.string.lang_mongolian),
    LanguagePreferenceOption("ne", Res.string.lang_nepali),
    LanguagePreferenceOption("no", Res.string.lang_norwegian),
    LanguagePreferenceOption("pa", Res.string.lang_punjabi),
    LanguagePreferenceOption("fa", Res.string.lang_persian),
    LanguagePreferenceOption("pl", Res.string.lang_polish),
    LanguagePreferenceOption("pt", Res.string.lang_portuguese_portugal),
    LanguagePreferenceOption("pt-BR", Res.string.lang_portuguese_brazil),
    LanguagePreferenceOption("ro", Res.string.lang_romanian),
    LanguagePreferenceOption("ru", Res.string.lang_russian),
    LanguagePreferenceOption("sr", Res.string.lang_serbian),
    LanguagePreferenceOption("si", Res.string.lang_sinhala),
    LanguagePreferenceOption("sk", Res.string.lang_slovak),
    LanguagePreferenceOption("sl", Res.string.lang_slovenian),
    LanguagePreferenceOption("es", Res.string.lang_spanish),
    LanguagePreferenceOption("es-419", Res.string.lang_spanish_latin_america),
    LanguagePreferenceOption("sw", Res.string.lang_swahili),
    LanguagePreferenceOption("sv", Res.string.lang_swedish),
    LanguagePreferenceOption("ta", Res.string.lang_tamil),
    LanguagePreferenceOption("te", Res.string.lang_telugu),
    LanguagePreferenceOption("th", Res.string.lang_thai),
    LanguagePreferenceOption("tr", Res.string.lang_turkish),
    LanguagePreferenceOption("uk", Res.string.lang_ukrainian),
    LanguagePreferenceOption("ur", Res.string.lang_urdu),
    LanguagePreferenceOption("uz", Res.string.lang_uzbek),
    LanguagePreferenceOption("vi", Res.string.lang_vietnamese),
    LanguagePreferenceOption("cy", Res.string.lang_welsh),
    LanguagePreferenceOption("zu", Res.string.lang_zulu),
)

/**
 * Delegates to [com.nuvio.app.core.language.normalizeLanguageCode].
 *
 * The tables and the parsing moved to `core/language/LanguageCodes.kt` so that source-selection
 * code can use them - this file reaches the generated Compose resource bundle for its localized
 * labels, and `SourceFacts.kt` is compiled outside Gradle where that bundle does not exist.
 * These two names stay here because a dozen player call sites import them from this package and
 * churning them would obscure the move.
 */
fun normalizeLanguageCode(language: String?): String? =
    com.nuvio.app.core.language.normalizeLanguageCode(language)

/** Delegates to [com.nuvio.app.core.language.languageMatchesPreference]. */
fun languageMatchesPreference(trackLanguage: String?, targetLanguage: String): Boolean =
    com.nuvio.app.core.language.languageMatchesPreference(trackLanguage, targetLanguage)

private fun languageLabelResForCode(code: String?): StringResource? {
    val normalized = normalizeLanguageCode(code) ?: return null
    return AvailableLanguageOptions.firstOrNull {
        normalizeLanguageCode(it.code) == normalized
    }?.labelRes
}

@Composable
fun languageLabelForCode(code: String?): String = when {
    code.isNullOrBlank() || code.equals(SubtitleLanguageOption.NONE, ignoreCase = true) ->
        stringResource(Res.string.settings_playback_option_none)
    code.equals(SubtitleLanguageOption.FORCED, ignoreCase = true) ->
        stringResource(Res.string.settings_playback_option_forced)
    code.equals(AudioLanguageOption.DEFAULT, ignoreCase = true) ->
        stringResource(Res.string.settings_playback_option_default)
    code.equals(AudioLanguageOption.DEVICE, ignoreCase = true) ||
        code.equals(SubtitleLanguageOption.DEVICE, ignoreCase = true) ->
        stringResource(Res.string.settings_playback_option_device_language)
    code.equals(AudioLanguageOption.ORIGINAL, ignoreCase = true) ->
        stringResource(Res.string.settings_playback_option_original)
    else -> languageLabelResForCode(code)?.let { stringResource(it) }
        ?: stringResource(Res.string.subtitle_language_unknown)
}

suspend fun getLanguageLabelForCode(code: String?): String = when {
    code.isNullOrBlank() || code.equals(SubtitleLanguageOption.NONE, ignoreCase = true) ->
        getString(Res.string.settings_playback_option_none)
    code.equals(SubtitleLanguageOption.FORCED, ignoreCase = true) ->
        getString(Res.string.settings_playback_option_forced)
    code.equals(AudioLanguageOption.DEFAULT, ignoreCase = true) ->
        getString(Res.string.settings_playback_option_default)
    code.equals(AudioLanguageOption.DEVICE, ignoreCase = true) ||
        code.equals(SubtitleLanguageOption.DEVICE, ignoreCase = true) ->
        getString(Res.string.settings_playback_option_device_language)
    code.equals(AudioLanguageOption.ORIGINAL, ignoreCase = true) ->
        getString(Res.string.settings_playback_option_original)
    else -> languageLabelResForCode(code)?.let { getString(it) }
        ?: getString(Res.string.subtitle_language_unknown)
}

fun resolvePreferredAudioLanguageTargets(
    preferredAudioLanguage: String,
    secondaryPreferredAudioLanguage: String?,
    deviceLanguages: List<String>,
    contentOriginalLanguage: String? = null,
): List<String> {
    fun normalize(language: String?): String? {
        val normalized = normalizeLanguageCode(language)
        return when (normalized) {
            null,
            AudioLanguageOption.DEFAULT,
            AudioLanguageOption.DEVICE,
            SubtitleLanguageOption.NONE,
            SubtitleLanguageOption.FORCED,
            -> null
            AudioLanguageOption.ORIGINAL -> contentOriginalLanguage?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            else -> normalized
        }
    }

    val primary = normalizeLanguageCode(preferredAudioLanguage) ?: AudioLanguageOption.DEVICE

    return when (primary) {
        AudioLanguageOption.DEFAULT -> listOfNotNull(
            normalize(secondaryPreferredAudioLanguage),
        ).distinct()

        AudioLanguageOption.DEVICE -> (
            deviceLanguages.mapNotNull(::normalize)
                + listOfNotNull(normalize(secondaryPreferredAudioLanguage))
            ).distinct()

        AudioLanguageOption.ORIGINAL -> {
            val originalLang = contentOriginalLanguage?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            if (originalLang != null) {
                listOfNotNull(
                    originalLang,
                    normalize(secondaryPreferredAudioLanguage),
                ).distinct()
            } else {
                // Fallback to device languages when original language is unknown
                (deviceLanguages.mapNotNull(::normalize)
                    + listOfNotNull(normalize(secondaryPreferredAudioLanguage))
                ).distinct()
            }
        }

        else -> listOfNotNull(
            normalize(preferredAudioLanguage),
            normalize(secondaryPreferredAudioLanguage),
        ).distinct()
    }
}

fun resolvePreferredSubtitleLanguageTargets(
    preferredSubtitleLanguage: String,
    secondaryPreferredSubtitleLanguage: String?,
    deviceLanguages: List<String>,
): List<String> {
    fun normalize(language: String?): String? {
        val normalized = normalizeLanguageCode(language)
        return when (normalized) {
            null,
            SubtitleLanguageOption.NONE,
            -> null
            AudioLanguageOption.DEFAULT -> null
            else -> normalized
        }
    }

    val primary = normalizeLanguageCode(preferredSubtitleLanguage) ?: SubtitleLanguageOption.NONE

    return when (primary) {
        SubtitleLanguageOption.NONE -> listOfNotNull(
            normalize(secondaryPreferredSubtitleLanguage),
        ).distinct()

        SubtitleLanguageOption.DEVICE -> (
            deviceLanguages.mapNotNull(::normalize)
                + listOfNotNull(normalize(secondaryPreferredSubtitleLanguage))
            ).distinct()

        else -> listOfNotNull(
            normalize(preferredSubtitleLanguage),
            normalize(secondaryPreferredSubtitleLanguage),
        ).distinct()
    }
}

internal expect object DeviceLanguagePreferences {
    fun preferredLanguageCodes(): List<String>
}

fun inferForcedSubtitleTrack(
    label: String?,
    language: String?,
    trackId: String?,
    hasForcedSelectionFlag: Boolean = false,
): Boolean {
    if (hasForcedSelectionFlag) return true

    val normalizedLanguage = normalizeLanguageCode(language)
    if (normalizedLanguage == SubtitleLanguageOption.FORCED) return true

    val text = listOfNotNull(label, language, trackId)
        .joinToString(" ")
        .lowercase()

    if ("forced" in text) return true
    return text.contains("songs") && text.contains("sign")
}

/**
 * Best-effort mapping from country name/code to ISO 639-1 primary language.
 * Used as a fallback when [resolveContentLanguage] has no explicit language field.
 */
fun countryToLanguageCode(country: String?): String? {
    val normalized = country?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    return COUNTRY_TO_LANGUAGE_MAP[normalized]
}

/**
 * Resolves the original content language as an ISO 639-1 code.
 * Falls back to country-based inference when the explicit language field is absent.
 */
fun resolveContentLanguage(language: String?, country: String?): String? {
    normalizeLanguageCode(language)?.let { return it }
    countryToLanguageCode(country)?.let { return it }
    return null
}

private val COUNTRY_TO_LANGUAGE_MAP = mapOf(
    // ISO 3166-1 alpha-2
    "jp" to "ja", "kr" to "ko", "cn" to "zh", "tw" to "zh",
    "fr" to "fr", "de" to "de", "it" to "it", "es" to "es",
    "pt" to "pt", "br" to "pt", "ru" to "ru", "in" to "hi",
    "tr" to "tr", "pl" to "pl", "nl" to "nl", "se" to "sv",
    "no" to "no", "dk" to "da", "fi" to "fi", "th" to "th",
    "il" to "he", "cz" to "cs", "ro" to "ro", "hu" to "hu",
    "ua" to "uk", "gr" to "el",
    // ISO 3166-1 alpha-3
    "jpn" to "ja", "kor" to "ko", "chn" to "zh", "twn" to "zh",
    "fra" to "fr", "deu" to "de", "ita" to "it", "esp" to "es",
    "prt" to "pt", "bra" to "pt", "rus" to "ru", "ind" to "hi",
    "tur" to "tr", "pol" to "pl", "nld" to "nl", "swe" to "sv",
    "nor" to "no", "dnk" to "da", "fin" to "fi", "tha" to "th",
    "isr" to "he", "cze" to "cs", "rou" to "ro", "hun" to "hu",
    "ukr" to "uk", "grc" to "el",
    // Common full names
    "japan" to "ja", "south korea" to "ko", "korea" to "ko",
    "china" to "zh", "taiwan" to "zh", "france" to "fr",
    "germany" to "de", "italy" to "it", "spain" to "es",
    "portugal" to "pt", "brazil" to "pt", "russia" to "ru",
    "india" to "hi", "turkey" to "tr", "poland" to "pl",
    "netherlands" to "nl", "sweden" to "sv", "norway" to "no",
    "denmark" to "da", "finland" to "fi", "thailand" to "th",
    "israel" to "he", "romania" to "ro", "hungary" to "hu",
    "ukraine" to "uk", "greece" to "el",
)
