package com.nuvio.app.core.sync

import android.app.Application
import android.content.Context
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.HomeCatalogSettingsStorage
import com.nuvio.app.features.home.HomePresentationSync
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsStorage
import com.nuvio.app.features.shuffle.EpisodeShuffleRepository
import com.nuvio.app.features.shuffle.EpisodeShuffleStorage
import com.nuvio.app.features.shuffle.EpisodeShuffleSync
import com.nuvio.app.features.streams.StreamBadgeSettingsRepository
import com.nuvio.app.features.streams.StreamBadgeSettingsStorage
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The settings the setup + settings pass started syncing, and the two storage faults it fixed.
 * Anything Advanced Setup writes must reach a second device of the same family, or the wizard lies.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ZProfileSyncCoverageTest {
    @BeforeTest
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        listOf(
            "nuvio_player_settings",
            "nuvio_stream_badge_settings",
            "episode_shuffle",
            "nuvio_home_catalog_settings",
        ).forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        PlayerSettingsStorage.initialize(context)
        StreamBadgeSettingsStorage.initialize(context)
        EpisodeShuffleStorage.initialize(context)
        HomeCatalogSettingsStorage.initialize(context)
        PlayerSettingsRepository.clearLocalState()
        StreamBadgeSettingsRepository.clearLocalState()
        EpisodeShuffleRepository.clearLocalState()
        HomeCatalogSettingsRepository.clearLocalState()
    }

    @AfterTest
    fun clearState() {
        PlayerSettingsRepository.clearLocalState()
        StreamBadgeSettingsRepository.clearLocalState()
        EpisodeShuffleRepository.clearLocalState()
        HomeCatalogSettingsRepository.clearLocalState()
    }

    @Test
    fun legacyPlayerLayoutRoundTripsAndAnOlderPayloadKeepsTheLocalChoice() {
        PlayerSettingsRepository.setUseLegacyPlayerLayout(true)
        val payload = PlayerSettingsStorage.exportToSyncPayload()
        assertEquals(true, payload.decodeSyncBoolean("use_legacy_player_layout"))

        PlayerSettingsRepository.setUseLegacyPlayerLayout(false)
        PlayerSettingsStorage.replaceFromSyncPayload(payload)
        PlayerSettingsRepository.onProfileChanged()
        assertTrue(PlayerSettingsRepository.uiState.value.useLegacyPlayerLayout)

        PlayerSettingsStorage.replaceFromSyncPayload(buildJsonObject {})
        PlayerSettingsRepository.onProfileChanged()
        assertTrue(PlayerSettingsRepository.uiState.value.useLegacyPlayerLayout)
    }

    @Test
    fun addonSubtitleStartupModeRoundTripsAndAnOlderPayloadKeepsTheLocalChoice() {
        PlayerSettingsStorage.saveAddonSubtitleStartupMode("PREFERRED_ONLY")
        val payload = PlayerSettingsStorage.exportToSyncPayload()

        PlayerSettingsStorage.saveAddonSubtitleStartupMode("ALL")
        PlayerSettingsStorage.replaceFromSyncPayload(payload)
        assertEquals("PREFERRED_ONLY", PlayerSettingsStorage.loadAddonSubtitleStartupMode())

        PlayerSettingsStorage.replaceFromSyncPayload(buildJsonObject {})
        assertEquals("PREFERRED_ONLY", PlayerSettingsStorage.loadAddonSubtitleStartupMode())
    }

    @Test
    fun remotePluginSelectionLandsInPluginsNotAddons() {
        PlayerSettingsStorage.saveStreamAutoPlaySelectedAddons(setOf("addon-a"))
        PlayerSettingsStorage.saveStreamAutoPlaySelectedPlugins(setOf("plugin-x"))
        val payload = PlayerSettingsStorage.exportToSyncPayload()

        PlayerSettingsStorage.saveStreamAutoPlaySelectedAddons(setOf("addon-b"))
        PlayerSettingsStorage.saveStreamAutoPlaySelectedPlugins(setOf("plugin-y"))
        PlayerSettingsStorage.replaceFromSyncPayload(payload)

        assertEquals(setOf("addon-a"), PlayerSettingsStorage.loadStreamAutoPlaySelectedAddons())
        assertEquals(setOf("plugin-x"), PlayerSettingsStorage.loadStreamAutoPlaySelectedPlugins())
    }

    @Test
    fun addonLogoRoundTripsAndAnOlderPayloadKeepsTheLocalChoice() {
        StreamBadgeSettingsRepository.setShowAddonLogo(true)
        val payload = StreamBadgeSettingsStorage.exportToSyncPayload()
        assertEquals(true, payload.decodeSyncBoolean("show_addon_logo"))

        StreamBadgeSettingsRepository.setShowAddonLogo(false)
        StreamBadgeSettingsStorage.replaceFromSyncPayload(payload)
        StreamBadgeSettingsRepository.onProfileChanged()
        assertTrue(StreamBadgeSettingsRepository.uiState.value.showAddonLogo)

        StreamBadgeSettingsStorage.replaceFromSyncPayload(buildJsonObject {})
        StreamBadgeSettingsRepository.onProfileChanged()
        assertTrue(StreamBadgeSettingsRepository.uiState.value.showAddonLogo)
    }

    @Test
    fun randomEpisodeAvailabilityTravelsAndAnAbsentFeatureLeavesItAlone() {
        EpisodeShuffleRepository.setAvailable(true)
        val exported = EpisodeShuffleSync.export()

        EpisodeShuffleRepository.setAvailable(false)
        EpisodeShuffleSync.applyFromSync(exported)
        assertTrue(EpisodeShuffleRepository.uiState.value.available)

        EpisodeShuffleSync.applyFromSync(null)
        assertTrue(EpisodeShuffleRepository.uiState.value.available)
        EpisodeShuffleSync.applyFromSync(JsonPrimitive("garbage"))
        assertTrue(EpisodeShuffleRepository.uiState.value.available)
    }

    @Test
    fun homeHeroTravelsAndAnAbsentFeatureLeavesItAlone() {
        HomeCatalogSettingsRepository.setHeroEnabled(false)
        val exported = HomePresentationSync.export()

        HomeCatalogSettingsRepository.setHeroEnabled(true)
        HomePresentationSync.applyFromSync(exported)
        assertFalse(HomeCatalogSettingsRepository.snapshot().heroEnabled)

        HomePresentationSync.applyFromSync(null)
        assertFalse(HomeCatalogSettingsRepository.snapshot().heroEnabled)
    }

    @Test
    fun everyContributorHasAUniqueKey() {
        val keys = zProfileSyncContributors.map { it.key }
        assertEquals(keys.toSet().size, keys.size)
    }
}
