package com.clawfare.search

import java.time.Instant

class SearchService(
    private val providers: List<FareProvider>,
    private val cache: ResponseCache,
    private val sink: FareSink = NoopSink,
) {
    fun estimate(
        query: SearchQuery,
        includeQuota: Boolean = false,
    ): Estimate {
        val q = normalizeQuery(query)
        val problem = validateQuery(q)
        if (problem != null) return Estimate(0, 0, 0, 0, warnings = listOf(problem))
        val lines = mutableListOf<String>()
        var live = 0
        var cached = 0
        var skipped = 0
        var returns = 0
        val effectiveCache = if (q.cacheOnly) cache else FreshResponseCache(cache)
        for (provider in selected(q)) {
            val calls = provider.baseCalls(q)
            if ((provider.id == "serpapi" || provider.id == "searchapi") && q.fetchReturns && q.tripType != "one_way") {
                returns += calls.size * q.returnChoices
            }
            for (call in calls) {
                val hit = effectiveCache.get(provider.id, call.params) != null
                when {
                    hit -> {
                        cached++
                        lines += "${call.description} — cached"
                    }
                    !provider.configured -> {
                        skipped++
                        lines += "${call.description} — skipped, no API key"
                    }
                    q.cacheOnly -> {
                        skipped++
                        lines += "${call.description} — not in cache"
                    }
                    else -> {
                        live++
                        lines += "${call.description} — will call"
                    }
                }
            }
        }
        val warnings = mutableListOf<String>()
        if (q.cacheOnly) {
            warnings += "Cache only: nothing is fetched. Return lookups run only when that outbound is already cached."
            returns = 0
        } else if (returns > 0) {
            warnings +=
                "Plus up to $returns return lookups (one per chosen outbound). Those are cached too, and skipped once they are on disk."
        }
        if (live >= 20) {
            warnings += "$live live route searches before return lookups. SerpApi’s free plan is about 250 searches a month."
        }
        if ("duffel" in q.providers) warnings += "Duffel prices are mocks and are not quoted as real fares."
        if ("travelpayouts" in q.providers) warnings += "Travelpayouts fares are cached calendar prices, not a live search."
        if (selected(q).isEmpty()) warnings += "Pick at least one provider, or open the Florida sample."
        if (q.excludedAirlines.isNotEmpty()) {
            warnings += "Excluded marketing/operating airlines: ${q.excludedAirlines.joinToString(", ")}."
        }
        val shown = if (lines.size > 40) lines.take(40) + "… and ${lines.size - 40} more" else lines
        val quota =
            selected(q).map { provider ->
                if (includeQuota) {
                    runCatching { provider.quota() }
                        .getOrElse { QuotaView(provider.id, provider.configured, note = "Quota check failed") }
                } else {
                    QuotaView(provider.id, provider.configured, note = "Remaining quota not queried; use fare quota.")
                }
            }
        return Estimate(live, cached, skipped, returns, shown, warnings, quota)
    }

    fun search(query: SearchQuery): SearchResponse {
        val q = normalizeQuery(query)
        val problem = validateQuery(q)
        if (problem != null) return SearchResponse(ok = false, error = problem)
        val est = estimate(q)
        val maxCalls = q.maxLiveCalls
        if (!q.cacheOnly && maxCalls != null && est.liveCalls + est.returnLookupsUpperBound > maxCalls) {
            return SearchResponse(
                ok = false,
                estimate = est,
                error = "Call budget exceeded: up to ${est.liveCalls + est.returnLookupsUpperBound} requested, cap $maxCalls.",
            )
        }
        val needsLive = !q.cacheOnly && (est.liveCalls > 0 || est.returnLookupsUpperBound > 0)
        if (needsLive && !q.confirmed) {
            return SearchResponse(ok = false, needsConfirmation = true, estimate = est)
        }
        val warnings = est.warnings.toMutableList()
        val effectiveCache = if (q.cacheOnly) cache else FreshResponseCache(cache)
        val found = mutableListOf<Itinerary>()
        val tokens = mutableMapOf<String, FlightTokens>()
        for (provider in selected(q)) {
            val outcome =
                try {
                    provider.search(q, effectiveCache, allowNetwork = !q.cacheOnly && provider.configured)
                } catch (e: Exception) {
                    warnings += "${provider.displayName} failed: ${redact(e.message ?: "error")}"
                    continue
                }
            warnings += outcome.warnings
            for ((id, token) in outcome.tokens) tokens[id] = token
            // Enforce exclusions before storing, ranking or exporting provider results.
            // These preferences are independent of the browser's display-only filters.
            val restricted = FilterSpec(airlineBlock = q.excludedAirlines)
            val eligible =
                outcome.itineraries.filter {
                    Filters.matches(it, restricted) && TransitRules.allows(it, q.excludedTransitAirports, q.excludedTransitCountries)
                }
            if (eligible.size != outcome.itineraries.size) {
                warnings += "${outcome.itineraries.size - eligible.size} fares excluded by airline preference."
            }
            found += eligible
        }
        val (itineraries, keptTokens) = dedupe(found, tokens)
        val id =
            q.investigation?.takeIf { it.isNotBlank() }
                ?: (
                    "search-" +
                        Instant
                            .now()
                            .toString()
                            .replace(Regex("[^0-9]"), "")
                            .take(14)
                )
        val result =
            ResultSet(
                id = id,
                title = titleOf(q),
                subtitle = subtitleOf(q, itineraries),
                fetchedAt = Instant.now().toString(),
                currency = "GBP",
                checkedBags = q.checkedBags,
                passengers = q.adults,
                itineraries = itineraries.sortedWith(compareBy({ it.outboundDate }, { it.pricePerPerson ?: Double.MAX_VALUE })),
                suggestedFilters = FilterSpec(maxStops = 1, hideMock = true),
                warnings = warnings.distinct(),
                headerNotes =
                    listOf(
                        NoteCard(
                            "This search",
                            "${q.origins.joinToString(
                                "/",
                            )} → ${q.destinations.joinToString(
                                "/",
                            )}, ${q.adults} adult${if (q.adults == 1) "" else "s"}, ${cabinLabelOf(
                                q.cabin,
                            )} long-haul, ${cabinLabelOf(
                                q.cabinShortHaul,
                            )} on short hops, ${q.checkedBags} checked bag${if (q.checkedBags == 1) "" else "s"}.",
                        ),
                        NoteCard(
                            "Prices",
                            "GBP per person. Duffel rows are mocks. Travelpayouts rows are cached calendar fares. A cached Google Flights row was a live price when it was fetched — the timestamp is on the row.",
                            warn = true,
                        ),
                    ),
                query = q,
            )
        val saved = sink.save(StoredResult(result, keptTokens))
        return SearchResponse(ok = true, estimate = est, result = saved)
    }

    fun vendors(
        resultId: String,
        request: VendorRequest,
    ): SearchResponse {
        if (resultId == SampleData.ID) {
            val sample = sink.load(resultId)?.result ?: SampleData.florida()
            return SearchResponse(
                ok = true,
                result = sample.copy(warnings = listOf("Seller rows are the saved comparison, not a new lookup.")),
            )
        }
        val stored = sink.load(resultId) ?: return SearchResponse(ok = false, error = "No saved search '$resultId'.")
        val itinerary =
            (stored.result.itineraries + stored.result.addons).find { it.id == request.itineraryId }
                ?: return SearchResponse(ok = false, error = "Itinerary not in this search.")
        val markets =
            request.markets
                .map { it.lowercase() }
                .distinct()
                .ifEmpty { listOf("uk") }
        val provider =
            providers.firstOrNull { it.id == stored.tokens[itinerary.id]?.provider && it is GoogleFlightsProvider }
                ?: providers.firstOrNull { it is GoogleFlightsProvider && it.configured }
        if (provider == null) {
            return SearchResponse(ok = false, error = "Seller offers need SerpApi or SearchAPI. Neither is configured.")
        }
        val upper = markets.size * 7
        if (!request.cacheOnly && !request.confirmed) {
            return SearchResponse(
                ok = false,
                needsConfirmation = true,
                estimate =
                    Estimate(
                        liveCalls = upper,
                        cachedCalls = 0,
                        skippedCalls = 0,
                        returnLookupsUpperBound = 0,
                        lines =
                            listOf(
                                "Up to $upper ${provider.displayName} calls for ${markets.joinToString(
                                    ", ",
                                )} (round trip, two one-ways, and booking options). Cached responses are free.",
                            ),
                        warnings = listOf("Nothing is fetched until you confirm."),
                    ),
            )
        }
        val effectiveCache = if (request.cacheOnly) cache else FreshResponseCache(cache)
        val fetch =
            provider.vendors(
                itinerary,
                stored.tokens[itinerary.id],
                markets,
                stored.result.checkedBags,
                effectiveCache,
                allowNetwork = !request.cacheOnly,
            )
        val updated =
            stored.result.copy(
                vendorItineraryId = request.itineraryId,
                vendors = fetch.rows,
                vendorIntro =
                    listOf(
                        NoteCard(
                            "Sellers",
                            "Seller prices and all-in GBP are totals for ${itinerary.passengers} adult(s), unlike the itinerary grid, which displays per-person fares. The baggage estimate includes ${stored.result.checkedBags} checked bag(s) per adult. Where Google lists bag fees for every requested checked bag, they are estimated per person and direction. Unknown bag charges remain unverified and are excluded from all-in ranking.",
                        ),
                        NoteCard(
                            "Reputation",
                            "Flags are a starting point (Trustpilot / BBB notes from the Florida comparison). Edit the note on a row before you export — the snapshot keeps what you typed.",
                        ),
                    ) + stored.result.vendorIntro,
                warnings = (stored.result.warnings + fetch.warnings).distinct(),
                fetchedAt = Instant.now().toString(),
            )
        val saved = sink.save(stored.copy(result = updated))
        return SearchResponse(ok = true, result = saved)
    }

    fun listSearches(): List<ResultSet> = sink.list()

    fun open(id: String): ResultSet? {
        if (id == SampleData.ID) return sink.load(id)?.result ?: SampleData.florida()
        return sink.load(id)?.result
    }

    private fun selected(q: SearchQuery): List<FareProvider> = providers.filter { it.id in q.providers }

    private fun titleOf(q: SearchQuery): String {
        val route = "${q.origins.joinToString("/")} → ${q.destinations.joinToString("/")}"
        val whenText = if (q.nights != null) "${q.outboundFrom} · ${q.nights} nights" else q.outboundFrom
        return "$route · $whenText"
    }

    private fun subtitleOf(
        q: SearchQuery,
        items: List<Itinerary>,
    ): String {
        val live = items.count { it.livePrice && it.sourceLabel != "mock" }
        val mock = items.count { it.sourceLabel == "mock" }
        val cached = items.count { !it.livePrice && it.sourceLabel != "mock" }
        return "$live live, $cached cached, $mock mock. ${q.adults} adult${if (q.adults == 1) "" else "s"}."
    }
}

