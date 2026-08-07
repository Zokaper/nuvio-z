package com.nuvio.app.features.playback

import com.nuvio.app.features.downloads.DownloadPreset
import com.nuvio.app.features.downloads.VideoResolution

/**
 * Which download entry point a playback mode opens.
 *
 * The mode changes *how the download is started*, never how it runs. `DownloadsRepository`,
 * the queue, the transfer stack and `PresetSourceSelector` are untouched by everything here -
 * that separation is deliberate, because the download stack is mid-reliability-pass and must
 * not be destabilised by a product change (see `AGENTS.md`, "What A Download Has To Be").
 */
sealed class DownloadEntryDecision {
    abstract val reason: String

    /** Today's behaviour: pick a preset, let the picker choose the file. */
    data class ShowPresetDialog(override val reason: String) : DownloadEntryDecision()

    /**
     * Open the source list so the user picks the exact release to download.
     *
     * Not new construction: `StreamsScreen` already downloads a chosen source
     * (`enqueueWithPreset`), and the manual path already exists as the fallback when
     * automatic selection fails. This promotes it to the first choice for Classic.
     */
    data class ChooseSourceManually(override val reason: String) : DownloadEntryDecision()

    /** Instant: no dialog. Start with the preset that matches the connection. */
    data class StartWithPreset(override val reason: String) : DownloadEntryDecision()
}

object PlaybackModeDownloadRouter {

    /**
     * [isSingleItem] is the caller's read of the download scope: one episode or one movie,
     * as opposed to a season or a multi-season batch.
     *
     * It is why Classic is not unconditionally manual. Asking someone to hand-pick a release
     * for each of twenty episodes is not "full control", it is a chore - so a Classic user
     * choosing a season still gets the preset dialog, and only a single item is worth a
     * source list.
     */
    fun decide(mode: PlaybackMode, isSingleItem: Boolean): DownloadEntryDecision = when (mode) {
        PlaybackMode.CLASSIC ->
            if (isSingleItem) {
                DownloadEntryDecision.ChooseSourceManually(
                    "Classic downloads a single item by picking the release",
                )
            } else {
                DownloadEntryDecision.ShowPresetDialog(
                    "Classic falls back to presets for multi-item scopes",
                )
            }

        PlaybackMode.STREAMLINED ->
            DownloadEntryDecision.ShowPresetDialog("Streamlined keeps the preset dialog")

        PlaybackMode.INSTANT ->
            DownloadEntryDecision.StartWithPreset("Instant starts without asking")
    }

    /**
     * The preset that best fits [ceiling], for Instant's no-dialog start.
     *
     * A download preset is a *storage* budget, and what the connection can sustain is a
     * bandwidth one; resolution is the only axis on which they are comparable, so it is the
     * only one used. The highest preset that does not exceed the ceiling wins, falling back
     * to the smallest preset when every one of them does.
     *
     * This is the download *entry point* only - it never reaches the queue or the transfer
     * stack. Playback does not come here: it costs real sources instead of guessing from
     * the connection, which it can only do because it has a candidate list and this does not.
     *
     * Returns null only when there are no presets at all.
     */
    fun presetForResolution(presets: List<DownloadPreset>, ceiling: VideoResolution?): DownloadPreset? {
        if (presets.isEmpty()) return null
        ceiling ?: return presets.smallest()
        return presets
            .filter { it.targetResolution.height <= ceiling.height }
            .maxByOrNull { it.targetResolution.height }
            ?: presets.smallest()
    }

    private fun List<DownloadPreset>.smallest(): DownloadPreset =
        minByOrNull { it.targetResolution.height } ?: first()
}
