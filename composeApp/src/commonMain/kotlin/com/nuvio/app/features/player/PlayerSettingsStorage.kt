package com.nuvio.app.features.player

import kotlinx.serialization.json.JsonObject

/**
 * Which stored sync keys `replaceFromSyncPayload` may clear before applying [payload].
 *
 * **Only the keys the payload actually carries.** The remote blob is authoritative for
 * settings it knows about, never for settings it has never heard of: clearing every sync key
 * first destroys anything added since that blob was last written. That is not hypothetical -
 * it reset the playback mode and re-showed the first-launch selector on every sync for any
 * signed-in user whose stored blob predated `0.4.0-beta`, because none of the `playback_*`
 * keys existed when it was written.
 *
 * Shared by all three actuals so the rule cannot drift between platforms.
 */
internal fun syncKeysToClear(syncKeys: List<String>, payload: JsonObject): List<String> =
    syncKeys.filter(payload::containsKey)

internal expect object PlayerSettingsStorage {
    fun loadShowLoadingOverlay(): Boolean?
    fun saveShowLoadingOverlay(enabled: Boolean)
    fun loadShowParentalGuide(): Boolean?
    fun saveShowParentalGuide(enabled: Boolean)
    fun loadResizeMode(): String?
    fun saveResizeMode(mode: String)
    fun loadHoldToSpeedEnabled(): Boolean?
    fun saveHoldToSpeedEnabled(enabled: Boolean)
    fun loadHoldToSpeedValue(): Float?
    fun saveHoldToSpeedValue(speed: Float)
    fun loadTouchGesturesEnabled(): Boolean?
    fun saveTouchGesturesEnabled(enabled: Boolean)
    fun loadExternalPlayerEnabled(): Boolean?
    fun saveExternalPlayerEnabled(enabled: Boolean)
    fun loadExternalPlayerForwardSubtitles(): Boolean?
    fun saveExternalPlayerForwardSubtitles(enabled: Boolean)
    fun loadExternalPlayerSendSkipSegments(): Boolean?
    fun saveExternalPlayerSendSkipSegments(enabled: Boolean)
    fun loadExternalPlayerId(): String?
    fun saveExternalPlayerId(playerId: String?)
    fun loadPreferredAudioLanguage(): String?
    fun savePreferredAudioLanguage(language: String)
    fun loadSecondaryPreferredAudioLanguage(): String?
    fun saveSecondaryPreferredAudioLanguage(language: String?)
    fun loadPreferredSubtitleLanguage(): String?
    fun savePreferredSubtitleLanguage(language: String)
    fun loadSecondaryPreferredSubtitleLanguage(): String?
    fun saveSecondaryPreferredSubtitleLanguage(language: String?)
    fun loadSubtitleTextColor(): String?
    fun saveSubtitleTextColor(colorHex: String)
    fun loadSubtitleBackgroundColor(): String?
    fun saveSubtitleBackgroundColor(colorHex: String)
    fun loadSubtitleOutlineColor(): String?
    fun saveSubtitleOutlineColor(colorHex: String)
    fun loadSubtitleOutlineEnabled(): Boolean?
    fun saveSubtitleOutlineEnabled(enabled: Boolean)
    fun loadSubtitleOutlineWidth(): Int?
    fun saveSubtitleOutlineWidth(width: Int)
    fun loadSubtitleBold(): Boolean?
    fun saveSubtitleBold(enabled: Boolean)
    fun loadSubtitleFontSizeSp(): Int?
    fun saveSubtitleFontSizeSp(fontSizeSp: Int)
    fun loadSubtitleBottomOffset(): Int?
    fun saveSubtitleBottomOffset(bottomOffset: Int)
    fun loadSubtitleUseForcedSubtitles(): Boolean?
    fun saveSubtitleUseForcedSubtitles(enabled: Boolean)
    fun loadSubtitleShowOnlyPreferredLanguages(): Boolean?
    fun saveSubtitleShowOnlyPreferredLanguages(enabled: Boolean)
    fun loadAddonSubtitleStartupMode(): String?
    fun saveAddonSubtitleStartupMode(mode: String)
    fun loadStreamReuseLastLinkEnabled(): Boolean?
    fun saveStreamReuseLastLinkEnabled(enabled: Boolean)
    fun loadStreamReuseLastLinkCacheHours(): Int?
    fun saveStreamReuseLastLinkCacheHours(hours: Int)
    fun loadAndroidPlaybackEngine(): String?
    fun saveAndroidPlaybackEngine(engine: String)
    fun loadAndroidLibmpvVideoOutput(): String?
    fun saveAndroidLibmpvVideoOutput(output: String)
    fun loadAndroidLibmpvHardwareDecodingEnabled(): Boolean?
    fun saveAndroidLibmpvHardwareDecodingEnabled(enabled: Boolean)
    fun loadAndroidLibmpvYuv420pEnabled(): Boolean?
    fun saveAndroidLibmpvYuv420pEnabled(enabled: Boolean)
    fun loadDecoderPriority(): Int?
    fun saveDecoderPriority(priority: Int)
    fun loadMapDV7ToHevc(): Boolean?
    fun saveMapDV7ToHevc(enabled: Boolean)
    fun loadTunnelingEnabled(): Boolean?
    fun saveTunnelingEnabled(enabled: Boolean)
    fun loadStreamAutoPlayMode(): String?
    fun saveStreamAutoPlayMode(mode: String)
    fun loadStreamAutoPlaySource(): String?
    fun saveStreamAutoPlaySource(source: String)
    fun loadStreamAutoPlaySelectedAddons(): Set<String>?
    fun saveStreamAutoPlaySelectedAddons(addons: Set<String>)
    fun loadStreamAutoPlaySelectedPlugins(): Set<String>?
    fun saveStreamAutoPlaySelectedPlugins(plugins: Set<String>)
    fun loadStreamAutoPlayRegex(): String?
    fun saveStreamAutoPlayRegex(regex: String)
    fun loadStreamAutoPlayTimeoutSeconds(): Int?
    fun saveStreamAutoPlayTimeoutSeconds(seconds: Int)
    fun loadSkipIntroEnabled(): Boolean?
    fun saveSkipIntroEnabled(enabled: Boolean)
    fun loadAnimeSkipEnabled(): Boolean?
    fun saveAnimeSkipEnabled(enabled: Boolean)
    fun loadAnimeSkipClientId(): String?
    fun saveAnimeSkipClientId(clientId: String)

    fun loadIntroDbApiKey(): String?
    fun saveIntroDbApiKey(apiKey: String)
    fun loadIntroSubmitEnabled(): Boolean?
    fun saveIntroSubmitEnabled(enabled: Boolean)
    fun loadPlaybackMode(): String?
    fun savePlaybackMode(mode: String)
    fun loadPlaybackAllowTorrentAutopick(): Boolean?
    fun savePlaybackAllowTorrentAutopick(enabled: Boolean)
    fun loadPlaybackQualityTiers(): String?
    fun savePlaybackQualityTiers(payload: String)
    fun loadPlaybackMeteredCapHeight(): Int?
    fun savePlaybackMeteredCapHeight(height: Int)
    fun loadPlaybackAutoDownshift(): Boolean?
    fun savePlaybackAutoDownshift(enabled: Boolean)

    /**
     * Whether the mode selector has been shown, tracked separately from the mode itself.
     *
     * Without this, "chose Classic" and "never chose" are the same stored value, so the
     * selector would either reappear forever or never reach an existing install.
     */
    fun loadPlaybackModeSelectorSeen(): Boolean?
    fun savePlaybackModeSelectorSeen(seen: Boolean)
    fun loadStreamAutoPlayNextEpisodeEnabled(): Boolean?
    fun saveStreamAutoPlayNextEpisodeEnabled(enabled: Boolean)
    fun loadStreamAutoPlayPreferBingeGroup(): Boolean?
    fun saveStreamAutoPlayPreferBingeGroup(enabled: Boolean)
    fun loadStreamAutoPlayReuseBingeGroup(): Boolean?
    fun saveStreamAutoPlayReuseBingeGroup(enabled: Boolean)
    fun loadNextEpisodeThresholdMode(): String?
    fun saveNextEpisodeThresholdMode(mode: String)
    fun loadNextEpisodeThresholdPercent(): Float?
    fun saveNextEpisodeThresholdPercent(percent: Float)
    fun loadNextEpisodeThresholdMinutesBeforeEnd(): Float?
    fun saveNextEpisodeThresholdMinutesBeforeEnd(minutes: Float)
    fun loadUseLibass(): Boolean?
    fun saveUseLibass(enabled: Boolean)
    fun loadLibassRenderType(): String?
    fun saveLibassRenderType(renderType: String)
    fun loadIosVideoOutputPreset(): String?
    fun saveIosVideoOutputPreset(preset: String)
    fun loadIosToneMappingMode(): String?
    fun saveIosToneMappingMode(mode: String)
    fun loadIosTargetPrimaries(): String?
    fun saveIosTargetPrimaries(primaries: String)
    fun loadIosTargetTransfer(): String?
    fun saveIosTargetTransfer(transfer: String)
    fun loadIosHardwareDecoderMode(): String?
    fun saveIosHardwareDecoderMode(mode: String)
    fun loadIosAudioOutputMode(): String?
    fun saveIosAudioOutputMode(mode: String)
    fun loadIosExtendedDynamicRangeEnabled(): Boolean?
    fun saveIosExtendedDynamicRangeEnabled(enabled: Boolean)
    fun loadIosTargetColorspaceHintEnabled(): Boolean?
    fun saveIosTargetColorspaceHintEnabled(enabled: Boolean)
    fun loadIosHdrComputePeakEnabled(): Boolean?
    fun saveIosHdrComputePeakEnabled(enabled: Boolean)
    fun loadIosDebandEnabled(): Boolean?
    fun saveIosDebandEnabled(enabled: Boolean)
    fun loadIosInterpolationEnabled(): Boolean?
    fun saveIosInterpolationEnabled(enabled: Boolean)
    fun loadIosBrightness(): Int?
    fun saveIosBrightness(value: Int)
    fun loadIosContrast(): Int?
    fun saveIosContrast(value: Int)
    fun loadIosSaturation(): Int?
    fun saveIosSaturation(value: Int)
    fun loadIosGamma(): Int?
    fun saveIosGamma(value: Int)
    fun exportToSyncPayload(): JsonObject
    fun replaceFromSyncPayload(payload: JsonObject)
}
