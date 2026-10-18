package com.clawfare.search

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate

interface HttpFetcher {
    fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): HttpResult
}

data class HttpResult(
    val status: Int,
    val body: String,
)

class JdkHttpFetcher : HttpFetcher {
    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

    override fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): HttpResult {
        val builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(90))
        headers.forEach { (k, v) -> builder.header(k, v) }
        val req =
            if (method.equals("POST", true)) {
                builder.header("Content-Type", headers["Content-Type"] ?: "application/json")
                builder.POST(HttpRequest.BodyPublishers.ofString(body ?: "")).build()
            } else {
                builder.GET().build()
            }
        val res = client.send(req, HttpResponse.BodyHandlers.ofString())
        return HttpResult(res.statusCode(), res.body())
    }
}

data class CallSpec(
    val provider: String,
    val description: String,
    val params: Map<String, String>,
)

data class ProviderOutcome(
    val itineraries: List<Itinerary> = emptyList(),
    val tokens: Map<String, FlightTokens> = emptyMap(),
    val warnings: List<String> = emptyList(),
    val liveCalls: Int = 0,
    val cachedCalls: Int = 0,
)

data class VendorFetch(
    val rows: List<VendorRow> = emptyList(),
    val warnings: List<String> = emptyList(),
    val liveCalls: Int = 0,
    val cachedCalls: Int = 0,
)

interface FareProvider {
    val id: String
    val displayName: String
    val configured: Boolean

    /** live, cached, or mock — how prices from this provider must be labelled. */
    val priceKind: String

    fun quota(): QuotaView

    fun baseCalls(query: SearchQuery): List<CallSpec>

    fun search(
        query: SearchQuery,
        cache: ResponseCache,
        allowNetwork: Boolean,
    ): ProviderOutcome

    fun vendors(
        itinerary: Itinerary,
        stored: FlightTokens?,
        markets: List<String>,
        checkedBags: Int,
        cache: ResponseCache,
        allowNetwork: Boolean,
    ): VendorFetch = VendorFetch(warnings = listOf("$displayName does not return per-seller booking options."))
}

fun datePairs(query: SearchQuery): List<Pair<String, String?>> {
    val outs = inclusiveDates(query.outboundFrom, query.outboundTo.ifBlank { query.outboundFrom })
    if (query.tripType == "one_way") return outs.map { it.toString() to null }
    if (query.nights != null) {
        return outs.map { it.toString() to it.plusDays(query.nights.toLong()).toString() }
    }
    val rets = inclusiveDates(query.returnFrom ?: return emptyList(), query.returnTo ?: query.returnFrom!!)
    return outs.flatMap { o -> rets.filter { it.isAfter(o) }.map { o.toString() to it.toString() } }
}

private fun inclusiveDates(
    fromRaw: String,
    toRaw: String,
): List<LocalDate> {
    val from = LocalDate.parse(fromRaw)
    val to = LocalDate.parse(toRaw)
    if (to.isBefore(from)) return emptyList()
    return generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()
}

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
        currency = q.currency.uppercase().ifBlank { "GBP" },
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

