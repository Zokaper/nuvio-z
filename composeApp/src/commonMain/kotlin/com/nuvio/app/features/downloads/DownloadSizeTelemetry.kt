package com.nuvio.app.features.downloads

import com.nuvio.app.core.debug.isDebugBuild

/**
 * Debug-build samples for calibrating the size levels (Phase 9). The GB/hour numbers in
 * [DownloadSizeLevels] are provisional; these lines are the real candidate distributions they
 * get calibrated against.
 *
 * One `size_sample` line per discovery (split into parts of [SOURCES_PER_LINE]), one `size_pick`
 * per decided entry. Each source is `height:MB:GBh:cache:quality:codec:hdr:dur`:
 * - `GBh` is size over runtime - the source's own duration when it reported one (`dur=s`),
 *   else the title's (`dur=t`), else unknown (`na`);
 * - `cache` is the selector's evidence: `C` cached, `H` plain HTTP, `N` not usable.
 *
 * **Nothing identifying leaves this file:** no URL, header, token, file name, stream title,
 * provider, addon or title. Quality and codec are reduced to short tokens of safe characters.
 */
internal object DownloadSizeTelemetry {
    const val SOURCES_PER_LINE = 20
    const val MAX_SOURCES = 80

    /** Test seam; debug builds only in production. */
    internal var enabled: () -> Boolean = { isDebugBuild }

    fun discovered(target: DownloadTarget, candidates: List<DownloadSourceCandidate>) {
        if (!enabled()) return
        runCatching {
            sampleLines(
                isEpisode = target.isEpisode,
                runtimeMinutes = target.runtimeMinutes,
                sources = candidates.map { it.facts to DownloadSourceSelector.cacheEvidence(it) },
            ).forEach { DownloadDiagnostics.note("size_sample", it) }
        }
    }

    fun picked(entry: DownloadBatchEntry) {
        if (!enabled()) return
        val facts = (entry.selection as? SourceSelectionResult.Selected)?.facts
            ?: (entry.selection as? SourceSelectionResult.ApprovalNeeded)?.facts
            ?: return
        DownloadDiagnostics.note(
            "size_pick",
            "kind=${kindOf(entry.season != null)} runtime=${entry.runtimeMinutes ?: "na"} " +
                "decision=${entry.decision?.name ?: entry.state.name} source=${sourceToken(facts, null, entry.runtimeMinutes)}",
        )
    }

    internal fun sampleLines(
        isEpisode: Boolean,
        runtimeMinutes: Int?,
        sources: List<Pair<SourceFacts, DownloadCacheEvidence>>,
    ): List<String> {
        val kept = spread(sources, MAX_SOURCES)
        // `sample=spread` marks lines taken across the whole list; earlier builds kept the first 80.
        val head = "kind=${kindOf(isEpisode)} runtime=${runtimeMinutes ?: "na"} total=${sources.size} sample=spread"
        if (kept.isEmpty()) return listOf("$head part=1/1 sources=")
        val parts = kept.chunked(SOURCES_PER_LINE)
        return parts.mapIndexed { index, part ->
            "$head part=${index + 1}/${parts.size} sources=" +
                part.joinToString(",") { (facts, evidence) -> sourceToken(facts, evidence, runtimeMinutes) }
        }
    }

    /**
     * At most [max] of [items], evenly spaced from first to last. Addons list their biggest files
     * first, so keeping the head (as `.57`-`.59` did) dropped the small files the Small and Medium
     * levels are calibrated against.
     */
    internal fun <T> spread(items: List<T>, max: Int): List<T> =
        if (items.size <= max) items else List(max) { index -> items[index * items.size / max] }

    internal fun sourceToken(facts: SourceFacts, evidence: DownloadCacheEvidence?, runtimeMinutes: Int?): String {
        val height = facts.resolution?.height?.toString() ?: "na"
        val bytes = facts.sizeBytes
        val megabytes = bytes?.let { (it / 1_000_000L).toString() } ?: "na"
        val sourceHours = facts.durationSeconds?.takeIf { it > 0 }?.let { it / 3600.0 }
        val titleHours = runtimeMinutes?.takeIf { it > 0 }?.let { it / 60.0 }
        val hours = sourceHours ?: titleHours
        val gbh = if (bytes != null && hours != null) twoDecimals(bytes / 1_000_000_000.0 / hours) else "na"
        val duration = when {
            sourceHours != null -> "s"
            titleHours != null -> "t"
            else -> "na"
        }
        val cache = when (evidence) {
            DownloadCacheEvidence.CACHED -> "C"
            DownloadCacheEvidence.PLAIN_HTTP -> "H"
            DownloadCacheEvidence.NOT_USABLE -> "N"
            null -> "-"
        }
        val hdr = if (facts.dynamicRange.any { it != "SDR" }) "hdr" else "sdr"
        return listOf(height, megabytes, gbh, cache, safeToken(facts.releaseQuality), safeToken(facts.codec), hdr, duration)
            .joinToString(":")
    }

    private fun kindOf(isEpisode: Boolean) = if (isEpisode) "episode" else "movie"

    /**
     * Letters, digits and `-+.` only, at most 12 characters: a tag, never free text. Anything
     * URL- or header-shaped is dropped whole rather than filtered into a fragment of itself.
     */
    internal fun safeToken(value: String?): String {
        if (value != null && value.any { it in "/:?=@&%#" }) return "other"
        val cleaned = value.orEmpty().filter { it.isLetterOrDigit() || it == '-' || it == '+' || it == '.' }.take(12)
        return cleaned.ifEmpty { "na" }
    }

    private fun twoDecimals(value: Double): String {
        val hundredths = kotlin.math.round(value * 100.0).toLong()
        val whole = hundredths / 100
        val fraction = (hundredths % 100).toString().padStart(2, '0')
        return "$whole.$fraction"
    }
}
