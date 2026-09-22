package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerInitialAudioSelectionTest {

    @Test
    fun resolvePreferredAudioTrackIndex_selectsEnglishWhenPrecededByRussian() {
        val tracks = listOf(
            AudioTrack(index = 0, id = "1", label = "Russian (AC3 5.1)", language = "rus"),
            AudioTrack(index = 1, id = "2", label = "English (TrueHD Atmos 7.1)", language = "eng"),
            AudioTrack(index = 2, id = "3", label = "Ukrainian (E-AC3 5.1)", language = "ukr"),
        )
        val selectedIndex = resolvePreferredAudioTrackIndex(
            tracks = tracks,
            preferredLanguages = listOf("en"),
        )
        assertEquals(1, selectedIndex)
    }

    @Test
    fun resolvePreferredAudioTrackIndex_prioritizesPrimaryLanguageOverEarlierSecondaryLanguage() {
        val tracks = listOf(
            AudioTrack(index = 0, id = "1", label = "Russian", language = "rus"),
            AudioTrack(index = 1, id = "2", label = "English", language = "eng"),
        )
        // Primary English, secondary Russian
        val selectedIndex = resolvePreferredAudioTrackIndex(
            tracks = tracks,
            preferredLanguages = listOf("en", "ru"),
        )
        // Must select English (index 1), NOT Russian (index 0)
        assertEquals(1, selectedIndex)
    }

    @Test
    fun resolvePreferredAudioTrackIndex_handlesIso639CodesAndAliases() {
        val tracks = listOf(
            AudioTrack(index = 0, id = "1", label = "Spanish", language = "spa"),
            AudioTrack(index = 1, id = "2", label = "German", language = "deu"),
            AudioTrack(index = 2, id = "3", label = "French", language = "fra"),
        )
        assertEquals(0, resolvePreferredAudioTrackIndex(tracks, listOf("es")))
        assertEquals(1, resolvePreferredAudioTrackIndex(tracks, listOf("de")))
        assertEquals(2, resolvePreferredAudioTrackIndex(tracks, listOf("fr")))
    }

    @Test
    fun resolvePreferredAudioTrackIndex_matchesRegionalVariants() {
        val tracks = listOf(
            AudioTrack(index = 0, id = "1", label = "English US", language = "en-US"),
            AudioTrack(index = 1, id = "2", label = "Portuguese Brazil", language = "pt-BR"),
        )
        assertEquals(0, resolvePreferredAudioTrackIndex(tracks, listOf("en")))
        assertEquals(1, resolvePreferredAudioTrackIndex(tracks, listOf("pt")))
    }

    @Test
    fun resolvePreferredAudioTrackIndex_fallsBackToTrackLabelWhenLanguageMissing() {
        val tracks = listOf(
            AudioTrack(index = 0, id = "1", label = "Russian AC3 5.1", language = null),
            AudioTrack(index = 1, id = "2", label = "English [Original]", language = null),
        )
        assertEquals(1, resolvePreferredAudioTrackIndex(tracks, listOf("en")))
        assertEquals(0, resolvePreferredAudioTrackIndex(tracks, listOf("ru")))
    }

    @Test
    fun resolvePreferredAudioTrackIndex_returnsNegativeOneWhenNoMatch() {
        val tracks = listOf(
            AudioTrack(index = 0, id = "1", label = "Russian", language = "rus"),
            AudioTrack(index = 1, id = "2", label = "French", language = "fra"),
        )
        assertEquals(-1, resolvePreferredAudioTrackIndex(tracks, listOf("en")))
        assertEquals(-1, resolvePreferredAudioTrackIndex(emptyList(), listOf("en")))
        assertEquals(-1, resolvePreferredAudioTrackIndex(tracks, emptyList()))
    }

    @Test
    fun runtime_doesNotMarkPreferredAudioAppliedWhenTracksAreEmpty() {
        val controller = TestRecordingController(emptyList())
        val runtime = createTestRuntime(controller)

        runtime.applyPreferredAudioTrack(listOf("en"))

        // Controller was called with target languages, but runtime must NOT mark selection applied yet
        assertEquals(listOf(listOf("en")), controller.audioLanguagePreferences)
        assertFalse(runtime.preferredAudioSelectionApplied)
    }

    @Test
    fun runtime_appliesPreferredAudioWhenTracksArriveLater() {
        val tracks = listOf(
            AudioTrack(index = 0, id = "1", label = "Russian", language = "rus"),
            AudioTrack(index = 1, id = "2", label = "English", language = "eng"),
        )
        val controller = TestRecordingController(emptyList())
        val runtime = createTestRuntime(controller)

        // First pass: empty tracks
        runtime.applyPreferredAudioTrack(listOf("en"))
        assertFalse(runtime.preferredAudioSelectionApplied)

        // Tracks arrive
        controller.tracks = tracks
        runtime.audioTracks = tracks

        // Second pass: populated tracks dispatches to controller
        runtime.applyPreferredAudioTrack(listOf("en"))
        assertEquals(2, controller.audioLanguagePreferences.size)

        // Confirmation pass marks selection applied
        runtime.applyPreferredAudioTrack(listOf("en"))
        assertTrue(runtime.preferredAudioSelectionApplied)
    }

    @Test
    fun runtime_manualUserSelectionIsPreservedAndNotOverridden() {
        val tracks = listOf(
            AudioTrack(index = 0, id = "1", label = "Russian", language = "rus"),
            AudioTrack(index = 1, id = "2", label = "English", language = "eng"),
        )
        val controller = TestRecordingController(tracks)
        val runtime = createTestRuntime(controller)
        runtime.audioTracks = tracks

        // User explicitly chooses Russian (track 0)
        runtime.persistAudioPreference(tracks[0])
        controller.audioLanguagePreferences.clear()

        // Automatic audio track application should not override explicit choice
        runtime.applyPreferredAudioTrack(listOf("en"))
        assertTrue(controller.audioLanguagePreferences.isEmpty())
        assertTrue(runtime.isUserExplicitAudioSelection)
    }

    @Test
    fun runtime_refreshAudioTracksIfChangedTriggersWhileLoadingIfTracksReady() {
        val tracks = listOf(
            AudioTrack(index = 0, id = "1", label = "English", language = "eng"),
        )
        val controller = TestRecordingController(tracks)
        val runtime = createTestRuntime(controller)
        runtime.playbackSnapshot = PlayerPlaybackSnapshot(isLoading = true, isPlaying = false)
        runtime.audioTracks = emptyList()
        runtime.preferredAudioSelectionApplied = false

        runtime.refreshAudioTracksIfChanged()

        // Tracks must be populated from controller even though playback is still loading
        assertEquals(tracks, runtime.audioTracks)
        assertEquals(listOf(listOf("en")), controller.audioLanguagePreferences)

        // Next refresh cycle confirms selection
        runtime.refreshAudioTracksIfChanged()
        assertTrue(runtime.preferredAudioSelectionApplied)
    }

    private fun createTestRuntime(controller: TestRecordingController) = PlayerScreenRuntime(
        PlayerScreenArgs(
            profileId = 1,
            title = "Test title",
            sourceUrl = "https://example.com/video.mkv",
            sourceAudioUrl = null,
            sourceHeaders = emptyMap(),
            sourceResponseHeaders = emptyMap(),
            streamType = null,
            providerName = "Test provider",
            streamTitle = "Stream title",
            streamSubtitle = null,
            initialBingeGroup = null,
            pauseDescription = null,
            onBack = {},
            onOpenInExternalPlayer = null,
            onOpenExternalUrl = null,
            modifier = Modifier,
            logo = null,
            poster = null,
            background = null,
            seasonNumber = null,
            episodeNumber = null,
            episodeTitle = null,
            episodeThumbnail = null,
            contentType = "movie",
            videoId = "tt-test-video",
            parentMetaId = "tt-test-video",
            parentMetaType = "movie",
            providerAddonId = null,
            torrentInfoHash = null,
            torrentFileIdx = null,
            torrentFilename = null,
            torrentTrackers = emptyList(),
            initialPositionMs = 0L,
            initialProgressFraction = null,
            contentLanguage = "en",
        ),
    ).apply {
        playerController = controller
        playbackSnapshot = PlayerPlaybackSnapshot(isLoading = false, isPlaying = true)
        playerSettingsUiState = PlayerSettingsUiState(
            preferredAudioLanguage = "en",
            preferredSubtitleLanguage = SubtitleLanguageOption.NONE,
        )
        trackPreferenceRestoreApplied = true
        preferredSubtitleSelectionApplied = true
    }

    private class TestRecordingController(var tracks: List<AudioTrack>) : PlayerEngineController {
        val audioSelections = mutableListOf<Int>()
        val audioLanguagePreferences = mutableListOf<List<String>>()
        var subtitles = emptyList<SubtitleTrack>()

        override fun getAudioTracks() = tracks
        override fun getSubtitleTracks() = subtitles
        override fun applyAudioLanguagePreferences(languages: List<String>) {
            audioLanguagePreferences += languages
        }
        override fun selectAudioTrack(index: Int) {
            audioSelections += index
        }
        override fun selectSubtitleTrack(index: Int) = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun seekBy(offsetMs: Long) = Unit
        override fun retry() = Unit
        override fun setPlaybackSpeed(speed: Float) = Unit
        override fun setSubtitleUri(url: String) = Unit
        override fun clearExternalSubtitle() = Unit
        override fun clearExternalSubtitleAndSelect(trackIndex: Int) = Unit
    }
}
