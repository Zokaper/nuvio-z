package com.nuvio.app.features.downloads

import androidx.compose.runtime.Composable
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.download_preset_codec_any
import nuvio.composeapp.generated.resources.download_preset_codec_av1
import nuvio.composeapp.generated.resources.download_preset_codec_avc
import nuvio.composeapp.generated.resources.download_preset_codec_hevc
import nuvio.composeapp.generated.resources.download_preset_hdr_any
import nuvio.composeapp.generated.resources.download_preset_hdr_avoid
import nuvio.composeapp.generated.resources.download_preset_hdr_prefer
import nuvio.composeapp.generated.resources.download_preset_hdr_require
import nuvio.composeapp.generated.resources.download_preset_hdr_require_dolby_vision
import nuvio.composeapp.generated.resources.download_preset_resolution_label
import nuvio.composeapp.generated.resources.download_preset_size_largest
import nuvio.composeapp.generated.resources.download_preset_size_largest_description
import nuvio.composeapp.generated.resources.download_preset_size_limit_value
import nuvio.composeapp.generated.resources.download_preset_size_mid_range
import nuvio.composeapp.generated.resources.download_preset_size_mid_range_description
import nuvio.composeapp.generated.resources.download_preset_size_smallest
import nuvio.composeapp.generated.resources.download_preset_size_smallest_description
import org.jetbrains.compose.resources.stringResource

/**
 * One set of human-readable preset labels for both preset surfaces.
 *
 * The picker and the editor used to print raw enum names (`AVOID_HDR`) and format
 * their values differently from one another, so a preset read as two different
 * things depending on where it was looked at.
 */

@Composable
internal fun VideoResolution.presetLabel(): String =
    stringResource(Res.string.download_preset_resolution_label, height)

@Composable
internal fun CodecPreference.presetLabel(): String = stringResource(
    when (this) {
        CodecPreference.ANY -> Res.string.download_preset_codec_any
        CodecPreference.HEVC -> Res.string.download_preset_codec_hevc
        CodecPreference.AV1 -> Res.string.download_preset_codec_av1
        CodecPreference.AVC -> Res.string.download_preset_codec_avc
    },
)

@Composable
internal fun DynamicRangePolicy.presetLabel(): String = stringResource(
    when (this) {
        DynamicRangePolicy.ANY -> Res.string.download_preset_hdr_any
        DynamicRangePolicy.AVOID_HDR -> Res.string.download_preset_hdr_avoid
        DynamicRangePolicy.PREFER_HDR -> Res.string.download_preset_hdr_prefer
        DynamicRangePolicy.REQUIRE_HDR -> Res.string.download_preset_hdr_require
        DynamicRangePolicy.REQUIRE_DOLBY_VISION -> Res.string.download_preset_hdr_require_dolby_vision
    },
)

@Composable
internal fun SizePreference.presetLabel(): String = stringResource(
    when (this) {
        SizePreference.LARGEST_UNDER_CAP -> Res.string.download_preset_size_largest
        SizePreference.MID_RANGE -> Res.string.download_preset_size_mid_range
        SizePreference.SMALLEST -> Res.string.download_preset_size_smallest
    },
)

@Composable
internal fun SizePreference.presetDescription(): String = stringResource(
    when (this) {
        SizePreference.LARGEST_UNDER_CAP -> Res.string.download_preset_size_largest_description
        SizePreference.MID_RANGE -> Res.string.download_preset_size_mid_range_description
        SizePreference.SMALLEST -> Res.string.download_preset_size_smallest_description
    },
)

@Composable
internal fun DownloadPreset.sizeLimitLabel(): String =
    stringResource(Res.string.download_preset_size_limit_value, formatGigabytes(gigabytesPerHourLimit))

/**
 * The preset in one line: resolution, cap, codec, dynamic range, size preference.
 *
 * Anything left at "any" is dropped rather than spelled out, so the line only ever
 * carries decisions someone actually made.
 */
@Composable
internal fun DownloadPreset.summaryLine(): String {
    val parts = buildList {
        add(targetResolution.presetLabel())
        add(sizeLimitLabel())
        if (codecPreference != CodecPreference.ANY) add(codecPreference.presetLabel())
        if (dynamicRangePolicy != DynamicRangePolicy.ANY) add(dynamicRangePolicy.presetLabel())
        add(sizePreference.presetLabel())
    }
    return parts.joinToString(" · ")
}

/** Trims the trailing zero off whole caps so a preset reads "8 GB", not "8.0 GB". */
internal fun formatGigabytes(value: Double): String {
    val rounded = (value * 100.0).toLong() / 100.0
    return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
}
