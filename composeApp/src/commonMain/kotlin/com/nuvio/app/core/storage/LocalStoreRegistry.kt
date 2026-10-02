package com.nuvio.app.core.storage

// No imports, and none may be added: the pure suites compile this file on its own.

/**
 * What a local store holds, which decides what signing out does to it.
 *
 * Sign-out used to be two unrelated hand-written lists - Android's SharedPreferences names and
 * iOS's NSUserDefaults keys - plus desktop deleting its entire data folder. Android's list had
 * drifted fourteen stores behind the code, including the TMDB settings (which carry a personal API
 * key) and the AIOStreams install credentials, so the next account's profile N simply inherited
 * them. Desktop went the other way and wiped device preferences it had promised to keep: interface
 * zoom, app language, window placement, the renderer choice, the device setup revision.
 *
 * The rule now: **everything that belongs to an account or a profile is cleared, every credential
 * is cleared, caches are cleared, and a device preference survives.**
 */
internal enum class LocalStoreClass {
    /** Settings and data of an account or one of its profiles. Cleared. */
    AccountProfile,

    /** Tokens, keys, PINs, sessions. Cleared. */
    Credential,

    /** Derived or fetched data that can be rebuilt. Cleared. */
    Cache,

    /** A preference of this installation, not of whoever is signed in. Kept. */
    DeviceLocal,
}

/**
 * One named store: an Android SharedPreferences file, or a desktop `DesktopStorage` store - the two
 * platforms use the same names.
 *
 * [preservedKeys] are device-level keys living inside an otherwise account-scoped store. They
 * survive a wipe of that store; everything else in it is removed.
 */
internal data class LocalStore(
    val name: String,
    val storeClass: LocalStoreClass,
    val preservedKeys: Set<String> = emptySet(),
) {
    val isWiped: Boolean get() = storeClass != LocalStoreClass.DeviceLocal
}

internal object LocalStoreRegistry {
    private fun account(name: String, vararg preserved: String) =
        LocalStore(name, LocalStoreClass.AccountProfile, preserved.toSet())

    private fun credential(name: String) = LocalStore(name, LocalStoreClass.Credential)

    private fun cache(name: String) = LocalStore(name, LocalStoreClass.Cache)

    private fun device(name: String) = LocalStore(name, LocalStoreClass.DeviceLocal)

    /**
     * ⚠ **A new store must be added here**, or sign-out leaves it behind on Android. The Android host
     * test `LocalStoreRegistryCoverageTest` reads every SharedPreferences name out of the Android
     * sources and fails on one this list does not classify.
     */
    val stores: List<LocalStore> = listOf(
        // Account / profile settings and data
        account("episode_shuffle"),
        account("nuvio_addons"),
        account("nuvio_library"),
        account("nuvio_library_display_settings"),
        account("nuvio_home_catalog_settings"),
        account("nuvio_player_settings"),
        account("nuvio_player_track_preferences"),
        account("nuvio_resume_prompt"),
        account("torrent_settings"),
        account("nuvio_profile_cache"),
        account("nuvio_profiles"),
        // App language is a device choice kept in the theme store; the rest of the store is profile.
        account("nuvio_theme_settings", "selected_app_language"),
        account("nuvio_poster_card_style"),
        account("nuvio_card_depth_style"),
        account("nuvio_custom_poster_url"),
        account("nuvio_debrid_settings"),
        account("nuvio_tmdb_settings"),
        account("nuvio_mdblist_settings"),
        account("nuvio_trakt_settings"),
        account("nuvio_trakt_comments"),
        account("nuvio_watched"),
        account("nuvio_watch_progress"),
        account("nuvio_stream_badge_settings"),
        account("nuvio_continue_watching_preferences"),
        account("nuvio_episode_release_notifications"),
        account("nuvio_episode_release_notifications_platform"),
        account("nuvio_discover_selection"),
        account("nuvio_collection_mobile_settings"),
        account("nuvio_collections"),
        account("nuvio_plugins"),
        account("nuvio_member_access"),
        account("nuvio_meta_screen_settings"),
        account("nuvio_season_view_mode"),
        account("nuvio_search_history"),
        account("nuvio_social"),
        account("nuvio_social_features"),
        account("nuvio_download_policy"),
        // The device revision is this installation's; the per-profile arrival and Advanced Setup
        // flags beside it belong to whichever account is signed in.
        account("nuvio_device_setup", "device_setup_revision"),

        // Credentials
        credential("nuvio_auth"),
        credential("nuvio_trakt_auth"),
        credential("nuvio_simkl_auth"),
        credential("nuvio_mdblist_auth"),
        credential("nuvio_profile_pin_cache"),
        credential("nuvio_aiostreams_credentials"),
        credential("nuvio_official_session"),

        // Caches
        cache("nuvio_avatar_cache"),
        cache("nuvio_avatars"),
        cache("nuvio_mdblist_sync"),
        cache("nuvio_simkl_sync"),
        cache("nuvio_trakt_library"),
        cache("nuvio_stream_link_cache"),
        cache("nuvio_cw_enrichment"),
        cache("nuvio_continue_watching_enrichment"),
        cache("nuvio_binge_group_cache"),

        // Device-local: kept across accounts
        device("nuvio_whats_new"),
        device("nuvio_updater"),
        device("server_configuration"),
        device("nuvio_sync_client_identity"),
        device("nuvio_sentry_settings"),
        device("nuvio_network_quality"),
        // The download queue and its files are this device's; wiping the records would orphan them.
        device("nuvio_downloads"),
        device("nuvio_downloads_android"),
        device("nuvio_download_live_notifications"),
        device("nuvio_window_state"),
        device("nuvio_player_runtime"),
        device("nuvio_desktop_renderer"),
        device("nuvio_app_icon"),
        device("nuvio_discord_rich_presence"),
    )

