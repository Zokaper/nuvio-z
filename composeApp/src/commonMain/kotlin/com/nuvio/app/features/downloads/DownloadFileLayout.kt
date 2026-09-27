package com.nuvio.app.features.downloads

/**
 * Where a finished download lives on disk, as a path under the downloads folder that a person
 * browsing it can read (Phase 9 closeout):
 *
 * ```
 * Modern Family/Season 01/S01E01 - Pilot.mkv
 * Modern Family/Specials/S00E02 - Behind the Scenes.mkv
 * Dune - Part Two (2024)/Dune - Part Two.mkv
 * ```
 *
 * **Presentation only.** The store stays authoritative: nothing reads a title, season or episode
 * back out of these names, and every file is found through its persisted `localFileUri`. Only
 * finished files are ever placed here - partial `.part` files stay flat, where the transfer engine
 * keeps them.
 *
 * Pure, so every naming and collision rule is tested without a disk; [DownloadFileOrganizer] does
 * the moving.
 */
internal object DownloadFileLayout {
    private const val TitleMaxLength = 80
    private const val EpisodeTitleMaxLength = 60 // keeps a Windows path well under 260
    private const val MaxNumberedCopies = 99

    /** A download already placed under the downloads folder, as the collision rules see it. */
    data class Placed(val parentMetaId: String, val relativePath: String)

    /**
     * The path [item] should move to, given what is already [placed] (every other download's
     * organized path) and what [exists] on disk. Deterministic for the same inputs.
     *
     * A title folder belongs to one title: if another title already uses the name (a remake, two
     * films of one name), the year is tried, then a short tag derived from the title's id. A file
     * name taken inside the folder (the same episode downloaded for two profiles) gets " (2)",
     * " (3)"... Comparisons ignore case, since Windows and macOS disks do.
     */
    fun target(
        item: DownloadItem,
        year: Int?,
        placed: List<Placed>,
        exists: (String) -> Boolean,
    ): String {
        val candidates = titleFolderCandidates(item, year)
        val folder = candidates.firstOrNull { candidate ->
            val prefix = "$candidate/".lowercase()
            placed.none { it.parentMetaId != item.parentMetaId && it.relativePath.lowercase().startsWith(prefix) }
        } ?: "${candidates.first()} [${shortTag(item.parentMetaId + "/" + item.id)}]"
        val directory = listOfNotNull(folder, seasonFolder(item)).joinToString("/")
        val baseName = fileBaseName(item)
        val extension = extensionOf(item.fileName)
        val takenPaths = placed.map { it.relativePath.lowercase() }.toSet()
        fun candidate(suffix: String) = "$directory/$baseName$suffix.$extension"
        fun free(path: String) = path.lowercase() !in takenPaths && !exists(path)

        candidate("").takeIf(::free)?.let { return it }
        for (copy in 2..MaxNumberedCopies) {
            candidate(" ($copy)").takeIf(::free)?.let { return it }
        }
        return candidate(" [${shortTag(item.id)}]")
    }

    /** Folder names to try for [item]'s title, most readable first. */
    fun titleFolderCandidates(item: DownloadItem, year: Int?): List<String> {
        val name = safeName(item.title, TitleMaxLength).ifBlank { "Untitled" }
        val withYear = year?.let { "$name ($it)" }
        val tagged = "$name [${shortTag(item.parentMetaId)}]"
        return if (item.isEpisode) {
            listOfNotNull(name, withYear, tagged).distinct()
        } else {
            // A film's folder carries its year when it is known: "Dune (1984)" and "Dune (2021)".
            listOfNotNull(withYear ?: name, tagged).distinct()
        }
    }

    /** "Season 01", or "Specials" for season 0; null for a film. */
    fun seasonFolder(item: DownloadItem): String? {
        val season = item.seasonNumber?.takeIf { item.isEpisode } ?: return null
        return if (season == 0) "Specials" else "Season ${pad(season)}"
    }

    /** "S01E01 - Pilot", "S01E01" without an episode title, or the film's name. */
    fun fileBaseName(item: DownloadItem): String {
        if (!item.isEpisode) return safeName(item.title, TitleMaxLength).ifBlank { "Untitled" }
        val code = "S${pad(item.seasonNumber ?: 0)}E${pad(item.episodeNumber ?: 0)}"
        val episodeTitle = safeName(item.episodeTitle, EpisodeTitleMaxLength)
        return if (episodeTitle.isBlank()) code else "$code - $episodeTitle"
    }

    /** The finished file's extension, from the name the engine gave it ("mkv"), else "mp4". */
    fun extensionOf(fileName: String): String {
        val suffix = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return if (suffix.length in 2..5 && suffix.all { it.isLetterOrDigit() }) suffix else "mp4"
    }

    /** The first four-digit year in a release line such as "2009-2020" or "2024". */
    fun yearOf(releaseInfo: String?): Int? =
        releaseInfo?.let { Regex("""(?<!\d)(1[89]\d\d|2\d\d\d)(?!\d)""").find(it)?.value?.toIntOrNull() }

    /**
     * A name that is safe as one path segment on Windows, macOS, Linux, Android and iOS, and still
     * reads as the original: "Dune: Part Two" -> "Dune - Part Two". Never contains a separator,
     * never "." or "..", never a Windows device name, never ends in a space or a period.
     */
    fun safeName(raw: String?, maxLength: Int): String {
        val mapped = buildString {
            val source = raw.orEmpty()
            var index = 0
            while (index < source.length) {
                val char = source[index]
                when {
                    char == ':' -> append(if (source.getOrNull(index + 1) == ' ') " -" else "-")
                    char == '/' || char == '\\' || char == '|' -> append('-')
                    char == '"' -> append('\'')
                    char == '*' || char == '?' || char == '<' || char == '>' -> Unit
                    char.code < 0x20 || char.code == 0x7F -> append(' ')
                    else -> append(char)
                }
                index++
            }
        }
        var name = mapped.replace(Regex("\\s+"), " ").trim().trimStart('.').trimEnd('.', ' ')
        if (name.length > maxLength) {
            name = name.take(maxLength)
            // Never leave half of a surrogate pair at the cut.
            if (name.isNotEmpty() && name.last().isHighSurrogate()) name = name.dropLast(1)
            name = name.trimEnd('.', ' ')
        }
        if (name.substringBefore('.').uppercase() in WindowsDeviceNames) name = "${name}_"
        return name
    }

    private fun pad(number: Int): String = number.toString().padStart(2, '0')

    /** Six hex digits of a stable FNV-1a hash: short, deterministic, and never read back. */
    fun shortTag(value: String): String {
        var hash = 0x811c9dc5.toInt()
        value.forEach { hash = (hash xor it.code) * 0x01000193 }
        return (hash.toUInt() and 0xFFFFFFu).toString(16).padStart(6, '0')
    }

    private val WindowsDeviceNames: Set<String> =
        setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }
}
