package com.nuvio.app.features.playback

import com.nuvio.app.core.language.normalizeLanguageCode
import com.nuvio.app.features.downloads.SourceFacts

/**
 * What the pre-playback surfaces may say about a source's languages, and how sure they are.
 *
 * ## Why this exists
 *
 * The loading band used to print `SourceFacts.languages` verbatim. That set is only ever a positive
 * claim, and English is the unmarked case in release names, so an ordinary English WEB-DL printed
 * nothing at all while a release that happened to carry `ITA` or `VOSTFR` printed a foreign
 * language - the band was most confident exactly where it was least representative. On top of that
 * `VOSTFR` / `LEGENDADO` / `ENG.SUBS` were read as *audio*, and the subtitle half fell back to the
 * audio languages.
 *
 * ## The precedence
 *
 *  1. **Structured source metadata** - a tagged `languages` field from Nuvio, AIOStreams or a plugin.
 *  2. **Release-name tokens** - `ENG`, `HINDI`, flag emoji; `MULTi` stays multi, never a language.
 *  3. **The title's original language**, only when the release says nothing about audio and nothing
 *     contradicts it - a `DUBBED` marker does. This is what lets an English film with no `ENG`
 *     token read English *and* an anime with no token read Japanese: the fallback is the title's
 *     own language, never "English by default".
 *  4. **Unknown.**
 *
 * Subtitles never fall back to the title's language: nothing about a film's origin says which
 * subtitle tracks a particular release carries.
 *
 * Pure and import-light so `scripts/run-pure-suites.sh` executes it.
 */
object SourceLanguageInference {

    enum class Basis { STRUCTURED, RELEASE_NAME, CONTENT_ORIGINAL, UNKNOWN }

    data class Claim(
        val codes: Set<String> = emptySet(),
        /** Several tracks advertised without names - `MULTi`, `DUAL`, `MultiSubs`. */
        val isMulti: Boolean = false,
        val basis: Basis = Basis.UNKNOWN,
    ) {
        val isKnown: Boolean get() = codes.isNotEmpty() || isMulti
    }

    data class Languages(val audio: Claim, val subtitles: Claim)

    fun infer(facts: SourceFacts?, contentOriginalLanguage: String?): Languages =
        Languages(audio = audio(facts, contentOriginalLanguage), subtitles = subtitles(facts))

    fun audio(facts: SourceFacts?, contentOriginalLanguage: String?): Claim {
        if (facts == null) return Claim()
        if (facts.languages.isNotEmpty()) {
            return Claim(
                codes = facts.languages,
                isMulti = facts.isMultiLanguage,
                basis = if (facts.hasStructuredLanguages) Basis.STRUCTURED else Basis.RELEASE_NAME,
            )
        }
        // `MULTi` with no names is an answer - "several, unnamed" - and it contradicts a single
        // original-language guess, so it is returned as-is rather than filled in.
        if (facts.isMultiLanguage) return Claim(isMulti = true, basis = Basis.RELEASE_NAME)
        if (facts.claimsDubbedAudio) return Claim()
        val original = contentLanguageCode(contentOriginalLanguage) ?: return Claim()
        return Claim(codes = setOf(original), basis = Basis.CONTENT_ORIGINAL)
    }

    fun subtitles(facts: SourceFacts?): Claim {
        if (facts == null) return Claim()
        if (facts.subtitleLanguages.isNotEmpty()) {
            return Claim(
                codes = facts.subtitleLanguages + facts.releaseSubtitleLanguages,
                isMulti = facts.claimsMultiSubtitles,
                basis = Basis.STRUCTURED,
            )
        }
        if (facts.releaseSubtitleLanguages.isNotEmpty() || facts.claimsMultiSubtitles) {
            return Claim(
                codes = facts.releaseSubtitleLanguages,
                isMulti = facts.claimsMultiSubtitles,
                basis = Basis.RELEASE_NAME,
            )
        }
        return Claim()
    }

    /**
     * A title's original language as a code, or null when it is not credibly one.
     *
     * Catalogue metadata arrives as `en` from TMDB and as `English` - sometimes `English, Spanish` -
     * from Cinemeta. The first entry is the original; anything that does not normalize to a short
     * code is dropped rather than guessed at.
     */
    fun contentLanguageCode(raw: String?): String? {
        val first = raw?.split(',', '/', '|')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val code = normalizeLanguageCode(first) ?: return null
        return code.takeIf { ContentCodeShape.matches(it) && it != "und" && it != "xx" }
    }

    private val ContentCodeShape = Regex("^[a-z]{2,3}(-[a-z0-9]{2,4})?$")
}
