package com.clawfare.search

/** Shared itinerary direction, layover and European premium classifiers. */
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

