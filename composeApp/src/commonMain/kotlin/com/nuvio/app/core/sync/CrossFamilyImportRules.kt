package com.nuvio.app.core.sync

// No imports, and none may be added: the pure suites compile this file on its own. The JSON work
// that applies these rules is `CrossFamilySettingsImport.kt`.

/**
 * What a profile brings with it the first time it reaches the *other* platform family.
 *
 * The profile settings blob is stored per family - `"mobile"` for Android and iOS, `"desktop"` for
 * desktop, upstream's design on upstream's server - so nothing in it crosses between a phone and a
 * computer. A profile set up on a phone used to look brand-new on desktop and got the whole setup
 * wizard again. The import copies, **once**, the settings that mean the same thing on both families,
 * and nothing else.
 *
 * **An explicit allowlist, never "most of the blob".** Everything not named here stays at this
 * family's value, and the families drift independently afterwards; true shared sync is logged as
 * future work. Deliberately never imported: navigation style and glow, the Liquid Glass tab bar and
 * the desktop sidebar/top bar, the player layout and touch controls, every decoder / engine / HDR
 * output / libass / RTX setting, the external player, subtitle *rendering* (sizes mean different
 * things under different renderers), the quality limit, HDR preference and metered cap (they depend
 * on the device and its connection - Device Setup asks them instead), codec and audio preference
 * (hardware-dependent), stream auto-play rules, tracking sources (they depend on tokens that never
 * leave a device), episode-release alerts (desktop has no notifications), hover preview (desktop
 * only), poster transition and hero trailer playback (Android-only), and Random Episode (mobile
 * only).
 */
internal enum class CrossFamilyImportMode {
    /** Not imported: this family keeps its own value. */
    Skip,

    /** The whole feature is replaced by the other family's. */
    Whole,

    /** Only [CrossFamilyFeatureRule.keys] are copied over this family's value; the rest is kept. */
    OnlyKeys,

    /** Everything except [CrossFamilyFeatureRule.keys] is copied over this family's value. */
    AllButKeys,
}

/**
 * One field of the blob's `features` object. For a field that holds a JSON object - or a string
 * holding one, as several legacy payloads do - [keys] name keys inside it.
 */
internal data class CrossFamilyFeatureRule(
    val field: String,
    val mode: CrossFamilyImportMode,
    val keys: Set<String> = emptySet(),
)

internal object CrossFamilyImportRules {
    const val MOBILE_PLATFORM: String = "mobile"
    const val DESKTOP_PLATFORM: String = "desktop"

    /** The other family, for a family name; the import never crosses to anything but these two. */
    fun otherFamily(platform: String): String? = when (platform) {
        MOBILE_PLATFORM -> DESKTOP_PLATFORM
        DESKTOP_PLATFORM -> MOBILE_PLATFORM
        else -> null
    }

    /**
     * A profile arrives only when the other family has *finished* setup there - revision 8 is the
     * oldest shape the current wizard does not replay in full. Anything older is a profile whose
     * answers this build would ask again anyway.
     */
    const val MIN_IMPORTABLE_REVISION: Int = 8

    const val SETUP_REVISION_KEY: String = "setup_wizard_completed_revision"

    /** The player keys that mean the same thing on every platform. */
    val PLAYER_KEYS: Set<String> = setOf(
        "playback_mode",
        "preferred_audio_language",
        "secondary_preferred_audio_language",
        "preferred_subtitle_language",
        "secondary_preferred_subtitle_language",
        "playback_language_strictness",
        "playback_language_migrated_v1",
        "playback_prefer_embedded_subtitles",
        "subtitle_use_forced_subtitles",
        "subtitle_show_only_preferred_languages",
        "subtitle_strip_sdh",
        "addon_subtitle_startup_mode",
        "skip_intro_enabled",
        "auto_skip_segment_types",
        "auto_skip_movie_credits",
        "auto_skip_post_credits",
        "animeskip_enabled",
        "intro_submit_enabled",
        "stream_auto_play_next_episode_enabled",
        "stream_auto_play_next_episode_fallback_enabled",
        "stream_auto_play_prefer_binge_group",
        "stream_auto_play_reuse_binge_group",
        "next_episode_threshold_mode",
        "next_episode_threshold_percent_v2",
        "next_episode_threshold_minutes_before_end_v2",
        "pause_overlay_enabled",
        "show_loading_overlay",
        "show_parental_guide",
        "settings_show_advanced",
    )

    /**
     * Named so a test can prove none of them is ever copied, whatever the allowlist grows into. Not a
     * complete list of what stays behind - everything not in [PLAYER_KEYS] stays - but the keys whose
     * import would be a real fault.
     */
    val NEVER_IMPORTED_PLAYER_KEYS: Set<String> = setOf(
        SETUP_REVISION_KEY,
        "playback_quality_ceiling_mbps",
        "playback_dynamic_range_policy",
        "playback_metered_cap_height",
        "playback_codec_preference",
        "playback_audio_preference",
        "playback_allow_torrent_autopick",
        "use_legacy_player_layout",
        "touch_gestures_enabled",
        "hold_to_speed_enabled",
        "hold_to_speed_value",
        "external_player_enabled",
        "external_player_id",
        "resize_mode",
        "android_playback_engine",
        "android_libmpv_hardware_decoding_enabled",
        "decoder_priority",
        "use_libass",
        "ios_video_output_preset",
        "ios_hardware_decoder_mode",
        "subtitle_font_size_sp",
        "subtitle_bottom_offset",
        "stream_auto_play_mode",
        "stream_auto_play_regex",
        "nvidia_rtx_super_resolution_enabled",
        "show_player_loading_status",
    )

