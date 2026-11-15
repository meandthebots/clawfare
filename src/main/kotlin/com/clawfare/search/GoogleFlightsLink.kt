package com.clawfare.search

import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Google Flights `tfs` link for an exact itinerary.
 * Same protobuf layout the trip pages were built with: field 19 is 1 for a round trip and 2 for a one-way.
 * Seat codes: 1 economy, 2 premium economy, 3 business, 4 first.
 */
object GoogleFlightsLink {
    fun roundTrip(
        outboundDate: String,
        returnDate: String,
        from: String,
        to: String,
        cabin: String,
        outbound: List<Leg> = emptyList(),
        returning: List<Leg> = emptyList(),
        currency: String = "GBP",
        country: String = "uk",
    ): String {
        val bytes = protoRoundTrip(outboundDate, returnDate, from, to, seat(cabin), outbound, returning)
        return page(bytes, currency, country)
    }

    fun oneWay(
        date: String,
        from: String,
        to: String,
        cabin: String,
        legs: List<Leg> = emptyList(),
        currency: String = "GBP",
        country: String = "uk",
    ): String {
        val raw = ByteArrayOutputStream()
        raw.write(fld(1, 28))
        raw.write(fld(2, 2))
        raw.write(fld(3, legGroup(date, from, to, legs)))
        raw.write(fld(8, 1))
        raw.write(fld(9, seat(cabin)))
        raw.write(fld(14, 1))
        raw.write(fld(19, 2))
        return page(raw.toByteArray(), currency, country)
    }

    fun seat(cabin: String): Int =
        when (cabinKeyOf(cabin)) {
            "first" -> 4
            "business" -> 3
            "premium_economy" -> 2
            else -> 1
        }

    private fun page(
        bytes: ByteArray,
        currency: String,
        country: String,
    ): String {
        val tfs = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val cur = currency.uppercase().ifBlank { "GBP" }
        val gl = country.lowercase().ifBlank { "uk" }
        return "https://www.google.com/travel/flights?hl=en&gl=$gl&curr=$cur&tfs=$tfs&tfu=EgIIAQ"
    }

    private fun protoRoundTrip(
        outboundDate: String,
        returnDate: String,
        from: String,
        to: String,
        seat: Int,
        outbound: List<Leg>,
        returning: List<Leg>,
    ): ByteArray {
        val raw = ByteArrayOutputStream()
        raw.write(fld(1, 28))
        raw.write(fld(2, 2))
        raw.write(fld(3, legGroup(outboundDate, from, to, outbound)))
        raw.write(fld(3, legGroup(returnDate, to, from, returning)))
        raw.write(fld(8, 1))
        raw.write(fld(9, seat))
        raw.write(fld(14, 1))
        raw.write(fld(19, 1))
        return raw.toByteArray()
    }

    private fun legGroup(
        date: String,
        from: String,
        to: String,
        legs: List<Leg>,
    ): ByteArray {
        val raw = ByteArrayOutputStream()
        raw.write(fld(2, date))
        for (leg in legs) {
            val (carrier, number) = splitFlight(leg.flightNumber, leg.airlineCode)
            val seg = ByteArrayOutputStream()
            seg.write(fld(1, leg.from.ifBlank { from }))
            seg.write(fld(2, leg.departDate.ifBlank { date }))
            seg.write(fld(3, leg.to))
            seg.write(fld(5, carrier))
            seg.write(fld(6, number))
            raw.write(fld(4, seg.toByteArray()))
        }
        raw.write(fld(13, fld(1, 1) + fld(2, from)))
        raw.write(fld(14, fld(1, 1) + fld(2, to)))
        return raw.toByteArray()
    }

    fun splitFlight(
        flightNumber: String,
        airlineCode: String,
    ): Pair<String, String> {
        val trimmed = flightNumber.trim()
        val parts = trimmed.split(Regex("\\s+"), limit = 2)
        if (parts.size == 2 && parts[0].any { it.isLetter() }) {
            return parts[0].uppercase() to parts[1]
        }
        val m = Regex("^([A-Za-z0-9]{2})(\\d+)$").find(trimmed.replace(" ", ""))
        if (m != null) return m.groupValues[1].uppercase() to m.groupValues[2]
        return airlineCode.uppercase() to trimmed
    }

    private fun vint(value: Int): ByteArray {
        var n = value
        val out = ByteArrayOutputStream()
        while (true) {
            val b = n and 0x7f
            n = n ushr 7
            if (n != 0) {
                out.write(b or 0x80)
            } else {
                out.write(b)
                return out.toByteArray()
            }
        }
    }

    private fun fld(
        field: Int,
        value: Int,
    ): ByteArray = vint(field shl 3) + vint(value)

    private fun fld(
        field: Int,
        value: String,
    ): ByteArray = fld(field, value.encodeToByteArray())

    private fun fld(
        field: Int,
        value: ByteArray,
    ): ByteArray = vint((field shl 3) or 2) + vint(value.size) + value

    private operator fun ByteArray.plus(other: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(this)
        out.write(other)
        return out.toByteArray()
    }
}

fun linksFor(
    cabin: String,
    outbound: List<Leg>,
    returning: List<Leg>?,
    returnDate: String?,
    currency: String = "GBP",
    country: String = "uk",
): List<BookLink> {
    if (outbound.isEmpty()) return emptyList()
    val from = outbound.first().from
    val to = outbound.last().to
    val outDate = outbound.first().departDate
    val retDate = returning?.firstOrNull()?.departDate ?: returnDate
    val links = mutableListOf<BookLink>()
    if (retDate != null && !returning.isNullOrEmpty()) {
        links +=
            BookLink(
                "exact",
                "Exact itinerary on Google Flights",
                GoogleFlightsLink.roundTrip(outDate, retDate, from, to, cabin, outbound, returning, currency, country),
            )
    } else if (retDate != null) {
        links +=
            BookLink(
                "partial",
                "Outbound fixed on Google Flights (choose the return there)",
                GoogleFlightsLink.roundTrip(outDate, retDate, from, to, cabin, outbound, emptyList(), currency, country),
            )
    } else {
        links +=
            BookLink(
                "exact",
                "Exact itinerary on Google Flights",
                GoogleFlightsLink.oneWay(outDate, from, to, cabin, outbound, currency, country),
            )
    }
    if (retDate != null) {
        links +=
            BookLink(
                "search",
                "Search these dates on Google Flights",
                GoogleFlightsLink.roundTrip(outDate, retDate, from, to, cabin, currency = currency, country = country),
            )
    }
    val ordered = LinkedHashSet<String>()
    val all = outbound + (returning ?: emptyList())
    all.filter { isTransatlantic(it.from, it.to) }.forEach { ordered += it.airlineCode.uppercase() }
    all.forEach { ordered += it.airlineCode.uppercase() }
    for (code in ordered) {
        val site = AIRLINE_SITES[code] ?: continue
        links += BookLink("airline", "${site.first} website", site.second)
        if (links.count { it.kind == "airline" } >= 3) break
    }
    return links
}
