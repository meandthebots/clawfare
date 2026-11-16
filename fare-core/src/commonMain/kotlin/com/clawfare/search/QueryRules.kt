package com.clawfare.search

/** Canonicalize search requests identically on JVM, Kotlin/JS and Kotlin/Wasm. */
fun airportsOf(raw: List<String>): List<String> =
    raw
        .flatMap { it.split(Regex("[,/\\s]+")) }
        .map { it.trim().uppercase() }
        .filter { code -> code.length == 3 && code.all { it.isLetter() } }
        .distinct()

fun normalizeQuery(q: SearchQuery): SearchQuery {
    val nights = q.nights?.takeIf { it > 0 }
    val trip = if (q.tripType == "one_way") "one_way" else "round_trip"
    return q.copy(
        origins = airportsOf(q.origins),
        destinations = airportsOf(q.destinations),
        outboundFrom = q.outboundFrom.trim(),
        outboundTo = q.outboundTo.trim().ifBlank { q.outboundFrom.trim() },
        returnFrom = q.returnFrom?.trim()?.ifBlank { null },
        returnTo = q.returnTo?.trim()?.ifBlank { null },
        nights = if (trip == "one_way") null else nights,
        tripType = trip,
        adults = q.adults.coerceIn(1, 9),
        cabin = cabinKeyOf(q.cabin),
        cabinShortHaul = cabinKeyOf(q.cabinShortHaul),
        checkedBags = q.checkedBags.coerceIn(0, 4),
        excludedAirlines =
            q.excludedAirlines
                .map { it.trim().uppercase() }
                .filter { it.isNotBlank() }
                .distinct(),
        currency = q.currency.uppercase().ifBlank { "GBP" },
        market =
            q.market
                .trim()
                .lowercase()
                .ifBlank { "uk" },
        excludedTransitAirports = airportsOf(q.excludedTransitAirports),
        excludedTransitCountries =
            q.excludedTransitCountries
                .map { it.trim().uppercase() }
                .filter { it.isNotBlank() }
                .distinct(),
        maxLiveCalls = q.maxLiveCalls?.coerceAtLeast(0),
        providers =
            q.providers
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .distinct(),
        returnChoices = q.returnChoices.coerceIn(1, 8),
        investigation =
            q.investigation
                ?.lowercase()
                ?.replace(Regex("[^a-z0-9-]+"), "-")
                ?.trim('-')
                ?.ifBlank { null },
    )
}
