package com.clawfare.search

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

fun directionOf(
    legs: List<Leg>,
    apiLayovers: List<Layover> = emptyList(),
    apiDuration: Int? = null,
): Direction {
    val layovers = if (apiLayovers.isNotEmpty()) apiLayovers else computeLayovers(legs)
    return Direction(
        legs = legs,
        layovers = layovers,
        durationMinutes = apiDuration,
        stops = (legs.size - 1).coerceAtLeast(0),
    )
}

fun computeLayovers(legs: List<Leg>): List<Layover> {
    if (legs.size < 2) return emptyList()
    val out = mutableListOf<Layover>()
    for (i in 0 until legs.size - 1) {
        val minutes =
            minutesBetween(
                legs[i].arriveDate,
                legs[i].arriveTime,
                legs[i + 1].departDate,
                legs[i + 1].departTime,
            )
        if (minutes != null && minutes >= 0) out += Layover(legs[i].to, minutes)
    }
    return out
}

fun minutesBetween(
    date1: String,
    time1: String,
    date2: String,
    time2: String,
): Int? =
    try {
        val a = LocalDateTime.parse("${date1}T${normalizeTime(time1)}")
        val b = LocalDateTime.parse("${date2}T${normalizeTime(time2)}")
        Duration.between(a, b).toMinutes().toInt()
    } catch (_: Exception) {
        null
    }

fun normalizeTime(raw: String): String {
    val t = raw.trim().take(5)
    val parts = t.split(":")
    if (parts.size < 2) return "00:00"
    return parts[0].padStart(2, '0') + ":" + parts[1].padStart(2, '0')
}

fun nightsBetween(
    start: String?,
    end: String?,
): Int? {
    if (start.isNullOrBlank() || end.isNullOrBlank()) return null
    return try {
        ChronoUnit.DAYS.between(LocalDate.parse(start), LocalDate.parse(end)).toInt()
    } catch (_: Exception) {
        null
    }
}

fun euPremium(
    cabinKey: String,
    legs: List<Leg>,
): Boolean {
    if (cabinKey != "premium_economy") return false
    val transatlantic = legs.filter { isTransatlantic(it.from, it.to) }
    if (transatlantic.isEmpty()) return false
    return transatlantic.all { leg ->
        val premium =
            leg.cabin == null ||
                cabinKeyOf(leg.cabin) == "premium_economy" ||
                cabinKeyOf(leg.cabin) == "business" ||
                cabinKeyOf(leg.cabin) == "first"
        premium && (isEuropeanOperator(leg.operatingCarrier) || isEuropeanOperator(leg.airline) || isEuropeanOperator(leg.airlineCode))
    }
}

fun assembleItinerary(
    id: String,
    pricePerPerson: Double?,
    priceCurrency: String,
    passengers: Int,
    cabinRaw: String,
    outboundLegs: List<Leg>,
    returnLegs: List<Leg>?,
    returnMissing: Boolean,
    source: String,
    sourceLabel: String,
    livePrice: Boolean,
    fetchedAt: String?,
    prevPrice: Double? = null,
    notes: String = "",
    assumedReturn: Boolean = false,
    outboundDuration: Int? = null,
    returnDuration: Int? = null,
    apiLayoversOut: List<Layover> = emptyList(),
    apiLayoversRet: List<Layover> = emptyList(),
    returnDateHint: String? = null,
    linkCountry: String = "uk",
    linkCurrency: String = "GBP",
): Itinerary {
    val cabinKey = cabinKeyOf(cabinRaw)
    val outbound = directionOf(outboundLegs, apiLayoversOut, outboundDuration)
    val returning = if (returnLegs.isNullOrEmpty()) null else directionOf(returnLegs, apiLayoversRet, returnDuration)
    val allLegs = outboundLegs + (returnLegs ?: emptyList())
    val codes = LinkedHashSet<String>()
    allLegs.forEach {
        if (it.airlineCode.isNotBlank()) codes += it.airlineCode.uppercase()
    }
    val names = LinkedHashSet<String>()
    allLegs.forEach {
        val name = it.airline.ifBlank { AIRLINE_NAMES[it.airlineCode.uppercase()].orEmpty() }
        if (name.isNotBlank()) names += name
    }
    val layoverMinutes = outbound.layovers.map { it.minutes } + (returning?.layovers?.map { it.minutes } ?: emptyList())
    val travel =
        when {
            outbound.durationMinutes != null && returning == null -> outbound.durationMinutes
            outbound.durationMinutes != null && returning?.durationMinutes != null ->
                outbound.durationMinutes + returning.durationMinutes
            else -> null
        }
    val currency = priceCurrency.uppercase().ifBlank { "GBP" }
    val gbp = pricePerPerson?.let { Fx.toGbpOrNull(it, currency) }
    val outDate = outboundLegs.firstOrNull()?.departDate.orEmpty()
    val retDate = returnLegs?.firstOrNull()?.departDate ?: returnDateHint
    return Itinerary(
        id = id,
        pricePerPerson = gbp,
        priceOriginal = if (currency == "GBP") null else pricePerPerson,
        priceCurrency = currency,
        passengers = passengers,
        cabin = cabinLabelOf(cabinKey),
        cabinKey = cabinKey,
        euPremium = euPremium(cabinKey, allLegs),
        origin = outboundLegs.firstOrNull()?.from.orEmpty(),
        destination = outboundLegs.lastOrNull()?.to.orEmpty(),
        returnFrom = returnLegs?.firstOrNull()?.from,
        outboundDate = outDate,
        returnDate = retDate,
        nights = nightsBetween(outDate, retDate),
        airlineSummary = names.joinToString(" / "),
        outbound = outbound,
        returning = returning,
        returnMissing = returnMissing || returning == null && retDate != null,
        stopsOut = outbound.stops,
        stopsRet = returning?.stops,
        layoverMinutes = layoverMinutes,
        maxLayoverMinutes = layoverMinutes.maxOrNull(),
        travelMinutes = travel,
        outboundDepart = outboundLegs.firstOrNull()?.departTime?.let(::normalizeTime),
        returnDepart = returnLegs?.firstOrNull()?.departTime?.let(::normalizeTime),
        airlineCodes = codes.toList(),
        source = source,
        sourceLabel = sourceLabel,
        livePrice = livePrice,
        fetchedAt = fetchedAt,
        links = linksFor(cabinKey, outboundLegs, returnLegs, retDate, linkCurrency, linkCountry),
        prevPrice = prevPrice?.let { Fx.toGbpOrNull(it, "GBP") },
        notes = notes,
        assumedReturn = assumedReturn,
    )
}

