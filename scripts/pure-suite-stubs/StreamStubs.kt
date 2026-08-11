// Neighbour stubs, continued. See Neighbours.kt for the rule: nothing under test is stubbed.
//
// `isTorrentStream` and `playableDirectUrl` are derived exactly as the real StreamItem derives
// them for the fields these tests set, because the protocol gate in PlaybackSourceSelector
// branches on both and a stub that answered differently would be testing the stub.

package com.nuvio.app.features.streams

data class StreamProxyHeaders(
    val request: Map<String, String>? = null,
    val response: Map<String, String>? = null,
)

data class StreamBehaviorHints(
    val filename: String? = null,
    val videoSize: Long? = null,
    val bingeGroup: String? = null,
    val proxyHeaders: StreamProxyHeaders? = null,
)

class StreamClientResolve(val isDirectDebridCandidate: Boolean = false)

data class StreamItem(
    val name: String? = null,
    val url: String? = null,
    val infoHash: String? = null,
    val addonName: String? = null,
    val addonId: String = "",
    val behaviorHints: StreamBehaviorHints = StreamBehaviorHints(),
    val clientResolve: StreamClientResolve? = null,
    val isAddonDebridCandidate: Boolean = false,
) {
    val playableDirectUrl: String?
        get() = url?.takeIf { !it.startsWith("magnet:") && !it.startsWith("torrent://") }

    val isDirectDebridStream: Boolean
        get() = clientResolve?.isDirectDebridCandidate == true

    val isTorrentStream: Boolean
        get() = !isDirectDebridStream && (
            !infoHash.isNullOrBlank() ||
                url?.startsWith("magnet:") == true ||
                url?.startsWith("torrent://") == true
            )

    val p2pInfoHash: String?
        get() = infoHash?.takeIf { it.isNotBlank() }

    val hasPlayableSource: Boolean
        get() = playableDirectUrl != null || !infoHash.isNullOrBlank() || clientResolve != null
}