private fun flightKey(it: Itinerary): String {
    val out = it.outbound.legs.joinToString(",") { "${it.flightNumber}@${it.departDate}:${it.departTime}:${it.from}:${it.to}" }
    val ret = it.returning?.legs?.joinToString(",") { "${it.flightNumber}@${it.departDate}:${it.departTime}:${it.from}:${it.to}" } ?: "none"
    // Same physical flight can have different fares, rules and providers.
    // Duffel offer IDs are distinct sellable offers even on the same flight.
    val offer = if (it.source == "duffel") it.id else ""
    return listOf(
        it.source,
        it.cabinKey,
        it.passengers.toString(),
        it.fareBrand.orEmpty(),
        it.farePolicyKey.orEmpty(),
        offer,
        out,
        ret,
    ).joinToString("|")
}

private fun rank(it: Itinerary): Int =
    when (it.sourceLabel) {
        "live" -> 0
        "sample" -> 1
        "cached" -> 2
        "mock" -> 3
        else -> 4
    }

private fun dedupe(
    items: List<Itinerary>,
    tokens: Map<String, FlightTokens>,
): Pair<List<Itinerary>, Map<String, FlightTokens>> {
    val best = linkedMapOf<String, Itinerary>()
    for (item in items) {
        val key = flightKey(item)
        val current = best[key]
        if (current == null || better(item, current)) best[key] = item
    }
    val kept = best.values.toList()
    val keptTokens = kept.mapNotNull { it -> tokens[it.id]?.let { token -> it.id to token } }.toMap()
    return kept to keptTokens
}

private fun better(
    candidate: Itinerary,
    current: Itinerary,
): Boolean {
    if (rank(candidate) != rank(current)) return rank(candidate) < rank(current)
    return (candidate.pricePerPerson ?: Double.MAX_VALUE) < (current.pricePerPerson ?: Double.MAX_VALUE)
}

/** Where a finished search is stored. The SQLite investigation is the source of truth. */
interface FareSink {
    fun save(stored: StoredResult): ResultSet

    fun load(id: String): StoredResult?

    fun list(): List<ResultSet>
}

object NoopSink : FareSink {
    override fun save(stored: StoredResult): ResultSet = stored.result

    override fun load(id: String): StoredResult? = null

    override fun list(): List<ResultSet> = emptyList()
}