object Filters {
    fun matches(
        it: Itinerary,
        f: FilterSpec,
    ): Boolean {
        if (f.hideMock && it.sourceLabel == "mock") return false
        val stops = if (it.stopsRet == null) it.stopsOut else maxOf(it.stopsOut, it.stopsRet)
        if (f.nonstopOnly && (it.stopsOut != 0 || it.returnMissing || it.stopsRet != 0)) return false
        if (f.maxStops != null && stops > f.maxStops) return false
        if (f.maxLayoverMinutes != null && it.layoverMinutes.any { mins -> mins > f.maxLayoverMinutes }) return false
        if (it.outboundDate in f.excludeOutboundDates) return false
        if (it.returnDate != null && it.returnDate in f.excludeReturnDates) return false
        if (f.europeanPremiumOnly && !it.euPremium) return false
        if (f.maxTravelMinutes != null && (it.travelMinutes == null || it.travelMinutes > f.maxTravelMinutes)) return false
        if (!f.outboundDepartAfter.isNullOrBlank() &&
            it.outboundDepart != null &&
            it.outboundDepart < normalizeTime(f.outboundDepartAfter)
        ) {
            return false
        }
        if (!f.outboundDepartBefore.isNullOrBlank() &&
            it.outboundDepart != null &&
            it.outboundDepart > normalizeTime(f.outboundDepartBefore)
        ) {
            return false
        }
        if (!f.returnDepartAfter.isNullOrBlank() &&
            it.returnDepart != null &&
            it.returnDepart < normalizeTime(f.returnDepartAfter)
        ) {
            return false
        }
        if (!f.cabin.isNullOrBlank() && f.cabin != "all" && it.cabinKey != cabinKeyOf(f.cabin)) return false
        if (f.nights != null && it.nights != f.nights) return false
        if (!f.origin.isNullOrBlank() && it.origin != f.origin.uppercase()) return false
        if (f.airlineAllow.isNotEmpty() && !allows(it, f.airlineAllow)) return false
        if (f.airlineBlock.isNotEmpty() && blocked(it, f.airlineBlock)) return false
        return true
    }

    /** Every leg must be operated or marketed by one of the allowed airlines. */
    private fun allows(
        it: Itinerary,
        allow: List<String>,
    ): Boolean {
        val tokens = allow.map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return true
        val legs = it.outbound.legs + (it.returning?.legs ?: emptyList())
        if (legs.isEmpty()) return tokens.any { token -> it.airlineSummary.contains(token, ignoreCase = true) }
        return legs.all { leg -> tokens.any { token -> carrierMatch(leg, token) } }
    }

    private fun blocked(
        it: Itinerary,
        block: List<String>,
    ): Boolean {
        val tokens = block.map { it.trim() }.filter { it.isNotEmpty() }
        val legs = it.outbound.legs + (it.returning?.legs ?: emptyList())
        if (legs.isEmpty()) return tokens.any { token -> it.airlineSummary.contains(token, ignoreCase = true) }
        return legs.any { leg -> tokens.any { token -> carrierMatch(leg, token) } }
    }

    private fun carrierMatch(
        leg: Leg,
        token: String,
    ): Boolean {
        if (token.equals(leg.airlineCode, ignoreCase = true)) return true
        if (token.equals(leg.airline, ignoreCase = true)) return true
        if (token.equals(leg.operatingCarrier, ignoreCase = true)) return true
        if (token.length > 2 &&
            (leg.airline.contains(token, ignoreCase = true) || leg.operatingCarrier.contains(token, ignoreCase = true))
        ) {
            return true
        }
        return false
    }

