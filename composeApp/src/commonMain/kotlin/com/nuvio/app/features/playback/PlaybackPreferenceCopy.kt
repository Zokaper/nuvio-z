package com.nuvio.app.features.playback

import androidx.compose.runtime.Composable
import com.nuvio.app.features.downloads.DynamicRangePolicy
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.playback_hdr_any_detail
import nuvio.composeapp.generated.resources.playback_hdr_avoid_detail
import nuvio.composeapp.generated.resources.playback_hdr_prefer_detail
import nuvio.composeapp.generated.resources.playback_hdr_require_detail
import nuvio.composeapp.generated.resources.playback_hdr_require_dv_detail
import nuvio.composeapp.generated.resources.playback_language_off_short
import nuvio.composeapp.generated.resources.playback_language_off_detail
import nuvio.composeapp.generated.resources.playback_language_prefer_short
import nuvio.composeapp.generated.resources.playback_language_prefer_detail
import nuvio.composeapp.generated.resources.playback_language_require_short
import nuvio.composeapp.generated.resources.playback_language_require_detail
import nuvio.composeapp.generated.resources.playback_quality_limit_auto
import nuvio.composeapp.generated.resources.playback_quality_limit_auto_short
import nuvio.composeapp.generated.resources.playback_quality_limit_auto_instant_detail
import nuvio.composeapp.generated.resources.playback_quality_limit_auto_streamlined_detail
import nuvio.composeapp.generated.resources.playback_quality_limit_balanced
import nuvio.composeapp.generated.resources.playback_quality_limit_balanced_detail
import nuvio.composeapp.generated.resources.playback_quality_limit_high
import nuvio.composeapp.generated.resources.playback_quality_limit_high_detail
import nuvio.composeapp.generated.resources.playback_quality_limit_mbps
import nuvio.composeapp.generated.resources.playback_quality_limit_named
import nuvio.composeapp.generated.resources.playback_quality_limit_saver
import nuvio.composeapp.generated.resources.playback_quality_limit_saver_detail
import nuvio.composeapp.generated.resources.playback_quality_limit_very_high
import nuvio.composeapp.generated.resources.playback_quality_limit_very_high_detail
import nuvio.composeapp.generated.resources.settings_playback_quality_ceiling_value
import org.jetbrains.compose.resources.stringResource

/*
 * Nuvio Z: the words for the three automatic-choice preferences - the quality limit, audio
 * language matching and HDR - in the setup wizard and in Settings (wizard polish, 2026-09-27).
 *
 * ⚠ **Names only; the stored values and the engine are untouched.** The ceiling is still
 * `playback_quality_ceiling_mbps` in {0, 10, 20, 35, 60} and still filters by credible bitrate in
 * `PlaybackQualityOptions.build`; the language rule is still `LanguageStrictness`; HDR is still
 * `DynamicRangePolicy`. What changed is that a bitrate is no longer the headline - nobody has an
 * intuition for "20 Mb/s" - so each step is named for what it admits, with the Mb/s secondary.
 */

/** The quality-limit steps in the order the wizard shows them: no cap first, then tightest to loosest. */
val PlaybackQualityLimitOrder: List<Int> = listOf(0, 10, 20, 35, 60)

/**
 * The step's name. 0 is **Automatic**, not "No limit": there is no cap, so in Instant the measured
 * connection alone decides and in Streamlined every quality stays on offer. A value that is not one
 * of the steps (a build with a different ladder) is named by its bitrate rather than snapped.
 */
@Composable
fun playbackQualityLimitName(mbps: Int): String = when (mbps) {
    0 -> stringResource(Res.string.playback_quality_limit_auto)
    10 -> stringResource(Res.string.playback_quality_limit_saver)
    20 -> stringResource(Res.string.playback_quality_limit_balanced)
    35 -> stringResource(Res.string.playback_quality_limit_high)
    60 -> stringResource(Res.string.playback_quality_limit_very_high)
    else -> if (mbps <= 0) {
        stringResource(Res.string.playback_quality_limit_auto)
    } else {
        stringResource(Res.string.settings_playback_quality_ceiling_value, mbps)
    }
}

/**
 * The step's name where five sit side by side on a phone: "Auto" for Automatic (the word YouTube's
 * quality menu uses), the rest as [playbackQualityLimitName]. "Automatic" does not fit a fifth of a
 * 360 dp screen without breaking mid-word.
 */
