package com.nuvio.app.features.streams

data class StreamLaunch(
    val profileId: Int,
    val type: String,
    val videoId: String,
    val parentMetaId: String? = null,
    val parentMetaType: String? = null,
    val title: String,
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val pauseDescription: String? = null,
    val resumePositionMs: Long? = null,
    val resumeProgressFraction: Float? = null,
    val manualSelection: Boolean = false,
    val startFromBeginning: Boolean = false,
    /**
     * The user came here to download, not to watch.
     *
     * Classic's download entry point opens this same source list so the release can be
     * chosen by hand. Without this flag the list would play the tapped source, silently
     * discarding the intent behind the button that was actually pressed.
     */
    val downloadIntent: Boolean = false,
)

object StreamLaunchStore {
    private var nextLaunchId = 1L
    private val launches = mutableMapOf<Long, StreamLaunch>()

    fun put(launch: StreamLaunch): Long {
        val launchId = nextLaunchId++
        launches[launchId] = launch
        return launchId
    }

    fun get(launchId: Long): StreamLaunch? = launches[launchId]

    fun remove(launchId: Long) {
        launches.remove(launchId)
    }

    fun clear() {
        nextLaunchId = 1L
        launches.clear()
    }
}