    /**
     * Short-haul (non-transatlantic) legs may sit in a lower cabin than the long-haul search.
     * A leg below the requested short-haul cabin is rejected. Unknown per-leg cabin is kept.
     */
    fun shortHaulAccept(
        it: Itinerary,
        shortHaulCabin: String,
    ): Boolean {
        val legs = it.outbound.legs + (it.returning?.legs ?: emptyList())
        return legs.filter { leg -> !isTransatlantic(leg.from, leg.to) }.all { leg ->
            leg.cabin.isNullOrBlank() || cabinAtLeast(leg.cabin, shortHaulCabin)
        }
    }
}

/**
 * Side-trip feasibility used by the Florida sample grid.
 * Mexico (the add-on) has to sit strictly inside the long-haul stay, and a pre-Christmas
 * add-on has to be back by Christmas Eve. Same-day overlap with the flight home does not work.
 */
object Feasibility {
    const val ANCHOR: String = "2026-12-24"

    fun evaluate(
        primary: Itinerary,
        addon: Itinerary?,
        primaryPassengers: Int = 2,
        addonPassengers: Int = 1,
    ): ComboCell {
        val no = mutableListOf<String>()
        val catches = mutableListOf<String>()
        val oks = mutableListOf<String>()
        val anchor = LocalDate.parse(ANCHOR)

        val ret = primary.returnDate?.let { LocalDate.parse(it) }
        val out = primary.outboundDate.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val arrive =
            primary.outbound.legs
                .lastOrNull()
                ?.arriveDate
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: out

        if (ret != null && !ret.isAfter(anchor)) {
            no += "Flies home on or before Christmas Eve (${primary.returnDate}) — both travellers need to be in Sarasota that day."
        }
        if (primary.origin == "LGW") catches += "Departs London Gatwick (LGW), not Heathrow."
        if (primary.returnMissing) {
            catches +=
                "Return leg was not retrieved — price is the round-trip ‘from’ fare, and the connection rules on the way home are unconfirmed."
        }
        if (out != null && arrive != null && arrive.isAfter(out)) {
            val where = if (primary.destination == "SRQ") "Sarasota" else "Tampa"
            catches += "Lands $where ${primary.outbound.legs.lastOrNull()?.arriveTime.orEmpty()} the day after departure."
        }

        if (addon != null) {
            val start = LocalDate.parse(addon.outboundDate)
            val end = addon.returnDate?.let { LocalDate.parse(it) }
            if (arrive != null && !start.isAfter(arrive)) {
                no +=
                    "Add-on leaves on ${addon.outboundDate}, but the long-haul only lands on $arrive — the side trip has to start the next day."
            }
            if (end != null && start.isBefore(anchor.plusDays(1)) && end.isAfter(anchor)) {
                no += "Pre-Christmas side trip returns ${addon.returnDate} — it has to be back in Florida by 24 Dec."
            }
            if (end != null && ret != null && end == ret) {
                no +=
                    "Side trip lands the same day the long-haul flies home (${primary.returnDate}) — it has to end strictly before that flight."
            } else if (end != null && ret != null && end.isAfter(ret)) {
                no += "Side trip ends ${addon.returnDate}, after the flight home on ${primary.returnDate}."
            }
            if (end != null && end == anchor && start.isBefore(anchor.plusDays(1))) {
                catches += "Lands back on Christmas Eve — a late arrival into Tampa, then the drive to Sarasota."
            }
        }

        if (no.isEmpty()) {
            if (addon != null) {
                oks += "Side trip starts ${addon.outboundDate}, after the long-haul lands."
                val end = addon.returnDate
                if (end != null && LocalDate.parse(addon.outboundDate).isBefore(anchor.plusDays(1))) {
                    oks += "Back in Florida on $end, in time for Christmas Eve."
                } else {
                    oks += "Together for Christmas Eve; the side trip leaves on Christmas Day or later."
                }
                if (primary.returnDate != null) oks += "Back before the flight home (${primary.returnDate})."
            } else if (primary.returnDate != null) {
                oks += "In Florida ${primary.outboundDate} → ${primary.returnDate}, covering Christmas Eve."
            }
        }

        val status =
            when {
                no.isNotEmpty() -> "no"
                catches.isNotEmpty() -> "catch"
                else -> "ok"
            }
        val primaryPrice = primary.pricePerPerson ?: 0.0
        val addonPrice = addon?.pricePerPerson ?: 0.0
        val total = primaryPrice * primaryPassengers + if (addon != null) addonPrice * addonPassengers else 0.0
        val prev =
            if (primary.prevPrice != null && (addon == null || addon.prevPrice != null)) {
                primary.prevPrice * primaryPassengers + (addon?.prevPrice ?: 0.0) * (if (addon != null) addonPassengers else 0)
            } else {
                null
            }
        return ComboCell(
            primaryId = primary.id,
            addonId = addon?.id,
            status = status,
            total = total,
            prevTotal = prev,
            reasonsNo = no,
            catches = catches,
            oks = oks,
        )
    }
}
