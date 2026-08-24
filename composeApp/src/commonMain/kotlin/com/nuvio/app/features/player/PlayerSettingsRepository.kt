package com.nuvio.app.features.player

import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.player.skip.NextEpisodeThresholdMode
import com.nuvio.app.features.downloads.AudioPreference
import com.nuvio.app.features.downloads.CodecPreference
import com.nuvio.app.features.downloads.DynamicRangePolicy
import com.nuvio.app.features.playback.LanguageStrictness
import com.nuvio.app.features.playback.PlaybackMode
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamAutoPlaySource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlin.math.abs

val STREAM_AUTO_PLAY_TIMEOUT_VALUES: List<Int> = listOf(
    0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 15, 20, 25, 30, Int.MAX_VALUE
)

/**
 * Snaps [value] to the nearest allowed timeout value in [STREAM_AUTO_PLAY_TIMEOUT_VALUES].
 * Ties break to the lower value. Negative values snap to 0.
 */
fun snapToAllowedTimeout(value: Int): Int {
    if (value <= 0) return 0
    var bestValue = STREAM_AUTO_PLAY_TIMEOUT_VALUES[0]
    var bestDistance = Long.MAX_VALUE
    for (allowed in STREAM_AUTO_PLAY_TIMEOUT_VALUES) {
        val distance = abs(value.toLong() - allowed.toLong())
        if (distance < bestDistance || (distance == bestDistance && allowed < bestValue)) {
            bestDistance = distance
            bestValue = allowed
        }
    }
    return bestValue
}

data class PlayerSettingsUiState(
    val showLoadingOverlay: Boolean = true,
    val showParentalGuide: Boolean = true,
    val resizeMode: PlayerResizeMode = PlayerResizeMode.Fit,
    val holdToSpeedEnabled: Boolean = true,
    val holdToSpeedValue: Float = 2f,
    val touchGesturesEnabled: Boolean = true,
    val externalPlayerEnabled: Boolean = false,
    val externalPlayerForwardSubtitles: Boolean = false,
    val externalPlayerSendSkipSegments: Boolean = false,
    val externalPlayerId: String? = ExternalPlayerPlatform.defaultPlayerId(),
    val preferredAudioLanguage: String = AudioLanguageOption.DEVICE,
    val secondaryPreferredAudioLanguage: String? = null,
    val preferredSubtitleLanguage: String = SubtitleLanguageOption.NONE,
    val secondaryPreferredSubtitleLanguage: String? = null,
    val subtitleStyle: SubtitleStyleState = SubtitleStyleState.DEFAULT,
    val addonSubtitleStartupMode: AddonSubtitleStartupMode = AddonSubtitleStartupMode.ALL_SUBTITLES,
    val streamReuseLastLinkEnabled: Boolean = false,
    val streamReuseLastLinkCacheHours: Int = 24,
    val androidPlaybackEngine: AndroidPlaybackEngine = AndroidPlaybackEngine.Auto,
    val androidLibmpvVideoOutput: AndroidLibmpvVideoOutput = AndroidLibmpvVideoOutput.GpuNext,
    val androidLibmpvHardwareDecodingEnabled: Boolean = true,
    val androidLibmpvYuv420pEnabled: Boolean = false,
    val decoderPriority: Int = 1,
    val mapDV7ToHevc: Boolean = false,
    val tunnelingEnabled: Boolean = false,
    val playbackMode: PlaybackMode = PlaybackMode.Default,
    val playbackAllowTorrentAutopick: Boolean = false,
    /**
     * What the *playback* picker should prefer, as distinct from what a download preset does.
     *
     * These existed only on `DownloadPreset` until 0.5.0-beta, so a user who set a codec or an
     * HDR policy got it honoured for the episodes they downloaded and silently ignored for
     * every episode they watched. `ANY` means "no opinion" and leaves the by-resolution
     * default in `PlaybackQualityOptions.preferencesFor` in charge.
     */
    val playbackCodecPreference: CodecPreference = CodecPreference.ANY,
    val playbackDynamicRangePolicy: DynamicRangePolicy = DynamicRangePolicy.ANY,
    /**
     * What the automatic picker should want out of a release's audio track.
     *
     * There was no source of truth for audio at all until this: `SourceFacts` parsed none,
     * so a 95 GB HDR remux carrying a lossy track beat a lossless release outright for a
     * user who wanted both. Channels feed the same score without a knob of their own.
     */
    val playbackAudioPreference: AudioPreference = AudioPreference.ANY,
    /**
     * How hard Streamlined tries to honour [preferredAudioLanguage] when picking a source.
     *
     * Defaults to REQUIRE, which is unusual for a preference and deliberate: the reported
     * failure is being handed a source with no audio or subtitles the user can follow, and a
     * soft preference is what produced it.
     */
    val playbackLanguageStrictness: LanguageStrictness = LanguageStrictness.REQUIRE,
    /** Megabits per second the automatic picker may not exceed. `0` means no ceiling. */
    val playbackQualityCeilingMbps: Int = 0,
    val showAdvancedSettings: Boolean = false,
    val playbackMeteredCapHeight: Int = 720,
    /**
     * Instant's opt-in automatic source downshift. Off by default: it trades a visible
     * 1-3s hiccup for avoiding a stall, which is only worth it under sustained starvation.
     */
    val playbackAutoDownshift: Boolean = false,
    /** False until the first-launch mode selector has been answered or dismissed. */
    val playbackModeSelectorSeen: Boolean = false,
    /**
     * Highest setup-wizard revision this profile has finished; 0 means never.
     *
     * Read through `shouldShowSetupWizard` in `features/setup/SetupWizardSteps.kt` rather
     * than compared here - that function owns the downgrade rule.
     */
    val setupWizardCompletedRevision: Int = 0,
    val streamAutoPlayMode: StreamAutoPlayMode = StreamAutoPlayMode.MANUAL,
    val streamAutoPlaySource: StreamAutoPlaySource = StreamAutoPlaySource.ALL_SOURCES,
    val streamAutoPlaySelectedAddons: Set<String> = emptySet(),
    val streamAutoPlaySelectedPlugins: Set<String> = emptySet(),
    val streamAutoPlayRegex: String = "",
    val streamAutoPlayTimeoutSeconds: Int = 3,
    val skipIntroEnabled: Boolean = true,
    val animeSkipEnabled: Boolean = false,
    val animeSkipClientId: String = "",
    val introDbApiKey: String = "",
    val introSubmitEnabled: Boolean = false,
    val streamAutoPlayNextEpisodeEnabled: Boolean = false,
    val streamAutoPlayNextEpisodeFallbackEnabled: Boolean = true,
    val streamAutoPlayPreferBingeGroup: Boolean = true,
    val streamAutoPlayReuseBingeGroup: Boolean = true,
    val nextEpisodeThresholdMode: NextEpisodeThresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
    val nextEpisodeThresholdPercent: Float = 99f,
    val nextEpisodeThresholdMinutesBeforeEnd: Float = 2f,
    val useLibass: Boolean = false,
    val libassRenderType: String = "CUES",
    val iosVideoOutputPreset: IosVideoOutputPreset = IosVideoOutputPreset.NativeEdr,
    val iosToneMappingMode: IosToneMappingMode = IosToneMappingMode.Auto,
    val iosTargetPrimaries: IosTargetPrimaries = IosTargetPrimaries.Auto,
    val iosTargetTransfer: IosTargetTransfer = IosTargetTransfer.Auto,
    val iosHardwareDecoderMode: IosHardwareDecoderMode = IosHardwareDecoderMode.VideoToolbox,
    val iosAudioOutputMode: IosAudioOutputMode = IosAudioOutputMode.Auto,
    val iosExtendedDynamicRangeEnabled: Boolean = true,
    val iosTargetColorspaceHintEnabled: Boolean = true,
    val iosHdrComputePeakEnabled: Boolean = true,
    val iosDebandEnabled: Boolean = false,
    val iosInterpolationEnabled: Boolean = false,
    val iosBrightness: Int = 0,
    val iosContrast: Int = 0,
    val iosSaturation: Int = 0,
    val iosGamma: Int = 0,
) {
    /**
     * [preferredAudioLanguage] as something a *release* can be ranked against, or null.
     *
     * The stored value doubles as an instruction to the player's own track selection, and
     * three of its values - `default`, `device`, `original` - name no language at all.
     * Passing one of those to `SourceRanking` matches nothing, which looks exactly like the
     * preference not being wired up: the defect this exists to close. Resolved in one place
     * because two callers build a `PlaybackSelectionContext`, and a rule applied in one of
     * them is a rule that holds for the first episode and not the next one.
     */
    val rankableAudioLanguage: String?
        get() = preferredAudioLanguage.rankableLanguageOrNull()

    /** [secondaryPreferredAudioLanguage], under exactly [rankableAudioLanguage]'s rules. */
    val rankableSecondaryAudioLanguage: String?
        get() = secondaryPreferredAudioLanguage?.rankableLanguageOrNull()

    private fun String.rankableLanguageOrNull(): String? = takeIf {
        it.isNotBlank() &&
            it != AudioLanguageOption.DEFAULT &&
            it != AudioLanguageOption.DEVICE &&
            it != AudioLanguageOption.ORIGINAL
    }
}

object PlayerSettingsRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val _uiState = MutableStateFlow(PlayerSettingsUiState())
    val uiState: StateFlow<PlayerSettingsUiState> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var showLoadingOverlay = true
    private var showParentalGuide = true
    private var resizeMode = PlayerResizeMode.Fit
    private var holdToSpeedEnabled = true
    private var holdToSpeedValue = 2f
    private var touchGesturesEnabled = true
    private var externalPlayerEnabled = false
    private var externalPlayerForwardSubtitles = false
    private var externalPlayerSendSkipSegments = false
    private var externalPlayerId: String? = ExternalPlayerPlatform.defaultPlayerId()
    private var preferredAudioLanguage = AudioLanguageOption.DEVICE
    private var secondaryPreferredAudioLanguage: String? = null
    private var preferredSubtitleLanguage = SubtitleLanguageOption.NONE
    private var secondaryPreferredSubtitleLanguage: String? = null
    private var subtitleStyle = SubtitleStyleState.DEFAULT
    private var addonSubtitleStartupMode = AddonSubtitleStartupMode.ALL_SUBTITLES
    private var streamReuseLastLinkEnabled = false
    private var streamReuseLastLinkCacheHours = 24
    private var androidPlaybackEngine = AndroidPlaybackEngine.Auto
    private var androidLibmpvVideoOutput = AndroidLibmpvVideoOutput.GpuNext
    private var androidLibmpvHardwareDecodingEnabled = true
    private var androidLibmpvYuv420pEnabled = false
    private var decoderPriority = 1
    private var mapDV7ToHevc = false
    private var tunnelingEnabled = false
    private var playbackMode = PlaybackMode.Default
    private var playbackAllowTorrentAutopick = false
    private var playbackCodecPreference = CodecPreference.ANY
    private var playbackDynamicRangePolicy = DynamicRangePolicy.ANY
    private var playbackAudioPreference = AudioPreference.ANY
    private var playbackLanguageStrictness = LanguageStrictness.REQUIRE
    private var playbackQualityCeilingMbps = 0
    private var showAdvancedSettings = false
    private var playbackMeteredCapHeight = 720
    private var playbackAutoDownshift = false
    private var playbackModeSelectorSeen = false
    private var setupWizardCompletedRevision = 0
    private var streamAutoPlayMode = StreamAutoPlayMode.MANUAL
    private var streamAutoPlaySource = StreamAutoPlaySource.ALL_SOURCES
    private var streamAutoPlaySelectedAddons: Set<String> = emptySet()
    private var streamAutoPlaySelectedPlugins: Set<String> = emptySet()
    private var streamAutoPlayRegex = ""
    private var streamAutoPlayTimeoutSeconds = 3
    private var skipIntroEnabled = true
    private var animeSkipEnabled = false
    private var animeSkipClientId = ""
    private var introDbApiKey = ""
    private var introSubmitEnabled = false
    private var streamAutoPlayNextEpisodeEnabled = false
    private var streamAutoPlayNextEpisodeFallbackEnabled = true
    private var streamAutoPlayPreferBingeGroup = true
    private var streamAutoPlayReuseBingeGroup = true
    private var nextEpisodeThresholdMode = NextEpisodeThresholdMode.PERCENTAGE
    private var nextEpisodeThresholdPercent = 99f
    private var nextEpisodeThresholdMinutesBeforeEnd = 2f
    private var useLibass = false
    private var libassRenderType = "CUES"
    private var iosVideoOutputPreset = IosVideoOutputPreset.NativeEdr
    private var iosToneMappingMode = IosToneMappingMode.Auto
    private var iosTargetPrimaries = IosTargetPrimaries.Auto
    private var iosTargetTransfer = IosTargetTransfer.Auto
    private var iosHardwareDecoderMode = IosHardwareDecoderMode.VideoToolbox
    private var iosAudioOutputMode = IosAudioOutputMode.Auto
    private var iosExtendedDynamicRangeEnabled = true
    private var iosTargetColorspaceHintEnabled = true
    private var iosHdrComputePeakEnabled = true
    private var iosDebandEnabled = false
    private var iosInterpolationEnabled = false
    private var iosBrightness = 0
    private var iosContrast = 0
    private var iosSaturation = 0
    private var iosGamma = 0

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun clearLocalState() {
        hasLoaded = false
        showLoadingOverlay = true
        showParentalGuide = true
        resizeMode = PlayerResizeMode.Fit
        holdToSpeedEnabled = true
        holdToSpeedValue = 2f
        touchGesturesEnabled = true
        externalPlayerEnabled = false
        externalPlayerForwardSubtitles = false
        externalPlayerSendSkipSegments = false
        externalPlayerId = ExternalPlayerPlatform.defaultPlayerId()
        preferredAudioLanguage = AudioLanguageOption.DEVICE
        secondaryPreferredAudioLanguage = null
        preferredSubtitleLanguage = SubtitleLanguageOption.NONE
        secondaryPreferredSubtitleLanguage = null
        subtitleStyle = SubtitleStyleState.DEFAULT
        addonSubtitleStartupMode = AddonSubtitleStartupMode.ALL_SUBTITLES
        streamReuseLastLinkEnabled = false
        streamReuseLastLinkCacheHours = 24
        androidPlaybackEngine = AndroidPlaybackEngine.Auto
        androidLibmpvVideoOutput = AndroidLibmpvVideoOutput.GpuNext
        androidLibmpvHardwareDecodingEnabled = true
        androidLibmpvYuv420pEnabled = false
        decoderPriority = 1
        mapDV7ToHevc = false
        tunnelingEnabled = false
        playbackMode = PlaybackMode.Default
        playbackAllowTorrentAutopick = false
        playbackCodecPreference = CodecPreference.ANY
        playbackDynamicRangePolicy = DynamicRangePolicy.ANY
        playbackAudioPreference = AudioPreference.ANY
        playbackLanguageStrictness = LanguageStrictness.REQUIRE
        playbackQualityCeilingMbps = 0
        showAdvancedSettings = false
        playbackMeteredCapHeight = 720
        playbackAutoDownshift = false
        playbackModeSelectorSeen = false
        setupWizardCompletedRevision = 0
        streamAutoPlayMode = StreamAutoPlayMode.MANUAL
        streamAutoPlaySource = StreamAutoPlaySource.ALL_SOURCES
        streamAutoPlaySelectedAddons = emptySet()
        streamAutoPlaySelectedPlugins = emptySet()
        streamAutoPlayRegex = ""
        streamAutoPlayTimeoutSeconds = 3
        skipIntroEnabled = true
        animeSkipEnabled = false
        animeSkipClientId = ""
        introDbApiKey = ""
        introSubmitEnabled = false
        streamAutoPlayNextEpisodeEnabled = false
        streamAutoPlayNextEpisodeFallbackEnabled = true
        streamAutoPlayPreferBingeGroup = true
        streamAutoPlayReuseBingeGroup = true
        nextEpisodeThresholdMode = NextEpisodeThresholdMode.PERCENTAGE
        nextEpisodeThresholdPercent = 99f
        nextEpisodeThresholdMinutesBeforeEnd = 2f
        useLibass = false
        libassRenderType = "CUES"
        iosVideoOutputPreset = IosVideoOutputPreset.NativeEdr
        iosToneMappingMode = IosToneMappingMode.Auto
        iosTargetPrimaries = IosTargetPrimaries.Auto
        iosTargetTransfer = IosTargetTransfer.Auto
        iosHardwareDecoderMode = IosHardwareDecoderMode.VideoToolbox
        iosAudioOutputMode = IosAudioOutputMode.Auto
        iosExtendedDynamicRangeEnabled = true
        iosTargetColorspaceHintEnabled = true
        iosHdrComputePeakEnabled = true
        iosDebandEnabled = false
        iosInterpolationEnabled = false
        iosBrightness = 0
        iosContrast = 0
        iosSaturation = 0
        iosGamma = 0
        publish()
    }

    private fun loadFromDisk() {
        hasLoaded = true
        showLoadingOverlay = PlayerSettingsStorage.loadShowLoadingOverlay() ?: true
        showParentalGuide = PlayerSettingsStorage.loadShowParentalGuide() ?: true
        resizeMode = PlayerSettingsStorage.loadResizeMode()
            ?.let { runCatching { PlayerResizeMode.valueOf(it) }.getOrNull() }
            ?: PlayerResizeMode.Fit
        holdToSpeedEnabled = PlayerSettingsStorage.loadHoldToSpeedEnabled() ?: true
        holdToSpeedValue = PlayerSettingsStorage.loadHoldToSpeedValue() ?: 2f
        touchGesturesEnabled = PlayerSettingsStorage.loadTouchGesturesEnabled() ?: true
        externalPlayerEnabled = PlayerSettingsStorage.loadExternalPlayerEnabled() ?: false
        externalPlayerForwardSubtitles = PlayerSettingsStorage.loadExternalPlayerForwardSubtitles() ?: false
        externalPlayerSendSkipSegments = PlayerSettingsStorage.loadExternalPlayerSendSkipSegments() ?: false
        externalPlayerId = PlayerSettingsStorage.loadExternalPlayerId()
            ?: ExternalPlayerPlatform.defaultPlayerId()
        preferredAudioLanguage =
            normalizeLanguageCode(PlayerSettingsStorage.loadPreferredAudioLanguage())
                ?: AudioLanguageOption.DEVICE
        secondaryPreferredAudioLanguage =
            normalizeLanguageCode(PlayerSettingsStorage.loadSecondaryPreferredAudioLanguage())
        preferredSubtitleLanguage =
            normalizeLanguageCode(PlayerSettingsStorage.loadPreferredSubtitleLanguage())
                ?: SubtitleLanguageOption.NONE
        secondaryPreferredSubtitleLanguage =
            normalizeLanguageCode(PlayerSettingsStorage.loadSecondaryPreferredSubtitleLanguage())
        subtitleStyle = SubtitleStyleState(
            textColor = subtitleColorFromStorage(PlayerSettingsStorage.loadSubtitleTextColor())
                ?: SubtitleStyleState.DEFAULT.textColor,
            backgroundColor = subtitleColorFromStorage(PlayerSettingsStorage.loadSubtitleBackgroundColor())
                ?: SubtitleStyleState.DEFAULT.backgroundColor,
            outlineColor = subtitleColorFromStorage(PlayerSettingsStorage.loadSubtitleOutlineColor())
                ?: SubtitleStyleState.DEFAULT.outlineColor,
            outlineEnabled = PlayerSettingsStorage.loadSubtitleOutlineEnabled()
                ?: SubtitleStyleState.DEFAULT.outlineEnabled,
            outlineWidth = PlayerSettingsStorage.loadSubtitleOutlineWidth()
                ?: SubtitleStyleState.DEFAULT.outlineWidth,
            bold = PlayerSettingsStorage.loadSubtitleBold()
                ?: SubtitleStyleState.DEFAULT.bold,
            fontSizeSp = (PlayerSettingsStorage.loadSubtitleFontSizeSp()
                ?: SubtitleStyleState.DEFAULT.fontSizeSp).coerceIn(subtitleFontSizeRangeSp),
            bottomOffset = PlayerSettingsStorage.loadSubtitleBottomOffset()
                ?: SubtitleStyleState.DEFAULT.bottomOffset,
            stripSdh = PlayerSettingsStorage.loadSubtitleStripSdh()
                ?: SubtitleStyleState.DEFAULT.stripSdh,
            useForcedSubtitles = PlayerSettingsStorage.loadSubtitleUseForcedSubtitles()
                ?: SubtitleStyleState.DEFAULT.useForcedSubtitles,
            showOnlyPreferredLanguages = PlayerSettingsStorage.loadSubtitleShowOnlyPreferredLanguages()
                ?: SubtitleStyleState.DEFAULT.showOnlyPreferredLanguages,
        )
        addonSubtitleStartupMode = PlayerSettingsStorage.loadAddonSubtitleStartupMode()
            ?.let { runCatching { AddonSubtitleStartupMode.valueOf(it) }.getOrNull() }
            ?: AddonSubtitleStartupMode.ALL_SUBTITLES
        streamReuseLastLinkEnabled = PlayerSettingsStorage.loadStreamReuseLastLinkEnabled() ?: false
        streamReuseLastLinkCacheHours = PlayerSettingsStorage.loadStreamReuseLastLinkCacheHours() ?: 24
        androidPlaybackEngine = PlayerSettingsStorage.loadAndroidPlaybackEngine()
            ?.let { runCatching { AndroidPlaybackEngine.valueOf(it) }.getOrNull() }
            ?: AndroidPlaybackEngine.Auto
        androidLibmpvVideoOutput = PlayerSettingsStorage.loadAndroidLibmpvVideoOutput()
            ?.let { runCatching { AndroidLibmpvVideoOutput.valueOf(it) }.getOrNull() }
            ?: AndroidLibmpvVideoOutput.GpuNext
        androidLibmpvHardwareDecodingEnabled = PlayerSettingsStorage.loadAndroidLibmpvHardwareDecodingEnabled() ?: true
        androidLibmpvYuv420pEnabled = PlayerSettingsStorage.loadAndroidLibmpvYuv420pEnabled() ?: false
        decoderPriority = PlayerSettingsStorage.loadDecoderPriority() ?: 1
        mapDV7ToHevc = PlayerSettingsStorage.loadMapDV7ToHevc() ?: false
        tunnelingEnabled = PlayerSettingsStorage.loadTunnelingEnabled() ?: false
        // Coerced on the way out of storage, never on the way in - see `coerceSelectable`.
        // A profile that chose Instant keeps its stored key and gets it back when the mode
        // returns; until then it behaves as the mode nearest to what it asked for.
        playbackMode = PlaybackMode.coerceSelectable(
            PlaybackMode.fromStorage(PlayerSettingsStorage.loadPlaybackMode()),
        )
        playbackAllowTorrentAutopick = PlayerSettingsStorage.loadPlaybackAllowTorrentAutopick() ?: false
        // An unreadable stored value falls back to ANY rather than throwing: a renamed enum
        // constant must not make the app unable to load its own settings.
        playbackCodecPreference = PlayerSettingsStorage.loadPlaybackCodecPreference()
            ?.let { stored -> CodecPreference.entries.firstOrNull { it.name == stored } }
            ?: CodecPreference.ANY
        playbackDynamicRangePolicy = PlayerSettingsStorage.loadPlaybackDynamicRangePolicy()
            ?.let { stored -> DynamicRangePolicy.entries.firstOrNull { it.name == stored } }
            ?: DynamicRangePolicy.ANY
        playbackAudioPreference = PlayerSettingsStorage.loadPlaybackAudioPreference()
            ?.let { stored -> AudioPreference.entries.firstOrNull { it.name == stored } }
            ?: AudioPreference.ANY
        playbackLanguageStrictness = PlayerSettingsStorage.loadPlaybackLanguageStrictness()
            ?.let { stored -> LanguageStrictness.entries.firstOrNull { it.name == stored } }
            ?: LanguageStrictness.REQUIRE
        playbackQualityCeilingMbps = PlayerSettingsStorage.loadPlaybackQualityCeilingMbps()
            ?.coerceAtLeast(0)
            ?: 0
        // Unset means the profile predates this toggle. Defaulting it to false there would
        // hide settings the user had already tuned, which reads as data loss rather than as
        // a cleaner screen - so a profile that has touched any advanced setting keeps them
        // visible, and only a profile that never has starts hidden.
        showAdvancedSettings = PlayerSettingsStorage.loadShowAdvancedSettings()
            ?: hasTunedAnAdvancedSetting(
                allowTorrentAutopick = PlayerSettingsStorage.loadPlaybackAllowTorrentAutopick(),
                autoDownshift = PlayerSettingsStorage.loadPlaybackAutoDownshift(),
                meteredCapHeight = PlayerSettingsStorage.loadPlaybackMeteredCapHeight(),
                reuseLastLinkEnabled = PlayerSettingsStorage.loadStreamReuseLastLinkEnabled(),
                reuseLastLinkCacheHours = PlayerSettingsStorage.loadStreamReuseLastLinkCacheHours(),
                streamAutoPlayMode = PlayerSettingsStorage.loadStreamAutoPlayMode(),
                streamAutoPlayRegex = PlayerSettingsStorage.loadStreamAutoPlayRegex(),
                androidPlaybackEngine = PlayerSettingsStorage.loadAndroidPlaybackEngine(),
                decoderPriority = PlayerSettingsStorage.loadDecoderPriority(),
            )
        playbackMeteredCapHeight = PlayerSettingsStorage.loadPlaybackMeteredCapHeight()
            ?.takeIf { it in 360..2160 } ?: 720
        playbackAutoDownshift = PlayerSettingsStorage.loadPlaybackAutoDownshift() ?: false
        playbackModeSelectorSeen =
            PlayerSettingsStorage.loadPlaybackModeSelectorSeen() ?: false
        setupWizardCompletedRevision =
            PlayerSettingsStorage.loadSetupWizardCompletedRevision() ?: 0
        streamAutoPlayMode = PlayerSettingsStorage.loadStreamAutoPlayMode()
            ?.let { runCatching { StreamAutoPlayMode.valueOf(it) }.getOrNull() }
            ?: StreamAutoPlayMode.MANUAL
        streamAutoPlaySource = PlayerSettingsStorage.loadStreamAutoPlaySource()
            ?.let { runCatching { StreamAutoPlaySource.valueOf(it) }.getOrNull() }
            ?: StreamAutoPlaySource.ALL_SOURCES
        streamAutoPlaySelectedAddons = PlayerSettingsStorage.loadStreamAutoPlaySelectedAddons() ?: emptySet()
        streamAutoPlaySelectedPlugins = PlayerSettingsStorage.loadStreamAutoPlaySelectedPlugins() ?: emptySet()
        if (!AppFeaturePolicy.pluginsEnabled) {
            val normalizedSource = normalizeStreamAutoPlaySource(streamAutoPlaySource)
            if (normalizedSource != streamAutoPlaySource) {
                streamAutoPlaySource = normalizedSource
                PlayerSettingsStorage.saveStreamAutoPlaySource(normalizedSource.name)
            }
            if (streamAutoPlaySelectedPlugins.isNotEmpty()) {
                streamAutoPlaySelectedPlugins = emptySet()
                PlayerSettingsStorage.saveStreamAutoPlaySelectedPlugins(emptySet())
            }
        }
        streamAutoPlayRegex = PlayerSettingsStorage.loadStreamAutoPlayRegex() ?: ""
        streamAutoPlayTimeoutSeconds = PlayerSettingsStorage.loadStreamAutoPlayTimeoutSeconds() ?: 3
        // Legacy migration: 11 was the old sentinel for "unlimited"
        if (streamAutoPlayTimeoutSeconds == 11) {
            streamAutoPlayTimeoutSeconds = Int.MAX_VALUE
            PlayerSettingsStorage.saveStreamAutoPlayTimeoutSeconds(streamAutoPlayTimeoutSeconds)
        } else if (streamAutoPlayTimeoutSeconds !in STREAM_AUTO_PLAY_TIMEOUT_VALUES) {
            streamAutoPlayTimeoutSeconds = snapToAllowedTimeout(streamAutoPlayTimeoutSeconds)
            PlayerSettingsStorage.saveStreamAutoPlayTimeoutSeconds(streamAutoPlayTimeoutSeconds)
        }
        skipIntroEnabled = PlayerSettingsStorage.loadSkipIntroEnabled() ?: true
        animeSkipEnabled = PlayerSettingsStorage.loadAnimeSkipEnabled() ?: false
        animeSkipClientId = PlayerSettingsStorage.loadAnimeSkipClientId() ?: ""
        introDbApiKey = PlayerSettingsStorage.loadIntroDbApiKey() ?: ""
        introSubmitEnabled = PlayerSettingsStorage.loadIntroSubmitEnabled() ?: false
        streamAutoPlayNextEpisodeEnabled = PlayerSettingsStorage.loadStreamAutoPlayNextEpisodeEnabled() ?: false
        streamAutoPlayNextEpisodeFallbackEnabled = PlayerSettingsStorage.loadStreamAutoPlayNextEpisodeFallbackEnabled() ?: true
        streamAutoPlayPreferBingeGroup = PlayerSettingsStorage.loadStreamAutoPlayPreferBingeGroup() ?: true
        streamAutoPlayReuseBingeGroup = PlayerSettingsStorage.loadStreamAutoPlayReuseBingeGroup() ?: true
        nextEpisodeThresholdMode = PlayerSettingsStorage.loadNextEpisodeThresholdMode()
            ?.let { runCatching { NextEpisodeThresholdMode.valueOf(it) }.getOrNull() }
            ?: NextEpisodeThresholdMode.PERCENTAGE
        nextEpisodeThresholdPercent = PlayerSettingsStorage.loadNextEpisodeThresholdPercent() ?: 99f
        nextEpisodeThresholdMinutesBeforeEnd = PlayerSettingsStorage.loadNextEpisodeThresholdMinutesBeforeEnd() ?: 2f
        useLibass = PlayerSettingsStorage.loadUseLibass() ?: false
        libassRenderType = PlayerSettingsStorage.loadLibassRenderType() ?: "CUES"
        iosVideoOutputPreset = PlayerSettingsStorage.loadIosVideoOutputPreset()
            ?.let { runCatching { IosVideoOutputPreset.valueOf(it) }.getOrNull() }
            ?: IosVideoOutputPreset.NativeEdr
        iosToneMappingMode = PlayerSettingsStorage.loadIosToneMappingMode()
            ?.let { runCatching { IosToneMappingMode.valueOf(it) }.getOrNull() }
            ?: IosToneMappingMode.Auto
        iosTargetPrimaries = PlayerSettingsStorage.loadIosTargetPrimaries()
            ?.let { runCatching { IosTargetPrimaries.valueOf(it) }.getOrNull() }
            ?: IosTargetPrimaries.Auto
        iosTargetTransfer = PlayerSettingsStorage.loadIosTargetTransfer()
            ?.let { runCatching { IosTargetTransfer.valueOf(it) }.getOrNull() }
            ?: IosTargetTransfer.Auto
        iosHardwareDecoderMode = PlayerSettingsStorage.loadIosHardwareDecoderMode()
            ?.let { runCatching { IosHardwareDecoderMode.valueOf(it) }.getOrNull() }
            ?: IosHardwareDecoderMode.VideoToolbox
        iosAudioOutputMode = IosAudioOutputMode.fromStoredName(PlayerSettingsStorage.loadIosAudioOutputMode())
        iosExtendedDynamicRangeEnabled = PlayerSettingsStorage.loadIosExtendedDynamicRangeEnabled() ?: true
        iosTargetColorspaceHintEnabled = PlayerSettingsStorage.loadIosTargetColorspaceHintEnabled() ?: true
        iosHdrComputePeakEnabled = PlayerSettingsStorage.loadIosHdrComputePeakEnabled() ?: true
        iosDebandEnabled = PlayerSettingsStorage.loadIosDebandEnabled() ?: false
        iosInterpolationEnabled = PlayerSettingsStorage.loadIosInterpolationEnabled() ?: false
        iosBrightness = PlayerSettingsStorage.loadIosBrightness() ?: 0
        iosContrast = PlayerSettingsStorage.loadIosContrast() ?: 0
        iosSaturation = PlayerSettingsStorage.loadIosSaturation() ?: 0
        iosGamma = PlayerSettingsStorage.loadIosGamma() ?: 0
        publish()
    }

    fun setShowLoadingOverlay(enabled: Boolean) {
        ensureLoaded()
        if (showLoadingOverlay == enabled) return
        showLoadingOverlay = enabled
        publish()
        PlayerSettingsStorage.saveShowLoadingOverlay(enabled)
    }

    fun setShowParentalGuide(enabled: Boolean) {
        ensureLoaded()
        if (showParentalGuide == enabled) return
        showParentalGuide = enabled
        publish()
        PlayerSettingsStorage.saveShowParentalGuide(enabled)
    }

    fun setResizeMode(mode: PlayerResizeMode) {
        ensureLoaded()
        if (resizeMode == mode) return
        resizeMode = mode
        publish()
        PlayerSettingsStorage.saveResizeMode(mode.name)
    }

    fun setHoldToSpeedEnabled(enabled: Boolean) {
        ensureLoaded()
        if (holdToSpeedEnabled == enabled) return
        holdToSpeedEnabled = enabled
        publish()
        PlayerSettingsStorage.saveHoldToSpeedEnabled(enabled)
    }

    fun setHoldToSpeedValue(speed: Float) {
        ensureLoaded()
        val normalized = speed.coerceIn(1f, 4f)
        if (holdToSpeedValue == normalized) return
        holdToSpeedValue = normalized
        publish()
        PlayerSettingsStorage.saveHoldToSpeedValue(normalized)
    }

    fun setTouchGesturesEnabled(enabled: Boolean) {
        ensureLoaded()
        if (touchGesturesEnabled == enabled) return
        touchGesturesEnabled = enabled
        publish()
        PlayerSettingsStorage.saveTouchGesturesEnabled(enabled)
    }

    fun setExternalPlayerEnabled(enabled: Boolean) {
        ensureLoaded()
        if (enabled && externalPlayerId.isNullOrBlank()) {
            externalPlayerId = ExternalPlayerPlatform.defaultPlayerId()
                ?: ExternalPlayerPlatform.availablePlayers().firstOrNull()?.id
            PlayerSettingsStorage.saveExternalPlayerId(externalPlayerId)
        }
        if (externalPlayerEnabled == enabled) {
            publish()
            return
        }
        externalPlayerEnabled = enabled
        publish()
        PlayerSettingsStorage.saveExternalPlayerEnabled(enabled)
    }

    fun setExternalPlayerId(playerId: String?) {
        ensureLoaded()
        val normalized = playerId?.takeIf { it.isNotBlank() }
        if (externalPlayerId == normalized) return
        externalPlayerId = normalized
        publish()
        PlayerSettingsStorage.saveExternalPlayerId(normalized)
    }

    fun setExternalPlayerForwardSubtitles(enabled: Boolean) {
        ensureLoaded()
        if (externalPlayerForwardSubtitles == enabled) return
        externalPlayerForwardSubtitles = enabled
        publish()
        PlayerSettingsStorage.saveExternalPlayerForwardSubtitles(enabled)
    }

    fun setExternalPlayerSendSkipSegments(enabled: Boolean) {
        ensureLoaded()
        if (externalPlayerSendSkipSegments == enabled) return
        externalPlayerSendSkipSegments = enabled
        publish()
        PlayerSettingsStorage.saveExternalPlayerSendSkipSegments(enabled)
    }

    fun setPreferredAudioLanguage(language: String) {
        ensureLoaded()
        val normalized = normalizeLanguageCode(language) ?: AudioLanguageOption.DEVICE
        if (preferredAudioLanguage == normalized) return
        preferredAudioLanguage = normalized
        publish()
        PlayerSettingsStorage.savePreferredAudioLanguage(normalized)
    }

    fun setSecondaryPreferredAudioLanguage(language: String?) {
        ensureLoaded()
        val normalized = normalizeLanguageCode(language)
        if (secondaryPreferredAudioLanguage == normalized) return
        secondaryPreferredAudioLanguage = normalized
        publish()
        PlayerSettingsStorage.saveSecondaryPreferredAudioLanguage(normalized)
    }

    fun setPreferredSubtitleLanguage(language: String) {
        ensureLoaded()
        val normalized = normalizeLanguageCode(language) ?: SubtitleLanguageOption.NONE
        if (preferredSubtitleLanguage == normalized) return
        preferredSubtitleLanguage = normalized
        publish()
        PlayerSettingsStorage.savePreferredSubtitleLanguage(normalized)
    }

    fun setSecondaryPreferredSubtitleLanguage(language: String?) {
        ensureLoaded()
        val normalized = normalizeLanguageCode(language)
        if (secondaryPreferredSubtitleLanguage == normalized) return
        secondaryPreferredSubtitleLanguage = normalized
        publish()
        PlayerSettingsStorage.saveSecondaryPreferredSubtitleLanguage(normalized)
    }

    fun setSubtitleStyle(style: SubtitleStyleState) {
        ensureLoaded()
        val normalized = style.copy(fontSizeSp = style.fontSizeSp.coerceIn(subtitleFontSizeRangeSp))
        if (subtitleStyle == normalized) return
        subtitleStyle = normalized
        publish()
        PlayerSettingsStorage.saveSubtitleTextColor(normalized.textColor.toStorageHexString())
        PlayerSettingsStorage.saveSubtitleBackgroundColor(normalized.backgroundColor.toStorageHexString())
        PlayerSettingsStorage.saveSubtitleOutlineColor(normalized.outlineColor.toStorageHexString())
        PlayerSettingsStorage.saveSubtitleOutlineEnabled(normalized.outlineEnabled)
        PlayerSettingsStorage.saveSubtitleOutlineWidth(normalized.outlineWidth)
        PlayerSettingsStorage.saveSubtitleBold(normalized.bold)
        PlayerSettingsStorage.saveSubtitleFontSizeSp(normalized.fontSizeSp)
        PlayerSettingsStorage.saveSubtitleBottomOffset(normalized.bottomOffset)
        PlayerSettingsStorage.saveSubtitleStripSdh(normalized.stripSdh)
        PlayerSettingsStorage.saveSubtitleUseForcedSubtitles(normalized.useForcedSubtitles)
        PlayerSettingsStorage.saveSubtitleShowOnlyPreferredLanguages(normalized.showOnlyPreferredLanguages)
    }

    fun setAddonSubtitleStartupMode(mode: AddonSubtitleStartupMode) {
        ensureLoaded()
        if (addonSubtitleStartupMode == mode) return
        addonSubtitleStartupMode = mode
        publish()
        PlayerSettingsStorage.saveAddonSubtitleStartupMode(mode.name)
    }

    fun setStreamReuseLastLinkEnabled(enabled: Boolean) {
        ensureLoaded()
        if (streamReuseLastLinkEnabled == enabled) return
        streamReuseLastLinkEnabled = enabled
        publish()
        PlayerSettingsStorage.saveStreamReuseLastLinkEnabled(enabled)
    }

    fun setStreamReuseLastLinkCacheHours(hours: Int) {
        ensureLoaded()
        if (streamReuseLastLinkCacheHours == hours) return
        streamReuseLastLinkCacheHours = hours
        publish()
        PlayerSettingsStorage.saveStreamReuseLastLinkCacheHours(hours)
    }

    fun setAndroidPlaybackEngine(engine: AndroidPlaybackEngine) {
        ensureLoaded()
        if (androidPlaybackEngine == engine) return
        androidPlaybackEngine = engine
        publish()
        PlayerSettingsStorage.saveAndroidPlaybackEngine(engine.name)
    }

    fun setAndroidLibmpvVideoOutput(output: AndroidLibmpvVideoOutput) {
        ensureLoaded()
        if (androidLibmpvVideoOutput == output) return
        androidLibmpvVideoOutput = output
        publish()
        PlayerSettingsStorage.saveAndroidLibmpvVideoOutput(output.name)
    }

    fun setAndroidLibmpvHardwareDecodingEnabled(enabled: Boolean) {
        ensureLoaded()
        if (androidLibmpvHardwareDecodingEnabled == enabled) return
        androidLibmpvHardwareDecodingEnabled = enabled
        publish()
        PlayerSettingsStorage.saveAndroidLibmpvHardwareDecodingEnabled(enabled)
    }

    fun setAndroidLibmpvYuv420pEnabled(enabled: Boolean) {
        ensureLoaded()
        if (androidLibmpvYuv420pEnabled == enabled) return
        androidLibmpvYuv420pEnabled = enabled
        publish()
        PlayerSettingsStorage.saveAndroidLibmpvYuv420pEnabled(enabled)
    }

    fun setDecoderPriority(priority: Int) {
        ensureLoaded()
        if (decoderPriority == priority) return
        decoderPriority = priority
        publish()
        PlayerSettingsStorage.saveDecoderPriority(priority)
    }

    fun setMapDV7ToHevc(enabled: Boolean) {
        ensureLoaded()
        if (mapDV7ToHevc == enabled) return
        mapDV7ToHevc = enabled
        publish()
        PlayerSettingsStorage.saveMapDV7ToHevc(enabled)
    }

    fun setTunnelingEnabled(enabled: Boolean) {
        ensureLoaded()
        if (tunnelingEnabled == enabled) return
        tunnelingEnabled = enabled
        publish()
        PlayerSettingsStorage.saveTunnelingEnabled(enabled)
    }

    fun setPlaybackMode(mode: PlaybackMode) {
        ensureLoaded()
        if (playbackMode == mode) return
        playbackMode = mode
        publish()
        PlayerSettingsStorage.savePlaybackMode(mode.name)
    }

    fun setShowAdvancedSettings(enabled: Boolean) {
        ensureLoaded()
        if (showAdvancedSettings == enabled) return
        showAdvancedSettings = enabled
        publish()
        PlayerSettingsStorage.saveShowAdvancedSettings(enabled)
    }

    fun setPlaybackAllowTorrentAutopick(enabled: Boolean) {
        ensureLoaded()
        if (playbackAllowTorrentAutopick == enabled) return
        playbackAllowTorrentAutopick = enabled
        publish()
        PlayerSettingsStorage.savePlaybackAllowTorrentAutopick(enabled)
    }

    fun setPlaybackCodecPreference(preference: CodecPreference) {
        ensureLoaded()
        if (playbackCodecPreference == preference) return
        playbackCodecPreference = preference
        publish()
        PlayerSettingsStorage.savePlaybackCodecPreference(preference.name)
    }

    fun setPlaybackDynamicRangePolicy(policy: DynamicRangePolicy) {
        ensureLoaded()
        if (playbackDynamicRangePolicy == policy) return
        playbackDynamicRangePolicy = policy
        publish()
        PlayerSettingsStorage.savePlaybackDynamicRangePolicy(policy.name)
    }

    fun setPlaybackAudioPreference(preference: AudioPreference) {
        ensureLoaded()
        if (playbackAudioPreference == preference) return
        playbackAudioPreference = preference
        publish()
        PlayerSettingsStorage.savePlaybackAudioPreference(preference.name)
    }

    fun setPlaybackLanguageStrictness(strictness: LanguageStrictness) {
        ensureLoaded()
        if (playbackLanguageStrictness == strictness) return
        playbackLanguageStrictness = strictness
        publish()
        PlayerSettingsStorage.savePlaybackLanguageStrictness(strictness.name)
    }

    fun setPlaybackQualityCeilingMbps(mbps: Int) {
        ensureLoaded()
        // Zero is "no ceiling" and the only value below the floor that means anything. Anything
        // under 5 Mbps would refuse every release above a 720p encode, which is a setting that
        // looks like a preference and behaves like a fault.
        val normalized = if (mbps <= 0) 0 else mbps.coerceIn(5, 500)
        if (playbackQualityCeilingMbps == normalized) return
        playbackQualityCeilingMbps = normalized
        publish()
        PlayerSettingsStorage.savePlaybackQualityCeilingMbps(normalized)
    }

    fun setPlaybackMeteredCapHeight(height: Int) {
        ensureLoaded()
        val normalized = height.coerceIn(360, 2160)
        if (playbackMeteredCapHeight == normalized) return
        playbackMeteredCapHeight = normalized
        publish()
        PlayerSettingsStorage.savePlaybackMeteredCapHeight(normalized)
    }

    fun setPlaybackAutoDownshift(enabled: Boolean) {
        ensureLoaded()
        if (playbackAutoDownshift == enabled) return
        playbackAutoDownshift = enabled
        publish()
        PlayerSettingsStorage.savePlaybackAutoDownshift(enabled)
    }

    /**
     * Records that the mode selector has been answered.
     *
     * Kept separate from [setPlaybackMode] because choosing Classic - the
     * pre-selected option - is a no-op for the mode and must still dismiss the
     * selector for good.
     */
    fun markPlaybackModeSelectorSeen() {
        ensureLoaded()
        if (playbackModeSelectorSeen) return
        playbackModeSelectorSeen = true
        publish()
        PlayerSettingsStorage.savePlaybackModeSelectorSeen(true)
    }

    /**
     * Records that the setup wizard has been completed at [revision].
     *
     * Only ever increases. A profile can carry a higher revision than this build knows about,
     * because the value syncs and the user may have finished the wizard on a newer install;
     * lowering it there would re-run a wizard they have already answered a superset of.
     */
    fun markSetupWizardCompleted(revision: Int) {
        ensureLoaded()
        if (setupWizardCompletedRevision >= revision) return
        setupWizardCompletedRevision = revision
        publish()
        PlayerSettingsStorage.saveSetupWizardCompletedRevision(revision)
    }

    fun setStreamAutoPlayMode(mode: StreamAutoPlayMode) {
        ensureLoaded()
        if (streamAutoPlayMode == mode) return
        streamAutoPlayMode = mode
        publish()
        PlayerSettingsStorage.saveStreamAutoPlayMode(mode.name)
    }

    fun setStreamAutoPlaySource(source: StreamAutoPlaySource) {
        ensureLoaded()
        val normalizedSource = normalizeStreamAutoPlaySource(source)
        if (streamAutoPlaySource == normalizedSource) return
        streamAutoPlaySource = normalizedSource
        publish()
        PlayerSettingsStorage.saveStreamAutoPlaySource(normalizedSource.name)
    }

    fun setStreamAutoPlaySelectedAddons(addons: Set<String>) {
        ensureLoaded()
        if (streamAutoPlaySelectedAddons == addons) return
        streamAutoPlaySelectedAddons = addons
        publish()
        PlayerSettingsStorage.saveStreamAutoPlaySelectedAddons(addons)
    }

    fun setStreamAutoPlaySelectedPlugins(plugins: Set<String>) {
        ensureLoaded()
        val normalizedPlugins = if (AppFeaturePolicy.pluginsEnabled) plugins else emptySet()
        if (streamAutoPlaySelectedPlugins == normalizedPlugins) return
        streamAutoPlaySelectedPlugins = normalizedPlugins
        publish()
        PlayerSettingsStorage.saveStreamAutoPlaySelectedPlugins(normalizedPlugins)
    }

    fun setStreamAutoPlayRegex(regex: String) {
        ensureLoaded()
        if (streamAutoPlayRegex == regex) return
        streamAutoPlayRegex = regex
        publish()
        PlayerSettingsStorage.saveStreamAutoPlayRegex(regex)
    }

    fun setStreamAutoPlayTimeoutSeconds(seconds: Int) {
        ensureLoaded()
        if (streamAutoPlayTimeoutSeconds == seconds) return
        streamAutoPlayTimeoutSeconds = seconds
        publish()
        PlayerSettingsStorage.saveStreamAutoPlayTimeoutSeconds(seconds)
    }

    fun setSkipIntroEnabled(enabled: Boolean) {
        ensureLoaded()
        if (skipIntroEnabled == enabled) return
        skipIntroEnabled = enabled
        publish()
        PlayerSettingsStorage.saveSkipIntroEnabled(enabled)
    }

    fun setAnimeSkipEnabled(enabled: Boolean) {
        ensureLoaded()
        if (animeSkipEnabled == enabled) return
        animeSkipEnabled = enabled
        publish()
        PlayerSettingsStorage.saveAnimeSkipEnabled(enabled)
    }

    fun setAnimeSkipClientId(clientId: String) {
        ensureLoaded()
        if (animeSkipClientId == clientId) return
        animeSkipClientId = clientId
        publish()
        PlayerSettingsStorage.saveAnimeSkipClientId(clientId)
    }

    fun setIntroDbApiKey(apiKey: String) {
        ensureLoaded()
        if (introDbApiKey == apiKey) return
        introDbApiKey = apiKey
        publish()
        PlayerSettingsStorage.saveIntroDbApiKey(apiKey)
    }

    fun setIntroSubmitEnabled(enabled: Boolean) {
        ensureLoaded()
        if (introSubmitEnabled == enabled) return
        introSubmitEnabled = enabled
        publish()
        PlayerSettingsStorage.saveIntroSubmitEnabled(enabled)
    }

    fun setStreamAutoPlayNextEpisodeEnabled(enabled: Boolean) {
        ensureLoaded()
        if (streamAutoPlayNextEpisodeEnabled == enabled) return
        streamAutoPlayNextEpisodeEnabled = enabled
        publish()
        PlayerSettingsStorage.saveStreamAutoPlayNextEpisodeEnabled(enabled)
    }

    fun setStreamAutoPlayNextEpisodeFallbackEnabled(enabled: Boolean) {
        ensureLoaded()
        if (streamAutoPlayNextEpisodeFallbackEnabled == enabled) return
        streamAutoPlayNextEpisodeFallbackEnabled = enabled
        publish()
        PlayerSettingsStorage.saveStreamAutoPlayNextEpisodeFallbackEnabled(enabled)
    }

    fun setStreamAutoPlayPreferBingeGroup(enabled: Boolean) {
        ensureLoaded()
        if (streamAutoPlayPreferBingeGroup == enabled) return
        streamAutoPlayPreferBingeGroup = enabled
        publish()
        PlayerSettingsStorage.saveStreamAutoPlayPreferBingeGroup(enabled)
    }

    fun setStreamAutoPlayReuseBingeGroup(enabled: Boolean) {
        ensureLoaded()
        if (streamAutoPlayReuseBingeGroup == enabled) return
        streamAutoPlayReuseBingeGroup = enabled
        publish()
        PlayerSettingsStorage.saveStreamAutoPlayReuseBingeGroup(enabled)
    }

    fun setNextEpisodeThresholdMode(mode: NextEpisodeThresholdMode) {
        ensureLoaded()
        if (nextEpisodeThresholdMode == mode) return
        nextEpisodeThresholdMode = mode
        publish()
        PlayerSettingsStorage.saveNextEpisodeThresholdMode(mode.name)
    }

    fun setNextEpisodeThresholdPercent(percent: Float) {
        ensureLoaded()
        if (nextEpisodeThresholdPercent == percent) return
        nextEpisodeThresholdPercent = percent
        publish()
        PlayerSettingsStorage.saveNextEpisodeThresholdPercent(percent)
    }

    fun setNextEpisodeThresholdMinutesBeforeEnd(minutes: Float) {
        ensureLoaded()
        if (nextEpisodeThresholdMinutesBeforeEnd == minutes) return
        nextEpisodeThresholdMinutesBeforeEnd = minutes
        publish()
        PlayerSettingsStorage.saveNextEpisodeThresholdMinutesBeforeEnd(minutes)
    }

    fun setUseLibass(enabled: Boolean) {
        ensureLoaded()
        if (useLibass == enabled) return
        useLibass = enabled
        publish()
        PlayerSettingsStorage.saveUseLibass(enabled)
    }

    fun setLibassRenderType(renderType: String) {
        ensureLoaded()
        if (libassRenderType == renderType) return
        libassRenderType = renderType
        publish()
        PlayerSettingsStorage.saveLibassRenderType(renderType)
    }

    fun setIosVideoOutputPreset(preset: IosVideoOutputPreset) {
        ensureLoaded()
        iosVideoOutputPreset = preset
        when (preset) {
            IosVideoOutputPreset.NativeEdr -> {
                iosExtendedDynamicRangeEnabled = true
                iosTargetColorspaceHintEnabled = true
                iosHdrComputePeakEnabled = true
                iosToneMappingMode = IosToneMappingMode.Auto
                iosTargetPrimaries = IosTargetPrimaries.Auto
                iosTargetTransfer = IosTargetTransfer.Auto
            }
            IosVideoOutputPreset.SdrToneMapped -> {
                iosExtendedDynamicRangeEnabled = false
                iosTargetColorspaceHintEnabled = false
                iosHdrComputePeakEnabled = true
                iosToneMappingMode = IosToneMappingMode.Bt2390
                iosTargetPrimaries = IosTargetPrimaries.Bt709
                iosTargetTransfer = IosTargetTransfer.Srgb
            }
            IosVideoOutputPreset.Compatibility -> {
                iosExtendedDynamicRangeEnabled = false
                iosTargetColorspaceHintEnabled = true
                iosHdrComputePeakEnabled = false
                iosToneMappingMode = IosToneMappingMode.Auto
                iosTargetPrimaries = IosTargetPrimaries.Auto
                iosTargetTransfer = IosTargetTransfer.Auto
            }
            IosVideoOutputPreset.Custom -> Unit
        }
        publish()
        saveIosVideoOutputSettings()
    }

    fun setIosToneMappingMode(mode: IosToneMappingMode) {
        ensureLoaded()
        iosVideoOutputPreset = IosVideoOutputPreset.Custom
        iosToneMappingMode = mode
        publish()
        saveIosVideoOutputSettings()
    }

    fun setIosTargetPrimaries(primaries: IosTargetPrimaries) {
        ensureLoaded()
        iosVideoOutputPreset = IosVideoOutputPreset.Custom
        iosTargetPrimaries = primaries
        publish()
        saveIosVideoOutputSettings()
    }

    fun setIosTargetTransfer(transfer: IosTargetTransfer) {
        ensureLoaded()
        iosVideoOutputPreset = IosVideoOutputPreset.Custom
        iosTargetTransfer = transfer
        publish()
        saveIosVideoOutputSettings()
    }

    fun setIosHardwareDecoderMode(mode: IosHardwareDecoderMode) {
        ensureLoaded()
        iosHardwareDecoderMode = mode
        publish()
        PlayerSettingsStorage.saveIosHardwareDecoderMode(mode.name)
    }

    fun setIosAudioOutputMode(mode: IosAudioOutputMode) {
        ensureLoaded()
        iosAudioOutputMode = mode.takeUnless { it == IosAudioOutputMode.AvFoundation } ?: IosAudioOutputMode.Auto
        publish()
        PlayerSettingsStorage.saveIosAudioOutputMode(iosAudioOutputMode.name)
    }

    fun setIosExtendedDynamicRangeEnabled(enabled: Boolean) {
        ensureLoaded()
        iosVideoOutputPreset = IosVideoOutputPreset.Custom
        iosExtendedDynamicRangeEnabled = enabled
        publish()
        saveIosVideoOutputSettings()
    }

    fun setIosTargetColorspaceHintEnabled(enabled: Boolean) {
        ensureLoaded()
        iosVideoOutputPreset = IosVideoOutputPreset.Custom
        iosTargetColorspaceHintEnabled = enabled
        publish()
        saveIosVideoOutputSettings()
    }

    fun setIosHdrComputePeakEnabled(enabled: Boolean) {
        ensureLoaded()
        iosVideoOutputPreset = IosVideoOutputPreset.Custom
        iosHdrComputePeakEnabled = enabled
        publish()
        saveIosVideoOutputSettings()
    }

    fun setIosDebandEnabled(enabled: Boolean) {
        ensureLoaded()
        iosDebandEnabled = enabled
        publish()
        PlayerSettingsStorage.saveIosDebandEnabled(enabled)
    }

    fun setIosInterpolationEnabled(enabled: Boolean) {
        ensureLoaded()
        iosInterpolationEnabled = enabled
        publish()
        PlayerSettingsStorage.saveIosInterpolationEnabled(enabled)
    }

    fun setIosBrightness(value: Int) {
        ensureLoaded()
        iosBrightness = value.coerceIn(-50, 50)
        publish()
        PlayerSettingsStorage.saveIosBrightness(iosBrightness)
    }

    fun setIosContrast(value: Int) {
        ensureLoaded()
        iosContrast = value.coerceIn(-50, 50)
        publish()
        PlayerSettingsStorage.saveIosContrast(iosContrast)
    }

    fun setIosSaturation(value: Int) {
        ensureLoaded()
        iosSaturation = value.coerceIn(-50, 50)
        publish()
        PlayerSettingsStorage.saveIosSaturation(iosSaturation)
    }

    fun setIosGamma(value: Int) {
        ensureLoaded()
        iosGamma = value.coerceIn(-50, 50)
        publish()
        PlayerSettingsStorage.saveIosGamma(iosGamma)
    }

    fun resetIosVideoOutputTuning() {
        ensureLoaded()
        iosBrightness = 0
        iosContrast = 0
        iosSaturation = 0
        iosGamma = 0
        iosDebandEnabled = false
        iosInterpolationEnabled = false
        publish()
        PlayerSettingsStorage.saveIosBrightness(0)
        PlayerSettingsStorage.saveIosContrast(0)
        PlayerSettingsStorage.saveIosSaturation(0)
        PlayerSettingsStorage.saveIosGamma(0)
        PlayerSettingsStorage.saveIosDebandEnabled(false)
        PlayerSettingsStorage.saveIosInterpolationEnabled(false)
    }

    private fun saveIosVideoOutputSettings() {
        PlayerSettingsStorage.saveIosVideoOutputPreset(iosVideoOutputPreset.name)
        PlayerSettingsStorage.saveIosToneMappingMode(iosToneMappingMode.name)
        PlayerSettingsStorage.saveIosTargetPrimaries(iosTargetPrimaries.name)
        PlayerSettingsStorage.saveIosTargetTransfer(iosTargetTransfer.name)
        PlayerSettingsStorage.saveIosExtendedDynamicRangeEnabled(iosExtendedDynamicRangeEnabled)
        PlayerSettingsStorage.saveIosTargetColorspaceHintEnabled(iosTargetColorspaceHintEnabled)
        PlayerSettingsStorage.saveIosHdrComputePeakEnabled(iosHdrComputePeakEnabled)
    }

    private fun publish() {
        _uiState.value = PlayerSettingsUiState(
            showLoadingOverlay = showLoadingOverlay,
            showParentalGuide = showParentalGuide,
            resizeMode = resizeMode,
            holdToSpeedEnabled = holdToSpeedEnabled,
            holdToSpeedValue = holdToSpeedValue,
            touchGesturesEnabled = touchGesturesEnabled,
            externalPlayerEnabled = externalPlayerEnabled,
            externalPlayerForwardSubtitles = externalPlayerForwardSubtitles,
            externalPlayerSendSkipSegments = externalPlayerSendSkipSegments,
            externalPlayerId = externalPlayerId,
            preferredAudioLanguage = preferredAudioLanguage,
            secondaryPreferredAudioLanguage = secondaryPreferredAudioLanguage,
            preferredSubtitleLanguage = preferredSubtitleLanguage,
            secondaryPreferredSubtitleLanguage = secondaryPreferredSubtitleLanguage,
            subtitleStyle = subtitleStyle,
            addonSubtitleStartupMode = addonSubtitleStartupMode,
            streamReuseLastLinkEnabled = streamReuseLastLinkEnabled,
            streamReuseLastLinkCacheHours = streamReuseLastLinkCacheHours,
            androidPlaybackEngine = androidPlaybackEngine,
            androidLibmpvVideoOutput = androidLibmpvVideoOutput,
            androidLibmpvHardwareDecodingEnabled = androidLibmpvHardwareDecodingEnabled,
            androidLibmpvYuv420pEnabled = androidLibmpvYuv420pEnabled,
            decoderPriority = decoderPriority,
            mapDV7ToHevc = mapDV7ToHevc,
            tunnelingEnabled = tunnelingEnabled,
            playbackMode = playbackMode,
            playbackAllowTorrentAutopick = playbackAllowTorrentAutopick,
            playbackCodecPreference = playbackCodecPreference,
            playbackDynamicRangePolicy = playbackDynamicRangePolicy,
            playbackAudioPreference = playbackAudioPreference,
            playbackLanguageStrictness = playbackLanguageStrictness,
            playbackQualityCeilingMbps = playbackQualityCeilingMbps,
            showAdvancedSettings = showAdvancedSettings,
            playbackMeteredCapHeight = playbackMeteredCapHeight,
            playbackAutoDownshift = playbackAutoDownshift,
            playbackModeSelectorSeen = playbackModeSelectorSeen,
            setupWizardCompletedRevision = setupWizardCompletedRevision,
            streamAutoPlayMode = streamAutoPlayMode,
            streamAutoPlaySource = streamAutoPlaySource,
            streamAutoPlaySelectedAddons = streamAutoPlaySelectedAddons,
            streamAutoPlaySelectedPlugins = streamAutoPlaySelectedPlugins,
            streamAutoPlayRegex = streamAutoPlayRegex,
            streamAutoPlayTimeoutSeconds = streamAutoPlayTimeoutSeconds,
            skipIntroEnabled = skipIntroEnabled,
            animeSkipEnabled = animeSkipEnabled,
            animeSkipClientId = animeSkipClientId,
            introDbApiKey = introDbApiKey,
            introSubmitEnabled = introSubmitEnabled,
            streamAutoPlayNextEpisodeEnabled = streamAutoPlayNextEpisodeEnabled,
            streamAutoPlayNextEpisodeFallbackEnabled = streamAutoPlayNextEpisodeFallbackEnabled,
            streamAutoPlayPreferBingeGroup = streamAutoPlayPreferBingeGroup,
            streamAutoPlayReuseBingeGroup = streamAutoPlayReuseBingeGroup,
            nextEpisodeThresholdMode = nextEpisodeThresholdMode,
            nextEpisodeThresholdPercent = nextEpisodeThresholdPercent,
            nextEpisodeThresholdMinutesBeforeEnd = nextEpisodeThresholdMinutesBeforeEnd,
            useLibass = useLibass,
            libassRenderType = libassRenderType,
            iosVideoOutputPreset = iosVideoOutputPreset,
            iosToneMappingMode = iosToneMappingMode,
            iosTargetPrimaries = iosTargetPrimaries,
            iosTargetTransfer = iosTargetTransfer,
            iosHardwareDecoderMode = iosHardwareDecoderMode,
            iosAudioOutputMode = iosAudioOutputMode,
            iosExtendedDynamicRangeEnabled = iosExtendedDynamicRangeEnabled,
            iosTargetColorspaceHintEnabled = iosTargetColorspaceHintEnabled,
            iosHdrComputePeakEnabled = iosHdrComputePeakEnabled,
            iosDebandEnabled = iosDebandEnabled,
            iosInterpolationEnabled = iosInterpolationEnabled,
            iosBrightness = iosBrightness,
            iosContrast = iosContrast,
            iosSaturation = iosSaturation,
            iosGamma = iosGamma,
        )
    }

    private fun normalizeStreamAutoPlaySource(source: StreamAutoPlaySource): StreamAutoPlaySource {
        return if (!AppFeaturePolicy.pluginsEnabled && source == StreamAutoPlaySource.ENABLED_PLUGINS_ONLY) {
            StreamAutoPlaySource.ALL_SOURCES
        } else {
            source
        }
    }
}
