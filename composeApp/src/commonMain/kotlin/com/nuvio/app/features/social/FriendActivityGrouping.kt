package com.nuvio.app.features.social

/**
 * Recently Watched, grouped by title rather than by run.
 *
 * The server pages activity as *runs* - one friend's contiguous stretch on one title - which is the
 * right unit for paging and the wrong unit for reading: one binge split across two sittings arrived as
 * two identical Daredevil cards, and three friends on the same film as three. A person scanning this
 * shelf asks "what have my friends been into", so the title is the row and the friends are its detail.
 *
 * Pure and import-free (beyond the social models) so the pure suites can execute it. Grouping runs
 * over every page loaded so far, and the group key is the `contentId`, so a `LazyList` keyed by it
 * stays stable as later pages fold more runs into rows that are already on screen.
 */

const val FriendActivityHomeGroupLimit = 12
private const val FriendActivityAvatarStackLimit = 3

data class FriendActivityGroup(
    val contentId: String,
    val contentType: String,
    val title: String,
    val poster: String?,
    val background: String?,
    /** Distinct friends, the most recent first. */
    val friends: List<SocialProfileSummary>,
    /** The newest run in the group. Its episode is the one the context line leads with. */
    val latestRun: RecentActivityRun,
    /** Every run folded into this row, newest first. */
    val runs: List<RecentActivityRun>,
    val totalEvents: Int,
    val lastEventTime: String,
    /** [lastEventTime] parsed, or null when the server sent something unreadable. */
    val lastEventMs: Long?,
) {
    val isEpisodic: Boolean
        get() = latestRun.season != null || latestRun.episode != null

    /** Avatars drawn in the stack; the rest collapse into [avatarOverflow]. */
    val stackedFriends: List<SocialProfileSummary> get() = friends.take(FriendActivityAvatarStackLimit)
    val avatarOverflow: Int get() = (friends.size - FriendActivityAvatarStackLimit).coerceAtLeast(0)
}

/**
 * Merges runs sharing a `contentId`. Order is the order the newest run of each title first appears in
 * [runs] - the server already sorts by `last_event_time` descending, and re-sorting on a parsed
 * timestamp would reorder rows whenever one of them failed to parse.
 */
fun groupFriendActivity(runs: List<RecentActivityRun>): List<FriendActivityGroup> {
    val order = LinkedHashMap<String, MutableList<RecentActivityRun>>()
    runs.forEach { run -> order.getOrPut(run.contentId) { mutableListOf() } += run }
    return order.map { (contentId, group) ->
        // Newest first within the group. Unparseable timestamps keep their server order.
        val sorted = group.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<RecentActivityRun>> {
                    parseSocialTimestampMs(it.value.lastEventTime) ?: Long.MIN_VALUE
                }.thenBy { it.index },
            )
            .map { it.value }
        val latest = sorted.first()
        FriendActivityGroup(
            contentId = contentId,
            contentType = latest.contentType,
            title = latest.title,
            poster = sorted.firstNotNullOfOrNull { it.poster?.takeIf(String::isNotBlank) },
            background = sorted.firstNotNullOfOrNull { it.background?.takeIf(String::isNotBlank) },
            friends = sorted.map { it.profile }.distinctBy { it.profileId },
            latestRun = latest,
            runs = sorted,
            totalEvents = sorted.sumOf { it.eventCount.coerceAtLeast(1) },
            lastEventTime = latest.lastEventTime,
            lastEventMs = parseSocialTimestampMs(latest.lastEventTime),
        )
    }
}

/** "Seraph", "Seraph & debug", "Seraph + 2". */
fun FriendActivityGroup.friendNamesLabel(): String {
    val names = friends.map { it.displayName.ifBlank { it.handle } }
    return when (names.size) {
        0 -> ""
        1 -> names[0]
        2 -> "${names[0]} & ${names[1]}"
        else -> "${names[0]} + ${names.size - 1}"
    }
}

/**
 * The third line: "Movie", "S1 E5", "S2 E4–E9 · 6 episodes".
 *
 * A run carries only its *latest* episode, so the span is drawn from what the loaded runs name and the
 * count from the events they add up to - whichever is larger, since both undercount in different ways.
 * A span is only drawn inside one season; across seasons the latest episode leads.
 */
fun FriendActivityGroup.contextLabel(): String {
    if (!isEpisodic) return if (contentType.lowercase() == "movie") "Movie" else ""
    val known = runs
        .mapNotNull { run -> run.episode?.let { (run.season ?: 0) to it } }
        .distinct()
    val count = maxOf(totalEvents, known.size)
    val latest = episodeCode(latestRun.season, latestRun.episode)
    val seasons = known.map { it.first }.distinct()
    val lead = if (known.size > 1 && seasons.size == 1) {
        val season = seasons.single()
        val first = known.minOf { it.second }
        val last = known.maxOf { it.second }
        val prefix = if (season > 0) "S$season " else ""
        "${prefix}E$first–E$last"
    } else {
        latest
    }
    return if (count > 1) "$lead · $count episodes" else lead
}