@Composable
fun playbackQualityLimitShortName(mbps: Int): String =
    if (mbps <= 0) stringResource(Res.string.playback_quality_limit_auto_short) else playbackQualityLimitName(mbps)

/** "10 Mb/s" under a step's name, or nothing for Automatic. */
@Composable
fun playbackQualityLimitRate(mbps: Int): String? =
    if (mbps <= 0) null else stringResource(Res.string.playback_quality_limit_mbps, mbps)

/** One line: "Balanced · up to 20 Mb/s" - where a list has room for a single label (Settings). */
@Composable
fun playbackQualityLimitLabel(mbps: Int): String {
    if (mbps <= 0 || mbps !in PlaybackQualityLimitOrder) return playbackQualityLimitName(mbps)
    return stringResource(Res.string.playback_quality_limit_named, playbackQualityLimitName(mbps), mbps)
}

/**
 * What a step lets through, in the words of the releases it keeps. [instant] only changes
 * Automatic's sentence: with no cap, Instant follows the connection while Streamlined offers
 * everything.
 */
@Composable
fun playbackQualityLimitDetail(mbps: Int, instant: Boolean): String = when (mbps) {
    10 -> stringResource(Res.string.playback_quality_limit_saver_detail)
    20 -> stringResource(Res.string.playback_quality_limit_balanced_detail)
    35 -> stringResource(Res.string.playback_quality_limit_high_detail)
    60 -> stringResource(Res.string.playback_quality_limit_very_high_detail)
    else -> stringResource(
        if (instant) {
            Res.string.playback_quality_limit_auto_instant_detail
        } else {
            Res.string.playback_quality_limit_auto_streamlined_detail
        },
    )
}

/** "Any" / "Prefer" / "Require" - short enough for one segmented row under "Your language". */
@Composable
fun playbackLanguageStrictnessShort(strictness: LanguageStrictness): String = when (strictness) {
    LanguageStrictness.OFF -> stringResource(Res.string.playback_language_off_short)
    LanguageStrictness.PREFER -> stringResource(Res.string.playback_language_prefer_short)
    LanguageStrictness.REQUIRE -> stringResource(Res.string.playback_language_require_short)
}

/**
 * What each language rule does. ⚠ REQUIRE **demotes, it does not delete**
 * (`PlaybackSourceSelector.byLanguage`): a release without your audio or subtitles is still tried
 * last if nothing else works, so the sentence says exactly that and never "only".
 */
@Composable
fun playbackLanguageStrictnessDetail(strictness: LanguageStrictness): String = when (strictness) {
    LanguageStrictness.OFF -> stringResource(Res.string.playback_language_off_detail)
    LanguageStrictness.PREFER -> stringResource(Res.string.playback_language_prefer_detail)
    LanguageStrictness.REQUIRE -> stringResource(Res.string.playback_language_require_detail)
}

/** The everyday HDR choices; the two strict rules are offered separately, as secondary options. */
val PlaybackHdrEverydayChoices: List<DynamicRangePolicy> =
    listOf(DynamicRangePolicy.ANY, DynamicRangePolicy.AVOID_HDR, DynamicRangePolicy.PREFER_HDR)

val PlaybackHdrStrictChoices: List<DynamicRangePolicy> =
    listOf(DynamicRangePolicy.REQUIRE_HDR, DynamicRangePolicy.REQUIRE_DOLBY_VISION)

/**
 * What each HDR rule does. ⚠ The strict rules rank an unmatched release last
 * (`SourceRanking.dynamicRangeScore` -> `UNSATISFIED_REQUIREMENT`) rather than refusing it, which is
 * what their sentence says.
 */
@Composable
fun playbackDynamicRangeDetail(policy: DynamicRangePolicy): String = when (policy) {
    DynamicRangePolicy.ANY -> stringResource(Res.string.playback_hdr_any_detail)
    DynamicRangePolicy.AVOID_HDR -> stringResource(Res.string.playback_hdr_avoid_detail)
    DynamicRangePolicy.PREFER_HDR -> stringResource(Res.string.playback_hdr_prefer_detail)
    DynamicRangePolicy.REQUIRE_HDR -> stringResource(Res.string.playback_hdr_require_detail)
    DynamicRangePolicy.REQUIRE_DOLBY_VISION -> stringResource(Res.string.playback_hdr_require_dv_detail)
}
