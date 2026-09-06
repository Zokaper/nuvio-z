package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdvancedSettingsDefaultTest {

    private fun untouched(
        allowTorrentAutopick: Boolean? = null,
        meteredCapHeight: Int? = null,
        streamAutoPlayMode: String? = null,
        streamAutoPlayRegex: String? = null,
        androidPlaybackEngine: String? = null,
        decoderPriority: Int? = null,
    ) = hasTunedAnAdvancedSetting(
        allowTorrentAutopick = allowTorrentAutopick,
        meteredCapHeight = meteredCapHeight,
        streamAutoPlayMode = streamAutoPlayMode,
        streamAutoPlayRegex = streamAutoPlayRegex,
        androidPlaybackEngine = androidPlaybackEngine,
        decoderPriority = decoderPriority,
    )

    @Test
    fun aFreshProfileStartsWithAdvancedHidden() {
        assertFalse(untouched())
    }

    @Test
    fun aProfileThatChangedAnAdvancedSettingKeepsThemVisible() {
        // The case this function exists for: hiding a row the user deliberately turned on
        // would read as the setting having been lost, not as a tidier screen.
        assertTrue(untouched(allowTorrentAutopick = true))
        assertTrue(untouched(meteredCapHeight = 1080))
        assertTrue(untouched(streamAutoPlayRegex = "1080p"))
        assertTrue(untouched(decoderPriority = 2))
    }

    @Test
    fun storedFalseStillCountsAsTouched() {
        // "Never written" is null. An explicit false means the user went in and turned
        // something off, which is every bit as deliberate as turning it on.
        assertTrue(untouched(allowTorrentAutopick = false))
    }
}
