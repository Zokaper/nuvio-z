package com.nuvio.app.features.player

import android.app.Application
import android.content.Context
import androidx.compose.ui.Modifier
import com.nuvio.app.features.playback.PlaybackMode
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.WatchProgressStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Replays a desktop mpv open against the real picker: tracks appear mid-load, mpv has already made its own
 * pick (the file's default-flagged track), the audio label gains channel info a poll later, and addon
 * subtitles arrive late. Whatever mpv chose, the picker's choice must be what ends up on screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SubtitleAutoPickScenarioTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        listOf("nuvio_player_track_preferences", "nuvio_watch_progress", "nuvio_player_settings").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        PlayerTrackPreferenceStorage.initialize(context)
        PlayerSettingsStorage.initialize(context)
        WatchProgressStorage.initialize(context)
        WatchProgressRepository.clearLocalState()
    }

    @After
    fun cleanup() {
        scope.cancel()
    }

    private val webDlSubs = listOf(
        SubtitleTrack(index = 0, id = "1", label = "English [Forced]", language = "en", isForced = true),
        SubtitleTrack(index = 1, id = "2", label = "English", language = "en"),
        SubtitleTrack(index = 2, id = "3", label = "English [SDH]", language = "en"),
        SubtitleTrack(index = 3, id = "4", label = "Spanish (Latin America)", language = "es", isDefault = true),
        SubtitleTrack(index = 4, id = "5", label = "French", language = "fr"),
    )

    private val openSubtitles = listOf(
        AddonSubtitle(id = "os-1", url = "https://os/en1.srt", language = "eng", display = "English", addonName = "OpenSubtitles v3"),
        AddonSubtitle(id = "os-2", url = "https://os/es1.srt", language = "spa", display = "Spanish", addonName = "OpenSubtitles v3"),
    )

    @Test
    fun picksOverrideWhateverMpvSelected() {
        for (mpvDefault in listOf(3, -1, 0)) {
            for (addonsLate in listOf(true, false)) {
                val case = "mpvPreselected=$mpvDefault addonsLate=$addonsLate"
                assertEquals("English [Forced]", run(true, mpvDefault, addonsLate, webDlSubs), "forced on, $case")
                assertEquals("English", run(false, mpvDefault, addonsLate, webDlSubs), "forced off, $case")
            }
        }
    }

    @Test
    fun noForcedTrackMeansNoneInForcedModeAndEnglishOtherwise() {
        val noForced = webDlSubs.filterNot { it.isForced }.mapIndexed { i, t -> t.copy(index = i) }
        assertEquals("none", run(true, 2, true, noForced))
        assertEquals("English", run(false, 2, true, noForced))
    }

    private fun run(forced: Boolean, mpvDefault: Int, addonsLate: Boolean, subs: List<SubtitleTrack>): String {
        val mpv = FakeMpv(subs, mpvDefault)
        val runtime = runtime(forced, mpv)
        // Opening: no tracks yet.
        runtime.playbackSnapshot = PlayerPlaybackSnapshot(isLoading = true)
        runtime.refreshTracks()
        // Tracks appear while still loading; mpv has made its own pick.
        mpv.opened = true
        runtime.refreshTracks()
        runtime.playbackSnapshot = PlayerPlaybackSnapshot(isLoading = false)
        if (addonsLate) runtime.isLoadingAddonSubtitles = true else runtime.addonSubtitles = openSubtitles
        repeat(3) { runtime.refreshTracks() }
        mpv.channelsKnown = true
        repeat(3) { runtime.refreshTracks() }
        if (addonsLate) {
            runtime.addonSubtitles = openSubtitles
            runtime.isLoadingAddonSubtitles = false
        }
        repeat(5) { runtime.refreshTracks() }
        return mpv.externalUrl ?: mpv.selectedSub?.let { subs[it].label } ?: "none"
    }

    private fun runtime(forced: Boolean, mpv: FakeMpv) = PlayerScreenRuntime(
        PlayerScreenArgs(
            profileId = 1, title = "Movie", sourceUrl = "https://example.com/movie.mkv", sourceAudioUrl = null,
            sourceHeaders = emptyMap(), sourceResponseHeaders = emptyMap(), streamType = null, providerName = "Test",
            streamTitle = "Movie", streamSubtitle = null, initialBingeGroup = null, pauseDescription = null,
            onBack = {}, onOpenInExternalPlayer = null, onOpenExternalUrl = null, modifier = Modifier, logo = null,
            poster = null, background = null, seasonNumber = null, episodeNumber = null, episodeTitle = null,
            episodeThumbnail = null, contentType = "movie", videoId = "tt1", parentMetaId = "tt1",
            parentMetaType = "movie", providerAddonId = "test", torrentInfoHash = null, torrentFileIdx = null,
            torrentFilename = null, torrentTrackers = emptyList(), initialPositionMs = 0L,
            initialProgressFraction = null,
        ),
    ).apply {
        scope = this@SubtitleAutoPickScenarioTest.scope
        playerSettingsUiState = PlayerSettingsUiState(
            preferredAudioLanguage = "en",
            preferredSubtitleLanguage = "en",
            subtitleStyle = SubtitleStyleState.DEFAULT.copy(useForcedSubtitles = forced),
            playbackMode = PlaybackMode.INSTANT,
            playbackPreferEmbeddedSubtitles = true,
        )
        playerController = mpv
        resetIdentityStateIfNeeded()
        playerControllerSourceUrl = activeSourceUrl
    }

    private class FakeMpv(private val subs: List<SubtitleTrack>, mpvDefault: Int) : PlayerEngineController {
        var opened = false
        var channelsKnown = false
        var selectedSub: Int? = mpvDefault.takeIf { it >= 0 }
        var selectedAudio = 0
        var externalUrl: String? = null

        override fun getAudioTracks(): List<AudioTrack> = if (!opened) emptyList() else listOf(
            AudioTrack(0, "1", if (channelsKnown) "DDP5.1 (5.1(side), E-AC-3)" else "DDP5.1 (E-AC-3)", "en", selectedAudio == 0),
            AudioTrack(1, "2", "Spanish (E-AC-3)", "es", selectedAudio == 1),
        )

        override fun getSubtitleTracks(): List<SubtitleTrack> =
            if (!opened) emptyList() else subs.map { it.copy(isSelected = it.index == selectedSub && externalUrl == null) }

        override fun selectSubtitleTrack(index: Int) {
            selectedSub = index.takeIf { it >= 0 }
        }

        override fun setSubtitleUri(url: String) {
            externalUrl = url
        }

        override fun clearExternalSubtitle() {
            externalUrl = null
        }

        override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
            externalUrl = null; selectedSub = trackIndex.takeIf { it >= 0 }
        }

        override fun applyAudioLanguagePreferences(languages: List<String>) {
            selectedAudio = 0
        }

        override fun selectAudioTrack(index: Int) {
            selectedAudio = index
        }

        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun seekBy(offsetMs: Long) = Unit
        override fun retry() = Unit
        override fun setPlaybackSpeed(speed: Float) = Unit
    }
}
