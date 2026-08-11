// Neighbour stub. `PlaybackModeModels.kt` carries the serializable tier/pin types and reaches
// kotlinx.serialization; only these members are read by the router and its test.
package com.nuvio.app.features.playback

enum class PlaybackMode {
    CLASSIC, STREAMLINED, INSTANT;

    val isSelectable: Boolean get() = this != INSTANT

    companion object {
        val Default = CLASSIC
        fun fromStorage(value: String?): PlaybackMode =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: Default
        fun selectableFromStorage(value: String?): PlaybackMode =
            fromStorage(value).let { if (it.isSelectable) it else STREAMLINED }
    }
}