fun validateQuery(q: SearchQuery): String? {
    if (q.origins.isEmpty() || q.destinations.isEmpty()) return "Enter at least one origin and one destination airport code."
    try {
        datePairs(q)
    } catch (e: Exception) {
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

class TravelpayoutsProvider(
    private val token: String?,
    private val http: HttpFetcher,
) : FareProvider {
    override val id = "travelpayouts"
    override val displayName = "Travelpayouts"
    override val configured = !token.isNullOrBlank()
    override val priceKind = "cached"
    private val secret = token.orEmpty()

    override fun quota() =
        QuotaView(
            id,
            configured,
            note =
                if (configured) {
                    "Cached calendar fares, not a live search. No search quota is reported."
                } else {
                    "Set TRAVELPAYOUTS_API_TOKEN."
                },
        )

    override fun baseCalls(query: SearchQuery): List<CallSpec> {
        val months = months(query)
        return query.origins.flatMap { origin ->
            query.destinations.flatMap { dest ->
                months.map { month ->
                    CallSpec(id, "Travelpayouts cached $origin→$dest $month", params(query, origin, dest, month))
                }
            }
        }
    }

    override fun search(
        query: SearchQuery,
        cache: ResponseCache,
        allowNetwork: Boolean,
    ): ProviderOutcome {
        if (!configured &&
            allowNetwork
        ) {
            return ProviderOutcome(warnings = listOf("Travelpayouts is not configured (TRAVELPAYOUTS_API_TOKEN)."))
        }
        val warnings = mutableListOf("Travelpayouts prices are cached calendar fares, not live availability.")
        val out = mutableListOf<Itinerary>()
        var live = 0
        var cached = 0
        val wanted = datePairs(query).map { it.first to it.second }.toSet()
        for (spec in baseCalls(query)) {
            val loaded = read(cache, spec.params, allowNetwork, warnings) ?: continue
            if (loaded.first) cached++ else live++
            out += parse(loaded.second, query, wanted, loaded.third)
        }
        return ProviderOutcome(out, emptyMap(), warnings, live, cached)
    }

    private fun parse(
        body: String,
        query: SearchQuery,
        wanted: Set<Pair<String, String?>>,
        fetchedAt: String,
    ): List<Itinerary> {
        val root = runCatching { AppJson.parseToJsonElement(body) as JsonObject }.getOrNull() ?: return emptyList()
        val data = root["data"] ?: return emptyList()
        val rows =
            when (data) {
                is JsonArray -> data
                else -> return emptyList()
            }
        return rows.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val dep = o.str("departure_at")?.take(10) ?: return@mapNotNull null
            val ret = o.str("return_at")?.take(10)
            val key = dep to if (query.tripType == "one_way") null else ret
            if (query.tripType != "one_way" && ret == null) return@mapNotNull null
            if (wanted.none { it.first == dep && (query.tripType == "one_way" || it.second == ret) }) return@mapNotNull null
            val airline = o.str("airline").orEmpty().uppercase()
            val number = o.str("flight_number").orEmpty()
            val fn =
                if (number.isBlank()) {
                    airline
                } else if (number.startsWith(airline)) {
                    number
                } else {
                    "$airline $number"
                }
            val origin = o.str("origin") ?: o.str("origin_airport") ?: return@mapNotNull null
            val dest = o.str("destination") ?: o.str("destination_airport") ?: return@mapNotNull null
            val transfers = o.int("transfers") ?: 0
            val retTransfers = o.int("return_transfers") ?: 0
            if (query.nonstopOnly && (transfers > 0 || retTransfers > 0)) return@mapNotNull null
            val depTime = o.str("departure_at")?.let { if (it.length >= 16) it.substring(11).take(5) else "00:00" } ?: "00:00"
            val outLeg =
                Leg(
                    fn,
                    AIRLINE_NAMES[airline] ?: airline,
                    airline,
                    AIRLINE_NAMES[airline] ?: airline,
                    origin,
                    dest,
                    normalizeTime(depTime),
                    "",
                    dep,
                    dep,
                    durationMinutes = o.int("duration_to"),
                    cabin = cabinLabelOf(query.cabin),
                )
            val retLeg =
                if (ret != null) {
                    val rt = o.str("return_at")?.let { if (it.length >= 16) it.substring(11).take(5) else "" }.orEmpty()
                    Leg(
                        airline,
                        AIRLINE_NAMES[airline] ?: airline,
                        airline,
                        AIRLINE_NAMES[airline] ?: airline,
                        dest,
                        origin,
                        normalizeTime(
                            rt
                                .ifBlank {
                                    "00:00"
                                },
                        ),
                        "",
                        ret,
                        ret,
                        durationMinutes = o.int("duration_back"),
                        cabin = cabinLabelOf(query.cabin),
                    )
                } else {
                    null
                }
            val price = o.dbl("price") ?: return@mapNotNull null
            val currency = (root.str("currency") ?: query.currency).uppercase()
            assembleItinerary(
                id = "tp-" + sha256("$origin$dest$dep$ret$fn").take(10),
                pricePerPerson = price,
                priceCurrency = currency,
                passengers = query.adults,
                cabinRaw = query.cabin,
                outboundLegs = listOf(outLeg),
                returnLegs = retLeg?.let { listOf(it) },
                returnMissing = query.tripType != "one_way" && retLeg == null,
                source = id,
                sourceLabel = "cached",
                livePrice = false,
                fetchedAt = fetchedAt,
                notes = "Travelpayouts cached calendar fare. Not a live itinerary — connections are a stop count only.",
                outboundDuration = o.int("duration_to"),
                returnDuration = o.int("duration_back"),
            ).copy(stopsOut = transfers, stopsRet = if (ret != null) retTransfers else null)
        }
    }

    private fun read(
        cache: ResponseCache,
        params: Map<String, String>,
        allowNetwork: Boolean,
        warnings: MutableList<String>,
    ): Triple<Boolean, String, String>? {
        cache.get(id, params)?.let { return Triple(true, it.body, it.cachedAt) }
        if (!allowNetwork || !configured) {
            if (!allowNetwork) {
                warnings +=
                    "Travelpayouts ${params["origin"]}→${params["destination"]} ${params["departure_at"]} is not cached."
            }
            return null
        }
        val url =
            "https://api.travelpayouts.com/aviasales/v3/prices_for_dates?" +
                (params + ("token" to secret)).entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val res =
            try {
                http.request("GET", url, emptyMap(), null)
            } catch (e: Exception) {
                warnings += "Travelpayouts failed: ${redact(e.message ?: "error", listOf(secret))}"
                return null
            }
        if (failureKind(res.status, res.body) == FailureKind.OUT_OF_CREDIT || res.status == 401 || res.status == 429) {
            warnings +=
                "Travelpayouts rejected the token or the quota (${res.status}). ${redact(
                    extractError(res.body) ?: "",
                    listOf(secret),
                )}".trim()
            return null
        }
        if (res.status !in 200..299) {
            warnings += "Travelpayouts error ${res.status}: ${redact(res.body.take(160), listOf(secret))}"
            return null
        }
        cache.put(id, params, res.body, listOf(secret))
        return Triple(
            false,
            res.body,
            java.time.Instant
                .now()
                .toString(),
        )
    }

    private fun params(
        query: SearchQuery,
        origin: String,
        dest: String,
        month: String,
    ): Map<String, String> =
        linkedMapOf(
            "origin" to origin,
            "destination" to dest,
            "departure_at" to month,
            "currency" to "gbp",
            "sorting" to "price",
            "limit" to "100",
            "unique" to "false",
            "one_way" to if (query.tripType == "one_way") "true" else "false",
            "direct" to if (query.nonstopOnly) "true" else "false",
        ).apply {
            if (query.tripType != "one_way") this["return_at"] = month
        }

    private fun months(query: SearchQuery): List<String> {
        val from = LocalDate.parse(query.outboundFrom)
        val to = LocalDate.parse(query.outboundTo.ifBlank { query.outboundFrom })
        val out = mutableListOf<String>()
        var cursor = from.withDayOfMonth(1)
        val end = to.withDayOfMonth(1)
        while (!cursor.isAfter(end)) {
            out += "%04d-%02d".format(cursor.year, cursor.monthValue)
            cursor = cursor.plusMonths(1)
        }
        return out
    }
}

