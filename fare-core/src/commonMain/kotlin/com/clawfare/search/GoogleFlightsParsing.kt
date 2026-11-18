package com.clawfare.search

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Both SerpApi and SearchAPI return Google Flights-style results. Parsing is
 * shared by JVM, JS, and Wasm so hosted mode never needs a second JS parser.
 * Opaque provider tokens remain server-side; never include ParsedOffer directly
 * in a public ResultSet or a client-facing error message.
 */
@Serializable
data class ParsedOffer(
    val price: Double?,
    val legs: List<Leg>,
    val layovers: List<Layover>,
    val durationMinutes: Int?,
    val departureToken: String?,
    val bookingToken: String?,
    val fareBrand: String? = null,
)

/**
 * This is intentionally the same lenient-but-not-invented provider parser
 * used by JVM. No malformed or unpriced result becomes a zero-valued fare.
 */
fun parseGoogleFlightsOffers(body: String): List<ParsedOffer> {
    val root = AppJson.parseToJsonElement(body) as? JsonObject ?: return emptyList()
    val lists =
        listOf("best_flights", "other_flights").flatMap { key ->
            root[key]?.let { runCatching { it.jsonArray }.getOrNull() } ?: JsonArray(emptyList())
        }
    return lists.mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        val flights = obj["flights"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return@mapNotNull null
        val legs = flights.mapNotNull { parseProviderLeg(it as? JsonObject ?: return@mapNotNull null) }
        if (legs.isEmpty()) return@mapNotNull null
        val layovers =
            obj["layovers"]?.let { runCatching { it.jsonArray }.getOrNull() }.orEmpty().mapNotNull { node ->
                val o = node as? JsonObject ?: return@mapNotNull null
                val mins = o.providerInt("duration") ?: return@mapNotNull null
                Layover(o.providerString("id") ?: o.providerString("name") ?: "", mins)
            }
        ParsedOffer(
            price = obj.providerDouble("price"),
            legs = legs,
            layovers = layovers,
            durationMinutes = obj.providerInt("total_duration"),
            departureToken = obj.providerString("departure_token"),
            bookingToken = obj.providerString("booking_token"),
            fareBrand = obj.providerString("fare_brand") ?: obj.providerString("fare_brand_name"),
        )
    }
}

private fun parseProviderLeg(obj: JsonObject): Leg? {
    val dep = obj["departure_airport"] as? JsonObject ?: return null
    val arr = obj["arrival_airport"] as? JsonObject ?: return null
    val (depDate, depTime) = providerStamp(dep)
    val (arrDate, arrTime) = providerStamp(arr)
    val number = obj.providerString("flight_number") ?: return null
    val airline = obj.providerString("airline") ?: ""
    val code = splitFlightCode(number, "").first.ifBlank { airline.take(2).uppercase() }
    // Preserve marketing name, but don't assert that the actual operator is verified.
    val operatorEvidence = obj.providerString("plane_and_crew_by")
    return Leg(
        flightNumber = number,
        airline = airline.ifBlank { AIRLINE_NAMES[code] ?: code },
        airlineCode = code,
        operatingCarrier = operatorEvidence ?: airline,
        from = dep.providerString("id") ?: return null,
        to = arr.providerString("id") ?: return null,
        departTime = depTime,
        arriveTime = arrTime,
        departDate = depDate,
        arriveDate = arrDate,
        durationMinutes = obj.providerInt("duration"),
        cabin = obj.providerString("travel_class"),
        aircraft = obj.providerString("airplane"),
        operatingCarrierVerified = !operatorEvidence.isNullOrBlank(),
    )
}

private fun providerStamp(airport: JsonObject): Pair<String, String> {
    val time = airport.providerString("time").orEmpty()
    val date = airport.providerString("date")
    if (time.length >= 16 && time[4] == '-') {
        return time.take(10) to normalizeTime(time.substring(11))
    }
    if (!date.isNullOrBlank()) return date.take(10) to normalizeTime(time)
    return "" to normalizeTime(time)
}

private fun JsonObject.providerString(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

private fun JsonObject.providerInt(key: String): Int? =
    this[key]?.jsonPrimitive?.intOrNull ?: this[key]?.jsonPrimitive?.doubleOrNull?.toInt()

private fun JsonObject.providerDouble(key: String): Double? =
    this[key]?.jsonPrimitive?.doubleOrNull ?: this[key]?.jsonPrimitive?.intOrNull?.toDouble()

/** Exact provider-marketing-flight split used by the existing JVM link builder. */
fun splitFlightCode(flightNumber: String, airlineCode: String): Pair<String, String> {
    val trimmed = flightNumber.trim()
    val parts = trimmed.split(Regex("\\s+"), limit = 2)
    if (parts.size == 2 && parts[0].any { it.isLetter() }) {
        return parts[0].uppercase() to parts[1]
    }
    val match = Regex("^([A-Za-z0-9]{2})(\\d+)$").find(trimmed.replace(" ", ""))
    if (match != null) return match.groupValues[1].uppercase() to match.groupValues[2]
    return airlineCode.uppercase() to trimmed
}
