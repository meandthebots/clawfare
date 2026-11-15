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

data class ParsedOffer(
    val price: Double?,
    val legs: List<Leg>,
    val layovers: List<Layover>,
    val durationMinutes: Int?,
    val departureToken: String?,
    val bookingToken: String?,
)

fun parseGoogleFlightsOffers(body: String): List<ParsedOffer> {
    val root = AppJson.parseToJsonElement(body) as? JsonObject ?: return emptyList()
    val lists =
        listOf("best_flights", "other_flights").flatMap { key ->
            root[key]?.let { runCatching { it.jsonArray }.getOrNull() } ?: JsonArray(emptyList())
        }
    return lists.mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        val flights = obj["flights"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return@mapNotNull null
        val legs = flights.mapNotNull { parseLeg(it as? JsonObject ?: return@mapNotNull null) }
        if (legs.isEmpty()) return@mapNotNull null
        val layovers =
            obj["layovers"]?.let { runCatching { it.jsonArray }.getOrNull() }.orEmpty().mapNotNull { node ->
                val o = node as? JsonObject ?: return@mapNotNull null
                val mins = o.int("duration") ?: return@mapNotNull null
                Layover(o.str("id") ?: o.str("name") ?: "", mins)
            }
        ParsedOffer(
            price = obj.dbl("price"),
            legs = legs,
            layovers = layovers,
            durationMinutes = obj.int("total_duration"),
            departureToken = obj.str("departure_token"),
            bookingToken = obj.str("booking_token"),
        )
    }
}

