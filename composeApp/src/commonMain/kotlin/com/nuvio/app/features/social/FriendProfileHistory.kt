package com.nuvio.app.features.social

/**
 * A friend's history as the profile page draws it (2026-10-24): one shelf per calendar month, newest
 * first. Within a month a title appears once - a binge is one tile with its episode count - but a
 * title watched in two months appears in both, because each shelf says what they watched then.
 */
data class FriendHistoryMonth(
    /** 0 for the shelf of timestamps that did not parse, which always comes last. */
    val year: Int,
    /** 1-12; 0 with [year] 0. */
    val month: Int,
    val entries: List<FriendHistoryEntry>,
)

data class FriendHistoryEntry(
    val group: FriendActivityGroup,
    /** Day of the month of the latest run, in the viewer's time; null when it did not parse. */
    val day: Int?,
)

fun friendHistoryMonths(runs: List<RecentActivityRun>, utcOffsetMs: Long): List<FriendHistoryMonth> {
    val byMonth = LinkedHashMap<Pair<Int, Int>, MutableList<RecentActivityRun>>()
    runs.forEach { run ->
        val key = parseSocialTimestampMs(run.lastEventTime)?.let { civilDate(it + utcOffsetMs) }
            ?.let { (year, month, _) -> year to month } ?: (0 to 0)
        byMonth.getOrPut(key) { mutableListOf() } += run
    }
    return byMonth.entries
        .sortedWith(compareByDescending<Map.Entry<Pair<Int, Int>, *>> { it.key.first != 0 }
            .thenByDescending { it.key.first }.thenByDescending { it.key.second })
        .map { (key, monthRuns) ->
            FriendHistoryMonth(
                year = key.first,
                month = key.second,
                entries = groupFriendActivity(monthRuns).map { group ->
                    FriendHistoryEntry(group, group.lastEventMs?.let { civilDate(it + utcOffsetMs).third })
                },
            )
        }
}

/** Year, month (1-12) and day of a UTC epoch millisecond, without a date library (Hinnant's civil_from_days). */
internal fun civilDate(epochMs: Long): Triple<Int, Int, Int> {
    val days = epochMs.floorDiv(86_400_000L)
    val z = days + 719_468L
    val era = z.floorDiv(146_097L)
    val doe = z - era * 146_097L
    val yoe = (doe - doe / 1_460L + doe / 36_524L - doe / 146_096L) / 365L
    val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
    val mp = (5L * doy + 2L) / 153L
    val day = (doy - (153L * mp + 2L) / 5L + 1L).toInt()
    val month = (if (mp < 10L) mp + 3L else mp - 9L).toInt()
    val year = (yoe + era * 400L + if (month <= 2) 1L else 0L).toInt()
    return Triple(year, month, day)
}