private fun episodeCode(season: Int?, episode: Int?): String = when {
    season != null && episode != null -> "S$season E$episode"
    episode != null -> "E$episode"
    season != null -> "Season $season"
    else -> ""
}

/** "Just now", "5m", "2h", "3d", "2w", "4mo". Future timestamps (clock skew) read as "Just now". */
fun relativeTimeLabel(eventMs: Long?, nowMs: Long): String {
    if (eventMs == null) return ""
    val elapsed = (nowMs - eventMs).coerceAtLeast(0L)
    val minutes = elapsed / 60_000L
    val hours = minutes / 60L
    val days = hours / 24L
    return when {
        minutes < 1L -> "Just now"
        hours < 1L -> "${minutes}m"
        days < 1L -> "${hours}h"
        days < 7L -> "${days}d"
        days < 35L -> "${days / 7L}w"
        else -> "${(days / 30L).coerceAtLeast(1L)}mo"
    }
}

enum class FriendActivityBucket(val label: String) {
    Today("Today"),
    Yesterday("Yesterday"),
    ThisWeek("This week"),
    Earlier("Earlier"),
}

/**
 * Calendar buckets in the viewer's local time. [utcOffsetMs] is passed in rather than read, so the
 * function stays pure; a timestamp that did not parse is "Earlier", never "Today".
 */
fun friendActivityBucket(eventMs: Long?, nowMs: Long, utcOffsetMs: Long = 0L): FriendActivityBucket {
    if (eventMs == null) return FriendActivityBucket.Earlier
    val today = (nowMs + utcOffsetMs).floorDiv(DayMs)
    val day = (eventMs + utcOffsetMs).floorDiv(DayMs)
    return when {
        day >= today -> FriendActivityBucket.Today
        day == today - 1 -> FriendActivityBucket.Yesterday
        day > today - 7 -> FriendActivityBucket.ThisWeek
        else -> FriendActivityBucket.Earlier
    }
}

/** Groups split into buckets, keeping each bucket's internal order and dropping empty ones. */
fun bucketFriendActivity(
    groups: List<FriendActivityGroup>,
    nowMs: Long,
    utcOffsetMs: Long = 0L,
): List<Pair<FriendActivityBucket, List<FriendActivityGroup>>> {
    val byBucket = groups.groupBy { friendActivityBucket(it.lastEventMs, nowMs, utcOffsetMs) }
    return FriendActivityBucket.entries.mapNotNull { bucket -> byBucket[bucket]?.let { bucket to it } }
}

private const val DayMs = 86_400_000L

/**
 * Parses the timestamps PostgREST sends (`2026-09-15T10:04:05.123456+00:00`, `...Z`, or no fraction)
 * into epoch milliseconds. Null for anything else. Hand-rolled so this file stays import-free.
 */
fun parseSocialTimestampMs(value: String): Long? {
    val match = SocialTimestampPattern.matchEntire(value.trim()) ?: return null
    val g = match.groupValues
    val year = g[1].toInt()
    val month = g[2].toInt()
    val day = g[3].toInt()
    val hour = g[4].toInt()
    val minute = g[5].toInt()
    val second = g[6].toInt()
    if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 60) return null
    val millis = g[7].takeIf { it.isNotEmpty() }?.padEnd(3, '0')?.take(3)?.toInt() ?: 0
    val offsetMs = when {
        g[8].isEmpty() || g[8] == "Z" || g[8] == "z" -> 0L
        else -> {
            val sign = if (g[8][0] == '-') -1 else 1
            val digits = g[8].substring(1).replace(":", "")
            val oh = digits.take(2).toIntOrNull() ?: return null
            val om = digits.drop(2).take(2).toIntOrNull() ?: 0
            sign * (oh * 3_600_000L + om * 60_000L)
        }
    }
    val days = daysFromCivil(year, month, day)
    return days * DayMs + hour * 3_600_000L + minute * 60_000L + second * 1_000L + millis - offsetMs
}

private val SocialTimestampPattern =
    Regex("""(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?(Z|z|[+-]\d{2}(?::?\d{2})?)?""")

/** Howard Hinnant's days-from-civil, valid for the proleptic Gregorian calendar. */
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = (if (month <= 2) year - 1 else year).toLong()
    val era = y.floorDiv(400L)
    val yoe = y - era * 400L
    val mp = ((month + 9) % 12).toLong()
    val doy = (153L * mp + 2L) / 5L + day - 1L
    val doe = yoe * 365L + yoe / 4L - yoe / 100L + doy
    return era * 146_097L + doe - 719_468L
}