fun parseBookingQuotes(body: String): List<SellerQuote> {
    val root = AppJson.parseToJsonElement(body) as? JsonObject ?: return emptyList()
    val options = root["booking_options"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return emptyList()
    val seen = LinkedHashMap<String, SellerQuote>()
    for (opt in options) {
        val obj = opt as? JsonObject ?: continue
        val together = obj["together"] as? JsonObject ?: continue
        val vendor = together.str("book_with") ?: continue
        if (vendor.contains(",")) continue
        val title = together.str("option_title")?.takeIf { it.isNotBlank() }
        val name = if (title != null) "$vendor — $title" else vendor
        if (name in seen) continue
        val price = together.dbl("price") ?: continue
        val bags =
            together["baggage_prices"]
                ?.let { runCatching { it.jsonArray }.getOrNull() }
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.joinToString("; ")
                .orEmpty()
        seen[name] = SellerQuote(name, price, bags, title)
    }
    return seen.values.toList()
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

private fun parseLeg(obj: JsonObject): Leg? {
    val dep = obj["departure_airport"] as? JsonObject ?: return null
    val arr = obj["arrival_airport"] as? JsonObject ?: return null
    val (depDate, depTime) = splitStamp(dep)
    val (arrDate, arrTime) = splitStamp(arr)
    val number = obj.str("flight_number") ?: return null
    val airline = obj.str("airline") ?: ""
    val code = GoogleFlightsLink.splitFlight(number, "").first.ifBlank { airline.take(2).uppercase() }
    val operating = obj.str("plane_and_crew_by")?.takeIf { it.isNotBlank() } ?: airline
    return Leg(
        flightNumber = number,
        airline = airline.ifBlank { AIRLINE_NAMES[code] ?: code },
        airlineCode = code,
        operatingCarrier = operating,
        from = dep.str("id") ?: return null,
        to = arr.str("id") ?: return null,
        departTime = depTime,
        arriveTime = arrTime,
        departDate = depDate,
        arriveDate = arrDate,
        durationMinutes = obj.int("duration"),
        cabin = obj.str("travel_class")?.let { cabinLabelOf(cabinKeyOf(it)) },
        aircraft = obj.str("airplane"),
    )
}

private fun splitStamp(airport: JsonObject): Pair<String, String> {
    val time = airport.str("time").orEmpty()
    val date = airport.str("date")
    if (time.length >= 16 && time[4] == '-') {
        return time.take(10) to normalizeTime(time.substring(11))
    }
    if (!date.isNullOrBlank()) return date.take(10) to normalizeTime(time)
    return "" to normalizeTime(time)
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
        var stopped = false
        for (spec in baseCalls(query)) {
            if (stopped) break
            val fetched = load(cache, spec.params, allowNetwork, warnings) { stopped = true } ?: continue
            if (fetched.fromCache) cached++ else live++
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
                    addOffer(itineraries, tokens, offer, null, query, spec.params, fetched.cachedAt, fetched.fromCache)
                    continue
                }
                val returnParams = spec.params + ("departure_token" to token)
                val ret = load(cache, returnParams, allowNetwork, warnings) { stopped = true }
                if (ret == null) {
                    addOffer(itineraries, tokens, offer, null, query, spec.params, fetched.cachedAt, fetched.fromCache)
                    continue
                }
                if (ret.fromCache) cached++ else live++
                val returns =
                    parseGoogleFlightsOffers(ret.body)
                        .filter { !query.nonstopOnly || it.legs.size == 1 }
                        .sortedBy { it.price ?: Double.MAX_VALUE }
                val chosen = returns.firstOrNull()
                val priceWasCached = if (chosen != null) ret.fromCache else fetched.fromCache
                val stamped = if (chosen != null) ret.cachedAt else fetched.cachedAt
                addOffer(itineraries, tokens, offer, chosen, query, spec.params, stamped, priceWasCached, token)
            }
        }
        return ProviderOutcome(itineraries, tokens, warnings, live, cached)
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
        val links = mutableMapOf<String, List<BookLink>>()
        for (market in markets) {
            if (stopped) break
            val (gl, cur) = marketParams(market)
            val rt =
                quotesForDirection(itinerary, stored, gl, cur, roundTrip = true, outboundOnly = false, cache, allowNetwork, warnings, {
                    stopped =
                        true
                }, { if (it) cachedHits++ else live++ })
            val ow =
                if (stopped) {
                    emptyMap()
                } else {
                    quotesForDirection(itinerary, null, gl, cur, roundTrip = false, outboundOnly = true, cache, allowNetwork, warnings, {
                        stopped =
                            true
                    }, { if (it) cachedHits++ else live++ })
                }
            val back =
                if (stopped) {
                    emptyMap()
                } else {
                    quotesForDirection(itinerary, null, gl, cur, roundTrip = false, outboundOnly = false, cache, allowNetwork, warnings, {
                        stopped =
                            true
                    }, { if (it) cachedHits++ else live++ })
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
            )
        return VendorFetch(rows, warnings, live, cachedHits)
    }

    private fun quotesForDirection(
        itinerary: Itinerary,
        stored: FlightTokens?,
        gl: String,
        currency: String,
        roundTrip: Boolean,
        outboundOnly: Boolean,
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
                "adults" to "1",
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
            val match = offers.firstOrNull { flightMatch(it.legs, legs) } ?: offers.minByOrNull { it.price ?: Double.MAX_VALUE }
            if (roundTrip && match?.departureToken != null && match.bookingToken == null) {
                val ret =
                    load(cache, params + ("departure_token" to match.departureToken), allowNetwork, warnings, stop) ?: return emptyMap()
                count(ret.fromCache)
                val returns = parseGoogleFlightsOffers(ret.body)
                val want = itinerary.returning?.legs.orEmpty()
                val chosen = returns.firstOrNull { want.isEmpty() || flightMatch(it.legs, want) } ?: returns.firstOrNull()
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
        return parseBookingQuotes(booked.body).associateBy { it.vendor }
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
        val built =
            assembleItinerary(
                id = itineraryId(outbound.legs, retLegs),
                pricePerPerson = price,
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
            )
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
                return Loaded(
                    res.body,
                    java.time.Instant
                        .now()
                        .toString(),
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
                return Loaded(
                    res.body,
                    java.time.Instant
                        .now()
                        .toString(),
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
                "gl" to "uk",
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
    return offered.map { it.flightNumber.replace(" ", "").uppercase() } ==
        wanted.map { it.flightNumber.replace(" ", "").uppercase() }
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
