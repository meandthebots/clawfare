package com.clawfare.search

/**
 * Every returned segment is checked independently. A requested search cabin
 * only constrains the provider query; it is not proof of the booked cabin.
 * Strict mode is opt-in for legacy data and should be enabled for premium-fare
 * decision workflows. Unknown fields fail closed in strict mode.
 */
object OfferEligibility {
    fun rejectionReason(
        itinerary: Itinerary,
        query: SearchQuery,
    ): String? {
        if (query.excludedAirlines.isNotEmpty() &&
            !Filters.matches(itinerary, FilterSpec(airlineBlock = query.excludedAirlines, hideMock = false))
        ) {
            return "excluded_carrier"
        }

        val transitRejection =
            TransitRules.rejectionReason(
                itinerary,
                query.excludedTransitAirports,
                query.excludedTransitCountries,
            )
        if (transitRejection != null) return transitRejection

        val directions = listOf(itinerary.outbound) + listOfNotNull(itinerary.returning)
        val legs = directions.flatMap { it.legs }
        if (legs.isEmpty() || directions.any { it.legs.isEmpty() }) return "missing_legs"
        if (query.tripType != "one_way" && itinerary.returnMissing &&
            (query.strictPerLegCabin || query.strictOperatingCarrier)
        ) {
            return "unverified_return_legs"
        }
        if (query.strictOperatingCarrier && legs.any { it.operatingCarrier.isBlank() }) return "unknown_operator"

        for (leg in legs) {
            val minimum = if (isShortHaul(leg)) query.cabinShortHaul else query.cabin
            if (query.strictPerLegCabin) {
                if (recognizedCabinKeyOf(leg.cabin) == null) return "unknown_cabin"
                if (!cabinAtLeast(leg.cabin, minimum)) return "insufficient_cabin"
            }
        }
        return null
    }

    /**
     * Classify explicit intra-European hops and observed <=4-hour flights as
     * short-haul, but never downgrade an Europe-to-non-Europe segment merely
     * because a provider reported an implausibly short duration.
     * An unknown duration outside Europe is conservatively long-haul.
     */
    fun isShortHaul(leg: Leg): Boolean {
        val fromEurope = isEuropeanAirport(leg.from)
        val toEurope = isEuropeanAirport(leg.to)
        if (fromEurope != toEurope) return false
        if (fromEurope && toEurope) return true
        return leg.durationMinutes != null && leg.durationMinutes in 1..240
    }
}
