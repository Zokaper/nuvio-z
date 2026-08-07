package com.nuvio.app.features.player

/**
 * Whether a profile with no stored `settings_show_advanced` value should start with the
 * advanced rows visible.
 *
 * The toggle exists because the settings screens have grown to a few hundred rows and most of
 * them are tuning knobs. Hiding them by default is right for someone who has never opened one
 * - and wrong for someone who has, because a setting they deliberately changed silently
 * disappearing reads as data loss, not as a cleaner screen.
 *
 * So rather than guessing how old an install is, this asks the question that actually matters:
 * **has this profile ever stored a value for an advanced setting?** Every parameter is the
 * nullable result of a `PlayerSettingsStorage.load*` call, where null means "never written".
 *
 * Pure and separate from the repository so the rule can be read and tested on its own; the
 * repository calls it exactly once, when the stored flag is absent.
 */
internal fun hasTunedAnAdvancedSetting(
    allowTorrentAutopick: Boolean?,
    autoDownshift: Boolean?,
    meteredCapHeight: Int?,
    reuseLastLinkEnabled: Boolean?,
    reuseLastLinkCacheHours: Int?,
    streamAutoPlayMode: String?,
    streamAutoPlayRegex: String?,
    androidPlaybackEngine: String?,
    decoderPriority: Int?,
): Boolean = listOf(
    allowTorrentAutopick,
    autoDownshift,
    meteredCapHeight,
    reuseLastLinkEnabled,
    reuseLastLinkCacheHours,
    streamAutoPlayMode,
    streamAutoPlayRegex,
    androidPlaybackEngine,
    decoderPriority,
).any { it != null }
