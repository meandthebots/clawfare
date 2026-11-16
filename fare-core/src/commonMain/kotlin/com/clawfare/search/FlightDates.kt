package com.clawfare.search

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus

/**
 * Calendar-day arithmetic for provider itineraries. These are airport-local
 * wall-clock dates and times, not UTC instants: never apply system time zones
 * or DST offsets when comparing the published local flight timestamps.
 */
fun datePairs(query: SearchQuery): List<Pair<String, String?>> {
    val outs = inclusiveDates(query.outboundFrom, query.outboundTo.ifBlank { query.outboundFrom })
    if (query.tripType == "one_way") return outs.map { it.toString() to null }
    val nights = query.nights
    if (nights != null) {
        return outs.map { it.toString() to it.plus(nights, DateTimeUnit.DAY).toString() }
    }
    val rets = inclusiveDates(query.returnFrom ?: return emptyList(), query.returnTo ?: query.returnFrom!!)
    return outs.flatMap { o -> rets.filter { it > o }.map { o.toString() to it.toString() } }
}

private fun inclusiveDates(fromRaw: String, toRaw: String): List<LocalDate> {
    val from = LocalDate.parse(fromRaw)
    val to = LocalDate.parse(toRaw)
    if (to < from) return emptyList()
    return generateSequence(from) { it.plus(1, DateTimeUnit.DAY) }.takeWhile { it <= to }.toList()
}

fun validateQuery(q: SearchQuery): String? {
    if (q.origins.isEmpty() || q.destinations.isEmpty()) return "Enter at least one origin and one destination airport code."
    try {
        datePairs(q)
    } catch (_: Exception) {
        return "Check the dates. Use a return range, or a number of nights."
    }
    if (q.tripType != "one_way" && q.nights == null && q.returnFrom.isNullOrBlank()) {
        return "Set a return date range or a trip length in nights."
    }
    val pairs = datePairs(q)
    if (pairs.isEmpty()) return "No outbound/return pairs. The return has to be after the outbound."
    val routes = pairs.size * q.origins.size * q.destinations.size
    if (routes > 500) {
        return "That is $routes route searches before return lookups. Narrow the airports or the dates."
    }
    return null
}

fun normalizeTime(raw: String): String {
    val t = raw.trim().take(5)
    val parts = t.split(":")
    if (parts.size < 2) return "00:00"
    return parts[0].padStart(2, '0') + ":" + parts[1].padStart(2, '0')
}

/**
 * Difference in local calendar/wall-clock minutes (not actual elapsed flight
 * time across time zones). Invalid dates/times fail closed as null.
 */
fun minutesBetween(
    date1: String,
    time1: String,
    date2: String,
    time2: String,
): Int? =
    try {
        val left = LocalDate.parse(date1)
        val right = LocalDate.parse(date2)
        val first = normalizedLocalTime(time1)
        val second = normalizedLocalTime(time2)
        val minutes =
            (right.toEpochDays().toLong() - left.toEpochDays()) * 1440L +
                (second.hour - first.hour) * 60L + second.minute - first.minute
        if (minutes in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) minutes.toInt() else null
    } catch (_: Exception) {
        null
    }

private fun normalizedLocalTime(raw: String): LocalTime {
    val parts = normalizeTime(raw).split(":")
    return LocalTime(parts[0].toInt(), parts[1].toInt())
}

fun nightsBetween(start: String?, end: String?): Int? {
    if (start.isNullOrBlank() || end.isNullOrBlank()) return null
    return try {
        LocalDate.parse(end).toEpochDays() - LocalDate.parse(start).toEpochDays()
    } catch (_: Exception) {
        null
    }
}
