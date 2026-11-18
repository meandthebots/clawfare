package com.clawfare.search

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

enum class FailureKind { NONE, EMPTY, OUT_OF_CREDIT, ERROR }

fun failureKind(
    status: Int,
    body: String,
): FailureKind {
    val err = extractError(body)
    val low = (err ?: if (status >= 400) body else "").lowercase()
    if (low.isBlank() && status in 200..299) return FailureKind.NONE
    if (status == 429 || OUT_OF_CREDIT.any { it in low }) return FailureKind.OUT_OF_CREDIT
    if (EMPTY_RESULT.any { it in low }) return FailureKind.EMPTY
    if (status in 200..299 && err == null) return FailureKind.NONE
    return FailureKind.ERROR
}

private val OUT_OF_CREDIT =
    listOf(
        "run out of searches",
        "out of searches",
        "not enough credits",
        "quota exceeded",
        "exceeded your",
        "insufficient credits",
        "account credit",
        "credits exhausted",
    )
private val EMPTY_RESULT = listOf("hasn't returned", "didn't return", "no results", "hasn't returned any")

fun extractError(body: String): String? {
    return try {
        val el = AppJson.parseToJsonElement(body)
        val obj = el as? JsonObject ?: return null
        obj["error"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
    } catch (_: Exception) {
        null
    }
}

/**
 * HTTP 200 alone is not evidence of zero availability. A recognized Google
 * flights response has flight arrays (possibly empty), or an explicit empty
 * result error. Unrecognized payloads must stay incomplete, not sold-out.
 */
private fun recognizedFlightResponse(body: String): Boolean {
    val root = runCatching { AppJson.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return false
    val keys = listOf("best_flights", "other_flights")
    if (keys.any { it in root }) return keys.all { it !in root || root[it] is JsonArray }
    return failureKind(200, body) == FailureKind.EMPTY
}

/** Compatibility view of priced, single-ticket options only. The full metadata
 * (including unpriced and split-ticket options) is returned by parseGoogleSellerOptions.
 */
fun parseBookingQuotes(body: String): List<SellerQuote> =
    parseGoogleSellerOptions(
        body = body,
        provider = "unknown",
        market = "uk",
        currency = "GBP",
        itineraryId = "unselected",
        passengers = 1,
        journey = "round_trip",
        observedAt = "",
    ).filter { !it.splitTickets && it.priceTotal != null }
        .map { option ->
            SellerQuote(
                vendor = listOfNotNull(option.seller, option.fareBrand ?: option.fareType).joinToString(" — "),
                price = requireNotNull(option.priceTotal),
                bags = option.bags,
                title = option.fareBrand ?: option.fareType,
                optionId = option.id,
            )
        }

fun parseSerpQuota(body: String): QuotaView {
    val obj = runCatching { AppJson.parseToJsonElement(body) as JsonObject }.getOrNull()
    val remaining = obj?.int("plan_searches_left") ?: obj?.int("total_searches_left")
    return QuotaView(
        provider = "serpapi",
        configured = true,
        remaining = remaining,
        limit = obj?.int("searches_per_month"),
        used = obj?.int("this_month_usage"),
        note = obj?.str("plan_name"),
    )
}

fun parseSearchApiQuota(body: String): QuotaView {
    val obj = runCatching { AppJson.parseToJsonElement(body) as JsonObject }.getOrNull()
    val remaining = obj?.int("credits_remaining") ?: obj?.int("searches_left") ?: obj?.int("credits")
    val limit = obj?.int("monthly_credits") ?: obj?.int("credits_limit") ?: obj?.int("monthly_allowance")
    val used = obj?.int("credits_used") ?: obj?.int("searches_used")
    return QuotaView("searchapi", configured = true, remaining = remaining, limit = limit, used = used)
}

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull ?: this[key]?.jsonPrimitive?.doubleOrNull?.toInt()

private fun JsonObject.dbl(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull ?: this[key]?.jsonPrimitive?.intOrNull?.toDouble()

enum class GoogleFlightsStyle { SERPAPI, SEARCHAPI }

class GoogleFlightsProvider(
    override val id: String,
    override val displayName: String,
    private val apiKey: String?,
    private val style: GoogleFlightsStyle,
    private val http: HttpFetcher,
) : FareProvider {
    override val configured: Boolean = !apiKey.isNullOrBlank()
    override val priceKind: String = "live"
    private val secret: String = apiKey.orEmpty()

    override fun quota(): QuotaView {
        if (!configured) return QuotaView(id, configured = false, note = "Set ${envName()} — the key stays on the server.")
        val url =
            when (style) {
                GoogleFlightsStyle.SERPAPI -> "https://serpapi.com/account.json?api_key=${enc(secret)}"
                GoogleFlightsStyle.SEARCHAPI -> "https://www.searchapi.io/api/v1/me?api_key=${enc(secret)}"
            }
        return try {
            val res = http.request("GET", url, emptyMap(), null)
            val kind = failureKind(res.status, res.body)
            if (kind == FailureKind.OUT_OF_CREDIT) {
                QuotaView(id, true, remaining = 0, note = "Out of credits")
            } else if (res.status !in 200..299) {
                QuotaView(id, true, note = "Quota check failed (${res.status})")
            } else {
                when (style) {
                    GoogleFlightsStyle.SERPAPI -> parseSerpQuota(res.body).copy(provider = id)
                    GoogleFlightsStyle.SEARCHAPI -> parseSearchApiQuota(res.body).copy(provider = id)
                }
            }
        } catch (e: Exception) {
            QuotaView(id, true, note = "Quota check failed: ${redact(e.message ?: "error", listOf(secret))}")
        }
    }

    override fun baseCalls(query: SearchQuery): List<CallSpec> {
        val cabin = travelClassParam(query.cabin)
        return datePairs(query).flatMap { (out, ret) ->
            query.origins.flatMap { origin ->
                query.destinations.map { dest ->
                    val params = baseParams(query, origin, dest, out, ret, cabin)
                    CallSpec(id, describe(origin, dest, out, ret, query.cabin), params)
                }
            }
        }
    }

    override fun search(
        query: SearchQuery,
        cache: ResponseCache,
        allowNetwork: Boolean,
    ): ProviderOutcome {
        if (!configured && !allowNetwork) {
            // Cache-only still works without a key.
        } else if (!configured) {
            return ProviderOutcome(warnings = listOf("$displayName is not configured (${envName()} is unset)."))
        }
        val warnings = mutableListOf<String>()
        val itineraries = mutableListOf<Itinerary>()
        val tokens = mutableMapOf<String, FlightTokens>()
        var live = 0
        var cached = 0
        var loadedBase = 0
        var returnExpected = 0
        var returnLoaded = 0
        var unresolvedReturns = 0
        var malformed = 0
        var stopped = false
        val specs = baseCalls(query)
        for (spec in specs) {
            if (stopped) break
            val fetched = load(cache, spec.params, allowNetwork, warnings) { stopped = true } ?: continue
            loadedBase++
            if (fetched.fromCache) cached++ else live++
            if (!recognizedFlightResponse(fetched.body)) {
                malformed++
                warnings += "$displayName returned an unrecognized flight response; coverage incomplete."
                continue
            }
            val offers = parseGoogleFlightsOffers(fetched.body)
            val picked =
                offers
                    .filter { !query.nonstopOnly || it.legs.size == 1 }
                    .sortedBy { it.price ?: Double.MAX_VALUE }
                    .take(if (query.fetchReturns && query.tripType != "one_way") query.returnChoices else offers.size.coerceAtLeast(1))
            if (!query.fetchReturns || query.tripType == "one_way" || spec.params["return_date"].isNullOrBlank()) {
                for (offer in picked) addOffer(itineraries, tokens, offer, null, query, spec.params, fetched.cachedAt, fetched.fromCache)
                continue
            }
            for (offer in picked) {
                if (stopped) break
                val token = offer.departureToken
                if (token.isNullOrBlank()) {
                    unresolvedReturns++
                    addOffer(itineraries, tokens, offer, null, query, spec.params, fetched.cachedAt, fetched.fromCache)
                    continue
                }
                returnExpected++
                val returnParams = spec.params + ("departure_token" to token)
                val ret = load(cache, returnParams, allowNetwork, warnings) { stopped = true }
                if (ret == null) {
                    unresolvedReturns++
                    addOffer(itineraries, tokens, offer, null, query, spec.params, fetched.cachedAt, fetched.fromCache)
                    continue
                }
                returnLoaded++
                if (ret.fromCache) cached++ else live++
                if (!recognizedFlightResponse(ret.body)) {
                    malformed++
                    unresolvedReturns++
                    addOffer(itineraries, tokens, offer, null, query, spec.params, fetched.cachedAt, fetched.fromCache)
                    continue
                }
                val returns =
                    parseGoogleFlightsOffers(ret.body)
                        .filter { !query.nonstopOnly || it.legs.size == 1 }
                        .sortedBy { it.price ?: Double.MAX_VALUE }
                val chosen = returns.firstOrNull()
                if (chosen == null) unresolvedReturns++
                val priceWasCached = if (chosen != null) ret.fromCache else fetched.fromCache
                val stamped = if (chosen != null) ret.cachedAt else fetched.cachedAt
                addOffer(itineraries, tokens, offer, chosen, query, spec.params, stamped, priceWasCached, token)
            }
        }
        return ProviderOutcome(
            itineraries = itineraries,
            tokens = tokens,
            warnings = warnings,
            liveCalls = live,
            cachedCalls = cached,
            baseCallsExpected = specs.size,
            baseCallsLoaded = loadedBase,
            returnLookupsExpected = returnExpected,
            returnLookupsLoaded = returnLoaded,
            unresolvedReturns = unresolvedReturns,
            malformedResponses = malformed,
        )
    }

    override fun vendors(
        itinerary: Itinerary,
        stored: FlightTokens?,
        markets: List<String>,
        checkedBags: Int,
        cache: ResponseCache,
        allowNetwork: Boolean,
    ): VendorFetch {
        if (!configured && allowNetwork) {
            return VendorFetch(warnings = listOf("$displayName is not configured."))
        }
        return vendorsCounted(itinerary, stored, markets, checkedBags, cache, allowNetwork)
    }

    private fun vendorsCounted(
        itinerary: Itinerary,
        stored: FlightTokens?,
        markets: List<String>,
        checkedBags: Int,
        cache: ResponseCache,
        allowNetwork: Boolean,
    ): VendorFetch {
        val warnings = mutableListOf<String>()
        var live = 0
        var cachedHits = 0
        var stopped = false
        val quotes = linkedMapOf<String, MarketQuotes>()
        val options = mutableListOf<SellerOption>()
        val links = mutableMapOf<String, List<BookLink>>()
        for (market in markets) {
            if (stopped) break
            val (gl, cur) = marketParams(market)
            val stop: () -> Unit = { stopped = true }
            val count: (Boolean) -> Unit = { fromCache ->
                if (fromCache) cachedHits++ else live++
            }
            val rt =
                quotesForDirection(
                    itinerary = itinerary,
                    stored = stored,
                    gl = gl,
                    currency = cur,
                    roundTrip = true,
                    outboundOnly = false,
                    options = options,
                    cache = cache,
                    allowNetwork = allowNetwork,
                    warnings = warnings,
                    stop = stop,
                    count = count,
                )
            val ow =
                if (stopped) {
                    emptyMap()
                } else {
                    quotesForDirection(
                        itinerary = itinerary,
                        stored = null,
                        gl = gl,
                        currency = cur,
                        roundTrip = false,
                        outboundOnly = true,
                        options = options,
                        cache = cache,
                        allowNetwork = allowNetwork,
                        warnings = warnings,
                        stop = stop,
                        count = count,
                    )
                }
            val back =
                if (stopped) {
                    emptyMap()
                } else {
                    quotesForDirection(
                        itinerary = itinerary,
                        stored = null,
                        gl = gl,
                        currency = cur,
                        roundTrip = false,
                        outboundOnly = false,
                        options = options,
                        cache = cache,
                        allowNetwork = allowNetwork,
                        warnings = warnings,
                        stop = stop,
                        count = count,
                    )
                }
            quotes[market] = MarketQuotes(rt, ow, back)
            links[market] = vendorLinks(itinerary, cur, gl)
        }
        val rows =
            VendorMath.rows(
                quotes,
                checkedBags,
                links,
                java.time.Instant
                    .now()
                    .toString(),
                itinerary.passengers,
            )
        return VendorFetch(rows = rows, options = options, warnings = warnings, liveCalls = live, cachedCalls = cachedHits)
    }

    private fun quotesForDirection(
        itinerary: Itinerary,
        stored: FlightTokens?,
        gl: String,
        currency: String,
        roundTrip: Boolean,
        outboundOnly: Boolean,
        options: MutableList<SellerOption>,
        cache: ResponseCache,
        allowNetwork: Boolean,
        warnings: MutableList<String>,
        stop: () -> Unit,
        count: (fromCache: Boolean) -> Unit,
    ): Map<String, SellerQuote> {
        val legs =
            when {
                roundTrip || outboundOnly -> itinerary.outbound.legs
                else -> itinerary.returning?.legs.orEmpty()
            }
        if (legs.isEmpty()) return emptyMap()
        val from = legs.first().from
        val to = legs.last().to
        val date = legs.first().departDate
        val params =
            linkedMapOf(
                "engine" to "google_flights",
                "currency" to currency,
                "gl" to gl,
                "hl" to "en",
                "adults" to itinerary.passengers.toString(),
                "departure_id" to from,
                "arrival_id" to to,
                "outbound_date" to date,
            )
        if (roundTrip && itinerary.returnDate != null) {
            params["return_date"] = itinerary.returnDate
            if (style == GoogleFlightsStyle.SERPAPI) params["type"] = "1" else params["flight_type"] = "round_trip"
        } else {
            if (style == GoogleFlightsStyle.SERPAPI) params["type"] = "2" else params["flight_type"] = "one_way"
        }
        params["travel_class"] = travelClassParam(itinerary.cabinKey)
        if (itinerary.stopsOut == 0 && (itinerary.stopsRet == null || itinerary.stopsRet == 0)) {
            if (style == GoogleFlightsStyle.SERPAPI) params["stops"] = "1" else params["stops"] = "nonstop"
        }
        var bookingToken = if (roundTrip && gl == "uk" && currency == "GBP") stored?.bookingToken else null
        if (bookingToken.isNullOrBlank()) {
            val fetched = load(cache, params, allowNetwork, warnings, stop) ?: return emptyMap()
            count(fetched.fromCache)
            val offers = parseGoogleFlightsOffers(fetched.body)
            val match = offers.firstOrNull { flightMatch(it.legs, legs) }
            if (match == null) {
                warnings += "$displayName returned no matching flight for the selected itinerary; seller options not attributed."
                return emptyMap()
            }
            if (roundTrip && match.departureToken != null && match.bookingToken == null) {
                val ret =
                    load(cache, params + ("departure_token" to match.departureToken), allowNetwork, warnings, stop) ?: return emptyMap()
                count(ret.fromCache)
                val returns = parseGoogleFlightsOffers(ret.body)
                val want = itinerary.returning?.legs.orEmpty()
                val chosen = returns.firstOrNull { want.isNotEmpty() && flightMatch(it.legs, want) }
                if (chosen == null) warnings += "$displayName returned no matching inbound flight; booking token discarded."
                bookingToken = chosen?.bookingToken
            } else {
                bookingToken = match?.bookingToken
            }
        }
        if (bookingToken.isNullOrBlank()) {
            warnings += "$displayName did not return a booking token for $from→$to ($currency)."
            return emptyMap()
        }
        val booked = load(cache, params + ("booking_token" to bookingToken), allowNetwork, warnings, stop) ?: return emptyMap()
        count(booked.fromCache)
        val parsed =
            parseGoogleSellerOptions(
                body = booked.body,
                provider = id,
                market = gl,
                currency = currency,
                itineraryId = itinerary.id,
                passengers = itinerary.passengers,
                journey =
                    when {
                        roundTrip -> "round_trip"
                        outboundOnly -> "one_way_out"
                        else -> "one_way_back"
                    },
                observedAt = booked.cachedAt,
            )
        options += parsed
        val notRankable = parsed.count { it.splitTickets || it.priceTotal == null || it.phoneServiceFee != null }
        if (notRankable > 0) {
            warnings += "$notRankable seller options recorded without a comparable single-ticket all-in quote (split/unpriced/phone fee)."
        }
        return parsed
            .filter { !it.splitTickets && it.priceTotal != null && it.phoneServiceFee == null }
            .associate { option ->
                val label = listOfNotNull(option.seller, option.fareBrand ?: option.fareType).joinToString(" — ")
                option.id to
                    SellerQuote(
                        vendor = label,
                        price = requireNotNull(option.priceTotal),
                        bags = option.bags,
                        title = option.fareBrand ?: option.fareType,
                        optionId = option.id,
                    )
            }
    }

    private fun addOffer(
        into: MutableList<Itinerary>,
        tokens: MutableMap<String, FlightTokens>,
        outbound: ParsedOffer,
        inbound: ParsedOffer?,
        query: SearchQuery,
        params: Map<String, String>,
        fetchedAt: String,
        servedFromCache: Boolean,
        departureToken: String? = outbound.departureToken,
    ) {
        val retLegs = inbound?.legs
        val price = inbound?.price ?: outbound.price
        val brand = inbound?.fareBrand ?: outbound.fareBrand
        // Include the provider and fare product; identical flights can sell several fare types.
        // Never include the price in the ID: price history needs a stable offer-family key.
        val flightId = itineraryId(outbound.legs, retLegs)
        val productId = "$id-$flightId-${sha256(brand.orEmpty()).take(8)}"
        val built =
            assembleItinerary(
                id = productId,
                // Google Flights fare totals are for the requested party; the grid
                // and the persisted flight price are explicitly per adult.
                pricePerPerson = price?.div(query.adults.coerceAtLeast(1)),
                priceCurrency = query.currency,
                passengers = query.adults,
                cabinRaw = query.cabin,
                outboundLegs = outbound.legs,
                returnLegs = retLegs,
                returnMissing = query.tripType != "one_way" && retLegs.isNullOrEmpty(),
                source = id,
                sourceLabel = if (servedFromCache) "cached" else "live",
                livePrice = true,
                fetchedAt = fetchedAt,
                outboundDuration = outbound.durationMinutes,
                returnDuration = inbound?.durationMinutes,
                apiLayoversOut = outbound.layovers,
                apiLayoversRet = inbound?.layovers ?: emptyList(),
                returnDateHint = params["return_date"],
            ).copy(fareBrand = brand)
        if (!Filters.shortHaulAccept(built, query.cabinShortHaul)) return
        into += built
        tokens[built.id] =
            FlightTokens(
                provider = id,
                departureToken = departureToken,
                bookingToken = inbound?.bookingToken ?: outbound.bookingToken,
                params = params,
            )
    }

    private fun load(
        cache: ResponseCache,
        params: Map<String, String>,
        allowNetwork: Boolean,
        warnings: MutableList<String>,
        stop: () -> Unit,
    ): Loaded? {
        cache.get(id, params)?.let { return Loaded(it.body, it.cachedAt, fromCache = true) }
        if (!allowNetwork) {
            warnings += "No cached response for ${describeParams(params)} — skipped, cache only."
            return null
        }
        if (!configured) return null
        val url =
            endpoint() + "?" +
                (params + ("api_key" to secret)).entries.joinToString("&") {
                    "${enc(it.key)}=${enc(it.value)}"
                }
        val res =
            try {
                http.request("GET", url, emptyMap(), null)
            } catch (e: Exception) {
                warnings += "$displayName request failed: ${redact(e.message ?: "error", listOf(secret))}"
                return null
            }
        when (failureKind(res.status, res.body)) {
            FailureKind.OUT_OF_CREDIT -> {
                warnings +=
                    "$displayName is out of credits. Showing whatever was already cached. ${redact(
                        extractError(res.body) ?: "",
                        listOf(secret),
                    )}".trim()
                stop()
                return null
            }
            FailureKind.EMPTY -> {
                cache.put(id, params, res.body, listOf(secret))
                val fallback = java.time.Instant.now()
                val observedAt = cache.get(id, params)?.cachedAt ?: fallback.toString()
                return Loaded(
                    res.body,
                    observedAt,
                    fromCache = false,
                )
            }
            FailureKind.ERROR -> {
                warnings += "$displayName error ${res.status}: ${redact(extractError(res.body) ?: res.body.take(180), listOf(secret))}"
                if (res.status == 401 || res.status == 403) stop()
                return null
            }
            FailureKind.NONE -> {
                cache.put(id, params, res.body, listOf(secret))
                val fallback = java.time.Instant.now()
                val observedAt = cache.get(id, params)?.cachedAt ?: fallback.toString()
                return Loaded(
                    res.body,
                    observedAt,
                    fromCache = false,
                )
            }
        }
    }

    private fun baseParams(
        query: SearchQuery,
        origin: String,
        dest: String,
        outbound: String,
        ret: String?,
        cabin: String,
    ): Map<String, String> {
        val params =
            linkedMapOf(
                "engine" to "google_flights",
                "currency" to query.currency.uppercase(),
                "gl" to query.market,
                "hl" to "en",
                "adults" to query.adults.toString(),
                "departure_id" to origin,
                "arrival_id" to dest,
                "outbound_date" to outbound,
            )
        val round = query.tripType != "one_way" && ret != null
        if (round) params["return_date"] = ret!!
        when (style) {
            GoogleFlightsStyle.SERPAPI -> {
                params["type"] = if (round) "1" else "2"
                params["travel_class"] = cabin
                if (query.nonstopOnly) params["stops"] = "1"
            }
            GoogleFlightsStyle.SEARCHAPI -> {
                params["flight_type"] = if (round) "round_trip" else "one_way"
                params["travel_class"] = cabinName(query.cabin)
                if (query.nonstopOnly) params["stops"] = "nonstop"
            }
        }
        return params
    }

    private fun endpoint(): String =
        when (style) {
            GoogleFlightsStyle.SERPAPI -> "https://serpapi.com/search.json"
            GoogleFlightsStyle.SEARCHAPI -> "https://www.searchapi.io/api/v1/search"
        }

    private fun envName(): String =
        when (style) {
            GoogleFlightsStyle.SERPAPI -> "SERPAPI_API_KEY"
            GoogleFlightsStyle.SEARCHAPI -> "SEARCHAPI_API_KEY"
        }

    private fun travelClassParam(cabin: String): String =
        when (style) {
            GoogleFlightsStyle.SERPAPI ->
                when (cabinKeyOf(cabin)) {
                    "premium_economy" -> "2"
                    "business" -> "3"
                    "first" -> "4"
                    else -> "1"
                }
            GoogleFlightsStyle.SEARCHAPI -> cabinName(cabin)
        }

    private fun cabinName(cabin: String): String =
        when (cabinKeyOf(cabin)) {
            "premium_economy" -> "premium_economy"
            "business" -> "business"
            "first" -> "first"
            else -> "economy"
        }

    private fun describe(
        origin: String,
        dest: String,
        out: String,
        ret: String?,
        cabin: String,
    ): String = "$displayName $origin→$dest $out${ret?.let { "→$it" } ?: ""} ${cabinLabelOf(cabinKeyOf(cabin))}"

    private fun describeParams(params: Map<String, String>): String =
        "${params["departure_id"]}→${params["arrival_id"]} ${params["outbound_date"]}${params["return_date"]?.let { "→$it" } ?: ""}"

    private data class Loaded(
        val body: String,
        val cachedAt: String,
        val fromCache: Boolean,
    )
}

fun flightMatch(
    offered: List<Leg>,
    wanted: List<Leg>,
): Boolean {
    if (wanted.isEmpty() || offered.size != wanted.size) return false
    return offered.zip(wanted).all { (candidate, selected) ->
        candidate.flightNumber.replace(" ", "").equals(selected.flightNumber.replace(" ", ""), ignoreCase = true) &&
            candidate.from.equals(selected.from, ignoreCase = true) &&
            candidate.to.equals(selected.to, ignoreCase = true) &&
            candidate.departDate == selected.departDate &&
            normalizeTime(candidate.departTime) == normalizeTime(selected.departTime)
    }
}

fun itineraryId(
    outbound: List<Leg>,
    returning: List<Leg>?,
): String {
    val raw = (outbound + (returning ?: emptyList())).joinToString("|") { "${it.flightNumber}@${it.departDate}" }
    return "itin-" + sha256(raw).take(10)
}

fun marketParams(market: String): Pair<String, String> =
    when (market) {
        "us" -> "us" to "USD"
        "mx" -> "mx" to "MXN"
        "ca" -> "ca" to "CAD"
        "es" -> "es" to "EUR"
        "fr" -> "fr" to "EUR"
        "de" -> "de" to "EUR"
        "nl" -> "nl" to "EUR"
        "dk" -> "dk" to "DKK"
        else -> "uk" to "GBP"
    }

fun vendorLinks(
    itinerary: Itinerary,
    currency: String,
    country: String,
): List<BookLink> {
    val out = itinerary.outbound.legs
    val ret = itinerary.returning?.legs
    if (out.isEmpty()) return emptyList()
    val links = mutableListOf<BookLink>()
    if (!ret.isNullOrEmpty() && itinerary.returnDate != null) {
        links +=
            BookLink(
                "rt",
                "RT",
                GoogleFlightsLink.roundTrip(
                    itinerary.outboundDate,
                    itinerary.returnDate,
                    out.first().from,
                    out.last().to,
                    itinerary.cabinKey,
                    out,
                    ret,
                    currency,
                    country,
                ),
            )
    }
    links +=
        BookLink(
            "out",
            "Out",
            GoogleFlightsLink.oneWay(out.first().departDate, out.first().from, out.last().to, itinerary.cabinKey, out, currency, country),
        )
    if (!ret.isNullOrEmpty()) {
        links +=
            BookLink(
                "back",
                "Back",
                GoogleFlightsLink.oneWay(
                    ret.first().departDate,
                    ret.first().from,
                    ret.last().to,
                    itinerary.cabinKey,
                    ret,
                    currency,
                    country,
                ),
            )
    }
    return links
}

private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