class DuffelProvider(
    private val token: String?,
    private val http: HttpFetcher,
) : FareProvider {
    override val id = "duffel"
    override val displayName = "Duffel"
    override val configured = !token.isNullOrBlank()
    override val priceKind = "mock"
    private val secret = token.orEmpty()

    override fun quota(): QuotaView {
        val mode = if (secret.contains("test")) "Test token detected." else "Token is not marked as a test key."
        return QuotaView(
            id,
            configured,
            note = if (configured) "Mock offers only — never a real price. $mode" else "Set DUFFEL_ACCESS_TOKEN (test mode).",
        )
    }

    override fun baseCalls(query: SearchQuery): List<CallSpec> =
        datePairs(query).flatMap { (out, ret) ->
            query.origins.flatMap { origin ->
                query.destinations.map { dest ->
                    CallSpec(
                        id,
                        "Duffel mock $origin→$dest $out${ret?.let { "→$it" } ?: ""}",
                        mapOf(
                            "origin" to origin,
                            "destination" to dest,
                            "outbound" to out,
                            "return" to (ret ?: ""),
                            "cabin" to query.cabin,
                        ),
                    )
                }
            }
        }

    override fun search(
        query: SearchQuery,
        cache: ResponseCache,
        allowNetwork: Boolean,
    ): ProviderOutcome {
        val warnings = mutableListOf("Duffel offers are test-mode mocks and are not real prices.")
        if (!secret.contains("test") && configured) {
            warnings += "DUFFEL_ACCESS_TOKEN does not look like a test token. Results are still labelled mock."
        }
        if (!configured && allowNetwork) return ProviderOutcome(warnings = warnings + "Duffel is not configured (DUFFEL_ACCESS_TOKEN).")
        var live = 0
        var cached = 0
        val out = mutableListOf<Itinerary>()
        for ((outDate, ret) in datePairs(query)) {
            for (origin in query.origins) {
                for (dest in query.destinations) {
                    val params =
                        mapOf(
                            "origin" to origin,
                            "destination" to dest,
                            "outbound" to outDate,
                            "return" to (ret ?: ""),
                            "cabin" to query.cabin,
                            "adults" to query.adults.toString(),
                        )
                    val loaded = read(cache, params, query, origin, dest, outDate, ret, allowNetwork, warnings) ?: continue
                    if (loaded.first) cached++ else live++
                    out += parseOffers(loaded.second, query, loaded.third)
                }
            }
        }
        return ProviderOutcome(out, emptyMap(), warnings, live, cached)
    }

    private fun read(
        cache: ResponseCache,
        params: Map<String, String>,
        query: SearchQuery,
        origin: String,
        dest: String,
        outDate: String,
        ret: String?,
        allowNetwork: Boolean,
        warnings: MutableList<String>,
    ): Triple<Boolean, String, String>? {
        cache.get(id, params)?.let { return Triple(true, it.body, it.cachedAt) }
        if (!allowNetwork || !configured) {
            if (!allowNetwork) warnings += "Duffel $origin→$dest $outDate is not cached."
            return null
        }
        val slices = mutableListOf(slice(origin, dest, outDate, query.cabin))
        if (query.tripType != "one_way" && ret != null) slices += slice(dest, origin, ret, query.cabin)
        val payload = """{"data":{"slices":[${slices.joinToString(
            ",",
        )}],"passengers":[${(1..query.adults).joinToString(",") { """{"type":"adult"}""" }}],"cabin_class":"${cabinKeyOf(query.cabin)}"}}"""
        val res =
            try {
                http.request(
                    "POST",
                    "https://api.duffel.com/air/offer_requests?return_offers=true",
                    mapOf(
                        "Authorization" to "Bearer $secret",
                        "Duffel-Version" to "v2",
                        "Accept" to "application/json",
                        "Content-Type" to "application/json",
                    ),
                    payload,
                )
            } catch (e: Exception) {
                warnings += "Duffel failed: ${redact(e.message ?: "error", listOf(secret))}"
                return null
            }
        if (failureKind(res.status, res.body) == FailureKind.OUT_OF_CREDIT || res.status == 401 || res.status == 429) {
            warnings +=
                "Duffel rejected the request (${res.status}). ${redact(
                    extractError(res.body) ?: res.body.take(160),
                    listOf(secret),
                )}".trim()
            return null
        }
        if (res.status !in 200..299) {
            warnings += "Duffel error ${res.status}: ${redact(res.body.take(180), listOf(secret))}"
            return null
        }
        cache.put(id, params, res.body, listOf(secret))
        return Triple(
            false,
            res.body,
            java.time.Instant
                .now()
                .toString(),
        )
    }

    private fun slice(
        from: String,
        to: String,
        date: String,
        cabin: String,
    ) = """{"origin":"$from","destination":"$to","departure_date":"$date","cabin_class":"${cabinKeyOf(cabin)}"}"""

    private fun parseOffers(
        body: String,
        query: SearchQuery,
        fetchedAt: String,
    ): List<Itinerary> {
        val root = runCatching { AppJson.parseToJsonElement(body) as JsonObject }.getOrNull() ?: return emptyList()
        val data = root["data"] as? JsonObject ?: return emptyList()
        val offers = (data["offers"] as? JsonArray) ?: return emptyList()
        return offers.mapNotNull { el ->
            val offer = el as? JsonObject ?: return@mapNotNull null
            val slices = offer["slices"] as? JsonArray ?: return@mapNotNull null
            val directions =
                slices.map { slice ->
                    val segs = (slice as JsonObject)["segments"] as? JsonArray ?: JsonArray(emptyList())
                    segs.mapNotNull { parseSegment(it as? JsonObject ?: return@mapNotNull null) }
                }
            if (directions.isEmpty() || directions[0].isEmpty()) return@mapNotNull null
            if (query.nonstopOnly && directions.any { it.size != 1 }) return@mapNotNull null
            val amount = offer.str("total_amount")?.toDoubleOrNull() ?: return@mapNotNull null
            val currency = offer.str("total_currency") ?: query.currency
            // Duffel total_amount is for all passengers. Show per person.
            val per = amount / query.adults.coerceAtLeast(1)
            assembleItinerary(
                id = "duffel-" + (offer.str("id") ?: sha256(body).take(10)),
                pricePerPerson = per,
                priceCurrency = currency,
                passengers = query.adults,
                cabinRaw = query.cabin,
                outboundLegs = directions[0],
                returnLegs = directions.getOrNull(1),
                returnMissing = query.tripType != "one_way" && directions.getOrNull(1).isNullOrEmpty(),
                source = id,
                sourceLabel = "mock",
                livePrice = false,
                fetchedAt = fetchedAt,
                notes = "Duffel test-mode offer. Not a real price.",
            )
        }
    }

    private fun parseSegment(seg: JsonObject): Leg? {
        val origin = (seg["origin"] as? JsonObject)?.str("iata_code") ?: return null
        val dest = (seg["destination"] as? JsonObject)?.str("iata_code") ?: return null
        val dep = seg.str("departing_at") ?: return null
        val arr = seg.str("arriving_at") ?: dep
        val marketing = seg["marketing_carrier"] as? JsonObject
        val operating = seg["operating_carrier"] as? JsonObject
        val code = marketing?.str("iata_code").orEmpty()
        val number = seg.str("marketing_carrier_flight_number").orEmpty()
        val fn = "$code $number".trim()
        val cabin =
            ((seg["passengers"] as? JsonArray)?.firstOrNull() as? JsonObject)?.str("cabin_class_marketing_name")
                ?: ((seg["passengers"] as? JsonArray)?.firstOrNull() as? JsonObject)?.str("cabin_class")
        return Leg(
            flightNumber = fn,
            airline = marketing?.str("name") ?: AIRLINE_NAMES[code] ?: code,
            airlineCode = code,
            operatingCarrier = operating?.str("name") ?: marketing?.str("name") ?: code,
            from = origin,
            to = dest,
            departTime = normalizeTime(dep.substring(11).take(5)),
            arriveTime = normalizeTime(arr.substring(11).take(5)),
            departDate = dep.take(10),
            arriveDate = arr.take(10),
            durationMinutes = isoMinutes(seg.str("duration")),
            cabin = cabin?.let { cabinLabelOf(cabinKeyOf(it)) },
        )
    }
}

private fun isoMinutes(raw: String?): Int? {
    if (raw.isNullOrBlank()) return null
    val m = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?""").find(raw) ?: return null
    val h = m.groupValues[1].toIntOrNull() ?: 0
    val min = m.groupValues[2].toIntOrNull() ?: 0
    return h * 60 + min
}

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

private fun JsonObject.dbl(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull ?: this[key]?.jsonPrimitive?.intOrNull?.toDouble()

private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

fun providersFromEnv(http: HttpFetcher = JdkHttpFetcher()): List<FareProvider> =
    listOf(
        GoogleFlightsProvider("serpapi", "SerpApi Google Flights", System.getenv("SERPAPI_API_KEY"), GoogleFlightsStyle.SERPAPI, http),
        GoogleFlightsProvider(
            "searchapi",
            "SearchAPI Google Flights",
            System.getenv("SEARCHAPI_API_KEY"),
            GoogleFlightsStyle.SEARCHAPI,
            http,
        ),
        TravelpayoutsProvider(System.getenv("TRAVELPAYOUTS_API_TOKEN"), http),
        DuffelProvider(System.getenv("DUFFEL_ACCESS_TOKEN"), http),
    )