    val THEME_KEYS: Set<String> = setOf("selected_theme", "custom_theme_colors", "amoled_enabled")

    val NEVER_IMPORTED_THEME_KEYS: Set<String> = setOf(
        "nav_bar_style",
        "nav_bar_glow_enabled",
        "liquid_glass_native_tab_bar_enabled",
        "desktop_navigation_layout",
    )

    val POSTER_KEYS: Set<String> = setOf(
        "widthDp",
        "heightDp",
        "cornerRadiusDp",
        "catalogLandscapeModeEnabled",
        "hideLabelsEnabled",
    )

    /** Detail-page settings that exist on one platform only. */
    val META_SCREEN_PLATFORM_KEYS: Set<String> = setOf("hero_trailer_playback", "poster_transition_enabled")

    /** `z_features` entries that mean the same thing everywhere. Random Episode is mobile-only. */
    val Z_FEATURE_KEYS: Set<String> = setOf("home_presentation")

    val rules: List<CrossFamilyFeatureRule> = listOf(
        CrossFamilyFeatureRule("theme_settings", CrossFamilyImportMode.OnlyKeys, THEME_KEYS),
        CrossFamilyFeatureRule("poster_card_style_settings_payload", CrossFamilyImportMode.OnlyKeys, POSTER_KEYS),
        CrossFamilyFeatureRule("custom_poster_url_pattern", CrossFamilyImportMode.Whole),
        CrossFamilyFeatureRule("custom_poster_enabled_screens", CrossFamilyImportMode.Whole),
        CrossFamilyFeatureRule("card_depth_style_settings_payload", CrossFamilyImportMode.Whole),
        CrossFamilyFeatureRule("player_settings", CrossFamilyImportMode.OnlyKeys, PLAYER_KEYS),
        CrossFamilyFeatureRule("stream_badge_settings", CrossFamilyImportMode.Whole),
        CrossFamilyFeatureRule("debrid_settings", CrossFamilyImportMode.Whole),
        CrossFamilyFeatureRule("tmdb_settings", CrossFamilyImportMode.Whole),
        CrossFamilyFeatureRule("mdblist_settings", CrossFamilyImportMode.Whole),
        CrossFamilyFeatureRule("meta_screen_settings_payload", CrossFamilyImportMode.AllButKeys, META_SCREEN_PLATFORM_KEYS),
        CrossFamilyFeatureRule("collection_mobile_settings_payload", CrossFamilyImportMode.Whole),
        CrossFamilyFeatureRule("continue_watching_settings_payload", CrossFamilyImportMode.Whole),
        CrossFamilyFeatureRule("trakt_settings_payload", CrossFamilyImportMode.Skip),
        CrossFamilyFeatureRule("trakt_comments_settings", CrossFamilyImportMode.Whole),
        CrossFamilyFeatureRule("notifications_settings", CrossFamilyImportMode.Skip),
        CrossFamilyFeatureRule("social_features", CrossFamilyImportMode.Whole),
        CrossFamilyFeatureRule("download_policy", CrossFamilyImportMode.Whole),
        CrossFamilyFeatureRule("z_features", CrossFamilyImportMode.OnlyKeys, Z_FEATURE_KEYS),
    )

    fun rule(field: String): CrossFamilyFeatureRule =
        rules.firstOrNull { it.field == field } ?: CrossFamilyFeatureRule(field, CrossFamilyImportMode.Skip)

    /**
     * The revision this family records for an imported profile: the other family's, never above what
     * this build knows. A profile finished at revision 8 or 9 then gets this build's normal Upgrade
     * steps; one finished by a newer build is not dragged backwards past what this build can ask.
     */
    fun importedRevision(otherFamilyRevision: Int?, currentRevision: Int): Int? =
        otherFamilyRevision
            ?.takeIf { it >= MIN_IMPORTABLE_REVISION }
            ?.let { minOf(it, currentRevision) }
}

/** Why an import did or did not happen. The Welcome step's retry line reads [TransportFailure]. */
enum class CrossFamilyImportOutcome {
    /** Imported: the profile arrives with its preferences, and Device Setup runs. */
    Imported,

    /** This family already has a blob for the profile, so nothing is ever imported over it. */
    FamilyAlreadyUsed,

    /** The other family has no finished setup to bring. Initial Setup, as before. */
    NothingToImport,

    /** Signed out, anonymous or not configured: there is no account to import from. */
    NotEligible,

    /**
     * The other family could not be reached (network or server). Distinct from [NothingToImport]:
     * the Welcome step offers **Try again**, because a phone-configured profile would otherwise be
     * set up from scratch on desktop for want of one request.
     */
    TransportFailure,
}
