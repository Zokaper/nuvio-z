package com.nuvio.app.features.downloads

/** Import-free policy for choosing and presenting the one download shown by live status UI. */
internal object DownloadsLiveStatusPolicy {
    enum class State {
        FINDING_SOURCES,
        PREPARING,
        WAITING,
        STARTING,
        DOWNLOADING,
        RETRYING,
        PAUSED,
        FAILED,
        COMPLETED,
    }

    data class Candidate(
        val id: String,
        val state: State,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long? = null,
        val queuePosition: Long = 0L,
        val updatedAtEpochMs: Long = 0L,
    )

    data class Presentation(
        val candidate: Candidate,
        val progressPercent: Int?,
        val activeCount: Int = 1,
        val remainingCount: Int = 0,
        val queueSummaryText: String? = null,
    )

    fun select(
        items: List<Candidate>,
        resolvingBatch: Candidate? = null,
        currentSelectedId: String? = null,
    ): Presentation? {
        val eligible = items.filter { it.state != State.COMPLETED && it.state != State.FINDING_SOURCES }
        val activeItems = eligible.filter {
            it.state in setOf(State.DOWNLOADING, State.STARTING, State.PREPARING)
        }
        val remainingItems = eligible.filter {
            it.state in setOf(State.WAITING, State.RETRYING)
        }

        // Sticky primary: if currentSelectedId is still active, prefer keeping it
        val sticky = currentSelectedId?.let { id ->
            activeItems.firstOrNull { it.id == id }
        }

        val primary = sticky ?: eligible
            .sortedWith(
                compareBy<Candidate> { priority(it.state) }
                    .thenBy { it.queuePosition }
                    .thenBy { it.id }
            )
            .firstOrNull()

        val selected = primary ?: resolvingBatch?.takeIf { it.state == State.FINDING_SOURCES } ?: return null
        val percent = selected.totalBytes
            ?.takeIf { it > 0L }
            ?.let { total ->
                ((selected.downloadedBytes.coerceAtLeast(0L).toDouble() / total.toDouble()) * 100.0)
                    .toInt()
                    .coerceIn(0, 100)
            }

        val activeCount = activeItems.size.coerceAtLeast(
            if (selected.state in setOf(State.DOWNLOADING, State.STARTING, State.PREPARING)) 1 else 0
        )
        val remainingCount = remainingItems.count { it.id != selected.id }
        val summary = buildSummaryText(activeCount, remainingCount)

        return Presentation(
            candidate = selected,
            progressPercent = percent,
            activeCount = activeCount,
            remainingCount = remainingCount,
            queueSummaryText = summary,
        )
    }

    fun buildSummaryText(activeCount: Int, remainingCount: Int): String? {
        if (activeCount <= 1 && remainingCount == 0) return null
        return when {
            activeCount > 1 && remainingCount > 0 -> "$activeCount downloading • $remainingCount remaining"
            activeCount > 1 -> "$activeCount downloading"
            remainingCount > 0 -> "$remainingCount remaining"
            else -> null
        }
    }

    private fun priority(state: State): Int = when (state) {
        State.DOWNLOADING -> 0
        State.PREPARING -> 1
        State.RETRYING -> 2
        State.WAITING -> 3
        State.STARTING -> 4
        State.PAUSED -> 5
        State.FAILED -> 6
        State.FINDING_SOURCES -> 7
        State.COMPLETED -> 8
    }
}