    private val byName: Map<String, LocalStore> = stores.associateBy { it.name }

    fun find(name: String): LocalStore? = byName[name]

    val wiped: List<LocalStore> get() = stores.filter { it.isWiped }

    /**
     * Whether a desktop store the registry has never heard of is wiped: **yes**. An unclassified store
     * is far more likely to be account data a later change forgot to register than a device
     * preference, and leaving an account's data for the next person is the worse failure.
     */
    fun isWiped(name: String): Boolean = find(name)?.isWiped ?: true
}

// --- iOS: NSUserDefaults keys rather than named stores --------------------------------------------

/**
 * Whether signing out removes the NSUserDefaults key [key] (iOS keeps every store's keys in one
 * domain, so the named registry above cannot describe it).
 *
 * **Profile-scoped means `<base>_<profileIndex>`** - the shape `ProfileScopedKey` writes and every
 * index-keyed payload shares - for a lowercase snake-case base, so Apple's and third-party SDKs'
 * CamelCase keys are never touched. Unscoped account data is named explicitly, and a short list of
 * device keys is never removed even if it happened to match.
 */
internal fun isAccountScopedDefaultsKey(key: String, maxProfiles: Int): Boolean {
    if (key in IOS_DEVICE_KEYS) return false
    if (key in IOS_ACCOUNT_PLAIN_KEYS) return true
    if (IOS_ACCOUNT_PREFIXES.any(key::startsWith)) return true
    val separator = key.lastIndexOf('_')
    if (separator <= 0 || separator == key.lastIndex) return false
    val base = key.substring(0, separator)
    if (base in IOS_DEVICE_KEYS) return false
    val index = key.substring(separator + 1).toIntOrNull() ?: return false
    if (index !in 1..maxProfiles) return false
    return base.first() in 'a'..'z' && base.all { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '.' }
}

/** Unscoped iOS keys holding account data. */
internal val IOS_ACCOUNT_PLAIN_KEYS: Set<String> = setOf(
    "profile_payload",
    "avatar_catalog_payload",
    "anonymous_user_id",
    "member_access_payload",
    "member_background_catalog_payload",
    "installed_manifest_urls",
    "installed_manifest_enabled_states",
    "plugins_state",
    "watch_progress_payload",
    "nuvio_mdblist_sync",
    "NuvioNativeProfileName",
    "NuvioNativeProfileAvatarColor",
    "NuvioNativeProfileAvatarURL",
    "NuvioNativeProfileAvatarBackgroundColor",
)

/** iOS key prefixes holding account data or caches of it. */
internal val IOS_ACCOUNT_PREFIXES: List<String> = listOf(
    "stream_link_",
    "cw_enrichment_cache_",
    "social_state_",
    "social_outbox_",
    "social_abandoned_join_",
    "profile_pin_cache_",
)

/** iOS keys that belong to the installation and are never removed. */
internal val IOS_DEVICE_KEYS: Set<String> = setOf(
    "nuvio_device_setup_revision",
    "nuvio_whats_new_ack_serial",
    "nuvio_whats_new_ack_seq",
    "nuvio_whats_new_ack_seen",
    "nuvio_whats_new_viewed_seq",
    "nuvio_whats_new_viewed_seen",
    "nuvio_whats_new_ack_debug_build",
    "nuvio_whats_new_last_seen_version",
    "nuvio_network_quality_estimates_json",
    "client_instance_id",
    "sentry_enabled",
    "selected_app_language",
    "downloads_payload",
    "downloads_payload_corrupt",
    "downloads_title_metadata",
)
